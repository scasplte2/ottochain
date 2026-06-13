package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_test.{Participant, TestFixture}

import weaver.SimpleIOSuite

/**
 * Tests for guard evaluation edge cases.
 *
 * Verifies behavior when guards:
 * - Access missing state fields
 * - Have deeply nested expressions
 * - Encounter evaluation errors
 * - Use complex boolean logic
 */
object GuardEdgeCasesSuite extends SimpleIOSuite {

  test("guard accessing missing state field gracefully handles null") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Guard references a field that doesn't exist in state
        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start"), isFinal = false),
            StateId("end")   -> State(StateId("end"), isFinal = false)
          ),
          initialState = StateId("start"),
          transitions = List(
            // Guard checks missing field - should evaluate to false or null
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "check",
              // Access non-existent state.missingField and compare to true
              guard = ApplyExpression(
                EqOp,
                List(VarExpression(Left("state.missingField")), ConstExpression(BoolValue(true)))
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first")))),
              dependencies = Set.empty
            ),
            // Fallback guard that always passes
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "check",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("fallback")))),
              dependencies = Set.empty
            )
          )
        )

        // State has no "missingField"
        initialData = MapValue(Map("existingField" -> IntValue(42)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "check",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          // First guard should fail (missing field evaluates to null != true), fallback should succeed
          val updated = machines.get(fiberId)
          expect(updated.isDefined, "Updated fiber should exist") and
          expect(
            updated.exists(_.currentState == StateId("end")),
            s"Expected state 'end', got ${updated.map(_.currentState)}"
          ) and
          expect(
            updated.exists(_.stateData match {
              case MapValue(m) => m.get("path").contains(StrValue("fallback"))
              case _           => false
            }),
            "Expected 'path' to be 'fallback' (second transition taken)"
          )
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed with fallback transition, but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("guard with deeply nested expression evaluates within limits") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // 10 levels of nested AND/OR
        deepGuard = (1 to 10).foldLeft[JsonLogicExpression](
          ConstExpression(BoolValue(true))
        ) { (acc, i) =>
          if (i % 2 == 0)
            ApplyExpression(AndOp, List(acc, ConstExpression(BoolValue(true))))
          else
            ApplyExpression(OrOp, List(acc, ConstExpression(BoolValue(false))))
        }

        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start"), isFinal = false),
            StateId("end")   -> State(StateId("end"), isFinal = false)
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "process",
              guard = deepGuard,
              effect = ConstExpression(MapValue(Map("processed" -> BoolValue(true)))),
              dependencies = Set.empty
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "process",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, gasUsed, _, _) =>
          // Deeply nested guard: 10 levels of nested conditionals
          // Each level has: if (1) = 2 gas ops, so ~20 gas minimum
          val expectedMinGas = 10L
          expect(
            machines.get(fiberId).exists(_.currentState == StateId("end")),
            s"Expected state 'end', got ${machines.get(fiberId).map(_.currentState)}"
          ) and
          expect(gasUsed >= expectedMinGas, s"Expected at least $expectedMinGas gas, got $gasUsed")
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("guard with division by zero in expression") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Guard that divides by zero
        divByZeroGuard = ApplyExpression(
          EqOp,
          List(
            ApplyExpression(
              DivOp,
              List(ConstExpression(IntValue(100)), ConstExpression(IntValue(0)))
            ),
            ConstExpression(IntValue(0))
          )
        )

        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start"), isFinal = false),
            StateId("end")   -> State(StateId("end"), isFinal = false)
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "divide",
              guard = divByZeroGuard,
              effect = ConstExpression(MapValue(Map("divided" -> BoolValue(true)))),
              dependencies = Set.empty
            ),
            // Fallback
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "divide",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("fallback" -> BoolValue(true)))),
              dependencies = Set.empty
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "divide",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[FailureReason.EvaluationError],
            s"Expected EvaluationError but got: ${reason.getClass.getSimpleName}"
          )
        case _ =>
          failure(s"Division by zero should cause guard evaluation error")
      }
    }
  }

  test("guard evaluation order follows transition list order") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Three transitions - first two guards check specific conditions
        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start"), isFinal = false),
            StateId("end")   -> State(StateId("end"), isFinal = false)
          ),
          initialState = StateId("start"),
          transitions = List(
            // First: requires value > 100
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "process",
              guard = ApplyExpression(
                Gt,
                List(VarExpression(Left("state.value")), ConstExpression(IntValue(100)))
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("high")))),
              dependencies = Set.empty
            ),
            // Second: requires value > 50
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "process",
              guard = ApplyExpression(
                Gt,
                List(VarExpression(Left("state.value")), ConstExpression(IntValue(50)))
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("medium")))),
              dependencies = Set.empty
            ),
            // Third: always passes
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "process",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("low")))),
              dependencies = Set.empty
            )
          )
        )

        // Value is 75 - should match second guard (> 50 but not > 100)
        initialData = MapValue(Map("value" -> IntValue(75)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "process",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          val updated = machines.get(fiberId)
          expect(updated.isDefined) and
          // Second transition should be taken (75 > 50 but 75 not > 100)
          expect(updated.exists(_.stateData match {
            case MapValue(m) => m.get("path").contains(StrValue("medium"))
            case _           => false
          }))
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("guard returning non-boolean value causes EvaluationError") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Guard returns an integer instead of boolean
        nonBooleanGuard = ConstExpression(IntValue(42))

        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start"), isFinal = false),
            StateId("end")   -> State(StateId("end"), isFinal = false)
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = nonBooleanGuard,
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true)))),
              dependencies = Set.empty
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "go",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          // Guard returning non-boolean (IntValue(42)) should error with EvaluationError
          expect(
            reason.isInstanceOf[FailureReason.EvaluationError],
            s"Expected EvaluationError but got: ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with EvaluationError, but transaction was committed")
      }
    }
  }

  test("fiber with non-MapValue stateData causes EvaluationError") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start"), isFinal = false),
            StateId("end")   -> State(StateId("end"), isFinal = false)
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true)))),
              dependencies = Set.empty
            )
          )
        )

        // State data is an ArrayValue instead of MapValue
        initialData: JsonLogicValue = ArrayValue(List(IntValue(1), IntValue(2), IntValue(3)))
        initialHash <- initialData.computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "go",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[FailureReason.EvaluationError],
            s"Expected EvaluationError but got: ${reason.getClass.getSimpleName}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with EvaluationError for non-MapValue state data")
      }
    }
  }
}
