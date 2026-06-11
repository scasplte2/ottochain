package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.ext.http4s.error.RefinedRequestApplicationDecoder
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.metagraph_l0.webhooks.{SubscribeRequest, SubscribeResponse, SubscriberRegistry}
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}
import xyz.kd5ujc.schema.fiber.{AuditRenderer, FiberStatus}
import xyz.kd5ujc.schema.registry.RegistryName
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router
import org.http4s.{HttpRoutes, QueryParamDecoder, Response, Status}

class ML0CustomRoutes[F[_]: Async](
  checkpointService:  CheckpointService[F, CalculatedState],
  subscriberRegistry: SubscriberRegistry[F]
)(implicit
  context: L0NodeContext[F]
) extends MetagraphPublicRoutes[F] {

  implicit val fiberStatusDecoder: QueryParamDecoder[FiberStatus] =
    QueryParamDecoder[String].emap { s =>
      FiberStatus.withNameOption(s).toRight(org.http4s.ParseFailure(s, s"Invalid FiberStatus: $s"))
    }

  object StatusQueryParam extends OptionalQueryParamDecoderMatcher[FiberStatus]("status")

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

    case GET -> Root / "onchain" =>
      context.getOnChainState[OnChain].toResponse

    case GET -> Root / "checkpoint" =>
      checkpointService.get
        .map(_.asRight[DataApplicationValidationError])
        .toResponse

    case GET -> Root / "state-machines" :? StatusQueryParam(statusOpt) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        statusOpt
          .fold(state.stateMachines) { status =>
            state.stateMachines.filter { case (_, fiber) => fiber.status == status }
          }
          .asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.stateMachines.get(fiberId).asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "oracles" :? StatusQueryParam(statusOpt) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        statusOpt
          .fold(state.scripts) { status =>
            state.scripts.filter { case (_, oracle) => oracle.status == status }
          }
          .asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "oracles" / UUIDVar(oracleId) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.scripts.get(oracleId).asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) / "events" =>
      context
        .getOnChainState[OnChain]
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(fiberId, List.empty)
            .collect { case r: EventReceipt => r }
        })
        .toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) / "audit" =>
      checkpointService.get.flatMap { checkpoint =>
        context
          .getOnChainState[OnChain]
          .map(_.map { onChain =>
            AuditRenderer.renderAll(
              onChain.latestLogs.getOrElse(fiberId, List.empty),
              checkpoint.state.reverseNames.toMap
            )
          })
      }.toResponse

    case GET -> Root / "oracles" / UUIDVar(oracleId) / "invocations" =>
      context
        .getOnChainState[OnChain]
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(oracleId, List.empty)
            .collect { case i: OracleInvocation => i }
        })
        .toResponse

    // =========================================================================
    // Registry Endpoints — schema-package + alias lifecycle (discovery / e2e assertions)
    // =========================================================================

    // All registry entries: name -> RegistryEntry (target = schema-package version lineage, or alias).
    case GET -> Root / "registry" =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.registry.asRight[DataApplicationValidationError]
      }.toResponse

    // Reverse record (#29): fiber UUID -> its canonical registered name. Declared before the
    // by-name route; arity differs so there is no ambiguity, but keep the specific path first.
    case GET -> Root / "registry" / "reverse" / UUIDVar(fiberId) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.reverseNames.get(fiberId).asRight[DataApplicationValidationError]
      }.toResponse

    // A single entry by full `labels.tld` name (e.g. `counter.package`, `my-counter.machine`).
    case GET -> Root / "registry" / nameStr =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        RegistryName
          .from(nameStr)
          .toOption
          .flatMap(state.registry.get)
          .asRight[DataApplicationValidationError]
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
