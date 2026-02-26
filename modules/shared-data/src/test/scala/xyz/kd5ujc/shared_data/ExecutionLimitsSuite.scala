package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object ExecutionLimitsSuite extends SimpleIOSuite {

  test("depth exceeded: nested triggers hit maxDepth") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machine1fiberId <- UUIDGen.randomUUID[IO]
        machine2fiberId <- UUIDGen.randomUUID[IO]

        // Machine 1: triggers machine 2 on "ping"
        machine1Json = s"""
        {
          "states": {
            "idle": { "id": "idle", "isFinal": false },
            "pinged": { "id": "pinged", "isFinal": false }
          },
          "initialState": "idle",
          "transitions": [
            {
              "from": "idle",
              "to": "pinged",
              "eventName": "ping",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine2fiberId",
                    "eventName": "pong",
                    "payload": { "var": "state" }
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        // Machine 2: triggers machine 1 on "pong"
        machine2Json = s"""
        {
          "states": {
            "idle": { "id": "idle", "isFinal": false },
            "ponged": { "id": "ponged", "isFinal": false }
          },
          "initialState": "idle",
          "transitions": [
            {
              "from": "idle",
              "to": "ponged",
              "eventName": "pong",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine1fiberId",
                    "eventName": "ping",
                    "payload": { "var": "state" }
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": "ponged",
              "to": "idle",
              "eventName": "pong",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine1fiberId",
                    "eventName": "ping",
                    "payload": { "var": "state" }
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        machine1Def <- IO.fromEither(decode[StateMachineDefinition](machine1Json))
        machine2Def <- IO.fromEither(decode[StateMachineDefinition](machine2Json))

        initialData = MapValue(Map("count" -> IntValue(0)))

        createMachine1 = Updates.CreateStateMachine(machine1fiberId, machine1Def, initialData)
        machine1Proof <- fixture.registry.generateProofs(createMachine1, Set(Alice))
        stateAfterMachine1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine1, machine1Proof)
        )

        createMachine2 = Updates.CreateStateMachine(machine2fiberId, machine2Def, initialData)
        machine2Proof      <- fixture.registry.generateProofs(createMachine2, Set(Alice))
        stateAfterMachine2 <- combiner.insert(stateAfterMachine1, Signed(createMachine2, machine2Proof))

        // Send initial ping - should hit depth limit due to ping-pong loop
        pingEvent = Updates.TransitionStateMachine(
          machine1fiberId,
          "ping",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        pingProof  <- fixture.registry.generateProofs(pingEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterMachine2, Signed(pingEvent, pingProof))

        machine1 = finalState.calculated.stateMachines
          .get(machine1fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machine2 = finalState.calculated.stateMachines
          .get(machine2fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Atomic rollback: transaction should have aborted, no state changes
        machine1Count = machine1.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        machine2Count = machine2.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

      } yield expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      expect(machine1Count.contains(BigInt(0))) and
      expect(machine2Count.contains(BigInt(0))) and
      expect(machine1.map(_.currentState).contains(StateId("idle"))) and
      expect(machine2.map(_.currentState).contains(StateId("idle"))) and
      expect(machine1.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }

  test("gas exhausted: expensive computation hits maxGas") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineFiberId <- UUIDGen.randomUUID[IO]

        // Machine with many self-triggering transitions to exceed gas limit
        // Each transition costs ~35 gas (10 guard + 20 effect + 5 trigger)
        // With 30+ transitions, we'll exceed maxGas=1000
        machineJson = s"""
        {
          "states": {
            "s0": { "id": "s0", "isFinal": false },
            "s1": { "id": "s1", "isFinal": false },
            "s2": { "id": "s2", "isFinal": false },
            "s3": { "id": "s3", "isFinal": false },
            "s4": { "id": "s4", "isFinal": false },
            "s5": { "id": "s5", "isFinal": false },
            "s6": { "id": "s6", "isFinal": false },
            "s7": { "id": "s7", "isFinal": false },
            "s8": { "id": "s8", "isFinal": false },
            "s9": { "id": "s9", "isFinal": false },
            "s10": { "id": "s10", "isFinal": false },
            "s11": { "id": "s11", "isFinal": false },
            "s12": { "id": "s12", "isFinal": false },
            "s13": { "id": "s13", "isFinal": false },
            "s14": { "id": "s14", "isFinal": false },
            "s15": { "id": "s15", "isFinal": false },
            "s16": { "id": "s16", "isFinal": false },
            "s17": { "id": "s17", "isFinal": false },
            "s18": { "id": "s18", "isFinal": false },
            "s19": { "id": "s19", "isFinal": false },
            "s20": { "id": "s20", "isFinal": false },
            "s21": { "id": "s21", "isFinal": false },
            "s22": { "id": "s22", "isFinal": false },
            "s23": { "id": "s23", "isFinal": false },
            "s24": { "id": "s24", "isFinal": false },
            "s25": { "id": "s25", "isFinal": false },
            "s26": { "id": "s26", "isFinal": false },
            "s27": { "id": "s27", "isFinal": false },
            "s28": { "id": "s28", "isFinal": false },
            "s29": { "id": "s29", "isFinal": false },
            "s30": { "id": "s30", "isFinal": true }
          },
          "initialState": "s0",
          "transitions": [
            { "from": "s0", "to": "s1", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 1 }, "dependencies": [] },
            { "from": "s1", "to": "s2", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 2 }, "dependencies": [] },
            { "from": "s2", "to": "s3", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 3 }, "dependencies": [] },
            { "from": "s3", "to": "s4", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 4 }, "dependencies": [] },
            { "from": "s4", "to": "s5", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 5 }, "dependencies": [] },
            { "from": "s5", "to": "s6", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 6 }, "dependencies": [] },
            { "from": "s6", "to": "s7", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 7 }, "dependencies": [] },
            { "from": "s7", "to": "s8", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 8 }, "dependencies": [] },
            { "from": "s8", "to": "s9", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 9 }, "dependencies": [] },
            { "from": "s9", "to": "s10", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 10 }, "dependencies": [] },
            { "from": "s10", "to": "s11", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 11 }, "dependencies": [] },
            { "from": "s11", "to": "s12", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 12 }, "dependencies": [] },
            { "from": "s12", "to": "s13", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 13 }, "dependencies": [] },
            { "from": "s13", "to": "s14", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 14 }, "dependencies": [] },
            { "from": "s14", "to": "s15", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 15 }, "dependencies": [] },
            { "from": "s15", "to": "s16", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 16 }, "dependencies": [] },
            { "from": "s16", "to": "s17", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 17 }, "dependencies": [] },
            { "from": "s17", "to": "s18", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 18 }, "dependencies": [] },
            { "from": "s18", "to": "s19", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 19 }, "dependencies": [] },
            { "from": "s19", "to": "s20", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 20 }, "dependencies": [] },
            { "from": "s20", "to": "s21", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 21 }, "dependencies": [] },
            { "from": "s21", "to": "s22", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 22 }, "dependencies": [] },
            { "from": "s22", "to": "s23", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 23 }, "dependencies": [] },
            { "from": "s23", "to": "s24", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 24 }, "dependencies": [] },
            { "from": "s24", "to": "s25", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 25 }, "dependencies": [] },
            { "from": "s25", "to": "s26", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 26 }, "dependencies": [] },
            { "from": "s26", "to": "s27", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 27 }, "dependencies": [] },
            { "from": "s27", "to": "s28", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 28 }, "dependencies": [] },
            { "from": "s28", "to": "s29", "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineFiberId", "eventName": "advance", "payload": {} }], "step": 29 }, "dependencies": [] },
            { "from": "s29", "to": "s30", "eventName": "advance", "guard": true, "effect": { "step": 30 }, "dependencies": [] }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("step" -> IntValue(0)))

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send advance event - should trigger chain but stop when gas exhausted
        advanceEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "advance",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        advanceProof <- fixture.registry.generateProofs(advanceEvent, Set(Alice))
        finalState   <- combiner.insert(stateAfterCreate, Signed(advanceEvent, advanceProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        step = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      // Atomic rollback - transaction aborted due to gas exhaustion
      expect(step.contains(BigInt(0))) and // No state changes
      expect(machine.map(_.currentState).contains(StateId("s0"))) and // Original state
      expect(machine.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and // Sequence not incremented
      expect(machine.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }

  test("cycle detection: same event processed twice on same fiber") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineFiberId <- UUIDGen.randomUUID[IO]

        // Machine that triggers itself with the same event (should be detected as cycle)
        machineJson = s"""
        {
          "states": {
            "idle": { "id": "idle", "isFinal": false },
            "processing": { "id": "processing", "isFinal": false }
          },
          "initialState": "idle",
          "transitions": [
            {
              "from": "idle",
              "to": "processing",
              "eventName": "start",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineFiberId",
                    "eventName": "start",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": "processing",
              "to": "processing",
              "eventName": "start",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineFiberId",
                    "eventName": "start",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("count" -> IntValue(0)))

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send start event - should detect cycle when trigger tries to send "start" again
        startEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "start",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        startProof <- fixture.registry.generateProofs(startEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterCreate, Signed(startEvent, startProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        count = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      // Atomic rollback - cycle detected, transaction aborted
      expect(count.contains(BigInt(0))) and // No state changes
      expect(machine.map(_.currentState).contains(StateId("idle"))) and // Original state
      expect(machine.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and // Sequence not incremented
      expect(machine.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }
}
