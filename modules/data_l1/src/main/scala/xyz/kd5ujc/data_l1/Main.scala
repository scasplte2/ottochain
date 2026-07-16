package xyz.kd5ujc.data_l1

import java.util.UUID

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.l1.CurrencyL1App
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.node.shared.infrastructure.DataL1
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.data_l1.app.DataL1AppConfig
import xyz.kd5ujc.data_l1.app.DataL1AppConfigOps._
import xyz.kd5ujc.shared_data.app._

import com.monovore.decline.Opts
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CurrencyL1App(
      name = "data-app-l1",
      header = "Metagraph Data L1 node",
      clusterId = ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      tessellationVersion = TessellationVersion.unsafeFrom(io.constellationnetwork.BuildInfo.version),
      metagraphVersion = MetagraphVersion.unsafeFrom(BuildInfo.version)
    ) {

  private object CustomConfig {
    @volatile var configPath: Option[String] = None
    // the metagraph-L0 peer this node runs against (`--l0-peer`) — reused as the commit-index
    // heal target (onchain-incrementals RFC §3.3); no extra configuration needed.
    @volatile var l0Peer: Option[L0Peer] = None
  }

  override val opts: Opts[currency.l1.cli.method.Run] = {
    val configFileOpt: Opts[Option[String]] =
      Opts.option[String]("config", help = "Path to a custom configuration file").orNone

    (currency.l1.cli.method.opts(DataL1), configFileOpt).mapN { (parentOpts, configPath) =>
      CustomConfig.configPath = configPath
      CustomConfig.l0Peer = Some(parentOpts.l0Peer)

      parentOpts
    }
  }

  override def dataApplication: Option[Resource[IO, BaseDataApplicationL1Service[IO]]] = (for {
    config <- ApplicationConfigOps.loadConfig[IO, DataL1AppConfig](CustomConfig.configPath).asResource
    implicit0(logger: SelfAwareStructuredLogger[IO]) <- Slf4jLogger.create[IO].asResource
    implicit0(supervisor: Supervisor[IO])            <- Supervisor[IO]
    implicit0(sp: SecurityProvider[IO])              <- SecurityProvider.forAsync[IO]
    keyPair                                          <- loadKeyPair[IO](config).asResource
    httpClient                                       <- EmberClientBuilder.default[IO].build
    healClient = CustomConfig.l0Peer.map { peer =>
      CommitIndexHttpClient.make[IO](httpClient, Uri.unsafeFromString(s"http://${peer.ip}:${peer.port}"))
    }
    l1Service <- DataL1Service.make[IO](healClient).asResource
  } yield l1Service).some
}
