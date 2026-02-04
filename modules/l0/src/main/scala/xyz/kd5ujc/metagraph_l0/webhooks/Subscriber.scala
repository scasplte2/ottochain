package xyz.kd5ujc.metagraph_l0.webhooks

import java.time.Instant
import java.util.UUID

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
 * Webhook subscriber for snapshot notifications
 */
case class Subscriber(
  id:             String,
  callbackUrl:    String,
  secret:         Option[String],
  active:         Boolean,
  createdAt:      Instant,
  lastDeliveryAt: Option[Instant],
  failCount:      Int
)

object Subscriber {

  def create(callbackUrl: String, secret: Option[String]): Subscriber =
    Subscriber(
      id = s"sub_${UUID.randomUUID().toString.take(8)}",
      callbackUrl = callbackUrl,
      secret = secret,
      active = true,
      createdAt = Instant.now(),
      lastDeliveryAt = None,
      failCount = 0
    )

  implicit val encoder: Encoder[Subscriber] = deriveEncoder[Subscriber]
  implicit val decoder: Decoder[Subscriber] = deriveDecoder[Subscriber]
}

/**
 * Request to subscribe to webhook notifications
 */
case class SubscribeRequest(
  callbackUrl: String,
  secret:      Option[String]
)

object SubscribeRequest {
  implicit val decoder: Decoder[SubscribeRequest] = deriveDecoder[SubscribeRequest]
}

/**
 * Response for successful subscription
 */
case class SubscribeResponse(
  id:          String,
  callbackUrl: String,
  createdAt:   Instant
)

object SubscribeResponse {
  implicit val encoder: Encoder[SubscribeResponse] = deriveEncoder[SubscribeResponse]

  def fromSubscriber(s: Subscriber): SubscribeResponse =
    SubscribeResponse(s.id, s.callbackUrl, s.createdAt)
}

/**
 * Snapshot notification payload sent to subscribers
 */
case class SnapshotNotification(
  event:       String,
  ordinal:     Long,
  hash:        String,
  timestamp:   Instant,
  metagraphId: String,
  stats:       NotificationStats
)

object SnapshotNotification {
  implicit val encoder: Encoder[SnapshotNotification] = deriveEncoder[SnapshotNotification]
}

case class NotificationStats(
  updatesProcessed:    Int,
  stateMachinesActive: Int,
  scriptsActive:       Int
)

object NotificationStats {
  implicit val encoder: Encoder[NotificationStats] = deriveEncoder[NotificationStats]
}
