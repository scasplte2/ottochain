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
 * Trust model (verified, #37): a binding is recorded only after the chain checks, on-chain, that the
 * fiber's own `definition.computeDigest` equals the registered version's `logicHash`. A fiber therefore
 * cannot falsely claim to instantiate a version — the combiner aborts and the validator rejects a
 * definition that does not hash to the registered logic. This is the "verify identity" dial (it pins
 * *which* logic, never *what shape* the logic must take; see strong-typing-and-conformance.md §0.5). The
 * proto schema stays advisory (`schemaShape`) — conformance of logic to schema is the separate, opt-in
 * dial and is NOT enforced here.
 */
@derive(customizableEncoder, customizableDecoder)
final case class SchemaBinding(
  name:       RegistryName,
  version:    SemVer,
  schemaHash: Hash,
  logicHash:  Hash
)
