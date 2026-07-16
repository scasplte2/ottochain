package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * The protocol nullifier set (docs/proposals/protocol-nullifier-set.md) wired END-TO-END through the
 * combiner: a transition emitting `_consumeNullifier` inserts `nullifier/<emitter-domain>/<nf> -> ordinal`
 * into `CalculatedState.nullifiers`, and the combiner (`NullifierCombiner`) is the SOLE, graceful
 * enforcement site. Proves:
 *   (1) HAPPY PATH — the nf lands under the EMITTING fiber's own domain with the batch ordinal;
 *   (2) DOUBLE-SPEND — a second consumption of the same nf under the same domain is a RejectionReceipt and
 *       the whole update (fiber mutation included) is discarded — protocol-enforced "one script, one fill";
 *   (3) DOMAIN ISOLATION — a DIFFERENT fiber consuming the SAME nf value succeeds (the domain is the
 *       emitter's own id, so apps can never collide with / grief each other's namespaces);
 *   (4) CAP — more than `maxNullifierConsumptions` items in one transition is a graceful reject;
 *   (5) MALFORMED — a non-hex nf value is a LOUD graceful reject (never a silent drop);
 *   (6) POLICY — `allowedEffects` without the NULLIFIER family aborts the transition (fail-closed dial);
 *   (7) SANITIZATION — `_consumeNullifier` never leaks into stateData (StateMerger strips `_`-keys).
 */
object NullifierConsumeSuite extends SimpleIOSuite {

  private val nfA = "a" * 64
  private val nfB = "b" * 64

  // A one-state "prescription" machine: OPEN -> OPEN on "fill", whose effect consumes the given nf values.
  // `nfJsonItems` is the raw JSON of the `_consumeNullifier` array items (strings / sub-expressions).
  private def fillJson(nfJsonItems: String): String =
    s"""
    {
      "states": {
        "OPEN": { "id": "OPEN", "isFinal": false }
      },
      "initialState": "OPEN",
      "transitions": [
        {
          "from": "OPEN",
          "to": "OPEN",
          "eventName": "fill",
          "guard": true,
          "effect": { "_consumeNullifier": [ $nfJsonItems ], "filled": true },
          "dependencies": []
        }
      ]
    }
    """

  private def buildFiber(id: UUID, json: String, ordinal: SnapshotOrdinal): IO[Records.StateMachineFiberRecord] =
    for {
      definition <- IO.fromEither(decode[StateMachineDefinition](json))
      stateData = MapValue(Map("filled" -> BoolValue(false)))
      hash <- (stateData: JsonLogicValue).computeDigest
    } yield Records.StateMachineFiberRecord(
      fiberId = id,
      creationOrdinal = ordinal,
      previousUpdateOrdinal = ordinal,
      latestUpdateOrdinal = ordinal,
      definition = definition,
      currentState = StateId("OPEN"),
      stateData = stateData,
      stateDataHash = hash,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = Set.empty,
      status = FiberStatus.Active
    )

  private def rejectionReasons(state: DataState[OnChain, CalculatedState]): List[String] =
    state.onChain.latestLogs.values.flatten.collect { case r: FiberLogEntry.RejectionReceipt => r.reason }.toList

  private def fill(fiberId: UUID, seq: FiberOrdinal = FiberOrdinal.MinValue): Updates.TransitionStateMachine =
    Updates.TransitionStateMachine(fiberId, "fill", MapValue(Map.empty), seq)

  // ── (1) happy path: nf present under the emitter domain with the batch ordinal ──────────────────

