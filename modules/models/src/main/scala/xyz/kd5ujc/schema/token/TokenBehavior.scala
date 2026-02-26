package xyz.kd5ujc.schema.token

/**
 * 4-bit TDEG token behavior matrix.
 *
 * Encoding:
 *   Bit 3 (8): T = Transferable — can change owner
 *   Bit 2 (4): D = Divisible   — can be split/merged
 *   Bit 1 (2): E = Expirable   — has time-based validity
 *   Bit 0 (1): G = Governable  — subject to validator policy
 *
 * TokenBehavior.value = T×8 + D×4 + E×2 + G×1  (0..15)
 *
 * Cross-language equivalence: matches TypeScript SDK PR #45.
 * ⚠️ Guard difference: Scala uses `sequenceNumber`; TypeScript uses `$ordinal`
 *    (TypeScript has a latent bug — $ordinal defaults to 0 in JLVM context).
 */
sealed trait TokenBehavior {
  def value: Int
  def name: String

  final def isTransferable: Boolean = (value & TokenBehaviorFlags.Transferable) != 0
  final def isDivisible: Boolean = (value & TokenBehaviorFlags.Divisible) != 0
  final def isExpirable: Boolean = (value & TokenBehaviorFlags.Expirable) != 0
  final def isGovernable: Boolean = (value & TokenBehaviorFlags.Governable) != 0
}

object TokenBehavior {

  // ── Type 0–7: Soulbound (T=0) ──────────────────────────────────────────────

  /** Type 0: T=0, D=0, E=0, G=0 — Permanent soulbound badge */
  case object SoulboundReceipt extends TokenBehavior {
    val value = 0; val name = "SOULBOUND_RECEIPT"
  }

  /** Type 1: T=0, D=0, E=0, G=1 — Governed membership badge */
  case object GovernedBadge extends TokenBehavior {
    val value = 1; val name = "GOVERNED_BADGE"
  }

  /** Type 2: T=0, D=0, E=1, G=0 — Time-limited credential */
  case object ExpirableCredential extends TokenBehavior {
    val value = 2; val name = "EXPIRABLE_CREDENTIAL"
  }

  /** Type 3: T=0, D=0, E=1, G=1 — Professional license */
  case object GovernedLicense extends TokenBehavior {
    val value = 3; val name = "GOVERNED_LICENSE"
  }

  /** Type 4: T=0, D=1, E=0, G=0 — Accumulated reputation score */
  case object LoyaltyPoints extends TokenBehavior {
    val value = 4; val name = "LOYALTY_POINTS"
  }

  /** Type 5: T=0, D=1, E=0, G=1 — Governed allocation score */
  case object GovernedAllocation extends TokenBehavior {
    val value = 5; val name = "GOVERNED_ALLOCATION"
  }

  /** Type 6: T=0, D=1, E=1, G=0 — Expirable loyalty points */
  case object ExpirablePoints extends TokenBehavior {
    val value = 6; val name = "EXPIRABLE_POINTS"
  }

  /** Type 7: T=0, D=1, E=1, G=1 — Governed expirable allocation */
  case object GovernedExpirablePoints extends TokenBehavior {
    val value = 7; val name = "GOVERNED_EXPIRABLE_POINTS"
  }

  // ── Type 8–15: Transferable (T=1) ──────────────────────────────────────────

  /** Type 8: T=1, D=0, E=0, G=0 — Pure NFT */
  case object NFT extends TokenBehavior {
    val value = 8; val name = "NFT"
  }

  /** Type 9: T=1, D=0, E=0, G=1 — Governed collectible */
  case object GovernedNFT extends TokenBehavior {
    val value = 9; val name = "GOVERNED_NFT"
  }

  /** Type 10: T=1, D=0, E=1, G=0 — Event ticket */
  case object ExpirableNFT extends TokenBehavior {
    val value = 10; val name = "EXPIRABLE_NFT"
  }

  /** Type 11: T=1, D=0, E=1, G=1 — Governed ticket */
  case object GovernedExpirableNFT extends TokenBehavior {
    val value = 11; val name = "GOVERNED_EXPIRABLE_NFT"
  }

  /** Type 12: T=1, D=1, E=0, G=0 — Fungible utility token (ERC-20) */
  case object FungibleToken extends TokenBehavior {
    val value = 12; val name = "FUNGIBLE_TOKEN"
  }

  /** Type 13: T=1, D=1, E=0, G=1 — Stablecoin / regulated token */
  case object GovernedFungibleToken extends TokenBehavior {
    val value = 13; val name = "GOVERNED_FUNGIBLE_TOKEN"
  }

  /** Type 14: T=1, D=1, E=1, G=0 — Airline miles / subscription credits */
  case object ExpirableFungibleToken extends TokenBehavior {
    val value = 14; val name = "EXPIRABLE_FUNGIBLE_TOKEN"
  }

  /** Type 15: T=1, D=1, E=1, G=1 — Full-featured financial instrument */
  case object GovernedExpirableFungible extends TokenBehavior {
    val value = 15; val name = "GOVERNED_EXPIRABLE_FUNGIBLE"
  }

  // ── Lookup ─────────────────────────────────────────────────────────────────

  val all: List[TokenBehavior] = List(
    SoulboundReceipt,
    GovernedBadge,
    ExpirableCredential,
    GovernedLicense,
    LoyaltyPoints,
    GovernedAllocation,
    ExpirablePoints,
    GovernedExpirablePoints,
    NFT,
    GovernedNFT,
    ExpirableNFT,
    GovernedExpirableNFT,
    FungibleToken,
    GovernedFungibleToken,
    ExpirableFungibleToken,
    GovernedExpirableFungible
  )

  def fromInt(value: Int): Option[TokenBehavior] =
    all.find(_.value == value)

  def unsafeFromInt(value: Int): TokenBehavior =
    fromInt(value).getOrElse(
      throw new IllegalArgumentException(s"Invalid TokenBehavior value: $value (must be 0–15)")
    )

  /** Construct behavior from individual TDEG flags */
  def fromFlags(transferable: Boolean, divisible: Boolean, expirable: Boolean, governable: Boolean): TokenBehavior = {
    val v =
      (if (transferable) 8 else 0) |
      (if (divisible) 4 else 0) |
      (if (expirable) 2 else 0) |
      (if (governable) 1 else 0)
    unsafeFromInt(v)
  }

  // ── Operation Legality ─────────────────────────────────────────────────────

  def isOperationAllowed(behavior: TokenBehavior, op: TokenOperation): Boolean = op match {
    case TokenOperation.Mint      => true // Always allowed (guard may restrict)
    case TokenOperation.Burn      => true // Always allowed (even when expired)
    case TokenOperation.Transfer  => behavior.isTransferable
    case TokenOperation.Split     => behavior.isDivisible
    case TokenOperation.Merge     => behavior.isDivisible
    case TokenOperation.SetPolicy => behavior.isGovernable
    case TokenOperation.Expire    => behavior.isExpirable
    case TokenOperation.Extend    => behavior.isExpirable
  }
}
