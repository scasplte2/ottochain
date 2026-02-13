package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Debug tests for context variable access.
 */
object ContextVariablesSuite extends SimpleIOSuite {

  // Simple machine that just checks $ordinal > 0 (should always pass)
  private val ordinalCheckMachine = """
  {
    "states": {
      "START": { "id": { "value": "START" }, "isFinal": false, "metadata": null },
      "DONE": { "id": { "value": "DONE" }, "isFinal": true, "metadata": null }
    },
    "initialState": { "value": "START" },
    "transitions": [
      {
        "from": { "value": "START" },
        "to": { "value": "DONE" },
        "eventName": "finish",
        "guard": { ">=": [{ "var": "$ordinal" }, 0] },
        "effect": { 
          "merge": [
            { "var": "state" },
            { "ordinalSeen": { "var": "$ordinal" } }
          ]
        },
        "dependencies": []
      }
    ],
    "metadata": { "name": "OrdinalCheck" }
  }
  """

  // Machine that copies $ordinal, $lastSnapshotHash, $epochProgress to state
  private val contextCaptureMachine = """
  {
    "states": {
      "START": { "id": { "value": "START" }, "isFinal": false, "metadata": null },
      "CAPTURED": { "id": { "value": "CAPTURED" }, "isFinal": true, "metadata": null }
    },
    "initialState": { "value": "START" },
    "transitions": [
      {
        "from": { "value": "START" },
        "to": { "value": "CAPTURED" },
        "eventName": "capture",
        "guard": { "==": [1, 1] },
        "effect": { 
          "merge": [
            { "var": "state" },
            { 
              "capturedOrdinal": { "var": "$ordinal" },
              "capturedHash": { "var": "$lastSnapshotHash" },
              "capturedEpoch": { "var": "$epochProgress" }
            }
          ]
        },
        "dependencies": []
      }
    ],
    "metadata": { "name": "ContextCapture" }
  }
  """

  // Machine that requires ordinal >= state.minOrdinal
  private val ordinalGuardMachine = """
  {
    "states": {
      "LOCKED": { "id": { "value": "LOCKED" }, "isFinal": false, "metadata": null },
      "UNLOCKED": { "id": { "value": "UNLOCKED" }, "isFinal": true, "metadata": null }
    },
    "initialState": { "value": "LOCKED" },
    "transitions": [
      {
        "from": { "value": "LOCKED" },
        "to": { "value": "UNLOCKED" },
        "eventName": "unlock",
        "guard": { ">=": [{ "var": "$ordinal" }, { "var": "state.minOrdinal" }] },
        "effect": { "var": "state" },
        "dependencies": []
      }
    ],
    "metadata": { "name": "OrdinalGuard" }
  }
  """

  test("$ordinal is accessible in guard (always passes when ordinal > 0)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](ordinalCheckMachine))

        createOp = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        finishOp = Updates.TransitionStateMachine(
          fiberId,
          "finish",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        finishProof <- fixture.registry.generateProofs(finishOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(finishOp, finishProof))

        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.map(_.currentState).contains(StateId("DONE")))
    }
  }

  test("context variables are captured in effect") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](contextCaptureMachine))

        createOp = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        captureOp = Updates.TransitionStateMachine(
          fiberId,
          "capture",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        captureProof <- fixture.registry.generateProofs(captureOp, Set(Alice))
        state2       <- combiner.insert(state1, Signed(captureOp, captureProof))

        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        stateData = finalMachine.map(_.stateData).collect { case MapValue(m) => m }
        ordinalCaptured = stateData.flatMap(_.get("capturedOrdinal"))
        hashCaptured = stateData.flatMap(_.get("capturedHash"))
        epochCaptured = stateData.flatMap(_.get("capturedEpoch"))

      } yield expect(ordinalCaptured.isDefined, s"ordinal should be captured") and
      expect(hashCaptured.isDefined, s"hash should be captured") and
      expect(epochCaptured.isDefined, s"epoch should be captured")
    }
  }

  test("ordinal guard blocks when minOrdinal is in the future") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](ordinalGuardMachine))

        // Set minOrdinal to far future
        currentOrdinal = fixture.ordinal.value.value
        futureOrdinal = currentOrdinal + 1000000

        initialData = MapValue(Map("minOrdinal" -> IntValue(futureOrdinal)))

        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        unlockOp = Updates.TransitionStateMachine(
          fiberId,
          "unlock",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        unlockProof <- fixture.registry.generateProofs(unlockOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(unlockOp, unlockProof))

        // Guard should block transition - state stays LOCKED
        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(
        finalMachine.map(_.currentState).contains(StateId("LOCKED")),
        s"should stay LOCKED when ordinal < minOrdinal"
      )
    }
  }

  test("ordinal guard passes when minOrdinal is in the past") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](ordinalGuardMachine))

        // Set minOrdinal to 0 (always in the past)
        initialData = MapValue(Map("minOrdinal" -> IntValue(0)))

        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        unlockOp = Updates.TransitionStateMachine(
          fiberId,
          "unlock",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        unlockProof <- fixture.registry.generateProofs(unlockOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(unlockOp, unlockProof))

        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.map(_.currentState).contains(StateId("UNLOCKED")))
    }
  }
}