  test("happy path: the consumed nf lands under the EMITTING fiber's domain with the batch ordinal") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val fiberId = UUID.fromString("d0c70000-0000-4000-8000-000000000001")
      for {
        fiber <- buildFiber(fiberId, fillJson(s""""$nfA""""), fixture.ordinal)
        genesis = DataState(OnChain.genesis, CalculatedState.genesis.copy(stateMachines = SortedMap(fiberId -> fiber)))
        pr    <- fixture.registry.generateProofs(fill(fiberId), Set(Alice))
        after <- combiner.insert(genesis, Signed(fill(fiberId), pr))
        domain = after.calculated.nullifiers.get(fiberId)
      } yield expect(rejectionReasons(after).isEmpty) and
      expect(domain.flatMap(_.get(Hash(nfA))).contains(fixture.ordinal)) and
      // the fiber's own transition committed alongside the insert
      expect(after.calculated.stateMachines.get(fiberId).map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      // nothing landed under any OTHER domain
      expect(after.calculated.nullifiers.keySet == Set(fiberId))
    }
  }

  // ── (2) double-spend: second insert of the same nf ⇒ RejectionReceipt, state unmutated ──────────

  test("double-spend: a second fill consuming the same nf is a RejectionReceipt and mutates nothing") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val fiberId = UUID.fromString("d0c70000-0000-4000-8000-000000000002")
      for {
        fiber <- buildFiber(fiberId, fillJson(s""""$nfA""""), fixture.ordinal)
        genesis = DataState(OnChain.genesis, CalculatedState.genesis.copy(stateMachines = SortedMap(fiberId -> fiber)))
        pr1    <- fixture.registry.generateProofs(fill(fiberId), Set(Alice))
        after1 <- combiner.insert(genesis, Signed(fill(fiberId), pr1))
        second = fill(fiberId, FiberOrdinal.MinValue.next)
        pr2    <- fixture.registry.generateProofs(second, Set(Alice))
        after2 <- combiner.insert(after1, Signed(second, pr2))
      } yield expect(rejectionReasons(after1).isEmpty) and
      expect(rejectionReasons(after2).exists(_.contains("nullifier already consumed (double-spend)"))) and
      // the nullifier map is UNMUTATED: still exactly the first spend at the first ordinal
      expect(after2.calculated.nullifiers == after1.calculated.nullifiers) and
      // the second update's fiber mutation was discarded along with it (all-or-nothing)
      expect(after2.calculated.stateMachines.get(fiberId).map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next))
    }
  }

  // ── (3) domain isolation: a DIFFERENT fiber may consume the SAME nf value ───────────────────────

