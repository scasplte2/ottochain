package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{CreateScript, InvokeScript, UpgradeScript}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.{CommonRules, RegistryRules, ScriptRules}

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
   * These validations run during API ingestion with only OnChain state available.
   *
   * @param state The current OnChain state for existence checks
   */
  class L1Validator[F[_]: Monad](state: OnChain) {

    /** Validates a CreateScript update */
    def createScript(update: CreateScript): F[ValidationResult] =
      for {
        cidCheck       <- CommonRules.cidNotUsed(update.fiberId, state)
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
        cidExists     <- CommonRules.cidIsFound(update.fiberId, state)
        seqNumOk      <- ScriptRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, state)
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
        cidExists <- CommonRules.cidIsFound(update.fiberId, state)
        seqNumOk  <- ScriptRules.L1.sequenceNumberMatches(update.fiberId, update.targetSequenceNumber, state)
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

    /** Validates a CreateScript update (L0 specific checks) */
    def createScript(update: CreateScript): F[ValidationResult] =
      RegistryRules.L0.scriptRefResolvesAndMatches(update.schemaRef, update.scriptProgram, state.calculated)

    /** Validates an InvokeScript update (L0 specific checks) */
    def invokeScript(update: InvokeScript): F[ValidationResult] =
      ScriptRules.L0.accessControlCheck(update.fiberId, proofs, state.calculated)

    /** Validates an UpgradeScript update (active, owner, same-package re-bind + verified hash) */
    def upgradeScript(update: UpgradeScript): F[ValidationResult] =
      for {
        scriptActive  <- ScriptRules.L0.scriptIsActive(update.fiberId, state.calculated)
        signedByOwner <- ScriptRules.L0.scriptSignedByOwners(update.fiberId, proofs, state.calculated)
        bindingOk <- ScriptRules.L0.bindingNameMatchesScript(update.fiberId, update.targetRef.name, state.calculated)
        targetOk <- RegistryRules.L0.scriptRefResolvesAndMatches(
          Some(update.targetRef),
          update.newProgram,
          state.calculated
        )
      } yield List(scriptActive, signedByOwner, bindingOk, targetOk).combineAll
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

    /** Validates a CreateScript update (all checks) */
    def createScript(update: CreateScript): F[ValidationResult] =
      for {
        l1Result <- l1.createScript(update)
        l0Result <- l0.createScript(update)
      } yield l1Result |+| l0Result

    /** Validates an InvokeScript update (all checks) */
    def invokeScript(update: InvokeScript): F[ValidationResult] =
      for {
        l1Result <- l1.invokeScript(update)
        l0Result <- l0.invokeScript(update)
      } yield l1Result |+| l0Result

    /** Validates an UpgradeScript update (all checks) */
    def upgradeScript(update: UpgradeScript): F[ValidationResult] =
      for {
        l1Result <- l1.upgradeScript(update)
        l0Result <- l0.upgradeScript(update)
      } yield l1Result |+| l0Result
  }
}
