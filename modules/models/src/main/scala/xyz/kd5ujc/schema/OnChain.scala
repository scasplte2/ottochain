package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.SortedMap

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

@derive(customizableDecoder, customizableEncoder)
case class OnChain(
  fiberCommits:    SortedMap[UUID, FiberCommit],
  latestLogs:      SortedMap[UUID, List[FiberLogEntry]],
  registryCommits: SortedMap[RegistryName, Hash] = SortedMap.empty,
  assetCommits:    SortedMap[UUID, AssetCommit] = SortedMap.empty
) extends DataOnChainState

object OnChain {
  val genesis: OnChain = OnChain(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)
}
