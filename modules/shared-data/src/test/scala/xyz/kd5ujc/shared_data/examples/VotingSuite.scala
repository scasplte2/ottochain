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
 * Voting State Machine — Unit Tests
 *
 * A simple voting contract demonstrating:
 * - Multi-party coordination (voters)
 * - State transitions: pending → voting → completed
 */
object VotingSuite extends SimpleIOSuite {

  test("Happy path: start then end") {
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
            "voting": { "id": { "value": "voting" }, "isFinal": false, "metadata": null },
            "completed": { "id": { "value": "completed" }, "isFinal": true, "metadata": null }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "voting" },
              "eventName": "startVoting",
              "guard": { "==": [1, 1] },
              "effect": { "merge": [{ "var": "state" }, { "candidates": { "var": "event.candidates" } }] },
              "dependencies": []
            },
            {
              "from": { "value": "voting" },
              "to": { "value": "completed" },
              "eventName": "endVoting",
              "guard": { "==": [1, 1] },
              "effect": { "var": "state" },
              "dependencies": []
            }
          ],
          "metadata": { "name": "Voting" }
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("candidates" -> ArrayValue(List.empty)))

        // Create the state machine
        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Start the voting
        startVotingOp = Updates.TransitionStateMachine(
          fiberId,
          "startVoting",
          MapValue(Map("candidates" -> ArrayValue(List(StrValue("Alice"), StrValue("Bob"))))),
          FiberOrdinal.MinValue
        )
        startVotingProof <- fixture.registry.generateProofs(startVotingOp, Set(Alice))
        state2           <- combiner.insert(state1, Signed(startVotingOp, startVotingProof))

        // End the voting
        endVotingOp = Updates.TransitionStateMachine(
          fiberId,
          "endVoting",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(1L)
        )
        endVotingProof <- fixture.registry.generateProofs(endVotingOp, Set(Alice))
        state3         <- combiner.insert(state2, Signed(endVotingOp, endVotingProof))

        // Verify final state
        finalMachine = state3.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("completed")))
    }
  }

}
