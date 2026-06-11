package xyz.kd5ujc.schema.registry

import enumeratum.EnumEntry.Lowercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

/**
 * The reserved top-level kind of a [[RegistryName]] — the `.tld` suffix. Fixed in-protocol so a name's
 * kind is legible from the key itself:
 *
 *  - `.package` — a versioned schema/program package (its [[VersionLineage]]).
 *  - `.machine` — an alias (nickname) for a state-machine fiber.
 *  - `.script`  — an alias (nickname) for a script/oracle fiber.
 *
 * Putting the TLD in the key lets a package and a fiber alias share label text under different TLDs
 * (`escrow.package` vs `escrow.machine`). See docs/proposals/naming-and-fingerprints.md §3.
 */
sealed trait NameTld extends EnumEntry with Lowercase

object NameTld extends Enum[NameTld] with CirceEnum[NameTld] {
  val values: IndexedSeq[NameTld] = findValues

  case object Package extends NameTld
  case object Machine extends NameTld
  case object Script extends NameTld
}
