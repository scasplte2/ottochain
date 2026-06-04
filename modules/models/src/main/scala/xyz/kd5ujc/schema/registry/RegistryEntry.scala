package xyz.kd5ujc.schema.registry

import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A single owned entry in the registry namespace: a [[RegistryName]] -> a discriminated [[RegistryTarget]],
 * with the owning addresses (who may publish versions / change status / transfer — enforced by the
 * combiner in #23c). The entry holds only commitments + metadata; never schema/definition bytes.
 */
@derive(customizableEncoder, customizableDecoder)
final case class RegistryEntry(
  name:   RegistryName,
  owner:  Set[Address],
  target: RegistryTarget
)
