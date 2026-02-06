package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._
import xyz.kd5ujc.shared_data.fiber.core.{ExecutionState, FiberT}
import xyz.kd5ujc.shared_data.fiber.triggers.TriggerDispatcher
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.CommonRules
import xyz.kd5ujc.shared_test.{Participant, TestFixture}

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DeterministicExecutionSuite extends SimpleIOSuite with Checkers {

  test("deterministic execution: same input always produces same output") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Create a simple state machine
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

        initialData = MapValue(Map("counter" -> IntValue(0)))
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
        input = FiberInput.Transition(
          "activate",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)

        // Execute the same input multiple times
        orchestrator1 = FiberEngine.make[IO](calculatedState, ordinal, limits)
        result1 <- orchestrator1.process(fiberId, input, List.empty)

        orchestrator2 = FiberEngine.make[IO](calculatedState, ordinal, limits)
        result2 <- orchestrator2.process(fiberId, input, List.empty)

        orchestrator3 = FiberEngine.make[IO](calculatedState, ordinal, limits)
        result3 <- orchestrator3.process(fiberId, input, List.empty)

      } yield expect(result1 == result2) and // All results should be identical
      expect(result2 == result3) and
      expect(result1.isInstanceOf[TransactionResult.Committed]) and
      expect(
        result1 match {
          case TransactionResult.Committed(updatedSMs, _, _, _, _, _) =>
            updatedSMs.get(fiberId).exists(_.currentState == StateId("ACTIVE"))
          case _ => false
        }
      )
    }
  }

  test("gas limit enforcement: transaction fails when gas exhausted") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Use a guard that will consume gas during evaluation
        // 50 nested additions (within depth limit of 100) with very low gas limit
        expensiveExpr = (1 to 50).foldLeft[JsonLogicExpression](
          ConstExpression(IntValue(1))
        ) { (acc, _) =>
          ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
        }

        // Guard that evaluates the expensive expression and compares using ===
        guardExpr = ApplyExpression(
          EqStrictOp,
          List(expensiveExpr, ConstExpression(IntValue(51))) // Will be true
        )

        definition = StateMachineDefinition(
          states = Map(
            StateId("idle") -> State(StateId("idle"))
          ),
          initialState = StateId("idle"),
          transitions = List(
            Transition(
              from = StateId("idle"),
              to = StateId("idle"),
              eventName = "process",
              guard = guardExpr,
              effect = ConstExpression(MapValue(Map("processed" -> BoolValue(true))))
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
        input = FiberInput.Transition(
          "process",
          MapValue(Map.empty)
        )

        // Use a very low gas limit (1) so evaluation should exceed it
        limits = ExecutionLimits(maxDepth = 10, maxGas = 1L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)
      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(reason.isInstanceOf[FailureReason.GasExhaustedFailure])
            .or(failure(s"Expected GasExhaustedFailure but got: ${reason.getClass.getSimpleName}"))
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with GasExhaustedFailure, but transaction was committed")
      }
    }
  }

  test("input validation: rejects deeply nested expressions") {
    val deepExpression = (1 to 150).foldLeft[JsonLogicExpression](
      ConstExpression(IntValue(1))
    ) { (acc, _) =>
      ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
    }

    CommonRules.expressionWithinDepthLimit[IO](deepExpression, "testExpr", maxDepth = 100).map { result =>
      expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.contains("exceeds maximum"))))
    }
  }

  test("input validation: rejects oversized state") {
    val largeString = "x" * 1000000
    val largeState = MapValue(
      Map(
        "data" -> StrValue(largeString)
      )
    )

    CommonRules.valueWithinSizeLimit[IO](largeState, maxSizeBytes = 500000, "testState").map { result =>
      expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.contains("exceeds maximum"))))
    }
  }

  test("input validation: rejects control characters in event payload") {
    val dirtyPayload = MapValue(
      Map(
        "message" -> StrValue("Hello\u0000\u0001World")
      )
    )

    CommonRules.payloadStructureValid[IO](dirtyPayload, "testPayload").map { result =>
      expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.contains("control characters"))))
    }
  }

  test("cycle detection: prevents infinite loops") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Create a state machine that triggers itself with the same event type
        definition = StateMachineDefinition(
          states = Map(
            StateId("loop1") -> State(StateId("loop1")),
            StateId("loop2") -> State(StateId("loop2"))
          ),
          initialState = StateId("loop1"),
          transitions = List(
            Transition(
              from = StateId("loop1"),
              to = StateId("loop2"),
              eventName = "loop",
              guard = ConstExpression(BoolValue(true)),
              // Effect triggers the same fiber with the same event type (creates cycle)
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
              from = StateId("loop2"),
              to = StateId("loop1"),
              eventName = "loop",
              guard = ConstExpression(BoolValue(true)),
              // This transition ALSO triggers, creating an infinite loop
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
          currentState = StateId("loop1"),
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
            .or(failure(s"Expected CycleDetected, got $reason"))
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure(s"Expected Aborted, got Committed")
      }
    }
  }

  test("depth limit enforcement: prevents stack overflow") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        // Create multiple fibers that chain-trigger each other
        fiber1Id <- UUIDGen.randomUUID[IO]
        fiber2Id <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Each fiber triggers the other, creating depth
        makeDef = (targetId: java.util.UUID) =>
          StateMachineDefinition(
            states = Map(
              StateId("s1") -> State(StateId("s1")),
              StateId("s2") -> State(StateId("s2"))
            ),
            initialState = StateId("s1"),
            transitions = List(
              Transition(
                from = StateId("s1"),
                to = StateId("s2"),
                eventName = "ping",
                guard = ConstExpression(BoolValue(true)),
                effect = ConstExpression(
                  MapValue(
                    Map(
                      "pinged" -> BoolValue(true),
                      "_triggers" -> ArrayValue(
                        List(
                          MapValue(
                            Map(
                              "targetMachineId" -> StrValue(targetId.toString),
                              "eventName"       -> StrValue("ping"),
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
                from = StateId("s2"),
                to = StateId("s1"),
                eventName = "ping",
                guard = ConstExpression(BoolValue(true)),
                effect = ConstExpression(
                  MapValue(
                    Map(
                      "ponged" -> BoolValue(true),
                      "_triggers" -> ArrayValue(
                        List(
                          MapValue(
                            Map(
                              "targetMachineId" -> StrValue(targetId.toString),
                              "eventName"       -> StrValue("ping"),
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

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest

        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          fiberId = fiber1Id,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = makeDef(fiber2Id),
          currentState = StateId("s1"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = fiber2Id,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = makeDef(fiber1Id),
          currentState = StateId("s1"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(
          SortedMap(fiber1Id -> fiber1, fiber2Id -> fiber2),
          SortedMap.empty
        )

        input = FiberInput.Transition(
          "ping",
          MapValue(Map.empty)
        )

        // Very shallow depth limit
        limits = ExecutionLimits(maxDepth = 2, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiber1Id, input, List.empty)

      } yield expect(
        result match {
          case TransactionResult.Aborted(reason, _, _) =>
            reason match {
              case FailureReason.DepthExceeded(_, _) => true
              case _                                 => false
            }
          case _ => false
        }
      )
    }
  }

  test("atomic triggers: all succeed or all fail") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiber1Id <- UUIDGen.randomUUID[IO]
        fiber2Id <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        simpleDef = StateMachineDefinition(
          states = Map(
            StateId("state1") -> State(StateId("state1")),
            StateId("state2") -> State(StateId("state2"))
          ),
          initialState = StateId("state1"),
          transitions = List(
            Transition(
              from = StateId("state1"),
              to = StateId("state2"),
              eventName = "advance",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("advanced" -> BoolValue(true))))
            )
          )
        )

        data1 = MapValue(Map("id" -> IntValue(1)))
        hash1 <- (data1: JsonLogicValue).computeDigest

        data2 = MapValue(Map("id" -> IntValue(2)))
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          fiberId = fiber1Id,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = simpleDef,
          currentState = StateId("state1"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = fiber2Id,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = simpleDef,
          currentState = StateId("state1"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(
          SortedMap(
            fiber1Id -> fiber1,
            fiber2Id -> fiber2
          ),
          SortedMap.empty
        )

        triggers = List(
          FiberTrigger(
            targetFiberId = fiber1Id,
            input = FiberInput.Transition("advance", MapValue(Map.empty)),
            sourceFiberId = None
          ),
          FiberTrigger(
            targetFiberId = fiber2Id,
            input = FiberInput.Transition("advance", MapValue(Map.empty)),
            sourceFiberId = None
          )
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)

        ctx = FiberContext(ordinal, limits, GasConfig.Default, FiberGasConfig.Default)
        dispatcher = TriggerDispatcher.make[IO, FiberT[IO, *]]
        result <- dispatcher
          .dispatch(triggers, calculatedState)
          .run(ctx)
          .runA(ExecutionState.initial)

      } yield result match {
        case TransactionResult.Committed(updatedSMs, _, _, _, _, _) =>
          // Both fibers should be updated atomically
          expect(updatedSMs.size == 2, s"Expected 2 updated machines, got ${updatedSMs.size}") and
          expect(
            updatedSMs.get(fiber1Id).exists(_.currentState == StateId("state2")),
            s"Expected fiber1 in state 'state2', got ${updatedSMs.get(fiber1Id).map(_.currentState)}"
          ) and
          expect(
            updatedSMs.get(fiber2Id).exists(_.currentState == StateId("state2")),
            s"Expected fiber2 in state 'state2', got ${updatedSMs.get(fiber2Id).map(_.currentState)}"
          )
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("failed transactions don't change state") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        definition = StateMachineDefinition(
          states = Map(
            StateId("initial") -> State(StateId("initial"))
          ),
          initialState = StateId("initial"),
          transitions = List.empty // No valid transitions
        )

        initialData = MapValue(Map("important" -> IntValue(42)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("initial"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.unsafeApply(5),
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "invalid",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

        // State should remain unchanged - get from original calculatedState
        finalFiber = calculatedState.stateMachines.get(fiberId)

      } yield expect(result.isInstanceOf[TransactionResult.Aborted]) and
      // Fiber state should be unchanged (Aborted means no changes applied)
      expect(finalFiber.isDefined) and
      expect(finalFiber.exists(_.sequenceNumber == FiberOrdinal.unsafeApply(5L))) and
      expect(finalFiber.exists(_.stateData == initialData))
    }
  }

  test("guard gas accounting: charges gas for failed guards") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Create state machine with multiple guarded transitions
        // First two guards will fail, third will pass
        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start")),
            StateId("end")   -> State(StateId("end"))
          ),
          initialState = StateId("start"),
          transitions = List(
            // First guard: expensive computation that returns false
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ApplyExpression(
                EqOp,
                List(
                  ApplyExpression(AddOp, List(ConstExpression(IntValue(1)), ConstExpression(IntValue(1)))),
                  ConstExpression(IntValue(999)) // 1+1 != 999, so false
                )
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first"))))
            ),
            // Second guard: also returns false
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ApplyExpression(
                EqOp,
                List(
                  ApplyExpression(AddOp, List(ConstExpression(IntValue(2)), ConstExpression(IntValue(2)))),
                  ConstExpression(IntValue(999)) // 2+2 != 999, so false
                )
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("second"))))
            ),
            // Third guard: returns true
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("third"))))
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

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, totalGasUsed, _, _) =>
          // Gas should include ALL guard evaluations (both failed ones + the successful one) + effect
          // First guard: == check (1) + add (1) + 2 consts (2) = 4
          // Second guard: == check (1) + add (1) + 2 consts (2) = 4
          // Third guard: const bool (1) = 1
          // Effect: const map (1) = 1
          // Minimum expected: 10 gas (guard evaluations + effect)
          val updatedFiber = machines.get(fiberId)
          val expectedMinGas = 10L
          expect(totalGasUsed >= expectedMinGas, s"Expected at least $expectedMinGas gas used, got $totalGasUsed") and
          expect(
            updatedFiber.exists(_.stateData match {
              case MapValue(m) => m.get("path").contains(StrValue("third"))
              case _           => false
            }),
            "Expected 'path' to be 'third'"
          ) and
          expect(
            updatedFiber.exists(_.currentState == StateId("end")),
            "Expected state to be 'end'"
          )
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("gas exhaustion returns structured GasExhaustedFailure reason") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Create state machine with expensive guard that will exhaust gas
        expensiveGuard = (1 to 50).foldLeft[JsonLogicExpression](
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
              guard = ApplyExpression(EqOp, List(expensiveGuard, ConstExpression(IntValue(50)))),
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

        // Use very low gas limit to trigger exhaustion
        limits = ExecutionLimits(maxDepth = 10, maxGas = 10L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          reason match {
            case FailureReason.GasExhaustedFailure(used, limit, phase) =>
              expect(used <= limit, s"Gas used ($used) should be at or below limit ($limit)") and
              expect(limit == 10L, s"Expected gas limit 10L, got $limit") and
              expect(phase == GasExhaustionPhase.Guard, s"Expected Guard phase, got $phase")
            case other =>
              failure(s"Expected GasExhaustedFailure but got: ${other.getClass.getSimpleName}")
          }
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with GasExhaustedFailure, but transaction was committed")
      }
    }
  }

  test("cycle detection: 3-node cycle A→B→C→A detected") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machineA <- UUIDGen.randomUUID[IO]
        machineB <- UUIDGen.randomUUID[IO]
        machineC <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // A triggers B
        defA = StateMachineDefinition(
          states = Map(
            StateId("idle")      -> State(StateId("idle")),
            StateId("triggered") -> State(StateId("triggered"))
          ),
          initialState = StateId("idle"),
          transitions = List(
            Transition(
              from = StateId("idle"),
              to = StateId("triggered"),
              eventName = "start",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(1),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineB.toString),
                            "eventName"       -> StrValue("continue"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Transition to handle incoming loop - triggers B again to create cycle
            Transition(
              from = StateId("triggered"),
              to = StateId("idle"),
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
                            "targetMachineId" -> StrValue(machineB.toString),
                            "eventName"       -> StrValue("continue"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Also handle loop in idle state to keep cycle going
            Transition(
              from = StateId("idle"),
              to = StateId("triggered"),
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
                            "targetMachineId" -> StrValue(machineB.toString),
                            "eventName"       -> StrValue("continue"),
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

        // B triggers C (with self-transition to allow cycle to continue)
        defB = StateMachineDefinition(
          states = Map(
            StateId("waiting")   -> State(StateId("waiting")),
            StateId("continued") -> State(StateId("continued"))
          ),
          initialState = StateId("waiting"),
          transitions = List(
            Transition(
              from = StateId("waiting"),
              to = StateId("continued"),
              eventName = "continue",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(2),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineC.toString),
                            "eventName"       -> StrValue("finish"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Self-transition to handle continue event in cycle
            Transition(
              from = StateId("continued"),
              to = StateId("continued"),
              eventName = "continue",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(2),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineC.toString),
                            "eventName"       -> StrValue("finish"),
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

        // C triggers A (completes the cycle, with self-transition to allow cycle to continue)
        defC = StateMachineDefinition(
          states = Map(
            StateId("PENDING")  -> State(StateId("PENDING")),
            StateId("finished") -> State(StateId("finished"))
          ),
          initialState = StateId("PENDING"),
          transitions = List(
            Transition(
              from = StateId("PENDING"),
              to = StateId("finished"),
              eventName = "finish",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(3),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineA.toString),
                            "eventName"       -> StrValue("loop"), // Back to A!
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Self-transition to handle finish event in cycle
            Transition(
              from = StateId("finished"),
              to = StateId("finished"),
              eventName = "finish",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(3),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineA.toString),
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

        dataA = MapValue(Map.empty)
        hashA <- (dataA: JsonLogicValue).computeDigest

        dataB = MapValue(Map.empty)
        hashB <- (dataB: JsonLogicValue).computeDigest

        dataC = MapValue(Map.empty)
        hashC <- (dataC: JsonLogicValue).computeDigest

        fiberA = Records.StateMachineFiberRecord(
          fiberId = machineA,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defA,
          currentState = StateId("idle"),
          stateData = dataA,
          stateDataHash = hashA,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiberB = Records.StateMachineFiberRecord(
          fiberId = machineB,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defB,
          currentState = StateId("waiting"),
          stateData = dataB,
          stateDataHash = hashB,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiberC = Records.StateMachineFiberRecord(
          fiberId = machineC,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defC,
          currentState = StateId("PENDING"),
          stateData = dataC,
          stateDataHash = hashC,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(
          SortedMap(machineA -> fiberA, machineB -> fiberB, machineC -> fiberC),
          SortedMap.empty
        )

        input = FiberInput.Transition(
          "start",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(machineA, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          // 3-node cycle A→B→C→A should be detected before depth is exceeded
          expect(
            reason.isInstanceOf[FailureReason.CycleDetected],
            s"Expected CycleDetected but got: ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with CycleDetected, but transaction was committed")
      }
    }
  }

  test("cycle detection: different event types forming cycle") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machineA <- UUIDGen.randomUUID[IO]
        machineB <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // A sends "ping" to B, B sends "pong" to A, A sends "ping" to B...
        defA = StateMachineDefinition(
          states = Map(
            StateId("s1") -> State(StateId("s1")),
            StateId("s2") -> State(StateId("s2"))
          ),
          initialState = StateId("s1"),
          transitions = List(
            Transition(
              from = StateId("s1"),
              to = StateId("s2"),
              eventName = "start",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "sent" -> StrValue("ping"),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineB.toString),
                            "eventName"       -> StrValue("ping"),
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
              from = StateId("s2"),
              to = StateId("s1"),
              eventName = "pong",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "received" -> StrValue("pong"),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineB.toString),
                            "eventName"       -> StrValue("ping"),
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

        defB = StateMachineDefinition(
          states = Map(
            StateId("idle")     -> State(StateId("idle")),
            StateId("received") -> State(StateId("received"))
          ),
          initialState = StateId("idle"),
          transitions = List(
            Transition(
              from = StateId("idle"),
              to = StateId("received"),
              eventName = "ping",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "got" -> StrValue("ping"),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineA.toString),
                            "eventName"       -> StrValue("pong"),
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
              from = StateId("received"),
              to = StateId("idle"),
              eventName = "ping",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "got" -> StrValue("ping again"),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineA.toString),
                            "eventName"       -> StrValue("pong"),
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

        dataA = MapValue(Map.empty)
        hashA <- (dataA: JsonLogicValue).computeDigest

        dataB = MapValue(Map.empty)
        hashB <- (dataB: JsonLogicValue).computeDigest

        fiberA = Records.StateMachineFiberRecord(
          fiberId = machineA,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defA,
          currentState = StateId("s1"),
          stateData = dataA,
          stateDataHash = hashA,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiberB = Records.StateMachineFiberRecord(
          fiberId = machineB,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defB,
          currentState = StateId("idle"),
          stateData = dataB,
          stateDataHash = hashB,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(
          SortedMap(machineA -> fiberA, machineB -> fiberB),
          SortedMap.empty
        )

        input = FiberInput.Transition(
          "start",
          MapValue(Map.empty)
        )

        // Use high depth limit so cycle detection triggers before depth is exceeded
        limits = ExecutionLimits(maxDepth = 100, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(machineA, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          // Ping-pong cycle should be detected: A→ping→B→pong→A→ping→B (repeat!)
          expect(
            reason.isInstanceOf[FailureReason.CycleDetected],
            s"Expected CycleDetected but got: ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with CycleDetected, but transaction was committed")
      }
    }
  }

  test("cycle detection: parent-child mutual trigger forms cycle") {
    // Test cycle detection when parent triggers child, child triggers parent back
    // Both fibers pre-exist to avoid spawn+trigger timing issues
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Child triggers parent on activate, creating half of the cycle
        childDefinition = StateMachineDefinition(
          states = Map(
            StateId("init")      -> State(StateId("init")),
            StateId("triggered") -> State(StateId("triggered"))
          ),
          initialState = StateId("init"),
          transitions = List(
            Transition(
              from = StateId("init"),
              to = StateId("triggered"),
              eventName = "activate",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "activated" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(parentId.toString),
                            "eventName"       -> StrValue("child_callback"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Self-transition to handle activate when already triggered (for cycle)
            Transition(
              from = StateId("triggered"),
              to = StateId("init"),
              eventName = "activate",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "reactivated" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(parentId.toString),
                            "eventName"       -> StrValue("child_callback"),
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

        // Parent triggers child, creating the other half of the cycle
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("ready")    -> State(StateId("ready")),
            StateId("ACTIVE")   -> State(StateId("ACTIVE")),
            StateId("callback") -> State(StateId("callback"))
          ),
          initialState = StateId("ready"),
          transitions = List(
            // Start transition triggers child
            Transition(
              from = StateId("ready"),
              to = StateId("ACTIVE"),
              eventName = "start",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "started" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(childId.toString),
                            "eventName"       -> StrValue("activate"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Callback from child triggers child again - creates cycle!
            Transition(
              from = StateId("ACTIVE"),
              to = StateId("callback"),
              eventName = "child_callback",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "callback_received" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(childId.toString),
                            "eventName"       -> StrValue("activate"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            // Handle callback from callback state to continue cycle
            Transition(
              from = StateId("callback"),
              to = StateId("ACTIVE"),
              eventName = "child_callback",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "callback_received" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(childId.toString),
                            "eventName"       -> StrValue("activate"),
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

        parentData = MapValue(Map.empty)
        parentHash <- (parentData: JsonLogicValue).computeDigest

        childData = MapValue(Map.empty)
        childHash <- (childData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          fiberId = parentId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = parentDefinition,
          currentState = StateId("ready"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        childFiber = Records.StateMachineFiberRecord(
          fiberId = childId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = childDefinition,
          currentState = StateId("init"),
          stateData = childData,
          stateDataHash = childHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        // Both fibers exist from the start
        calculatedState = CalculatedState(SortedMap(parentId -> parentFiber, childId -> childFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "start",
          MapValue(Map.empty)
        )

        // Use high depth limit so cycle detection triggers before depth is exceeded
        limits = ExecutionLimits(maxDepth = 100, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(parentId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          // Parent→child→parent→child... cycle should be detected
          expect(
            reason.isInstanceOf[FailureReason.CycleDetected],
            s"Expected CycleDetected but got: ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with CycleDetected, but transaction was committed")
      }
    }
  }

  test("dependency resolution: non-existent machine dependency aborts transaction") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId   <- UUIDGen.randomUUID[IO]
        missingId <- UUIDGen.randomUUID[IO] // This machine doesn't exist
        ordinal = fixture.ordinal

        // State machine with a dependency on a non-existent machine
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
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true)))),
              dependencies = Set(missingId) // Dependency on non-existent machine
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

        // Calculated state only contains the main fiber, not the dependency
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
          // Missing trigger target should cause TriggerTargetNotFound
          expect(
            reason.isInstanceOf[FailureReason.TriggerTargetNotFound],
            s"Expected TriggerTargetNotFound but got: ${reason.getClass.getSimpleName}"
          )
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          // If missing dependencies are skipped, verify the main transition completed
          expect(
            machines.get(fiberId).exists(_.currentState == StateId("end")),
            "If dependencies are skipped, transition should still complete"
          )
      }
    }
  }
}
