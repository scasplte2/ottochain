package xyz.kd5ujc.data_l1

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L1NodeContext
import io.constellationnetwork.ext.http4s.error.RefinedRequestApplicationDecoder
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CommittedOnChain
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryCodec._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.L1ContextOps
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.schema.OnChain
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.api.{CommitIndexResponse, HashResult, VersionInfo}
import xyz.kd5ujc.shared_data.lifecycle.CommitIndexService

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router

class DataL1CustomRoutes[F[_]: Async](commitIndexService: CommitIndexService[F])(implicit
  context: L1NodeContext[F]
) extends MetagraphPublicRoutes[F] {

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {
    // Version endpoint for monitoring integration
    case GET -> Root / "version" =>
      Ok(
        VersionInfo(
          service = "ottochain-dl1",
          version = BuildInfo.version,
          name = BuildInfo.name,
          scalaVersion = BuildInfo.scalaVersion,
          sbtVersion = BuildInfo.sbtVersion,
          gitCommit = BuildInfo.gitCommit,
          buildTime = BuildInfo.buildTime,
          tessellationVersion = io.constellationnetwork.BuildInfo.version
        )
      )

    case req @ POST -> Root / "util" / "hash" =>
      req.asR[Signed[OttochainMessage]] { msg =>
        msg.value.computeDigest.flatMap(digest => Ok(HashResult(digest, msg.value)))
      }

    case GET -> Root / "onchain" =>
      // ML0 commits CommittedOnChain[OnChain]; unwrap .inner so this route returns the plain OnChain.
      // Under OnChain v2 this is the PER-BATCH delta — cumulative consumers use /v1/commit-index.
      context.getOnChainState[CommittedOnChain[OnChain]].map(_.map(_.inner)).toResponse

    case GET -> Root / "commit-index" =>
      // This node's OWN folded/healed cumulative view (onchain-incrementals RFC §3.3) — the surface
      // the e2e harness/SDK polls for DL1 sync. Reading it drives the same fold/heal refresh the
      // ingestion gate uses, so the two cannot disagree.
      commitIndexService.refreshed
        .map(_.map(cp => CommitIndexResponse(cp.ordinal, cp.state)))
        .toResponse
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
