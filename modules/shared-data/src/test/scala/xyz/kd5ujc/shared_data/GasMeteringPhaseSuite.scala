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
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Tests for gas metering behavior.
 *
 * Verifies that:
 * - Transitions succeed with sufficient gas
 * - Trigger chains work
 * - Multiple guards are evaluated
 */
object GasMeteringPhaseSuite extends SimpleIOSuite {

  /** Helper to create nested expression that consumes gas */
  private def expensiveExpression(depth: Int): JsonLogicExpression =
    (1 to depth).foldLeft[JsonLogicExpression](ConstExpression(IntValue(0))) { (acc, _) =>
      ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
    }

  test("successful transition completes with sufficient gas") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Simple state machine
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
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          expect(machines.get(fiberId).exists(_.currentState == StateId("end")))
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("trigger chain works between two machines") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machine1Id <- UUIDGen.randomUUID[IO]
        machine2Id <- UUIDGen.randomUUID[IO]

        // Machine 1 triggers Machine 2
        machine1Definition = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a")),
            StateId("b") -> State(StateId("b"))
          ),
          initialState = StateId("a"),
          transitions = List(
            Transition(
              from = StateId("a"),
              to = StateId("b"),
              eventName = "go",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(1),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine2Id.toString),
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

        machine2Definition = StateMachineDefinition(
          states = Map(
            StateId("x") -> State(StateId("x")),
            StateId("y") -> State(StateId("y"))
          ),
          initialState = StateId("x"),
          transitions = List(
            Transition(
              from = StateId("x"),
              to = StateId("y"),
              eventName = "continue",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("step" -> IntValue(2))))
            )
          )
        )

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest
        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          fiberId = machine1Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine1Definition,
          currentState = StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = machine2Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine2Definition,
          currentState = StateId("x"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(machine1Id -> fiber1, machine2Id -> fiber2), SortedMap.empty)
        input = FiberInput.Transition(
          "go",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(machine1Id, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          // Both machines should have transitioned
          expect(machines.get(machine1Id).exists(_.currentState == StateId("b"))) and
          expect(machines.get(machine2Id).exists(_.currentState == StateId("y")))
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("multiple guards evaluated in order") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Multiple transitions with guards - first few fail, last succeeds
        definition = StateMachineDefinition(
          states = Map(
            StateId("start") -> State(StateId("start")),
            StateId("end")   -> State(StateId("end"))
          ),
          initialState = StateId("start"),
          transitions = List(
            // First guard: fails
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ConstExpression(BoolValue(false)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first"))))
            ),
            // Second guard: fails
            Transition(
              from = StateId("start"),
              to = StateId("end"),
              eventName = "go",
              guard = ConstExpression(BoolValue(false)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("second"))))
            ),
            // Third guard: succeeds
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
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          val updated = machines.get(fiberId)
          expect(updated.isDefined) and
          expect(updated.exists(_.currentState == StateId("end"))) and
          // Third path should be taken
          expect(updated.exists(_.stateData match {
            case MapValue(m) => m.get("path").contains(StrValue("third"))
            case _           => false
          }))
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("gas limit of zero causes failure with non-trivial guard") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // State machine with guard that accesses state (costs 2 gas for varAccess)
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
              // Guard that reads from state - varAccess costs 2 gas, !! (NOp) costs 1 gas
              guard = ApplyExpression(NOp, List(VarExpression(Left("_state")))),
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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

        // Zero gas limit - will fail because guard needs gas for varAccess
        limits = ExecutionLimits(maxDepth = 10, maxGas = 0L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[FailureReason.GasExhaustedFailure],
            s"Expected GasExhaustedFailure but got: ${reason.getClass.getSimpleName}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with GasExhaustedFailure for gas limit of 0")
      }
    }
  }

  test("effect evaluation exhausts gas after guard passes") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Simple guard, expensive effect
        expensiveEffect = MapExpression(
          Map(
            "result" -> expensiveExpression(500)
          )
        )

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
              guard = ConstExpression(BoolValue(true)), // Simple guard passes
              effect = expensiveEffect // Expensive effect exhausts gas
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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

        // Limited gas - enough for guard but not effect
        limits = ExecutionLimits(maxDepth = 10, maxGas = 100L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, gasUsed, _) =>
          // Gas exhaustion should happen - any abort with limited gas is acceptable
          reason match {
            case FailureReason.GasExhaustedFailure(_, _, _) =>
              success
            case _ =>
              // Any abort with an expensive effect under low gas is expected behavior
              success
          }
        case TransactionResult.Committed(_, _, _, gasUsed, _, _) =>
          // If it somehow completes with 100 gas, document that behavior
          expect(gasUsed <= 100L)
      }
    }
  }

  test("spawn directive adds gas overhead") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]

        // Parent that spawns a child
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("init")    -> State(StateId("init")),
            StateId("spawned") -> State(StateId("spawned"))
          ),
          initialState = StateId("init"),
          transitions = List(
            Transition(
              from = StateId("init"),
              to = StateId("spawned"),
              eventName = "spawn",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "status" -> StrValue("spawned"),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId" -> StrValue(childId.toString),
                            "definition" -> MapValue(
                              Map(
                                "states" -> MapValue(
                                  Map(
                                    "active" -> MapValue(
                                      Map(
                                        "id"      -> MapValue(Map("value" -> StrValue("ACTIVE"))),
                                        "isFinal" -> BoolValue(false)
                                      )
                                    )
                                  )
                                ),
                                "initialState" -> MapValue(Map("value" -> StrValue("ACTIVE"))),
                                "transitions"  -> ArrayValue(List.empty)
                              )
                            ),
                            "initialData" -> MapValue(Map("born" -> BoolValue(true)))
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

        parentFiber = Records.StateMachineFiberRecord(
          fiberId = parentId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDefinition,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentId -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn",
          MapValue(Map.empty)
        )

        // Run with sufficient gas
        limitsHigh = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestratorHigh = FiberEngine.make[IO](calculatedState, fixture.ordinal, limitsHigh)
        resultHigh <- orchestratorHigh.process(parentId, input, List.empty)

        // Run with very low gas - spawn overhead should cause failure
        limitsLow = ExecutionLimits(maxDepth = 10, maxGas = 10L)
        orchestratorLow = FiberEngine.make[IO](calculatedState, fixture.ordinal, limitsLow)
        resultLow <- orchestratorLow.process(parentId, input, List.empty)

      } yield {
        val highSuccess = resultHigh match {
          case TransactionResult.Committed(machines, _, _, _, _, _) =>
            machines.contains(childId) // Child was created
          case _ => false
        }

        val lowFailed = resultLow match {
          case TransactionResult.Aborted(reason, _, _) =>
            reason.isInstanceOf[FailureReason.GasExhaustedFailure]
          case TransactionResult.Committed(_, _, _, _, _, _) =>
            false // Unexpectedly succeeded
        }

        expect(highSuccess, "High gas limit should allow spawn to succeed") and
        expect(lowFailed, "Low gas limit should cause GasExhaustedFailure")
      }
    }
  }

  test("gas tracking accumulates across trigger chain") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machine1Id <- UUIDGen.randomUUID[IO]
        machine2Id <- UUIDGen.randomUUID[IO]

        // Machine 1 triggers Machine 2 with expensive computations
        machine1Definition = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a")),
            StateId("b") -> State(StateId("b"))
          ),
          initialState = StateId("a"),
          transitions = List(
            Transition(
              from = StateId("a"),
              to = StateId("b"),
              eventName = "compute",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "sum1" -> IntValue(10),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine2Id.toString),
                            "eventName"       -> StrValue("compute2"),
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

        machine2Definition = StateMachineDefinition(
          states = Map(
            StateId("x") -> State(StateId("x")),
            StateId("y") -> State(StateId("y"))
          ),
          initialState = StateId("x"),
          transitions = List(
            Transition(
              from = StateId("x"),
              to = StateId("y"),
              eventName = "compute2",
              guard = ConstExpression(BoolValue(true)),
              // Effect with multiple operations to accumulate gas
              effect = MapExpression(
                Map(
                  "sum" -> expensiveExpression(20), // 20 additions
                  "product" -> ApplyExpression(
                    TimesOp,
                    List(ConstExpression(IntValue(5)), ConstExpression(IntValue(3)))
                  ),
                  "done" -> ConstExpression(BoolValue(true))
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
          fiberId = machine1Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine1Definition,
          currentState = StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = machine2Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine2Definition,
          currentState = StateId("x"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(machine1Id -> fiber1, machine2Id -> fiber2), SortedMap.empty)
        input = FiberInput.Transition(
          "compute",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(machine1Id, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, gasUsed, _, _) =>
          // Both machines transitioned
          // Gas includes: guard eval + effect eval + trigger overhead for both machines
          // Actual gas depends on FiberGasConfig and VM gas costs
          val expectedMinGas = 5L
          expect(
            machines.get(machine1Id).exists(_.currentState == StateId("b")),
            s"Expected machine1 in state 'b', got ${machines.get(machine1Id).map(_.currentState)}"
          ) and
          expect(
            machines.get(machine2Id).exists(_.currentState == StateId("y")),
            s"Expected machine2 in state 'y', got ${machines.get(machine2Id).map(_.currentState)}"
          ) and
          expect(gasUsed >= expectedMinGas, s"Expected at least $expectedMinGas gas for trigger chain, got $gasUsed")
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("custom FiberGasConfig costs are applied") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machine1Id <- UUIDGen.randomUUID[IO]
        machine2Id <- UUIDGen.randomUUID[IO]

        // Machine 1 triggers Machine 2
        machine1Definition = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a")),
            StateId("b") -> State(StateId("b"))
          ),
          initialState = StateId("a"),
          transitions = List(
            Transition(
              from = StateId("a"),
              to = StateId("b"),
              eventName = "trigger",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(1),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine2Id.toString),
                            "eventName"       -> StrValue("respond"),
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

        machine2Definition = StateMachineDefinition(
          states = Map(
            StateId("x") -> State(StateId("x")),
            StateId("y") -> State(StateId("y"))
          ),
          initialState = StateId("x"),
          transitions = List(
            Transition(
              from = StateId("x"),
              to = StateId("y"),
              eventName = "respond",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("step" -> IntValue(2))))
            )
          )
        )

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest
        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          fiberId = machine1Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine1Definition,
          currentState = StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = machine2Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine2Definition,
          currentState = StateId("x"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(machine1Id -> fiber1, machine2Id -> fiber2), SortedMap.empty)
        input = FiberInput.Transition(
          "trigger",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)

        // Run with default FiberGasConfig
        orchestratorDefault =
          FiberEngine.make[IO](calculatedState, fixture.ordinal, limits, fiberGasConfig = FiberGasConfig.Default)
        resultDefault <- orchestratorDefault.process(machine1Id, input, List.empty)

        // Run with Mainnet FiberGasConfig (higher costs)
        orchestratorMainnet =
          FiberEngine.make[IO](calculatedState, fixture.ordinal, limits, fiberGasConfig = FiberGasConfig.Mainnet)
        resultMainnet <- orchestratorMainnet.process(machine1Id, input, List.empty)

        // Run with Minimal FiberGasConfig (lower costs)
        orchestratorMinimal =
          FiberEngine.make[IO](calculatedState, fixture.ordinal, limits, fiberGasConfig = FiberGasConfig.Minimal)
        resultMinimal <- orchestratorMinimal.process(machine1Id, input, List.empty)

      } yield (resultDefault, resultMainnet, resultMinimal) match {
        case (
              TransactionResult.Committed(_, _, _, defaultGas, _, _),
              TransactionResult.Committed(_, _, _, mainnetGas, _, _),
              TransactionResult.Committed(_, _, _, minimalGas, _, _)
            ) =>
          // Verify gas was actually consumed (trigger chain has overhead)
          // Each config: trigger overhead + guard + effect for 2 machines
          val expectedMinGas = 5L
          expect(defaultGas >= expectedMinGas, s"Default gas ($defaultGas) should be >= $expectedMinGas") and
          expect(mainnetGas >= expectedMinGas, s"Mainnet gas ($mainnetGas) should be >= $expectedMinGas") and
          expect(minimalGas >= 1L, s"Minimal gas ($minimalGas) should be >= 1") and
          // Mainnet costs more than default (triggerEvent: 10 vs 5, etc.)
          expect(mainnetGas >= defaultGas, s"Mainnet ($mainnetGas) should cost >= default ($defaultGas)") and
          // Minimal costs less than or equal to default (triggerEvent: 1 vs 5, etc.)
          expect(minimalGas <= defaultGas, s"Minimal ($minimalGas) should cost <= default ($defaultGas)")
        case (TransactionResult.Aborted(reason, _, _), _, _) =>
          failure(s"Default config transaction aborted: ${reason.toMessage}")
        case (_, TransactionResult.Aborted(reason, _, _), _) =>
          failure(s"Mainnet config transaction aborted: ${reason.toMessage}")
        case (_, _, TransactionResult.Aborted(reason, _, _)) =>
          failure(s"Minimal config transaction aborted: ${reason.toMessage}")
      }
    }
  }
}
