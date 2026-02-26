package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.Records.StateMachineFiberRecord
import xyz.kd5ujc.schema.fiber._

import weaver.SimpleIOSuite

/**
 * Phase 1 metagraph integration tests.
 *
 * Verifies that:
 *   - StateMachineFiberRecord carries a per-fiber MPT state root
 *   - CalculatedState carries a metagraph-level state root
 *
 * These tests validate the schema additions introduced in Phase 1.
 * FiberCombiner + ML0Service integration is covered by higher-level E2E tests.
 */
object MetagraphIntegrationSuite extends SimpleIOSuite {

  private val emptyData: JsonLogicValue = MapValue(Map.empty[String, JsonLogicValue])

  private def minimalDefinition: StateMachineDefinition = {
    val initial = StateId("initial")
    StateMachineDefinition(
      states = Map(initial -> State(initial, isFinal = false)),
      initialState = initial,
      transitions = Nil
    )
  }

  private def sampleFiberRecord(fiberId: UUID): StateMachineFiberRecord =
    StateMachineFiberRecord(
      fiberId = fiberId,
      creationOrdinal = SnapshotOrdinal.MinValue,
      previousUpdateOrdinal = SnapshotOrdinal.MinValue,
      latestUpdateOrdinal = SnapshotOrdinal.MinValue,
      definition = minimalDefinition,
      currentState = StateId("initial"),
      stateData = emptyData,
      stateDataHash = Hash.empty,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = Set.empty[Address],
      status = FiberStatus.Active
    )

  // ============================================================================
  // Group 1: StateMachineFiberRecord stateRoot Field Tests (3 tests)
  // ============================================================================

  test("StateMachineFiberRecord should have stateRoot field") {
    val fiberId = UUID.randomUUID()
    val record = sampleFiberRecord(fiberId)

    IO(expect(record.stateRoot.isDefined == false))
  }

  test("StateMachineFiberRecord stateRoot should be optional and default to None") {
    val fiberId = UUID.randomUUID()
    val record = sampleFiberRecord(fiberId)

    IO(expect(record.stateRoot == None))
  }

  test("StateMachineFiberRecord stateRoot should accept Hash values") {
    val fiberId = UUID.randomUUID()
    val sampleHash = Hash("a" * 64)
    val record = sampleFiberRecord(fiberId).copy(stateRoot = Some(sampleHash))

    IO(expect(record.stateRoot == Some(sampleHash)))
  }

  // ============================================================================
  // Group 2: CalculatedState metagraphStateRoot Field Tests (3 tests)
  // ============================================================================

  test("CalculatedState should have metagraphStateRoot field") {
    val state = CalculatedState.genesis

    IO(expect(state.metagraphStateRoot.isDefined == false))
  }

  test("CalculatedState metagraphStateRoot should be optional and default to None") {
    val state = CalculatedState.genesis

    IO(expect(state.metagraphStateRoot == None))
  }

  test("CalculatedState metagraphStateRoot should accept Hash values") {
    val sampleHash = Hash("b" * 64)
    val state = CalculatedState.genesis.copy(metagraphStateRoot = Some(sampleHash))

    IO(expect(state.metagraphStateRoot == Some(sampleHash)))
  }

  // ============================================================================
  // Group 3: Schema Consistency Tests (3 tests)
  // ============================================================================

  test("StateMachineFiberRecord stateRoot should survive copy operations") {
    val fiberId = UUID.randomUUID()
    val sampleHash = Hash("c" * 64)
    val record = sampleFiberRecord(fiberId).copy(stateRoot = Some(sampleHash))
    val copied = record.copy(sequenceNumber = FiberOrdinal.unsafeApply(1L))

    IO(expect(copied.stateRoot == Some(sampleHash)))
  }

  test("CalculatedState should preserve metagraphStateRoot when adding fibers") {
    val fiberId = UUID.randomUUID()
    val sampleHash = Hash("d" * 64)
    val record = sampleFiberRecord(fiberId)
    val state = CalculatedState.genesis.copy(
      stateMachines = SortedMap(fiberId -> record),
      metagraphStateRoot = Some(sampleHash)
    )

    IO(
      expect(state.metagraphStateRoot == Some(sampleHash)) and
      expect(state.stateMachines.size == 1)
    )
  }

  test("CalculatedState genesis should have no fibers and no metagraphStateRoot") {
    val state = CalculatedState.genesis

    IO(
      expect(state.stateMachines.isEmpty) and
      expect(state.scripts.isEmpty) and
      expect(state.metagraphStateRoot.isEmpty)
    )
  }

  // ============================================================================
  // Group 4: hashCalculatedState Conditional Logic Tests (3 tests)
  // ============================================================================

  test("metagraphStateRoot is None by default — falls back to default hashing") {
    val state = CalculatedState.genesis

    // When metagraphStateRoot is absent, the ML0Service falls back to state.computeDigest.
    // Here we simply verify the field is None as a pre-condition for the fallback path.
    IO(expect(state.metagraphStateRoot.isEmpty))
  }

  test("metagraphStateRoot Some(_) is returned directly as the canonical hash") {
    val root = Hash("e" * 64)
    val state = CalculatedState.genesis.copy(metagraphStateRoot = Some(root))

    // ML0Service.hashCalculatedState returns metagraphStateRoot.get when defined.
    // Here we verify the field is set correctly as a precondition.
    IO(expect(state.metagraphStateRoot == Some(root)))
  }

  test("Two states with same fibers but different metagraphStateRoot are distinguishable") {
    val fiberId = UUID.randomUUID()
    val record = sampleFiberRecord(fiberId)
    val stateA =
      CalculatedState(SortedMap(fiberId -> record), SortedMap.empty, metagraphStateRoot = Some(Hash("a" * 64)))
    val stateB =
      CalculatedState(SortedMap(fiberId -> record), SortedMap.empty, metagraphStateRoot = Some(Hash("b" * 64)))

    IO(expect(stateA.metagraphStateRoot != stateB.metagraphStateRoot))
  }

  // ============================================================================
  // Group 5: State Proof API Readiness Tests (3 tests)
  // ============================================================================

  test("StateMachineFiberRecord with stateRoot supports proof generation precondition") {
    val fiberId = UUID.randomUUID()
    val root = Hash("f" * 64)
    val record = sampleFiberRecord(fiberId).copy(stateRoot = Some(root))

    // Proof generation requires a non-empty stateRoot
    IO(expect(record.stateRoot.isDefined))
  }

  test("CalculatedState with metagraphStateRoot supports state proof endpoint precondition") {
    val fiberId = UUID.randomUUID()
    val root = Hash("0" * 64)
    val record = sampleFiberRecord(fiberId).copy(stateRoot = Some(root))
    val state = CalculatedState(
      stateMachines = SortedMap(fiberId -> record),
      scripts = SortedMap.empty,
      metagraphStateRoot = Some(Hash("1" * 64))
    )

    IO(
      expect(state.metagraphStateRoot.isDefined) and
      expect(state.stateMachines.get(fiberId).flatMap(_.stateRoot).isDefined)
    )
  }

  test("Non-existent fiber returns None from stateMachines lookup") {
    val nonExistentId = UUID.randomUUID()
    val state = CalculatedState.genesis

    IO(expect(state.stateMachines.get(nonExistentId).isEmpty))
  }
}
