package xyz.kd5ujc.metagraph_l0.handlers

import cats.effect.Async
import cats.syntax.all._

import xyz.kd5ujc.metagraph_l0.webhooks.{SubscribeRequest, SubscribeResponse, SubscriberRegistry}

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.{Response, Status}

/** Webhook subscriber management logic: register, unregister, list (secrets redacted). */
class WebhookHandler[F[_]: Async](
  subscriberRegistry: SubscriberRegistry[F]
) {

  def subscribe(request: SubscribeRequest): F[Response[F]] =
    subscriberRegistry.register(request.callbackUrl, request.secret).map { subscriber =>
      Response[F](Status.Created).withEntity(SubscribeResponse.fromSubscriber(subscriber).asJson)
    }

  def unsubscribe(subscriberId: String): F[Response[F]] =
    subscriberRegistry.unregister(subscriberId).map { deleted =>
      if (deleted) Response[F](Status.NoContent)
      else Response[F](Status.NotFound).withEntity(Json.obj("error" -> "Subscriber not found".asJson))
    }

  def list: F[Response[F]] =
    subscriberRegistry.list.map { subscribers =>
      // Hide secrets in the response
      val sanitized = subscribers.map(s => s.copy(secret = s.secret.map(_ => "***")))
      Response[F](Status.Ok).withEntity(Json.obj("subscribers" -> sanitized.asJson))
    }
}
