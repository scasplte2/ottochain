package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}
import xyz.kd5ujc.schema.fiber._

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object Records {

  sealed trait FiberRecord {
    def fiberId: UUID
    def status: FiberStatus
    def owners: Set[Address]
    def creationOrdinal: SnapshotOrdinal
    def latestUpdateOrdinal: SnapshotOrdinal
    def sequenceNumber: FiberOrdinal
  }

  @derive(encoder, decoder)
  final case class StateMachineFiberRecord(
    fiberId:               UUID,
    creationOrdinal:       SnapshotOrdinal,
    previousUpdateOrdinal: SnapshotOrdinal,
    latestUpdateOrdinal:   SnapshotOrdinal,
    definition:            StateMachineDefinition,
    currentState:          StateId,
    stateData:             JsonLogicValue,
    stateDataHash:         Hash,
    sequenceNumber:        FiberOrdinal,
    owners:                Set[Address],
    status:                FiberStatus,
    lastReceipt:           Option[EventReceipt] = None,
    parentFiberId:         Option[UUID] = None,
    childFiberIds:         Set[UUID] = Set.empty
  ) extends FiberRecord

  @derive(encoder, decoder)
  final case class ScriptFiberRecord(
    fiberId:             UUID,
    creationOrdinal:     SnapshotOrdinal,
    latestUpdateOrdinal: SnapshotOrdinal,
    scriptProgram:       JsonLogicExpression,
    stateData:           Option[JsonLogicValue],
    stateDataHash:       Option[Hash],
    accessControl:       AccessControlPolicy,
    sequenceNumber:      FiberOrdinal,
    owners:              Set[Address],
    status:              FiberStatus,
    lastInvocation:      Option[OracleInvocation] = None
  ) extends FiberRecord
}
