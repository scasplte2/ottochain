package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

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
 * Wire-size guard for OnChain v2 (docs/proposals/onchain-incrementals.md).
 *
 * tessellation caps the whole state-channel snapshot binary at 512,000 bytes
 * (`max-state-channel-snapshot-binary-size-in-bytes`, node-shared application.conf; enforced in
 * `CurrencySnapshotCreator.createProposalArtifact`). Under the v1 CUMULATIVE OnChain that cap was
 * an existential ceiling: at the marginal costs pinned here (226 B per FiberCommit entry) the
 * chain could never address more than ~2,265 fibers before `UnableToReduceProposalByCutting`
 * halted it permanently — the events-cutter cannot shed non-event state.
 *
 * OnChain v2 carries only per-batch `touched*` deltas, so wire size is O(batch churn), never
 * O(total state) — cumulative entity count CANNOT appear in the struct at all. Churn IS events,
 * so an oversized batch is gracefully shed by the cutter (data blocks deferred). These tests pin:
 *   1. the per-touched-entry marginal costs via the production serialization path
 *      (`CommittedOnChain[OnChain].toBinary`) — deterministic, so the bands are tight
 *      codec-bloat regression guards (a band break means the wire format grew/shrank and the
 *      RFC numbers need re-deriving);
 *   2. the O(churn) property: an empty delta is near-fixed-size, and even an absurdly large
 *      single-batch churn (1,000 touched fibers — far beyond a realistic batch) still fits the
 *      budget with room for the cutter to work.
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

  private def withTouchedFibers(n: Int): OnChain =
    OnChain.genesis.copy(touchedFiberCommits = SortedMap.from((0 until n).map { i =>
      uuid(i) -> FiberCommit(hex64(1, i), Some(hex64(2, i)), FiberOrdinal.unsafeApply(1000L + i))
    }))

  private def withTouchedAssets(n: Int): OnChain =
    OnChain.genesis.copy(touchedAssetCommits = SortedMap.from((0 until n).map { i =>
      uuid(i) -> AssetCommit(behavior = 21, FiberOrdinal.unsafeApply(1000L + i), hex64(3, i), origin = None)
    }))

  private def withTouchedRegistry(n: Int): OnChain =
    OnChain.genesis.copy(touchedRegistryCommits = SortedMap.from((0 until n).map { i =>
      RegistryName.unsafe(s"vendor$i.escrow.package") -> hex64(4, i)
    }))

  private def withBurns(n: Int): OnChain =
    OnChain.genesis.copy(burnedAssets = SortedSet.from((0 until n).map(uuid)))

  private def wireBytes(oc: OnChain): IO[Int] =
    CommittedOnChain(oc, breadcrumb).toBinary.map(_.length)

  // marginal cost measured over a 1000-entry span, offset past small-n JSON framing noise
  private def marginalPerEntry(build: Int => OnChain): IO[Double] =
    for {
      atSmall <- wireBytes(build(64))
      atBig   <- wireBytes(build(1064))
    } yield (atBig - atSmall).toDouble / 1000

  test("an empty delta (idle batch) is near-fixed-size — cumulative state cannot bloat the wire") {
    wireBytes(OnChain.genesis).map { fixed =>
      expect(fixed < 1000)
    }
  }

  test("touched-fiber-commit marginal wire cost stays in band") {
    for {
      perFiber <- marginalPerEntry(withTouchedFibers)
      _        <- IO.println(f"[wire-size probe] perTouchedFiberCommit=$perFiber%.1f B")
    } yield expect(perFiber >= 150 && perFiber <= 350)
  }

  test("touched-asset-commit marginal wire cost stays in band") {
    for {
      perAsset <- marginalPerEntry(withTouchedAssets)
      _        <- IO.println(f"[wire-size probe] perTouchedAssetCommit=$perAsset%.1f B")
    } yield expect(perAsset >= 100 && perAsset <= 300)
  }

  test("touched-registry-commit marginal wire cost stays in band") {
    for {
      perReg <- marginalPerEntry(withTouchedRegistry)
      _      <- IO.println(f"[wire-size probe] perTouchedRegistryCommit=$perReg%.1f B")
    } yield expect(perReg >= 80 && perReg <= 250)
  }

  test("burned-asset marginal wire cost stays in band (bare uuids)") {
    for {
      perBurn <- marginalPerEntry(withBurns)
      _       <- IO.println(f"[wire-size probe] perBurnedAsset=$perBurn%.1f B")
    } yield expect(perBurn >= 30 && perBurn <= 60)
  }

  test("O(churn): even an absurdly large single-batch churn fits the budget with cutter headroom") {
    // 1,000 touched fibers + 500 touched assets + 100 burns in ONE batch — far beyond any
    // realistic snapshot's churn (each touch is driven by an event in the same snapshot).
    val hugeChurn = OnChain.genesis.copy(
      touchedFiberCommits = withTouchedFibers(1000).touchedFiberCommits,
      touchedAssetCommits = withTouchedAssets(500).touchedAssetCommits,
      burnedAssets = withBurns(100).burnedAssets
    )
    wireBytes(hugeChurn).map { total =>
      val pct = 100.0 * total / TessellationBudgetBytes
      println(f"[wire-size probe] hugeChurn(1000 fibers, 500 assets, 100 burns)=$total B = $pct%.1f%% of budget")
      // ~60% of the budget (308,725 B measured) for a batch far beyond realistic churn — and
      // unlike v1 it does NOT compound: the next idle batch is back to fixed-size, and a batch
      // that DID overflow is churn the events-cutter can shed. 75% = headroom regression bound.
      expect(total < TessellationBudgetBytes * 3 / 4)
    }
  }
}
