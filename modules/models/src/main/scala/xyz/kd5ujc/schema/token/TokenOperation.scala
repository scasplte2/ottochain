package xyz.kd5ujc.schema.token

import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

/** Operations that can be performed on a token fiber */
sealed trait TokenOperation extends EnumEntry with Uppercase

object TokenOperation extends Enum[TokenOperation] with CirceEnum[TokenOperation] {
  case object Mint extends TokenOperation
  case object Burn extends TokenOperation
  case object Transfer extends TokenOperation
  case object Split extends TokenOperation
  case object Merge extends TokenOperation
  case object SetPolicy extends TokenOperation
  case object Expire extends TokenOperation
  case object Extend extends TokenOperation

  val values: IndexedSeq[TokenOperation] = findValues
}
