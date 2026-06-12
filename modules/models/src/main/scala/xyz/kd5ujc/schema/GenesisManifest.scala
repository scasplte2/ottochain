package xyz.kd5ujc.schema

import scala.collection.immutable.SortedMap

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.fiber.StateMachineDefinition
import xyz.kd5ujc.schema.registry.{RegistryName, SchemaShape, SemVer}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * The off-chain "genesis manifest" produced by ottochain-sdk (#46): the CONTENT needed to pre-register the
 * std package set at genesis. It deliberately ships content (machineShape + the JSON-Logic definition), NOT
 * consensus hashes — the chain computes schemaHash/logicHash from this via its own `computeDigest` (see
 * [[xyz.kd5ujc.shared_data.genesis.GenesisManifestLoader]]). A package's registered `logicHash` is therefore
 * identical-by-construction to a fiber's bind-time `definition.computeDigest`: no cross-language hash
 * replication, no drift.
 */
@derive(customizableEncoder, customizableDecoder)
final case class GenesisManifest(
  version:  Int,
  packages: List[ManifestPackage]
)

@derive(customizableEncoder, customizableDecoder)
final case class ManifestPackage(
  name:         RegistryName,
  semver:       SemVer,
  machineShape: SchemaShape,
  definition:   StateMachineDefinition,
  strict:       Boolean = false,
  metadata:     SortedMap[String, String] = SortedMap.empty
)
