package xyz.kd5ujc.shared_data

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

object TriggerEventsSuite extends SimpleIOSuite {

  test("basic trigger: effect triggers event on another machine") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        initiatorfiberId <- UUIDGen.randomUUID[IO]
        targetfiberId    <- UUIDGen.randomUUID[IO]

        // Initiator: sends trigger to target on "start"
        initiatorJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "triggered": { "id": { "value": "triggered" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "triggered" },
              "eventName": "start",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$targetfiberId",
                    "eventName": "activate",
                    "payload": {
                      "initiator": ["var", "machineId"],
                      "timestamp": 12345
                    }
                  }
                ],
                "status": "triggered"
              },
              "dependencies": []
            }
          ]
        }
        """

        // Target: receives "activate" event
        targetJson = """
        {
          "states": {
            "inactive": { "id": { "value": "inactive" }, "isFinal": false },
            "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false }
          },
          "initialState": { "value": "inactive" },
          "transitions": [
            {
              "from": { "value": "inactive" },
              "to": { "value": "ACTIVE" },
              "eventName": "activate",
              "guard": true,
              "effect": [
                ["status", "ACTIVE"],
                ["activatedBy", { "var": "event.initiator" }],
                ["activatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }
        """

        initiatorDef <- IO.fromEither(decode[StateMachineDefinition](initiatorJson))
        targetDef    <- IO.fromEither(decode[StateMachineDefinition](targetJson))

        initiatorData = MapValue(Map("status" -> StrValue("idle")))
        targetData = MapValue(Map("status" -> StrValue("inactive")))

        createInitiator = Updates.CreateStateMachine(initiatorfiberId, initiatorDef, initiatorData)
        initiatorProof <- fixture.registry.generateProofs(createInitiator, Set(Alice))
        stateAfterInitiator <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createInitiator, initiatorProof)
        )

        createTarget = Updates.CreateStateMachine(targetfiberId, targetDef, targetData)
        targetProof      <- fixture.registry.generateProofs(createTarget, Set(Bob))
        stateAfterTarget <- combiner.insert(stateAfterInitiator, Signed(createTarget, targetProof))

        // Send start event to initiator - should trigger target
        startEvent = Updates
          .TransitionStateMachine(initiatorfiberId, "start", MapValue(Map.empty), FiberOrdinal.MinValue)
        startProof <- fixture.registry.generateProofs(startEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterTarget, Signed(startEvent, startProof))

        initiator = finalState.calculated.stateMachines
          .get(initiatorfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        target = finalState.calculated.stateMachines
          .get(targetfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        targetStatus = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        targetActivatedBy = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("activatedBy").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        targetActivatedAt = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("activatedAt").collect { case IntValue(t) => t }
            case _           => None
          }
        }

      } yield expect(initiator.isDefined) and
      expect(initiator.map(_.currentState).contains(StateId("triggered"))) and
      expect(target.isDefined) and
      expect(target.map(_.currentState).contains(StateId("ACTIVE"))) and
      expect(targetStatus.contains("ACTIVE")) and
      expect(targetActivatedBy.contains(initiatorfiberId.toString)) and
      expect(targetActivatedAt.contains(BigInt(12345)))
    }
  }

  test("cascading triggers: A triggers B triggers C") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineFiberId  <- UUIDGen.randomUUID[IO]
        machine2fiberId <- UUIDGen.randomUUID[IO]
        machine3fiberId <- UUIDGen.randomUUID[IO]

        // Machine A: triggers B
        machineAJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "done" },
              "eventName": "start",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine2fiberId",
                    "eventName": "continue",
                    "payload": {
                      "step": 1
                    }
                  }
                ],
                "step": 1
              },
              "dependencies": []
            }
          ]
        }
        """

        // Machine B: triggers C
        machineBJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "done" },
              "eventName": "continue",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine3fiberId",
                    "eventName": "finish",
                    "payload": {
                      "step": 2
                    }
                  }
                ],
                "step": 2
              },
              "dependencies": []
            }
          ]
        }
        """

        // Machine C: final machine
        machineCJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": true }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "done" },
              "eventName": "finish",
              "guard": true,
              "effect": {
                "step": 3
              },
              "dependencies": []
            }
          ]
        }
        """

        machineADef <- IO.fromEither(decode[StateMachineDefinition](machineAJson))
        machineBDef <- IO.fromEither(decode[StateMachineDefinition](machineBJson))
        machineCDef <- IO.fromEither(decode[StateMachineDefinition](machineCJson))

        initialData = MapValue(Map("step" -> IntValue(0)))

        createA = Updates.CreateStateMachine(machineFiberId, machineADef, initialData)
        aProof <- fixture.registry.generateProofs(createA, Set(Alice))
        stateAfterA <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createA, aProof)
        )

        createB = Updates.CreateStateMachine(machine2fiberId, machineBDef, initialData)
        bProof      <- fixture.registry.generateProofs(createB, Set(Alice))
        stateAfterB <- combiner.insert(stateAfterA, Signed(createB, bProof))

        createC = Updates.CreateStateMachine(machine3fiberId, machineCDef, initialData)
        cProof      <- fixture.registry.generateProofs(createC, Set(Alice))
        stateAfterC <- combiner.insert(stateAfterB, Signed(createC, cProof))

        // Send start to A - should cascade to B then C
        startEvent = Updates.TransitionStateMachine(machineFiberId, "start", MapValue(Map.empty), FiberOrdinal.MinValue)
        startProof <- fixture.registry.generateProofs(startEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterC, Signed(startEvent, startProof))

        machineA = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machineB = finalState.calculated.stateMachines
          .get(machine2fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machineC = finalState.calculated.stateMachines
          .get(machine3fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        stepA = machineA.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

        stepB = machineB.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

        stepC = machineC.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

      } yield expect(machineA.isDefined) and
      expect(machineA.map(_.currentState).contains(StateId("done"))) and
      expect(stepA.contains(BigInt(1))) and
      expect(machineB.isDefined) and
      expect(machineB.map(_.currentState).contains(StateId("done"))) and
      expect(stepB.contains(BigInt(2))) and
      expect(machineC.isDefined) and
      expect(machineC.map(_.currentState).contains(StateId("done"))) and
      expect(stepC.contains(BigInt(3)))
    }
  }

  test("trigger with payload: payload expression evaluated with context") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        sourcefiberId <- UUIDGen.randomUUID[IO]
        targetfiberId <- UUIDGen.randomUUID[IO]

        // Source: sends computed payload to target
        sourceJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "sent": { "id": { "value": "sent" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "sent" },
              "eventName": "send",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$targetfiberId",
                    "eventName": "receive",
                    "payload": {
                      "amount": ["+", ["var", "state.balance"], ["var", "event.amount"]],
                      "sender": ["var", "machineId"],
                      "message": ["var", "event.message"]
                    }
                  }
                ],
                "balance": ["-", ["var", "state.balance"], ["var", "event.amount"]]
              },
              "dependencies": []
            }
          ]
        }
        """

        // Target: receives payload
        targetJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "received": { "id": { "value": "received" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "received" },
              "eventName": "receive",
              "guard": true,
              "effect": [
                ["amount", { "var": "event.amount" }],
                ["sender", { "var": "event.sender" }],
                ["message", { "var": "event.message" }]
              ],
              "dependencies": []
            }
          ]
        }
        """

        sourceDef <- IO.fromEither(decode[StateMachineDefinition](sourceJson))
        targetDef <- IO.fromEither(decode[StateMachineDefinition](targetJson))

        sourceData = MapValue(Map("balance" -> IntValue(1000)))
        targetData = MapValue(Map.empty[String, JsonLogicValue])

        createSource = Updates.CreateStateMachine(sourcefiberId, sourceDef, sourceData)
        sourceProof <- fixture.registry.generateProofs(createSource, Set(Alice))
        stateAfterSource <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createSource, sourceProof)
        )

        createTarget = Updates.CreateStateMachine(targetfiberId, targetDef, targetData)
        targetProof      <- fixture.registry.generateProofs(createTarget, Set(Alice))
        stateAfterTarget <- combiner.insert(stateAfterSource, Signed(createTarget, targetProof))

        // Send event with payload - should compute and pass to target
        sendEvent = Updates.TransitionStateMachine(
          sourcefiberId,
          "send",
          MapValue(
            Map(
              "amount"  -> IntValue(250),
              "message" -> StrValue("Hello from source")
            )
          ),
          FiberOrdinal.MinValue
        )
        sendProof  <- fixture.registry.generateProofs(sendEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterTarget, Signed(sendEvent, sendProof))

        source = finalState.calculated.stateMachines
          .get(sourcefiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        target = finalState.calculated.stateMachines
          .get(targetfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        sourceBalance = source.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("balance").collect { case IntValue(b) => b }
            case _           => None
          }
        }

        targetAmount = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("amount").collect { case IntValue(a) => a }
            case _           => None
          }
        }

        targetSender = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("sender").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        targetMessage = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("message").collect { case StrValue(m) => m }
            case _           => None
          }
        }

      } yield expect(source.isDefined) and
      expect(source.map(_.currentState).contains(StateId("sent"))) and
      expect(sourceBalance.contains(BigInt(750))) and // 1000 - 250
      expect(target.isDefined) and
      expect(target.map(_.currentState).contains(StateId("received"))) and
      expect(targetAmount.contains(BigInt(1250))) and // 1000 + 250 (computed in payload)
      expect(targetSender.contains(sourcefiberId.toString)) and
      expect(targetMessage.contains("Hello from source"))
    }
  }

  test("failed trigger: target machine not found aborts transaction") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        sourcefiberId      <- UUIDGen.randomUUID[IO]
        nonExistentfiberId <- UUIDGen.randomUUID[IO]

        // Source: triggers non-existent machine - transaction should fail
        sourceJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "attempted": { "id": { "value": "attempted" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "attempted" },
              "eventName": "try_trigger",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$nonExistentfiberId",
                    "eventName": "activate",
                    "payload": {}
                  }
                ],
                "status": "attempted"
              },
              "dependencies": []
            }
          ]
        }
        """

        sourceDef <- IO.fromEither(decode[StateMachineDefinition](sourceJson))
        sourceData = MapValue(Map("status" -> StrValue("idle")))

        createSource = Updates.CreateStateMachine(sourcefiberId, sourceDef, sourceData)
        sourceProof <- fixture.registry.generateProofs(createSource, Set(Alice))
        stateAfterSource <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createSource, sourceProof)
        )

        // Send event - should fail because trigger target doesn't exist
        triggerEvent = Updates
          .TransitionStateMachine(sourcefiberId, "try_trigger", MapValue(Map.empty), FiberOrdinal.MinValue)
        triggerProof <- fixture.registry.generateProofs(triggerEvent, Set(Alice))
        finalState   <- combiner.insert(stateAfterSource, Signed(triggerEvent, triggerProof))

        // Source should remain in idle state (transaction aborted)
        source = finalState.calculated.stateMachines
          .get(sourcefiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Event should be recorded with failed receipt containing trigger target error
        isTriggerTargetNotFound = source.exists(_.lastReceipt.exists { r =>
          !r.success &&
          r.errorMessage
            .exists(msg => msg.contains("Trigger target fiber") && msg.contains(nonExistentfiberId.toString))
        })

      } yield expect(source.isDefined) and
      expect(source.map(_.currentState).contains(StateId("idle"))) and // Stayed in idle
      expect(isTriggerTargetNotFound)
    }
  }

  test("trigger cycle detection: A triggers B triggers A fails") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineAfiberId <- UUIDGen.randomUUID[IO]
        machineBfiberId <- UUIDGen.randomUUID[IO]

        // Machine A: triggers B with "ping"
        machineAJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "pinged": { "id": { "value": "pinged" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "pinged" },
              "eventName": "ping",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineBfiberId",
                    "eventName": "pong",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": { "value": "pinged" },
              "to": { "value": "idle" },
              "eventName": "ping",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineBfiberId",
                    "eventName": "pong",
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

        // Machine B: triggers A with "ping" (creates cycle)
        machineBJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "ponged": { "id": { "value": "ponged" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "ponged" },
              "eventName": "pong",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineAfiberId",
                    "eventName": "ping",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": { "value": "ponged" },
              "to": { "value": "idle" },
              "eventName": "pong",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineAfiberId",
                    "eventName": "ping",
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

        machineADef <- IO.fromEither(decode[StateMachineDefinition](machineAJson))
        machineBDef <- IO.fromEither(decode[StateMachineDefinition](machineBJson))

        initialData = MapValue(Map("count" -> IntValue(0)))

        createA = Updates.CreateStateMachine(machineAfiberId, machineADef, initialData)
        aProof <- fixture.registry.generateProofs(createA, Set(Alice))
        stateAfterA <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createA, aProof)
        )

        createB = Updates.CreateStateMachine(machineBfiberId, machineBDef, initialData)
        bProof      <- fixture.registry.generateProofs(createB, Set(Alice))
        stateAfterB <- combiner.insert(stateAfterA, Signed(createB, bProof))

        // Send ping to A - should trigger B which tries to trigger A again (cycle)
        pingEvent = Updates.TransitionStateMachine(machineAfiberId, "ping", MapValue(Map.empty), FiberOrdinal.MinValue)
        pingProof  <- fixture.registry.generateProofs(pingEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterB, Signed(pingEvent, pingProof))

        machineA = finalState.calculated.stateMachines
          .get(machineAfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machineB = finalState.calculated.stateMachines
          .get(machineBfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        countA = machineA.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        countB = machineB.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

      } yield expect(machineA.isDefined) and
      expect(machineB.isDefined) and
      // Atomic rollback - cycle detected, transaction aborted
      expect(countA.contains(BigInt(0))) and // No state changes
      expect(countB.contains(BigInt(0))) and
      expect(machineA.map(_.currentState).contains(StateId("idle"))) and // Original states
      expect(machineB.map(_.currentState).contains(StateId("idle"))) and
      // Parent fiber should have failed receipt
      expect(machineA.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }
}
