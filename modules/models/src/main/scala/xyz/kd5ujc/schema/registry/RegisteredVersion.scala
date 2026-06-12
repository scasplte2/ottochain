package xyz.kd5ujc.schema.registry

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * One immutable version of a registry entry. The chain commits only the hashes + the typed shape projection
 * (never the full descriptor or definition bytes — those live in the Bridge + the registration update's
 * history; see schema-architecture.md §4a).
 *
 * `shape` is [[RegistryShape.Machine]] for state-machine packages or [[RegistryShape.Script]] for script
 * packages — an ADT that carries the correct advisory projection for the kind.
 *
 * @param schemaHash  commitment to the protobuf FileDescriptorSet (descriptor bytes; off-chain)
 * @param logicHash   the VERIFIED-binding anchor: `computeDigest` of the registered logic, computed exactly
 *                    as a fiber computes its own definition/program digest. A fiber that references this
 *                    version is admitted only if its definition/program hashes to this value (see
 *                    [[SchemaBinding]]). Two versions MAY share a logicHash (e.g. a schema-only bump).
 * @param shape       the kind-correct advisory projection (publisher-claimed; the "describe" dial)
 * @param strict      opt-in runtime conformance gate (#33): for [[RegistryShape.Machine]] versions, every
 *                    PRODUCED state is checked against the SchemaShape and the transaction aborts on
 *                    non-conformance. Ignored for [[RegistryShape.Script]] versions.
 */
@derive(customizableEncoder, customizableDecoder)
final case class RegisteredVersion(
  version:      SemVer,
  schemaHash:   Hash,
  logicHash:    Hash,
  shape:        RegistryShape,
  status:       RegistryStatus,
  registeredAt: SnapshotOrdinal,
  strict:       Boolean
)
