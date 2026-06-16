package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.{BoolValue, ConstExpression, JsonLogicExpression}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.{Participant, TestFixture}

import weaver.SimpleIOSuite

/**
 * Phase 4 of the asset model (docs/proposals/asset-model.md §4/§6/§8/§10): the COMBINER-LEVEL morphism laws.
 * Drives the real `Combiner.insert` (the authoritative deterministic gate) and asserts the resulting state /
 * `RejectionReceipt`. The pure lattice algebra (meet glb/idempotent/commutative/associative, foldMeet,
 * Top/Bottom) is already covered by `TokenBehaviorLawSuite` / `TokenBehaviorPropertySuite`; this suite checks
 * the laws that only the stateful combiner can witness:
 *
 *   - RETRACTION: `Decompose ∘ Compose = id` on the stored `componentFiberIds` (round-trip restores them).
 *   - BEHAVIOR HOMOMORPHISM: a composite's stored `behavior == foldMeet(component behaviors)`.
 *   - PARTIAL-GRAPH REJECTION: Fractionalize (→C=0) then Compose (needs C=1) is `CombineRejected`.
 *   - CONSERVATION: total `amount` is preserved across Transfer/Compose/Decompose; Mint↑/Burn↓ derived supply.
 *   - HOLDER-OWNERSHIP (R1): a non-holder signer's morphism is rejected; a Fiber-held raw morphism is rejected.
 *   - NONCE LINEARITY: an authorized nonce consumed once cannot be reused; an expired authorization is rejected.
 */
object AssetMorphismLawSuite extends SimpleIOSuite {

  private def asset(n: String): RegistryName = RegistryName.unsafe(s"$n.asset")

