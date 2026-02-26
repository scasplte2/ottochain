package xyz.kd5ujc.schema.token

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber.{State, StateId, StateMachineDefinition, Transition}

/**
 * Factory for generating StateMachineDefinition from TokenBehavior.
 *
 * Produces wire-format JSON compatible with the TypeScript SDK (PR #45).
 *
 * Guard correctness note:
 *   - Uses `sequenceNumber` for expiry guards (JLVM context variable)
 *   - TypeScript SDK uses `$ordinal` which has a latent bug (defaults to 0)
 *   - This is an intentional divergence that fixes the bug in Scala (AC4/AC6)
 */
object TokenBehaviorBuilder {

  // ── State IDs ────────────────────────────────────────────────────────────

  private val ActiveState = StateId("ACTIVE")
  private val BurnedState = StateId("BURNED")
  private val ExpiredState = StateId("EXPIRED")

  // ── Guards (JSON Logic) ──────────────────────────────────────────────────

  /**
   * Governance check: delegation.isAuthorized must be truthy.
   * Applied to transfer transitions when G=1.
   */
  private val GovernanceGuard: JsonLogicExpression =
    VarExpression(Left("delegation.isAuthorized"))

  /**
   * Expiry check: sequenceNumber < state.expiresAtOrdinal
   *
   * ⚠️ Uses `sequenceNumber` (correct JLVM variable).
   * TypeScript reference uses `$ordinal` which defaults to 0 — latent bug.
   */
  private val ExpiryGuard: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.Lt,
      List(
        VarExpression(Left("sequenceNumber")),
        VarExpression(Left("state.expiresAtOrdinal"))
      )
    )

  /**
   * Split guard: event.amount <= state.balance
   * Prevents splitting more than the current token balance.
   */
  private val SplitGuard: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.Leq,
      List(
        VarExpression(Left("event.amount")),
        VarExpression(Left("state.balance"))
      )
    )

  /** Always-true guard: no restrictions */
  private val TrueGuard: JsonLogicExpression =
    ConstExpression(BoolValue(true))

  /** No-op effect (identity transform) */
  private val NoEffect: JsonLogicExpression =
    ConstExpression(NullValue)

  // ── Guard Composition ────────────────────────────────────────────────────

  /**
   * Compose a transfer guard based on G and E flags.
   *
   * G=1, E=1 → and(governance, expiry)
   * G=1, E=0 → governance only
   * G=0, E=1 → expiry only
   * G=0, E=0 → true (no restrictions)
   */
  private def transferGuard(g: Boolean, e: Boolean): JsonLogicExpression =
    (g, e) match {
      case (true, true)   => ApplyExpression(JsonLogicOp.AndOp, List(GovernanceGuard, ExpiryGuard))
      case (true, false)  => GovernanceGuard
      case (false, true)  => ExpiryGuard
      case (false, false) => TrueGuard
    }

  // ── Transition Builder ───────────────────────────────────────────────────

  private def tx(
    from:      StateId,
    to:        StateId,
    eventName: String,
    guard:     JsonLogicExpression
  ): Transition =
    Transition(
      from = from,
      to = to,
      eventName = eventName,
      guard = guard,
      effect = NoEffect,
      dependencies = Set.empty
    )

  // ── Factory ──────────────────────────────────────────────────────────────

  /**
   * Generate a StateMachineDefinition for the given TokenBehavior.
   *
   * States:
   *   - ACTIVE  (initial, non-final)
   *   - BURNED  (terminal)
   *   - EXPIRED (terminal, E=1 only)
   *
   * Transitions always present:
   *   - burn: ACTIVE → BURNED
   *
   * Conditional transitions:
   *   - transfer: ACTIVE → ACTIVE (T=1 only)
   *   - split:    ACTIVE → ACTIVE (D=1 only)
   *   - merge:    ACTIVE → ACTIVE (D=1 only)
   *   - expire:   ACTIVE → EXPIRED (E=1 only)
   */
  def toStateMachineDefinition(behavior: TokenBehavior): StateMachineDefinition = {
    val t = behavior.isTransferable
    val d = behavior.isDivisible
    val e = behavior.isExpirable
    val g = behavior.isGovernable

    // States
    val states: Map[StateId, State] = {
      val base = Map(
        ActiveState -> State(id = ActiveState, isFinal = false),
        BurnedState -> State(id = BurnedState, isFinal = true)
      )
      if (e) base + (ExpiredState -> State(id = ExpiredState, isFinal = true))
      else base
    }

    // Transitions
    val transitions: List[Transition] = List(
      Some(tx(ActiveState, BurnedState, "burn", TrueGuard)),
      if (t) Some(tx(ActiveState, ActiveState, "transfer", transferGuard(g, e))) else None,
      if (d) Some(tx(ActiveState, ActiveState, "split", SplitGuard)) else None,
      if (d) Some(tx(ActiveState, ActiveState, "merge", TrueGuard)) else None,
      if (e) Some(tx(ActiveState, ExpiredState, "expire", TrueGuard)) else None
    ).flatten

    // Metadata
    val metadata: JsonLogicValue = MapValue(
      Map(
        "name"          -> StrValue(s"Token_${behavior.name}"),
        "description"   -> StrValue(s"OttoChain token — ${behavior.name.toLowerCase.replace("_", " ")}"),
        "version"       -> StrValue("1.0.0"),
        "category"      -> StrValue("token"),
        "tokenBehavior" -> IntValue(behavior.value)
      )
    )

    StateMachineDefinition(
      states = states,
      initialState = ActiveState,
      transitions = transitions,
      metadata = Some(metadata)
    )
  }

  // ── Named Preset Factories ───────────────────────────────────────────────

  /** NFT: T=1, D=0, E=0, G=0 — behavior 8 */
  def nft: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.NFT)

  /** Fungible token: T=1, D=1, E=0, G=0 — behavior 12 */
  def fungibleToken: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.FungibleToken)

  /** Stablecoin/governed: T=1, D=1, E=0, G=1 — behavior 13 */
  def stablecoin: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.GovernedFungibleToken)

  /** License: T=0, D=0, E=1, G=1 — behavior 3 */
  def license: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.GovernedLicense)

  /** Soulbound badge: T=0, D=0, E=0, G=0 — behavior 0 */
  def soulboundBadge: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.SoulboundReceipt)
}
