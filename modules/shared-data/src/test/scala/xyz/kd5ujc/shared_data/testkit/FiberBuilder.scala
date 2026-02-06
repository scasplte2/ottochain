package xyz.kd5ujc.shared_data.testkit

import java.util.UUID

import cats.effect.Async

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_test.Participant
import xyz.kd5ujc.shared_test.Participant.ParticipantRegistry

/**
 * Builder for constructing `StateMachineFiberRecord` instances with sensible defaults.
 *
 * Reduces the 12-field constructor to a fluent builder with only the essentials required.
 *
 * == Usage ==
 * {{{
 * // Minimal — definition from JSON, data as key-value pairs:
 * val fiber <- FiberBuilder(fiberId, ordinal, definition)
 *   .withState("draft")
 *   .withData("status" -> StrValue("draft"), "count" -> IntValue(0))
 *   .ownedBy(registry, Alice, Bob)
 *   .build[IO]
 *
 * // With pre-built MapValue:
 * val fiber <- FiberBuilder(fiberId, ordinal, definition)
 *   .withState("ACTIVE")
 *   .withDataValue(myMapValue)
 *   .withOwners(Set(aliceAddr, bobAddr))
 *   .build[IO]
 * }}}
 */
case class FiberBuilder private (
  fiberId:      UUID,
  ordinal:      SnapshotOrdinal,
  definition:   StateMachineDefinition,
  currentState: StateId,
  stateData:    JsonLogicValue,
  owners:       Set[Address],
  status:       FiberStatus
) {

  /** Set the current state by name. */
  def withState(state: String): FiberBuilder =
    copy(currentState = StateId(state))

  /** Set state data from key-value pairs. */
  def withData(fields: (String, JsonLogicValue)*): FiberBuilder =
    copy(stateData = MapValue(fields.toMap))

  /** Set state data from an existing JsonLogicValue. */
  def withDataValue(data: JsonLogicValue): FiberBuilder =
    copy(stateData = data)

  /** Set owners from participant registry + participant names. */
  def ownedBy[F[_]](registry: ParticipantRegistry[F], participants: Participant*): FiberBuilder =
    copy(owners = participants.toSet.map(registry.addresses))

  /** Set owners from raw addresses. */
  def withOwners(addrs: Set[Address]): FiberBuilder =
    copy(owners = addrs)

  /** Set fiber status (default is Active). */
  def withStatus(s: FiberStatus): FiberBuilder =
    copy(status = s)

  /**
   * Build the fiber record, computing the state data hash.
   *
   * @return IO containing the constructed StateMachineFiberRecord
   */
  def build[F[_]: Async]: F[Records.StateMachineFiberRecord] = {
    import cats.syntax.functor._
    (stateData: JsonLogicValue).computeDigest.map { hash =>
      Records.StateMachineFiberRecord(
        fiberId = fiberId,
        creationOrdinal = ordinal,
        previousUpdateOrdinal = ordinal,
        latestUpdateOrdinal = ordinal,
        definition = definition,
        currentState = currentState,
        stateData = stateData,
        stateDataHash = hash,
        sequenceNumber = FiberOrdinal.MinValue,
        owners = owners,
        status = status
      )
    }
  }
}

object FiberBuilder {

  /**
   * Create a new FiberBuilder with required fields and sensible defaults.
   *
   * Default state is the definition's initialState.
   * Default data is an empty MapValue.
   * Default owners is empty (set via ownedBy or withOwners).
   * Default status is Active.
   */
  def apply(
    fiberId:    UUID,
    ordinal:    SnapshotOrdinal,
    definition: StateMachineDefinition
  ): FiberBuilder =
    new FiberBuilder(
      fiberId = fiberId,
      ordinal = ordinal,
      definition = definition,
      currentState = definition.initialState,
      stateData = MapValue(Map.empty),
      owners = Set.empty,
      status = FiberStatus.Active
    )
}