  private val stateShape: MessageShape =
    MessageShape("Asset.State", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))

  // Always-true / always-false JSON-Logic guards (literal boolean constants evaluate to themselves).
  private val allowGuard: JsonLogicExpression = ConstExpression(BoolValue(true))
  private val denyGuard: JsonLogicExpression = ConstExpression(BoolValue(false))

  // A fully-permissive supply policy: open mint, open burn, uncapped.
  private val openSupply: SupplyPolicy =
    SupplyPolicy(maxSupply = None, mintPolicy = Some(allowGuard), burnPolicy = Some(allowGuard), decimals = Some(0))

  // A capped supply policy (for the maxSupply / conservation tests).
  private def cappedSupply(cap: Long): SupplyPolicy =
    SupplyPolicy(
      maxSupply = Some(cap),
      mintPolicy = Some(allowGuard),
      burnPolicy = Some(allowGuard),
      decimals = Some(0)
    )

  // All morphisms Public, no allowlists, no guards (the structural + codomain path, unguarded).
  private val publicMorphisms: SortedMap[MorphismKind, MorphismSpec] =
    SortedMap(
      MorphismKind.Transfer      -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Burn          -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Fractionalize -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Compose       -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Decompose     -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Wrap          -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Stake         -> MorphismSpec(MorphismVisibility.Public, None, None, None)
    )

  private def policyOp(
    name:      String,
    behavior:  TokenBehavior,
    supply:    SupplyPolicy = openSupply,
    morphisms: SortedMap[MorphismKind, MorphismSpec] = publicMorphisms
  ): CreateAssetPolicy =
    CreateAssetPolicy(asset(name), SemVer(1, 0, 0), behavior, supply, morphisms, stateShape)

  private val genesis = DataState(OnChain.genesis, CalculatedState.genesis)

  private def wasRejected(state: DataState[OnChain, CalculatedState]): Boolean =
    state.onChain.latestLogs.values.flatten.exists {
      case _: FiberLogEntry.RejectionReceipt => true
      case _                                 => false
    }

  private def assetOf(state: DataState[OnChain, CalculatedState], id: UUID): Option[AssetRecord] =
    state.calculated.assets.get(id)

  private def totalSupply(state: DataState[OnChain, CalculatedState], name: String): Long =
    state.calculated.assets.values.filter(_.schemaBinding.name == asset(name)).map(_.amount).sum

  private def mintTo(
    id:     UUID,
    policy: String,
    holder: AssetHolder,
    amount: Long
  ): MintAsset =
    MintAsset(id, SchemaRef(asset(policy), VersionReq.Latest), holder, amount)

  // Deterministic asset ids.
  private val a1 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
  private val a2 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002")
  private val a3 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000003")
  private val composite = UUID.fromString("cccccccc-0000-4000-8000-00000000000c")

  // A driver that publishes a Fungible (TSC--) policy and mints a wallet-held asset of `amount` to `who`.
  private def setupOne(
    fixture: TestFixture,
    who:     Participant,
    amount:  Long,
    id:      UUID = a1,
    name:    String = "gold"
  )(implicit sp: SecurityProvider[IO], l0: L0NodeContext[IO]): IO[DataState[OnChain, CalculatedState]] = {
    val combiner = Combiner.make[IO]()
    val p = policyOp(name, TokenBehavior.Fungible)
    val mint = mintTo(id, name, AssetHolder.Wallet(fixture.registry.addresses(who)), amount)
    for {
      prP <- fixture.registry.generateProofs(p, Set(who))
      s1  <- combiner.insert(genesis, Signed(p, prP))
      prM <- fixture.registry.generateProofs(mint, Set(who))
      s2  <- combiner.insert(s1, Signed(mint, prM))
    } yield s2
  }

  // ── Behavior homomorphism + retraction (Compose / Decompose) ──────────────────────────────────

  test("Compose stores foldMeet(component behaviors) and the verbatim componentFiberIds (homomorphism)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val holder = AssetHolder.Wallet(fixture.registry.addresses(Alice))

      // Two combinable components with DIFFERENT behaviors: Fungible (TSC--) and GovernedFungible (TSC-G).
      val pFun = policyOp("fund", TokenBehavior.Fungible)
      val pGov = policyOp("govf", TokenBehavior.GovernedFungible)
      val mint1 = mintTo(a1, "fund", holder, 40L)
      val mint2 = mintTo(a2, "govf", holder, 60L)
      // Compose a1 with a2 → composite. Expected behavior = meet(Fungible, GovernedFungible) = GovernedFungible.
      val compose =
        ApplyMorphism(
          a1,
          MorphismKind.Compose,
          FiberOrdinal.MinValue,
          otherAssetIds = Some(List(a2)),
          compositeId = Some(composite)
        )
      for {
        pr1  <- fixture.registry.generateProofs(pFun, Set(Alice))
        s1   <- combiner.insert(genesis, Signed(pFun, pr1))
        pr2  <- fixture.registry.generateProofs(pGov, Set(Alice))
        s2   <- combiner.insert(s1, Signed(pGov, pr2))
        prM1 <- fixture.registry.generateProofs(mint1, Set(Alice))
        s3   <- combiner.insert(s2, Signed(mint1, prM1))
        prM2 <- fixture.registry.generateProofs(mint2, Set(Alice))
        s4   <- combiner.insert(s3, Signed(mint2, prM2))
        prC  <- fixture.registry.generateProofs(compose, Set(Alice))
        s5   <- combiner.insert(s4, Signed(compose, prC))
        comp = assetOf(s5, composite)
        expectedBehavior = TokenBehavior.foldMeet(List(TokenBehavior.Fungible, TokenBehavior.GovernedFungible))
      } yield expect(comp.isDefined) and
      expect(comp.exists(_.behavior == expectedBehavior)) and
      expect(comp.exists(_.behavior == TokenBehavior.GovernedFungible)) and // meet acquires G
      expect(comp.flatMap(_.componentFiberIds).contains(List(a1, a2))) and // stored VERBATIM
      // components consumed from the live set
      expect(assetOf(s5, a1).isEmpty) and expect(assetOf(s5, a2).isEmpty)
    }
  }

  test("Decompose ∘ Compose = id on componentFiberIds (the retraction) and conserves amount") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val holder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val p = policyOp("gold", TokenBehavior.Fungible)
      val mint1 = mintTo(a1, "gold", holder, 30L)
      val mint2 = mintTo(a2, "gold", holder, 70L)
      val compose =
        ApplyMorphism(
          a1,
          MorphismKind.Compose,
          FiberOrdinal.MinValue,
          otherAssetIds = Some(List(a2)),
          compositeId = Some(composite)
        )
      // Decompose targets the composite (sequenceNumber starts at 0 on the freshly-minted composite).
      val decompose = ApplyMorphism(composite, MorphismKind.Decompose, FiberOrdinal.MinValue)
      for {
        prP  <- fixture.registry.generateProofs(p, Set(Alice))
        s1   <- combiner.insert(genesis, Signed(p, prP))
        prM1 <- fixture.registry.generateProofs(mint1, Set(Alice))
        s2   <- combiner.insert(s1, Signed(mint1, prM1))
        prM2 <- fixture.registry.generateProofs(mint2, Set(Alice))
        s3   <- combiner.insert(s2, Signed(mint2, prM2))
        prC  <- fixture.registry.generateProofs(compose, Set(Alice))
        s4   <- combiner.insert(s3, Signed(compose, prC))
        totalAfterCompose = totalSupply(s4, "gold")
        prD <- fixture.registry.generateProofs(decompose, Set(Alice))
        s5  <- combiner.insert(s4, Signed(decompose, prD))
      } yield
      // composite consumed, the two original components restored (same ids)
      expect(assetOf(s5, composite).isEmpty) and
      expect(assetOf(s5, a1).isDefined) and
      expect(assetOf(s5, a2).isDefined) and
      // amount conserved across compose (100) and decompose (back to 100)
      expect(totalAfterCompose == 100L) and
      expect(totalSupply(s5, "gold") == 100L)
    }
  }

  // ── Partial-graph rejection (Fractionalize → C=0 → Compose rejected) ──────────────────────────

  test("Fractionalize forces C=0, so a subsequent Compose of a shard is CombineRejected (partial-graph)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val holder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val p = policyOp("gold", TokenBehavior.Fungible) // S=1, C=1
      val mint = mintTo(a1, "gold", holder, 100L)
      // Fractionalize a1 into two explicit shards.
      val shardX = UUID.fromString("dddddddd-0000-4000-8000-000000000001")
      val shardY = UUID.fromString("dddddddd-0000-4000-8000-000000000002")
      val frac =
        ApplyMorphism(a1, MorphismKind.Fractionalize, FiberOrdinal.MinValue, shardIds = Some(List(shardX, shardY)))
      // Another combinable asset to attempt to compose the (now C=0) shard with.
      val mint2 = mintTo(a2, "gold", holder, 10L)
      val compose =
        ApplyMorphism(
          shardX,
          MorphismKind.Compose,
          FiberOrdinal.MinValue,
          otherAssetIds = Some(List(a2)),
          compositeId = Some(composite)
        )
      for {
        prP  <- fixture.registry.generateProofs(p, Set(Alice))
        s1   <- combiner.insert(genesis, Signed(p, prP))
        prM  <- fixture.registry.generateProofs(mint, Set(Alice))
        s2   <- combiner.insert(s1, Signed(mint, prM))
        prM2 <- fixture.registry.generateProofs(mint2, Set(Alice))
        s3   <- combiner.insert(s2, Signed(mint2, prM2))
        prF  <- fixture.registry.generateProofs(frac, Set(Alice))
        s4   <- combiner.insert(s3, Signed(frac, prF))
        shardCombinable = assetOf(s4, shardX).map(_.behavior.combinable)
        prC <- fixture.registry.generateProofs(compose, Set(Alice))
        s5  <- combiner.insert(s4, Signed(compose, prC)).map(wasRejected)
        fracConserved = (assetOf(s4, shardX).map(_.amount).getOrElse(0L) +
          assetOf(s4, shardY).map(_.amount).getOrElse(0L))
      } yield
      // shards exist with C=0, source consumed, amount conserved across the fractionalize
      expect(shardCombinable.contains(false)) and
      expect(assetOf(s4, a1).isEmpty) and
      expect(fracConserved == 100L) and
      // the Compose of a C=0 shard is rejected (structural)
      expect(s5)
    }
  }

  // ── Conservation: Transfer preserves total; Mint↑ / Burn↓ derived supply ──────────────────────

  test("Transfer preserves total supply and moves only the holder") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val transfer =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(AssetHolder.Wallet(fixture.registry.addresses(Bob)))
        )
      for {
        s2 <- setupOne(fixture, Alice, 500L)
        pr <- fixture.registry.generateProofs(transfer, Set(Alice))
        s3 <- combiner.insert(s2, Signed(transfer, pr))
      } yield expect(totalSupply(s3, "gold") == 500L) and
      expect(assetOf(s3, a1).map(_.holder).contains(AssetHolder.Wallet(fixture.registry.addresses(Bob)))) and
      expect(assetOf(s3, a1).map(_.amount).contains(500L))
    }
  }

  test("Mint increases derived supply; Burn decreases it; maxSupply breach is CombineRejected") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val holder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      // capped policy at 100
      val p = policyOp("cap", TokenBehavior.Fungible, supply = cappedSupply(100L))
      val mint1 = mintTo(a1, "cap", holder, 60L)
      val mint2 = mintTo(a2, "cap", holder, 30L) // 60+30=90 <= 100 ok
      val mintOver = mintTo(a3, "cap", holder, 50L) // 90+50=140 > 100 → reject
      val burn = ApplyMorphism(a1, MorphismKind.Burn, FiberOrdinal.MinValue)
      for {
        prP  <- fixture.registry.generateProofs(p, Set(Alice))
        s1   <- combiner.insert(genesis, Signed(p, prP))
        prM1 <- fixture.registry.generateProofs(mint1, Set(Alice))
        s2   <- combiner.insert(s1, Signed(mint1, prM1))
        afterMint1 = totalSupply(s2, "cap")
        prM2 <- fixture.registry.generateProofs(mint2, Set(Alice))
        s3   <- combiner.insert(s2, Signed(mint2, prM2))
        afterMint2 = totalSupply(s3, "cap")
        prOver <- fixture.registry.generateProofs(mintOver, Set(Alice))
        sOver  <- combiner.insert(s3, Signed(mintOver, prOver))
        overRejected = wasRejected(sOver)
        prB <- fixture.registry.generateProofs(burn, Set(Alice))
        s4  <- combiner.insert(s3, Signed(burn, prB))
        afterBurn = totalSupply(s4, "cap")
      } yield expect(afterMint1 == 60L) and
      expect(afterMint2 == 90L) and
      expect(overRejected) and // maxSupply breach rejected
      expect(totalSupply(sOver, "cap") == 90L) and // unmutated on reject
      expect(afterBurn == 30L) and // burn removed a1 (60)
      expect(assetOf(s4, a1).isEmpty)
    }
  }

  test("Mint with a denying mintPolicy is CombineRejected; closed mint (None) is rejected") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val holder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val denySupply = SupplyPolicy(None, mintPolicy = Some(denyGuard), None, Some(0))
      val closedSupply = SupplyPolicy(None, mintPolicy = None, None, Some(0))
      val pDeny = policyOp("deny", TokenBehavior.Fungible, supply = denySupply)
      val pClosed = policyOp("closed", TokenBehavior.Fungible, supply = closedSupply)
      val mintDeny = mintTo(a1, "deny", holder, 10L)
      val mintClosed = mintTo(a2, "closed", holder, 10L)
      for {
        prD  <- fixture.registry.generateProofs(pDeny, Set(Alice))
        s1   <- combiner.insert(genesis, Signed(pDeny, prD))
        prC  <- fixture.registry.generateProofs(pClosed, Set(Alice))
        s2   <- combiner.insert(s1, Signed(pClosed, prC))
        prMD <- fixture.registry.generateProofs(mintDeny, Set(Alice))
        sD   <- combiner.insert(s2, Signed(mintDeny, prMD)).map(wasRejected)
        prMC <- fixture.registry.generateProofs(mintClosed, Set(Alice))
        sC   <- combiner.insert(s2, Signed(mintClosed, prMC)).map(wasRejected)
      } yield expect(sD) and expect(sC)
    }
  }

  // ── Holder-ownership (R1) ─────────────────────────────────────────────────────────────────────

  test("a non-holder signer's ApplyMorphism is CombineRejected (R1 holder-ownership)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      // a1 is held by Alice; Bob (not the holder) attempts a Transfer.
      val transfer =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(AssetHolder.Wallet(fixture.registry.addresses(Bob)))
        )
      for {
        s2       <- setupOne(fixture, Alice, 10L)
        prBob    <- fixture.registry.generateProofs(transfer, Set(Bob))
        rejected <- combiner.insert(s2, Signed(transfer, prBob)).map(wasRejected)
        // unchanged: still Alice's
        stillAlice = assetOf(s2, a1).map(_.holder).contains(AssetHolder.Wallet(fixture.registry.addresses(Alice)))
      } yield expect(rejected) and expect(stillAlice)
    }
  }

  test("a Fiber-held asset's raw ApplyMorphism is CombineRejected (deferred to phase 5)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val fiberHolder = AssetHolder.Fiber(UUID.fromString("ffffffff-0000-4000-8000-00000000000f"))
      val p = policyOp("gold", TokenBehavior.Fungible)
      // Mint directly into a fiber holder (allowed).
      val mint = mintTo(a1, "gold", fiberHolder, 10L)
      val transfer =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(AssetHolder.Wallet(fixture.registry.addresses(Alice)))
        )
      for {
        prP <- fixture.registry.generateProofs(p, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(p, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        mintedToFiber = assetOf(s2, a1).map(_.holder).contains(fiberHolder)
        prT      <- fixture.registry.generateProofs(transfer, Set(Alice))
        rejected <- combiner.insert(s2, Signed(transfer, prT)).map(wasRejected)
      } yield expect(mintedToFiber) and expect(rejected)
    }
  }

  // ── Nonce linearity (commit-reveal) ───────────────────────────────────────────────────────────

  test("an AuthorizeCompose nonce is consumed once by a Compose and cannot be reused") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceAsset = a1
      val bobAsset = a2
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))
      val pGold = policyOp("gold", TokenBehavior.Fungible)
      val pUsd = policyOp("usd", TokenBehavior.Fungible)
      val mintAlice = mintTo(aliceAsset, "gold", aliceHolder, 50L)
      val mintBob = mintTo(bobAsset, "usd", bobHolder, 50L)
      val farFuture = SnapshotOrdinal.unsafeApply(Long.MaxValue)
      // Alice authorizes a compose with the usd policy, nonce 7.
      val authorize = AuthorizeCompose(
        aliceAsset,
        asset("usd"),
        nonce = 7L,
        expiresAt = farFuture,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      // Bob composes his usd asset with Alice's gold asset, consuming nonce 7. compositeId held by Bob.
      val compose = ApplyMorphism(
        bobAsset,
        MorphismKind.Compose,
        FiberOrdinal.MinValue,
        otherAssetIds = Some(List(aliceAsset)),
        compositeId = Some(composite),
        nonce = Some(7L)
      )
      // A second compose attempt reusing nonce 7 (after it is consumed) — must reject.
      val compositeTwo = UUID.fromString("cccccccc-0000-4000-8000-00000000000d")
      val composeAgain = ApplyMorphism(
        bobAsset,
        MorphismKind.Compose,
        FiberOrdinal.MinValue,
        otherAssetIds = Some(List(aliceAsset)),
        compositeId = Some(compositeTwo),
        nonce = Some(7L)
      )
      for {
        prPg   <- fixture.registry.generateProofs(pGold, Set(Alice))
        s1     <- combiner.insert(genesis, Signed(pGold, prPg))
        prPu   <- fixture.registry.generateProofs(pUsd, Set(Bob))
        s2     <- combiner.insert(s1, Signed(pUsd, prPu))
        prMA   <- fixture.registry.generateProofs(mintAlice, Set(Alice))
        s3     <- combiner.insert(s2, Signed(mintAlice, prMA))
        prMB   <- fixture.registry.generateProofs(mintBob, Set(Bob))
        s4     <- combiner.insert(s3, Signed(mintBob, prMB))
        prAuth <- fixture.registry.generateProofs(authorize, Set(Alice))
        s5     <- combiner.insert(s4, Signed(authorize, prAuth))
        nonceRecorded = s5.calculated.usedNonces
          .getOrElse(aliceAsset, scala.collection.immutable.SortedSet.empty[Long])
          .contains(7L)
        prCompose <- fixture.registry.generateProofs(compose, Set(Bob))
        s6        <- combiner.insert(s5, Signed(compose, prCompose))
        composedOk = assetOf(s6, composite).isDefined
        nonceConsumed = !s6.calculated.usedNonces
          .getOrElse(aliceAsset, scala.collection.immutable.SortedSet.empty[Long])
          .contains(7L)
        prAgain <- fixture.registry.generateProofs(composeAgain, Set(Bob))
        sReuse  <- combiner.insert(s6, Signed(composeAgain, prAgain)).map(wasRejected)
      } yield expect(nonceRecorded) and
      expect(composedOk) and
      expect(nonceConsumed) and
      expect(sReuse) // reuse of the consumed nonce is rejected
    }
  }

  test("an already-expired AuthorizeCompose is rejected, so a Compose referencing the nonce fails") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))
      val pGold = policyOp("gold", TokenBehavior.Fungible)
      val pUsd = policyOp("usd", TokenBehavior.Fungible)
      val mintAlice = mintTo(a1, "gold", aliceHolder, 50L)
      val mintBob = mintTo(a2, "usd", bobHolder, 50L)
      // expiresAt = MinValue: the current ordinal is strictly greater, so the authorization is already expired.
      val expired = AuthorizeCompose(
        a1,
        asset("usd"),
        nonce = 9L,
        expiresAt = SnapshotOrdinal.MinValue,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      val compose = ApplyMorphism(
        a2,
        MorphismKind.Compose,
        FiberOrdinal.MinValue,
        otherAssetIds = Some(List(a1)),
        compositeId = Some(composite),
        nonce = Some(9L)
      )
      for {
        prPg  <- fixture.registry.generateProofs(pGold, Set(Alice))
        s1    <- combiner.insert(genesis, Signed(pGold, prPg))
        prPu  <- fixture.registry.generateProofs(pUsd, Set(Bob))
        s2    <- combiner.insert(s1, Signed(pUsd, prPu))
        prMA  <- fixture.registry.generateProofs(mintAlice, Set(Alice))
        s3    <- combiner.insert(s2, Signed(mintAlice, prMA))
        prMB  <- fixture.registry.generateProofs(mintBob, Set(Bob))
        s4    <- combiner.insert(s3, Signed(mintBob, prMB))
        prExp <- fixture.registry.generateProofs(expired, Set(Alice))
        s5    <- combiner.insert(s4, Signed(expired, prExp))
        authRejected = wasRejected(s5)
        nonceAbsent = !s5.calculated.usedNonces
          .getOrElse(a1, scala.collection.immutable.SortedSet.empty[Long])
          .contains(9L)
        prC <- fixture.registry.generateProofs(compose, Set(Bob))
        sC  <- combiner.insert(s5, Signed(compose, prC)).map(wasRejected)
      } yield expect(authRejected) and expect(nonceAbsent) and expect(sC)
    }
  }
}
