package xyz.kd5ujc.data_l1

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.metagraph_sdk.MetagraphCommonService
import io.constellationnetwork.metagraph_sdk.lifecycle.ValidationService
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.{CommitIndexHealClient, CommitIndexService, Validator}

import org.http4s._

object DataL1Service {

  /**
   * @param healClient transport for re-seeding the commit-index cache from ML0 on ordinal gaps
   *                   (OnChain v2 carries per-batch deltas only — onchain-incrementals RFC §3.3).
   *                   `None` degrades gap handling to a loud, possibly-incomplete fold (dev only).
   */
  def make[F[+_]: Async: Parallel: SecurityProvider](
    healClient: Option[CommitIndexHealClient[F]] = None
  ): F[BaseDataApplicationL1Service[F]] = for {
    // one shared cache: the ingestion gate (validateUpdate) and the polled sync surface
    // (GET /v1/commit-index) must observe the SAME folded view
    commitIndexService <- CommitIndexService.make[F](healClient)
    validator          <- Validator.make[F](commitIndexService)
    l1Service          <- makeBaseApplicationL1Service(validator, commitIndexService).pure[F]
  } yield l1Service

  private def makeBaseApplicationL1Service[F[+_]: Async](
    validator:          ValidationService[F, OttochainMessage, OnChain, CalculatedState],
    commitIndexService: CommitIndexService[F]
  ): BaseDataApplicationL1Service[F] =
    BaseDataApplicationL1Service[F, OttochainMessage, OnChain, CalculatedState](
      new MetagraphCommonService[F, OttochainMessage, OnChain, CalculatedState, L1NodeContext[F]]
        with DataApplicationL1Service[F, OttochainMessage, OnChain, CalculatedState] {

        override def validateUpdate(
          update: OttochainMessage
        )(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
          validator.validateUpdate(update)

        override def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] =
          new DataL1CustomRoutes[F](commitIndexService).public
      }
    )
}
