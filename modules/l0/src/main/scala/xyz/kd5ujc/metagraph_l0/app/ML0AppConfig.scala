package xyz.kd5ujc.metagraph_l0.app

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import xyz.kd5ujc.shared_data.app.SharedAppConfig

import org.http4s.Uri

case class ML0AppConfig(
  node:      SharedAppConfig.NodeConfig,
  webhook:   ML0AppConfig.WebhookConfig,
  hydration: ML0AppConfig.HydrationConfig
) extends SharedAppConfig

object ML0AppConfig {

  case class WebhookConfig(
    url:         Option[Uri],
    metagraphId: Option[String]
  )

  /**
   * Committed-catalog hydration (see `CommittedHydrationClient`): active only when both
   * `selfUrl` (this node's data-application base URL) and at least one peer are configured.
   */
  case class HydrationConfig(
    selfUrl:  Option[Uri],
    peers:    Option[List[Uri]],
    interval: Option[FiniteDuration]
  ) {
    def effectiveInterval: FiniteDuration = interval.getOrElse(30.seconds)
  }
}
