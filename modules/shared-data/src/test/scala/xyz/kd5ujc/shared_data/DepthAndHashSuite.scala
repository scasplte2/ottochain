package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Tests for depth accounting and state hash consistency.
 *
 * Low priority tests that verify:
 * - Depth counting includes spawns and triggers
 * - State hashes remain consistent across operations
 * - Failed transactions preserve original hashes
 */
object DepthAndHashSuite extends SimpleIOSuite {

  test("depth limit respects spawn operations") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]

        // Child definition that also spawns (would create grandchild)
        childDefinitionJson = MapValue(
          Map(
            "states" -> MapValue(
              Map(
                "init" -> MapValue(Map("id" -> StrValue("init")))
              )
            ),
            "initialState" -> StrValue("init"),
            "transitions"  -> ArrayValue(List.empty)
          )
        )

        // Parent spawns child
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("ready")   -> State(StateId("ready"), isFinal = false),
            StateId("spawned") -> State(StateId("spawned"), isFinal = false)
          ),
          initialState = StateId("ready"),
          transitions = List(
            Transition(
              from = StateId("ready"),
              to = StateId("spawned"),
              eventName = "spawn",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "spawned" -> BoolValue(true),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId"         -> StrValue(childId.toString),
                            "definition"      -> childDefinitionJson,
                            "initializerData" -> MapValue(Map("status" -> StrValue("spawned")))
                          )
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

        parentData = MapValue(Map.empty)
        parentHash <- (parentData: JsonLogicValue).computeDigest

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

        calculatedState = CalculatedState(SortedMap(parentId -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn",
          MapValue(Map.empty)
        )

        // Test with very low depth limit
        limits = ExecutionLimits(maxDepth = 1, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(parentId, input, List.empty)

      } yield result match {
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          // Spawns don't increment depth - only triggers do
          // With maxDepth=1, parent transition at depth 0 succeeds, child is spawned
          expect(
            machines.contains(parentId),
            "Parent machine should be in result"
          ) and
          expect(
            machines.get(parentId).exists(_.currentState == StateId("spawned")),
            s"Expected parent in 'spawned' state, got ${machines.get(parentId).map(_.currentState)}"
          )
        case TransactionResult.Aborted(reason, _, _) =>
          failure(s"Expected Committed (spawns don't increment depth), but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("failed transaction preserves original state hash") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine with no valid transitions
        definition = StateMachineDefinition(
          states = Map(
            StateId("locked") -> State(StateId("locked"), isFinal = false)
          ),
          initialState = StateId("locked"),
          transitions = List.empty
        )

        initialData = MapValue(Map("important" -> IntValue(999), "preserve" -> StrValue("this")))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("locked"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.unsafeApply(10),
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(fiberId -> fiber), SortedMap.empty)
        input = FiberInput.Transition(
          "unlock", // No such transition
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(_, _, _) =>
          // Original state in calculatedState should be unchanged
          val originalFiber = calculatedState.stateMachines.get(fiberId)
          expect(originalFiber.exists(_.stateDataHash == initialHash)) and
          expect(originalFiber.exists(_.stateData == initialData)) and
          expect(originalFiber.exists(_.sequenceNumber == FiberOrdinal.unsafeApply(10L)))
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted for missing transition")
      }
    }
  }

  test("same state data produces same hash deterministically") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        // Create identical state data multiple times
        data1 <- MapValue(Map("a" -> IntValue(1), "b" -> StrValue("hello"))).pure[IO]
        data2 = MapValue(Map("a" -> IntValue(1), "b" -> StrValue("hello")))
        data3 = MapValue(Map("b" -> StrValue("hello"), "a" -> IntValue(1))) // Different order

        hash1 <- (data1: JsonLogicValue).computeDigest
        hash2 <- (data2: JsonLogicValue).computeDigest
        hash3 <- (data3: JsonLogicValue).computeDigest

      } yield expect(hash1 == hash2) and // Same data should produce same hash
      expect(hash1 == hash3) // Map ordering should not affect hash (canonical)
    }
  }

  test("depth limit prevents deeply chained triggers") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        machine1 <- UUIDGen.randomUUID[IO]
        machine2 <- UUIDGen.randomUUID[IO]
        machine3 <- UUIDGen.randomUUID[IO]

        // Chain: 1 -> 2 -> 3 with maxDepth=2 should fail at 3
        def1 = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a"), isFinal = false),
            StateId("b") -> State(StateId("b"), isFinal = false)
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
                            "targetMachineId" -> StrValue(machine2.toString),
                            "eventName"       -> StrValue("go"),
                            "payload"         -> MapValue(Map.empty)
                          )
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

        def2 = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a"), isFinal = false),
            StateId("b") -> State(StateId("b"), isFinal = false)
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
                    "step" -> IntValue(2),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine3.toString),
                            "eventName"       -> StrValue("go"),
                            "payload"         -> MapValue(Map.empty)
                          )
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

        def3 = StateMachineDefinition(
          states = Map(
            StateId("a") -> State(StateId("a"), isFinal = false),
            StateId("b") -> State(StateId("b"), isFinal = false)
          ),
          initialState = StateId("a"),
          transitions = List(
            Transition(
              from = StateId("a"),
              to = StateId("b"),
              eventName = "go",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("step" -> IntValue(3)))),
              dependencies = Set.empty
            )
          )
        )

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest
        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest
        data3 = MapValue(Map.empty)
        hash3 <- (data3: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          fiberId = machine1,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = def1,
          currentState = StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = machine2,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = def2,
          currentState = StateId("a"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber3 = Records.StateMachineFiberRecord(
          fiberId = machine3,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = def3,
          currentState = StateId("a"),
          stateData = data3,
          stateDataHash = hash3,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(
          SortedMap(machine1 -> fiber1, machine2 -> fiber2, machine3 -> fiber3),
          SortedMap.empty
        )

        input = FiberInput.Transition(
          "go",
          MapValue(Map.empty)
        )

        // Depth 2 should allow 1->2 but fail at 3
        limits = ExecutionLimits(maxDepth = 2, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(machine1, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(reason.isInstanceOf[FailureReason.DepthExceeded])
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected to exceed depth")
      }
    }
  }
}
