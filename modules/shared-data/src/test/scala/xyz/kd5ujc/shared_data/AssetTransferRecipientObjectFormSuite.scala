package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation.EffectExtractor
import xyz.kd5ujc.shared_data.lifecycle.combine.CombineRejected
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * F2 — asset-effect ergonomics (docs/proposals/fiber-ergonomics/02-asset-effect-ergonomics.md §2): the
 * `_transferAsset` directive's `recipient` is the canonical [[AssetHolder]] OBJECT form ONLY
 * (`{"Fiber":{"fiberId":..}}` / `{"Wallet":{"address":..}}`), decoded strictly through the same magnolia
 * codec as every other holder surface. The legacy bare-string UUID/DAG-address form is GONE, and any
 * malformed recipient (or assetId) raises a graceful [[CombineRejected]] rather than being silently dropped
 * — a dropped transfer is a latent bug, not a no-op. Asserts at the
 * [[EffectExtractor.extractAssetTransfers]] boundary (which sees ALREADY-RESOLVED values; a DYNAMIC
 * `{"Fiber":{"fiberId":{"var":..}}}` is resolved by the prior effect eval, proven end-to-end by
 * `AssetFiberTransferSuite` + the e2e lanes) that:
 *   - object `{"Fiber":{"fiberId":<uuid>}}` ⇒ Fiber, `{"Wallet":{"address":<addr>}}` ⇒ Wallet,
 *   - a bare-string recipient is REJECTED (not silently honored under the old disambiguation),
 *   - a malformed object (unknown variant / missing field / bad address) is REJECTED (not dropped),
 *   - a non-UUID assetId is REJECTED (not dropped).
 */
object AssetTransferRecipientObjectFormSuite extends SimpleIOSuite {

  import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._

  // Drive EffectExtractor.extractAssetTransfers in the same FiberT MTL stack the engine uses.
  private def runExtract(
    effectResult: JsonLogicValue,
    ctx:          JsonLogicValue = MapValue(Map.empty)
  ): IO[List[FiberEffect.AssetTransferred]] = {
    val prog: FiberT[IO, List[FiberEffect.AssetTransferred]] =
      EffectExtractor.extractAssetTransfers[IO, FiberT[IO, *]](effectResult, ctx)
    prog
      .run(
        FiberContext(
          SnapshotOrdinal.MinValue,
          Hash.empty,
          io.constellationnetwork.schema.epoch
            .EpochProgress(eu.timepit.refined.types.numeric.NonNegLong.unsafeFrom(0L)),
          ExecutionLimits(),
          io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig.Default,
          FiberGasConfig.Default
        )
      )
      .runA(ExecutionState.initial)
  }

  // Build a single-directive `_transferAsset` effect with the given assetId + recipient JsonLogicValue.
  private def transferEffect(assetId: JsonLogicValue, recipient: JsonLogicValue): JsonLogicValue =
    MapValue(
      Map(
        ReservedKeys.TRANSFER_ASSET -> ArrayValue(
          List(MapValue(Map(ReservedKeys.ASSET_ID -> assetId, ReservedKeys.RECIPIENT -> recipient)))
        )
      )
    )

  private def fiberObj(id: UUID): JsonLogicValue =
    MapValue(Map("Fiber" -> MapValue(Map("fiberId" -> StrValue(id.toString)))))

  private def walletObj(addr: Address): JsonLogicValue =
    MapValue(Map("Wallet" -> MapValue(Map("address" -> StrValue(addr.value.value)))))

  // The IO raises CombineRejected on any malformation; assert it failed with exactly that.
  private def isRejected(io: IO[List[FiberEffect.AssetTransferred]]): IO[Boolean] =
    io.attempt.map(_.left.toOption.exists(_.isInstanceOf[CombineRejected]))

  // ── the canonical AssetHolder object form is accepted ───────────────────────────────────────────

  test("object form {\"Fiber\":{\"fiberId\":<uuid>}} ⇒ AssetHolder.Fiber") {
    val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000f3")
    val fiberRecipient = UUID.fromString("acc70000-0000-4000-8000-0000000000f3")
    runExtract(transferEffect(StrValue(assetId.toString), fiberObj(fiberRecipient))).map { transfers =>
      expect(transfers == List(FiberEffect.AssetTransferred(assetId, AssetHolder.Fiber(fiberRecipient))))
    }
  }

  test("object form {\"Wallet\":{\"address\":<dag-addr>}} ⇒ AssetHolder.Wallet") {
    TestFixture.resource(Set(Bob)).use { fixture =>
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000f4")
      val bobAddr: Address = fixture.registry.addresses(Bob)
      runExtract(transferEffect(StrValue(assetId.toString), walletObj(bobAddr))).map { transfers =>
        expect(transfers == List(FiberEffect.AssetTransferred(assetId, AssetHolder.Wallet(bobAddr))))
      }
    }
  }

  // ── the legacy bare-string form is now REJECTED, not silently honored ───────────────────────────

  test("bare-string recipient is REJECTED (CombineRejected), not honored") {
    val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000f1")
    val fiberRecipient = UUID.fromString("acc70000-0000-4000-8000-0000000000f1")
    isRejected(runExtract(transferEffect(StrValue(assetId.toString), StrValue(fiberRecipient.toString)))).map(expect(_))
  }

  // ── malformed recipients are REJECTED loudly, never dropped ─────────────────────────────────────

  test("malformed object recipients are REJECTED (no silent drop)") {
    val assetId = StrValue(UUID.fromString("a55e7000-0000-4000-8000-0000000000f5").toString)
    val bothVariant =
      MapValue(Map("Both" -> MapValue(Map("fiberId" -> StrValue("acc70000-0000-4000-8000-0000000000a1")))))
    val badFiberField = MapValue(Map("Fiber" -> MapValue(Map("nope" -> StrValue("x")))))
    val badWalletAddr = MapValue(Map("Wallet" -> MapValue(Map("address" -> StrValue("not-a-dag-address")))))
    for {
      a <- isRejected(runExtract(transferEffect(assetId, bothVariant)))
      b <- isRejected(runExtract(transferEffect(assetId, badFiberField)))
      c <- isRejected(runExtract(transferEffect(assetId, badWalletAddr)))
    } yield expect(a) and expect(b) and expect(c)
  }

  // ── a non-UUID assetId is REJECTED loudly ───────────────────────────────────────────────────────

  test("non-UUID assetId is REJECTED (no silent drop)") {
    val fiberRecipient = UUID.fromString("acc70000-0000-4000-8000-0000000000f7")
    isRejected(runExtract(transferEffect(StrValue("not-a-uuid"), fiberObj(fiberRecipient)))).map(expect(_))
  }
}
