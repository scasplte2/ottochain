package xyz.kd5ujc.metagraph_l0

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CatalogJournal, CommittedApp, CommittedReader}
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, CombinerService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import xyz.kd5ujc.metagraph_l0.webhooks.{SubscriberRegistry, WebhookDispatcher}
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.api.webhooks.{NotificationStats, SnapshotRejection}
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberStatus}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.genesis.GenesisLoader
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}

import fs2.io.file.Files
import monocle.Monocle.toAppliedFocusOps
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object ML0Service {

  /**
   * The ML0 data application, committed-state edition. `CommittedApp.makeL0` owns the on-chain
   * wrapping, combine/validateData dispatch, the two-tier committed root (`hashCalculatedState` ->
   * the snapshot's `calculatedStateProof`), get/setCalculatedState, and the `/committed/...` routes.
   * We hand it:
   *   - [[orderedCombiner]]: total-orders the batch (so every node folds the identical sequence) and
   *     clears `latestLogs` before folding, then delegates to the registry/fiber combiner;
   *   - [[rejectionNotifyingValidator]]: per-update validation with fire-and-forget rejection
   *     webhooks (the dispatch the framework's batch `validateData` would otherwise drop);
   *   - `extraRoutes`: the existing `ML0Routes` handlers (still reading the notification-side
   *     [[CheckpointService]] cache, refreshed once per snapshot by the consensus hook);
   *   - `onConsensusResult`: refresh that cache from the committed cell and dispatch the per-snapshot
   *     webhook (replaces the old hand-rolled `onSnapshotConsensusResult`).
   *
   * @param journal the committed-catalog journal (LevelDB in production) — REQUIRED: without it a
   *                seeded/restarted node cannot hydrate and stalls. See the migration proposal.
   */
  def make[F[+_]: Async: Files: Parallel: SecurityProvider](
    journal:     CatalogJournal[F],
    httpClient:  Option[Client[F]] = None,
    metagraphId: String = "DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG",
    genesisPath: Option[String] = None
  ): F[BaseDataApplicationL0Service[F]] = for {
    implicit0(logger: SelfAwareStructuredLogger[F]) <- Slf4jLogger.create[F]
    genesisState                                    <- GenesisLoader.load[F](genesisPath)
    checkpointService  <- CheckpointService.make[F, CalculatedState](genesisState.calculated)
    subscriberRegistry <- SubscriberRegistry.make[F]
    rejectionSink      <- Ref.of[F, List[FiberLogEntry.RejectionReceipt]](List.empty)
    combiner = Combiner.make[F]()
    validator <- Validator.make[F]()

    webhookDispatcher = httpClient.map(client => WebhookDispatcher.make[F](client, subscriberRegistry, metagraphId))

    service <- CommittedApp.makeL0[F, OttochainMessage, OnChain, CalculatedState](
      genesisState,
      orderedCombiner(combiner, rejectionSink),
      rejectionNotifyingValidator(validator, checkpointService, webhookDispatcher),
      journal,
      extraRoutes = Some { (reader: CommittedReader[F, CalculatedState], context: L0NodeContext[F]) =>
        implicit val ctx: L0NodeContext[F] = context
        ml0Routes(checkpointService, subscriberRegistry, reader)
      },
      onConsensusResult =
        Some((reader, snapshot) => onConsensus(reader, snapshot, checkpointService, webhookDispatcher, rejectionSink))
    )
  } yield service

  /**
   * Clear the per-batch OnChain delta (`latestLogs` + `touched*` + `burnedAssets` — under OnChain v2
   * EVERY OnChain field is per-batch; the cumulative maps live in CalculatedState), then sort the
   * batch by the TOTAL `OttochainMessage.signedOrdering` (signature tiebreak lives in models) so
   * every node folds the identical sequence — the surviving op on a tie is the same network-wide
   * (no fork), the loser becomes a RejectionReceipt — then delegate to the registry/fiber combiner.
   * No Hasher in the combine path now that the ordering is total.
   */
  private def orderedCombiner[F[+_]: Async](
    inner:         CombinerService[F, OttochainMessage, OnChain, CalculatedState],
    rejectionSink: Ref[F, List[FiberLogEntry.RejectionReceipt]]
  ): CombinerService[F, OttochainMessage, OnChain, CalculatedState] =
    new CombinerService[F, OttochainMessage, OnChain, CalculatedState] {

      def insert(
        previous: DataState[OnChain, CalculatedState],
        update:   Signed[OttochainMessage]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
        inner.insert(previous, update)

      override def foldLeft(
        previous: DataState[OnChain, CalculatedState],
        batch:    List[Signed[OttochainMessage]]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
        inner
          .foldLeft(
            previous.focus(_.onChain).replace(OnChain.genesis),
            batch.sorted(OttochainMessage.signedOrdering)
          )
          .flatTap { result =>
            // The framework invokes foldLeft (NOT insert) for the batch, so the capture MUST live here.
            // latestLogs was just cleared above, so result.latestLogs holds ONLY this batch's receipts —
            // collect its RejectionReceipts into the reliable notification sink for onConsensus to drain
            // (the serialized snapshot's latestLogs is re-combined away and unreliable to read
            // post-finalization). A FLAT list, drained whole each snapshot — receipts carry their own
            // ordinal for the client's sinceOrdinal filter; re-combine duplicates are deduped
            // client-side. flatTap leaves `result` untouched, so consensus is unaffected.
            val rejects = result.onChain.latestLogs.values.flatten.collect { case r: FiberLogEntry.RejectionReceipt =>
              r
            }.toList
            rejectionSink.update(_ ++ rejects).whenA(rejects.nonEmpty)
          }
    }

  /**
   * Per-update validation with fire-and-forget rejection webhooks. `makeL0` routes the framework's
   * batch `validateData` straight here; the default batch validator would accumulate errors but drop
   * the per-update rejection dispatch, so we re-add it.
   */
  private def rejectionNotifyingValidator[F[+_]: Async: Parallel](
    inner:             ValidationService[F, OttochainMessage, OnChain, CalculatedState],
    checkpointService: CheckpointService[F, CalculatedState],
    webhookDispatcher: Option[WebhookDispatcher[F]]
  ): ValidationService[F, OttochainMessage, OnChain, CalculatedState] =
    new ValidationService[F, OttochainMessage, OnChain, CalculatedState] {

      def validateUpdate(update: OttochainMessage)(implicit
        ctx: L1NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        inner.validateUpdate(update)

      def validateSignedUpdate(
        current:      DataState[OnChain, CalculatedState],
        signedUpdate: Signed[OttochainMessage]
      )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        inner.validateSignedUpdate(current, signedUpdate)

      override def validateData(
        current: DataState[OnChain, CalculatedState],
        batch:   NonEmptyList[Signed[OttochainMessage]]
      )(implicit ctx: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        // PARTIAL BLOCK ACCEPTANCE — the framework fix the Validator.scala TOCTOU notes defer to (#154).
        // `combineAll` would make ONE invalid update void the ENTIRE data block (all-or-nothing),
        // dropping every VALID sibling batched with it; those fibers' runners then resubmit and
        // re-poison — the parallel-flow cascade. The per-update failures that hit here under load are
        // stateful/TOCTOU (stale-seq resubmit, already-applied create, now-redundant transition), NOT
        // structural — DL1 already gated structure, and the combiner re-checks each rule and skips
        // gracefully (CombineRejected -> RejectionReceipt, unmutated state) while applying the valid
        // ones; a genuine non-deterministic error still propagates and aborts. Returning Valid is
        // therefore consensus-safe (every node computes the same skip set) and keeps committed state
        // correct. Rejections are batched onto the post-finalization `snapshot.finalized` webhook,
        // drained from the committed snapshot's RejectionReceipts in `onConsensus`.
        Async[F].pure(().validNec[DataApplicationValidationError])
    }

  /**
   * Per-snapshot consensus hook: refresh the notification-side calculated-state cache from the
   * committed cell (so the HTTP handlers and the webhook stats read the current value), then
   * fire-and-forget the snapshot webhook.
   */
  private def onConsensus[F[+_]: Async](
    reader:            CommittedReader[F, CalculatedState],
    snapshot:          Hashed[CurrencyIncrementalSnapshot],
    checkpointService: CheckpointService[F, CalculatedState],
    webhookDispatcher: Option[WebhookDispatcher[F]],
    rejectionSink:     Ref[F, List[FiberLogEntry.RejectionReceipt]]
  )(implicit logger: SelfAwareStructuredLogger[F]): F[Unit] =
    (for {
      committed <- reader.committed
      _         <- checkpointService.set(Checkpoint(committed.ordinal, committed.state))
      // Count from the snapshot's data part directly (the typed decode needs codecs this standalone
      // hook doesn't carry); updateHashes is the per-snapshot accepted-update set.
      updatesProcessed = snapshot.signed.value.dataApplication.flatMap(_.updateHashes).fold(0)(_.size)
      _ <- logger.info(s"Snapshot ordinal ${snapshot.ordinal.value}: $updatesProcessed updates")
      _ <- webhookDispatcher match {
        case Some(dispatcher) =>
          for {
            // Drain the reliable combine-time rejection sink for this snapshot's ordinal. The snapshot's
            // serialized `latestLogs` is NOT a reliable drain source: pending updates are re-combined every
            // snapshot until GL0-finalizes (latestLogs cleared + repopulated each combine), so the FINALIZED
            // copy this hook reads usually shows an empty/partial set (measured: 64 combine-rejects, only ~8
            // surfaced). The combiner instead records every reject here at combine time, keyed by ordinal.
            // Drain the whole sink (atomic getAndSet): everything the combine recorded since the last
            // snapshot's hook. Receipts carry their own ordinal; the client filters by sinceOrdinal.
            drained <- rejectionSink.getAndSet(List.empty)
            rejections = drained.map { r =>
              SnapshotRejection(
                updateType = r.updateType,
                fiberId = r.fiberId,
                targetSequenceNumber = r.targetSequenceNumber,
                actualSequenceNumber = r.actualSequenceNumber,
                reason = r.reason,
                updateHash = r.updateHash
              )
            }
            _ <- logger.info(s"[rej-drain] ord=${snapshot.ordinal.value} rejections=${rejections.size}")
            stats = NotificationStats(
              updatesProcessed = updatesProcessed,
              stateMachinesActive =
                committed.state.stateMachines.count { case (_, fiber) => fiber.status == FiberStatus.Active },
              scriptsActive = committed.state.scripts.count { case (_, script) => script.status == FiberStatus.Active },
              rejectedCount = rejections.size
            )
            _ <- Async[F].start(dispatcher.dispatch(snapshot, stats, rejections)).void
          } yield ()
        case None => Async[F].unit
      }
    } yield ()).handleErrorWith(logger.error(_)("Error during onSnapshotConsensusResult"))

  private def ml0Routes[F[+_]: Async](
    checkpointService:  CheckpointService[F, CalculatedState],
    subscriberRegistry: SubscriberRegistry[F],
    reader:             CommittedReader[F, CalculatedState]
  )(implicit context: L0NodeContext[F]): HttpRoutes[F] =
    new ML0Routes[F](
      new handlers.MetaHandler[F](checkpointService),
      new handlers.StateMachineHandler[F](checkpointService),
      new handlers.ScriptHandler[F](checkpointService),
      new handlers.RegistryHandler[F](checkpointService),
      new handlers.WebhookHandler[F](subscriberRegistry),
      new handlers.EstimateHandler[F](checkpointService),
      new handlers.StateProofHandler[F](reader)
    ).public <+> openapi.OpenApiRoutes.routes[F]
}
