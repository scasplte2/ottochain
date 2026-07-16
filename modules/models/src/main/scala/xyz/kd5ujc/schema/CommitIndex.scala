package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.SortedMap

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.registry.RegistryName

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * The recreated cumulative commit maps that the L1 structural gate reads (onchain-incrementals
 * RFC §3.3) — exactly the maps OnChain v1 used to carry on the wire.
 *
 * Provenance differs by node role:
 *   - ML0: `CommitIndex.fromCalculated(current.calculated)` — a cheap re-wrap of the DataState the
 *     framework already passes to `validateSignedUpdate`. Same triple, same freshness as v1.
 *   - DL1: maintained by `CommitIndexService` — `fold`ed from each contiguous snapshot's `touched*`
 *     delta, healed from ML0 (verified against the signed breadcrumb) on any ordinal gap.
 *
 * SECURITY NOTE (fail-open gate): `sequenceNumberMatches`/`cidNotUsed` treat a MISSING id as
 * pass-through — an incomplete index silently accepts what it should filter. Folding across an
 * ordinal gap can lose `touched*` writes, so `fold` must only ever be applied at
 * `index.ordinal + 1`; anything else requires a heal. The combiner remains the authoritative
 * stateful gate either way (invariant #2) — an index defect degrades spam filtering, never
 * consensus state.
 */
@derive(customizableDecoder, customizableEncoder)
case class CommitIndex(
  fiberCommits:    SortedMap[UUID, FiberCommit] = SortedMap.empty,
  assetCommits:    SortedMap[UUID, AssetCommit] = SortedMap.empty,
  registryCommits: SortedMap[RegistryName, Hash] = SortedMap.empty
)

object CommitIndex {

  val empty: CommitIndex = CommitIndex()

  /** ML0 view: the cumulative maps live in CalculatedState (rooted, not wire-shipped). */
  def fromCalculated(cs: CalculatedState): CommitIndex =
    CommitIndex(cs.fiberCommits, cs.assetCommits, cs.registryCommits)

  /**
   * Apply one snapshot's per-batch delta. ONLY valid for the immediately-next ordinal — the
   * caller (`CommitIndexService`) enforces contiguity; folding across a gap loses writes and the
   * gate fails open (see class doc).
   *
   * Order matters for a touch+burn in the same batch: upserts first, then burn removals win
   * (`DataStateOps`/`AssetCombiner` keep `touchedAssetCommits` and `burnedAssets` disjoint, but
   * the fold is defensive about it).
   */
  def fold(prev: CommitIndex, delta: OnChain): CommitIndex =
    CommitIndex(
      fiberCommits = prev.fiberCommits ++ delta.touchedFiberCommits,
      assetCommits = (prev.assetCommits ++ delta.touchedAssetCommits) -- delta.burnedAssets,
      registryCommits = prev.registryCommits ++ delta.touchedRegistryCommits
    )
}
