package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataOnChainState
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal}
import xyz.kd5ujc.schema.registry.RegistryName

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableDecoder, customizableEncoder)
case class FiberCommit(
  recordHash:     Hash,
  stateDataHash:  Option[Hash],
  sequenceNumber: FiberOrdinal
)

/**
 * The on-chain L1 fast-path commit for an asset instance (asset-model.md §6). It exposes a *safe* subset of
 * the asset's state — the packed 5-bit [[xyz.kd5ujc.schema.asset.TokenBehavior]] bitmask plus a sequence
 * number — so the L1 structural gate can reject geometrically-impossible morphisms (Transfer on a soulbound
 * asset, Fractionalize on an indivisible one) without a `CalculatedState` round-trip.
 *
 * `behavior` is ADVISORY and inherently stale (the L1 sequence comparison is batching-tolerant); the
 * combiner re-derives composite behavior from `CalculatedState.assets(...).behavior`, NEVER from these bits.
 * `origin` is the Phase-6 interop double-wrap fast-reject discriminator (D2 forward-ref); `= None` default is
 * fine here because `OnChain` is server-derived STATE, not a signed message (invariant #1 governs only
 * signed messages).
 */
@derive(customizableDecoder, customizableEncoder)
case class AssetCommit(
  behavior:       Int,
  sequenceNumber: FiberOrdinal,
  recordHash:     Hash,
  origin:         Option[Hash] = None
)

/**
 * OnChain v2 (docs/proposals/onchain-incrementals.md): PER-BATCH deltas only, never cumulative.
 *
 * tessellation serializes this struct into `dataApplication.onChainState` of EVERY incremental
 * snapshot, and the whole snapshot binary is hard-capped at 512,000 bytes
 * (`max-state-channel-snapshot-binary-size-in-bytes`; enforced in `CurrencySnapshotCreator`).
 * The v1 cumulative maps ran into that cap at ~2,265 fibers (measured, `OnChainWireSizeSuite`)
 * with a PERMANENT-HALT failure mode (`UnableToReduceProposalByCutting` — the events-cutter
 * cannot shed non-event state). v2 keeps snapshot bytes O(batch churn): churn IS events, so an
 * oversized batch degrades gracefully (data blocks deferred to the next snapshot).
 *
 * The cumulative maps now live in `CalculatedState.fiberCommits/assetCommits/registryCommits`
 * (rooted under the committed MPT as `commit/f|a|r/<id>`, never wire-shipped). Every `touched*`
 * write goes to BOTH (same `DataStateOps` fold, same hash) — the delta here is exactly what a
 * DL1 `CommitIndex` folds to recreate the full maps (see `CommitIndex.fold`).
 *
 * `touched*` maps use the SAME clear-then-accumulate mechanism as `latestLogs`: cleared at the
 * top of `orderedCombiner`'s fold, repopulated by this batch's writes only.
 */
@derive(customizableDecoder, customizableEncoder)
case class OnChain(
  touchedFiberCommits:    SortedMap[UUID, FiberCommit],
  latestLogs:             SortedMap[UUID, List[FiberLogEntry]],
  touchedRegistryCommits: SortedMap[RegistryName, Hash] = SortedMap.empty,
  touchedAssetCommits:    SortedMap[UUID, AssetCommit] = SortedMap.empty,
  // burns are removals — they cannot ride an upsert map (asset-model §5d Burn morphism)
  burnedAssets: SortedSet[UUID] = SortedSet.empty
) extends DataOnChainState

object OnChain {
  val genesis: OnChain = OnChain(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedSet.empty)
}
