package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.Base64

import cats.Applicative
import cats.data.{NonEmptySet, Validated}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.Updates.PublishVersion
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.shared_data.lifecycle.validate.{Limits, ValidationResult}

/**
 * Validation rules for the registry.
 *
 *  - L1 (structural): base64 well-formedness, non-empty fields, bundle size bound — no state needed.
 *  - L0 (contextual): ownership + a PREVIEW of the [[VersionLineage]] invariants against
 *    CalculatedState.registry. These mirror what [[xyz.kd5ujc.shared_data.lifecycle.combine.RegistryCombiner]]
 *    enforces authoritatively at combine (a rejected op aborts there); validating here gives early,
 *    structured rejection instead of an abort.
 */
object RegistryRules {

  object L1 {

    def validBase64[F[_]: Applicative](field: String, b64: String): F[ValidationResult] =
      scala.util
        .Try(Base64.getDecoder.decode(b64))
        .fold(
          _ => (Errors.InvalidBase64(field): DataApplicationValidationError).invalidNec[Unit].pure[F],
          _ => ().validNec[DataApplicationValidationError].pure[F]
        )

    def nonEmpty[F[_]: Applicative](field: String, s: String): F[ValidationResult] =
      Validated.condNec(s.nonEmpty, (), Errors.EmptyField(field): DataApplicationValidationError).pure[F]

    def bundleWithinLimit[F[_]: Applicative](pv: PublishVersion): F[ValidationResult] = {
      val size = pv.schemaB64.length.toLong + pv.definitionB64.length.toLong
      Validated
        .condNec(
          size <= Limits.MaxRegistryBundleBytes,
          (),
          Errors.BundleTooLarge(size, Limits.MaxRegistryBundleBytes): DataApplicationValidationError
        )
        .pure[F]
    }
  }

  object L0 {

    private def signerAddresses[F[_]: Async: SecurityProvider](proofs: NonEmptySet[SignatureProof]) =
      proofs.toList.traverse(_.id.toAddress).map(_.toSet)

    /** For an existing entry a signer must be an owner; a new name may be claimed by anyone (first publish). */
    def authorizedPublisher[F[_]: Async: SecurityProvider](
      name:   RegistryName,
      proofs: NonEmptySet[SignatureProof],
      state:  CalculatedState
    ): F[ValidationResult] =
      state.registry.get(name) match {
        case None => ().validNec[DataApplicationValidationError].pure[F]
        case Some(entry) =>
          signerAddresses(proofs).map { signers =>
            Validated.condNec(
              signers.intersect(entry.owner).nonEmpty,
              (),
              Errors.NotRegistryOwner(name.render): DataApplicationValidationError
            )
          }
      }

    /** The entry must exist and a signer must be an owner (status changes). */
    def authorizedForExisting[F[_]: Async: SecurityProvider](
      name:   RegistryName,
      proofs: NonEmptySet[SignatureProof],
      state:  CalculatedState
    ): F[ValidationResult] =
      state.registry.get(name) match {
        case None =>
          (Errors.UnknownRegistryName(name.render): DataApplicationValidationError).invalidNec[Unit].pure[F]
        case Some(entry) =>
          signerAddresses(proofs).map { signers =>
            Validated.condNec(
              signers.intersect(entry.owner).nonEmpty,
              (),
              Errors.NotRegistryOwner(name.render): DataApplicationValidationError
            )
          }
      }

    /** Preview VersionLineage.publish: a new version must be absent and strictly greater than the current max. */
    def versionAppendable[F[_]: Applicative](
      name:    RegistryName,
      version: SemVer,
      state:   CalculatedState
    ): F[ValidationResult] =
      lineageOf(name, state) match {
        case None => ().validNec[DataApplicationValidationError].pure[F] // new name -> first version
        case Some(lineage) =>
          if (lineage.versions.contains(version))
            (Errors.VersionAlreadyExists(version.render): DataApplicationValidationError).invalidNec[Unit].pure[F]
          else
            lineage.versions.keys.lastOption match {
              case Some(max) if SemVer.ordering.gteq(max, version) =>
                (Errors.NonMonotonicVersion(version.render, max.render): DataApplicationValidationError)
                  .invalidNec[Unit]
                  .pure[F]
              case _ => ().validNec[DataApplicationValidationError].pure[F]
            }
      }

