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

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoder
import org.http4s.server.Router

class DataL1CustomRoutes[F[_]: Async](implicit
  context: L1NodeContext[F]
) extends MetagraphPublicRoutes[F] {

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {
    // Version endpoint for monitoring integration
    case GET -> Root / "version" =>
      Ok(
        Json.obj(
          "service"             -> "ottochain-dl1".asJson,
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
      // ML0 commits CommittedOnChain[OnChain]; unwrap .inner so this route returns the plain OnChain
      // (clients and the e2e harness see the unchanged shape).
      context.getOnChainState[CommittedOnChain[OnChain]].map(_.map(_.inner)).toResponse
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
