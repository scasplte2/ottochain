package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, DataState}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{CreateScript, InvokeScript, UpgradeScript}
import xyz.kd5ujc.schema.{CalculatedState, CommitIndex, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.{CommonRules, ScriptRules}

/**
 * Validators for script operations.
 *
 * Provides separate L1 and L0 validator classes that compose the rules
 * from ScriptRules and CommonRules.
 */
object ScriptValidator {

  /**
   * L1 Validator - Structural validations at Data-L1 layer.
   *
   * These validations run during API ingestion against the recreated [[CommitIndex]] (OnChain v2
   * carries only per-batch deltas — onchain-incrementals RFC §3.3).
   *
   * @param index The recreated cumulative commit index for existence/sequence checks
   */
  class L1Validator[F[_]: Monad](index: CommitIndex) {

    /** Validates a CreateScript update */
    def createScript(update: CreateScript): F[ValidationResult] =
      for {
        cidCheck       <- CommonRules.cidNotUsed(update.fiberId, index)
        initialStateOk <- CommonRules.isMapValueOrNull(update.initialState, "initialState")
        scriptDepthOk <- CommonRules.expressionWithinDepthLimit(
          update.scriptProgram,
          "scriptProgram",
          Limits.MaxExpressionDepth
        )
        initialStateSizeOk <- update.initialState.fold(
          ().validNec[io.constellationnetwork.currency.dataApplication.DataApplicationValidationError].pure[F]
        ) { value =>
          CommonRules.valueWithinSizeLimit(value, Limits.MaxInitialDataBytes, "initialState")
        }
      } yield List(cidCheck, initialStateOk, scriptDepthOk, initialStateSizeOk).combineAll

    /** Validates an InvokeScript update */
    def invokeScript(update: InvokeScript): F[ValidationResult] =
      for {
        cidExists     <- CommonRules.cidIsFound(update.fiberId, index)
        seqNumOk      <- ScriptRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, index)
        argsStructure <- CommonRules.payloadStructureValid(update.args, "args")
        argsSize <- CommonRules.valueWithinSizeLimit(
          update.args,
          Limits.MaxEventPayloadBytes,
          "args"
        )
      } yield List(cidExists, seqNumOk, argsStructure, argsSize).combineAll

    /** Validates an UpgradeScript update (structural: script exists, new program depth, sequence) */
    def upgradeScript(update: UpgradeScript): F[ValidationResult] =
      for {
        cidExists <- CommonRules.cidIsFound(update.fiberId, index)
        seqNumOk  <- ScriptRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, index)
        programOk <- CommonRules.expressionWithinDepthLimit(update.newProgram, "newProgram", Limits.MaxExpressionDepth)
      } yield List(cidExists, seqNumOk, programOk).combineAll
  }

  /**
   * L0 Validator - Contextual validations at Metagraph-L0 layer.
   *
   * These validations run with full DataState and signature proofs available.
   *
   * @param state  The full DataState (OnChain + CalculatedState)
   * @param proofs The signature proofs from the signed update
   */
  class L0Validator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {

    /**
     * Validates a CreateScript update (L0 specific checks).
     *
     * NO-OP at the block-acceptance gate. The former `RegistryRules.L0.scriptRefResolvesAndMatches` read the
     * registry version lineage (`state.registry.get(name)` -> `lineage.resolve(...)`), which a concurrent
     * third-party publish/yank can flip between DL1 block formation and ML0 re-validation. An `Invalid` here
     * drops the ENTIRE DL1 block (tessellation all-or-nothing) — CLAUDE.md rule #3 / audit C3. The ref+hash
     * bind is re-verified GRACEFULLY in `ScriptCombiner.resolveScriptBinding` (CombineRejected ->
     * RejectionReceipt), so nothing is lost.
     */
    def createScript(update: CreateScript): F[ValidationResult] =
      ().validNec[DataApplicationValidationError].pure[F]

    /** Validates an InvokeScript update (L0 specific checks) — immutable access-control policy only */
    def invokeScript(update: InvokeScript): F[ValidationResult] =
      ScriptRules.L0.accessControlCheck(update.fiberId, proofs, state.calculated)

    /**
     * Validates an UpgradeScript update (L0): ONLY the immutable-auth owner-signature gate.
     *
     * `scriptSignedByOwners` reads `owners`, fixed at creation (TOCTOU-safe), and the combiner does NOT
     * re-check upgrade signers — so this gate MUST stay at ML0. Everything else was removed as a
     * block-poisoning hazard and is re-enforced GRACEFULLY in `ScriptCombiner.upgradeScript` (exact-sequence
     * + same-package + verified re-bind, all CombineRejected):
     *   - `scriptIsActive` — mutable status a concurrent same-script update can flip (audit M1);
     *   - `bindingNameMatchesScript` — reads the script's current (mutable) binding name;
     *   - `scriptRefResolvesAndMatches` — the registry lineage read (audit C3, CLAUDE.md rule #3) a
     *     concurrent same-package publish/yank can flip.
     */
    def upgradeScript(update: UpgradeScript): F[ValidationResult] =
      ScriptRules.L0.scriptSignedByOwners(update.fiberId, proofs, state.calculated)
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

    /** Validates a CreateScript update (all checks) */
    def createScript(update: CreateScript): F[ValidationResult] =
      for {
        l1Result <- l1.createScript(update)
        l0Result <- l0.createScript(update)
      } yield l1Result |+| l0Result

    /**
     * Validates an InvokeScript update at the ML0 block-acceptance gate: structural L1 checks (existence,
     * args structure/size) MINUS the mutable `ScriptRules.L1.sequenceNumberMatches` (audit M1: a concurrent
     * same-script invoke bumps the sequence and flips this Valid->Invalid, poisoning the whole block; the
     * combiner does the exact-sequence check + atomic bump as CombineRejected, preserving replay protection)
     * PLUS the immutable-auth access-control check (in `l0.invokeScript`). The DL1 `L1Validator.invokeScript`
     * path keeps the sequence pre-filter — DL1 rejection does not poison an ML0 block.
     */
    def invokeScript(update: InvokeScript): F[ValidationResult] =
      for {
        cidExists     <- CommonRules.cidIsFound(update.fiberId, index)
        argsStructure <- CommonRules.payloadStructureValid(update.args, "args")
        argsSize      <- CommonRules.valueWithinSizeLimit(update.args, Limits.MaxEventPayloadBytes, "args")
        l0Result      <- l0.invokeScript(update)
      } yield List(cidExists, argsStructure, argsSize, l0Result).combineAll

    /**
     * Validates an UpgradeScript update at the ML0 block-acceptance gate: structural L1 checks (existence,
     * new-program depth) MINUS the mutable `ScriptRules.L1.sequenceNumberMatches` (audit M1, same rationale
     * as `invokeScript`) PLUS the immutable-auth owner-signature check (in `l0.upgradeScript`).
     */
    def upgradeScript(update: UpgradeScript): F[ValidationResult] =
      for {
        cidExists <- CommonRules.cidIsFound(update.fiberId, index)
        programOk <- CommonRules.expressionWithinDepthLimit(update.newProgram, "newProgram", Limits.MaxExpressionDepth)
        l0Result  <- l0.upgradeScript(update)
      } yield List(cidExists, programOk, l0Result).combineAll
  }
}
