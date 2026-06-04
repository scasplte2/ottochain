package xyz.kd5ujc.schema.registry

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * What a registry entry resolves to. One namespace, discriminated target:
 *  - [[RegistryTarget.SchemaPackage]] — a versioned schema/program *type* (its [[VersionLineage]]).
 *
 * Instance aliases (name -> fiber UUID) and zone delegation arrive with the nickname registry (#29);
 * the trait is sealed so those arms slot in without reshaping the registry.
 */
@derive(customizableEncoder, customizableDecoder)
sealed trait RegistryTarget

object RegistryTarget {

  /** A versioned schema/program type: its append-only [[VersionLineage]]. */
  final case class SchemaPackage(versions: VersionLineage) extends RegistryTarget

  // TODO(#29): final case class InstanceAlias(fiberId: UUID, ref: (RegistryName, SemVer)) extends RegistryTarget
  // TODO(#29): final case class Delegation(zoneOwner: Set[Address]) extends RegistryTarget
}
