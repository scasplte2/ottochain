package xyz.kd5ujc.schema.api

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.Updates.OttochainMessage

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

/**
 * Response of `POST /v1/util/hash`: the canonical digest of a submitted message, echoed alongside the
 * message itself. Plain derived codec (`messageHash` / `message`) — `message` uses its own canonical
 * `OttochainMessage` encoder, so the echoed message stays byte-identical to what was signed.
 */
@derive(customizableEncoder, customizableDecoder)
final case class HashResult(messageHash: Hash, message: OttochainMessage)
