package xyz.kd5ujc.schema.asset

import cats.{Order, Show}

import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, CirceKeyEnum, Enum, EnumEntry}

/**
 * The kind of a typed asset morphism — the verb of an asset transformation, with a known domain guard
 * (a predicate on the source [[TokenBehavior]]) and a deterministic codomain (`β ↦ β'`). See
 * docs/proposals/asset-model.md §4:
 *
 *  - `Transfer`      (`T=1`)            — same behavior, new holder.
 *  - `Burn`          (none)             — asset destroyed (terminal).
 *  - `Fractionalize` (`S=1`)            — shards: source behavior with `C` forced to 0.
 *  - `Compose`       (all `C=1`)        — `meet` of all component behaviors.
 *  - `Decompose`     (`isComposite`)    — original component behaviors restored.
 *  - `Wrap`          (`T=1`)            — same behavior (identity-preserving on `β`).
 *  - `Stake`         (`T=1`)            — source behavior with `E:=1` (moves DOWN the lattice).
 *
 * Used as a [[scala.collection.immutable.SortedMap]] KEY in [[xyz.kd5ujc.schema.registry.RegistryShape.AssetPolicy.morphisms]],
 * so it carries: a total [[Order]]/[[Ordering]] (deterministic, sorted JSON-object key order for the
 * signing canonical), circe `Encoder`/`Decoder` (via [[CirceEnum]]), AND circe `KeyEncoder`/`KeyDecoder`
 * (via [[CirceKeyEnum]]) so the map serialises as a JSON object keyed by the (uppercase) entry name.
 */
sealed trait MorphismKind extends EnumEntry with Uppercase

object MorphismKind extends Enum[MorphismKind] with CirceEnum[MorphismKind] with CirceKeyEnum[MorphismKind] {
  val values: IndexedSeq[MorphismKind] = findValues

  case object Transfer extends MorphismKind
  case object Burn extends MorphismKind
  case object Fractionalize extends MorphismKind
  case object Compose extends MorphismKind
  case object Decompose extends MorphismKind
  case object Wrap extends MorphismKind
  case object Stake extends MorphismKind

  // Total order by the (uppercase) entry name: stable, deterministic, and the same order the JSON-object
  // keys take on the wire — essential for the signing canonical of any message carrying the morphism map.
  implicit val order: Order[MorphismKind] = Order.by(_.entryName)
  implicit val ordering: Ordering[MorphismKind] = order.toOrdering
  implicit val show: Show[MorphismKind] = Show.show(_.entryName)
}
