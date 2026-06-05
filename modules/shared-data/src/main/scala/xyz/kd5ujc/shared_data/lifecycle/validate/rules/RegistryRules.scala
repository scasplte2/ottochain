package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.Base64

import cats.Applicative
import cats.data.{NonEmptySet, Validated}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.Updates.PublishVersion
import xyz.kd5ujc.schema.fiber.StateMachineDefinition
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

    def bundleWithinLimit[F[_]: Applicative](pv: PublishVersion): F[ValidationResult] = {
      val size = pv.schemaB64.length.toLong
      Validated
        .condNec(
          size <= Limits.MaxRegistryBundleBytes,
          (),
          Errors.BundleTooLarge(size, Limits.MaxRegistryBundleBytes): DataApplicationValidationError
        )
        .pure[F]
    }

    // protobuf field-number constraints (FieldDescriptor): 1..2^29-1, excluding the reserved 19000..19999.
    private val MinFieldNumber = 1
    private val MaxFieldNumber = 536870911
    private val ReservedLo = 19000
    private val ReservedHi = 19999

    /**
     * The typed schema projection must be structurally proto-valid: non-empty type/field/command names,
     * field numbers in the legal proto range and outside the reserved window, and unique within a message.
     * (This validates the *shape* only — it does not check the logic conforms to it; conformance is the
     * separate opt-in dial. See strong-typing-and-conformance.md §0.5.)
     */
    def schemaShapeWellFormed[F[_]: Applicative](shape: SchemaShape): F[ValidationResult] = {
      val emptyCmdNames = shape.commands.keys.filter(_.trim.isEmpty).map(_ => "a command name is empty").toList
      val problems = emptyCmdNames ::: shape.allMessages.flatMap(messageProblems)
      problems match {
        case Nil => ().validNec[DataApplicationValidationError]
        case ps  => (Errors.MalformedSchemaShape(ps.mkString("; ")): DataApplicationValidationError).invalidNec[Unit]
      }
    }.pure[F]

    private def messageProblems(m: MessageShape): List[String] = {
      val tn = if (m.typeName.trim.isEmpty) "<unnamed>" else m.typeName
      val typeNameProblem = if (m.typeName.trim.isEmpty) List("a message has an empty typeName") else Nil
      val fieldNameProblems = m.fields.filter(_.name.trim.isEmpty).map(_ => s"$tn has a field with an empty name")
      val numberProblems = m.fields.flatMap { f =>
        if (f.number < MinFieldNumber || f.number > MaxFieldNumber)
          List(s"$tn.${f.name} has out-of-range field number ${f.number}")
        else if (f.number >= ReservedLo && f.number <= ReservedHi)
          List(s"$tn.${f.name} uses reserved field number ${f.number}")
        else Nil
      }
      val dupProblems = {
        val nums = m.fields.map(_.number)
        val dups = nums.diff(nums.distinct).distinct
        if (dups.nonEmpty) List(s"$tn has duplicate field numbers ${dups.mkString(",")}") else Nil
      }
      typeNameProblem ::: fieldNameProblems ::: numberProblems ::: dupProblems
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

    /**
     * A fiber's optional schemaRef must resolve against the registry AND the fiber's definition must hash
     * to the resolved version's `logicHash` (#37 VERIFIED binding) — mirrors FiberCombiner.resolveBinding,
     * giving an early structured rejection before the combiner's authoritative abort.
     */
    def refResolvesAndMatches[F[_]: Async](
      ref:        Option[SchemaRef],
      definition: StateMachineDefinition,
      state:      CalculatedState
    ): F[ValidationResult] =
      ref match {
        case None => ().validNec[DataApplicationValidationError].pure[F]
        case Some(SchemaRef(name, versionReq)) =>
          lineageOf(name, state) match {
            case None =>
              (Errors.SchemaRefUnknownName(name.render): DataApplicationValidationError).invalidNec[Unit].pure[F]
            case Some(lineage) =>
              lineage.resolve(versionReq) match {
                case Left(_) =>
                  (Errors.SchemaRefUnresolvable(name.render): DataApplicationValidationError).invalidNec[Unit].pure[F]
                case Right(rv) =>
                  definition.computeDigest.map { digest =>
                    Validated.condNec(
                      digest === rv.logicHash,
                      (),
                      Errors.SchemaRefLogicMismatch(name.render, rv.version.render): DataApplicationValidationError
                    )
                  }
              }
          }
      }

    private def lineageOf(name: RegistryName, state: CalculatedState): Option[VersionLineage] =
      state.registry.get(name).map(_.target).collect { case RegistryTarget.SchemaPackage(l) => l }
  }

  object Errors {

    final case class InvalidBase64(field: String) extends DataApplicationValidationError {
      override val message: String = s"registry field '$field' is not valid base64"
    }

    final case class BundleTooLarge(size: Long, max: Long) extends DataApplicationValidationError {
      override val message: String = s"registry descriptor of $size bytes exceeds limit $max"
    }

    final case class MalformedSchemaShape(reason: String) extends DataApplicationValidationError {
      override val message: String = s"registry schemaShape is malformed: $reason"
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

    final case class SchemaRefLogicMismatch(name: String, version: String) extends DataApplicationValidationError {

      override val message: String =
        s"fiber definition does not match the registered logic for '$name'@$version (verified binding)"
    }
  }
}
