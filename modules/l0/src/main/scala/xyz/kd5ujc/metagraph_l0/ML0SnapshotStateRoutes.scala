package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CommittedOnChain
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.schema.OnChain
import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}

import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.server.Router

/**
 * The L0 routes that read the latest SIGNED snapshot's on-chain state from the
 * [[L0NodeContext]] (vs the committed-cell-backed reads in [[ML0CustomRoutes]]).
 *
 * The registered on-chain type is `CommittedOnChain[OnChain]` (the breadcrumb wrapper added by
 * `CommittedApp.makeL0`), so the snapshot bytes are decoded as the wrapper and unwrapped here --
 * the response shapes are unchanged from the pre-committed implementation. The breadcrumb itself
 * is served by `GET /committed/root`.
 */
class ML0SnapshotStateRoutes[F[_]: Async](implicit context: L0NodeContext[F]) extends MetagraphPublicRoutes[F] {

  implicit private val onChainEncoder: Encoder[CommittedOnChain[OnChain]] = CommittedOnChain.encoder[OnChain]
  implicit private val onChainDecoder: Decoder[CommittedOnChain[OnChain]] = CommittedOnChain.decoder[OnChain]

  private def latestOnChain = context.getOnChainState[CommittedOnChain[OnChain]].map(_.map(_.inner))

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "onchain" =>
      latestOnChain.toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) / "events" =>
      latestOnChain
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(fiberId, List.empty)
            .collect { case r: EventReceipt => r }
        })
        .toResponse

    case GET -> Root / "oracles" / UUIDVar(scriptId) / "invocations" =>
      latestOnChain
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(scriptId, List.empty)
            .collect { case i: OracleInvocation => i }
        })
        .toResponse
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
