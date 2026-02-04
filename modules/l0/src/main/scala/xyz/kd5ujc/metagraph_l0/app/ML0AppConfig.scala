package xyz.kd5ujc.metagraph_l0.app

import xyz.kd5ujc.shared_data.app.SharedAppConfig

import org.http4s.Uri

case class ML0AppConfig(
  node:    SharedAppConfig.NodeConfig,
  webhook: ML0AppConfig.WebhookConfig
) extends SharedAppConfig

object ML0AppConfig {

  case class WebhookConfig(
    url:         Option[Uri],
    metagraphId: Option[String]
  )
}