  test("domain isolation: two different fibers consuming the same nf value both succeed") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val fiberA = UUID.fromString("d0c70000-0000-4000-8000-00000000000a")
      val fiberB = UUID.fromString("d0c70000-0000-4000-8000-00000000000b")
      for {
        fa <- buildFiber(fiberA, fillJson(s""""$nfA""""), fixture.ordinal)
        fb <- buildFiber(fiberB, fillJson(s""""$nfA""""), fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(stateMachines = SortedMap(fiberA -> fa, fiberB -> fb))
        )
        prA    <- fixture.registry.generateProofs(fill(fiberA), Set(Alice))
        after1 <- combiner.insert(genesis, Signed(fill(fiberA), prA))
        prB    <- fixture.registry.generateProofs(fill(fiberB), Set(Alice))
        after2 <- combiner.insert(after1, Signed(fill(fiberB), prB))
      } yield expect(rejectionReasons(after2).isEmpty) and
      expect(after2.calculated.nullifiers.get(fiberA).flatMap(_.get(Hash(nfA))).contains(fixture.ordinal)) and
      expect(after2.calculated.nullifiers.get(fiberB).flatMap(_.get(Hash(nfA))).contains(fixture.ordinal))
    }
  }

  // ── (4) cap: more than maxNullifierConsumptions items in one transition ⇒ reject ────────────────

  test("cap: a transition consuming 33 nullifiers (cap 32) is a graceful RejectionReceipt") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val fiberId = UUID.fromString("d0c70000-0000-4000-8000-000000000004")
      val cap = ExecutionLimits().maxNullifierConsumptions
      val items = (0 to cap).map(i => "\"%064x\"".format(i)).mkString(", ") // cap + 1 = 33 distinct nfs
      for {
        fiber <- buildFiber(fiberId, fillJson(items), fixture.ordinal)
        genesis = DataState(OnChain.genesis, CalculatedState.genesis.copy(stateMachines = SortedMap(fiberId -> fiber)))
        pr    <- fixture.registry.generateProofs(fill(fiberId), Set(Alice))
        after <- combiner.insert(genesis, Signed(fill(fiberId), pr))
      } yield expect(rejectionReasons(after).exists(_.contains("exceeding maxNullifierConsumptions"))) and
      // nothing was inserted (all-or-nothing) and the fiber mutation was discarded
      expect(after.calculated.nullifiers.isEmpty) and
      expect(after.calculated.stateMachines.get(fiberId).map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  // ── (5) malformed nf: bad hex is a LOUD graceful reject ─────────────────────────────────────────

  test("malformed: a non-hex nf value is a graceful RejectionReceipt (never a silent drop)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val fiberId = UUID.fromString("d0c70000-0000-4000-8000-000000000005")
      for {
        f <- buildFiber(fiberId, fillJson(""""not-a-nullifier""""), fixture.ordinal)
        genesis = DataState(OnChain.genesis, CalculatedState.genesis.copy(stateMachines = SortedMap(fiberId -> f)))
        pr    <- fixture.registry.generateProofs(fill(fiberId), Set(Alice))
        after <- combiner.insert(genesis, Signed(fill(fiberId), pr))
      } yield expect(rejectionReasons(after).exists(r => r.contains("_consumeNullifier") && r.contains("64 hex"))) and
      expect(after.calculated.nullifiers.isEmpty)
    }
  }

  // ── (6) allowedEffects: a policy without the NULLIFIER family aborts the transition ─────────────

  test("allowedEffects: consuming a nullifier under a policy without NULLIFIER is a PolicyViolation abort") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      for {
        base <- IO.fromEither(decode[StateMachineDefinition](fillJson(s""""$nfA"""")))
        forbid = base.copy(policy = FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit))))
        permit = base.copy(policy = FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Nullifier))))
        fid = UUID.fromString("d0c70000-0000-4000-8000-000000000006")
        data = MapValue(Map("filled" -> BoolValue(false)))
        h <- (data: JsonLogicValue).computeDigest
        record = (d: StateMachineDefinition) =>
          Records.StateMachineFiberRecord(
            fiberId = fid,
            creationOrdinal = fixture.ordinal,
            previousUpdateOrdinal = fixture.ordinal,
            latestUpdateOrdinal = fixture.ordinal,
            definition = d,
            currentState = StateId("OPEN"),
            stateData = data,
            stateDataHash = h,
            sequenceNumber = FiberOrdinal.MinValue,
            owners = Set.empty,
            status = FiberStatus.Active
          )
        input = FiberInput.Transition("fill", MapValue(Map.empty))
        forbidState = CalculatedState(SortedMap(fid -> record(forbid)), SortedMap.empty)
        permitState = CalculatedState(SortedMap(fid -> record(permit)), SortedMap.empty)
        forbidRes <- FiberEngine.make[IO](forbidState, fixture.ordinal).process(fid, input, List.empty)
        permitRes <- FiberEngine.make[IO](permitState, fixture.ordinal).process(fid, input, List.empty)
      } yield expect(forbidRes match {
        case TransactionResult.Aborted(FailureReason.PolicyViolation("allowedEffects", _), _, _) => true
        case _                                                                                   => false
      }) and
      expect(permitRes match {
        case c: TransactionResult.Committed => c.nullifierConsumptions.get(fid).exists(_.nonEmpty)
        case _                              => false
      })
    }
  }

  // ── (7) sanitization: `_consumeNullifier` never leaks into stateData ────────────────────────────

  test("sanitization: _consumeNullifier is stripped from the merged stateData (StateMerger `_`-key filter)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val fiberId = UUID.fromString("d0c70000-0000-4000-8000-000000000007")
      for {
        fiber <- buildFiber(fiberId, fillJson(s""""$nfB""""), fixture.ordinal)
        genesis = DataState(OnChain.genesis, CalculatedState.genesis.copy(stateMachines = SortedMap(fiberId -> fiber)))
        pr    <- fixture.registry.generateProofs(fill(fiberId), Set(Alice))
        after <- combiner.insert(genesis, Signed(fill(fiberId), pr))
        stateKeys = after.calculated.stateMachines.get(fiberId).map(_.stateData).collect { case MapValue(m) =>
          m.keySet
        }
      } yield expect(rejectionReasons(after).isEmpty) and
      expect(stateKeys.exists(_.contains("filled"))) and
      expect(stateKeys.exists(ks => !ks.exists(_.startsWith("_"))))
    }
  }
}
