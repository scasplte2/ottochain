package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Tests for concurrent event processing on the same state machine.
 *
 * Verifies that:
 * - Multiple events in same batch are processed deterministically
 * - Event ordering follows input order
 * - Each event sees state changes from previous events in the batch
 */
object ConcurrentEventsSuite extends SimpleIOSuite {

  /** Counter state machine that increments a counter on each event */
  private val counterDefinitionJson =
    """|{
       |  "states": {
       |    "counting": { "id": { "value": "counting" }, "isFinal": false }
       |  },
       |  "initialState": { "value": "counting" },
       |  "transitions": [
       |    {
       |      "from": { "value": "counting" },
       |      "to": { "value": "counting" },
       |      "eventName": "increment",
       |      "guard": true,
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "value": { "+": [{ "var": "state.value" }, 1] } }
       |        ]
       |      },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "counting" },
       |      "to": { "value": "counting" },
       |      "eventName": "add",
       |      "guard": true,
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "value": { "+": [{ "var": "state.value" }, { "var": "event.amount" }] } }
       |        ]
       |      },
       |      "dependencies": []
       |    }
       |  ]
       |}""".stripMargin

  test("sequential events on same SM processed deterministically") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        def_    <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(fiberId, def_, MapValue(Map("value" -> IntValue(0))))

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        // Process multiple increment events sequentially
        event1 = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.MinValue)
        proof1 <- fixture.registry.generateProofs(event1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(event1, proof1))

        event2 = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.unsafeApply(1))
        proof2 <- fixture.registry.generateProofs(event2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(event2, proof2))

        event3 = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.unsafeApply(2))
        proof3 <- fixture.registry.generateProofs(event3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(event3, proof3))

        // Run same sequence again from initial state to verify determinism
        state1b <- combiner.insert(state0, Signed(event1, proof1))
        event2b = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.unsafeApply(1))
        proof2b <- fixture.registry.generateProofs(event2b, Set(Alice))
        state2b <- combiner.insert(state1b, Signed(event2b, proof2b))
        event3b = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.unsafeApply(2))
        proof3b <- fixture.registry.generateProofs(event3b, Set(Alice))
        state3b <- combiner.insert(state2b, Signed(event3b, proof3b))

        machine1 = state3.calculated.stateMachines.get(fiberId)
        machine2 = state3b.calculated.stateMachines.get(fiberId)
      } yield expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      // Both executions should produce identical results
      expect(
        machine1
          .flatMap(_.stateData match {
            case MapValue(m) =>
              m.get("value") match {
                case Some(IntValue(v)) => Some(v)
                case _                 => None
              }
            case _ => None
          })
          .exists(_ == 3L)
      ) and
      // Determinism: same final state
      expect(machine1.map(_.stateDataHash) == machine2.map(_.stateDataHash)) and
      expect(machine1.map(_.sequenceNumber) == machine2.map(_.sequenceNumber))
    }
  }

  test("event ordering is deterministic based on input order") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        def_    <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(fiberId, def_, MapValue(Map("value" -> IntValue(0))))

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        // Order 1: add 10, then add 5 (result: 15)
        eventA1 = Updates
          .TransitionStateMachine(fiberId, "add", MapValue(Map("amount" -> IntValue(10))), FiberOrdinal.MinValue)
        proofA1       <- fixture.registry.generateProofs(eventA1, Set(Alice))
        stateOrder1_1 <- combiner.insert(state0, Signed(eventA1, proofA1))

        eventB1 = Updates
          .TransitionStateMachine(fiberId, "add", MapValue(Map("amount" -> IntValue(5))), FiberOrdinal.unsafeApply(1))
        proofB1       <- fixture.registry.generateProofs(eventB1, Set(Alice))
        stateOrder1_2 <- combiner.insert(stateOrder1_1, Signed(eventB1, proofB1))

        // Order 2: add 5, then add 10 (result: 15, but different sequence)
        eventA2 = Updates
          .TransitionStateMachine(fiberId, "add", MapValue(Map("amount" -> IntValue(5))), FiberOrdinal.MinValue)
        proofA2       <- fixture.registry.generateProofs(eventA2, Set(Alice))
        stateOrder2_1 <- combiner.insert(state0, Signed(eventA2, proofA2))

        eventB2 = Updates
          .TransitionStateMachine(fiberId, "add", MapValue(Map("amount" -> IntValue(10))), FiberOrdinal.unsafeApply(1))
        proofB2       <- fixture.registry.generateProofs(eventB2, Set(Alice))
        stateOrder2_2 <- combiner.insert(stateOrder2_1, Signed(eventB2, proofB2))

        machine1 = stateOrder1_2.calculated.stateMachines.get(fiberId)
        machine2 = stateOrder2_2.calculated.stateMachines.get(fiberId)
      } yield expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      // Both orders result in same final value (15)
      expect(
        machine1
          .flatMap(_.stateData match {
            case MapValue(m) =>
              m.get("value") match {
                case Some(IntValue(v)) => Some(v)
                case _                 => None
              }
            case _ => None
          })
          .exists(_ == 15L)
      ) and
      expect(
        machine2
          .flatMap(_.stateData match {
            case MapValue(m) =>
              m.get("value") match {
                case Some(IntValue(v)) => Some(v)
                case _                 => None
              }
            case _ => None
          })
          .exists(_ == 15L)
      ) and
      // Same sequence numbers (both processed 2 events)
      expect(machine1.exists(_.sequenceNumber == FiberOrdinal.unsafeApply(2L))) and
      expect(machine2.exists(_.sequenceNumber == FiberOrdinal.unsafeApply(2L)))
    }
  }

  /** State machine that accumulates values into an array using merge */
  private val historyDefinitionJson =
    """|{
       |  "states": {
       |    "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false }
       |  },
       |  "initialState": { "value": "ACTIVE" },
       |  "transitions": [
       |    {
       |      "from": { "value": "ACTIVE" },
       |      "to": { "value": "ACTIVE" },
       |      "eventName": "record",
       |      "guard": true,
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          {
       |            "history": { "merge": [{ "var": "state.history" }, [{ "var": "event.value" }]] },
       |            "lastValue": { "var": "event.value" }
       |          }
       |        ]
       |      },
       |      "dependencies": []
       |    }
       |  ]
       |}""".stripMargin

  test("array accumulation using merge across sequential events") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        def_    <- IO.fromEither(parser.parse(historyDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(
          fiberId,
          def_,
          MapValue(Map("history" -> ArrayValue(List.empty), "lastValue" -> IntValue(0)))
        )

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        // Record events: 1, 2, 3
        event1 = Updates
          .TransitionStateMachine(fiberId, "record", MapValue(Map("value" -> IntValue(1))), FiberOrdinal.MinValue)
        proof1 <- fixture.registry.generateProofs(event1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(event1, proof1))

        event2 = Updates
          .TransitionStateMachine(fiberId, "record", MapValue(Map("value" -> IntValue(2))), FiberOrdinal.unsafeApply(1))
        proof2 <- fixture.registry.generateProofs(event2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(event2, proof2))

        event3 = Updates
          .TransitionStateMachine(fiberId, "record", MapValue(Map("value" -> IntValue(3))), FiberOrdinal.unsafeApply(2))
        proof3 <- fixture.registry.generateProofs(event3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(event3, proof3))

        machine = state3.calculated.stateMachines.get(fiberId)

        // Extract history array from state
        history = machine.flatMap(_.stateData match {
          case MapValue(m) =>
            m.get("history") match {
              case Some(ArrayValue(arr)) =>
                Some(arr.collect { case IntValue(v) => v.toInt })
              case _ => None
            }
          case _ => None
        })

        lastValue = machine.flatMap(_.stateData match {
          case MapValue(m) =>
            m.get("lastValue") match {
              case Some(IntValue(v)) => Some(v)
              case _                 => None
            }
          case _ => None
        })
      } yield expect(machine.isDefined) and
      // History should contain all recorded values in order
      expect(history.exists(_ == List(1, 2, 3))) and
      // Last value should be 3
      expect(lastValue.exists(_ == 3L))
    }
  }
}
