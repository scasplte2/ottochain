package xyz.kd5ujc.metagraph_l0

import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._
import cats.{Monad, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{
  DataApplicationBlock,
  DataApplicationValidationErrorOr
}
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CatalogJournal, CommittedApp}
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, CombinerService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.CurrencyIncrementalSnapshotOps
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

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
   * Create the ML0 service: metakit's `CommittedApp.makeL0` (two-tier state-root commitment over
   * the `CommittedView[CalculatedState]` projection, on-chain breadcrumb, `/committed/...` routes)
   * plus ottochain's webhook/subscriber dispatch layered on top.
   *
   * @param httpClient HTTP client for webhook delivery
   * @param metagraphId The metagraph token identifier (for webhook notifications)
   */
  def make[F[+_]: Async: Files: Parallel: SecurityProvider](
    httpClient:  Option[Client[F]] = None,
    metagraphId: String = "DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG",
    genesisPath: Option[String] = None,
    // Local catalog journal (acquired as a Resource in Main and threaded in): lets a seeded committed
    // cell re-hydrate from its own persisted catalog so `combine`/`advanceWork` can resolve the parent
    // breadcrumb. Without it a seeded cell stays unhydrated and the metagraph cannot advance.
    journal: Option[CatalogJournal[F]] = None
  ): F[BaseDataApplicationL0Service[F]] = for {
    implicit0(logger: SelfAwareStructuredLogger[F]) <- Slf4jLogger.create[F]
    genesisState                                    <- GenesisLoader.load[F](genesisPath)

    // The authoritative calculated state lives in the committed cell owned by
    // CommittedApp.makeL0; this checkpoint is only the notification-side cache
    // (rejection ordinals, consensus stats), seeded from the loaded genesis and
    // refreshed on setCalculatedState exactly like the pre-committed implementation.
    checkpointService  <- CheckpointService.make[F, CalculatedState](genesisState.calculated)
    subscriberRegistry <- SubscriberRegistry.make[F]
    combiner           <- Combiner.make[F]().pure[F]
    validator          <- Validator.make[F]

    // Create webhook dispatcher if http client is provided
    webhookDispatcher = httpClient.map { client =>
      WebhookDispatcher.make[F](client, subscriberRegistry, metagraphId)
    }

    committedService <- CommittedApp.makeL0[F, OttochainMessage, OnChain, CalculatedState](
      genesisState,
      orderedCombiner(combiner),
      rejectionNotifyingValidator(validator, checkpointService, webhookDispatcher),
      extraRoutes = Some(reader => new ML0CustomRoutes[F](reader, subscriberRegistry).public),
      journal = journal
    )
  } yield withConsensusHooks(committedService, checkpointService, webhookDispatcher)

  /**
   * Sorts each batch with the canonical `OttochainMessage` ordering (creates before transitions,
   * transitions per fiber in sequence-number order) and resets the per-snapshot `latestLogs`
   * before folding -- the exact behavior of the previous hand-rolled `combine` override, expressed
   * as the dev combiner handed to `CommittedApp.makeL0`.
   */
  private def orderedCombiner[F[_]: Monad](
    inner: CombinerService[F, OttochainMessage, OnChain, CalculatedState]
  ): CombinerService[F, OttochainMessage, OnChain, CalculatedState] =
    new CombinerService[F, OttochainMessage, OnChain, CalculatedState] {

      override def foldLeft(
        previous: DataState[OnChain, CalculatedState],
        batch:    List[Signed[OttochainMessage]]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
        // OttochainMessage.signedOrdering is a TOTAL order (signature tiebreak in models), so a plain sort
        // makes every node fold the identical sequence -- no per-combiner digest tiebreak needed.
        inner.foldLeft(
          previous.focus(_.onChain.latestLogs).replace(SortedMap.empty),
          batch.sorted(OttochainMessage.signedOrdering)
        )

      override def insert(
        previous: DataState[OnChain, CalculatedState],
        update:   Signed[OttochainMessage]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
        inner.insert(previous, update)
    }

  /**
   * Per-update validation with fire-and-forget rejection webhooks, expressed as the dev validator
   * handed to `CommittedApp.makeL0` (whose `validateData` delegates here with the unwrapped
   * state). Result accumulation is identical to the previous hand-rolled override.
   */
  private def rejectionNotifyingValidator[F[+_]: Async: Parallel](
    inner:             ValidationService[F, OttochainMessage, OnChain, CalculatedState],
    checkpointService: CheckpointService[F, CalculatedState],
    webhookDispatcher: Option[WebhookDispatcher[F]]
  ): ValidationService[F, OttochainMessage, OnChain, CalculatedState] =
    new ValidationService[F, OttochainMessage, OnChain, CalculatedState] {

      override def validateUpdate(
        update: OttochainMessage
      )(implicit ctx: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        inner.validateUpdate(update)

      override def validateSignedUpdate(
        current:      DataState[OnChain, CalculatedState],
        signedUpdate: Signed[OttochainMessage]
      )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        inner.validateSignedUpdate(current, signedUpdate)

      override def validateData(
        current: DataState[OnChain, CalculatedState],
        batch:   NonEmptyList[Signed[OttochainMessage]]
      )(implicit ctx: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        for {
          // Current ordinal for rejection tracking (notification metadata only)
          ordinal <- checkpointService.get.map(_.ordinal)

          // Validate each update individually to track per-update rejections
          results <- batch.toList.traverse { signedUpdate =>
            inner.validateSignedUpdate(current, signedUpdate).map(signedUpdate -> _)
          }

          // Dispatch rejections (fire-and-forget) for failed validations
          _ <- webhookDispatcher match {
            case Some(dispatcher) =>
              results.collect { case (signedUpdate, Validated.Invalid(errors)) =>
                Async[F].start(dispatcher.dispatchRejection(ordinal, signedUpdate, errors)).void
              }.sequence_
            case None =>
              Async[F].unit
          }

          // Return combined result (all errors accumulated)
        } yield results.map(_._2).combineAll
    }

  /**
   * Delegating wrapper adding the consensus-result webhook dispatch and the snapshot-backed
   * custom routes on top of the service assembled by `CommittedApp.makeL0`.
   *
   * FLAGGED metakit follow-up: `CommittedApp.makeL0` currently exposes no
   * `onSnapshotConsensusResult` hook and its `extraRoutes` function receives only the
   * `CommittedReader` (not the `L0NodeContext`), so (a) webhook dispatch and (b) routes that read
   * the latest signed snapshot have to be layered here. Once makeL0 grows an
   * `onConsensusResult` argument and context-aware `extraRoutes`, this wrapper disappears.
   */
  private def withConsensusHooks[F[+_]: Async](
    underlying:        BaseDataApplicationL0Service[F],
    checkpointService: CheckpointService[F, CalculatedState],
    webhookDispatcher: Option[WebhookDispatcher[F]]
  )(implicit logger: SelfAwareStructuredLogger[F]): BaseDataApplicationL0Service[F] =
    new BaseDataApplicationL0Service[F] {

      // getSignedUpdates decodes the snapshot's blocks via the service's own update codecs
      implicit private val dataUpdateEncoder: io.circe.Encoder[DataUpdate] = underlying.dataEncoder
      implicit private val dataUpdateDecoder: io.circe.Decoder[DataUpdate] = underlying.dataDecoder

      override def genesis: DataState.Base = underlying.genesis

      override def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit] =
        (for {
          signedUpdates <- snapshot.signed.value.getSignedUpdates[OttochainMessage]
          _             <- logger.info(s"Got ${signedUpdates.size} updates for ordinal: ${snapshot.ordinal.value}")

          // Dispatch webhooks if dispatcher is configured
          _ <- webhookDispatcher match {
            case Some(dispatcher) =>
              // Get current state for notification stats
              checkpointService.get.flatMap { case Checkpoint(_, state) =>
                val stats = NotificationStats(
                  updatesProcessed = signedUpdates.size,
                  stateMachinesActive = state.stateMachines.count { case (_, fiber) =>
                    fiber.status == FiberStatus.Active
                  },
                  scriptsActive = state.scripts.count { case (_, script) =>
                    script.status == FiberStatus.Active
                  }
                )

                // Fire-and-forget: start webhook dispatch but don't wait for it
                Async[F].start(dispatcher.dispatch(snapshot, stats)).void
              }

            case None =>
              Async[F].unit
          }
        } yield ()).handleErrorWith(logger.error(_)("Error during onSnapshotConsensusResult")) >>
        underlying.onSnapshotConsensusResult(snapshot)

      override def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit
        context: L0NodeContext[F]
      ): F[Boolean] =
        (state match {
          case cs: CalculatedState => checkpointService.set(Checkpoint(ordinal, cs)).void
          case _                   => Async[F].unit
        }) >> underlying.setCalculatedState(ordinal, state)

      override def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] =
        underlying.routes <+> new ML0SnapshotStateRoutes[F].public

      // ---- everything below is pure delegation ----

      override def onGlobalSnapshotPull(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        context:  GlobalSnapshotInfo
      ): F[Unit] =
        underlying.onGlobalSnapshotPull(snapshot, context)

      override def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(implicit
        context: L0NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        underlying.validateData(state, updates)

      override def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(implicit
        context: L0NodeContext[F]
      ): F[DataState.Base] =
        underlying.combine(state, updates)

      override def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        underlying.getCalculatedState

      override def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
        underlying.hashCalculatedState(state)

      override def getTokenUnlocks(state: DataState[DataOnChainState, DataCalculatedState])(implicit
        context: L0NodeContext[F],
        async:   Async[F],
        hasher:  Hasher[F]
      ): F[SortedSet[TokenUnlock]] =
        underlying.getTokenUnlocks(state)(context, async, hasher)

      override def serializeState(state: DataOnChainState): F[Array[Byte]] = underlying.serializeState(state)

      override def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] =
        underlying.deserializeState(bytes)
      override def serializeUpdate(update: DataUpdate): F[Array[Byte]] = underlying.serializeUpdate(update)

      override def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] =
        underlying.deserializeUpdate(bytes)

      override def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] =
        underlying.serializeBlock(block)

      override def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] =
        underlying.deserializeBlock(bytes)

      override def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] =
        underlying.serializeCalculatedState(state)

      override def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] =
        underlying.deserializeCalculatedState(bytes)

      override def dataEncoder: io.circe.Encoder[DataUpdate] = underlying.dataEncoder
      override def dataDecoder: io.circe.Decoder[DataUpdate] = underlying.dataDecoder

      override def signedDataEntityEncoder: org.http4s.EntityEncoder[F, Signed[DataUpdate]] =
        underlying.signedDataEntityEncoder

      override def signedDataEntityDecoder: org.http4s.EntityDecoder[F, Signed[DataUpdate]] =
        underlying.signedDataEntityDecoder

      override def calculatedStateEncoder: io.circe.Encoder[DataCalculatedState] = underlying.calculatedStateEncoder
      override def calculatedStateDecoder: io.circe.Decoder[DataCalculatedState] = underlying.calculatedStateDecoder

      override def hashDataUpdate: Option[DataUpdate => F[Hash]] = underlying.hashDataUpdate

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(implicit
        context: L0NodeContext[F],
        A:       cats.Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        underlying.validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction)

      override def extractFees(ds: Seq[Signed[DataUpdate]])(implicit
        context: L0NodeContext[F],
        A:       cats.Applicative[F]
      ): F[Seq[Signed[FeeTransaction]]] =
        underlying.extractFees(ds)

      override def extractFees(ds: Seq[Signed[DataUpdate]])(implicit
        A: cats.Applicative[F]
      ): F[Seq[Signed[FeeTransaction]]] =
        underlying.extractFees(ds)(A)

      override def routesPrefix: io.constellationnetwork.routes.internal.ExternalUrlPrefix = underlying.routesPrefix
    }
}
