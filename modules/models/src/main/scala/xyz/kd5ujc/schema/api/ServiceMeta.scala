package xyz.kd5ujc.schema.api

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.Updates.OttochainMessage

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

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
 * message itself.
 *
 * NOTE — the wire keys contain spaces (`"protocol message hash"`, `"protocol message"`), so this is the
 * one DTO whose codec is hand-written rather than derived (the quarantined exception from
 * `docs/proposals/typed-network-interface.md` §6). `message` is encoded with the canonical
 * `OttochainMessage` encoder so the echoed message is byte-identical to what was signed. A clean v2
 * rename (`messageHash` / `message`) is tracked as a follow-up.
 */
final case class HashResult(messageHash: Hash, message: OttochainMessage)

object HashResult {
  private val HashKey = "protocol message hash"
  private val MessageKey = "protocol message"

  implicit val encoder: Encoder[HashResult] = Encoder.instance { r =>
    Json.obj(HashKey -> r.messageHash.asJson, MessageKey -> r.message.asJson)
  }

  implicit val decoder: Decoder[HashResult] = Decoder.instance { c =>
    for {
      h <- c.downField(HashKey).as[Hash]
      m <- c.downField(MessageKey).as[OttochainMessage]
    } yield HashResult(h, m)
  }
}
