package xyz.kd5ujc.schema.api.webhooks

import java.time.Instant
import java.util.UUID

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
 * Webhook subscriber for snapshot notifications.
 *
 * Static network DTO — lives in `models` so the chain and the SDK share one definition of the wire shape.
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

/** Request to subscribe to webhook notifications. */
case class SubscribeRequest(
  callbackUrl: String,
  secret:      Option[String]
)

object SubscribeRequest {
  implicit val encoder: Encoder[SubscribeRequest] = deriveEncoder[SubscribeRequest]
  implicit val decoder: Decoder[SubscribeRequest] = deriveDecoder[SubscribeRequest]
}

/** Response for a successful subscription. */
case class SubscribeResponse(
  id:          String,
  callbackUrl: String,
  createdAt:   Instant
)

object SubscribeResponse {
  implicit val encoder: Encoder[SubscribeResponse] = deriveEncoder[SubscribeResponse]
  implicit val decoder: Decoder[SubscribeResponse] = deriveDecoder[SubscribeResponse]

  def fromSubscriber(s: Subscriber): SubscribeResponse =
    SubscribeResponse(s.id, s.callbackUrl, s.createdAt)
}

/** Response of `GET /v1/webhooks/subscribers` — the subscriber list (secrets redacted by the handler). */
case class SubscriberList(subscribers: List[Subscriber])

object SubscriberList {
  implicit val encoder: Encoder[SubscriberList] = deriveEncoder[SubscriberList]
  implicit val decoder: Decoder[SubscriberList] = deriveDecoder[SubscriberList]
}

/** Snapshot notification payload POSTed to subscribers. */
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
