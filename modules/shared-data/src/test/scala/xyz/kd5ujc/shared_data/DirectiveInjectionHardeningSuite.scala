package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset.{AssetHolder, TokenBehavior}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry.{RegistryName, SchemaBinding, SemVer}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Directive-injection hardening (`EffectExtractor.authoredDirectiveResult`). Proves the reserved-`_`-directive
 * channel is INJECTION-IMMUNE: a directive is honoured ONLY when its KEY is LITERALLY AUTHORED in the signed
 * effect expression, never when the key is COMPUTED from event data.
 *
 *   (1) REGRESSION (the vector closed) — a transition whose effect computes a TOP-LEVEL key from event data
 *       (`{"merge":[{"var":"state"},{"set":[{}, {"var":"event.k"}, {"var":"event.v"}]}]}`) with an attacker
 *       supplying `event.k = "_transferAsset"` and `event.v = [a fully-valid transfer to the attacker's
 *       wallet]`. Under the OLD result-based extraction this drains the fiber's held asset; here the injected
 *       directive is IGNORED (a clean no-op) — the asset never moves, and there is no rejection.
 *   (2) CONTROL — the SAME machine shape with the directive key LITERALLY authored DOES move the asset
 *       (proves the channel still works; the regression is not passing because transfers are broken).
 *   (3)/(4) CONDITIONAL `_transferAsset` inside an `if` — fires iff the branch is taken (proves authored
 *       conditional directives are preserved, not regressed by the move off result-based extraction).
 *   (5)/(6) CONDITIONAL `_triggers` inside an `if` — the cross-fiber trigger fires iff the branch is taken.
 */
object DirectiveInjectionHardeningSuite extends SimpleIOSuite {

  private val binding: SchemaBinding =
    SchemaBinding(RegistryName.unsafe("gold.asset"), SemVer(1, 0, 0), Hash("schema-1.0.0"), Hash("logic-1.0.0"))

  private def heldAsset(assetId: UUID, holder: UUID, ordinal: SnapshotOrdinal): AssetRecord =
    AssetRecord(
      assetId = assetId,
      schemaBinding = binding,
      behavior = TokenBehavior.Fungible,
      holder = AssetHolder.Fiber(holder),
      amount = 100L,
      sequenceNumber = FiberOrdinal.MinValue,
      creationOrdinal = ordinal,
      latestUpdateOrdinal = ordinal
    )

  // Object-form AssetHolder recipient literal (canonical post-#193 form) for embedding in `_transferAsset`.
  private def walletHolderJson(address: String): String = s"""{ "Wallet": { "address": "$address" } }"""

  private def buildMachine(
    fiberId:   UUID,
    json:      String,
    state:     MapValue,
    initState: String,
    ordinal:   SnapshotOrdinal
  ): IO[Records.StateMachineFiberRecord] =
    for {
      definition <- IO.fromEither(decode[StateMachineDefinition](json))
      hash       <- (state: JsonLogicValue).computeDigest
    } yield Records.StateMachineFiberRecord(
      fiberId = fiberId,
      creationOrdinal = ordinal,
      previousUpdateOrdinal = ordinal,
      latestUpdateOrdinal = ordinal,
      definition = definition,
      currentState = StateId(initState),
      stateData = state,
      stateDataHash = hash,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = Set.empty,
      status = FiberStatus.Active
    )

  private def rejectionReasons(state: DataState[OnChain, CalculatedState]): List[String] =
    state.onChain.latestLogs.values.flatten.collect { case r: FiberLogEntry.RejectionReceipt => r.reason }.toList

  // ── (1) REGRESSION: a directive whose KEY is computed from event data is NEVER honoured ─────────────

