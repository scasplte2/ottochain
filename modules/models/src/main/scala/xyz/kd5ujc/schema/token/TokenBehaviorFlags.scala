package xyz.kd5ujc.schema.token

/** Bit masks for the 4-bit TDEG token behavior encoding */
object TokenBehaviorFlags {
  val Transferable: Int = 0x08 // bit 3
  val Divisible: Int = 0x04 // bit 2
  val Expirable: Int = 0x02 // bit 1
  val Governable: Int = 0x01 // bit 0
}
