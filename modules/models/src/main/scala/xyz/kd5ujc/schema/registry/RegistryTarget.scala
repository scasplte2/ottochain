package xyz.kd5ujc.schema.registry

import java.util.UUID

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * What a registry entry resolves to — one namespace, discriminated by a target kept consistent with the
 * name's [[NameTld]]:
 *  - [[RegistryTarget.SchemaPackage]] (`.package`) — a versioned schema/program *type* (its [[VersionLineage]]).
 *  - [[RegistryTarget.InstanceAlias]] (`.machine` / `.script`) — a nickname for a fiber of that kind (#29).
 *  - [[RegistryTarget.AssetPolicyPackage]] (`.asset`) — a versioned asset-policy *type* (its
 *    [[VersionLineage]]); policy : asset :: package : fiber (docs/proposals/asset-model.md §5).
 *
 * Zone delegation (a node owner controlling `*.zone`) arrives with the hierarchy follow-up; the trait is
 * sealed so it slots in without reshaping the registry.
 */
@derive(customizableEncoder, customizableDecoder)
sealed trait RegistryTarget

object RegistryTarget {

  /** A versioned schema/program type: its append-only [[VersionLineage]]. Lives under a `.package` name. */
  final case class SchemaPackage(versions: VersionLineage) extends RegistryTarget

  /** A nickname for an existing fiber instance; the fiber's kind must match the name's TLD (machine/script). */
  final case class InstanceAlias(fiberId: UUID) extends RegistryTarget

  /**
   * A versioned asset-policy type: its append-only [[VersionLineage]] (each version's
   * [[RegisteredVersion.shape]] is a [[RegistryShape.AssetPolicy]]). Lives under a `.asset` name. Publish /
   * resolve / status reuse the lineage machinery verbatim — an asset policy is governed exactly like a
   * schema package (docs/proposals/asset-model.md §5a).
   */
  final case class AssetPolicyPackage(versions: VersionLineage) extends RegistryTarget

  // TODO(#29 phase 4): final case class Delegation(zoneOwner: Set[Address]) extends RegistryTarget
}
