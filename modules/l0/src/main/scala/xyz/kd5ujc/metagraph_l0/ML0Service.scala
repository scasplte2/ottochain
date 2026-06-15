package xyz.kd5ujc.metagraph_l0

import cats.Parallel
import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CatalogJournal, CommittedApp, CommittedReader}
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, CombinerService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import xyz.kd5ujc.metagraph_l0.webhooks.{NotificationStats, SubscriberRegistry, WebhookDispatcher}
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberStatus
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
    combiner           <- Combiner.make[F]().pure[F]
    validator          <- Validator.make[F]

    webhookDispatcher = httpClient.map(client => WebhookDispatcher.make[F](client, subscriberRegistry, metagraphId))

    service <- CommittedApp.makeL0[F, OttochainMessage, OnChain, CalculatedState](
      genesisState,
      orderedCombiner(combiner),
      rejectionNotifyingValidator(validator, checkpointService, webhookDispatcher),
      journal,
      extraRoutes = Some { (_: CommittedReader[F, CalculatedState], context: L0NodeContext[F]) =>
        implicit val ctx: L0NodeContext[F] = context
        ml0Routes(checkpointService, subscriberRegistry)
      },
      onConsensusResult =
        Some((reader, snapshot) => onConsensus(reader, snapshot, checkpointService, webhookDispatcher))
    )
  } yield service

  /**
   * Total-order the batch and clear `latestLogs` before folding, then delegate. The message-level
   * `signedOrdering` is only PARTIAL (same-name registry ops and duplicate (fiber, sequence) ops
   * tie); break ties by the signed update's content digest so the surviving op is identical across
   * nodes (no fork) while the loser becomes a RejectionReceipt rather than aborting the batch.
   */
  private def orderedCombiner[F[+_]: Async](
    inner: CombinerService[F, OttochainMessage, OnChain, CalculatedState]
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
        for {
          keyed <- batch.traverse(u => u.computeDigest.map(h => u -> h.value))
          totalOrdering = Ordering.Tuple2(OttochainMessage.signedOrdering, Ordering.String)
          sorted = keyed.sorted(totalOrdering).map(_._1)
          result <- inner.foldLeft(previous.focus(_.onChain.latestLogs).replace(SortedMap.empty), sorted)
        } yield result
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
        for {
          ordinal <- checkpointService.get.map(_.ordinal)
          results <- batch.toList.traverse(su => inner.validateSignedUpdate(current, su).map(su -> _))
          _ <- webhookDispatcher match {
            case Some(dispatcher) =>
              results.collect { case (su, Validated.Invalid(errors)) =>
                Async[F].start(dispatcher.dispatchRejection(ordinal, su, errors)).void
              }.sequence_
            case None => Async[F].unit
          }
        } yield results.map(_._2).combineAll
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
    webhookDispatcher: Option[WebhookDispatcher[F]]
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
          val stats = NotificationStats(
            updatesProcessed = updatesProcessed,
            stateMachinesActive =
              committed.state.stateMachines.count { case (_, fiber) => fiber.status == FiberStatus.Active },
            scriptsActive = committed.state.scripts.count { case (_, script) => script.status == FiberStatus.Active }
          )
          Async[F].start(dispatcher.dispatch(snapshot, stats)).void
        case None => Async[F].unit
      }
    } yield ()).handleErrorWith(logger.error(_)("Error during onSnapshotConsensusResult"))

  private def ml0Routes[F[+_]: Async](
    checkpointService:  CheckpointService[F, CalculatedState],
    subscriberRegistry: SubscriberRegistry[F]
  )(implicit context: L0NodeContext[F]): HttpRoutes[F] =
    new ML0Routes[F](
      new handlers.MetaHandler[F](checkpointService),
      new handlers.StateMachineHandler[F](checkpointService),
      new handlers.ScriptHandler[F](checkpointService),
      new handlers.RegistryHandler[F](checkpointService),
      new handlers.WebhookHandler[F](subscriberRegistry),
      new handlers.EstimateHandler[F](checkpointService)
    ).public
}
