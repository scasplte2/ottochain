package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * E2E tests verifying that the Sequenced ordering ensures correct processing
 * when multiple updates arrive in a single batch (block).
 *
 * These tests demonstrate that the canonical ordering defined in OttochainMessage
 * enables correct batch processing when applied (as ML0Service.combine does).
 */
object SequencedOrderingE2ESuite extends SimpleIOSuite {

  // Counter state machine that increments a counter value
  private def counterStateMachine: StateMachineDefinition = {
    val active = StateId("active")

    StateMachineDefinition(
      states = Map(active -> State(active, isFinal = false)),
      initialState = active,
      transitions = List(
        Transition(
          from = active,
          to = active,
          eventName = "increment",
          guard = ConstExpression(BoolValue(true)),
          effect = ArrayExpression(
            List(
              ArrayExpression(
                List(
                  ConstExpression(StrValue("counter")),
                  ApplyExpression(
                    AddOp,
                    List(
                      VarExpression(Left("state.counter")),
                      ConstExpression(IntValue(1))
                    )
                  )
                )
              )
            )
          ),
          dependencies = Set.empty
        )
      )
    )
  }

  private val initialData: JsonLogicValue = MapValue(Map("counter" -> IntValue(0)))

  // Helper to extract counter value from fiber record
  private def extractCounter(fiber: Option[xyz.kd5ujc.schema.Records.StateMachineFiberRecord]): Option[BigInt] =
    fiber.flatMap { f =>
      f.stateData match {
        case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
        case _           => None
      }
    }

  test("E2E: canonical ordering sorts creates before transitions") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      val fiberId = UUID.randomUUID()

      val createMsg: OttochainMessage = CreateStateMachine(fiberId, counterStateMachine, initialData)
      val transitionMsg: OttochainMessage =
        TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), FiberOrdinal.MinValue.next)

      // Submit in wrong order: transition first, then create
      val wrongOrder: List[OttochainMessage] = List(transitionMsg, createMsg)

      // Apply canonical sorting
      val sorted = wrongOrder.sorted(OttochainMessage.ordering)

      // Verify create comes first after sorting
      IO.pure(
        expect(sorted.head == createMsg) and
        expect(sorted(1) == transitionMsg)
      )
    }
  }

  test("E2E: canonical ordering sorts transitions by sequence number") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      val fiberId = UUID.randomUUID()
      val seq1 = FiberOrdinal.MinValue.next
      val seq2 = seq1.next
      val seq3 = seq2.next

      val t1: OttochainMessage = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq1)
      val t2: OttochainMessage = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq2)
      val t3: OttochainMessage = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq3)

      // Submit in scrambled order: 3, 1, 2
      val scrambled: List[OttochainMessage] = List(t3, t1, t2)

      // Apply canonical sorting
      val sorted = scrambled.sorted(OttochainMessage.ordering)

      // Verify sorted in sequence order: 1, 2, 3
      IO.pure(
        expect(sorted == List(t1, t2, t3))
      )
    }
  }

  test("E2E: sequential processing with correct order succeeds") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val _sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId = UUID.randomUUID()
        definition = counterStateMachine

        // Create the fiber
        createMsg = CreateStateMachine(fiberId, definition, initialData)
        createProof <- fixture.registry.generateProofs(createMsg, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMsg, createProof)
        )

        // Create transitions targeting the correct sequence numbers
        // Fiber starts at sequence 0, so:
        // - First transition targets 0, fiber moves to 1
        // - Second transition targets 1, fiber moves to 2
        // - Third transition targets 2, fiber moves to 3
        seq0 = FiberOrdinal.MinValue // 0
        seq1 = seq0.next // 1
        seq2 = seq1.next // 2

        t1 = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq0)
        t2 = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq1)
        t3 = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq2)

        proof1 <- fixture.registry.generateProofs(t1, Set(Alice))
        proof2 <- fixture.registry.generateProofs(t2, Set(Alice))
        proof3 <- fixture.registry.generateProofs(t3, Set(Alice))

        // Process in correct order (as ML0Service would after sorting)
        state1 <- combiner.insert(state0, Signed(t1, proof1))
        state2 <- combiner.insert(state1, Signed(t2, proof2))
        state3 <- combiner.insert(state2, Signed(t3, proof3))

        // Verify the fiber's counter is 3
        fiber = state3.calculated.stateMachines.get(fiberId)
        counterValue = extractCounter(fiber)

        // After 3 transitions starting from 0, fiber should be at sequence 3
        expectedFinalSeq = seq2.next // 3
      } yield expect(counterValue.contains(BigInt(3))) and
      expect(fiber.map(_.sequenceNumber).contains(expectedFinalSeq))
    }
  }

  test("E2E: signedOrdering is consistent with message ordering") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val _sp: SecurityProvider[IO] = fixture.securityProvider

      val fiberId = UUID.randomUUID()
      val seq1 = FiberOrdinal.MinValue.next
      val seq2 = seq1.next

      val create: OttochainMessage = CreateStateMachine(fiberId, counterStateMachine, initialData)
      val t1: OttochainMessage = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq1)
      val t2: OttochainMessage = TransitionStateMachine(fiberId, "increment", MapValue(Map.empty), seq2)

      for {
        createProof <- fixture.registry.generateProofs(create.asInstanceOf[CreateStateMachine], Set(Alice))
        t1Proof     <- fixture.registry.generateProofs(t1.asInstanceOf[TransitionStateMachine], Set(Alice))
        t2Proof     <- fixture.registry.generateProofs(t2.asInstanceOf[TransitionStateMachine], Set(Alice))

        signedCreate = Signed(create, createProof)
        signedT1 = Signed(t1, t1Proof)
        signedT2 = Signed(t2, t2Proof)

        // Scrambled order
        scrambled = List(signedT2, signedCreate, signedT1)

        // Sort using signed ordering
        sorted = scrambled.sorted(OttochainMessage.signedOrdering)

        // Verify order matches what we expect: create, t1, t2
      } yield expect(sorted.map(_.value) == List(create, t1, t2))
    }
  }
}