    /** Preview VersionLineage.setStatus: the version must exist and the transition must be legal. */
    def statusTransitionLegal[F[_]: Applicative](
      name:    RegistryName,
      version: SemVer,
      to:      RegistryStatus,
      state:   CalculatedState
    ): F[ValidationResult] =
      lineageOf(name, state).flatMap(_.versions.get(version)) match {
        case None =>
          (Errors.UnknownVersion(version.render): DataApplicationValidationError).invalidNec[Unit].pure[F]
        case Some(rv) =>
          Validated
            .condNec(
              RegistryStatus.canTransition(rv.status, to),
              (),
              Errors.IllegalStatusTransition(rv.status.entryName, to.entryName): DataApplicationValidationError
            )
            .pure[F]
      }

    /** A fiber's optional schemaRef must resolve against the registry (the referenced version must exist). */
    def refResolves[F[_]: Applicative](ref: Option[SchemaRef], state: CalculatedState): F[ValidationResult] =
      ref match {
        case None => ().validNec[DataApplicationValidationError].pure[F]
        case Some(SchemaRef(name, versionReq)) =>
          lineageOf(name, state) match {
            case None =>
              (Errors.SchemaRefUnknownName(name.render): DataApplicationValidationError).invalidNec[Unit].pure[F]
            case Some(lineage) =>
              lineage
                .resolve(versionReq)
                .fold(
                  _ =>
                    (Errors
                      .SchemaRefUnresolvable(name.render): DataApplicationValidationError).invalidNec[Unit].pure[F],
                  _ => ().validNec[DataApplicationValidationError].pure[F]
                )
          }
      }

    private def lineageOf(name: RegistryName, state: CalculatedState): Option[VersionLineage] =
      state.registry.get(name).map(_.target).collect { case RegistryTarget.SchemaPackage(l) => l }
  }

  object Errors {

    final case class InvalidBase64(field: String) extends DataApplicationValidationError {
      override val message: String = s"registry field '$field' is not valid base64"
    }

    final case class EmptyField(field: String) extends DataApplicationValidationError {
      override val message: String = s"registry field '$field' must not be empty"
    }

    final case class BundleTooLarge(size: Long, max: Long) extends DataApplicationValidationError {
      override val message: String = s"registry bundle of $size bytes exceeds limit $max"
    }

    final case class NotRegistryOwner(name: String) extends DataApplicationValidationError {
      override val message: String = s"signer is not an owner of registry entry '$name'"
    }

    final case class UnknownRegistryName(name: String) extends DataApplicationValidationError {
      override val message: String = s"unknown registry name '$name'"
    }

    final case class VersionAlreadyExists(version: String) extends DataApplicationValidationError {
      override val message: String = s"registry version $version already exists"
    }

    final case class NonMonotonicVersion(attempted: String, current: String) extends DataApplicationValidationError {
      override val message: String = s"registry version $attempted is not greater than current $current"
    }

    final case class UnknownVersion(version: String) extends DataApplicationValidationError {
      override val message: String = s"unknown registry version $version"
    }

    final case class IllegalStatusTransition(from: String, to: String) extends DataApplicationValidationError {
      override val message: String = s"illegal registry status transition $from -> $to"
    }

    final case class SchemaRefUnknownName(name: String) extends DataApplicationValidationError {
      override val message: String = s"schemaRef refers to unknown registry name '$name'"
    }

    final case class SchemaRefUnresolvable(name: String) extends DataApplicationValidationError {
      override val message: String = s"schemaRef version is unresolvable for registry name '$name'"
    }
  }
}
