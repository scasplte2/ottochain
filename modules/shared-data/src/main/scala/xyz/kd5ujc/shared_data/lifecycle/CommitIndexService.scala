package xyz.kd5ujc.shared_data.lifecycle

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L1NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CommittedOnChain
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L1ContextOps
import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.{CommitIndex, OnChain}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * The DL1's recreated commit-index cache (onchain-incrementals RFC §3.3).
 *
 * OnChain v2 carries only per-batch deltas, so the cumulative view the L1 structural gate needs
 * is a FOLD over contiguous deltas:
 *   - `snapshot.ordinal == cache.ordinal + 1` → fold the observed delta (no extra I/O);
 *   - anything else (first sync, skipped ordinals, restart) → HEAL from ML0's
 *     `/v1/commit-index`. Folding across a gap is never allowed: lost `touched*` writes make the
 *     structural gate FAIL OPEN for unknown ids (create-dup passes, and transitions of
 *     gap-created fibers get spuriously rejected).
 *   - heal failure keeps the checkpoint UNCHANGED (stale-or-unsynced, retried on the next call)
 *     rather than silently adopting an incomplete base. The combiner remains the authoritative
 *     stateful gate either way.
 *
 * Shared by `Validator.validateUpdate` (refresh-then-validate) and the DL1's
 * `GET /v1/commit-index` route (refresh-then-serve) — so both the ingestion gate and the surface
 * the e2e harness/SDK polls for DL1 sync observe the SAME folded view, and polling the route
 * drives the same fold/heal the gate relies on.
 */
trait CommitIndexService[F[_]] {

  /** Refresh from the latest snapshot (fold or heal), then return the current view. */
  def refreshed(implicit ctx: L1NodeContext[F]): F[Either[DataApplicationValidationError, Checkpoint[CommitIndex]]]
}

object CommitIndexService {

  def make[F[_]: Async](healClient: Option[CommitIndexHealClient[F]]): F[CommitIndexService[F]] =
    // `None` = never synced: deltas can only be folded from a complete base, so the very first
    // refresh must heal (or degrade) — the initial ordinal is a sentinel, not a base.
    CheckpointService.make[F, Option[CommitIndex]](None).map { checkpointService =>
      new CommitIndexService[F] {

        private val logger: SelfAwareStructuredLogger[F] =
          Slf4jLogger.getLoggerFromClass(CommitIndexService.getClass)

        def refreshed(implicit
          ctx: L1NodeContext[F]
        ): F[Either[DataApplicationValidationError, Checkpoint[CommitIndex]]] =
          checkpointService
            .evalModify[DataApplicationValidationError] { checkpoint =>
              ctx.getLatestCurrencySnapshot.flatMap {
                case Right(snapshot) if snapshot.ordinal > checkpoint.ordinal || checkpoint.state.isEmpty =>
                  // ML0 commits CommittedOnChain[OnChain] (makeL0 wraps OnChain with the committed
                  // breadcrumb); unwrap .inner to get the per-batch delta.
                  ctx.getOnChainState[CommittedOnChain[OnChain]].flatMap {
                    case Right(committed) =>
                      val delta = committed.inner
                      checkpoint.state match {
                        case Some(index) if snapshot.ordinal.value.value === checkpoint.ordinal.value.value + 1L =>
                          val folded = CommitIndex.fold(index, delta)
                          logger
                            .info(
                              s"[DL1-cache] FOLDED delta: ordinal=${snapshot.ordinal} " +
                              s"touchedFibers=${delta.touchedFiberCommits.size} " +
                              s"touchedAssets=${delta.touchedAssetCommits.size} burns=${delta.burnedAssets.size} " +
                              s"indexFibers=${folded.fiberCommits.size}"
                            )
                            .as(
                              Checkpoint(snapshot.ordinal, (folded: CommitIndex).some)
                                .asRight[DataApplicationValidationError]
                            )
                        case Some(_) if snapshot.ordinal === checkpoint.ordinal =>
                          // only reachable via the isEmpty guard, which excludes Some — keep as-is
                          checkpoint.asRight[DataApplicationValidationError].pure[F]
                        case _ =>
                          heal(snapshot.ordinal, delta, checkpoint)
                      }
                    case Left(err) =>
                      logger.warn(s"[DL1-cache] REFRESH FAILED: $err").as(err.asLeft[Checkpoint[Option[CommitIndex]]])
                  }
                case Right(snapshot) =>
                  logger.debug(
                    s"[DL1-cache] NO REFRESH: snapshotOrdinal=${snapshot.ordinal} == cacheOrdinal=${checkpoint.ordinal}"
                  ) *> checkpoint.asRight[DataApplicationValidationError].pure[F]
                case Left(err) =>
                  logger.warn(s"[DL1-cache] SNAPSHOT ERROR: $err").as(err.asLeft[Checkpoint[Option[CommitIndex]]])
              }
            }
            .map(_.map(cp => Checkpoint(cp.ordinal, cp.state.getOrElse(CommitIndex.empty))))

        /**
         * Re-seed the index from ML0 (gap / first sync). Adoption cases relative to the locally
         * observed `snapshotOrdinal`:
         *   - healed at or ahead of it → adopt verbatim (the gate tolerates a fresher index);
         *   - healed exactly one behind → adopt + fold the locally observed delta on top;
         *   - healed further behind → adopt what we got and let the next refresh retry (ordinal
         *     stays behind, so the `>` guard fires again).
         */
        private def heal(
          snapshotOrdinal: SnapshotOrdinal,
          observedDelta:   OnChain,
          prev:            Checkpoint[Option[CommitIndex]]
        ): F[Either[DataApplicationValidationError, Checkpoint[Option[CommitIndex]]]] =
          healClient match {
            case Some(client) =>
              client.fetch.attempt.flatMap {
                case Right(res) =>
                  val adopted =
                    if (res.ordinal.value.value + 1L === snapshotOrdinal.value.value)
                      Checkpoint(snapshotOrdinal, (CommitIndex.fold(res.index, observedDelta): CommitIndex).some)
                    else
                      Checkpoint(res.ordinal, res.index.some)
                  logger
                    .info(
                      s"[DL1-cache] HEALED: ml0Ordinal=${res.ordinal} localOrdinal=$snapshotOrdinal " +
                      s"indexFibers=${adopted.state.map(_.fiberCommits.size).getOrElse(0)} " +
                      s"(prev=${prev.ordinal}${if (prev.state.isEmpty) ", unsynced" else ""})"
                    )
                    .as(adopted.asRight[DataApplicationValidationError])
                case Left(e) =>
                  logger
                    .error(
                      s"[DL1-cache] HEAL FAILED (${e.getMessage}): keeping " +
                      s"${if (prev.state.isEmpty) "UNSYNCED (validating against an empty index!)"
                        else s"stale ordinal=${prev.ordinal}"}; " +
                      "will retry on next update"
                    )
                    .as(prev.asRight[DataApplicationValidationError])
              }
            case None =>
              // transport-less dev mode: fold onto whatever base we have and advance, accepting
              // potential incompleteness — loudly, every gap.
              val base = prev.state.getOrElse(CommitIndex.empty)
              logger
                .error(
                  s"[DL1-cache] ORDINAL GAP with NO heal client: ${prev.ordinal} -> $snapshotOrdinal; " +
                  "folding onto a possibly-incomplete index (structural gate degraded; dev mode only)"
                )
                .as(
                  Checkpoint(snapshotOrdinal, (CommitIndex.fold(base, observedDelta): CommitIndex).some)
                    .asRight[DataApplicationValidationError]
                )
          }
      }
    }
}
