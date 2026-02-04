package xyz.kd5ujc.metagraph_l0.app

import xyz.kd5ujc.shared_data.app.ApplicationConfigOps._

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

object ML0AppConfigOps {
  implicit val webhookConfigReader: ConfigReader[ML0AppConfig.WebhookConfig] = deriveReader
  implicit val applicationConfigReader: ConfigReader[ML0AppConfig] = deriveReader
}
