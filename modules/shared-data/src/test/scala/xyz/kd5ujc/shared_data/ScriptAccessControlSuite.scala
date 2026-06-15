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

object ScriptAccessControlSuite extends SimpleIOSuite {

  // A denied/aborted invocation no longer raises out of the combiner — it records a RejectionReceipt and
  // leaves the script unmutated (so one rejected invocation can't abort the whole batch). Negative tests
  // assert a RejectionReceipt was emitted rather than catching an exception.
  private def wasRejected(state: DataState[OnChain, CalculatedState]): Boolean =
    state.onChain.latestLogs.values.flatten.exists {
      case _: FiberLogEntry.RejectionReceipt => true
      case _                                 => false
    }

  test("whitelist allows authorized user to invoke script directly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        scriptSource = """{"result": "success"}"""
        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = scriptFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof <- fixture.registry.generateProofs(invokeScript, Set(Alice))
        finalState  <- combiner.insert(stateAfterScript, Signed(invokeScript, invokeProof))

        script = finalState.calculated.scripts.get(scriptFiberId)

      } yield expect(script.isDefined) and
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(script.flatMap(_.lastInvocation.map(_.invokedBy)).contains(aliceAddress))
    }
  }

  test("whitelist denies unauthorized user from invoking script directly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        scriptSource = """{"result": "success"}"""
        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = scriptFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof <- fixture.registry.generateProofs(invokeScript, Set(Bob))

        rejected <- combiner.insert(stateAfterScript, Signed(invokeScript, invokeProof)).map(wasRejected)

        script = stateAfterScript.calculated.scripts.get(scriptFiberId)

      } yield expect(rejected) and
      expect(script.isDefined) and
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("whitelist allows multiple authorized users") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)
        bobAddress = fixture.registry.addresses(Bob)

        scriptSource = """{"result": "success"}"""
        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress, bobAddress))
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        invokeScript1 = Updates.InvokeScript(
          fiberId = scriptFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof1    <- fixture.registry.generateProofs(invokeScript1, Set(Alice))
        stateAfterAlice <- combiner.insert(stateAfterScript, Signed(invokeScript1, invokeProof1))

        scriptSeq1 = stateAfterAlice.calculated.scripts(scriptFiberId).sequenceNumber
        invokeScript2 = Updates.InvokeScript(
          fiberId = scriptFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = scriptSeq1
        )
        invokeProof2  <- fixture.registry.generateProofs(invokeScript2, Set(Bob))
        stateAfterBob <- combiner.insert(stateAfterAlice, Signed(invokeScript2, invokeProof2))

        scriptSeq2 = stateAfterBob.calculated.scripts(scriptFiberId).sequenceNumber
        invokeScript3 = Updates.InvokeScript(
          fiberId = scriptFiberId,
          method = "test",
          args = MapValue(Map.empty),
          targetSequenceNumber = scriptSeq2
        )
        invokeProof3    <- fixture.registry.generateProofs(invokeScript3, Set(Charlie))
        charlieRejected <- combiner.insert(stateAfterBob, Signed(invokeScript3, invokeProof3)).map(wasRejected)

        script = stateAfterBob.calculated.scripts.get(scriptFiberId)

      } yield expect(script.isDefined) and
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))) and
      expect(charlieRejected)
    }
  }

  test("state machine _scriptCall respects whitelist - owner is whitelisted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        scriptSource =
          """|{"if":[
             |  {"==":[{"var":"method"},"validate"]},
             |  {"result": "validated"},
             |  false
             |]}""".stripMargin

        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        machineJson = s"""
        {
          "states": {
            "idle": { "id": "idle", "isFinal": false },
            "validated": { "id": "validated", "isFinal": false }
          },
          "initialState": "idle",
          "transitions": [
            {
              "from": "idle",
              "to": "validated",
              "eventName": "validate",
              "guard": true,
              "effect": {
                "_scriptCall": {
                  "fiberId": "$scriptFiberId",
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
        stateAfterMachine <- combiner.insert(stateAfterScript, Signed(createMachine, machineProof))

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

        script = finalState.calculated.scripts.get(scriptFiberId)

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("validated"))) and
      expect(script.isDefined) and
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next))
    }
  }

