package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.schema.fiber.FiberLogEntry.EventReceipt
import xyz.kd5ujc.schema.fiber.{AuditRenderer, FiberStatus}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import org.http4s.server.Router
import org.http4s.{HttpRoutes, QueryParamDecoder}

/** State-machine fiber endpoints: list (optionally by status), single record, event log, audit render. */
class ML0StateMachineRoutes[F[_]: Async](
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
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
