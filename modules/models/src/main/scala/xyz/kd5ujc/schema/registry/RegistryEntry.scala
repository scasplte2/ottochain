package xyz.kd5ujc.schema.registry

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A single owned entry in the registry namespace: a [[RegistryName]] -> a discriminated [[RegistryTarget]],
 * with the owning addresses (who may publish versions / change status / transfer) and an optional metadata
 * grab-bag for off-chain links (e.g. "repo"/"homepage"/"docs" -> URL). The entry holds only commitments +
 * metadata; never schema/definition bytes.
 */
@derive(customizableEncoder, customizableDecoder)
final case class RegistryEntry(
  name:     RegistryName,
  owner:    Set[Address],
  target:   RegistryTarget,
  metadata: SortedMap[String, String] = SortedMap.empty
)
