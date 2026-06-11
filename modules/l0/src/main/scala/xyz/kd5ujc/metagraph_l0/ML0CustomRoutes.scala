package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.ext.http4s.error.RefinedRequestApplicationDecoder
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CommittedReader
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.metagraph_l0.webhooks.{SubscribeRequest, SubscribeResponse, SubscriberRegistry}
import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberStatus

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router
import org.http4s.{HttpRoutes, QueryParamDecoder, Response, Status}

/**
 * Custom L0 routes backed by the committed cell: every calculated-state read goes through the
 * [[CommittedReader]] handed out by `CommittedApp.makeL0` (one atomic cell read per request), so
 * responses are always consistent with the served `/committed/...` roots and proofs.
 *
 * Routes that need the latest SIGNED snapshot (on-chain state / logs) live in
 * [[ML0SnapshotStateRoutes]] -- makeL0's `extraRoutes` does not receive the `L0NodeContext`
 * (flagged as a metakit follow-up in `ML0Service`).
 */
class ML0CustomRoutes[F[_]: Async](
  reader:             CommittedReader[F, CalculatedState],
  subscriberRegistry: SubscriberRegistry[F]
) extends MetagraphPublicRoutes[F] {

  implicit val fiberStatusDecoder: QueryParamDecoder[FiberStatus] =
    QueryParamDecoder[String].emap { s =>
      FiberStatus.withNameOption(s).toRight(org.http4s.ParseFailure(s, s"Invalid FiberStatus: $s"))
    }

  object StatusQueryParam extends OptionalQueryParamDecoderMatcher[FiberStatus]("status")

  private def calculatedState: F[Checkpoint[CalculatedState]] =
    reader.committed.map(c => Checkpoint(c.ordinal, c.state))

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // Version endpoint for monitoring integration
    case GET -> Root / "version" =>
      Ok(
        Json.obj(
          "service"             -> "ottochain-ml0".asJson,
          "version"             -> BuildInfo.version.asJson,
          "name"                -> BuildInfo.name.asJson,
          "scalaVersion"        -> BuildInfo.scalaVersion.asJson,
          "sbtVersion"          -> BuildInfo.sbtVersion.asJson,
          "gitCommit"           -> BuildInfo.gitCommit.asJson,
          "buildTime"           -> BuildInfo.buildTime.asJson,
          "tessellationVersion" -> io.constellationnetwork.BuildInfo.version.asJson
        )
      )

    case req @ POST -> Root / "util" / "hash" =>
      req.asR[Signed[OttochainMessage]] { msg =>
        msg.value.computeDigest.flatMap { digest =>
          Ok(Json.obj("protocol message hash" -> digest.asJson, "protocol message" -> msg.value.asJson))
        }
      }

    case GET -> Root / "checkpoint" =>
      calculatedState
        .map(_.asRight[DataApplicationValidationError])
        .toResponse

    case GET -> Root / "state-machines" :? StatusQueryParam(statusOpt) =>
      calculatedState.map { case Checkpoint(_, state) =>
        statusOpt
          .fold(state.stateMachines) { status =>
            state.stateMachines.filter { case (_, fiber) => fiber.status == status }
          }
          .asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) =>
      calculatedState.map { case Checkpoint(_, state) =>
        state.stateMachines.get(fiberId).asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "oracles" :? StatusQueryParam(statusOpt) =>
      calculatedState.map { case Checkpoint(_, state) =>
        statusOpt
          .fold(state.scripts) { status =>
            state.scripts.filter { case (_, script) => script.status == status }
          }
          .asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "oracles" / UUIDVar(scriptId) =>
      calculatedState.map { case Checkpoint(_, state) =>
        state.scripts.get(scriptId).asRight[DataApplicationValidationError]
      }.toResponse

    // =========================================================================
    // Webhook Management Endpoints
    // =========================================================================

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
