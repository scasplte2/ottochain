package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset.{AssetHolder, TokenBehavior}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry.{RegistryName, SchemaBinding, SemVer}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation.EffectExtractor
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.lifecycle.combine.CombineRejected
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Phase 5 of the asset model (docs/proposals/asset-model.md §9/§10): the `_transferAsset` /
 * `FiberEffect.AssetTransferred` return channel wired END-TO-END, the `heldAssets` context, and the
 * fiber-held holder-ownership defense (R1) + no-reentrancy bound (R20). Proves:
 *   (1) ESCROW E2E — a fiber-held asset moves to a wallet when a transition emits `_transferAsset` (the
 *       channel works; without it the effect would be silently dropped),
 *   (2) HOLDER DEFENSE (R1) — a fiber that emits `_transferAsset` for an asset it does NOT hold is
 *       gracefully rejected (RejectionReceipt) and the asset is unchanged,
 *   (3) heldAssets — a transition guard that reads `heldAssets.<id>.amount` evaluates correctly,
 *   (4) EffectExtractor — `_transferAsset` (object-form recipient) parses into `AssetTransferred` with gas
 *       charged; a malformed directive is REJECTED (graceful CombineRejected), never silently dropped.
 */
object AssetFiberTransferSuite extends SimpleIOSuite {

  private val binding: SchemaBinding =
    SchemaBinding(RegistryName.unsafe("gold.asset"), SemVer(1, 0, 0), Hash("schema-1.0.0"), Hash("logic-1.0.0"))

  private def heldAsset(
    assetId:  UUID,
    holder:   UUID,
    behavior: TokenBehavior = TokenBehavior.Fungible,
    amount:   Long = 100L,
    ordinal:  SnapshotOrdinal
  ): AssetRecord =
    AssetRecord(
      assetId = assetId,
      schemaBinding = binding,
      behavior = behavior,
      holder = AssetHolder.Fiber(holder),
      amount = amount,
      sequenceNumber = FiberOrdinal.MinValue,
      creationOrdinal = ordinal,
      latestUpdateOrdinal = ordinal
    )

  // Object-form AssetHolder recipient literals for embedding in a `_transferAsset` effect — the recipient is
  // the canonical `{"Fiber":{"fiberId":..}}` / `{"Wallet":{"address":..}}` form ONLY (no bare strings).
  private def fiberHolderJson(fiberId:  String): String = s"""{ "Fiber": { "fiberId": "$fiberId" } }"""
  private def walletHolderJson(address: String): String = s"""{ "Wallet": { "address": "$address" } }"""

  // An escrow state machine: HOLDING -> RELEASED on a "release" event whose effect emits _transferAsset.
  // `$assetId` is the held asset id; `recipientJson` is the destination AssetHolder OBJECT literal
  // (fiberHolderJson(..) / walletHolderJson(..)).
  private def escrowJson(assetId: UUID, recipientJson: String): String =
    s"""
    {
      "states": {
        "HOLDING":  { "id": "HOLDING",  "isFinal": false },
        "RELEASED": { "id": "RELEASED", "isFinal": true }
      },
      "initialState": "HOLDING",
      "transitions": [
        {
          "from": "HOLDING",
          "to": "RELEASED",
          "eventName": "release",
          "guard": true,
          "effect": {
            "_transferAsset": [
              { "assetId": "$assetId", "recipient": $recipientJson }
            ],
            "released": true
          },
          "dependencies": []
        }
      ]
    }
    """

  private def buildEscrow(escrowId: UUID, json: String, ordinal: SnapshotOrdinal): IO[Records.StateMachineFiberRecord] =
    for {
      definition <- IO.fromEither(decode[StateMachineDefinition](json))
      stateData = MapValue(Map("released" -> BoolValue(false)))
      hash <- (stateData: JsonLogicValue).computeDigest
    } yield Records.StateMachineFiberRecord(
      fiberId = escrowId,
      creationOrdinal = ordinal,
      previousUpdateOrdinal = ordinal,
      latestUpdateOrdinal = ordinal,
      definition = definition,
      currentState = StateId("HOLDING"),
      stateData = stateData,
      stateDataHash = hash,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = Set.empty,
      status = FiberStatus.Active
    )

  private def rejectionReasons(state: DataState[OnChain, CalculatedState]): List[String] =
    state.onChain.latestLogs.values.flatten.collect { case r: FiberLogEntry.RejectionReceipt => r.reason }.toList

  // ── (1) ESCROW END-TO-END: the channel moves the asset ──────────────────────────────────────────

  test("escrow E2E: a fiber-held asset moves to a wallet when the release transition emits _transferAsset") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("e5c40000-0000-4000-8000-000000000001")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000001")
      val bobAddr: Address = fixture.registry.addresses(Bob)
      val bobStr = bobAddr.value.value

