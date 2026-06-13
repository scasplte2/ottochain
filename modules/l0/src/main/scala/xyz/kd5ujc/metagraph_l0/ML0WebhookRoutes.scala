package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes

import xyz.kd5ujc.metagraph_l0.webhooks.{SubscribeRequest, SubscribeResponse, SubscriberRegistry}

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Response, Status}

/** Webhook subscriber management: register, unregister, list (secrets redacted). */
class ML0WebhookRoutes[F[_]: Async](
  subscriberRegistry: SubscriberRegistry[F]
) extends MetagraphPublicRoutes[F] {

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    /**
     * Register a new webhook subscriber
     * POST /v1/webhooks/subscribe
     * Body: { "callbackUrl": "https://...", "secret": "optional" }
     */
    case req @ POST -> Root / "webhooks" / "subscribe" =>
      req.decode[SubscribeRequest] { request =>
        subscriberRegistry.register(request.callbackUrl, request.secret).flatMap { subscriber =>
          Response[F](Status.Created)
            .withEntity(SubscribeResponse.fromSubscriber(subscriber).asJson)
            .pure[F]
        }
      }

    /**
     * Unregister a webhook subscriber
     * DELETE /v1/webhooks/subscribe/:id
     */
    case DELETE -> Root / "webhooks" / "subscribe" / subscriberId =>
      subscriberRegistry.unregister(subscriberId).flatMap { deleted =>
        if (deleted) {
          Response[F](Status.NoContent).pure[F]
        } else {
          Response[F](Status.NotFound)
            .withEntity(Json.obj("error" -> "Subscriber not found".asJson))
            .pure[F]
        }
      }

    /**
     * List all webhook subscribers
     * GET /v1/webhooks/subscribers
     */
    case GET -> Root / "webhooks" / "subscribers" =>
      subscriberRegistry.list.flatMap { subscribers =>
        // Hide secrets in response
        val sanitized = subscribers.map(s => s.copy(secret = s.secret.map(_ => "***")))
        Ok(Json.obj("subscribers" -> sanitized.asJson))
      }
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
