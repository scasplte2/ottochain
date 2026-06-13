package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.ext.http4s.error.RefinedRequestApplicationDecoder
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router

/** Service meta + raw-state endpoints: version, hashing util, on-chain state, calculated checkpoint. */
class ML0MetaRoutes[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
)(implicit
  context: L0NodeContext[F]
) extends MetagraphPublicRoutes[F] {

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
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
