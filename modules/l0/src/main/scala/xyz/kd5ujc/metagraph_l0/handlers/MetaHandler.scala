package xyz.kd5ujc.metagraph_l0.handlers

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import io.circe.Json
import io.circe.syntax.EncoderOps

/** Service meta + raw-state logic: version info, message hashing, on-chain + calculated state. */
class MetaHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
)(implicit
  context: L0NodeContext[F]
) {

  val version: Json =
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

  def hash(message: OttochainMessage): F[Json] =
    message.computeDigest.map { digest =>
      Json.obj("protocol message hash" -> digest.asJson, "protocol message" -> message.asJson)
    }

  def onChain: F[Either[DataApplicationValidationError, OnChain]] =
    context.getOnChainState[OnChain]

  def checkpoint: F[Either[DataApplicationValidationError, Checkpoint[CalculatedState]]] =
    checkpointService.get.map(_.asRight[DataApplicationValidationError])
}
