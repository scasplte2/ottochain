package xyz.kd5ujc.metagraph_l0.app

import cats.syntax.all._

import xyz.kd5ujc.shared_data.app.ApplicationConfigOps._

import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

object ML0AppConfigOps {

  // Env-var friendly: a comma-separated string of URIs (e.g. COMMITTED_HYDRATION_PEERS)
  implicit val uriListReader: ConfigReader[List[Uri]] =
    ConfigReader[String].emap {
      _.split(',').toList
        .map(_.trim)
        .filter(_.nonEmpty)
        .traverse(s => Uri.fromString(s).leftMap(ex => CannotConvert(s, "URI", ex.getMessage)))
    }

  implicit val webhookConfigReader: ConfigReader[ML0AppConfig.WebhookConfig] = deriveReader
  implicit val hydrationConfigReader: ConfigReader[ML0AppConfig.HydrationConfig] = deriveReader
  implicit val genesisConfigReader: ConfigReader[ML0AppConfig.GenesisConfig] = deriveReader
  implicit val applicationConfigReader: ConfigReader[ML0AppConfig] = deriveReader
}
