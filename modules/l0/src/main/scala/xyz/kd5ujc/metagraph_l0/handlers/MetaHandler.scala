package xyz.kd5ujc.metagraph_l0.handlers

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.api.{CommitIndexResponse, HashResult, VersionInfo}
import xyz.kd5ujc.schema.{CalculatedState, CommitIndex, OnChain}

import monocle.Monocle.toAppliedFocusOps

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
    checkpointService.get.map { cp =>
      // The protocol nullifier set is UNBOUNDED (monotonic, never pruned — protocol-nullifier-set.md), and
      // /v1/checkpoint is the only whole-state JSON serialization surface, so the set is EXCLUDED here — a
      // handler-level slim ONLY (the canonical encoder / committed hashing paths are untouched). Nullifier
      // reads are served by the dedicated GET /v1/nullifiers/{domain}/{nf} route + committed state proofs.
      cp.focus(_.state.nullifiers).replace(SortedMap.empty).asRight[DataApplicationValidationError]
    }

  /**
   * The full recreated commit maps at the last committed ordinal (onchain-incrementals RFC §3.4):
   * the back-compat surface for consumers of the v1 cumulative `/v1/onchain`, and the DL1 heal
   * source. Served from the per-snapshot checkpoint cache — same freshness as `/v1/checkpoint`.
   */
  def commitIndex: F[Either[DataApplicationValidationError, CommitIndexResponse]] =
    checkpointService.get.map { cp =>
      CommitIndexResponse(cp.ordinal, CommitIndex.fromCalculated(cp.state)).asRight[DataApplicationValidationError]
    }
}
