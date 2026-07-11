package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.crypto.smt.SparseMerkleRoot
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CommittedBreadcrumb, CommittedOnChain, CommittedRoots}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryCodec._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.schema.registry.RegistryName
import xyz.kd5ujc.schema.{AssetCommit, FiberCommit, OnChain}

import weaver.SimpleIOSuite

/**
 * Phase-0 probe for the onchain-incrementals migration (docs/proposals/onchain-incrementals.md).
 *
 * tessellation caps the whole state-channel snapshot binary at 512,000 bytes
 * (`max-state-channel-snapshot-binary-size-in-bytes`, node-shared application.conf; enforced in
 * `CurrencySnapshotCreator.createProposalArtifact`). The cumulative `OnChain` maps ride inside
 * `dataApplication.onChainState` of EVERY incremental snapshot, and the framework's events-cutter
 * cannot shrink them — once the cumulative maps alone exceed the budget, the metagraph can no
 * longer produce any snapshot (`UnableToReduceProposalByCutting`): a permanent halt.
 *
 * These tests pin the marginal wire cost of each cumulative entry via the production
 * serialization path (`CommittedOnChain[OnChain].toBinary`, the exact bytes embedded in the
 * snapshot). Serialization is deterministic, so the bands are tight regression guards: if one
 * breaks, a codec change either bloated the wire format (burning ceiling headroom) or shrank it —
 * either way the RFC numbers and this suite must be re-derived together. When OnChain v2 lands
 * (per-batch deltas), the ceiling test below is expected to become obsolete and should be
 * replaced by an O(churn) assertion.
 */
object OnChainWireSizeSuite extends SimpleIOSuite {

  private val TessellationBudgetBytes = 512000

  // constant-size breadcrumb, same shape makeL0 wraps around OnChain (value irrelevant to marginals)
  private val breadcrumb =
    CommittedBreadcrumb(SnapshotOrdinal.MinValue, CommittedRoots(Hash.empty, SparseMerkleRoot.empty))

  // deterministic 64-hex digests / uuids so every run serializes byte-identically
  private def hex64(salt: Int, i: Int): Hash = {
    val h = (BigInt(salt) * 1000003 + BigInt(i)).toString(16)
    Hash(("0" * (64 - h.length)) + h)
  }

  private def uuid(i: Int): UUID = new UUID(0L, i.toLong)

  private def withFibers(n: Int): OnChain =
    OnChain.genesis.copy(fiberCommits = SortedMap.from((0 until n).map { i =>
      uuid(i) -> FiberCommit(hex64(1, i), Some(hex64(2, i)), FiberOrdinal.unsafeApply(1000L + i))
    }))

  private def withAssets(n: Int): OnChain =
    OnChain.genesis.copy(assetCommits = SortedMap.from((0 until n).map { i =>
      uuid(i) -> AssetCommit(behavior = 21, FiberOrdinal.unsafeApply(1000L + i), hex64(3, i), origin = None)
    }))

  private def withRegistry(n: Int): OnChain =
    OnChain.genesis.copy(registryCommits = SortedMap.from((0 until n).map { i =>
      RegistryName.unsafe(s"vendor$i.escrow.package") -> hex64(4, i)
    }))

  private def wireBytes(oc: OnChain): IO[Int] =
    CommittedOnChain(oc, breadcrumb).toBinary.map(_.length)

  // marginal cost measured over a 1000-entry span, offset past small-n JSON framing noise
  private def marginalPerEntry(build: Int => OnChain): IO[Double] =
    for {
      atSmall <- wireBytes(build(64))
      atBig   <- wireBytes(build(1064))
    } yield (atBig - atSmall).toDouble / 1000

  test("fixed overhead (empty OnChain + breadcrumb) is negligible against the budget") {
    wireBytes(OnChain.genesis).map { fixed =>
      expect(fixed < 1000)
    }
  }

  test("fiber-commit marginal wire cost stays in band; cumulative ceiling is O(low thousands)") {
    for {
      perFiber <- marginalPerEntry(withFibers)
      ceiling = (TessellationBudgetBytes / perFiber).toInt
      _ <- IO.println(
        f"[wire-size probe] perFiberCommit=$perFiber%.1f B → fiber-only ceiling ≈ $ceiling entries under $TessellationBudgetBytes B cap"
      )
    } yield expect(perFiber >= 150 && perFiber <= 350) and
    // the motivating fact for OnChain v2: the cumulative wire format cannot address
    // more than a few thousand fibers before the chain can no longer snapshot
    expect(ceiling < 5000)
  }

  test("asset-commit marginal wire cost stays in band") {
    for {
      perAsset <- marginalPerEntry(withAssets)
      _        <- IO.println(f"[wire-size probe] perAssetCommit=$perAsset%.1f B")
    } yield expect(perAsset >= 100 && perAsset <= 300)
  }

  test("registry-commit marginal wire cost stays in band") {
    for {
      perReg <- marginalPerEntry(withRegistry)
      _      <- IO.println(f"[wire-size probe] perRegistryCommit=$perReg%.1f B")
    } yield expect(perReg >= 80 && perReg <= 250)
  }

  test("a modest mixed economy already consumes a large fraction of the snapshot budget") {
    // 1500 fibers + 750 assets + 50 registry entries — a small production economy, far from web-scale
    val mixed = OnChain.genesis.copy(
      fiberCommits = withFibers(1500).fiberCommits,
      assetCommits = withAssets(750).assetCommits,
      registryCommits = withRegistry(50).registryCommits
    )
    wireBytes(mixed).map { total =>
      val pct = 100.0 * total / TessellationBudgetBytes
      println(f"[wire-size probe] mixed(1500 fibers, 750 assets, 50 registry)=$total B = $pct%.1f%% of budget")
      // documents that the ceiling binds well before any interesting scale
      expect(total > TessellationBudgetBytes / 2)
    }
  }
}
