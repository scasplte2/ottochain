package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
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

object OracleStateMachineIntegrationSuite extends SimpleIOSuite {

  test("state machine effect invokes oracle during transition") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validateAmount"]},
             |  {">=":[{"var":"args.amount"},100]},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "PENDING": { "id": { "value": "PENDING" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "PENDING" },
          "transitions": [
            {
              "from": { "value": "PENDING" },
              "to": { "value": "validated" },
              "eventName": "submit",
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "fiberId": "$oracleFiberId",
                  "method": "validateAmount",
                  "args": {
                    "amount": { "var": "event.amount" }
                  }
                },
                "submittedAmount": { "var": "event.amount" },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("PENDING")))

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        submitEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "submit",
          MapValue(Map("amount" -> IntValue(150))),
          FiberOrdinal.MinValue
        )
        submitProof <- registry.generateProofs(submitEvent, Set(Bob))
        finalState  <- combiner.insert(stateAfterMachine, Signed(submitEvent, submitProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scripts.get(oracleFiberId)

        machineStatus = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        submittedAmount = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("submittedAmount").collect { case IntValue(a) => a }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("validated"))) and
      expect(machineStatus.contains("validated")) and
      expect(submittedAmount.contains(BigInt(150))) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(oracle.flatMap(_.lastInvocation.map(_.method)).contains("validateAmount")) and
      expect(oracle.flatMap(_.lastInvocation.map(_.result)).exists {
        case BoolValue(true) => true
        case _               => false
      })
    }
  }

  test("state machine effect invokes oracle and oracle call fails - transition should fail") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validateAmount"]},
             |  {">=":[{"var":"args.amount"},100]},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "PENDING": { "id": { "value": "PENDING" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "PENDING" },
          "transitions": [
            {
              "from": { "value": "PENDING" },
              "to": { "value": "validated" },
              "eventName": "submit",
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "fiberId": "$oracleFiberId",
                  "method": "validateAmount",
                  "args": {
                    "amount": { "var": "event.amount" }
                  }
                },
                "submittedAmount": { "var": "event.amount" },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("PENDING")))

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        submitEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "submit",
          MapValue(Map("amount" -> IntValue(50))),
          FiberOrdinal.MinValue
        )
        submitProof <- registry.generateProofs(submitEvent, Set(Bob))
        finalState  <- combiner.insert(stateAfterMachine, Signed(submitEvent, submitProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scripts.get(oracleFiberId)

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("PENDING"))) and
      expect(machine.exists(_.lastReceipt.exists(r => !r.success))) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and
      expect(oracle.map(_.lastInvocation.isEmpty).contains(true))
    }
  }

  test("state machine guard reads oracle state before transition") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        oracleScript = """{"counter": 5}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))
        initialOracleState = MapValue(Map("counter" -> IntValue(3)))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = Some(initialOracleState),
          accessControl = AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "locked": { "id": { "value": "locked" }, "isFinal": false },
            "unlocked": { "id": { "value": "unlocked" }, "isFinal": false }
          },
          "initialState": { "value": "locked" },
          "transitions": [
            {
              "from": { "value": "locked" },
              "to": { "value": "unlocked" },
              "eventName": "unlock",
              "guard": {
                ">=": [
                  { "var": "scripts.$oracleFiberId.state.counter" },
                  5
                ]
              },
              "effect": {
                "status": "unlocked"
              },
              "dependencies": ["$oracleFiberId"]
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("locked")))

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        unlockEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "unlock",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        unlockProof           <- registry.generateProofs(unlockEvent, Set(Bob))
        stateAfterFirstUnlock <- combiner.insert(stateAfterMachine, Signed(unlockEvent, unlockProof))

        machineAfterFirstUnlock = stateAfterFirstUnlock.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        invokeOracle = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof      <- registry.generateProofs(invokeOracle, Set(Alice))
        stateAfterInvoke <- combiner.insert(stateAfterFirstUnlock, Signed(invokeOracle, invokeProof))

        secondUnlockEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "unlock",
          MapValue(Map.empty),
          stateAfterInvoke.calculated.stateMachines(machineFiberId).sequenceNumber
        )
        secondUnlockProof <- registry.generateProofs(secondUnlockEvent, Set(Bob))
        finalState        <- combiner.insert(stateAfterInvoke, Signed(secondUnlockEvent, secondUnlockProof))

        machineAfterSecondUnlock = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(machineAfterFirstUnlock.isDefined) and
      expect(machineAfterFirstUnlock.map(_.currentState).contains(StateId("locked"))) and
      expect(machineAfterFirstUnlock.exists(_.lastReceipt.exists(r => !r.success))) and
      expect(machineAfterSecondUnlock.isDefined) and
      expect(machineAfterSecondUnlock.map(_.currentState).contains(StateId("unlocked")))
    }
  }

  test("state machine uses oracle invocation result in subsequent state") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"calculateFee"]},
             |  {"*":[{"var":"args.amount"},0.05]},
             |  0
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "processing": { "id": { "value": "processing" }, "isFinal": false },
            "COMPLETED": { "id": { "value": "COMPLETED" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "processing" },
              "eventName": "initiate",
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "fiberId": "$oracleFiberId",
                  "method": "calculateFee",
                  "args": {
                    "amount": { "var": "event.amount" }
                  }
                },
                "amount": { "var": "event.amount" }
              },
              "dependencies": ["$oracleFiberId"]
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "COMPLETED" },
              "eventName": "finalize",
              "guard": true,
              "effect": [
                ["totalAmount", { "+": [
                  { "var": "state.amount" },
                  { "var": "scripts.$oracleFiberId.lastInvocation.result" }
                ]}],
                ["feeCalculated", { "var": "scripts.$oracleFiberId.lastInvocation.result" }],
                ["status", "COMPLETED"]
              ],
              "dependencies": ["$oracleFiberId"]
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        initiateEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "initiate",
          MapValue(Map("amount" -> IntValue(1000))),
          FiberOrdinal.MinValue
        )
        initiateProof      <- registry.generateProofs(initiateEvent, Set(Bob))
        stateAfterInitiate <- combiner.insert(stateAfterMachine, Signed(initiateEvent, initiateProof))

        finalizeEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "finalize",
          MapValue(Map.empty),
          stateAfterInitiate.calculated.stateMachines(machineFiberId).sequenceNumber
        )
        finalizeProof <- registry.generateProofs(finalizeEvent, Set(Bob))
        finalState    <- combiner.insert(stateAfterInitiate, Signed(finalizeEvent, finalizeProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        totalAmount = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) =>
              m.get("totalAmount").collect {
                case IntValue(a)   => a
                case FloatValue(a) => BigInt(a.toLong)
              }
            case _ => None
          }
        }

        feeCalculated = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) =>
              m.get("feeCalculated").collect {
                case IntValue(f)   => f
                case FloatValue(f) => BigInt(f.toLong)
              }
            case _ => None
          }
        }

        status = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("COMPLETED"))) and
      expect(feeCalculated.contains(BigInt(50))) and
      expect(totalAmount.contains(BigInt(1050))) and
      expect(status.contains("COMPLETED"))
    }
  }

  test("multiple state machines invoking same oracle maintains invocation count") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId   <- UUIDGen.randomUUID[IO]
        machine1fiberId <- UUIDGen.randomUUID[IO]
        machine2fiberId <- UUIDGen.randomUUID[IO]

        oracleScript = """{"result": "validated"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "validated" },
              "eventName": "validate",
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "fiberId": "$oracleFiberId",
                  "method": "check",
                  "args": {}
                },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        createMachine1 = Updates.CreateStateMachine(machine1fiberId, machineDef, initialData)
        machine1Proof      <- registry.generateProofs(createMachine1, Set(Bob))
        stateAfterMachine1 <- combiner.insert(stateAfterOracle, Signed(createMachine1, machine1Proof))

        createMachine2 = Updates.CreateStateMachine(machine2fiberId, machineDef, initialData)
        machine2Proof      <- registry.generateProofs(createMachine2, Set(Charlie))
        stateAfterMachine2 <- combiner.insert(stateAfterMachine1, Signed(createMachine2, machine2Proof))

        validateEvent1 = Updates.TransitionStateMachine(
          machine1fiberId,
          "validate",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        validate1Proof      <- registry.generateProofs(validateEvent1, Set(Bob))
        stateAfterValidate1 <- combiner.insert(stateAfterMachine2, Signed(validateEvent1, validate1Proof))

        validateEvent2 = Updates.TransitionStateMachine(
          machine2fiberId,
          "validate",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        validate2Proof <- registry.generateProofs(validateEvent2, Set(Charlie))
        finalState     <- combiner.insert(stateAfterValidate1, Signed(validateEvent2, validate2Proof))

        oracle = finalState.calculated.scripts.get(oracleFiberId)
        machine1 = finalState.calculated.stateMachines.get(machine1fiberId)
        machine2 = finalState.calculated.stateMachines.get(machine2fiberId)

      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))) and
      expect(oracle.map(_.lastInvocation.isDefined).contains(true)) and
      expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      expect(
        machine1
          .collect { case r: Records.StateMachineFiberRecord => r.currentState }
          .contains(StateId("validated"))
      ) and
      expect(
        machine2
          .collect { case r: Records.StateMachineFiberRecord => r.currentState }
          .contains(StateId("validated"))
      )
    }
  }
}
