package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.schema.fiber.FiberLogEntry.OracleInvocation
import xyz.kd5ujc.schema.fiber.FiberStatus
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import org.http4s.server.Router
import org.http4s.{HttpRoutes, QueryParamDecoder}

/**
 * Script-fiber endpoints (the legacy `/oracles` API surface is retained for compatibility; "script"
 * is the current term): list (optionally by status), single record, invocation log.
 */
class ML0ScriptRoutes[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
)(implicit
  context: L0NodeContext[F]
) extends MetagraphPublicRoutes[F] {

  implicit val fiberStatusDecoder: QueryParamDecoder[FiberStatus] =
    QueryParamDecoder[String].emap { s =>
      FiberStatus.withNameOption(s).toRight(org.http4s.ParseFailure(s, s"Invalid FiberStatus: $s"))
    }

  object StatusQueryParam extends OptionalQueryParamDecoderMatcher[FiberStatus]("status")

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

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

    case GET -> Root / "oracles" / UUIDVar(oracleId) / "invocations" =>
      context
        .getOnChainState[OnChain]
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(oracleId, List.empty)
            .collect { case i: OracleInvocation => i }
        })
        .toResponse
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
