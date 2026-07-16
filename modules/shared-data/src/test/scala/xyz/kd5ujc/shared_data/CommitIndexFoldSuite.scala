package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.schema.registry.RegistryName
import xyz.kd5ujc.schema.{AssetCommit, CalculatedState, CommitIndex, FiberCommit, OnChain}

import weaver.SimpleIOSuite

/**
 * `CommitIndex.fold` is the DL1's recreation of the cumulative commit maps from per-batch OnChain
 * v2 deltas (onchain-incrementals RFC §3.3). The genuinely load-bearing semantics:
 *   - upserts overwrite (a later touch of the same id wins — sequence bumps must land);
 *   - burns REMOVE, and burn-then-later-remint works (the maps are keyed state, not a log);
 *   - fold(fromCalculated(prev), delta) == fromCalculated(next) — the DL1's folded view must be
 *     extensionally identical to the ML0's CalculatedState-derived view, else the two gates drift.
 */
object CommitIndexFoldSuite extends SimpleIOSuite {

  private def uuid(i: Int): UUID = new UUID(0L, i.toLong)
  private def h(s:    String): Hash = Hash(s * 64 take 64)
  private def fc(seq: Long): FiberCommit = FiberCommit(h("a"), Some(h("b")), FiberOrdinal.unsafeApply(seq))
  private def ac(seq: Long): AssetCommit = AssetCommit(21, FiberOrdinal.unsafeApply(seq), h("c"), None)

  private val pkgName = RegistryName.unsafe("escrow.package")

  test("fold applies upserts, overwrites on re-touch, and removes burns") {
    val base = CommitIndex(
      fiberCommits = SortedMap(uuid(1) -> fc(1)),
      assetCommits = SortedMap(uuid(10) -> ac(1), uuid(11) -> ac(5)),
      registryCommits = SortedMap(pkgName -> h("d"))
    )
    val delta = OnChain.genesis.copy(
      touchedFiberCommits = SortedMap(uuid(1) -> fc(2), uuid(2) -> fc(0)), // bump + create
      touchedAssetCommits = SortedMap(uuid(12) -> ac(0)), // mint
      burnedAssets = SortedSet(uuid(10)) // burn
    )
    val next = CommitIndex.fold(base, delta)
    IO.pure(
      expect(next.fiberCommits(uuid(1)).sequenceNumber == FiberOrdinal.unsafeApply(2L)) and
      expect(next.fiberCommits.contains(uuid(2))) and
      expect(!next.assetCommits.contains(uuid(10))) and
      expect(next.assetCommits.contains(uuid(11)) && next.assetCommits.contains(uuid(12))) and
      expect(next.registryCommits == base.registryCommits)
    )
  }

  test("a same-batch touch+burn resolves to burned (writers keep the two disjoint; fold is defensive)") {
    val delta = OnChain.genesis.copy(
      touchedAssetCommits = SortedMap(uuid(10) -> ac(0)),
      burnedAssets = SortedSet(uuid(10))
    )
    IO.pure(expect(!CommitIndex.fold(CommitIndex.empty, delta).assetCommits.contains(uuid(10))))
  }

  test("folded DL1 view == ML0 fromCalculated view after the same writes") {
    // simulate what DataStateOps/AssetCombiner do: identical commits written to CalculatedState
    // (cumulative) and to the OnChain delta (touched) in the same fold
    val cs = CalculatedState.genesis.copy(
      fiberCommits = SortedMap(uuid(1) -> fc(3), uuid(2) -> fc(0)),
      assetCommits = SortedMap(uuid(12) -> ac(0)),
      registryCommits = SortedMap(pkgName -> h("d"))
    )
    val replayedDeltas = List(
      OnChain.genesis.copy(
        touchedFiberCommits = SortedMap(uuid(1) -> fc(1), uuid(2) -> fc(0)),
        touchedAssetCommits = SortedMap(uuid(10) -> ac(0)),
        touchedRegistryCommits = SortedMap(pkgName -> h("d"))
      ),
      OnChain.genesis.copy(
        touchedFiberCommits = SortedMap(uuid(1) -> fc(3)),
        touchedAssetCommits = SortedMap(uuid(12) -> ac(0)),
        burnedAssets = SortedSet(uuid(10))
      )
    )
    val folded = replayedDeltas.foldLeft(CommitIndex.empty)(CommitIndex.fold)
    IO.pure(expect(folded == CommitIndex.fromCalculated(cs)))
  }
}
