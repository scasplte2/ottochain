package xyz.kd5ujc.schema.asset

import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

/**
 * Access-control level of a single morphism on an asset policy (docs/proposals/asset-model.md §8):
 *
 *  - `Public`   — anyone may invoke (subject to the morphism's structural domain guard + optional `guard`).
 *  - `Governed` — invocation gated by the policy's governance path (owner / DAO threshold).
 *  - `Disabled` — the morphism is structurally unavailable on this policy version.
 *
 * Wire form is the uppercase entry name (`"PUBLIC"`, `"GOVERNED"`, `"DISABLED"`) via [[CirceEnum]].
 */
sealed trait MorphismVisibility extends EnumEntry with Uppercase

object MorphismVisibility extends Enum[MorphismVisibility] with CirceEnum[MorphismVisibility] {
  val values: IndexedSeq[MorphismVisibility] = findValues

  case object Public extends MorphismVisibility
  case object Governed extends MorphismVisibility
  case object Disabled extends MorphismVisibility
}
