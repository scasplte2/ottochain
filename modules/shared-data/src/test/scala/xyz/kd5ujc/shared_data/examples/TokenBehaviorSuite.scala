package xyz.kd5ujc.shared_data.examples

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber.StateId
import xyz.kd5ujc.schema.token.{TokenBehavior, TokenBehaviorBuilder, TokenOperation}

import weaver.SimpleIOSuite

/**
 * TokenBehavior Matrix — Unit Tests
 *
 * TDD test suite covering all 16 TDEG token behavior types.
 * Tests define the specification for the Scala implementation.
 *
 * Groups:
 *   1. Predicates (6 tests)
 *   2. State machine structure — all 16 types (16 tests)
 *   3. Transition presence by flag (12 tests)
 *   4. Wire format correctness (6 tests)
 *   5. Operation legality (8 tests)
 *   6. Named preset factories (5 tests)
 *   7. Cross-language equivalence (5 tests)
 */
object TokenBehaviorSuite extends SimpleIOSuite {

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def hasTransition(smd: xyz.kd5ujc.schema.fiber.StateMachineDefinition, eventName: String): Boolean =
    smd.transitions.exists(_.eventName == eventName)

  private def getTransition(smd: xyz.kd5ujc.schema.fiber.StateMachineDefinition, eventName: String) =
    smd.transitions.find(_.eventName == eventName)

  private def guardContains(guard: JsonLogicExpression, path: String): Boolean =
    guard match {
      case VarExpression(Left(v), _) => v == path
      case ApplyExpression(_, args)  => args.exists(guardContains(_, path))
      case _                         => false
    }

  // ── Group 1: TokenBehavior Predicates ────────────────────────────────────

  test("T1.1 fromFlags(T=1,D=0,E=0,G=0) → NFT (value=8)") {
    IO.pure {
      val b = TokenBehavior.fromFlags(transferable = true, divisible = false, expirable = false, governable = false)
      expect(b == TokenBehavior.NFT) and expect(b.value == 8)
    }
  }

  test("T1.2 fromFlags(T=1,D=1,E=0,G=1) → GovernedFungibleToken (value=13)") {
    IO.pure {
      val b = TokenBehavior.fromFlags(transferable = true, divisible = true, expirable = false, governable = true)
      expect(b == TokenBehavior.GovernedFungibleToken) and expect(b.value == 13)
    }
  }

  test("T1.3 NFT.isTransferable → true") {
    IO.pure {
      expect(TokenBehavior.NFT.isTransferable)
    }
  }

  test("T1.4 GovernedExpirablePoints.isTransferable → false (value=7)") {
    IO.pure {
      expect(!TokenBehavior.GovernedExpirablePoints.isTransferable) and
      expect(TokenBehavior.GovernedExpirablePoints.value == 7)
    }
  }

  test("T1.5 LoyaltyPoints.isDivisible → true (value=4)") {
    IO.pure {
      expect(TokenBehavior.LoyaltyPoints.isDivisible) and
      expect(TokenBehavior.LoyaltyPoints.value == 4)
    }
  }

  test("T1.6 GovernedBadge.isGovernable → true (value=1)") {
    IO.pure {
      expect(TokenBehavior.GovernedBadge.isGovernable) and
      expect(TokenBehavior.GovernedBadge.value == 1)
    }
  }

  // ── Group 2: State Machine Structure — All 16 Types ──────────────────────

  (0 until 16).foreach { n =>
    test(s"T2.$n behavior[$n] produces valid StateMachineDefinition") {
      IO.pure {
        val behavior = TokenBehavior.unsafeFromInt(n)
        val smd = TokenBehaviorBuilder.toStateMachineDefinition(behavior)

        // Must have ACTIVE + BURNED states
        expect(smd.states.contains(StateId("ACTIVE"))) and
        expect(smd.states.contains(StateId("BURNED"))) and
        // Initial state must be ACTIVE
        expect(smd.initialState == StateId("ACTIVE")) and
        // Must always have burn transition
        expect(hasTransition(smd, "burn")) and
        // ACTIVE must be non-final
        expect(!smd.states(StateId("ACTIVE")).isFinal) and
        // BURNED must be final
        expect(smd.states(StateId("BURNED")).isFinal)
      }
    }
  }

  // ── Group 3: Transition Presence by Flag ─────────────────────────────────

