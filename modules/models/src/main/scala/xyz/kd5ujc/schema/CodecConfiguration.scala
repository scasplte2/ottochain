package xyz.kd5ujc.schema

import io.constellationnetwork.security.hash.Hash

import io.circe.magnolia.configured.Configuration
import io.circe.{KeyDecoder, KeyEncoder}

/**
 * Shared codec configuration for all OttoChain schema types.
 *
 * `useDefaults = true` tells the magnolia-derived decoders to fall back to
 * Scala default parameter values when a JSON key is absent.  Without this,
 * every `Option[A] = None` or `Set[X] = Set.empty` field must be sent as
 * an explicit `null` / `[]` on the wire — a common source of client-side
 * serialisation bugs.
 *
 * Import `CodecConfiguration._` in any file using
 * `@derive(customizableEncoder, customizableDecoder)`.
 */
object CodecConfiguration {

  implicit val magnoliaConfiguration: Configuration =
    Configuration.default.withDefaults

  // Hash as a JSON-object map KEY (tessellation derives only the value-position Encoder/Decoder). Renders as
  // its plain string value — the same bytes as the value-position codec — so `SortedMap[Hash, *]` fields
  // (e.g. `CalculatedState.nullifiers` inner maps) round-trip byte-stably. No validation on decode, matching
  // the value-position decoder (Hash is a plain string wrapper).
  implicit val hashKeyEncoder: KeyEncoder[Hash] = KeyEncoder.encodeKeyString.contramap(_.value)
  implicit val hashKeyDecoder: KeyDecoder[Hash] = KeyDecoder.decodeKeyString.map(Hash(_))
}
