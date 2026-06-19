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
import xyz.kd5ujc.schema.api.{HashResult, VersionInfo}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

/** Service meta + raw-state logic: version info, message hashing, on-chain + calculated state. */
class MetaHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
)(implicit
  context: L0NodeContext[F]
) {

  val version: VersionInfo =
    VersionInfo(
      service = "ottochain-ml0",
      version = BuildInfo.version,
      name = BuildInfo.name,
      scalaVersion = BuildInfo.scalaVersion,
      sbtVersion = BuildInfo.sbtVersion,
      gitCommit = BuildInfo.gitCommit,
      buildTime = BuildInfo.buildTime,
      tessellationVersion = io.constellationnetwork.BuildInfo.version
    )

  def hash(message: OttochainMessage): F[HashResult] =
    message.computeDigest.map(digest => HashResult(digest, message))

  def onChain: F[Either[DataApplicationValidationError, OnChain]] =
    context.getOnChainState[OnChain]

  def checkpoint: F[Either[DataApplicationValidationError, Checkpoint[CalculatedState]]] =
    checkpointService.get.map(_.asRight[DataApplicationValidationError])
}
