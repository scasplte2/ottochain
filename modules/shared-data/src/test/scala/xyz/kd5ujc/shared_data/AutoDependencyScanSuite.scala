package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.evaluation.StaticDependencyScan
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * F6 (03-cross-fiber-and-authorization.md §2, option a): a guard/effect that statically reads
 * `{"var":"machines.<uuid>.…"}` AUTO-DECLARES `<uuid>` as a runtime read dependency, so the read resolves
 * (non-null) WITHOUT the author hand-adding it to `Transition.dependencies`. The augmentation is RUNTIME-only
 * — it never mutates the signed, hash-pinned `dependencies` field (rule #1).
 */
object AutoDependencyScanSuite extends SimpleIOSuite {

  // ── pure scanner ───────────────────────────────────────────────────────────────────────────────────

  pureTest("staticMachineRefs collects literal machines.<uuid> ids from guard AND effect") {
    val bId = UUID.randomUUID()
    val cId = UUID.randomUUID()
    val t = Transition(
      from = StateId("s0"),
      to = StateId("s1"),
      eventName = "go",
      guard = VarExpression(Left(s"machines.$bId.state.flag")), // guard-side ref
      effect = MapExpression(Map("copied" -> VarExpression(Left(s"machines.$cId.state.x")))), // effect-side ref
      dependencies = Set.empty // neither declared
    )
    expect(StaticDependencyScan.staticMachineRefs(t) == Set(bId, cId))
  }

  pureTest("staticMachineRefs ignores non-machines paths and computed (non-literal) targets") {
    val t = Transition(
      from = StateId("s0"),
      to = StateId("s1"),
      eventName = "go",
      guard = ConstExpression(BoolValue(true)),
      effect = MapExpression(
        Map(
          "self"     -> VarExpression(Left("state.value")),
          "computed" -> VarExpression(Left("event.target")) // a computed id is NOT statically resolvable
        )
      ),
      dependencies = Set.empty
    )
    expect(StaticDependencyScan.staticMachineRefs(t).isEmpty)
  }

  // ── end-to-end: the read resolves through the engine without a declared dependency ───────────────────

  private val targetDefJson: String =
    """{ "states": { "b0": { "id": "b0", "isFinal": false } }, "initialState": "b0", "transitions": [] }"""

  test("an UNDECLARED machines.<uuid> read resolves (non-null) via the auto-declared dependency") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]
        targetId <- UUIDGen.randomUUID[IO]
        readerId <- UUIDGen.randomUUID[IO]

        // Target fiber B holds state { x: 42 }.
        targetDef <- IO.fromEither(decode[StateMachineDefinition](targetDefJson))
        createB = Updates.CreateStateMachine(targetId, targetDef, MapValue(Map("x" -> IntValue(42))))
        createBProof <- fixture.registry.generateProofs(createB, Set(Alice))
        afterB <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createB, createBProof))

        // Reader fiber A reads machines.<B>.state.x but declares NO dependency (dependencies: []).
        readerDefJson =
          s"""
          {
            "states": { "a0": { "id": "a0", "isFinal": false }, "a1": { "id": "a1", "isFinal": false } },
            "initialState": "a0",
            "transitions": [
              { "from": "a0", "to": "a1", "eventName": "seed", "guard": true,
                "effect": { "copied": { "var": "machines.$targetId.state.x" } }, "dependencies": [] }
            ]
          }
          """
        readerDef <- IO.fromEither(decode[StateMachineDefinition](readerDefJson))
        createA = Updates.CreateStateMachine(readerId, readerDef, MapValue(Map.empty[String, JsonLogicValue]))
        createAProof <- fixture.registry.generateProofs(createA, Set(Alice))
        afterA       <- combiner.insert(afterB, Signed(createA, createAProof))

        seed = Updates.TransitionStateMachine(
          readerId,
          "seed",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        seedProof <- fixture.registry.generateProofs(seed, Set(Alice))
        afterSeed <- combiner.insert(afterA, Signed(seed, seedProof))

        reader = afterSeed.calculated.stateMachines
          .get(readerId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        copied = reader.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("copied")
            case _           => None
          }
        }
      } yield
      // the auto-declared dep projected B, so the read returned 42 (NOT null)
      expect(reader.map(_.currentState).contains(StateId("a1"))) and
      expect(copied.contains(IntValue(BigInt(42))))
    }
  }
}
