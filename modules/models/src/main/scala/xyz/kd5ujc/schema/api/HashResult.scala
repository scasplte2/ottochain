package xyz.kd5ujc.schema.api

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.api.HashResultCodec._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import io.circe.magnolia.configured.Configuration

/**
 * Response of `POST /v1/util/hash`: the canonical digest of a submitted message, echoed alongside the
 * message itself.
 *
 * The wire keys contain spaces (`"protocol message hash"`, `"protocol message"`) — not legal Scala
 * identifiers — so the codec is DERIVED under a [[HashResultCodec.configuration]] that maps these two
 * member names to their on-wire keys, rather than hand-written. `message` is encoded with its own canonical
 * `OttochainMessage` encoder (the per-member name transform applies only to `HashResult`'s own fields, not
 * recursively), so the echoed message stays byte-identical to what was signed. A clean v2 rename
 * (`messageHash` / `message`) would drop this transform entirely. See `typed-network-interface.md` §6.
 */
@derive(customizableEncoder, customizableDecoder)
final case class HashResult(messageHash: Hash, message: OttochainMessage)

/** Separate object (fully initialized before the derived codec) so the implicit ordering is safe. */
private object HashResultCodec {

  implicit val configuration: Configuration =
    Configuration.default.copy(transformMemberNames = {
      case "messageHash" => "protocol message hash"
      case "message"     => "protocol message"
      case other         => other
    })
}
