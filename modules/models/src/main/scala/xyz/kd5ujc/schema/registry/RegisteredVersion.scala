package xyz.kd5ujc.schema.registry

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * One immutable version of a registry entry. The chain commits only the hashes + the typed [[SchemaShape]]
 * projection (never the full descriptor or definition bytes — those live in the Bridge + the registration
 * update's history; see schema-architecture.md §4a).
 *
 * @param schemaHash   commitment to the protobuf FileDescriptorSet (descriptor bytes; off-chain)
 * @param logicHash    the VERIFIED-binding anchor: `StateMachineDefinition.computeDigest` of the registered
 *                     logic, computed exactly as a fiber computes its own definition's digest. A fiber that
 *                     references this version is admitted only if its definition hashes to this value (see
 *                     [[SchemaBinding]]). Two versions MAY share a logicHash (e.g. a schema-only bump).
 * @param schemaShape  the typed, proto-friendly domain projection (publisher-claimed, advisory — the
 *                     "describe" dial; never constrains the logic)
 * @param strict       opt-in runtime conformance gate (#33): when true, a fiber bound to this version has
 *                     every PRODUCED state (create/transition/migration) checked against `schemaShape` and
 *                     the transaction aborts on a non-conforming state. Default false = the loose,
 *                     experimentation path (the schema stays advisory). Publisher-decided, per version.
 */
@derive(customizableEncoder, customizableDecoder)
final case class RegisteredVersion(
  version:      SemVer,
  schemaHash:   Hash,
  logicHash:    Hash,
  schemaShape:  SchemaShape,
  status:       RegistryStatus,
  registeredAt: SnapshotOrdinal,
  strict:       Boolean = false
)
