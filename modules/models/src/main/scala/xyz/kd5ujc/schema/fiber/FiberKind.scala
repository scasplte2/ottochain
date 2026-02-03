package xyz.kd5ujc.schema.fiber

import enumeratum._

sealed trait FiberKind extends EnumEntry

object FiberKind extends Enum[FiberKind] with CirceEnum[FiberKind] {
  case object StateMachine extends FiberKind
  case object Script extends FiberKind

  val values: IndexedSeq[FiberKind] = findValues
}
