package xyz.kd5ujc.metagraph_l0

import java.nio.file.Paths
import java.util.UUID

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.l0.CurrencyL0App
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CatalogJournal
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.metagraph_l0.app.ML0AppConfig
import xyz.kd5ujc.metagraph_l0.app.ML0AppConfigOps._
import xyz.kd5ujc.shared_data.app._

import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CurrencyL0App(
      name = "metagraph-l0",
      header = "Metagraph L0 node",
      clusterId = ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      tessellationVersion = TessellationVersion.unsafeFrom(io.constellationnetwork.BuildInfo.version),
      metagraphVersion = MetagraphVersion.unsafeFrom(BuildInfo.version)
    ) {

  override def dataApplication: Option[Resource[IO, BaseDataApplicationL0Service[IO]]] = (for {
    config                                           <- ApplicationConfigOps.readDefault[IO, ML0AppConfig].asResource
    implicit0(logger: SelfAwareStructuredLogger[IO]) <- Slf4jLogger.create[IO].asResource
    implicit0(supervisor: Supervisor[IO])            <- Supervisor[IO]
    implicit0(sp: SecurityProvider[IO])              <- SecurityProvider.forAsync[IO]
    _                                                <- loadKeyPair[IO](config).asResource

    // HTTP client for webhook delivery, built unconditionally. The snapshot/rejection dispatcher is a
    // no-op when no subscriber is registered, so there is no reason to gate it on a config flag —
    // subscription via POST /webhooks/subscribe is what actually turns delivery on.
    httpClient <- EmberClientBuilder.default[IO].build.map(c => Some(c): Option[org.http4s.client.Client[IO]])

    // Committed-catalog journal: the node-local LevelDB store that lets a restarted/seeded node
    // re-hydrate its committed cell (without it, a seed lands unhydrated and stalls at combine).
    journal <- CatalogJournal.levelDb[IO](Paths.get("committed-catalog"))

    l0Service <- ML0Service
      .make[IO](
        journal = journal,
        httpClient = httpClient,
        metagraphId = config.webhook.metagraphId.getOrElse("DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG"),
        genesisPath = config.genesis.path
      )
      .asResource
  } yield l0Service).some
}
