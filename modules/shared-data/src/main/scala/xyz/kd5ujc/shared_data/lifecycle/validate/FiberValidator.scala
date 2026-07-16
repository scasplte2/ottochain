package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{ArchiveStateMachine, CreateStateMachine, TransitionStateMachine, UpgradeFiber}
import xyz.kd5ujc.schema.{CalculatedState, CommitIndex, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.{CommonRules, FiberRules}

/**
 * Validators for state machine fiber operations.
 *
 * Provides separate L1 and L0 validator classes that compose the rules
 * from FiberRules and CommonRules.
 */
object FiberValidator {

  /**
   * L1 Validator - Structural validations at Data-L1 layer.
   *
   * These validations run during API ingestion against the recreated [[CommitIndex]] (OnChain v2
   * carries only per-batch deltas; the DL1 folds/heals them into the index — onchain-incrementals
   * RFC §3.3). They check structural validity without needing signature proofs or CalculatedState.
   *
   * @param index The recreated cumulative commit index for existence/sequence checks
   */
  class L1Validator[F[_]: Monad](index: CommitIndex) {

    /** Validates a CreateStateMachineFiber update */
    def createFiber(update: CreateStateMachine): F[ValidationResult] =
      for {
        cidCheck       <- CommonRules.cidNotUsed(update.fiberId, index)
        definitionOk   <- FiberRules.L1.validStateMachineDefinition(update.definition)
        limitsOk       <- FiberRules.L1.definitionWithinLimits(update.definition)
        expressionsOk  <- FiberRules.L1.definitionExpressionsWithinDepthLimits(update.definition)
        reservedOk     <- FiberRules.L1.noReservedOperatorFieldNames(update.definition)
        initialDataMap <- CommonRules.isMapValue(update.initialData, "initialData")
        initialDataSize <- CommonRules.valueWithinSizeLimit(
          update.initialData,
          Limits.MaxInitialDataBytes,
          "initialData"
        )
        parentExists <- FiberRules.L1.parentFiberExistsInOnChain(update.parentFiberId, index)
      } yield List(
        cidCheck,
        definitionOk,
        limitsOk,
        expressionsOk,
        reservedOk,
        initialDataMap,
        initialDataSize,
        parentExists
      ).combineAll

    /** Validates a ProcessFiberEvent update */
    def processEvent(update: TransitionStateMachine): F[ValidationResult] =
      for {
        cidExists      <- CommonRules.cidIsFound(update.fiberId, index)
        seqNumOk       <- FiberRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, index)
        payloadNotNull <- CommonRules.isNotNull(update.payload, "payload")
        payloadSize <- CommonRules.valueWithinSizeLimit(
          update.payload,
          Limits.MaxEventPayloadBytes,
          "payload"
        )
        payloadStructure <- CommonRules.payloadStructureValid(update.payload, "payload")
      } yield List(cidExists, seqNumOk, payloadNotNull, payloadSize, payloadStructure).combineAll

    /** Validates an ArchiveFiber update */
    def archiveFiber(update: ArchiveStateMachine): F[ValidationResult] =
      for {
        cidExists <- CommonRules.cidIsFound(update.fiberId, index)
        seqNumOk  <- FiberRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, index)
      } yield List(cidExists, seqNumOk).combineAll

    /** Validates an UpgradeFiber update (structural: fiber exists, new definition valid, sequence) */
    def upgrade(update: UpgradeFiber): F[ValidationResult] =
      for {
        cidExists     <- CommonRules.cidIsFound(update.fiberId, index)
        definitionOk  <- FiberRules.L1.validStateMachineDefinition(update.newDefinition)
        limitsOk      <- FiberRules.L1.definitionWithinLimits(update.newDefinition)
        expressionsOk <- FiberRules.L1.definitionExpressionsWithinDepthLimits(update.newDefinition)
        reservedOk    <- FiberRules.L1.noReservedOperatorFieldNames(update.newDefinition)
        seqNumOk      <- FiberRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, index)
      } yield List(cidExists, definitionOk, limitsOk, expressionsOk, reservedOk, seqNumOk).combineAll
  }

  /**
   * L0 Validator - Contextual validations at Metagraph-L0 layer.
   *
   * These validations run with full DataState and signature proofs available.
   * They check business logic like ownership, status, and transition validity.
   *
   * @param state  The full DataState (OnChain + CalculatedState)
   * @param proofs The signature proofs from the signed update
   */
  class L0Validator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {

    /** Validates a CreateStateMachineFiber update (L0 specific checks) */
    def createFiber(update: CreateStateMachine): F[ValidationResult] =
      for {
        hasProofs    <- FiberRules.L0.hasProofs(proofs)
        parentActive <- FiberRules.L0.parentFiberActive(update.parentFiberId, state.calculated)
      } yield List(hasProofs, parentActive).combineAll

    // NOTE: a ProcessFiberEvent has NO L0 (signature) gate. The transition-signer authorization moved wholly
    // to the combiner's F7 gate (`FiberCombiner.processFiberEvent` → `TransitionPolicy.authorizes`) as a
    // graceful `CombineRejected` (#205). It was the one block-acceptance check with no DL1 non-fatal pre-filter
    // behind it (DL1 has no proofs), so a non-owner transition reached ML0, went Invalid, and dropped the whole
    // all-or-nothing block; and making it `transitionPolicy`-aware would require reading the upgrade-MUTABLE
    // `definition.policy` at block-acceptance (TOCTOU → block poison, CLAUDE.md rule #3). Archive/Upgrade keep
    // their L0 owner gate because those are `owners`-only (immutable, no policy dial) and TOCTOU-safe.

    /**
     * Validates an ArchiveFiber update (L0): ONLY the immutable-auth owner-signature gate. The mutable
     * `fiberIsActive` check was removed (audit M1) — a concurrent archive flips it and poisons the block;
     * the combiner is authoritative (exact-sequence gated, and re-archiving is a graceful outcome).
     */
    def archiveFiber(update: ArchiveStateMachine): F[ValidationResult] =
      FiberRules.L0.updateSignedByOwners(update.fiberId, proofs, state.calculated)

    /**
     * Validates an UpgradeFiber update (L0): ONLY the immutable-auth owner-signature gate.
     *
     * `updateSignedByOwners` reads `owners`, fixed at creation (TOCTOU-safe), and the combiner does NOT
     * re-check upgrade signers — so this gate MUST stay at ML0. The former fail-fast mirrors of the engine
     * UpgradeGate — same-package (`bindingNameMatches`), current-state-preserved (`currentStateInDefinition`)
     * and tighten-only (`upgradePolicyPermits`) — were REMOVED (audit M1 residual): each reads mutable
     * `CalculatedState.stateMachines`, so a concurrent same-fiber update (an archive, a competing upgrade that
     * advances/re-binds, a sequence bump) can flip them Valid->Invalid between DL1 block formation and ML0
     * re-validation, dropping the ENTIRE block (tessellation all-or-nothing — the same-fiber sibling of C3).
     * All three are re-enforced GRACEFULLY downstream as CombineRejected / abort -> RejectionReceipt:
     * same-package by `FiberCombiner.upgradeFiber` (`b.name === targetRef.name`), current-state-preserved by
     * the engine `migrateStateMachineGated` (`newDefinition.states.contains(currentState)` false ->
     * ValidationFailed), and tighten-only / Immutable / Governed / AppendOnly by the engine `UpgradeGate.check`.
     * The engine + combiner remain the authority. The DL1 `L1Validator.upgrade` path is unchanged — a DL1
     * rejection does not poison a block.
     */
    def upgrade(update: UpgradeFiber): F[ValidationResult] =
      FiberRules.L0.updateSignedByOwners(update.fiberId, proofs, state.calculated)
  }

  /**
   * Combined validator that runs both L1 and L0 validations.
   *
   * Used at the L0 layer where we have full state and need to run all validations.
   */
  class CombinedValidator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {
    // OnChain v2 carries only this batch's delta — the cumulative maps the structural checks need
    // live in CalculatedState (same triple, same freshness as the v1 OnChain read; RFC §3.2).
    private val index = CommitIndex.fromCalculated(state.calculated)
    private val l1 = new L1Validator[F](index)
    private val l0 = new L0Validator[F](state, proofs)

    /** Validates a CreateStateMachineFiber update (all checks) */
    def createFiber(update: CreateStateMachine): F[ValidationResult] =
      for {
        l1Result <- l1.createFiber(update)
        l0Result <- l0.createFiber(update)
      } yield l1Result |+| l0Result

    /**
     * Validates a ProcessFiberEvent update at the ML0 block-acceptance gate: STRUCTURAL checks only (existence,
     * payload not-null / size / structure) MINUS the mutable `FiberRules.L1.sequenceNumberMatches` (audit M1:
     * a concurrent same-fiber update bumps the sequence and flips this Valid->Invalid, poisoning the whole
     * block; the combiner does the exact-sequence check + atomic bump as CombineRejected, preserving replay
     * protection). The transition-signer gate was REMOVED from block-acceptance (#205): it is the ONE
     * block-acceptance check with no DL1 non-fatal pre-filter behind it (DL1 has no proofs), so a non-owner
     * transition reached ML0, went Invalid, and dropped the ENTIRE block; and it could not honour
     * `transitionPolicy=Open` without reading the upgrade-mutable `definition.policy` at block-acceptance
     * (TOCTOU → poison, rule #3). Signer authorization is now wholly the combiner's F7 gate
     * (`TransitionPolicy.authorizes`), a graceful `CombineRejected`. The DL1 `L1Validator.processEvent` path
     * keeps the sequence pre-filter — DL1 rejection does not poison a block.
     */
    def processEvent(update: TransitionStateMachine): F[ValidationResult] =
      for {
        cidExists        <- CommonRules.cidIsFound(update.fiberId, index)
        payloadNotNull   <- CommonRules.isNotNull(update.payload, "payload")
        payloadSize      <- CommonRules.valueWithinSizeLimit(update.payload, Limits.MaxEventPayloadBytes, "payload")
        payloadStructure <- CommonRules.payloadStructureValid(update.payload, "payload")
      } yield List(cidExists, payloadNotNull, payloadSize, payloadStructure).combineAll

    /**
     * Validates an ArchiveFiber update at the ML0 block-acceptance gate: existence check MINUS the mutable
     * `FiberRules.L1.sequenceNumberMatches` (audit M1) PLUS the immutable-auth owner-signature check (in
     * `l0.archiveFiber`).
     */
    def archiveFiber(update: ArchiveStateMachine): F[ValidationResult] =
      for {
        cidExists <- CommonRules.cidIsFound(update.fiberId, index)
        l0Result  <- l0.archiveFiber(update)
      } yield List(cidExists, l0Result).combineAll

    /**
     * Validates an UpgradeFiber update at the ML0 block-acceptance gate: structural L1 checks (existence,
     * new-definition structure/limits/depth/reserved-keys) MINUS the mutable `FiberRules.L1.sequenceNumberMatches`
     * (audit M1) PLUS the L0 immutable-auth owner-signature gate (in `l0.upgrade`). The former fail-fast
     * UpgradeGate mirrors (same-package / current-state / tighten-only) were REMOVED from this gate — they read
     * mutable `stateMachines` and are re-enforced gracefully by the engine + combiner (audit M1 residual; see
     * `l0.upgrade`).
     */
    def upgrade(update: UpgradeFiber): F[ValidationResult] =
      for {
        cidExists     <- CommonRules.cidIsFound(update.fiberId, index)
        definitionOk  <- FiberRules.L1.validStateMachineDefinition(update.newDefinition)
        limitsOk      <- FiberRules.L1.definitionWithinLimits(update.newDefinition)
        expressionsOk <- FiberRules.L1.definitionExpressionsWithinDepthLimits(update.newDefinition)
        reservedOk    <- FiberRules.L1.noReservedOperatorFieldNames(update.newDefinition)
        l0Result      <- l0.upgrade(update)
      } yield List(cidExists, definitionOk, limitsOk, expressionsOk, reservedOk, l0Result).combineAll
  }
}
