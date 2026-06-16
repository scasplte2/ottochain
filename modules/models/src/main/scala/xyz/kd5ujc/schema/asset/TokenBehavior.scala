package xyz.kd5ujc.schema.asset

import cats.kernel.BoundedSemilattice
import cats.{Eq, PartialOrder, Show}

/**
 * A token's behavioral capability surface, captured as 5 independent bits — T transferable, S splittable,
 * C combinable, E expirable, G governable — with weights `T=16, S=8, C=4, E=2, G=1`. The wire form is the
 * single packed [[bits]] Int (compact and signing-canonical-safe; matches `AssetCommit.behavior: Int`).
 *
 * The behaviors form a bounded lattice, but NOT the naive product lattice: T/S/C are *capabilities*
 * (more is more capable, so they meet with AND) while E/G are *restrictions* (acquiring expiry or
 * governance moves you DOWN the lattice, so they meet with OR). Formally the order is
 * `(B,<=)^3 x (B,>=)^2` for `(T,S,C,E,G)`, and [[meet]] is its greatest lower bound (GLB):
 *
 *   - `Top    = fromBits(28) = TSC-- = Fungible`  — most capable, least restricted (lattice identity).
 *   - `Bottom = fromBits(3)  = ---EG`             — most restricted (absorbing element).
 *
 * Because E/G are reversed, `Soulbound = fromBits(0) = -----` is an INTERIOR point, not the bottom:
 * meeting anything with `Soulbound` strips T/S/C but does not (by itself) add E/G, and a counterparty's
 * E/G still flows in via OR. The aggregate behavior of a composite is [[TokenBehavior.foldMeet]] of its
 * parts (a behavior-homomorphism: identity `Top`, associative/commutative/idempotent).
 */
final case class TokenBehavior(
  transferable: Boolean,
  splittable:   Boolean,
  combinable:   Boolean,
  expirable:    Boolean,
  governable:   Boolean
) {

  val bits: Int = (if (transferable) 16 else 0) + (if (splittable) 8 else 0) +
    (if (combinable) 4 else 0) + (if (expirable) 2 else 0) + (if (governable) 1 else 0)

  /**
   * Greatest lower bound on the product lattice `(B,<=)^3 x (B,>=)^2`: T,S,C use AND; E,G use OR
   * (acquiring expiry/governance is "more restrictive" => moving DOWN the lattice).
   */
  def meet(other: TokenBehavior): TokenBehavior = TokenBehavior(
    transferable && other.transferable,
    splittable && other.splittable,
    combinable && other.combinable,
    expirable || other.expirable,
    governable || other.governable
  )

  /** Lattice order: `this <= that  <=>  meet(this, that) == this`. */
  def <=(that: TokenBehavior): Boolean = this.meet(that) == this
}

object TokenBehavior {

  /** Read the low 5 bits of `n` into the five behavior flags. */
  def fromBits(n: Int): TokenBehavior = TokenBehavior(
    transferable = (n & 16) != 0,
    splittable = (n & 8) != 0,
    combinable = (n & 4) != 0,
    expirable = (n & 2) != 0,
    governable = (n & 1) != 0
  )

  /** Most capable, least restricted (TSC--). Lattice identity for [[meet]]. */
  val Top: TokenBehavior = fromBits(28)

  /** Most restricted (---EG). Absorbing element for [[meet]]. */
  val Bottom: TokenBehavior = fromBits(3)

  /** Aggregate behavior of a composite — the behavior-homomorphism (identity = [[Top]]). */
  def foldMeet(bs: IterableOnce[TokenBehavior]): TokenBehavior =
    bs.iterator.foldLeft(Top)(_ meet _)

  // Canonical presets (named points in the lattice).
  val Soulbound: TokenBehavior = fromBits(0)
  val ExpiringBadge: TokenBehavior = fromBits(2)
  val ExpiringGovernedBadge: TokenBehavior = fromBits(3)
  val NFT: TokenBehavior = fromBits(16)
  val Ticket: TokenBehavior = fromBits(18)
  val GovernedTicket: TokenBehavior = fromBits(19)
  val Fungible: TokenBehavior = fromBits(28)
  val GovernedFungible: TokenBehavior = fromBits(29)
  val ExpiringFungible: TokenBehavior = fromBits(30)
  val FullFeatured: TokenBehavior = fromBits(31)

  implicit val eq: Eq[TokenBehavior] = Eq.fromUniversalEquals

  implicit val partialOrder: PartialOrder[TokenBehavior] = PartialOrder.from { (a, b) =>
    val ab = a meet b
    if (ab == a && ab == b) 0.0
    else if (ab == a) -1.0
    else if (ab == b) 1.0
    else Double.NaN
  }

  implicit val boundedSemilattice: BoundedSemilattice[TokenBehavior] = new BoundedSemilattice[TokenBehavior] {
    def empty: TokenBehavior = Top
    def combine(a: TokenBehavior, b: TokenBehavior): TokenBehavior = a meet b
  }

  implicit val show: Show[TokenBehavior] = Show.show(b =>
    f"TokenBehavior(${b.bits}%02d:${if (b.transferable) "T" else "-"}${if (b.splittable) "S" else "-"}${if (b.combinable) "C"
      else "-"}${if (b.expirable) "E" else "-"}${if (b.governable) "G" else "-"})"
  )

  // Wire form is the packed Int bitmask (compact + signing-canonical-safe; matches AssetCommit.behavior: Int).
  implicit val encoder: io.circe.Encoder[TokenBehavior] = io.circe.Encoder.encodeInt.contramap(_.bits)
  implicit val decoder: io.circe.Decoder[TokenBehavior] = io.circe.Decoder.decodeInt.map(fromBits)
}
