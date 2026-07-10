package xyz.kd5ujc.shared_data.fiber.evaluation

import io.constellationnetwork.metagraph_sdk.json_logic._

/**
 * OttoChain-owned, cross-version-stable kind descriptor for a JLVM value.
 *
 * COMMITTED failure receipts (audit L1 — `docs/audit/fiber-engine-permissionless-safety-audit-2026-07-07.md`)
 * must render a value's kind through THIS mapping, never through `value.getClass.getSimpleName` and never
 * through metakit's own `JsonLogicValue.tag`. Both of those are metakit-controlled: a metakit patch that
 * renames a value class (changing `getSimpleName`) or re-tags one (changing `.tag`) would change the committed
 * hash of a REJECTED transaction's receipt, forking a mixed-version validator set on a rejected tx. This
 * vocabulary is pinned by OttoChain and MUST stay fixed across metakit upgrades (the tokens intentionally
 * mirror the current `.tag` vocabulary so pinning them here is byte-neutral today while decoupling us from any
 * future metakit change).
 */
object ValueKind {

  def of(v: JsonLogicValue): String = v match {
    case NullValue        => "null"
    case _: FunctionValue => "function"
    case _: BoolValue     => "bool"
    case _: IntValue      => "int"
    case _: FloatValue    => "float"
    case _: StrValue      => "string"
    case _: ArrayValue    => "array"
    case _: MapValue      => "map"
  }
}
