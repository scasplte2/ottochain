# Token Behavior Matrix — Scala Specification

> **Status:** TDD-Ready Specification  
> **Author:** @think (OttoBot)  
> **Date:** 2026-02-25  
> **Trello Card:** [6996301447b41cda59369256](https://trello.com/c/6996301447b41cda59369256)  
> **TypeScript Reference:** `ottobot-ai/ottochain-sdk` PR #45 (84 tests)

---

## 1. Problem Statement

The TypeScript SDK (`ottobot-ai/ottochain-sdk`) provides a complete token behavior implementation:
- 16 named behavior presets (TDEG model)
- `createTokenStateMachine(behavior: TokenBehavior): TokenStateMachineDefinition` factory
- Operation legality predicates (`isTransferable`, `isDivisible`, etc.)
- Event validators and type-safe builders

**Gap:** The Scala metagraph codebase (`scasplte2/ottochain`) has no equivalent `TokenBehavior` sealed trait or state machine builder. This forces:
1. **Manual duplication** — Every token fiber requires hand-crafted state machines
2. **No type safety** — Raw integer behavior codes with no compile-time checking
3. **Cross-language drift** — TypeScript and Scala may produce incompatible state machines

This specification defines the Scala implementation to achieve **cross-language equivalence** with the TypeScript SDK.

---

## 2. User Stories

### US-1: Metagraph Developer Creates Token Fibers
> As a metagraph developer, I want to create token state machines by specifying a `TokenBehavior` preset, so that I get correct state/transition structures without manual construction.

### US-2: Validator Checks Operation Legality
> As a validator rule implementer, I want to call `TokenBehavior.isOperationAllowed(op)` to determine if an operation (transfer, split, merge, etc.) is valid for a given token type.

### US-3: Proto Serialization for On-Chain Storage
> As a metagraph infrastructure maintainer, I want `TokenBehavior` to serialize to protobuf for on-chain storage and cross-service communication.

### US-4: Cross-Language Equivalence Testing
> As a QA engineer, I want to verify that Scala-generated state machines produce identical JSON to TypeScript-generated state machines for all 16 behavior types.

---

## 3. Module Placement

**Location:** `modules/models/src/main/scala/xyz/kd5ujc/schema/token/`

**Rationale (from @research feasibility):**
- Same module as `StateMachineDefinition`, `State`, `Transition`
- Builder is pure data construction — no IO, no engine dependencies
- `proto` module can reference it without dependency cycles
- No new sbt module required

**New files:**
```
modules/models/src/main/scala/xyz/kd5ujc/schema/token/
├── TokenBehavior.scala      # Sealed trait + 16 case objects
├── TokenBehaviorFlags.scala # Bit constants + predicates
├── TokenBehaviorBuilder.scala # Factory → StateMachineDefinition
└── TokenOperation.scala     # Operation enum for legality checks
```

---

## 4. Type Definitions

### 4.1 TokenBehavior Sealed Trait

```scala
package xyz.kd5ujc.schema.token

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/**
 * 4-bit TDEG encoding:
 *   Bit 3 (8): T = Transferable
 *   Bit 2 (4): D = Divisible
 *   Bit 1 (2): E = Expirable
 *   Bit 0 (1): G = Governable
 *
 * TokenBehavior = T×8 + D×4 + E×2 + G×1
 */
sealed trait TokenBehavior {
  def value: Int
  def name: String
  
  final def isTransferable: Boolean = (value & TokenBehaviorFlags.Transferable) != 0
  final def isDivisible: Boolean    = (value & TokenBehaviorFlags.Divisible) != 0
  final def isExpirable: Boolean    = (value & TokenBehaviorFlags.Expirable) != 0
  final def isGovernable: Boolean   = (value & TokenBehaviorFlags.Governable) != 0
}

object TokenBehavior {
  // ── Type 0–7: Soulbound (T=0) ──────────────────────────────────────────────
  
  /** Type 0: T=0, D=0, E=0, G=0 — Permanent soulbound badge */
  case object SoulboundReceipt extends TokenBehavior {
    val value = 0
    val name = "SOULBOUND_RECEIPT"
  }
  
  /** Type 1: T=0, D=0, E=0, G=1 — Governed membership badge */
  case object GovernedBadge extends TokenBehavior {
    val value = 1
    val name = "GOVERNED_BADGE"
  }
  
  /** Type 2: T=0, D=0, E=1, G=0 — Time-limited credential */
  case object ExpirableCredential extends TokenBehavior {
    val value = 2
    val name = "EXPIRABLE_CREDENTIAL"
  }
  
  /** Type 3: T=0, D=0, E=1, G=1 — Professional license */
  case object GovernedLicense extends TokenBehavior {
    val value = 3
    val name = "GOVERNED_LICENSE"
  }
  
  /** Type 4: T=0, D=1, E=0, G=0 — Accumulated reputation score */
  case object LoyaltyPoints extends TokenBehavior {
    val value = 4
    val name = "LOYALTY_POINTS"
  }
  
  /** Type 5: T=0, D=1, E=0, G=1 — Governed allocation score */
  case object GovernedAllocation extends TokenBehavior {
    val value = 5
    val name = "GOVERNED_ALLOCATION"
  }
  
  /** Type 6: T=0, D=1, E=1, G=0 — Expirable loyalty points */
  case object ExpirablePoints extends TokenBehavior {
    val value = 6
    val name = "EXPIRABLE_POINTS"
  }
  
  /** Type 7: T=0, D=1, E=1, G=1 — Governed expirable allocation */
  case object GovernedExpirablePoints extends TokenBehavior {
    val value = 7
    val name = "GOVERNED_EXPIRABLE_POINTS"
  }
  
  // ── Type 8–15: Transferable (T=1) ──────────────────────────────────────────
  
  /** Type 8: T=1, D=0, E=0, G=0 — Pure NFT */
  case object NFT extends TokenBehavior {
    val value = 8
    val name = "NFT"
  }
  
  /** Type 9: T=1, D=0, E=0, G=1 — Governed collectible */
  case object GovernedNFT extends TokenBehavior {
    val value = 9
    val name = "GOVERNED_NFT"
  }
  
  /** Type 10: T=1, D=0, E=1, G=0 — Event ticket */
  case object ExpirableNFT extends TokenBehavior {
    val value = 10
    val name = "EXPIRABLE_NFT"
  }
  
  /** Type 11: T=1, D=0, E=1, G=1 — Governed ticket */
  case object GovernedExpirableNFT extends TokenBehavior {
    val value = 11
    val name = "GOVERNED_EXPIRABLE_NFT"
  }
  
  /** Type 12: T=1, D=1, E=0, G=0 — Fungible utility token (ERC-20) */
  case object FungibleToken extends TokenBehavior {
    val value = 12
    val name = "FUNGIBLE_TOKEN"
  }
  
  /** Type 13: T=1, D=1, E=0, G=1 — Stablecoin / regulated token */
  case object GovernedFungibleToken extends TokenBehavior {
    val value = 13
    val name = "GOVERNED_FUNGIBLE_TOKEN"
  }
  
  /** Type 14: T=1, D=1, E=1, G=0 — Airline miles / subscription credits */
  case object ExpirableFungibleToken extends TokenBehavior {
    val value = 14
    val name = "EXPIRABLE_FUNGIBLE_TOKEN"
  }
  
  /** Type 15: T=1, D=1, E=1, G=1 — Full-featured financial instrument */
  case object GovernedExpirableFungible extends TokenBehavior {
    val value = 15
    val name = "GOVERNED_EXPIRABLE_FUNGIBLE"
  }
  
  // ── Lookup ─────────────────────────────────────────────────────────────────
  
  val all: List[TokenBehavior] = List(
    SoulboundReceipt, GovernedBadge, ExpirableCredential, GovernedLicense,
    LoyaltyPoints, GovernedAllocation, ExpirablePoints, GovernedExpirablePoints,
    NFT, GovernedNFT, ExpirableNFT, GovernedExpirableNFT,
    FungibleToken, GovernedFungibleToken, ExpirableFungibleToken, GovernedExpirableFungible
  )
  
  def fromInt(value: Int): Option[TokenBehavior] =
    all.find(_.value == value)
  
  def unsafeFromInt(value: Int): TokenBehavior =
    fromInt(value).getOrElse(
      throw new IllegalArgumentException(s"Invalid TokenBehavior value: $value (must be 0–15)")
    )
  
  /** Construct behavior from individual TDEG flags */
  def fromFlags(transferable: Boolean, divisible: Boolean, expirable: Boolean, governable: Boolean): TokenBehavior = {
    val value = (if (transferable) 8 else 0) |
                (if (divisible) 4 else 0) |
                (if (expirable) 2 else 0) |
                (if (governable) 1 else 0)
    unsafeFromInt(value)
  }
  
  // ── Operation Legality ─────────────────────────────────────────────────────
  
  def isOperationAllowed(behavior: TokenBehavior, op: TokenOperation): Boolean = op match {
    case TokenOperation.Mint      => true  // Always allowed (guard may restrict)
    case TokenOperation.Burn      => true  // Always allowed (even when expired)
    case TokenOperation.Transfer  => behavior.isTransferable
    case TokenOperation.Split     => behavior.isDivisible
    case TokenOperation.Merge     => behavior.isDivisible
    case TokenOperation.SetPolicy => behavior.isGovernable
    case TokenOperation.Expire    => behavior.isExpirable
    case TokenOperation.Extend    => behavior.isExpirable
  }
}
```

### 4.2 TokenBehaviorFlags

```scala
package xyz.kd5ujc.schema.token

object TokenBehaviorFlags {
  val Transferable: Int = 0x08  // bit 3
  val Divisible: Int    = 0x04  // bit 2
  val Expirable: Int    = 0x02  // bit 1
  val Governable: Int   = 0x01  // bit 0
}
```

### 4.3 TokenOperation Enum

```scala
package xyz.kd5ujc.schema.token

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._

sealed trait TokenOperation extends EnumEntry

object TokenOperation extends Enum[TokenOperation] with CirceEnum[TokenOperation] {
  case object Mint      extends TokenOperation
  case object Burn      extends TokenOperation
  case object Transfer  extends TokenOperation
  case object Split     extends TokenOperation
  case object Merge     extends TokenOperation
  case object SetPolicy extends TokenOperation
  case object Expire    extends TokenOperation
  case object Extend    extends TokenOperation
  
  val values = findValues
}
```

---

## 5. Builder: TokenBehaviorBuilder

```scala
package xyz.kd5ujc.schema.token

import xyz.kd5ujc.schema.fiber.{State, StateId, StateMachineDefinition, Transition}
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.circe.Json

/**
 * Factory for generating StateMachineDefinition from TokenBehavior.
 * 
 * Produces wire-format compatible JSON matching TypeScript SDK exactly.
 */
object TokenBehaviorBuilder {
  
  // ── State IDs ────────────────────────────────────────────────────────────
  
  private val ActiveState  = StateId("ACTIVE")
  private val BurnedState  = StateId("BURNED")
  private val ExpiredState = StateId("EXPIRED")
  
  // ── Guards (JSON Logic) ──────────────────────────────────────────────────
  
  /** Governance check: delegation.isAuthorized must be true */
  private val GovernanceGuard: JsonLogicExpression =
    JsonLogicExpression.Variable("delegation.isAuthorized")
  
  /**
   * Expiry check: sequenceNumber < state.expiresAtOrdinal
   * 
   * ⚠️ Critical: Use `sequenceNumber` NOT `$ordinal`.
   * The TypeScript SDK uses `$ordinal` which defaults to 0 (latent bug).
   * The JLVM context provides `sequenceNumber` as the current snapshot ordinal.
   * See: dfa-json-logic-patterns.md JLVM Context Variable Reference.
   */
  private val ExpiryGuard: JsonLogicExpression =
    JsonLogicExpression.Operation(
      "<",
      List(
        JsonLogicExpression.Variable("sequenceNumber"),
        JsonLogicExpression.Variable("state.expiresAtOrdinal")
      )
    )
  
  /** Split guard: event.amount <= state.balance */
  private val SplitGuard: JsonLogicExpression =
    JsonLogicExpression.Operation(
      "<=",
      List(
        JsonLogicExpression.Variable("event.amount"),
        JsonLogicExpression.Variable("state.balance")
      )
    )
  
  /** No-op guard (always passes) */
  private val TrueGuard: JsonLogicExpression =
    JsonLogicExpression.Literal(JsonLogicValue.Bool(true))
  
  /** No-op effect (identity) */
  private val NoEffect: JsonLogicExpression =
    JsonLogicExpression.Literal(JsonLogicValue.Null)
  
  // ── Guard Composition ────────────────────────────────────────────────────
  
  private def transferGuard(g: Boolean, e: Boolean): JsonLogicExpression =
    (g, e) match {
      case (true, true)   => JsonLogicExpression.And(List(GovernanceGuard, ExpiryGuard))
      case (true, false)  => GovernanceGuard
      case (false, true)  => ExpiryGuard
      case (false, false) => TrueGuard
    }
  
  // ── Transition Builders ──────────────────────────────────────────────────
  
  private def tx(from: StateId, to: StateId, eventName: String, guard: JsonLogicExpression): Transition =
    Transition(
      from         = from,
      to           = to,
      eventName    = eventName,
      guard        = guard,
      effect       = NoEffect,
      dependencies = Set.empty
    )
  
  // ── Factory ──────────────────────────────────────────────────────────────
  
  /**
   * Generate a StateMachineDefinition for the given TokenBehavior.
   *
   * States:
   *   - ACTIVE (initial)
   *   - BURNED (terminal)
   *   - EXPIRED (terminal, E=1 only)
   *
   * Transitions:
   *   - burn: ACTIVE → BURNED (always)
   *   - transfer: ACTIVE → ACTIVE (T=1 only, guards per G/E)
   *   - split: ACTIVE → ACTIVE (D=1 only, amount guard)
   *   - merge: ACTIVE → ACTIVE (D=1 only)
   *   - expire: ACTIVE → EXPIRED (E=1 only)
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
      // burn — always present
      Some(tx(ActiveState, BurnedState, "burn", TrueGuard)),
      // transfer — T=1 only
      if (t) Some(tx(ActiveState, ActiveState, "transfer", transferGuard(g, e))) else None,
      // split — D=1 only
      if (d) Some(tx(ActiveState, ActiveState, "split", SplitGuard)) else None,
      // merge — D=1 only
      if (d) Some(tx(ActiveState, ActiveState, "merge", TrueGuard)) else None,
      // expire — E=1 only
      if (e) Some(tx(ActiveState, ExpiredState, "expire", TrueGuard)) else None
    ).flatten
    
    // Metadata
    val metadata = JsonLogicValue.Obj(Map(
      "name"          -> JsonLogicValue.Str(s"Token_${behavior.name}"),
      "description"   -> JsonLogicValue.Str(s"OttoChain token — ${behavior.name.toLowerCase.replace("_", " ")}"),
      "version"       -> JsonLogicValue.Str("1.0.0"),
      "category"      -> JsonLogicValue.Str("token"),
      "tokenBehavior" -> JsonLogicValue.Int(behavior.value)
    ))
    
    StateMachineDefinition(
      states       = states,
      initialState = ActiveState,
      transitions  = transitions,
      metadata     = Some(metadata)
    )
  }
  
  // ── Named Preset Factories ───────────────────────────────────────────────
  
  /** NFT: T=1, D=0, E=0, G=0 — behavior 8 */
  def nft: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.NFT)
  
  /** Fungible token: T=1, D=1, E=0, G=0 — behavior 12 */
  def fungibleToken: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.FungibleToken)
  
  /** Stablecoin: T=1, D=1, E=0, G=1 — behavior 13 */
  def stablecoin: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.GovernedFungibleToken)
  
  /** License: T=0, D=0, E=1, G=1 — behavior 3 */
  def license: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.GovernedLicense)
  
  /** Soulbound badge: T=0, D=0, E=0, G=0 — behavior 0 */
  def soulboundBadge: StateMachineDefinition = toStateMachineDefinition(TokenBehavior.SoulboundReceipt)
}
```

---

## 6. Proto Definition

**File:** `modules/proto/src/main/protobuf/ottochain/v1/token.proto`

```protobuf
syntax = "proto3";

package ottochain.v1;

option java_package = "xyz.kd5ujc.ottochain.proto.v1";
option java_multiple_files = true;

/**
 * Token behavior encoding: 4-bit TDEG model.
 *   Bit 3 (8): Transferable
 *   Bit 2 (4): Divisible
 *   Bit 1 (2): Expirable
 *   Bit 0 (1): Governable
 */
enum TokenBehaviorType {
  TOKEN_BEHAVIOR_TYPE_UNSPECIFIED = 0;
  
  // Soulbound (T=0)
  SOULBOUND_RECEIPT         = 1;   // value=0: T=0, D=0, E=0, G=0
  GOVERNED_BADGE            = 2;   // value=1: T=0, D=0, E=0, G=1
  EXPIRABLE_CREDENTIAL      = 3;   // value=2: T=0, D=0, E=1, G=0
  GOVERNED_LICENSE          = 4;   // value=3: T=0, D=0, E=1, G=1
  LOYALTY_POINTS            = 5;   // value=4: T=0, D=1, E=0, G=0
  GOVERNED_ALLOCATION       = 6;   // value=5: T=0, D=1, E=0, G=1
  EXPIRABLE_POINTS          = 7;   // value=6: T=0, D=1, E=1, G=0
  GOVERNED_EXPIRABLE_POINTS = 8;   // value=7: T=0, D=1, E=1, G=1
  
  // Transferable (T=1)
  NFT                         = 9;   // value=8:  T=1, D=0, E=0, G=0
  GOVERNED_NFT                = 10;  // value=9:  T=1, D=0, E=0, G=1
  EXPIRABLE_NFT               = 11;  // value=10: T=1, D=0, E=1, G=0
  GOVERNED_EXPIRABLE_NFT      = 12;  // value=11: T=1, D=0, E=1, G=1
  FUNGIBLE_TOKEN              = 13;  // value=12: T=1, D=1, E=0, G=0
  GOVERNED_FUNGIBLE_TOKEN     = 14;  // value=13: T=1, D=1, E=0, G=1
  EXPIRABLE_FUNGIBLE_TOKEN    = 15;  // value=14: T=1, D=1, E=1, G=0
  GOVERNED_EXPIRABLE_FUNGIBLE = 16;  // value=15: T=1, D=1, E=1, G=1
}

/**
 * Token operation types for legality checking.
 */
enum TokenOperationType {
  TOKEN_OPERATION_TYPE_UNSPECIFIED = 0;
  MINT       = 1;
  BURN       = 2;
  TRANSFER   = 3;
  SPLIT      = 4;
  MERGE      = 5;
  SET_POLICY = 6;
  EXPIRE     = 7;
  EXTEND     = 8;
}

/**
 * Token behavior message for wire format.
 * Use behavior_value for the 4-bit integer, or behavior_type for named enum.
 */
message TokenBehavior {
  oneof behavior {
    int32 behavior_value = 1;           // 0–15 direct encoding
    TokenBehaviorType behavior_type = 2; // Named enum
  }
}
```

**Rationale:** New `token.proto` under `ottochain/v1/` establishes the domain-proto convention. Future domain-specific types (identity, market, governance) follow the same pattern.

---

## 7. Acceptance Criteria

### AC1: Sealed Trait with 16 Case Objects
- [ ] `TokenBehavior` is a sealed trait
- [ ] Exactly 16 case objects exist (0–15)
- [ ] Each case object has `value: Int` and `name: String`
- [ ] `TokenBehavior.all` contains all 16 in order

### AC2: TDEG Predicates
- [ ] `isTransferable`, `isDivisible`, `isExpirable`, `isGovernable` methods on trait
- [ ] Each predicate correctly checks the corresponding bit
- [ ] `TokenBehavior.fromFlags(t, d, e, g)` constructs correct behavior

### AC3: Builder Factory
- [ ] `TokenBehaviorBuilder.toStateMachineDefinition(behavior)` returns `StateMachineDefinition`
- [ ] Always produces ACTIVE + BURNED states
- [ ] EXPIRED state added only when E=1
- [ ] `burn` transition always present
- [ ] `transfer` transition present only when T=1
- [ ] `split` and `merge` transitions present only when D=1
- [ ] `expire` transition present only when E=1

### AC4: Guard Correctness
- [ ] Transfer guard for G=1 contains `delegation.isAuthorized`
- [ ] Transfer guard for E=1 contains `sequenceNumber < state.expiresAtOrdinal`
  - ⚠️ **Must use `sequenceNumber`** — TypeScript uses `$ordinal` which is a latent bug (defaults to 0)
- [ ] Combined guard for G=1 + E=1 uses `and` operator
- [ ] Split guard checks `event.amount <= state.balance`

### AC5: Operation Legality API
- [ ] `TokenBehavior.isOperationAllowed(behavior, op): Boolean` exists
- [ ] `Transfer` returns `behavior.isTransferable`
- [ ] `Split` and `Merge` return `behavior.isDivisible`
- [ ] `SetPolicy` returns `behavior.isGovernable`
- [ ] `Expire` and `Extend` return `behavior.isExpirable`
- [ ] `Mint` and `Burn` always return `true`

### AC6: Proto Serialization
- [ ] `token.proto` exists under `modules/proto/src/main/protobuf/ottochain/v1/`
- [ ] `TokenBehaviorType` enum has 17 values (UNSPECIFIED + 16 behaviors)
- [ ] `TokenOperationType` enum has 9 values (UNSPECIFIED + 8 operations)
- [ ] ScalaPB generates Scala classes from proto

### AC7: Wire Format Compatibility
- [ ] `StateId` serializes as `{ "value": "ACTIVE" }` not plain `"ACTIVE"`
- [ ] Transitions use `StateId` wrapper for `from` and `to`
- [ ] Metadata includes `tokenBehavior: Int` field
- [ ] JSON output matches TypeScript SDK exactly

### AC8: Named Preset Factories
- [ ] `TokenBehaviorBuilder.nft` returns behavior 8 definition
- [ ] `TokenBehaviorBuilder.fungibleToken` returns behavior 12 definition
- [ ] `TokenBehaviorBuilder.stablecoin` returns behavior 13 definition
- [ ] `TokenBehaviorBuilder.license` returns behavior 3 definition
- [ ] `TokenBehaviorBuilder.soulboundBadge` returns behavior 0 definition

### AC9: Cross-Language Equivalence
- [ ] For all 16 behaviors, Scala JSON output equals TypeScript JSON output
- [ ] State names match exactly (ACTIVE, BURNED, EXPIRED)
- [ ] Transition event names match exactly (burn, transfer, split, merge, expire)
- [ ] Guard JSON Logic structure is identical

---

## 8. TDD Test Cases

### Group 1: TokenBehavior Predicates (6 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T1.1 | `TokenBehavior.fromFlags(true, false, false, false)` | `TokenBehavior.NFT` (value=8) |
| T1.2 | `TokenBehavior.fromFlags(true, true, false, true)` | `TokenBehavior.GovernedFungibleToken` (value=13) |
| T1.3 | `TokenBehavior.NFT.isTransferable` | `true` |
| T1.4 | `TokenBehavior.GovernedExpirablePoints.isTransferable` | `false` (value=7) |
| T1.5 | `TokenBehavior.LoyaltyPoints.isDivisible` | `true` (value=4) |
| T1.6 | `TokenBehavior.GovernedBadge.isGovernable` | `true` (value=1) |

### Group 2: State Machine Structure — All 16 Types (16 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T2.0–15 | `TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.unsafeFromInt(n))` for n=0..15 | Valid `StateMachineDefinition` with ACTIVE + BURNED states, initialState=ACTIVE, burn transition present |

### Group 3: Transition Presence by Flag (12 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T3.1 | Behavior 8 (NFT, T=1) | Has `transfer` transition |
| T3.2 | Behavior 0 (soulbound, T=0) | No `transfer` transition |
| T3.3 | Behavior 12 (fungible, D=1) | Has `split` transition |
| T3.4 | Behavior 12 (fungible, D=1) | Has `merge` transition |
| T3.5 | Behavior 8 (NFT, D=0) | No `split` transition |
| T3.6 | Behavior 8 (NFT, D=0) | No `merge` transition |
| T3.7 | Behavior 2 (expirable, E=1) | Has `expire` transition |
| T3.8 | Behavior 0 (permanent, E=0) | No `expire` transition |
| T3.9 | All 16 types | Have `burn` transition |
| T3.10 | Behavior 13 (governed, G=1) | Transfer guard contains `delegation.isAuthorized` |
| T3.11 | Behavior 12 (not governed, G=0) | Transfer guard does NOT contain `delegation.isAuthorized` |
| T3.12 | Behavior 9 (T=1, G=1, E=0) | Transfer guard has governance only (no expiry check) |

### Group 4: Wire Format Correctness (6 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T4.1 | `initialState` field | `StateId("ACTIVE")` (serializes as `{ "value": "ACTIVE" }`) |
| T4.2 | State map keys | `StateId` wrappers, not plain strings |
| T4.3 | Transition `from`/`to` | `StateId` wrappers |
| T4.4 | Behavior 8 metadata | `tokenBehavior: 8` in metadata JSON |
| T4.5 | Behavior 10 (E=1, T=1) transfer guard | Contains `sequenceNumber` and `state.expiresAtOrdinal` |
| T4.6 | Behavior 12 (D=1) split guard | Contains `event.amount` and `state.balance` |

### Group 5: Operation Legality (8 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T5.1 | `isOperationAllowed(SoulboundReceipt, Transfer)` | `false` |
| T5.2 | `isOperationAllowed(NFT, Transfer)` | `true` |
| T5.3 | `isOperationAllowed(NFT, Split)` | `false` |
| T5.4 | `isOperationAllowed(FungibleToken, Split)` | `true` |
| T5.5 | `isOperationAllowed(NFT, Burn)` | `true` |
| T5.6 | `isOperationAllowed(GovernedBadge, SetPolicy)` | `true` |
| T5.7 | `isOperationAllowed(NFT, SetPolicy)` | `false` |
| T5.8 | `isOperationAllowed(ExpirableCredential, Expire)` | `true` |

### Group 6: Named Presets (5 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T6.1 | `TokenBehaviorBuilder.nft.metadata` | `tokenBehavior: 8` |
| T6.2 | `TokenBehaviorBuilder.fungibleToken.metadata` | `tokenBehavior: 12` |
| T6.3 | `TokenBehaviorBuilder.stablecoin` | Has transfer with governance guard |
| T6.4 | `TokenBehaviorBuilder.license` | No transfer (soulbound) + has expire |
| T6.5 | `TokenBehavior.all.length` | 16, unique values 0–15 |

### Group 7: Cross-Language Equivalence (5 tests)

| ID | Test Case | Expected |
|----|-----------|----------|
| T7.1 | Scala NFT JSON vs TypeScript NFT JSON | Identical structure |
| T7.2 | Scala Fungible JSON vs TypeScript Fungible JSON | Identical structure |
| T7.3 | Scala Stablecoin JSON vs TypeScript Stablecoin JSON | Identical structure |
| T7.4 | Scala License JSON vs TypeScript License JSON | Identical structure |
| T7.5 | Scala Soulbound JSON vs TypeScript Soulbound JSON | Identical structure |

**Total: 58 TDD test cases**

---

## 9. Implementation Notes

### 9.1 Guard Expression Construction

The `JsonLogicExpression` type is already defined in `io.constellationnetwork.metagraph_sdk.json_logic`. The builder must construct guard expressions using the existing API:

```scala
// Variable reference
JsonLogicExpression.Variable("sequenceNumber")

// Binary operation
JsonLogicExpression.Operation("<", List(lhs, rhs))

// Logical AND
JsonLogicExpression.And(List(guard1, guard2))

// Literal true
JsonLogicExpression.Literal(JsonLogicValue.Bool(true))
```

### 9.2 StateId Wire Format

The existing `StateId` case class uses Circe magnolia derivation with `keyEncoder` for map keys:

```scala
@derive(encoder, decoder, keyEncoder, keyDecoder)
case class StateId(value: String) extends AnyVal
```

This serializes state maps correctly:
```json
{
  "states": {
    "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false }
  }
}
```

### 9.3 sequenceNumber vs $ordinal

**Critical:** The TypeScript SDK uses `{ "var": "$ordinal" }` in the expiry guard. This is a latent bug because `$ordinal` is not a defined JLVM context variable and defaults to `0`, meaning expiry checks always pass.

The Scala implementation **must** use `sequenceNumber` which is the correct JLVM context variable for the current snapshot ordinal. This is an intentional fix documented in AC4.

Reference: JLVM Context Variable Table from `dfa-json-logic-patterns.md`:
- `sequenceNumber` — current fiber sequence number (monotonic counter)
- `state` — current state data object
- `event` — incoming event payload
- `eventName` — event name string
- `machineId` — fiber UUID
- `proofs` — array of cryptographic proofs (signers)

### 9.4 Circe Encoders for Cross-Language Testing

To verify cross-language equivalence, generate JSON from both Scala and TypeScript implementations and compare. The Scala JSON output should match exactly when:

1. Using the same Circe encoder configuration (magnolia derivation)
2. `StateId` wraps values as `{ "value": "..." }`
3. Metadata includes all TypeScript fields

---

## 10. Dependencies

### Existing (no changes needed)
- `derevo-circe` — Circe encoder/decoder derivation
- `io.constellationnetwork.metagraph_sdk.json_logic` — JsonLogicExpression types
- `enumeratum` + `enumeratum-circe` — TokenOperation enum

### New (already in project, just need import)
- None — all dependencies are already available in `modules/models`

---

## 11. Open Questions (Resolved)

| # | Question | Answer |
|---|----------|--------|
| OQ-1 | Module placement? | `modules/models/` — same as StateMachineDefinition |
| OQ-2 | Proto file organization? | New `token.proto` under `ottochain/v1/` |
| OQ-3 | StateId format timing? | STABLE — use current `{ "value": "..." }` format |
| OQ-4 | TypeScript `$ordinal` bug? | Scala uses `sequenceNumber` per JLVM spec (intentional fix) |

---

## 12. References

- **TypeScript Implementation:** `ottobot-ai/ottochain-sdk` branch `feat/asset-model-token-impl`
- **TypeScript Tests:** 84 tests in `tests/apps/token/`
- **Token Behavior Matrix Spec:** `docs/design/token-behavior-matrix.md` in ottochain-sdk
- **JLVM Context Variables:** `docs/design/dfa-json-logic-patterns.md` §11
- **@research Feasibility:** TRELLO-CONTEXT.md 2026-02-25 entry

---

*Specification complete. Ready for TDD test implementation.*
