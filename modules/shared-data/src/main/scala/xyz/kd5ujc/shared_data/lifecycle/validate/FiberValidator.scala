package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{ArchiveStateMachine, CreateStateMachine, TransitionStateMachine, UpgradeFiber}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
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
   * These validations run during API ingestion with only OnChain state available.
   * They check structural validity without needing signature proofs or CalculatedState.
   *
   * @param state The current OnChain state for existence checks
   */
  class L1Validator[F[_]: Monad](state: OnChain) {

    /** Validates a CreateStateMachineFiber update */
    def createFiber(update: CreateStateMachine): F[ValidationResult] =
      for {
        cidCheck       <- CommonRules.cidNotUsed(update.fiberId, state)
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
        parentExists <- FiberRules.L1.parentFiberExistsInOnChain(update.parentFiberId, state)
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
        cidExists      <- CommonRules.cidIsFound(update.fiberId, state)
        seqNumOk       <- FiberRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, state)
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
        cidExists <- CommonRules.cidIsFound(update.fiberId, state)
        seqNumOk  <- FiberRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, state)
      } yield List(cidExists, seqNumOk).combineAll

    /** Validates an UpgradeFiber update (structural: fiber exists, new definition valid, sequence) */
    def upgrade(update: UpgradeFiber): F[ValidationResult] =
      for {
        cidExists     <- CommonRules.cidIsFound(update.fiberId, state)
        definitionOk  <- FiberRules.L1.validStateMachineDefinition(update.newDefinition)
        limitsOk      <- FiberRules.L1.definitionWithinLimits(update.newDefinition)
        expressionsOk <- FiberRules.L1.definitionExpressionsWithinDepthLimits(update.newDefinition)
        reservedOk    <- FiberRules.L1.noReservedOperatorFieldNames(update.newDefinition)
        seqNumOk      <- FiberRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, state)
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

    /** Validates a ProcessFiberEvent update (L0 specific checks) */
    def processEvent(update: TransitionStateMachine): F[ValidationResult] =
      for {
        fiberActive <- FiberRules.L0.fiberIsActive(update.fiberId, state.calculated)
        // Relaxed: owners OR authorized participants (declared in CreateStateMachine.participants)
        signedOk     <- FiberRules.L0.updateSignedByOwnerOrParticipant(update.fiberId, proofs, state.calculated)
        transitionOk <- FiberRules.L0.transitionExists(update.fiberId, update.eventName, state.calculated)
      } yield List(fiberActive, signedOk, transitionOk).combineAll

    /** Validates an ArchiveFiber update (L0 specific checks) */
    def archiveFiber(update: ArchiveStateMachine): F[ValidationResult] =
      for {
        fiberActive   <- FiberRules.L0.fiberIsActive(update.fiberId, state.calculated)
        signedByOwner <- FiberRules.L0.updateSignedByOwners(update.fiberId, proofs, state.calculated)
      } yield List(fiberActive, signedByOwner).combineAll

    /** Validates an UpgradeFiber update (active, owner, same-package re-bind + verified hash, state preserved) */
    def upgrade(update: UpgradeFiber): F[ValidationResult] =
      for {
        fiberActive   <- FiberRules.L0.fiberIsActive(update.fiberId, state.calculated)
        signedByOwner <- FiberRules.L0.updateSignedByOwners(update.fiberId, proofs, state.calculated)
        bindingOk     <- FiberRules.L0.bindingNameMatches(update.fiberId, update.targetRef.name, state.calculated)
        stateOk       <- FiberRules.L0.currentStateInDefinition(update.fiberId, update.newDefinition, state.calculated)
        // version-compat-family §3.5: fail-fast mirror of the cheap, signer-independent UpgradeGate checks
        // (Immutable rejection + tighten-only). The engine UpgradeGate stays the authority and re-runs all.
        policyOk <- FiberRules.L0.upgradePolicyPermits(update.fiberId, update.newDefinition, state.calculated)
      } yield List(fiberActive, signedByOwner, bindingOk, stateOk, policyOk).combineAll
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
    private val l1 = new L1Validator[F](state.onChain)
    private val l0 = new L0Validator[F](state, proofs)

    /** Validates a CreateStateMachineFiber update (all checks) */
    def createFiber(update: CreateStateMachine): F[ValidationResult] =
      for {
        l1Result <- l1.createFiber(update)
        l0Result <- l0.createFiber(update)
      } yield l1Result |+| l0Result

    /** Validates a ProcessFiberEvent update (all checks) */
    def processEvent(update: TransitionStateMachine): F[ValidationResult] =
      for {
        l1Result <- l1.processEvent(update)
        l0Result <- l0.processEvent(update)
      } yield l1Result |+| l0Result

    /** Validates an ArchiveFiber update (all checks) */
    def archiveFiber(update: ArchiveStateMachine): F[ValidationResult] =
      for {
        l1Result <- l1.archiveFiber(update)
        l0Result <- l0.archiveFiber(update)
      } yield l1Result |+| l0Result

    /** Validates an UpgradeFiber update (all checks) */
    def upgrade(update: UpgradeFiber): F[ValidationResult] =
      for {
        l1Result <- l1.upgrade(update)
        l0Result <- l0.upgrade(update)
      } yield l1Result |+| l0Result
  }
}
