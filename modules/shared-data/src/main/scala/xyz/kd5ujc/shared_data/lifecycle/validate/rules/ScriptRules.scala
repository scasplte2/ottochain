package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.UUID

import cats.Applicative
import cats.data.{EitherT, NonEmptySet, Validated}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber.{AccessControlPolicy, FiberOrdinal, FiberStatus}
import xyz.kd5ujc.schema.registry.RegistryName
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.ValidationResult
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Validation rules specific to script operations.
 *
 * Organized by validation layer:
 * - L1: Structural validations that can run at Data-L1 with only OnChain state
 * - L0: Contextual validations that require CalculatedState and/or signature proofs
 */
object ScriptRules {

  // ============================================================================
  // L1 Rules - Structural validations (Data-L1 layer, OnChain state only)
  // ============================================================================

  object L1 {

    /** Validates that targetSequenceNumber matches the oracle's current sequence number */
    def sequenceNumberMatches[F[_]: Applicative](
      fiberId:              UUID,
      targetSequenceNumber: FiberOrdinal,
      state:                OnChain
    ): F[ValidationResult] =
      FiberRules.L1.sequenceNumberMatches(fiberId, targetSequenceNumber, state)
  }

  // ============================================================================
  // L0 Rules - Contextual validations (Metagraph-L0 layer, full state + proofs)
  // ============================================================================

  object L0 {

    /** Validates oracle access control policy allows the caller */
    def accessControlCheck[F[_]: Async: SecurityProvider](
      oracleId: UUID,
      proofs:   NonEmptySet[SignatureProof],
      state:    CalculatedState
    ): F[ValidationResult] = (for {
      oracle <- EitherT.fromOption[F](
        state.scripts.get(oracleId),
        Errors.OracleNotFound(oracleId): DataApplicationValidationError
      )
      callerAddresses <- EitherT.liftF(proofs.toList.traverse(_.id.toAddress))
      callerSet = callerAddresses.toSet
      authorized <- EitherT.cond[F](
        isAuthorized(oracle.accessControl, callerSet, state),
        (),
        Errors.OracleAccessDenied(oracleId, oracle.accessControl): DataApplicationValidationError
      )
    } yield authorized).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    /** Validates that an oracle exists */
    def oracleExists[F[_]: Applicative](
      oracleId: UUID,
      state:    CalculatedState
    ): F[ValidationResult] =
      state.scripts
        .get(oracleId)
        .fold(
          (Errors.OracleNotFound(oracleId): DataApplicationValidationError).invalidNec[Unit].pure[F]
        )(_ => ().validNec[DataApplicationValidationError].pure[F])

    /** Validates that a script fiber is in Active status. */
    def scriptIsActive[F[_]: Applicative](
      scriptId: UUID,
      state:    CalculatedState
    ): F[ValidationResult] =
      state.scripts.get(scriptId) match {
        case None =>
          (Errors.OracleNotFound(scriptId): DataApplicationValidationError).invalidNec[Unit].pure[F]
        case Some(record) =>
          Validated
            .condNec(
              record.status == FiberStatus.Active,
              (),
              Errors.ScriptNotActive(scriptId): DataApplicationValidationError
            )
            .pure[F]
      }

    /** Validates that the update is signed by at least one script owner. */
    def scriptSignedByOwners[F[_]: Async: SecurityProvider](
      scriptId: UUID,
      proofs:   NonEmptySet[SignatureProof],
      state:    CalculatedState
    ): F[ValidationResult] =
      state.scripts.get(scriptId) match {
        case None =>
          (Errors.OracleNotFound(scriptId): DataApplicationValidationError).invalidNec[Unit].pure[F]
        case Some(record) =>
          proofs.toList.traverse(_.id.toAddress).map { signerList =>
            val signers = signerList.toSet
            Validated.condNec(
              signers.intersect(record.owners).nonEmpty,
              (),
              Errors.ScriptNotSignedByOwner(scriptId): DataApplicationValidationError
            )
          }
      }

    /**
     * An UpgradeScript target must stay within the SAME package: the script must already be bound, and the
     * target name must equal its current binding's name (no cross-package switch).
     */
    def bindingNameMatchesScript[F[_]: Applicative](
      scriptId:   UUID,
      targetName: RegistryName,
      state:      CalculatedState
    ): F[ValidationResult] =
      state.scripts.get(scriptId) match {
        case None =>
          (Errors.OracleNotFound(scriptId): DataApplicationValidationError).invalidNec[Unit].pure[F]
        case Some(record) =>
          record.schemaBinding match {
            case Some(b) if b.name === targetName => ().validNec[DataApplicationValidationError].pure[F]
            case Some(b) =>
              (Errors.UpgradeScriptCrossPackage(
                scriptId,
                b.name.render,
                targetName.render
              ): DataApplicationValidationError).invalidNec[Unit].pure[F]
            case None =>
              (Errors.CannotUpgradeUnboundScript(scriptId): DataApplicationValidationError).invalidNec[Unit].pure[F]
          }
      }

    // ============== Private Helpers ==============

    private def isAuthorized(
      policy:    AccessControlPolicy,
      callerSet: Set[Address],
      state:     CalculatedState
    ): Boolean =
      policy match {
        case AccessControlPolicy.Public => true

        case AccessControlPolicy.FiberOwned(ownerFiberId) =>
          state.getFiber(ownerFiberId).exists { ownerFiber =>
            callerSet.intersect(ownerFiber.owners).nonEmpty
          }

        case AccessControlPolicy.Whitelist(allowed) =>
          callerSet.intersect(allowed).nonEmpty
      }
  }

  // ============================================================================
  // Errors - Oracle-specific validation errors
  // ============================================================================

  object Errors {

    final case class OracleNotFound(oracleId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Oracle $oracleId not found"
    }

    final case class OracleAccessDenied(oracleId: UUID, policy: AccessControlPolicy)
        extends DataApplicationValidationError {
      override val message: String = s"Access denied to oracle $oracleId (policy: $policy)"
    }

    final case class ScriptNotActive(scriptId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Script $scriptId is not active"
    }

    final case class ScriptNotSignedByOwner(scriptId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Update to script $scriptId not signed by any owner"
    }

    final case class CannotUpgradeUnboundScript(scriptId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Script $scriptId has no registry binding and cannot be upgraded"
    }

    final case class UpgradeScriptCrossPackage(scriptId: UUID, from: String, to: String)
        extends DataApplicationValidationError {

      override val message: String =
        s"Script $scriptId is bound to '$from'; cannot upgrade to a different package '$to'"
    }
  }
}
