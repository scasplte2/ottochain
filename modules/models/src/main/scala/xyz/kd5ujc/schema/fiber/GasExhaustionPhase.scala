package xyz.kd5ujc.schema.fiber

import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

/** Phase where gas was exhausted during execution */
sealed trait GasExhaustionPhase extends EnumEntry with Uppercase

object GasExhaustionPhase extends Enum[GasExhaustionPhase] with CirceEnum[GasExhaustionPhase] {
  val values: IndexedSeq[GasExhaustionPhase] = findValues

  case object Guard extends GasExhaustionPhase
  case object Effect extends GasExhaustionPhase
  case object Oracle extends GasExhaustionPhase
  case object Trigger extends GasExhaustionPhase
  case object Spawn extends GasExhaustionPhase
  case object Migration extends GasExhaustionPhase
}
