package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
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

object OracleAccessControlSuite extends SimpleIOSuite {

  test("whitelist allows authorized user to invoke oracle directly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        finalState  <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof))

        oracle = finalState.calculated.scripts.get(oracleFiberId)

      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(oracle.flatMap(_.lastInvocation.map(_.invokedBy)).contains(aliceAddress))
    }
  }

  test("whitelist denies unauthorized user from invoking oracle directly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Bob))

        result <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof)).attempt

        oracle = stateAfterOracle.calculated.scripts.get(oracleFiberId)

      } yield expect(result.isLeft) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("whitelist allows multiple authorized users") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)
        bobAddress = fixture.registry.addresses(Bob)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress, bobAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle1 = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof1    <- fixture.registry.generateProofs(invokeOracle1, Set(Alice))
        stateAfterAlice <- combiner.insert(stateAfterOracle, Signed(invokeOracle1, invokeProof1))

        oracleSeq1 = stateAfterAlice.calculated.scripts(oracleFiberId).sequenceNumber
        invokeOracle2 = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = oracleSeq1
        )
        invokeProof2  <- fixture.registry.generateProofs(invokeOracle2, Set(Bob))
        stateAfterBob <- combiner.insert(stateAfterAlice, Signed(invokeOracle2, invokeProof2))

        oracleSeq2 = stateAfterBob.calculated.scripts(oracleFiberId).sequenceNumber
        invokeOracle3 = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = oracleSeq2
        )
        invokeProof3  <- fixture.registry.generateProofs(invokeOracle3, Set(Charlie))
        charlieResult <- combiner.insert(stateAfterBob, Signed(invokeOracle3, invokeProof3)).attempt

        oracle = stateAfterBob.calculated.scripts.get(oracleFiberId)

      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))) and
      expect(charlieResult.isLeft)
    }
  }

  test("state machine _oracleCall respects whitelist - owner is whitelisted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validate"]},
             |  {"result": "validated"},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
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
                  "method": "validate",
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

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        validateEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "validate",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        validateProof <- fixture.registry.generateProofs(validateEvent, Set(Alice))
        finalState    <- combiner.insert(stateAfterMachine, Signed(validateEvent, validateProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scripts.get(oracleFiberId)

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("validated"))) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next))
    }
  }

  test("state machine _oracleCall respects whitelist - owner is NOT whitelisted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validate"]},
             |  {"result": "validated"},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
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
                  "method": "validate",
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

        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- fixture.registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        validateEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "validate",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        validateProof <- fixture.registry.generateProofs(validateEvent, Set(Bob))
        finalState    <- combiner.insert(stateAfterMachine, Signed(validateEvent, validateProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scripts.get(oracleFiberId)

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("idle"))) and
      expect(
        machine.exists(
          _.lastReceipt.exists(r =>
            !r.success && r.errorMessage
              .exists(msg => msg.contains("Access denied") || msg.contains("not in whitelist"))
          )
        )
      ) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("trigger directive to oracle respects whitelist - unauthorized owner blocked") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        // Oracle with whitelist - only Alice allowed
        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"process"]},
             |  {"result": "processed"},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        // State machine that uses _triggers to invoke the oracle (NOT _oracleCall)
        // Owner is Bob, who is NOT in the whitelist
        machineJson = s"""
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
              "eventName": "trigger",
              "guard": true,
              "effect": {
                "status": "triggered",
                "_triggers": [
                  {
                    "targetMachineId": "$oracleFiberId",
                    "eventName": "process",
                    "payload": {}
                  }
                ]
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        // Create state machine owned by Bob (not in whitelist)
        createMachine = Updates.CreateStateMachine(machineFiberId, machineDef, initialData)
        machineProof      <- fixture.registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        triggerEvent = Updates.TransitionStateMachine(
          machineFiberId,
          "trigger",
          MapValue(Map.empty),
          FiberOrdinal.MinValue
        )
        triggerProof <- fixture.registry.generateProofs(triggerEvent, Set(Bob))
        finalState   <- combiner.insert(stateAfterMachine, Signed(triggerEvent, triggerProof))

        machine = finalState.calculated.stateMachines
          .get(machineFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scripts.get(oracleFiberId)

      } yield expect(machine.isDefined) and
      // The SM's transition should have been aborted due to oracle access denial
      expect(machine.map(_.currentState).contains(StateId("idle"))) and
      expect(
        machine.exists(
          _.lastReceipt.exists(r =>
            !r.success && r.errorMessage
              .exists(msg => msg.toLowerCase.contains("access") || msg.toLowerCase.contains("denied"))
          )
        )
      ) and
      expect(oracle.isDefined) and
      // Oracle should NOT have been invoked
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("FiberOwned access control denies access when owner fiber does not exist") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        oracleFiberId <- IO.randomUUID
        ownerFiberId  <- IO.randomUUID // Non-existent fiber ID

        oracleScript =
          """|{
             |  "if": [
             |    { "==": [{ "var": "method" }, "process"] },
             |    "processed",
             |    "unknown"
             |  ]
             |}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        // Create oracle with FiberOwned access control (pointing to non-existent fiber)
        createOracle = Updates.CreateScript(
          fiberId = oracleFiberId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.FiberOwned(ownerFiberId)
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        // Attempt to invoke the oracle (should fail - owner fiber doesn't exist)
        invokeOracle = Updates.InvokeScript(
          fiberId = oracleFiberId,
          method = "process",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof  <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        invokeResult <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof)).attempt

      } yield invokeResult match {
        case Left(err: RuntimeException) =>
          // FiberOwned access control denies access when owner fiber doesn't exist
          expect(
            err.getMessage.toLowerCase.contains("access denied") ||
            err.getMessage.toLowerCase.contains("not authorized"),
            s"Expected access denied message, got: ${err.getMessage}"
          ) and expect(
            err.getMessage.toLowerCase.contains("owner fiber") &&
            err.getMessage.toLowerCase.contains("not found"),
            s"Expected 'owner fiber not found' in message, got: ${err.getMessage}"
          )
        case Left(other) =>
          failure(
            s"Expected RuntimeException for access denied, got: ${other.getClass.getSimpleName}: ${other.getMessage}"
          )
        case Right(_) =>
          failure("Expected FiberOwned access control to deny access when owner fiber doesn't exist")
      }
    }
  }
}
