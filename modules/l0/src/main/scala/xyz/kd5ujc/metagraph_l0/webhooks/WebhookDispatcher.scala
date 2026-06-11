package xyz.kd5ujc.metagraph_l0.webhooks

import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import cats.data.NonEmptyChain
import cats.effect.kernel.Async
import cats.implicits._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates._

import io.circe.syntax._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, MediaType, Method, Request, Uri}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.SelfAwareStructuredLogger

/**
 * Dispatches webhook notifications to subscribers on snapshot consensus.
 */
trait WebhookDispatcher[F[_]] {

  /**
   * Dispatch snapshot notification to all active subscribers.
   * This should be called from onSnapshotConsensusResult.
   *
   * Delivery is fire-and-forget to avoid blocking consensus.
   */
  def dispatch(
    snapshot: Hashed[CurrencyIncrementalSnapshot],
    stats:    NotificationStats
  ): F[Unit]

  /**
   * Dispatch rejection notification for a failed validation.
   * This should be called from validateData when an update fails validation.
   *
   * Delivery is fire-and-forget to avoid blocking consensus.
   *
   * @param ordinal Current snapshot ordinal
   * @param signedUpdate The update that was rejected
   * @param errors The validation errors that caused the rejection
   */
  def dispatchRejection(
    ordinal:      SnapshotOrdinal,
    signedUpdate: Signed[OttochainMessage],
    errors:       NonEmptyChain[DataApplicationValidationError]
  ): F[Unit]
}

object WebhookDispatcher {

  /**
   * Create a webhook dispatcher
   *
   * @param client HTTP client for making requests
   * @param registry Subscriber registry
   * @param metagraphId The metagraph token identifier
   */
  def make[F[_]](
    client:      Client[F],
    registry:    SubscriberRegistry[F],
    metagraphId: String
  )(implicit F: Async[F], logger: SelfAwareStructuredLogger[F]): WebhookDispatcher[F] =
    new WebhookDispatcher[F] {

      def dispatch(
        snapshot: Hashed[CurrencyIncrementalSnapshot],
        stats:    NotificationStats
      ): F[Unit] = {
        val notification = SnapshotNotification(
          event = "snapshot.finalized",
          ordinal = snapshot.ordinal.value.value,
          hash = snapshot.hash.value,
          timestamp = Instant.now(),
          metagraphId = metagraphId,
          stats = stats
        )

        val body = notification.asJson.noSpaces

        registry.listActive.flatMap { subscribers =>
          if (subscribers.isEmpty) {
            F.unit
          } else {
            logger.debug(
              s"Dispatching webhook to ${subscribers.size} subscribers for ordinal ${notification.ordinal}"
            ) *>
            subscribers.traverse_ { sub =>
              deliverToSubscriber(sub, body)
                .flatTap(_ => registry.markSuccess(sub.id))
                .flatTap(_ => logger.debug(s"Webhook delivered to ${sub.callbackUrl}"))
                .handleErrorWith { err =>
                  logger.warn(s"Webhook delivery failed for ${sub.callbackUrl}: ${err.getMessage}") *>
                  registry.markFailure(sub.id)
                }
            }
          }
        }
      }

      def dispatchRejection(
        ordinal:      SnapshotOrdinal,
        signedUpdate: Signed[OttochainMessage],
        errors:       NonEmptyChain[DataApplicationValidationError]
      ): F[Unit] = {
        val update = signedUpdate.value

        // Extract target sequence number for transition-like updates
        val targetSeqNum: Option[Long] = update match {
          case u: TransitionStateMachine => Some(u.targetSequenceNumber.value.value)
          case u: ArchiveStateMachine    => Some(u.targetSequenceNumber.value.value)
          case u: UpgradeFiber           => Some(u.targetSequenceNumber.value.value)
          case u: InvokeScript           => Some(u.targetSequenceNumber.value.value)
          case _                         => None
        }

        // Extract signer IDs from proofs (hex representation)
        val signers = signedUpdate.proofs.toList.map(_.id.hex.value)

        // Compute hash of signed update for dedup
        val updateHash = computeUpdateHash(signedUpdate)

        // Convert validation errors to our format
        val validationErrors = errors.toList.map { err =>
          ValidationError(
            code = err.getClass.getSimpleName.stripSuffix("$"),
            message = err.message
          )
        }

        val rejectedUpdate = RejectedUpdate(
          updateType = update.messageName,
          fiberId = update.fiberId,
          targetSequenceNumber = targetSeqNum,
          errors = validationErrors,
          signers = signers,
          updateHash = updateHash
        )

        val notification = RejectionNotification(
          event = "transaction.rejected",
          ordinal = ordinal.value.value,
          timestamp = Instant.now(),
          metagraphId = metagraphId,
          rejection = rejectedUpdate
        )

        val body = notification.asJson.noSpaces

        registry.listActive.flatMap { subscribers =>
          if (subscribers.isEmpty) {
            F.unit
          } else {
            logger.info(
              s"Dispatching rejection webhook for ${update.messageName} fiberId=${update.fiberId} " +
              s"to ${subscribers.size} subscribers (errors: ${validationErrors.map(_.code).mkString(", ")})"
            ) *>
            subscribers.traverse_ { sub =>
              deliverToSubscriber(sub, body)
                .flatTap(_ => logger.debug(s"Rejection webhook delivered to ${sub.callbackUrl}"))
                .handleErrorWith { err =>
                  // Fire-and-forget: log failure but don't track for rejection notifications
                  logger.warn(s"Rejection webhook delivery failed for ${sub.callbackUrl}: ${err.getMessage}")
                }
            }
          }
        }
      }

      /**
       * Compute a stable hash of the signed update for deduplication.
       * Uses the update JSON + first signer to create a unique identifier.
       */
      private def computeUpdateHash(signedUpdate: Signed[OttochainMessage]): String = {
        val updateJson = signedUpdate.value.asJson.noSpaces
        val firstSigner = signedUpdate.proofs.head.id.hex.value
        val combined = s"$updateJson:$firstSigner"

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(combined.getBytes(StandardCharsets.UTF_8))
        digest.digest().map("%02x".format(_)).mkString
      }

      private def deliverToSubscriber(sub: Subscriber, body: String): F[Unit] =
        Uri.fromString(sub.callbackUrl) match {
          case Left(err) =>
            logger.warn(s"Invalid callback URL ${sub.callbackUrl}: ${err.message}")

          case Right(uri) =>
            val signature = sub.secret.map(computeHmacSignature(body, _))

            val baseRequest = Request[F](Method.POST, uri)
              .withEntity(body)
              .withContentType(`Content-Type`(MediaType.application.json))

            val request = signature.fold(baseRequest) { sig =>
              baseRequest.putHeaders(
                Header.Raw(CIString("X-OttoChain-Signature"), s"sha256=$sig")
              )
            }

            client.expect[Unit](request)
        }

      private def computeHmacSignature(body: String, secret: String): String = {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        mac
          .doFinal(body.getBytes(StandardCharsets.UTF_8))
          .map("%02x".format(_))
          .mkString
      }
    }
}
