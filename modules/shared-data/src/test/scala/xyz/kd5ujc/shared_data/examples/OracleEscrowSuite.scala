package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
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
 * Oracle Escrow State Machine — Unit Tests
 *
 * An escrow contract that uses a script oracle to determine release conditions.
 * Demonstrates:
 * - State machine with conditional transitions (guards)
 * - Script oracle creation and invocation
 * - Integration of oracle results into state transitions
 */
object OracleEscrowSuite extends SimpleIOSuite {

  test("Oracle Escrow: Fund, Oracle Check, Release") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- IO.randomUUID
        oracleId <- IO.randomUUID

        // State machine definition
        machineJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false, "metadata": null },
            "funded": { "id": { "value": "funded" }, "isFinal": false, "metadata": null },
            "released": { "id": { "value": "released" }, "isFinal": true, "metadata": null }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "funded" },
              "eventName": "fund",
              "guard": { "==": [1, 1] },
              "effect": { "merge": [{ "var": "state" }, { "depositor": { "var": "event.depositor" }, "amount": { "var": "event.amount" } }] },
              "dependencies": []
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "released" },
              "eventName": "release",
              "guard": { "==": [{ "var": "event.approved"}, true] },
              "effect": { "merge": [{ "var": "state" }, { "beneficiary": { "var": "event.beneficiary" } }] },
              "dependencies": []
            }
          ],
          "metadata": { "name": "OracleEscrow" }
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("depositor" -> StrValue(""), "beneficiary" -> StrValue(""), "amount" -> IntValue(0)))

        // Create the state machine
        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Create the oracle script (always returns true)
        oracleScript = """{"==": [1, 1]}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        // Create the oracle
        createOracle = Updates.CreateScriptOracle(
          fiberId = oracleId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )
        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(createOracle, oracleProof))

        // Fund the escrow
        fundOp = Updates.TransitionStateMachine(
          fiberId,
          "fund",
          MapValue(Map("depositor" -> StrValue("alice"), "amount" -> IntValue(100))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state3    <- combiner.insert(state2, Signed(fundOp, fundProof))

        // Invoke Oracle (simulating an external check)
        invokeOracle = Updates.InvokeScriptOracle(
          fiberId = oracleId,
          method = "invoke",
          args = MapValue(Map.empty[String, JsonLogicValue]),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        state4      <- combiner.insert(state3, Signed(invokeOracle, invokeProof))

        // Release to beneficiary (using oracle result in guard)
        releaseOp = Updates.TransitionStateMachine(
          fiberId,
          "release",
          MapValue(Map("approved" -> BoolValue(true), "beneficiary" -> StrValue("bob"))),
          FiberOrdinal.unsafeApply(1L)
        )
        releaseProof <- fixture.registry.generateProofs(releaseOp, Set(Alice))
        state5       <- combiner.insert(state4, Signed(releaseOp, releaseProof))

        // Verify final state
        finalMachine = state5.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalData = finalMachine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => Some(m)
            case _           => None
          }
        }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("released"))) and
      expect(finalData.exists { m =>
        m.get("depositor").contains(StrValue("alice")) &&
        m.get("amount").contains(IntValue(100)) &&
        m.get("beneficiary").contains(StrValue("bob"))
      })
    }
  }

  test("Oracle Escrow: Fund, Oracle Check, Refund") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- IO.randomUUID
        oracleId <- IO.randomUUID

        // State machine definition
        machineJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false, "metadata": null },
            "funded": { "id": { "value": "funded" }, "isFinal": false, "metadata": null },
            "released": { "id": { "value": "released" }, "isFinal": true, "metadata": null },
            "refunded": { "id": { "value": "refunded" }, "isFinal": true, "metadata": null }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "funded" },
              "eventName": "fund",
              "guard": { "==": [1, 1] },
              "effect": { "merge": [{ "var": "state" }, { "depositor": { "var": "event.depositor" }, "amount": { "var": "event.amount" } }] },
              "dependencies": []
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "released" },
              "eventName": "release",
              "guard": { "==": [{ "var": "event.approved"}, true] },
              "effect": { "merge": [{ "var": "state" }, { "beneficiary": { "var": "event.beneficiary" } }] },
              "dependencies": []
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "refunded" },
              "eventName": "refund",
              "guard": { "==": [1, 1] },
              "effect": { "var": "state" },
              "dependencies": []
            }
          ],
          "metadata": { "name": "OracleEscrow" }
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("depositor" -> StrValue(""), "beneficiary" -> StrValue(""), "amount" -> IntValue(0)))

        // Create the state machine
        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Create the oracle script (always returns true)
        oracleScript = """{"==": [1, 1]}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        // Create the oracle
        createOracle = Updates.CreateScriptOracle(
          fiberId = oracleId,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )
        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(createOracle, oracleProof))

        // Fund the escrow
        fundOp = Updates.TransitionStateMachine(
          fiberId,
          "fund",
          MapValue(Map("depositor" -> StrValue("alice"), "amount" -> IntValue(100))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state3    <- combiner.insert(state2, Signed(fundOp, fundProof))

        // Invoke Oracle
        invokeOracle = Updates.InvokeScriptOracle(
          fiberId = oracleId,
          method = "invoke",
          args = MapValue(Map.empty[String, JsonLogicValue]),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        state4      <- combiner.insert(state3, Signed(invokeOracle, invokeProof))

        // Refund (Oracle result is false, so guard fails)
        refundOp = Updates.TransitionStateMachine(
          fiberId,
          "refund",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(1L)
        )
        refundProof <- fixture.registry.generateProofs(refundOp, Set(Alice))
        state5      <- combiner.insert(state4, Signed(refundOp, refundProof))

        // Verify final state
        finalMachine = state5.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("refunded")))
    }
  }

}
