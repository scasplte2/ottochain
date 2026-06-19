package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.JsonLogicOp
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
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * SEMI-PRIVATE guarded transition (docs/design/client-side-private-data.md) — the "public by default,
 * private by opt-in" tier. A holder proves OFF-CHAIN (SP1 zk-jlvm) that PRIVATE data satisfies a PUBLIC,
 * pinned JLVM rule and carries only `{publicValues, proof}` on the op; a [[SemiPrivateGuard]] re-verifies
 * the proof and binds it to the pinned rule by parsing the public-values blob with native `substr`/`cat`
 * (no `jlvm_pv_decode`). The value stays hidden; the predicate (e.g. `score >= 700`) stays legible.
 *
 * Real SP1 proofs are too heavy for unit tests (as in ZkGatedMorphismSuite), so the FULL positive path
 * (valid proof => transfer) lives in the cross-repo e2e. Here we pin the two on-chain-critical halves:
 *
 *   (A) PARSING PARITY — a Transfer governed by the BINDING-ONLY sub-guard (`exprBinding AND
 *       outputBinding`, no groth16) ACCEPTS a witness whose synthetic public-values bind to the pinned
 *       `logicHash` + `keccak256("true")`, and REJECTS mismatched ones. This exercises the real combiner
 *       and proves the `substr`/`cat` word offsets (2 / 130) parse the blob correctly ON-CHAIN.
 *   (B) FAIL-CLOSED — the full guard (incl. `groth16_verify`) gracefully rejects a garbage proof and an
 *       absent witness, leaving the asset untouched.
 *
 * The literals below are produced by `@ottochain/sdk/zk` (`exprHash(rule)` / `KECCAK_TRUE` / `dataHash`),
 * keeping the off-chain prover, the SDK, and this on-chain guard byte-aligned on the same canonical bytes.
 */
object SemiPrivateGuardedTransferSuite extends SimpleIOSuite {

  private def asset(n: String): RegistryName = RegistryName.unsafe(s"$n.asset")