      for {
        escrow <- buildEscrow(escrowId, escrowJson(assetId, walletHolderJson(bobStr)), fixture.ordinal)
        asset = heldAsset(assetId, escrowId, ordinal = fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow),
            assets = SortedMap(assetId -> asset)
          )
        )
        release = Updates.TransitionStateMachine(escrowId, "release", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(release, Set(Alice))
        after <- combiner.insert(genesis, Signed(release, pr))

        moved = after.calculated.assets.get(assetId)
        escrowAfter = after.calculated.stateMachines.get(escrowId)
      } yield expect(rejectionReasons(after).isEmpty) and
      // the channel works: holder is now Bob's wallet, sequence bumped, commit updated
      expect(moved.map(_.holder).contains(AssetHolder.Wallet(bobAddr))) and
      expect(moved.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(after.onChain.assetCommits.get(assetId).map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      // the escrow advanced to RELEASED
      expect(escrowAfter.map(_.currentState).contains(StateId("RELEASED")))
    }
  }

  test("escrow E2E: a fiber-held asset can be transferred to ANOTHER live fiber (custody hand-off)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("e5c40000-0000-4000-8000-000000000010")
      val vaultId = UUID.fromString("acc70000-0000-4000-8000-000000000010")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000010")

      for {
        escrow <- buildEscrow(escrowId, escrowJson(assetId, fiberHolderJson(vaultId.toString)), fixture.ordinal)
        // the vault never releases, so its recipient is never evaluated — any valid object literal suffices
        vault <- buildEscrow(vaultId, escrowJson(assetId, fiberHolderJson(vaultId.toString)), fixture.ordinal)
        asset = heldAsset(assetId, escrowId, ordinal = fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow, vaultId -> vault),
            assets = SortedMap(assetId -> asset)
          )
        )
        release = Updates.TransitionStateMachine(escrowId, "release", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(release, Set(Alice))
        after <- combiner.insert(genesis, Signed(release, pr))
        moved = after.calculated.assets.get(assetId)
      } yield expect(rejectionReasons(after).isEmpty) and
      expect(moved.map(_.holder).contains(AssetHolder.Fiber(vaultId)))
    }
  }

  // ── (2) HOLDER DEFENSE (R1): a fiber cannot transfer an asset it does NOT hold ──────────────────

  test("holder defense (R1): a fiber emitting _transferAsset for an asset it does NOT hold is rejected") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val attackerId = UUID.fromString("a77ac000-0000-4000-8000-000000000001")
      val ownerId = UUID.fromString("0w4e7000-0000-4000-8000-000000000001".replace('w', 'a').replace('e', 'b'))
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000002")
      val bobStr = fixture.registry.addresses(Bob).value.value

      for {
        // the attacker fiber's transition tries to transfer an asset HELD BY ownerId
        attacker <- buildEscrow(attackerId, escrowJson(assetId, walletHolderJson(bobStr)), fixture.ordinal)
        asset = heldAsset(assetId, ownerId, ordinal = fixture.ordinal) // held by ownerId, NOT attackerId
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(attackerId -> attacker),
            assets = SortedMap(assetId -> asset)
          )
        )
        release = Updates.TransitionStateMachine(attackerId, "release", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(release, Set(Alice))
        after <- combiner.insert(genesis, Signed(release, pr))

        assetAfter = after.calculated.assets.get(assetId)
        reasons = rejectionReasons(after)
      } yield expect(reasons.exists(_.contains("does not hold asset"))) and
      // the asset is UNCHANGED (still held by ownerId, original sequence) — all-or-nothing
      expect(assetAfter.map(_.holder).contains(AssetHolder.Fiber(ownerId))) and
      expect(assetAfter.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and
      // the attacker fiber's state mutation is discarded too (graceful reject of the whole update)
      expect(after.calculated.stateMachines.get(attackerId).map(_.currentState).contains(StateId("HOLDING")))
    }
  }

  test("holder defense: a non-transferable (soulbound) held asset cannot be transferred out") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("e5c40000-0000-4000-8000-000000000003")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000003")
      val bobStr = fixture.registry.addresses(Bob).value.value

      for {
        escrow <- buildEscrow(escrowId, escrowJson(assetId, walletHolderJson(bobStr)), fixture.ordinal)
        asset = heldAsset(assetId, escrowId, behavior = TokenBehavior.Soulbound, ordinal = fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow),
            assets = SortedMap(assetId -> asset)
          )
        )
        release = Updates.TransitionStateMachine(escrowId, "release", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(release, Set(Alice))
        after <- combiner.insert(genesis, Signed(release, pr))
      } yield expect(rejectionReasons(after).exists(_.contains("not transferable"))) and
      expect(after.calculated.assets.get(assetId).map(_.holder).contains(AssetHolder.Fiber(escrowId)))
    }
  }

  // ── (3) heldAssets context: a guard can read held asset state ───────────────────────────────────

  test("heldAssets: a transition guard reading heldAssets.<id>.amount evaluates correctly") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("e5c40000-0000-4000-8000-000000000004")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000004")
      val bobStr = fixture.registry.addresses(Bob).value.value

      // The guard only passes if the held asset's amount is > 50. We hold 100, so it passes; then transfers.
      val json = s"""
      {
        "states": {
          "HOLDING":  { "id": "HOLDING",  "isFinal": false },
          "RELEASED": { "id": "RELEASED", "isFinal": true }
        },
        "initialState": "HOLDING",
        "transitions": [
          {
            "from": "HOLDING",
            "to": "RELEASED",
            "eventName": "release",
            "guard": { ">": [{ "var": "heldAssets.$assetId.amount" }, 50] },
            "effect": {
              "_transferAsset": [ { "assetId": "$assetId", "recipient": ${walletHolderJson(bobStr)} } ],
              "released": true
            },
            "dependencies": []
          }
        ]
      }
      """

      for {
        escrow <- buildEscrow(escrowId, json, fixture.ordinal)
        asset = heldAsset(assetId, escrowId, amount = 100L, ordinal = fixture.ordinal)
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow),
            assets = SortedMap(assetId -> asset)
          )
        )
        release = Updates.TransitionStateMachine(escrowId, "release", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(release, Set(Alice))
        after <- combiner.insert(genesis, Signed(release, pr))
      } yield expect(rejectionReasons(after).isEmpty) and
      expect(after.calculated.stateMachines.get(escrowId).map(_.currentState).contains(StateId("RELEASED"))) and
      expect(after.calculated.assets.get(assetId).map(_.holder).exists(_.isInstanceOf[AssetHolder.Wallet]))
    }
  }

  test("heldAssets: a guard reading heldAssets.<id>.amount on a too-small amount does NOT transition") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()

      val escrowId = UUID.fromString("e5c40000-0000-4000-8000-000000000005")
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000005")
      val bobStr = fixture.registry.addresses(Bob).value.value

      val json = s"""
      {
        "states": {
          "HOLDING":  { "id": "HOLDING",  "isFinal": false },
          "RELEASED": { "id": "RELEASED", "isFinal": true }
        },
        "initialState": "HOLDING",
        "transitions": [
          {
            "from": "HOLDING",
            "to": "RELEASED",
            "eventName": "release",
            "guard": { ">": [{ "var": "heldAssets.$assetId.amount" }, 50] },
            "effect": {
              "_transferAsset": [ { "assetId": "$assetId", "recipient": ${walletHolderJson(bobStr)} } ],
              "released": true
            },
            "dependencies": []
          }
        ]
      }
      """

      for {
        escrow <- buildEscrow(escrowId, json, fixture.ordinal)
        asset = heldAsset(assetId, escrowId, amount = 10L, ordinal = fixture.ordinal) // guard requires > 50
        genesis = DataState(
          OnChain.genesis,
          CalculatedState.genesis.copy(
            stateMachines = SortedMap(escrowId -> escrow),
            assets = SortedMap(assetId -> asset)
          )
        )
        release = Updates.TransitionStateMachine(escrowId, "release", MapValue(Map.empty), FiberOrdinal.MinValue)
        pr    <- fixture.registry.generateProofs(release, Set(Alice))
        after <- combiner.insert(genesis, Signed(release, pr))
      } yield
      // guard failed -> no transition, asset unchanged (still fiber-held), no transfer applied
      expect(after.calculated.assets.get(assetId).map(_.holder).contains(AssetHolder.Fiber(escrowId))) and
      expect(after.calculated.stateMachines.get(escrowId).map(_.currentState).contains(StateId("HOLDING")))
    }
  }

  // ── (4) EffectExtractor: _transferAsset parses into AssetTransferred, gas charged, malformed REJECTED ──

  // A tiny harness to drive EffectExtractor.extractAssetTransfers in the same FiberT MTL stack the engine uses.
  import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._

  private def runExtract(
    effectResult: JsonLogicValue,
    ctx:          JsonLogicValue = MapValue(Map.empty)
  ): IO[(List[FiberEffect.AssetTransferred], Long)] = {
    val prog: FiberT[IO, (List[FiberEffect.AssetTransferred], Long)] =
      for {
        transfers <- EffectExtractor.extractAssetTransfers[IO, FiberT[IO, *]](effectResult, ctx)
        gasUsed   <- ExecutionOps.getGasUsed[FiberT[IO, *]]
      } yield (transfers, gasUsed)
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

  test("EffectExtractor: a well-formed _transferAsset directive parses into AssetTransferred (constant form)") {
    val assetId = UUID.fromString("a55e7000-0000-4000-8000-000000000099")
    val fiberRecipient = UUID.fromString("acc70000-0000-4000-8000-000000000099")
    val effect = MapValue(
      Map(
        ReservedKeys.TRANSFER_ASSET -> ArrayValue(
          List(
            MapValue(
              Map(
                ReservedKeys.ASSET_ID -> StrValue(assetId.toString),
                ReservedKeys.RECIPIENT -> MapValue(
                  Map("Fiber" -> MapValue(Map("fiberId" -> StrValue(fiberRecipient.toString))))
                )
              )
            )
          )
        )
      )
    )
    runExtract(effect).map { case (transfers, _) =>
      expect(transfers == List(FiberEffect.AssetTransferred(assetId, AssetHolder.Fiber(fiberRecipient))))
    }
  }

  test("EffectExtractor: directive expressions are METERED — a var-resolved directive charges gas (Morphism)") {
    val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000bb")
    val fiberRecipient = UUID.fromString("acc70000-0000-4000-8000-0000000000bb")
    // assetId / recipient are resolved from context via {"var": ...} — evaluating these through the metered
    // evaluator (GasExhaustionPhase.Morphism) consumes gas, proving the directive goes through MeteredEvaluator.
    val ctx = MapValue(Map("aid" -> StrValue(assetId.toString), "rcp" -> StrValue(fiberRecipient.toString)))
    val effect = MapValue(
      Map(
        ReservedKeys.TRANSFER_ASSET -> ArrayValue(
          List(
            MapValue(
              Map(
                ReservedKeys.ASSET_ID -> MapValue(Map(ReservedKeys.VAR -> StrValue("aid"))),
                ReservedKeys.RECIPIENT -> MapValue(
                  Map("Fiber" -> MapValue(Map("fiberId" -> MapValue(Map(ReservedKeys.VAR -> StrValue("rcp"))))))
                )
              )
            )
          )
        )
      )
    )
    runExtract(effect, ctx).map { case (transfers, gasUsed) =>
      expect(transfers == List(FiberEffect.AssetTransferred(assetId, AssetHolder.Fiber(fiberRecipient)))) and
      expect(gasUsed > 0L)
    }
  }

  test("EffectExtractor: an object-form Wallet recipient resolves to a Wallet holder") {
    TestFixture.resource(Set(Bob)).use { fixture =>
      val assetId = UUID.fromString("a55e7000-0000-4000-8000-0000000000aa")
      val bobAddr: Address = fixture.registry.addresses(Bob)
      val effect = MapValue(
        Map(
          ReservedKeys.TRANSFER_ASSET -> ArrayValue(
            List(
              MapValue(
                Map(
                  ReservedKeys.ASSET_ID -> StrValue(assetId.toString),
                  ReservedKeys.RECIPIENT -> MapValue(
                    Map("Wallet" -> MapValue(Map("address" -> StrValue(bobAddr.value.value))))
                  )
                )
              )
            )
          )
        )
      )
      runExtract(effect).map { case (transfers, _) =>
        expect(transfers == List(FiberEffect.AssetTransferred(assetId, AssetHolder.Wallet(bobAddr))))
      }
    }
  }

  test("EffectExtractor: a malformed _transferAsset directive is REJECTED (not silently dropped)") {
    val effectBadAsset = MapValue(
      Map(
        ReservedKeys.TRANSFER_ASSET -> ArrayValue(
          List(
            MapValue(
              Map(
                ReservedKeys.ASSET_ID -> StrValue("not-a-uuid"),
                ReservedKeys.RECIPIENT -> MapValue(
                  Map("Fiber" -> MapValue(Map("fiberId" -> StrValue(UUID.randomUUID().toString))))
                )
              )
            )
          )
        )
      )
    )
    val effectMissingRecipient = MapValue(
      Map(
        ReservedKeys.TRANSFER_ASSET -> ArrayValue(
          List(MapValue(Map(ReservedKeys.ASSET_ID -> StrValue(UUID.randomUUID().toString))))
        )
      )
    )
    def rejected(io: IO[(List[FiberEffect.AssetTransferred], Long)]): IO[Boolean] =
      io.attempt.map(_.left.toOption.exists(_.isInstanceOf[CombineRejected]))
    for {
      badRejected     <- rejected(runExtract(effectBadAsset))
      missingRejected <- rejected(runExtract(effectMissingRecipient))
      none            <- runExtract(MapValue(Map.empty)).map(_._1) // absent directive: no transfer, no error
    } yield expect(badRejected) and expect(missingRejected) and expect(none.isEmpty)
  }
}
