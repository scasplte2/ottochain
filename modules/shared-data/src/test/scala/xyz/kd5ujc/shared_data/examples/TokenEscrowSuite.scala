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
 * Token Escrow State Machine — Unit Tests
 *
 * A simple escrow contract demonstrating:
 * - Multi-party coordination (depositor, beneficiary)
 * - State transitions: pending → funded → released/refunded
 */
object TokenEscrowSuite extends SimpleIOSuite {

  test("Happy path: fund then release") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

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
              "guard": { "==": [1, 1] },
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
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "refunded" },
              "eventName": "expire",
              "guard": { "==": [1, 1] },
              "effect": { "var": "state" },
              "dependencies": []
            }
          ],
          "metadata": { "name": "TokenEscrow" }
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

        // Fund the escrow
        fundOp = Updates.TransitionStateMachine(
          fiberId,
          "fund",
          MapValue(Map("depositor" -> StrValue("alice"), "amount" -> IntValue(100))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundOp, fundProof))

        // Release to beneficiary
        releaseOp = Updates.TransitionStateMachine(
          fiberId,
          "release",
          MapValue(Map("beneficiary" -> StrValue("bob"))),
          FiberOrdinal.unsafeApply(1L)
        )
        releaseProof <- fixture.registry.generateProofs(releaseOp, Set(Alice))
        state3       <- combiner.insert(state2, Signed(releaseOp, releaseProof))

        // Verify final state
        finalMachine = state3.calculated.stateMachines
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

  test("Refund path: fund then refund") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Same machine definition
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
              "guard": { "==": [1, 1] },
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
          "metadata": { "name": "TokenEscrow" }
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

        // Fund the escrow
        fundOp = Updates.TransitionStateMachine(
          fiberId,
          "fund",
          MapValue(Map("depositor" -> StrValue("alice"), "amount" -> IntValue(100))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundOp, fundProof))

        // Refund to depositor
        refundOp = Updates.TransitionStateMachine(
          fiberId,
          "refund",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(1L)
        )
        refundProof <- fixture.registry.generateProofs(refundOp, Set(Alice))
        state3      <- combiner.insert(state2, Signed(refundOp, refundProof))

        // Verify final state
        finalMachine = state3.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("refunded")))
    }
  }

  test("Expire path: fund then expire") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Same machine definition
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
              "guard": { "==": [1, 1] },
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
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "refunded" },
              "eventName": "expire",
              "guard": { "==": [1, 1] },
              "effect": { "var": "state" },
              "dependencies": []
            }
          ],
          "metadata": { "name": "TokenEscrow" }
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

        // Fund the escrow
        fundOp = Updates.TransitionStateMachine(
          fiberId,
          "fund",
          MapValue(Map("depositor" -> StrValue("alice"), "amount" -> IntValue(100))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundOp, fundProof))

        // Expire the escrow
        expireOp = Updates.TransitionStateMachine(
          fiberId,
          "expire",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(1L)
        )
        expireProof <- fixture.registry.generateProofs(expireOp, Set(Alice))
        state3      <- combiner.insert(state2, Signed(expireOp, expireProof))

        // Verify final state
        finalMachine = state3.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("refunded")))
    }
  }

}
