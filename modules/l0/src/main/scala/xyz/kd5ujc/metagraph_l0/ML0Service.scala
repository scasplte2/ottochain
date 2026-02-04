package xyz.kd5ujc.metagraph_l0

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Parallel}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.metagraph_sdk.MetagraphCommonService
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, CombinerService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.CurrencyIncrementalSnapshotOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import xyz.kd5ujc.metagraph_l0.webhooks.{NotificationStats, SubscriberRegistry, WebhookDispatcher}
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberStatus
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}

import monocle.Monocle.toAppliedFocusOps
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object ML0Service {

  /**
   * Create the ML0 service with webhook support
   *
   * @param httpClient HTTP client for webhook delivery
   * @param metagraphId The metagraph token identifier (for webhook notifications)
   */
  def make[F[+_]: Async: Parallel: SecurityProvider](
    httpClient:  Option[Client[F]] = None,
    metagraphId: String = "DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG"
  ): F[BaseDataApplicationL0Service[F]] = for {
    implicit0(logger: SelfAwareStructuredLogger[F]) <- Slf4jLogger.create[F]
    checkpointService  <- CheckpointService.make[F, CalculatedState](CalculatedState.genesis)
    subscriberRegistry <- SubscriberRegistry.make[F]
    combiner           <- Combiner.make[F]().pure[F]
    validator          <- Validator.make[F]

    // Create webhook dispatcher if http client is provided
    webhookDispatcher = httpClient.map { client =>
      WebhookDispatcher.make[F](client, subscriberRegistry, metagraphId)
    }

    dataApplicationL0Service <- makeBaseApplicationL0Service(
      checkpointService,
      combiner,
      validator,
      subscriberRegistry,
      webhookDispatcher
    ).pure[F]
  } yield dataApplicationL0Service

  private def makeBaseApplicationL0Service[F[+_]: Async](
    checkpointService:  CheckpointService[F, CalculatedState],
    combiner:           CombinerService[F, OttochainMessage, OnChain, CalculatedState],
    validator:          ValidationService[F, OttochainMessage, OnChain, CalculatedState],
    subscriberRegistry: SubscriberRegistry[F],
    webhookDispatcher:  Option[WebhookDispatcher[F]]
  )(implicit logger: SelfAwareStructuredLogger[F]): BaseDataApplicationL0Service[F] =
    BaseDataApplicationL0Service[F, OttochainMessage, OnChain, CalculatedState](
      new MetagraphCommonService[F, OttochainMessage, OnChain, CalculatedState, L0NodeContext[F]]
        with DataApplicationL0Service[F, OttochainMessage, OnChain, CalculatedState] {

        override def genesis: DataState[OnChain, CalculatedState] =
          DataState(OnChain.genesis, CalculatedState.genesis)

        override def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit
          A: Applicative[F]
        ): F[Unit] =
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
          } yield ()).handleErrorWith(logger.error(_)("Error during onSnapshotConsensusResult"))

        override def validateData(
          state:   DataState[OnChain, CalculatedState],
          updates: NonEmptyList[Signed[OttochainMessage]]
        )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
          validator.validateDataParallel(state, updates)

        override def combine(
          state:   DataState[OnChain, CalculatedState],
          updates: List[Signed[OttochainMessage]]
        )(implicit context: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
          combiner.foldLeft(
            state.focus(_.onChain.latestLogs).replace(SortedMap.empty),
            updates
          )

        override def getCalculatedState(implicit
          context: L0NodeContext[F]
        ): F[(SnapshotOrdinal, CalculatedState)] =
          checkpointService.get.map { case Checkpoint(ordinal, state) => ordinal -> state }

        override def setCalculatedState(ordinal: SnapshotOrdinal, state: CalculatedState)(implicit
          context: L0NodeContext[F]
        ): F[Boolean] = checkpointService.set(Checkpoint(ordinal, state))

        override def hashCalculatedState(state: CalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
          state.computeDigest

        override def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] =
          new ML0CustomRoutes[F](checkpointService, subscriberRegistry).public
      }
    )
}
