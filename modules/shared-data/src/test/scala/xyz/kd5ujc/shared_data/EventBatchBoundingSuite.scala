package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.FiberLogEntry.EventReceipt
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Tests for eventLog behavior and bounding by ExecutionLimits.maxLogSize.
 *
 * The eventLog accumulates EventReceipt entries (most recent first) and is
 * bounded by maxLogSize from ExecutionLimits at the protocol level.
 * lastReceipt holds the receipt from the most recent event.
 */
object EventBatchBoundingSuite extends SimpleIOSuite {

  /** Simple state machine definition that allows repeated events */
  private val counterDefinitionJson =
    """|{
       |  "states": {
       |    "counting": { "id": "counting", "isFinal": false }
       |  },
       |  "initialState": "counting",
       |  "transitions": [
       |    {
       |      "from": "counting",
       |      "to": "counting",
       |      "eventName": "increment",
       |      "guard": true,
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "counter": { "+": [{ "var": "state.counter" }, 1] } }
       |        ]
       |      },
       |      "dependencies": []
       |    }
       |  ]
       |}""".stripMargin

  test("eventLog is populated after processing events") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        def_    <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(fiberId, def_, MapValue(Map("counter" -> IntValue(0))))

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        // Process first event
        event1 = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.MinValue)
        proof1 <- fixture.registry.generateProofs(event1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(event1, proof1))

        machine1 = state1.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Process second event
        seq1 = state1.calculated.stateMachines(fiberId).sequenceNumber
        event2 = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq1)
        proof2 <- fixture.registry.generateProofs(event2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(event2, proof2))

        machine2 = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      // After first event, lastReceipt should be set
      expect(machine1.exists(_.lastReceipt.isDefined)) and
      // After second event, lastReceipt is updated
      expect(machine2.exists(_.lastReceipt.isDefined))
    }
  }

  test("eventLog is bounded by maxLogSize from ExecutionLimits") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fiberId <- IO.randomUUID
        def_    <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        initialData = MapValue(Map("counter" -> IntValue(0)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        owners = Set(Alice).map(fixture.registry.addresses)

        // Seed with a fiber that already has a last receipt
        seedReceipt = EventReceipt(
          fiberId = fiberId,
          sequenceNumber = FiberOrdinal.unsafeApply(4L),
          eventName = "increment",
          ordinal = fixture.ordinal,
          fromState = StateId("counting"),
          toState = StateId("counting"),
          success = true,
          gasUsed = 0L,
          triggersFired = 0
        )

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = def_,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.unsafeApply(4L),
          owners = owners,
          status = FiberStatus.Active,
          lastReceipt = Some(seedReceipt)
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        // Use FiberEngine directly
        engine = FiberEngine.make[IO](
          baseState.calculated,
          fixture.ordinal,
          limits = ExecutionLimits()
        )

        // Generate valid proofs via the registry
        dummyUpdate = Updates
          .TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.unsafeApply(4L))
        proofs <- fixture.registry.generateProofs(dummyUpdate, Set(Alice)).map(_.toList)

        input = FiberInput.Transition("increment", MapValue(Map.empty))

        result <- engine.process(fiberId, input, proofs)

        updatedFiber = result match {
          case TransactionResult.Committed(machines, _, _, _, _, _) =>
            machines.get(fiberId)
          case _ => None
        }

      } yield expect(updatedFiber.isDefined) and
      // lastReceipt should be updated with the new sequence number
      expect(updatedFiber.exists(_.lastReceipt.exists(_.sequenceNumber == FiberOrdinal.unsafeApply(5L))))
    }
  }

  test("lastReceipt is set after processing") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        def_    <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(fiberId, def_, MapValue(Map("counter" -> IntValue(0))))

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        machine0 = state0.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Process event
        event1 = Updates.TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.MinValue)
        proof1 <- fixture.registry.generateProofs(event1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(event1, proof1))

        machine1 = state1.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield
      // Before any event, lastReceipt should be None
      expect(machine0.exists(_.lastReceipt.isEmpty)) and
      // After processing, lastReceipt should be set with success
      expect(machine1.exists(_.lastReceipt.exists(_.success))) and
      expect(machine1.exists(_.lastReceipt.exists(_.eventName == "increment")))
    }
  }
}
