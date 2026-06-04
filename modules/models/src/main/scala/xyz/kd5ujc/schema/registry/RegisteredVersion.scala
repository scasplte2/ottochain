package xyz.kd5ujc.schema.registry

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

/**
 * One immutable version of a registry entry. The chain commits only these hashes (never the schema
 * or definition bytes — those live in the Bridge + the registration update's history; see §4a).
 *
 * @param schemaHash   commitment to the protobuf FileDescriptorSet
 * @param logicHash    commitment to the JSON-Logic definition
 * @param stateMessage fully-qualified name of the State message inside the descriptor
 * @param commands     eventName/method -> Command message FQN
 */
final case class RegisteredVersion(
  version:      SemVer,
  schemaHash:   Hash,
  logicHash:    Hash,
  stateMessage: String,
  commands:     SortedMap[String, String],
  status:       RegistryStatus,
  registeredAt: SnapshotOrdinal
)
