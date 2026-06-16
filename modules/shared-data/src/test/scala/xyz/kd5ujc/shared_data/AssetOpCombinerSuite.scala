package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{ApplyMorphism, AuthorizeCompose, CreateAssetPolicy, MintAsset, OttochainMessage}
import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{AssetCommit, CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.AssetValidator
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser.decode
import io.circe.syntax.EncoderOps
import weaver.SimpleIOSuite

/**
 * Phase 3 of the asset model (docs/proposals/asset-model.md §6/§7): the SIGNED asset ops + L1 STRUCTURAL gate
 * + OnChain `AssetCommit` + TOTAL combiner dispatch. Proves:
 *   (a) every new op round-trips through the `OttochainMessage` ADT codec,
 *   (b) the L1 structural gate rejects a behavior-impossible morphism (Transfer on a soulbound T=0 asset) and
 *       a sequence-number regression, and accepts a valid one,
 *   (c) the stateful ops (MintAsset / ApplyMorphism / AuthorizeCompose) produce a graceful RejectionReceipt
 *       (the snapshot is NOT aborted — phase-4 stubs), and
 *   (d) CreateAssetPolicy is IMPLEMENTED: it publishes an AssetPolicyPackage version into the registry.
 */
object AssetOpCombinerSuite extends SimpleIOSuite {

  private val assetId = UUID.fromString("22222222-2222-4222-8222-222222222222")

  private def asset(n: String): RegistryName = RegistryName.unsafe(s"$n.asset")

  private val stateShape: MessageShape =
    MessageShape("Asset.State", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))

  private val supply: SupplyPolicy = SupplyPolicy(maxSupply = Some(1000000L), None, None, decimals = Some(8))

  private val morphisms: SortedMap[MorphismKind, MorphismSpec] =
    SortedMap(
      MorphismKind.Transfer -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Burn     -> MorphismSpec(MorphismVisibility.Governed, None, None, None)
    )

  private def createPolicy(
    name:     String,
    v:        SemVer,
    behavior: TokenBehavior = TokenBehavior.Fungible
  ): CreateAssetPolicy =
    CreateAssetPolicy(asset(name), v, behavior, supply, morphisms, stateShape)

  private val genesis = DataState(OnChain.genesis, CalculatedState.genesis)

  private def wasRejected(state: DataState[OnChain, CalculatedState]): Boolean =
    state.onChain.latestLogs.values.flatten.exists {
      case _: FiberLogEntry.RejectionReceipt => true
      case _                                 => false
    }

  // An OnChain carrying a single AssetCommit for `assetId` at sequence 0 with the given behavior bits.
  private def onChainWithAsset(behavior: TokenBehavior, seq: FiberOrdinal = FiberOrdinal.MinValue): OnChain =
    OnChain.genesis.copy(
      assetCommits = SortedMap(assetId -> AssetCommit(behavior.bits, seq, Hash("asset-record-hash")))
    )

  private def policyVersions(state: DataState[OnChain, CalculatedState], name: String): Option[Set[SemVer]] =
    state.calculated.registry
      .get(asset(name))
      .map(_.target)
      .collect { case RegistryTarget.AssetPolicyPackage(l) => l.versions.keySet }

  // ── (a) ADT codec round-trips ─────────────────────────────────────────────────────────────────

  test("each asset op round-trips through the OttochainMessage ADT codec") {
    val ops: List[OttochainMessage] = List(
      createPolicy("gold", SemVer(1, 0, 0)),
      MintAsset(assetId, SchemaRef(asset("gold"), VersionReq.Latest), AssetHolder.Fiber(assetId), 100L),
      ApplyMorphism(assetId, MorphismKind.Transfer, FiberOrdinal.MinValue),
      AuthorizeCompose(
        assetId,
        asset("usd"),
        nonce = 7L,
        expiresAt = io.constellationnetwork.schema.SnapshotOrdinal.MinValue,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
    )
    val allRoundTrip = ops.forall(op => decode[OttochainMessage]((op: OttochainMessage).asJson.noSpaces) == Right(op))
    IO.pure(expect(allRoundTrip))
  }

  // ── (b) L1 structural gate ────────────────────────────────────────────────────────────────────

  test("ApplyMorphism Transfer is INVALID when the asset's T bit is absent (soulbound)") {
    val l1 = new AssetValidator.L1Validator[IO](onChainWithAsset(TokenBehavior.Soulbound)) // bits=0, T=0
    l1.applyMorphism(ApplyMorphism(assetId, MorphismKind.Transfer, FiberOrdinal.MinValue)).map {
      case Invalid(_) => success
      case Valid(_)   => failure("expected INVALID: Transfer on a soulbound (T=0) asset")
    }
  }

  test("ApplyMorphism Transfer is VALID when the asset's T bit is present (transferable)") {
    val l1 = new AssetValidator.L1Validator[IO](onChainWithAsset(TokenBehavior.Fungible)) // TSC--, T=1
    l1.applyMorphism(ApplyMorphism(assetId, MorphismKind.Transfer, FiberOrdinal.MinValue)).map {
      case Valid(_)      => success
      case Invalid(errs) => failure(s"expected VALID: ${errs.toNonEmptyList.toList.map(_.message).mkString("; ")}")
    }
  }

  test("ApplyMorphism Fractionalize is INVALID when S=0 (indivisible), VALID when S=1") {
    val l1Indivisible = new AssetValidator.L1Validator[IO](onChainWithAsset(TokenBehavior.NFT)) // T only, S=0
    val l1Splittable = new AssetValidator.L1Validator[IO](onChainWithAsset(TokenBehavior.Fungible)) // TSC, S=1
    for {
      bad <- l1Indivisible.applyMorphism(ApplyMorphism(assetId, MorphismKind.Fractionalize, FiberOrdinal.MinValue))
      ok  <- l1Splittable.applyMorphism(ApplyMorphism(assetId, MorphismKind.Fractionalize, FiberOrdinal.MinValue))
    } yield expect(bad.isInvalid) and expect(ok.isValid)
  }

  test("ApplyMorphism is INVALID when the asset is unknown (no on-chain commit)") {
    val l1 = new AssetValidator.L1Validator[IO](OnChain.genesis) // empty assetCommits
    l1.applyMorphism(ApplyMorphism(assetId, MorphismKind.Burn, FiberOrdinal.MinValue)).map { r =>
      expect(r.isInvalid)
    }
  }

  test("ApplyMorphism is INVALID on a sequence-number regression (target < committed)") {
    // committed at seq 5; a morphism targeting seq 2 regresses -> hard reject
    val committedAt5 = FiberOrdinal.unsafeApply(5L)
    val l1 = new AssetValidator.L1Validator[IO](onChainWithAsset(TokenBehavior.Fungible, committedAt5))
    l1.applyMorphism(ApplyMorphism(assetId, MorphismKind.Transfer, FiberOrdinal.unsafeApply(2L))).map { r =>
      expect(r.isInvalid)
    }
  }

  test("MintAsset is INVALID when amount <= 0, VALID when amount > 0") {
    val l1 = new AssetValidator.L1Validator[IO](OnChain.genesis)
    val zero = MintAsset(assetId, SchemaRef(asset("gold"), VersionReq.Latest), AssetHolder.Fiber(assetId), 0L)
    val pos = MintAsset(assetId, SchemaRef(asset("gold"), VersionReq.Latest), AssetHolder.Fiber(assetId), 1L)
    for {
      bad <- l1.mintAsset(zero)
      ok  <- l1.mintAsset(pos)
    } yield expect(bad.isInvalid) and expect(ok.isValid)
  }

  // ── (c) combiner stubs produce a graceful RejectionReceipt (snapshot not aborted) ──────────────

  test("MintAsset / ApplyMorphism / AuthorizeCompose are gracefully rejected (RejectionReceipt, phase-4 stub)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val mint = MintAsset(assetId, SchemaRef(asset("gold"), VersionReq.Latest), AssetHolder.Fiber(assetId), 100L)
      val morph = ApplyMorphism(assetId, MorphismKind.Transfer, FiberOrdinal.MinValue)
      val auth = AuthorizeCompose(
        assetId,
        asset("usd"),
        nonce = 7L,
        expiresAt = io.constellationnetwork.schema.SnapshotOrdinal.MinValue,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      for {
        prM    <- fixture.registry.generateProofs(mint, Set(Alice))
        sMint  <- combiner.insert(genesis, Signed(mint, prM)).map(wasRejected)
        prA    <- fixture.registry.generateProofs(morph, Set(Alice))
        sMorph <- combiner.insert(genesis, Signed(morph, prA)).map(wasRejected)
        prC    <- fixture.registry.generateProofs(auth, Set(Alice))
        sAuth  <- combiner.insert(genesis, Signed(auth, prC)).map(wasRejected)
      } yield expect(sMint) and expect(sMorph) and expect(sAuth)
    }
  }

  // ── (d) CreateAssetPolicy is IMPLEMENTED (publishes through the registry lineage) ──────────────

  test("CreateAssetPolicy publishes an AssetPolicyPackage version; the owner can append a higher version") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = createPolicy("gold", SemVer(1, 0, 0))
      val p2 = createPolicy("gold", SemVer(1, 1, 0))
      for {
        validator <- Validator.make[IO]
        pr1       <- fixture.registry.generateProofs(p1, Set(Alice))
        valid     <- validator.validateSignedUpdate(genesis, Signed(p1, pr1))
        s1        <- combiner.insert(genesis, Signed(p1, pr1))
        pr2       <- fixture.registry.generateProofs(p2, Set(Alice))
        s2        <- combiner.insert(s1, Signed(p2, pr2))
        shapeIsAssetPolicy = s2.calculated.registry
          .get(asset("gold"))
          .map(_.target)
          .collect { case RegistryTarget.AssetPolicyPackage(l) => l.head.map(_.shape) }
          .flatten
          .exists(_.isInstanceOf[RegistryShape.AssetPolicy])
      } yield expect(valid.isValid) and
      expect(policyVersions(s2, "gold").contains(Set(SemVer(1, 0, 0), SemVer(1, 1, 0)))) and
      expect(s2.onChain.registryCommits.contains(asset("gold"))) and
      expect(shapeIsAssetPolicy)
    }
  }

  test("CreateAssetPolicy by a non-owner to an existing policy is rejected at combine (validator structural-only)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = createPolicy("gold", SemVer(1, 0, 0)) // Alice claims + owns
      val p2 = createPolicy("gold", SemVer(1, 1, 0)) // Bob (not an owner) tries to publish
      for {
        validator     <- Validator.make[IO]
        pr1           <- fixture.registry.generateProofs(p1, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(p1, pr1))
        pr2           <- fixture.registry.generateProofs(p2, Set(Bob))
        valid         <- validator.validateSignedUpdate(s1, Signed(p2, pr2))
        combineFailed <- combiner.insert(s1, Signed(p2, pr2)).map(wasRejected)
      } yield expect(valid.isValid) and expect(combineFailed)
    }
  }
}
