package xyz.kd5ujc.schema.asset

import java.util.UUID

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.registry.SchemaBinding

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * The committed snapshot of ONE component that was consumed into a composite at `Compose` time — the
 * reveal witness that makes `Decompose` a FAITHFUL retraction (asset-model.md §4 "the retraction"; Phase
 * 4 hardening). It captures exactly the RESTORABLE fields of an
 * [[xyz.kd5ujc.schema.Records.AssetRecord]], EXCLUDING the volatile `creationOrdinal` /
 * `latestUpdateOrdinal` / `sequenceNumber`, which RESET on restore (the restored component is a fresh
 * record at the decompose ordinal, sequence `FiberOrdinal.MinValue`).
 *
 * == Why this exists ==
 * The pre-hardening `Compose` stored only `componentFiberIds`, and `Decompose` restored ids with an
 * EVEN amount split and components inheriting the COMPOSITE's behavior — a LOSSY retraction
 * (`Decompose ∘ Compose = id` held only on the id multiset). To make it FAITHFUL the combiner commits, at
 * `Compose`, to the CANONICAL (sorted-by-`assetId`) list of these witnesses
 * (`AssetRecord.componentsCommitment = hash(sortedWitnesses)`), and `Decompose` REQUIRES a matching reveal
 * (`ApplyMorphism.priorComponents`). Choice (a) — strict: a committed composite MUST decompose faithfully;
 * a missing / mismatched / non-conserving witness is a graceful `CombineRejected`, never a lossy fallback.
 *
 * == Recursion ==
 * `componentFiberIds` + `componentsCommitment` are carried so that a restored component can ITSELF be a
 * composite and itself be decomposed faithfully — the witness is recursive over nesting.
 *
 *   - `assetId`              — the component instance id (restored verbatim).
 *   - `schemaBinding`        — the component's pinned policy version (restored verbatim).
 *   - `behavior`            — the component's pre-Compose behavior (NOT the composite's `foldMeet`).
 *   - `holder`              — the component's pre-Compose custody (restoring returns it to its owner).
 *   - `amount`              — the component's pre-Compose amount (`Σ` must equal the composite's amount).
 *   - `expiresAt`           — the component's expiry, if any.
 *   - `componentFiberIds`   — present iff this component was itself a composite (nested retraction).
 *   - `componentsCommitment` — the nested composite's own component commitment (recursive faithfulness).
 *   - `provenance`          — the component's cross-chain origin, if any (interop forward-ref).
 *
 * This is a [[AssetOp]]-adjacent payload (it rides the SIGNED [[xyz.kd5ujc.schema.Updates.ApplyMorphism]]
 * via the `priorComponents: Option[List[ComponentWitness]]` field), but it is itself never the top-level
 * signed message — invariant #1 applies at the `ApplyMorphism` field level (`priorComponents` is `Option`).
 */
@derive(customizableEncoder, customizableDecoder)
final case class ComponentWitness(
  assetId:              UUID,
  schemaBinding:        SchemaBinding,
  behavior:             TokenBehavior,
  holder:               AssetHolder,
  amount:               Long,
  expiresAt:            Option[SnapshotOrdinal],
  componentFiberIds:    Option[List[UUID]],
  componentsCommitment: Option[Hash],
  provenance:           Option[OriginProvenance]
)