  test("T3.1 Behavior 8 (NFT, T=1) has transfer transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.NFT)
      expect(hasTransition(smd, "transfer"))
    }
  }

  test("T3.2 Behavior 0 (soulbound, T=0) has no transfer transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.SoulboundReceipt)
      expect(!hasTransition(smd, "transfer"))
    }
  }

  test("T3.3 Behavior 12 (fungible, D=1) has split transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.FungibleToken)
      expect(hasTransition(smd, "split"))
    }
  }

  test("T3.4 Behavior 12 (fungible, D=1) has merge transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.FungibleToken)
      expect(hasTransition(smd, "merge"))
    }
  }

  test("T3.5 Behavior 8 (NFT, D=0) has no split transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.NFT)
      expect(!hasTransition(smd, "split"))
    }
  }

  test("T3.6 Behavior 8 (NFT, D=0) has no merge transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.NFT)
      expect(!hasTransition(smd, "merge"))
    }
  }

  test("T3.7 Behavior 2 (expirable, E=1) has expire transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.ExpirableCredential)
      expect(hasTransition(smd, "expire"))
    }
  }

  test("T3.8 Behavior 0 (permanent, E=0) has no expire transition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.SoulboundReceipt)
      expect(!hasTransition(smd, "expire"))
    }
  }

  test("T3.9 All 16 behaviors have burn transition") {
    IO.pure {
      val results = (0 until 16).map { n =>
        val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.unsafeFromInt(n))
        hasTransition(smd, "burn")
      }
      expect(results.forall(identity))
    }
  }

  test("T3.10 Behavior 13 (governed, G=1) transfer guard contains delegation.isAuthorized") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.GovernedFungibleToken)
      val xfer = getTransition(smd, "transfer")
      expect(xfer.isDefined) and
      expect(guardContains(xfer.get.guard, "delegation.isAuthorized"))
    }
  }

  test("T3.11 Behavior 12 (non-governed, G=0) transfer guard does NOT contain delegation.isAuthorized") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.FungibleToken)
      val xfer = getTransition(smd, "transfer")
      expect(xfer.isDefined) and
      expect(!guardContains(xfer.get.guard, "delegation.isAuthorized"))
    }
  }

  test("T3.12 Behavior 9 (T=1, G=1, E=0) transfer guard has governance only (no expiry check)") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.GovernedNFT)
      val xfer = getTransition(smd, "transfer")
      expect(xfer.isDefined) and
      expect(guardContains(xfer.get.guard, "delegation.isAuthorized")) and
      expect(!guardContains(xfer.get.guard, "sequenceNumber"))
    }
  }

  // ── Group 4: Wire Format Correctness ─────────────────────────────────────

  test("T4.1 initialState field is StateId(ACTIVE)") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.NFT)
      expect(smd.initialState == StateId("ACTIVE"))
    }
  }

  test("T4.2 state map keys are StateId wrappers") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.FungibleToken)
      val keys = smd.states.keySet
      expect(keys.contains(StateId("ACTIVE"))) and
      expect(keys.contains(StateId("BURNED")))
    }
  }

  test("T4.3 transitions use StateId wrappers for from/to") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.NFT)
      val burn = getTransition(smd, "burn")
      val xfer = getTransition(smd, "transfer")
      expect(burn.isDefined) and
      expect(burn.get.from == StateId("ACTIVE")) and
      expect(burn.get.to == StateId("BURNED")) and
      expect(xfer.isDefined) and
      expect(xfer.get.from == StateId("ACTIVE")) and
      expect(xfer.get.to == StateId("ACTIVE"))
    }
  }

  test("T4.4 Behavior 8 (NFT) metadata contains tokenBehavior=8") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.NFT)
      val tokenBehaviorField = smd.metadata.flatMap {
        case MapValue(m) => m.get("tokenBehavior")
        case _           => None
      }
      expect(tokenBehaviorField.contains(IntValue(BigInt(8))))
    }
  }

  test("T4.5 Behavior 10 (E=1, T=1) transfer guard contains sequenceNumber and state.expiresAtOrdinal") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.ExpirableNFT)
      val xfer = getTransition(smd, "transfer")
      expect(xfer.isDefined) and
      expect(guardContains(xfer.get.guard, "sequenceNumber")) and
      expect(guardContains(xfer.get.guard, "state.expiresAtOrdinal"))
    }
  }

  test("T4.6 Behavior 12 (D=1) split guard contains event.amount and state.balance") {
    IO.pure {
      val smd = TokenBehaviorBuilder.toStateMachineDefinition(TokenBehavior.FungibleToken)
      val split = getTransition(smd, "split")
      expect(split.isDefined) and
      expect(guardContains(split.get.guard, "event.amount")) and
      expect(guardContains(split.get.guard, "state.balance"))
    }
  }

  // ── Group 5: Operation Legality ───────────────────────────────────────────

  test("T5.1 Mint is always allowed") {
    IO.pure {
      val results = TokenBehavior.all.map(b => TokenBehavior.isOperationAllowed(b, TokenOperation.Mint))
      expect(results.forall(identity))
    }
  }

  test("T5.2 Burn is always allowed") {
    IO.pure {
      val results = TokenBehavior.all.map(b => TokenBehavior.isOperationAllowed(b, TokenOperation.Burn))
      expect(results.forall(identity))
    }
  }

  test("T5.3 Transfer allowed iff isTransferable") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        TokenBehavior.isOperationAllowed(b, TokenOperation.Transfer) == b.isTransferable
      }
      expect(results.forall(identity))
    }
  }

  test("T5.4 Split allowed iff isDivisible") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        TokenBehavior.isOperationAllowed(b, TokenOperation.Split) == b.isDivisible
      }
      expect(results.forall(identity))
    }
  }

  test("T5.5 Merge allowed iff isDivisible") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        TokenBehavior.isOperationAllowed(b, TokenOperation.Merge) == b.isDivisible
      }
      expect(results.forall(identity))
    }
  }

  test("T5.6 SetPolicy allowed iff isGovernable") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        TokenBehavior.isOperationAllowed(b, TokenOperation.SetPolicy) == b.isGovernable
      }
      expect(results.forall(identity))
    }
  }

  test("T5.7 Expire allowed iff isExpirable") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        TokenBehavior.isOperationAllowed(b, TokenOperation.Expire) == b.isExpirable
      }
      expect(results.forall(identity))
    }
  }

  test("T5.8 Extend allowed iff isExpirable") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        TokenBehavior.isOperationAllowed(b, TokenOperation.Extend) == b.isExpirable
      }
      expect(results.forall(identity))
    }
  }

  // ── Group 6: Named Preset Factories ──────────────────────────────────────

  test("T6.1 TokenBehaviorBuilder.nft produces behavior 8 definition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.nft
      expect(smd.initialState == StateId("ACTIVE")) and
      expect(hasTransition(smd, "transfer")) and
      expect(!hasTransition(smd, "split"))
    }
  }

  test("T6.2 TokenBehaviorBuilder.fungibleToken produces behavior 12 definition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.fungibleToken
      expect(hasTransition(smd, "transfer")) and
      expect(hasTransition(smd, "split")) and
      expect(hasTransition(smd, "merge")) and
      expect(!hasTransition(smd, "expire"))
    }
  }

  test("T6.3 TokenBehaviorBuilder.stablecoin produces behavior 13 definition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.stablecoin
      expect(hasTransition(smd, "transfer")) and
      expect(guardContains(getTransition(smd, "transfer").get.guard, "delegation.isAuthorized"))
    }
  }

  test("T6.4 TokenBehaviorBuilder.license produces behavior 3 definition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.license
      expect(!hasTransition(smd, "transfer")) and
      expect(hasTransition(smd, "expire")) and
      expect(smd.states.contains(StateId("EXPIRED")))
    }
  }

  test("T6.5 TokenBehaviorBuilder.soulboundBadge produces behavior 0 definition") {
    IO.pure {
      val smd = TokenBehaviorBuilder.soulboundBadge
      expect(!hasTransition(smd, "transfer")) and
      expect(!hasTransition(smd, "split")) and
      expect(!hasTransition(smd, "expire")) and
      expect(smd.transitions.map(_.eventName) == List("burn"))
    }
  }

  // ── Group 7: Structural Equivalence Checks ────────────────────────────────

  test("T7.1 All 16 behaviors have unique values 0..15") {
    IO.pure {
      val values = TokenBehavior.all.map(_.value)
      expect(values.sorted == (0 until 16).toList) and
      expect(values.toSet.size == 16)
    }
  }

  test("T7.2 All 16 behaviors have unique names") {
    IO.pure {
      val names = TokenBehavior.all.map(_.name)
      expect(names.toSet.size == 16)
    }
  }

  test("T7.3 fromInt(n).value == n for all n in 0..15") {
    IO.pure {
      val results = (0 until 16).map { n =>
        TokenBehavior.fromInt(n).map(_.value).contains(n)
      }
      expect(results.forall(identity))
    }
  }

  test("T7.4 fromInt returns None for out-of-range values") {
    IO.pure {
      expect(TokenBehavior.fromInt(-1).isEmpty) and
      expect(TokenBehavior.fromInt(16).isEmpty) and
      expect(TokenBehavior.fromInt(100).isEmpty)
    }
  }

  test("T7.5 Expirable behaviors (E=1) have EXPIRED state; non-expirable don't") {
    IO.pure {
      val results = TokenBehavior.all.map { b =>
        val smd = TokenBehaviorBuilder.toStateMachineDefinition(b)
        val hasExp = smd.states.contains(StateId("EXPIRED"))
        val expected = b.isExpirable
        hasExp == expected
      }
      expect(results.forall(identity))
    }
  }
}
