package xyz.kd5ujc.schema.api.webhooks

import java.time.Instant
import java.util.UUID

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
 * Rejection notification sent when a transaction is rejected at ML0.
 *
 * This allows users to understand why their transaction didn't land on-chain
 * even though DL1 accepted it.
 *
 * @param event Always "transaction.rejected"
 * @param ordinal Snapshot ordinal when rejection occurred
 * @param timestamp ISO 8601 timestamp
 * @param metagraphId The metagraph token identifier
 * @param rejection Details about the rejected update
 */
case class RejectionNotification(
  event:       String,
  ordinal:     Long,
  timestamp:   Instant,
  metagraphId: String,
  rejection:   RejectedUpdate
)

object RejectionNotification {
  implicit val encoder: Encoder[RejectionNotification] = deriveEncoder[RejectionNotification]
  implicit val decoder: Decoder[RejectionNotification] = deriveDecoder[RejectionNotification]
}

/**
 * Details of a rejected update.
 *
 * @param updateType The type of update (e.g., "CreateStateMachine", "TransitionStateMachine")
 * @param fiberId UUID of the target fiber
 * @param targetSequenceNumber For transitions, the target sequence number
 * @param errors List of validation errors that caused the rejection
 * @param signers Signer IDs (hex) from the transaction proofs
 * @param updateHash Hash of the signed update for deduplication
 */
case class RejectedUpdate(
  updateType:           String,
  fiberId:              UUID,
  targetSequenceNumber: Option[Long],
  errors:               List[ValidationError],
  signers:              List[String],
  updateHash:           String
)

object RejectedUpdate {
  implicit val encoder: Encoder[RejectedUpdate] = deriveEncoder[RejectedUpdate]
  implicit val decoder: Decoder[RejectedUpdate] = deriveDecoder[RejectedUpdate]
}

/**
 * A validation error with code and message.
 *
 * @param code Error code (e.g., "FiberNotActive", "NotSignedByOwner")
 * @param message Human-readable error description
 */
case class ValidationError(
  code:    String,
  message: String
)

object ValidationError {
  implicit val encoder: Encoder[ValidationError] = deriveEncoder[ValidationError]
  implicit val decoder: Decoder[ValidationError] = deriveDecoder[ValidationError]
}
