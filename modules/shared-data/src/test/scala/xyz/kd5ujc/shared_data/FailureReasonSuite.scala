package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Tests for comprehensive FailureReason coverage.
 *
 * Verifies that all FailureReason ADT variants are properly returned
 * for their corresponding error conditions.
 */
object FailureReasonSuite extends SimpleIOSuite {

  test("NoTransitionFound when event type not in transitionMap") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine with only one event type defined
        definition = StateMachineDefinition(
          states = Map(
            StateId("idle")   -> State(StateId("idle")),
            StateId("ACTIVE") -> State(StateId("ACTIVE"))
          ),
          initialState = StateId("idle"),
          transitions = List(
            Transition(
              from = StateId("idle"),
              to = StateId("ACTIVE"),
              eventName = "activate",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("activated" -> BoolValue(true))))
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
          currentState = StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)

        // Send an event type that doesn't exist in the transitionMap
        input = FiberInput.Transition(
          "unknown_event",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          reason match {
            case FailureReason.NoTransitionFound(state, en) =>
              expect(state == StateId("idle"), s"Expected state 'idle', got $state") and
              expect(
                en == "unknown_event",
                s"Expected event 'unknown_event', got $en"
              )
            case other =>
              failure(s"Expected NoTransitionFound but got: ${other.getClass.getSimpleName}")
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with NoTransitionFound, but transaction was committed")
      }
    }
  }

  test("NoGuardMatched when all guards return false") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // Multiple transitions for same event, all guards return false
        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start")),
            StateId("end")   -> State(StateId("end"))
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ConstExpression(BoolValue(false)), // Always false
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first"))))
            ),
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ConstExpression(BoolValue(false)), // Always false
              effect = ConstExpression(MapValue(Map("path" -> StrValue("second"))))
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
          reason match {
            case FailureReason.NoGuardMatched(_, _, attemptedCount) =>
              expect(attemptedCount == 2, s"Expected 2 guard attempts, got $attemptedCount")
            case other =>
              failure(s"Expected NoGuardMatched but got: ${other.getClass.getSimpleName}")
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with NoGuardMatched, but transaction was committed")
      }
    }
  }

  test("TriggerTargetNotFound includes fiberId in message") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId       <- UUIDGen.randomUUID[IO]
        nonExistentId <- UUIDGen.randomUUID[IO]

        // State machine that triggers a non-existent target
        definition = StateMachineDefinition(
          states = Map(
            StateId("idle")      -> State(StateId("idle")),
            StateId("triggered") -> State(StateId("triggered"))
          ),
          initialState = StateId("idle"),
          transitions = List(
            Transition(
              from = StateId("idle"),
              to = StateId("triggered"),
              eventName = "fire",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "fired" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(nonExistentId.toString),
                            "eventName"       -> StrValue("receive"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
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
          currentState = StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        // Only the source fiber exists, target doesn't
        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "fire",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          reason match {
            case FailureReason.TriggerTargetNotFound(targetId, _) =>
              expect(targetId == nonExistentId, s"Expected target $nonExistentId, got $targetId")
            case other =>
              failure(s"Expected TriggerTargetNotFound but got: ${other.getClass.getSimpleName}")
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with TriggerTargetNotFound, but transaction was committed")
      }
    }
  }

  test("CycleDetected for self-referential trigger") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine triggers itself with same event type
        definition = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a")),
            StateId("b") -> State(StateId("b"))
          ),
          initialState = StateId("a"),
          transitions = List(
            Transition(
              from = StateId("a"),
              to = StateId("b"),
              eventName = "loop",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "looped" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(fiberId.toString),
                            "eventName"       -> StrValue("loop"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            Transition(
              from = StateId("b"),
              to = StateId("a"),
              eventName = "loop",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "back" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(fiberId.toString),
                            "eventName"       -> StrValue("loop"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
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
          currentState = StateId("a"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "loop",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(reason.isInstanceOf[FailureReason.CycleDetected])
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with CycleDetected, got Committed")
      }
    }
  }

  test("GasExhaustedFailure with phase information") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // Expensive guard that will exhaust gas
        expensiveGuard = (1 to 100).foldLeft[JsonLogicExpression](
          ConstExpression(IntValue(0))
        ) { (acc, _) =>
          ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
        }

        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start")),
            StateId("end")   -> State(StateId("end"))
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ApplyExpression(EqOp, List(expensiveGuard, ConstExpression(IntValue(100)))),
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true))))
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

        // Gas limit set to exhaust during guard evaluation (100 additions need ~100+ gas)
        limits = ExecutionLimits(maxDepth = 10, maxGas = 50L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          reason match {
            case FailureReason.GasExhaustedFailure(gasUsed, gasLimit, phase) =>
              // Gas should exhaust during guard evaluation (100 additions with 50L limit)
              expect(gasUsed <= gasLimit, s"Gas used ($gasUsed) should not exceed limit ($gasLimit)") and
              expect(gasLimit == 50L, s"Expected gas limit 50L, got $gasLimit") and
              expect(
                phase == GasExhaustionPhase.Guard,
                s"Expected Guard phase (guard has 100 additions), got $phase"
              )
            case other =>
              failure(
                s"Expected GasExhaustedFailure in Guard phase but got: ${other.getClass.getSimpleName}: ${other.toMessage}"
              )
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with GasExhaustedFailure, but transaction was committed")
      }
    }
  }

  // Note: Payload validation with control characters is now handled at L1 (validateUpdate)
  // not at runtime in FiberOrchestrator. See ValidatorSuite and DeterministicExecutionSuite
  // for tests covering L1 payload validation (CommonRules.payloadStructureValid).
  // This test now verifies that payloads reaching the orchestrator are assumed to be valid.
  test("Valid payload with proper characters succeeds") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start")),
            StateId("end")   -> State(StateId("end"))
          ),
          initialState = StateId("start"),
          transitions = List(
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "process",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true))))
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

        // Valid payload (control character validation happens at L1)
        validPayload = MapValue(
          Map(
            "message" -> StrValue("Hello World")
          )
        )

        input = FiberInput.Transition(
          "process",
          validPayload
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          success
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason}")
      }
    }
  }

  test("type mismatch: MethodCall input with StateMachineFiberRecord") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      val registry = fixture.registry
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        definition = StateMachineDefinition(
          states = Map(
            StateId("idle") -> State(StateId("idle"))
          ),
          initialState = StateId("idle"),
          transitions = List.empty
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        // Create a state machine fiber
        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)

        // Use MethodCall input (oracle-style) with state machine - type mismatch
        input = FiberInput.MethodCall(
          method = "someMethod",
          args = MapValue(Map.empty),
          caller = registry.addresses(Alice)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          reason match {
            case FailureReason.FiberInputMismatch(fid, fiberType, inputType) =>
              expect(fid == fiberId, s"Expected fiber $fiberId, got $fid") and
              expect(
                fiberType == FiberKind.StateMachine,
                s"Expected fiberType StateMachine, got $fiberType"
              ) and
              expect(inputType == InputKind.MethodCall, s"Expected inputType MethodCall, got $inputType")
            case other =>
              failure(s"Expected FiberInputMismatch but got: ${other.getClass.getSimpleName}")
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with FiberInputMismatch, but transaction was committed")
      }
    }
  }

  test("type mismatch: Transition input with ScriptFiberRecord") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        oracleId <- UUIDGen.randomUUID[IO]

        // Simple oracle script that just returns state
        oracleScript = VarExpression(Left("_state"))

        oracleData = MapValue(Map("value" -> IntValue(0)))
        oracleHash <- (oracleData: JsonLogicValue).computeDigest

        // Create a script fiber
        oracle = Records.ScriptFiberRecord(
          fiberId = oracleId,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          scriptProgram = oracleScript,
          stateData = Some(oracleData),
          stateDataHash = Some(oracleHash),
          owners = Set.empty,
          status = FiberStatus.Active,
          sequenceNumber = FiberOrdinal.MinValue,
          accessControl = AccessControlPolicy.Public
        )

        calculatedState = CalculatedState(SortedMap.empty, SortedMap(oracleId -> oracle))

        // Use Transition input (state machine-style) with oracle - type mismatch
        input = FiberInput.Transition(
          "someEvent",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(oracleId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          reason match {
            case FailureReason.FiberInputMismatch(oid, fiberType, inputType) =>
              expect(oid == oracleId, s"Expected oracle $oracleId, got $oid") and
              expect(
                fiberType == FiberKind.Script,
                s"Expected fiberType Script, got $fiberType"
              ) and
              expect(inputType == InputKind.Transition, s"Expected inputType Transition, got $inputType")
            case other =>
              failure(s"Expected FiberInputMismatch but got: ${other.getClass.getSimpleName}")
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with FiberInputMismatch, but transaction was committed")
      }
    }
  }
}
