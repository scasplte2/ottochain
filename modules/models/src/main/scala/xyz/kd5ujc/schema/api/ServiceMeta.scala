package xyz.kd5ujc.schema.api

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/** Service identity + build metadata returned by `GET /v1/version` on every node (ML0 + DL1). */
@derive(customizableEncoder, customizableDecoder)
final case class VersionInfo(
  service:             String,
  version:             String,
  name:                String,
  scalaVersion:        String,
  sbtVersion:          String,
  gitCommit:           String,
  buildTime:           String,
  tessellationVersion: String
)