  // The effect writes a top-level key COMPUTED from event data: `state ∪ { event.k : event.v }`. An honest app
  // author intends dynamic STATE keys, but the same shape lets an attacker pick `event.k = "_transferAsset"`.
  private val computedKeyEffectJson: String =
    s"""
    {
      "states": {
        "HOLDING":  { "id": "HOLDING",  "isFinal": false },
        "RELEASED": { "id": "RELEASED", "isFinal": true }
      },
      "initialState": "HOLDING",
      "transitions": [
        {
          "from": "HOLDING",
          "to": "RELEASED",
          "eventName": "poke",
          "guard": true,
          "effect": {
            "merge": [
              { "var": "state" },
              { "set": [ {}, { "var": "event.k" }, { "var": "event.v" } ] }
            ]
          },
          "dependencies": []
        }
      ]
    }
    """

  test("REGRESSION: an injected _transferAsset (computed top-level key) is ignored — the asset is NOT drained") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val victimId = UUID.fromString("17150000-0000-4000-8000-000000000001")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000f1")
      val attacker: Address = fixture.registry.addresses(Bob) // the would-be thief's wallet

      // event.v is a FULLY VALID `_transferAsset` array (object-form recipient) — so the ONLY thing stopping
      // the drain is the authored-key hardening, not a malformed directive.
      val maliciousPayload = MapValue(
        Map(
          "k" -> StrValue(ReservedKeys.TRANSFER_ASSET),
          "v" -> ArrayValue(
            List(
              MapValue(
                Map(
                  "assetId"   -> StrValue(assetId.toString),
                  "recipient" -> MapValue(Map("Wallet" -> MapValue(Map("address" -> StrValue(attacker.value.value)))))
                )
              )
            )
          )
        )
      )

