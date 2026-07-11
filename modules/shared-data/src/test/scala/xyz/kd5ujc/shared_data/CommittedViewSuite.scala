package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset.{AssetHolder, TokenBehavior}
import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.schema.registry.{RegistryEntry, RegistryName, RegistryTarget, SchemaBinding, SemVer}

import weaver.SimpleIOSuite

/**
 * The committed-state projection of CalculatedState (Phase 1 of the committed-state migration). The
 * genuinely load-bearing logic is the registry key derivation: CommitKey segments are <= 64 chars
 * and lowercase, but a RegistryName renders up to 253 — and `entries` is TOTAL (no error channel),
 * so an over-long name must fall back to a hashed key rather than throw inside combine.
 */
object CommittedViewSuite extends SimpleIOSuite {

  private def entry(name: RegistryName): RegistryEntry =
    RegistryEntry(name, Set.empty, RegistryTarget.InstanceAlias(UUID.randomUUID()))

  private val escrow = RegistryName.unsafe("escrow.package")
  // render = 60 + 1 + 10 + 1 + 7 = 79 chars -> overflows a 64-char CommitKey segment
  private val longName = RegistryName.unsafe(("a" * 60) + "." + ("b" * 10) + ".package")
  private val rid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

  private val state = CalculatedState(
    stateMachines = SortedMap.empty,
    scripts = SortedMap.empty,
    registry = SortedMap(escrow -> entry(escrow), longName -> entry(longName)),
    reverseNames = SortedMap(rid -> escrow)
  )

  test("registry uses readable keys, an over-long name falls back to a hashed key, reverse keys by uuid") {
    val entries = CalculatedState.committedView.entries(state)
    val keys = entries.keySet.map(_.value)
    IO.pure(
      expect(keys.contains("registry/escrow.package")) and
      expect(keys.exists(k => k.startsWith("registry/h/") && k.stripPrefix("registry/h/").matches("[0-9a-f]{64}"))) and
      expect(keys.contains(s"reverse/$rid")) and
      // every populated field projected, nothing silently dropped
      expect(entries.size == state.registry.size + state.reverseNames.size) and
      // deterministic across calls
      expect(CalculatedState.committedView.entries(state) == entries)
    )
  }

  test("entries is total: a maximal-length registry name projects without throwing") {
    val maxName = RegistryName.unsafe((1 to 3).map(_ => "a" * 60).mkString(".") + ".package") // ~190 chars
    val s = CalculatedState(SortedMap.empty, SortedMap.empty, SortedMap(maxName -> entry(maxName)), SortedMap.empty)
    IO(CalculatedState.committedView.entries(s)).map(e => expect(e.size == 1))
  }

  test("genesis projects to an empty dictionary") {
    IO.pure(expect(CalculatedState.committedView.entries(CalculatedState.genesis).isEmpty))
  }

  // ── asset/nonce projections (Phase 2) — TOTAL keys per asset instance and per used-nonce entry ──

  private val assetId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

  private val assetRecord = AssetRecord(
    assetId = assetId,
    schemaBinding = SchemaBinding(RegistryName.unsafe("gold.asset"), SemVer(1, 0, 0), Hash("sh"), Hash("lh")),
    behavior = TokenBehavior.Fungible,
    holder = AssetHolder.Fiber(UUID.fromString("00000000-0000-0000-0000-0000000000cc")),
    amount = 100L,
    sequenceNumber = FiberOrdinal.MinValue,
    creationOrdinal = SnapshotOrdinal.MinValue,
    latestUpdateOrdinal = SnapshotOrdinal.MinValue
  )

  test("entries projects asset/<uuid> and nonce/<uuid> keys totally (an empty nonce set is fine)") {
    val s = CalculatedState.genesis.copy(
      assets = SortedMap(assetId -> assetRecord),
      usedNonces = SortedMap(assetId -> SortedSet(5L, 2L, 9L), rid -> SortedSet.empty[Long])
    )
    val keys = CalculatedState.committedView.entries(s).keySet.map(_.value)
    IO.pure(
      expect(keys.contains(s"asset/$assetId")) and
      expect(keys.contains(s"nonce/$assetId")) and
      expect(keys.contains(s"nonce/$rid")) and // empty SortedSet still projects (no throw, no drop)
      // total: one key per asset + one per used-nonce entry
      expect(CalculatedState.committedView.entries(s).size == s.assets.size + s.usedNonces.size)
    )
  }

  // ── commit/ projections (onchain-incrementals RFC §3.1) — the provable DL1 heal namespace ──

  test("entries projects commit/f|a|r keys; over-long registry names take the hashed fallback") {
    import xyz.kd5ujc.schema.{AssetCommit, FiberCommit}
    import xyz.kd5ujc.schema.fiber.FiberOrdinal

    val s = CalculatedState.genesis.copy(
      fiberCommits = SortedMap(rid -> FiberCommit(Hash("rh"), Some(Hash("sh")), FiberOrdinal.MinValue)),
      assetCommits = SortedMap(assetId -> AssetCommit(21, FiberOrdinal.MinValue, Hash("ah"))),
      registryCommits = SortedMap(escrow -> Hash("eh"), longName -> Hash("lh"))
    )
    val keys = CalculatedState.committedView.entries(s).keySet.map(_.value)
    IO.pure(
      expect(keys.contains(s"commit/f/$rid")) and
      expect(keys.contains(s"commit/a/$assetId")) and
      expect(keys.contains("commit/r/escrow.package")) and
      // key derivation stays TOTAL: the 79-char name overflows a segment -> hashed fallback
      expect(keys.exists(k => k.startsWith("commit/r/h/") && k.stripPrefix("commit/r/h/").matches("[0-9a-f]{64}"))) and
      // one leaf per commit entry, nothing dropped
      expect(CalculatedState.committedView.entries(s).size == 1 + 1 + 2)
    )
  }
}
