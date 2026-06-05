package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.registry.SchemaBinding

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * Supertype for log entries emitted by fibers.
 *
 * State machines emit EventReceipt entries.
 * Script oracles emit OracleInvocation entries.
 *
 * These are collected per-ordinal in OnChain.latestLogs for external signaling.
 */
@derive(customizableEncoder, customizableDecoder)
sealed trait FiberLogEntry {
  def fiberId: UUID
}

object FiberLogEntry {

  @derive(customizableEncoder, customizableDecoder)
  final case class EventReceipt(
    fiberId:        UUID,
    sequenceNumber: FiberOrdinal,
    eventName:      String,
    ordinal:        SnapshotOrdinal,
    fromState:      StateId,
    toState:        StateId,
    success:        Boolean,
    gasUsed:        Long,
    triggersFired:  Int,
    errorMessage:   Option[String] = None,
    sourceFiberId:  Option[UUID] = None,
    emittedEvents:  List[EmittedEvent] = List.empty
  ) extends FiberLogEntry

  object EventReceipt {

    def success(
      sm:            Records.StateMachineFiberRecord,
      eventName:     String,
      ordinal:       SnapshotOrdinal,
      gasUsed:       Long,
      newStateId:    Option[StateId],
      triggers:      List[FiberTrigger],
      sourceFiberId: Option[UUID] = None,
      emittedEvents: List[EmittedEvent] = List.empty
    ): EventReceipt = EventReceipt(
      fiberId = sm.fiberId,
      sequenceNumber = sm.sequenceNumber.next,
      eventName = eventName,
      ordinal = ordinal,
      fromState = sm.currentState,
      toState = newStateId.getOrElse(sm.currentState),
      success = true,
      gasUsed = gasUsed,
      triggersFired = triggers.size,
      sourceFiberId = sourceFiberId,
      emittedEvents = emittedEvents
    )

    def failure(
      sm:        Records.StateMachineFiberRecord,
      eventName: String,
      ordinal:   SnapshotOrdinal,
      gasUsed:   Long,
      reason:    FailureReason
    ): EventReceipt = EventReceipt(
      fiberId = sm.fiberId,
      sequenceNumber = sm.sequenceNumber,
      eventName = eventName,
      ordinal = ordinal,
      fromState = sm.currentState,
      toState = sm.currentState,
      success = false,
      gasUsed = gasUsed,
      triggersFired = 0,
      errorMessage = Some(reason.toMessage)
    )
  }

  @derive(customizableEncoder, customizableDecoder)
  final case class OracleInvocation(
    fiberId:   UUID,
    method:    String,
    args:      JsonLogicValue,
    result:    JsonLogicValue,
    gasUsed:   Long,
    invokedAt: SnapshotOrdinal,
    invokedBy: Address
  ) extends FiberLogEntry

  /**
   * Emitted once when a fiber is created — the birth record for the audit trail. Records the resolved
   * registry binding (name@version + committed hashes) when the fiber was instantiated from a registered
   * version (#26), or None for an ad-hoc fiber. Seeds the audit-trail rendering (#30).
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class CreationReceipt(
    fiberId:       UUID,
    ordinal:       SnapshotOrdinal,
    initialState:  StateId,
    owners:        Set[Address],
    schemaBinding: Option[SchemaBinding] = None,
    parentFiberId: Option[UUID] = None
  ) extends FiberLogEntry

  /**
   * Emitted when a fiber is upgraded to a different registered version (#27). Records the binding change
   * (from -> to) and whether a state migration ran.
   *
   * HAND-OFF NOTE: the chain verified the new definition's hash (verified binding) and applied the
   * migration deterministically, but it did NOT verify the commute-law `migrate ∘ step = step ∘ migrate`
   * — that is a handed-off, off-chain authoring-time expectation, backed by the trust layer
   * (reputation / reserved `std.*` namespace). See docs/proposals/trust-and-verification-handoff.md and
   * the commute-law test-kit.
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class UpgradeReceipt(
    fiberId:     UUID,
    ordinal:     SnapshotOrdinal,
    fromBinding: Option[SchemaBinding],
    toBinding:   SchemaBinding,
    gasUsed:     Long,
    migrated:    Boolean
  ) extends FiberLogEntry
}