  private val stateShape: MessageShape =
    MessageShape("Asset.State", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))

  private val genesis = DataState(OnChain.genesis, CalculatedState.genesis)

  private def wasRejected(state: DataState[OnChain, CalculatedState]): Boolean =
    state.onChain.latestLogs.values.flatten.exists {
      case _: FiberLogEntry.RejectionReceipt => true
      case _                                 => false
    }

  private def assetOf(state: DataState[OnChain, CalculatedState], id: UUID): Option[AssetRecord] =
    state.calculated.assets.get(id)

  private val a1 = UUID.fromString("dddddddd-0000-4000-8000-000000000001")

  // ── Off-chain keccak literals (from @ottochain/sdk/zk) — NEVER recomputed on-chain ──────────────
  // rule = {">=":[{"var":"score"},700]} ; the value `score` stays private, the bound 700 is public.
  private val logicHash: String = "0xccc6c597899c9e0dc76770e4e288fd9796431140c8cd4facc9f01daeaac0bb62"
  private val keccakTrue: String = "0x6273151f959616268004b58dbb21e5c851b7b8d04498b4aabee12291d22fc034"
  private val dataHash: String = "0xfe47d84fb51a51fb7d89133d93ed9c734670cca1662b8e682e3bd923c40ffa1b"
  private val vkey: String = "0x" + "00" * 32 // 32-byte dummy vkey (correct width)

  /** `abi_encode(JlvmPublicValues)` = `0x | exprHash | dataHash | outputHash | ok` (4×32 bytes). */
  private def publicValues(exprH: String, outH: String, ok: Boolean): String = {
    val w = (h: String) => h.stripPrefix("0x")
    "0x" + w(exprH) + w(dataHash) + w(outH) + (if (ok) "00" * 31 + "01" else "00" * 32)
  }

  // A witness whose public-values BIND (exprHash == logicHash, outputHash == keccak(true), ok). The
  // proof is garbage — irrelevant for the binding-only guard, rejected by the full guard's groth16 clause.
  private val pvBinding: String = publicValues(logicHash, keccakTrue, ok = true)
  // Wrong rule: a different exprHash word — exprBinding must fail.
  private val pvWrongExpr: String = publicValues("0x" + "99" * 32, keccakTrue, ok = true)
  // Rule did not return true: a different outputHash word (and ok=false) — outputBinding must fail.
  private val pvWrongOutput: String = publicValues(logicHash, "0x" + "44" * 32, ok = false)

  private def witnessOf(pv: String): JsonLogicValue =
    MapValue(Map("publicValues" -> StrValue(pv), "proof" -> StrValue("0x" + "00" * 8)))

  // ── Guards ───────────────────────────────────────────────────────────────────────────────────
  // The binding-only sub-guard: parses + binds the public values, WITHOUT groth16. Lets us prove the
  // on-chain parser end-to-end without a (heavy) real proof. NOT a production guard on its own — the
  // full `SemiPrivateGuard.guard` always includes the groth16 verification.
  private val bindingOnlyGuard: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.AndOp,
      List(SemiPrivateGuard.exprBinding(logicHash), SemiPrivateGuard.outputBinding(keccakTrue))
    )

  private val fullGuard: JsonLogicExpression = SemiPrivateGuard.guard(vkey, logicHash, keccakTrue)

  // ── Policy builders (mirrors ZkGatedMorphismSuite) ─────────────────────────────────────────────
  private def openSupply: SupplyPolicy =
    SupplyPolicy(
      maxSupply = None,
      mintPolicy = Some(ConstExpression(BoolValue(true))),
      burnPolicy = None,
      decimals = Some(0)
    )

  private def transferGovernedBy(name: String, guard: JsonLogicExpression): CreateAssetPolicy =
    CreateAssetPolicy(
      asset(name),
      SemVer(1, 0, 0),
      TokenBehavior.Fungible, // T=1 so Transfer is structurally allowed
      openSupply,
      SortedMap(MorphismKind.Transfer -> MorphismSpec(MorphismVisibility.Governed, None, None, Some(guard))),
      stateShape
    )

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // (A) PARSING PARITY — the binding-only guard accepts a bound witness, rejects mismatched ones
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  test("binding-only guard: a Transfer SUCCEEDS when the public-values bind, and is REJECTED otherwise") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))

      val policy = transferGovernedBy("semipriv", bindingOnlyGuard)
      val mint = MintAsset(a1, SchemaRef(asset("semipriv"), VersionReq.Latest), aliceHolder, 100L)

      def transfer(pv: String) =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(bobHolder),
          witness = Some(witnessOf(pv))
        )

      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        // (a) wrong exprHash on a fresh state — must reject, asset stays at Alice/seq 0.
        prWe <- fixture.registry.generateProofs(transfer(pvWrongExpr), Set(Alice))
        sWe  <- combiner.insert(s2, Signed(transfer(pvWrongExpr), prWe))
        // (b) wrong outputHash on a fresh state — must reject.
        prWo <- fixture.registry.generateProofs(transfer(pvWrongOutput), Set(Alice))
        sWo  <- combiner.insert(s2, Signed(transfer(pvWrongOutput), prWo))
        // (c) bound public-values on the original state — must succeed, holder -> Bob.
        prOk <- fixture.registry.generateProofs(transfer(pvBinding), Set(Alice))
        sOk  <- combiner.insert(s2, Signed(transfer(pvBinding), prOk))
      } yield expect(assetOf(s2, a1).isDefined) and
      // (a) wrong rule hash -> graceful rejection, asset untouched
      expect(wasRejected(sWe)) and expect(assetOf(sWe, a1).map(_.holder).contains(aliceHolder)) and
      // (b) rule did not return true -> graceful rejection, asset untouched
      expect(wasRejected(sWo)) and expect(assetOf(sWo, a1).map(_.holder).contains(aliceHolder)) and
      // (c) public-values bind -> morphism applied, holder moved
      expect(!wasRejected(sOk)) and expect(assetOf(sOk, a1).map(_.holder).contains(bobHolder))
    }
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // (B) FAIL-CLOSED — the full guard (incl. groth16) rejects a garbage proof and an absent witness
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  test("full semi-private guard: a garbage groth16 proof is gracefully CombineRejected, asset unchanged") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))

      val policy = transferGovernedBy("semiprivfull", fullGuard)
      val mint = MintAsset(a1, SchemaRef(asset("semiprivfull"), VersionReq.Latest), aliceHolder, 70L)
      // Public-values bind, but the proof is garbage — groth16_verify fails => graceful deny.
      val transfer =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(bobHolder),
          witness = Some(witnessOf(pvBinding))
        )
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        prT <- fixture.registry.generateProofs(transfer, Set(Alice))
        s3  <- combiner.insert(s2, Signed(transfer, prT))
      } yield expect(wasRejected(s3)) and
      expect(assetOf(s3, a1).map(_.holder).contains(aliceHolder)) and
      expect(assetOf(s3, a1).map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("full semi-private guard: a missing witness is gracefully CombineRejected") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))

      val policy = transferGovernedBy("semiprivnowit", fullGuard)
      val mint = MintAsset(a1, SchemaRef(asset("semiprivnowit"), VersionReq.Latest), aliceHolder, 50L)
      // No witness => `witness` is NullValue => `{"var":"witness.publicValues"}` is null => opcode errors/false.
      val transferNoWitness =
        ApplyMorphism(a1, MorphismKind.Transfer, FiberOrdinal.MinValue, recipient = Some(bobHolder))
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        prT <- fixture.registry.generateProofs(transferNoWitness, Set(Alice))
        s3  <- combiner.insert(s2, Signed(transferNoWitness, prT))
      } yield expect(wasRejected(s3)) and
      expect(assetOf(s3, a1).map(_.holder).contains(aliceHolder))
    }
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // (C) REAL PROOF — a GPU-generated SP1-Groth16 proof flows through the FULL guard end-to-end
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  private def realWitness(pv: String, proof: String): JsonLogicValue =
    MapValue(Map("publicValues" -> StrValue(pv), "proof" -> StrValue(proof)))

  // The guard pins the REAL program vkey (same program => the same vkey verifies both proofs).
  private val realGuard: JsonLogicExpression =
    SemiPrivateGuard.guard(SemiPrivateRealProof.vkey, logicHash, keccakTrue)

  test("full semi-private guard: a REAL SP1 proof (rule true) is accepted and the Transfer SUCCEEDS") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))

      val policy = transferGovernedBy("semiprivreal", realGuard)
      val mint = MintAsset(a1, SchemaRef(asset("semiprivreal"), VersionReq.Latest), aliceHolder, 100L)
      val transfer = ApplyMorphism(
        a1,
        MorphismKind.Transfer,
        FiberOrdinal.MinValue,
        recipient = Some(bobHolder),
        witness = Some(realWitness(SemiPrivateRealProof.truePublicValues, SemiPrivateRealProof.trueProof))
      )
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        prT <- fixture.registry.generateProofs(transfer, Set(Alice))
        s3  <- combiner.insert(s2, Signed(transfer, prT))
      } yield expect(!wasRejected(s3)) and
      expect(assetOf(s3, a1).map(_.holder).contains(bobHolder))
    }
  }

  test("full semi-private guard: a REAL proof whose rule returned FALSE is rejected (output binding)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))

      val policy = transferGovernedBy("semiprivrealf", realGuard)
      val mint = MintAsset(a1, SchemaRef(asset("semiprivrealf"), VersionReq.Latest), aliceHolder, 100L)
      // groth16_verify passes (a valid proof) but the rule evaluated FALSE => outputHash != keccak("true")
      // => the output binding fails => graceful rejection. The guard enforces the OUTCOME, not just validity.
      val transfer = ApplyMorphism(
        a1,
        MorphismKind.Transfer,
        FiberOrdinal.MinValue,
        recipient = Some(bobHolder),
        witness = Some(realWitness(SemiPrivateRealProof.falsePublicValues, SemiPrivateRealProof.falseProof))
      )
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        prT <- fixture.registry.generateProofs(transfer, Set(Alice))
        s3  <- combiner.insert(s2, Signed(transfer, prT))
      } yield expect(wasRejected(s3)) and
      expect(assetOf(s3, a1).map(_.holder).contains(aliceHolder))
    }
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // The word offsets are the SDK<->chain contract (0x|exprHash|dataHash|outputHash|ok, 64-hex words).
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  pureTest("public-values word offsets are 2 / 66 / 130 / 194 (the @ottochain/sdk/zk layout)") {
    expect(List(0, 1, 2, 3).map(SemiPrivateGuard.wordOffset) == List(2, 66, 130, 194))
  }
}
