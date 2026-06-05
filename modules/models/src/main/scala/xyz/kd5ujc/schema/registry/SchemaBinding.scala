package xyz.kd5ujc.schema.registry

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A caller's reference to a registered schema/program version, supplied at fiber creation
 * (`CreateStateMachine.schemaRef`). The combiner resolves `version` against the registry at create time.
 */
@derive(customizableEncoder, customizableDecoder)
final case class SchemaRef(name: RegistryName, version: VersionReq)

/**
 * The resolved, pinned binding recorded on a fiber: which registry (name, version) it instantiates, with
 * the committed hashes. Resolved once at create (against the registry at that ordinal) and then immutable
 * for the fiber's life — re-resolution is an explicit upgrade (#27).
 *
 * Trust model (declaration, #24): this records *which* version the fiber claims to instantiate and that the
 * version exists in the registry. It does NOT verify on-chain that the fiber's `definition` equals the
 * registered logic — that is checked off-chain against `logicHash` (the agnostic / Etherscan-claim model).
 * TODO(#24b): optional on-chain verified binding (requires the registry's logic to be a typed, canonically
 * hashed definition rather than the opaque base64 blob; see schema-architecture.md).
 */
@derive(customizableEncoder, customizableDecoder)
final case class SchemaBinding(
  name:       RegistryName,
  version:    SemVer,
  schemaHash: Hash,
  logicHash:  Hash
)
