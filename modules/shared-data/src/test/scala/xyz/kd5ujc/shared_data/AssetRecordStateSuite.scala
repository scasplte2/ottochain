package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset.{AssetHolder, OriginProvenance, TokenBehavior}
import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.schema.registry.{RegistryName, SchemaBinding, SemVer}

import eu.timepit.refined.refineV
import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * Phase 2 of the asset model (docs/proposals/asset-model.md §5b/§5c): the asset INSTANCE + IDENTITY +
 * COMMITTED-STATE layer. This suite proves
 *   (a) [[AssetRecord]] / [[AssetHolder]] / [[OriginProvenance]] round-trip through circe,
 *   (b) `CalculatedState.assets` / `usedNonces` carry the new records and round-trip, and
 *   (c) the `committedView` projection enumerates TOTAL `asset/<uuid>` / `nonce/<uuid>` keys — every value
 *       projects deterministically and never throws (a non-total key would halt consensus in combine).
 */
object AssetRecordStateSuite extends SimpleIOSuite {

  private val ord = SnapshotOrdinal.MinValue

  private val assetA = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
  private val assetB = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
  private val fiberHolder = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
  private val componentX = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
  private val componentY = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

  private val walletAddr: Address =
    refineV[DAGAddressRefined].apply[String]("DAG2BAUcXKujRhzk4XZ6RDYL2ifXWMgfw1v7YxZu") match {
      case Right(v) => Address(v)
      case Left(e)  => sys.error(s"bad test address: $e")
    }

  private val binding: SchemaBinding =
    SchemaBinding(RegistryName.unsafe("gold.asset"), SemVer(1, 0, 0), Hash("schema-1.0.0"), Hash("logic-1.0.0"))

  private val provenance: OriginProvenance =
    OriginProvenance(
      originChainId = "eip155:1",
      originAssetRef = "0xA0b8...eB48",
      fullPath = List("ottochain-bridge-hop", "eip155:1-lock"),
      attestationHash = Hash("attestation")
    )

  // A native (no-provenance) asset held by a wallet.
  private val recordWallet: AssetRecord =
    AssetRecord(
      assetId = assetA,
      schemaBinding = binding,
      behavior = TokenBehavior.Fungible,
      holder = AssetHolder.Wallet(walletAddr),
      amount = 1000L,
      sequenceNumber = FiberOrdinal.MinValue,
      creationOrdinal = ord,
      latestUpdateOrdinal = ord
    )

  // A composite asset held by a fiber, with provenance and component refs.
  private val recordFiber: AssetRecord =
    AssetRecord(
      assetId = assetB,
      schemaBinding = binding,
      behavior = TokenBehavior.NFT,
      holder = AssetHolder.Fiber(fiberHolder),
      amount = 1L,
      sequenceNumber = FiberOrdinal.unsafeApply(3L),
      creationOrdinal = ord,
      latestUpdateOrdinal = ord,
      expiresAt = Some(ord),
      componentFiberIds = Some(List(componentX, componentY)),
      parentCompositeId = Some(assetA),
      provenance = Some(provenance)
    )

  // ── (a) value-type codec round-trips ──────────────────────────────────────────────────────────

  test("AssetHolder round-trips for both variants (Wallet / Fiber)") {
    val w: AssetHolder = AssetHolder.Wallet(walletAddr)
    val f: AssetHolder = AssetHolder.Fiber(fiberHolder)
    IO.pure(
      expect(decode[AssetHolder](w.asJson.noSpaces) == Right(w)) and
      expect(decode[AssetHolder](f.asJson.noSpaces) == Right(f))
    )
  }

  test("OriginProvenance round-trips") {
    IO.pure(expect(decode[OriginProvenance](provenance.asJson.noSpaces) == Right(provenance)))
  }

  test("AssetRecord round-trips (wallet-held native, and fiber-held composite with provenance)") {
    IO.pure(
      expect(decode[AssetRecord](recordWallet.asJson.noSpaces) == Right(recordWallet)) and
      expect(decode[AssetRecord](recordFiber.asJson.noSpaces) == Right(recordFiber))
    )
  }

  test("AssetRecord.behavior wire form is the packed Int (TokenBehavior codec)") {
    val behaviorJson = recordWallet.asJson.hcursor.downField("behavior").as[Int]
    IO.pure(expect(behaviorJson == Right(TokenBehavior.Fungible.bits)))
  }

  // ── (b) CalculatedState carries the new state ─────────────────────────────────────────────────

  test("CalculatedState carrying assets + usedNonces round-trips") {
    val cs = CalculatedState.genesis.copy(
      assets = SortedMap(assetA -> recordWallet, assetB -> recordFiber),
      usedNonces = SortedMap(assetA -> SortedSet(1L, 7L, 42L))
    )
    val json = cs.asJson.noSpaces
    IO.pure(expect(decode[CalculatedState](json) == Right(cs)))
  }

  // ── (c) the committed-view projection: TOTAL asset/ + nonce/ keys ─────────────────────────────

  test("committedView enumerates asset/<uuid> and nonce/<uuid> keys, one per entry") {
    val cs = CalculatedState.genesis.copy(
      assets = SortedMap(assetA -> recordWallet, assetB -> recordFiber),
      usedNonces = SortedMap(assetA -> SortedSet(3L, 1L, 2L), assetB -> SortedSet.empty[Long])
    )
    val entries = CalculatedState.committedView.entries(cs)
    val keys = entries.keySet.map(_.value)
    IO.pure(
      expect(keys.contains(s"asset/$assetA")) and
      expect(keys.contains(s"asset/$assetB")) and
      expect(keys.contains(s"nonce/$assetA")) and
      expect(keys.contains(s"nonce/$assetB")) and
      // one key per asset + one per used-nonce entry, nothing silently dropped
      expect(entries.size == cs.assets.size + cs.usedNonces.size) and
      // the asset value is the record's JSON
      expect(
        entries
          .get(io.constellationnetwork.metagraph_sdk.lifecycle.committed.CommitKey.unsafe(s"asset/$assetA"))
          .contains(recordWallet.asJson)
      ) and
      // the nonce value is a JSON array of the SORTED elements (deterministic total encoding)
      expect(
        entries
          .get(io.constellationnetwork.metagraph_sdk.lifecycle.committed.CommitKey.unsafe(s"nonce/$assetA"))
          .contains(io.circe.Json.fromValues(List(1L, 2L, 3L).map(io.circe.Json.fromLong)))
      )
    )
  }

  test("committedView is total and deterministic over assets / usedNonces (no throw, stable across calls)") {
    val cs = CalculatedState.genesis.copy(
      assets = SortedMap(assetA -> recordWallet, assetB -> recordFiber),
      usedNonces = SortedMap(assetA -> SortedSet((1L to 50L): _*))
    )
    IO(CalculatedState.committedView.entries(cs)).map { e =>
      expect(e.size == 3) and
      expect(CalculatedState.committedView.entries(cs) == e)
    }
  }

  test("genesis still projects to an empty dictionary (no asset/nonce keys for empty state)") {
    IO.pure(expect(CalculatedState.committedView.entries(CalculatedState.genesis).isEmpty))
  }
}
