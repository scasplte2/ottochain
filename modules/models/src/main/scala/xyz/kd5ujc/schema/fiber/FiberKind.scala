package xyz.kd5ujc.schema.fiber

import enumeratum.EnumEntry.Uppercase
import enumeratum._

sealed trait FiberKind extends EnumEntry with Uppercase

object FiberKind extends Enum[FiberKind] with CirceEnum[FiberKind] {
  case object StateMachine extends FiberKind
  case object Script extends FiberKind

  /**
   * Asset instances are not fibers (asset-model D1: dedicated `AssetRecord`), but they reuse the fiber
   * fingerprint scheme for a readable `.asset` handle — `FiberFingerprint.of(uuid, Asset)` renders
   * `lusab-…-bavor.asset`. See docs/proposals/asset-model.md §5c.
   */
  case object Asset extends FiberKind

  val values: IndexedSeq[FiberKind] = findValues
}