      for {
        victim <- buildMachine(victimId, computedKeyEffectJson, MapValue(Map.empty), "HOLDING", fixture.ordinal)
        asset = heldAsset(assetId, victimId, fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(victimId -> victim),
            assets = SortedMap(assetId -> asset)
          )
        )
        poke = Updates.TransitionStateMachine(victimId, "poke", maliciousPayload, FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(poke, Set(Alice))
        after <- combiner.insert(genesis, Signed(poke, pr))

        assetAfter = after.calculated.assets.get(assetId)
      } yield
      // the injected directive is a clean no-op: no graceful reject, the transition still advances
      expect(rejectionReasons(after).isEmpty) and
      expect(after.calculated.stateMachines.get(victimId).map(_.currentState).contains(StateId("RELEASED"))) and
      // and CRUCIALLY the asset never moved — still fiber-held by the victim at the original sequence
      expect(assetAfter.map(_.holder).contains(AssetHolder.Fiber(victimId))) and
      expect(assetAfter.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and
      expect(after.onChain.assetCommits.get(assetId).isEmpty)
    }
  }

  // ── (2) CONTROL: the SAME shape with a LITERAL directive key DOES transfer ───────────────────────────

  private def literalTransferJson(assetId: UUID, recipientJson: String): String =
    s"""
    {
      "states": {
        "HOLDING":  { "id": "HOLDING",  "isFinal": false },
        "RELEASED": { "id": "RELEASED", "isFinal": true }
      },
      "initialState": "HOLDING",
      "transitions": [
        {
          "from": "HOLDING",
          "to": "RELEASED",
          "eventName": "poke",
          "guard": true,
          "effect": {
            "_transferAsset": [ { "assetId": "$assetId", "recipient": $recipientJson } ],
            "released": true
          },
          "dependencies": []
        }
      ]
    }
    """

  test("CONTROL: a LITERALLY-authored _transferAsset still moves the asset (the channel works)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("17150000-0000-4000-8000-000000000002")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000f2")
      val bob: Address = fixture.registry.addresses(Bob)

      for {
        escrow <- buildMachine(
          escrowId,
          literalTransferJson(assetId, walletHolderJson(bob.value.value)),
          MapValue(Map("released" -> BoolValue(false))),
          "HOLDING",
          fixture.ordinal
        )
        asset = heldAsset(assetId, escrowId, fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow),
            assets = SortedMap(assetId -> asset)
          )
        )
        poke = Updates.TransitionStateMachine(escrowId, "poke", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(poke, Set(Alice))
        after <- combiner.insert(genesis, Signed(poke, pr))
      } yield expect(rejectionReasons(after).isEmpty) and
      expect(after.calculated.assets.get(assetId).map(_.holder).contains(AssetHolder.Wallet(bob)))
    }
  }

  // ── (3)/(4) CONDITIONAL _transferAsset inside an `if` — fires iff the branch is taken ────────────────

  private def conditionalTransferJson(assetId: UUID, recipientJson: String): String =
    s"""
    {
      "states": {
        "HOLDING":  { "id": "HOLDING",  "isFinal": false },
        "RELEASED": { "id": "RELEASED", "isFinal": true }
      },
      "initialState": "HOLDING",
      "transitions": [
        {
          "from": "HOLDING",
          "to": "RELEASED",
          "eventName": "maybe_release",
          "guard": true,
          "effect": {
            "if": [
              { "var": "event.doTransfer" },
              {
                "_transferAsset": [ { "assetId": "$assetId", "recipient": $recipientJson } ],
                "released": true
              },
              { "released": false }
            ]
          },
          "dependencies": []
        }
      ]
    }
    """

  private def runConditionalTransfer(doTransfer: Boolean): IO[(Boolean, Boolean, List[String])] =
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("17150000-0000-4000-8000-000000000003")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000f3")
      val bob: Address = fixture.registry.addresses(Bob)

      for {
        escrow <- buildMachine(
          escrowId,
          conditionalTransferJson(assetId, walletHolderJson(bob.value.value)),
          MapValue(Map("released" -> BoolValue(false))),
          "HOLDING",
          fixture.ordinal
        )
        asset = heldAsset(assetId, escrowId, fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow),
            assets = SortedMap(assetId -> asset)
          )
        )
        payload = MapValue(Map("doTransfer" -> BoolValue(doTransfer)))
        ev = Updates.TransitionStateMachine(escrowId, "maybe_release", payload, FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(ev, Set(Alice))
        after <- combiner.insert(genesis, Signed(ev, pr))
        movedToWallet = after.calculated.assets.get(assetId).map(_.holder).contains(AssetHolder.Wallet(bob))
        advanced = after.calculated.stateMachines.get(escrowId).map(_.currentState).contains(StateId("RELEASED"))
      } yield (movedToWallet, advanced, rejectionReasons(after))
    }

  test("CONDITIONAL transfer: the `if`-true branch FIRES the authored _transferAsset") {
    runConditionalTransfer(doTransfer = true).map { case (moved, advanced, rejects) =>
      expect(rejects.isEmpty) and expect(advanced) and expect(moved)
    }
  }

  test("CONDITIONAL transfer: the `if`-false branch does NOT fire the _transferAsset (asset untouched)") {
    runConditionalTransfer(doTransfer = false).map { case (moved, advanced, rejects) =>
      expect(rejects.isEmpty) and expect(advanced) and expect(!moved)
    }
  }

  // ── (5)/(6) CONDITIONAL _triggers inside an `if` — cross-fiber trigger fires iff the branch is taken ──

  private def conditionalTriggerJson(targetId: UUID): String =
    s"""
    {
      "states": {
        "idle":    { "id": "idle",    "isFinal": false },
        "decided": { "id": "decided", "isFinal": false }
      },
      "initialState": "idle",
      "transitions": [
        {
          "from": "idle",
          "to": "decided",
          "eventName": "decide",
          "guard": true,
          "effect": {
            "if": [
              { "var": "event.fire" },
              {
                "_triggers": [
                  { "targetMachineId": "$targetId", "eventName": "activate", "payload": {} }
                ],
                "status": "fired"
              },
              { "status": "skipped" }
            ]
          },
          "dependencies": []
        }
      ]
    }
    """

  private val triggerTargetJson: String =
    """
    {
      "states": {
        "inactive": { "id": "inactive", "isFinal": false },
        "ACTIVE":   { "id": "ACTIVE",   "isFinal": false }
      },
      "initialState": "inactive",
      "transitions": [
        {
          "from": "inactive",
          "to": "ACTIVE",
          "eventName": "activate",
          "guard": true,
          "effect": { "status": "ACTIVE" },
          "dependencies": []
        }
      ]
    }
    """

  private def runConditionalTrigger(fire: Boolean): IO[(Boolean, Boolean, List[String])] =
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val initiatorId = UUID.fromString("17150000-0000-4000-8000-000000000005")
      val targetId = UUID.fromString("17150000-0000-4000-8000-000000000006")

      for {
        initiator <- buildMachine(
          initiatorId,
          conditionalTriggerJson(targetId),
          MapValue(Map("status" -> StrValue("idle"))),
          "idle",
          fixture.ordinal
        )
        target <- buildMachine(
          targetId,
          triggerTargetJson,
          MapValue(Map("status" -> StrValue("inactive"))),
          "inactive",
          fixture.ordinal
        )
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(initiatorId -> initiator, targetId -> target)
          )
        )
        payload = MapValue(Map("fire" -> BoolValue(fire)))
        ev = Updates.TransitionStateMachine(initiatorId, "decide", payload, FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(ev, Set(Alice))
        after <- combiner.insert(genesis, Signed(ev, pr))
        targetActivated = after.calculated.stateMachines.get(targetId).map(_.currentState).contains(StateId("ACTIVE"))
        initiatorAdvanced =
          after.calculated.stateMachines.get(initiatorId).map(_.currentState).contains(StateId("decided"))
      } yield (targetActivated, initiatorAdvanced, rejectionReasons(after))
    }

  test("CONDITIONAL trigger: the `if`-true branch FIRES the cross-fiber _triggers (target activates)") {
    runConditionalTrigger(fire = true).map { case (targetActivated, advanced, rejects) =>
      expect(rejects.isEmpty) and expect(advanced) and expect(targetActivated)
    }
  }

  test("CONDITIONAL trigger: the `if`-false branch does NOT fire the _triggers (target stays inactive)") {
    runConditionalTrigger(fire = false).map { case (targetActivated, advanced, rejects) =>
      expect(rejects.isEmpty) and expect(advanced) and expect(!targetActivated)
    }
  }

  // ── (7) MERGE-nested directive — the staked-oracle-pool PRODUCTION shape ─────────────────────────────

  // `effect: { "merge": [ { "var": "state" }, { "_addDependency": [..] } ] }` — the directive is authored
  // INSIDE a top-level `merge`, NOT as a literal top-level key. A top-level-only walker would silently DROP
  // it; the merge-recursion must honour it. This is byte-for-byte the shape of
  // e2e-test/examples/staked-oracle-pool/definition.json (`bind_registry`), so this locks in that e2e lane.
  private def mergeNestedDependencyJson(depId: UUID): String =
    s"""
    {
      "states": {
        "init":  { "id": "init",  "isFinal": false },
        "bound": { "id": "bound", "isFinal": false }
      },
      "initialState": "init",
      "transitions": [
        {
          "from": "init",
          "to": "bound",
          "eventName": "bind",
          "guard": true,
          "effect": {
            "merge": [
              { "var": "state" },
              { "_addDependency": [ { "fiberId": "$depId" } ] }
            ]
          },
          "dependencies": []
        }
      ]
    }
    """

  test("MERGE-nested: an _addDependency authored inside a top-level `merge` is honoured (staked-oracle shape)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val consumerId = UUID.fromString("17150000-0000-4000-8000-000000000007")
      val depId = UUID.fromString("17150000-0000-4000-8000-000000000008")

      for {
        consumer <- buildMachine(
          consumerId,
          mergeNestedDependencyJson(depId),
          MapValue(Map("seen" -> IntValue(0))),
          "init",
          fixture.ordinal
        )
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(stateMachines = SortedMap(consumerId -> consumer))
        )
        bind = Updates.TransitionStateMachine(consumerId, "bind", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(bind, Set(Alice))
        after <- combiner.insert(genesis, Signed(bind, pr))
        bound = after.calculated.stateMachines.get(consumerId).collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(rejectionReasons(after).isEmpty) and
      expect(bound.map(_.currentState).contains(StateId("bound"))) and
      expect(bound.exists(_.dynamicDependencies.exists(d => d.fiberId == depId && d.active)))
    }
  }
}