  test("state machine _scriptCall respects whitelist - owner is NOT whitelisted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        scriptSource =
          """|{"if":[
             |  {"==":[{"var":"method"},"validate"]},
             |  {"result": "validated"},
             |  false
             |]}""".stripMargin

        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        machineJson = s"""
        {
          "states": {
            "idle": { "id": "idle", "isFinal": false },
            "validated": { "id": "validated", "isFinal": false }
          },
          "initialState": "idle",
          "transitions": [
            {
              "from": "idle",
              "to": "validated",
              "eventName": "validate",
              "guard": true,
              "effect": {
                "_scriptCall": {
                  "fiberId": "$scriptFiberId",
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
        stateAfterMachine <- combiner.insert(stateAfterScript, Signed(createMachine, machineProof))

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

        script = finalState.calculated.scripts.get(scriptFiberId)

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
      expect(script.isDefined) and
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("trigger directive to script respects whitelist - unauthorized owner blocked") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId  <- UUIDGen.randomUUID[IO]
        machineFiberId <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        // Script with whitelist - only Alice allowed
        scriptSource =
          """|{"if":[
             |  {"==":[{"var":"method"},"process"]},
             |  {"result": "processed"},
             |  false
             |]}""".stripMargin

        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        // State machine that uses _triggers to invoke the script (NOT _scriptCall)
        // Owner is Bob, who is NOT in the whitelist
        machineJson = s"""
        {
          "states": {
            "idle": { "id": "idle", "isFinal": false },
            "triggered": { "id": "triggered", "isFinal": false }
          },
          "initialState": "idle",
          "transitions": [
            {
              "from": "idle",
              "to": "triggered",
              "eventName": "trigger",
              "guard": true,
              "effect": {
                "status": "triggered",
                "_triggers": [
                  {
                    "targetMachineId": "$scriptFiberId",
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
        stateAfterMachine <- combiner.insert(stateAfterScript, Signed(createMachine, machineProof))

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

        script = finalState.calculated.scripts.get(scriptFiberId)

      } yield expect(machine.isDefined) and
      // The SM's transition should have been aborted due to script access denial
      expect(machine.map(_.currentState).contains(StateId("idle"))) and
      expect(
        machine.exists(
          _.lastReceipt.exists(r =>
            !r.success && r.errorMessage
              .exists(msg => msg.toLowerCase.contains("access") || msg.toLowerCase.contains("denied"))
          )
        )
      ) and
      expect(script.isDefined) and
      // Script should NOT have been invoked
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("FiberOwned access control denies access when owner fiber does not exist") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        scriptFiberId <- IO.randomUUID
        ownerFiberId  <- IO.randomUUID // Non-existent fiber ID

        scriptSource =
          """|{
             |  "if": [
             |    { "==": [{ "var": "method" }, "process"] },
             |    "processed",
             |    "unknown"
             |  ]
             |}""".stripMargin

        scriptProg <- IO.fromEither(parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        // Create script with FiberOwned access control (pointing to non-existent fiber)
        createScript = Updates.CreateScript(
          fiberId = scriptFiberId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.FiberOwned(ownerFiberId)
        )

        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterScript <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        // Attempt to invoke the script (should fail - owner fiber doesn't exist)
        invokeScript = Updates.InvokeScript(
          fiberId = scriptFiberId,
          method = "process",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeScript, Set(Alice))
        // The denied invocation no longer raises — it records a RejectionReceipt; assert on its reason.
        invokeState <- combiner.insert(stateAfterScript, Signed(invokeScript, invokeProof))
        reasons = invokeState.onChain.latestLogs.values.flatten
          .collect { case r: FiberLogEntry.RejectionReceipt =>
            r.reason.toLowerCase
          }
          .mkString(" | ")

      } yield expect(
        reasons.nonEmpty,
        "Expected FiberOwned access control to deny access (a RejectionReceipt) when owner fiber doesn't exist"
      ) and expect(
        reasons.contains("access denied") || reasons.contains("not authorized"),
        s"Expected access denied reason, got: $reasons"
      ) and expect(
        reasons.contains("owner fiber") && reasons.contains("not found"),
        s"Expected 'owner fiber not found' in reason, got: $reasons"
      )
    }
  }
}
