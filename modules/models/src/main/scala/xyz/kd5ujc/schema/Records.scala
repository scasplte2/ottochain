package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.asset.{AssetHolder, OriginProvenance, TokenBehavior}
import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, ScriptInvocation}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry.SchemaBinding

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
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

  @derive(customizableEncoder, customizableDecoder)
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
    childFiberIds:         Set[UUID] = Set.empty,
    schemaBinding:         Option[SchemaBinding] = None,
    authorizedSigners:     Set[Address] = Set.empty
  ) extends FiberRecord

  @derive(customizableEncoder, customizableDecoder)
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
    lastInvocation:      Option[ScriptInvocation] = None,
    schemaBinding:       Option[SchemaBinding] = None
  ) extends FiberRecord

  /**
   * An asset INSTANCE record — deliberately NOT a [[FiberRecord]] (asset-model D1: dedicated `AssetRecord`,
   * not asset-as-fiber). An asset has no JSON-Logic definition of its own; its behavior lives in the bound
   * policy package version, pinned here via [[SchemaBinding]] exactly as a state-machine pins its schema
   * ("pin once at mint; re-resolution is an explicit upgrade"). Lives in `CalculatedState.assets`. See
   * docs/proposals/asset-model.md §5b/§5c.
   *
   *   - `behavior`          — cached from the bound policy version (the authoritative copy; the combiner
   *                           re-derives composite behavior from records, never from the `OnChain` commit bits).
   *   - `holder`            — wallet or live fiber custody ([[AssetHolder]]).
   *   - `componentFiberIds` — present iff this is a composite; stored verbatim for retraction.
   *   - `componentsCommitment` — present iff this is a composite (Phase-4 hardening); the digest of the
   *                           CANONICAL (sorted-by-`assetId`) `List[ComponentWitness]` of the consumed
   *                           parts, so `Decompose` can verify the reveal witness and restore each component
   *                           FAITHFULLY (behavior/holder/amount/binding) — `None` for non-composites.
   *   - `parentCompositeId` — set on a component that has been folded into a composite.
   *   - `provenance`        — `None` for natively-issued assets; carries cross-chain origin for bridged-in
   *                           assets (D2 forward-ref → asset-interop-functor.md, full behavior Phase 6).
   *
   * Ordinals/sequence mirror the fiber records (`SnapshotOrdinal` for create/latest, `FiberOrdinal` for the
   * monotonic sequence number). A `= None` default on a `CalculatedState` record is fine — signing-canonical
   * invariant #1 governs SIGNED messages only, and `AssetRecord` is server-derived state.
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class AssetRecord(
    assetId:              UUID,
    schemaBinding:        SchemaBinding,
    behavior:             TokenBehavior,
    holder:               AssetHolder,
    amount:               Long,
    sequenceNumber:       FiberOrdinal,
    creationOrdinal:      SnapshotOrdinal,
    latestUpdateOrdinal:  SnapshotOrdinal,
    expiresAt:            Option[SnapshotOrdinal] = None,
    componentFiberIds:    Option[List[UUID]] = None,
    componentsCommitment: Option[Hash] = None,
    parentCompositeId:    Option[UUID] = None,
    provenance:           Option[OriginProvenance] = None
  )
}
