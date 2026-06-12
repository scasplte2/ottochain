package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, DataState}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{PublishMachineVersion, PublishScriptVersion, RegisterAlias, SetVersionStatus}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.{FiberRules, RegistryRules}

/**
 * Validators for registry operations, composing [[RegistryRules]] (mirrors `FiberValidator`).
 *
 * L1 is structural (base64, non-empty, size); L0 adds ownership + an invariant preview against
 * CalculatedState.registry. The RegistryCombiner still enforces the invariants authoritatively at combine.
 */
object RegistryValidator {

  /** L1 structural checks (no state needed). */
  class L1Validator[F[_]: Monad] {

    def publishMachineVersion(update: PublishMachineVersion): F[ValidationResult] =
      for {
        schemaOk     <- RegistryRules.L1.validBase64("schemaB64", update.schemaB64)
        shapeOk      <- RegistryRules.L1.machineShapeWellFormed(update.machineShape)
        bundleOk     <- RegistryRules.L1.bundleWithinLimitMachine(update)
        reservedOk   <- RegistryRules.L1.notReserved(update.name)
        metaOk       <- RegistryRules.L1.metadataConforms(update.metadata.getOrElse(Map.empty[String, String]))
        definitionOk <- FiberRules.L1.validStateMachineDefinition(update.definition)
        limitsOk     <- FiberRules.L1.definitionWithinLimits(update.definition)
      } yield List(schemaOk, shapeOk, bundleOk, reservedOk, metaOk, definitionOk, limitsOk).combineAll

    def setStatus(update: SetVersionStatus): F[ValidationResult] = {
      val _ = update
      ().validNec[DataApplicationValidationError].pure[F]
    }

    def publishScriptVersion(update: PublishScriptVersion): F[ValidationResult] =
      for {
        schemaOk   <- RegistryRules.L1.validBase64("schemaB64", update.schemaB64)
        bundleOk   <- RegistryRules.L1.bundleWithinLimitScript(update)
        reservedOk <- RegistryRules.L1.notReserved(update.name)
        metaOk     <- RegistryRules.L1.metadataConforms(update.metadata.getOrElse(Map.empty[String, String]))
      } yield List(schemaOk, bundleOk, reservedOk, metaOk).combineAll

    def registerAlias(update: RegisterAlias): F[ValidationResult] =
      for {
        tldOk      <- RegistryRules.L1.aliasTldIsFiber(update.name)
        reservedOk <- RegistryRules.L1.notReserved(update.name)
        metaOk     <- RegistryRules.L1.metadataConforms(update.metadata.getOrElse(Map.empty[String, String]))
      } yield List(tldOk, reservedOk, metaOk).combineAll
  }

  /** L0 contextual checks (ownership + invariant preview). */
  class L0Validator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {

    def publishMachineVersion(update: PublishMachineVersion): F[ValidationResult] =
      for {
        authd      <- RegistryRules.L0.authorizedPublisher(update.name, proofs, state.calculated)
        appendable <- RegistryRules.L0.versionAppendable(update.name, update.version, state.calculated)
      } yield authd |+| appendable

    def setStatus(update: SetVersionStatus): F[ValidationResult] =
      for {
        authd <- RegistryRules.L0.authorizedForExisting(update.name, proofs, state.calculated)
        legal <- RegistryRules.L0.statusTransitionLegal(update.name, update.version, update.status, state.calculated)
      } yield authd |+| legal

    def publishScriptVersion(update: PublishScriptVersion): F[ValidationResult] =
      for {
        authd      <- RegistryRules.L0.authorizedPublisher(update.name, proofs, state.calculated)
        appendable <- RegistryRules.L0.versionAppendable(update.name, update.version, state.calculated)
      } yield authd |+| appendable

    def registerAlias(update: RegisterAlias): F[ValidationResult] =
      for {
        kindOk  <- RegistryRules.L0.aliasTargetIsKind(update.name, update.targetFiberId, state.calculated)
        ownerOk <- RegistryRules.L0.signerOwnsAliasTarget(update.targetFiberId, proofs, state.calculated)
        nameOk  <- RegistryRules.L0.aliasNameAvailable(update.name, proofs, state.calculated)
      } yield List(kindOk, ownerOk, nameOk).combineAll
  }

  /**
   * Advisory pre-validation only — MUST NOT be used from `validateSignedUpdate`.
   * Registry stateful checks (lineage, ownership) in validateSignedUpdate cause block-poisoning:
   * a TOCTOU race returns Invalid → the entire block is dropped. See Validator.scala and CLAUDE.md.
   * Use only for L0 node pre-screening during block BUILDING, never block ACCEPTANCE.
   */
  class CombinedValidator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {
    private val l1 = new L1Validator[F]
    private val l0 = new L0Validator[F](state, proofs)

    def publishMachineVersion(update: PublishMachineVersion): F[ValidationResult] =
      for {
        l1Result <- l1.publishMachineVersion(update)
        l0Result <- l0.publishMachineVersion(update)
      } yield l1Result |+| l0Result

    def setStatus(update: SetVersionStatus): F[ValidationResult] =
      for {
        l1Result <- l1.setStatus(update)
        l0Result <- l0.setStatus(update)
      } yield l1Result |+| l0Result

    def publishScriptVersion(update: PublishScriptVersion): F[ValidationResult] =
      for {
        l1Result <- l1.publishScriptVersion(update)
        l0Result <- l0.publishScriptVersion(update)
      } yield l1Result |+| l0Result

    def registerAlias(update: RegisterAlias): F[ValidationResult] =
      for {
        l1Result <- l1.registerAlias(update)
        l0Result <- l0.registerAlias(update)
      } yield l1Result |+| l0Result
  }
}
