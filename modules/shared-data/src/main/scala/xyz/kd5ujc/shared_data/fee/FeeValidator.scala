package xyz.kd5ujc.shared_data.fee

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, FeeTransaction}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage

/**
 * Validates the FeeTransaction paired with a DataUpdate against OttoChain's fee rules.
 *
 * == Validation Rules ==
 *
 * When `config.enabled = false` (grace period):
 *   - Missing fee: accepted (no enforcement)
 *   - Voluntary fee present: accepted (recorded on-chain, no checks)
 *
 * When `config.enabled = true` and `config.required = true`:
 *   - Missing fee: rejected with [[FeeErrors.FeeRequired]]
 *   - Fee with wrong dataUpdateRef: rejected with [[FeeErrors.FeeRefMismatch]]
 *   - Fee sent to wrong address: rejected with [[FeeErrors.FeeBadDestination]]
 *   - Fee amount below required: rejected with [[FeeErrors.FeeTooLow]]
 *   - Valid fee: accepted
 */
object FeeValidator {

  // ── Error ADT ────────────────────────────────────────────────────────────────

  object FeeErrors {

    case object FeeRequired extends DataApplicationValidationError {
      val message = "Fee required: no FeeTransaction paired with this DataUpdate"
    }

    final case class FeeRefMismatch(expected: String, actual: String) extends DataApplicationValidationError {
      val message = s"Fee dataUpdateRef mismatch: expected $expected, got $actual"
    }

    final case class FeeBadDestination(expected: String, actual: String) extends DataApplicationValidationError {
      val message = s"Fee wrong destination: expected $expected, got $actual"
    }

    final case class FeeTooLow(required: Long, actual: Long) extends DataApplicationValidationError {
      val message = s"Fee insufficient: required $required datum, got $actual datum"
    }
  }

  // Helper: lift a DataApplicationValidationError into the validated type
  private def invalid(err: DataApplicationValidationError): DataApplicationValidationErrorOr[Unit] =
    err.invalidNec[Unit]

  private val valid: DataApplicationValidationErrorOr[Unit] =
    ().validNec[DataApplicationValidationError]

  // ── Public API ────────────────────────────────────────────────────────────────

  /**
   * Validates the fee for a signed update.
   *
   * @param config       FeeConfig controlling enforcement behaviour.
   * @param signedUpdate The signed OttochainMessage being validated.
   * @param maybeFee     The optional paired FeeTransaction.
   * @return             ValidatedNec result — valid if fee checks pass.
   */
  def validateFee[F[_]: Async](
    config: FeeConfig
  )(
    signedUpdate: Signed[OttochainMessage],
    maybeFee:     Option[Signed[FeeTransaction]]
  )(implicit sp: SecurityProvider[F]): F[DataApplicationValidationErrorOr[Unit]] = {
    import FeeErrors._

    if (!config.enabled) {
      // Grace period — accept everything, no enforcement
      valid.pure[F]
    } else {
      maybeFee match {
        case None =>
          invalid(FeeRequired).pure[F]

        case Some(signedFee) =>
          val fee = signedFee.value

          // We need F effects to compute the update hash
          for {
            updateHash <- signedUpdate.value.computeDigest
            estimatedFee = FeeEstimator.estimateFee(config)(signedUpdate.value)
            treasury = config.treasuryAddress.get // safe: enabled → treasury defined

            refCheck: DataApplicationValidationErrorOr[Unit] =
              if (fee.dataUpdateRef == updateHash) valid
              else invalid(FeeRefMismatch(updateHash.value, fee.dataUpdateRef.value))

            destCheck: DataApplicationValidationErrorOr[Unit] =
              if (fee.destination == treasury) valid
              else invalid(FeeBadDestination(treasury.value.value, fee.destination.value.value))

            amountCheck: DataApplicationValidationErrorOr[Unit] =
              if (fee.amount.value.value >= estimatedFee) valid
              else invalid(FeeTooLow(estimatedFee, fee.amount.value.value))

          } yield (refCheck, destCheck, amountCheck).mapN((_, _, _) => ())
      }
    }
  }
}
