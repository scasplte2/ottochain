package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, DataState}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{PublishVersion, RegisterAlias, SetVersionStatus}
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

    def publish(update: PublishVersion): F[ValidationResult] =
      for {
        schemaOk     <- RegistryRules.L1.validBase64("schemaB64", update.schemaB64)
        shapeOk      <- RegistryRules.L1.schemaShapeWellFormed(update.schemaShape)
        bundleOk     <- RegistryRules.L1.bundleWithinLimit(update)
        reservedOk   <- RegistryRules.L1.notReserved(update.name)
        metaOk       <- RegistryRules.L1.metadataConforms(update.metadata)
        definitionOk <- FiberRules.L1.validStateMachineDefinition(update.definition)
        limitsOk     <- FiberRules.L1.definitionWithinLimits(update.definition)
      } yield List(schemaOk, shapeOk, bundleOk, reservedOk, metaOk, definitionOk, limitsOk).combineAll

    def setStatus(update: SetVersionStatus): F[ValidationResult] = {
      val _ = update
      ().validNec[DataApplicationValidationError].pure[F]
    }

    def registerAlias(update: RegisterAlias): F[ValidationResult] =
      for {
        tldOk      <- RegistryRules.L1.aliasTldIsFiber(update.name)
        reservedOk <- RegistryRules.L1.notReserved(update.name)
        metaOk     <- RegistryRules.L1.metadataConforms(update.metadata)
      } yield List(tldOk, reservedOk, metaOk).combineAll
  }

  /** L0 contextual checks (ownership + invariant preview). */
  class L0Validator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {

    def publish(update: PublishVersion): F[ValidationResult] =
      for {
        authd      <- RegistryRules.L0.authorizedPublisher(update.name, proofs, state.calculated)
        appendable <- RegistryRules.L0.versionAppendable(update.name, update.version, state.calculated)
      } yield authd |+| appendable

    def setStatus(update: SetVersionStatus): F[ValidationResult] =
      for {
        authd <- RegistryRules.L0.authorizedForExisting(update.name, proofs, state.calculated)
        legal <- RegistryRules.L0.statusTransitionLegal(update.name, update.version, update.status, state.calculated)
      } yield authd |+| legal

    def registerAlias(update: RegisterAlias): F[ValidationResult] =
      for {
        kindOk  <- RegistryRules.L0.aliasTargetIsKind(update.name, update.targetFiberId, state.calculated)
        ownerOk <- RegistryRules.L0.signerOwnsAliasTarget(update.targetFiberId, proofs, state.calculated)
        nameOk  <- RegistryRules.L0.aliasNameAvailable(update.name, proofs, state.calculated)
      } yield List(kindOk, ownerOk, nameOk).combineAll
  }

  /** Combined L1 + L0, used at the L0 layer. */
  class CombinedValidator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {
    private val l1 = new L1Validator[F]
    private val l0 = new L0Validator[F](state, proofs)

    def publish(update: PublishVersion): F[ValidationResult] =
      for {
        l1Result <- l1.publish(update)
        l0Result <- l0.publish(update)
      } yield l1Result |+| l0Result

    def setStatus(update: SetVersionStatus): F[ValidationResult] =
      for {
        l1Result <- l1.setStatus(update)
        l0Result <- l0.setStatus(update)
      } yield l1Result |+| l0Result

    def registerAlias(update: RegisterAlias): F[ValidationResult] =
      for {
        l1Result <- l1.registerAlias(update)
        l0Result <- l0.registerAlias(update)
      } yield l1Result |+| l0Result
  }
}
