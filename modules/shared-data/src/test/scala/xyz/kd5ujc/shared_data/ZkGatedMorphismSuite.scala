package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.crypto.zk.merkle.{Fr, PoseidonMerkleProof, PoseidonMerkleTree}
import io.constellationnetwork.metagraph_sdk.crypto.zk.poseidon.Poseidon
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.JsonLogicOp
import io.constellationnetwork.metagraph_sdk.json_logic.ops.HexBytes
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
 * ZkVerify-gated morphisms (docs/proposals/asset-model.md §8) — the consensus "zk-as-integrity" wiring:
 * a `Governed` morphism's `MorphismSpec.guard` (or a `mintPolicy`) REQUIRES a proof / Merkle-membership
 * witness carried on the signed op (the new `witness: Option[JsonLogicValue]` field), by calling one of
 * metakit's already-wired, gas-metered verifier opcodes (`pmt_verify` / `groth16_verify`) over the
 * `witness` context key the combiner injects. This is PURE WIRING — the proof is built and verified by
 * metakit's primitives; the combiner only enriches the guard context and evaluates the existing guard
 * through `AssetCombiner.evalGuardOrReject`.
 *
 * The headline test constructs a REAL Poseidon-Merkle inclusion proof with metakit's `PoseidonMerkleTree`
 * (same tree the on-chain `pmt_verify` opcode folds against), proving the opcode agrees with the builder
 * and gates the morphism: a valid inclusion proof lets the Transfer through; a tampered one is a graceful
 * `CombineRejected` with the asset unchanged.
 *
 * CAVEAT: metakit's verifier has no public security audit — a ZkVerify guard must not protect real value
 * until that verifier is audited (see asset-model.md §8 / `AssetCombiner.morphismContext` scaladoc).
 */
object ZkGatedMorphismSuite extends SimpleIOSuite {

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

  // Deterministic asset ids.
  private val a1 = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001")
  private val a2 = UUID.fromString("eeeeeeee-0000-4000-8000-000000000002")
  private val a3 = UUID.fromString("eeeeeeee-0000-4000-8000-000000000003")

  // ── Real Poseidon-Merkle tree + inclusion proof (built with metakit's PoseidonMerkleTree) ──────

  /** 0x-prefixed lowercase 32-byte hex of an Fr field element (the encoding every crypto opcode expects). */
  private def fr(v: BigInt): String = HexBytes.encodeFr(v).fold(throw _, identity)

  /** A canonical Fr leaf derived from a seed (a Poseidon output is always canonical). */
  private def leafFor(seed: Long): BigInt = Poseidon.hash(Seq(BigInt(seed).mod(Fr.R)))

  private val MerkleDepth = 4 // capacity 2^4 = 16 positions; small but a real multi-level proof

  // A tree with three live leaves at distinct positions; we prove inclusion of the one at `provePos`.
  private val provePos: BigInt = BigInt(5)
  private val proveLeaf: BigInt = leafFor(100L)

  private val tree: PoseidonMerkleTree =
    PoseidonMerkleTree.fromLeaves(
      MerkleDepth,
      List(BigInt(2) -> leafFor(10L), provePos -> proveLeaf, BigInt(11) -> leafFor(30L))
    )

  private val proof: PoseidonMerkleProof = tree.inclusionProof(provePos)
  private val rootHex: String = fr(tree.root)

  /** The witness payload a holder carries on the op: the inclusion proof as a JSON-Logic map. */
  private def merkleWitness(leaf: BigInt, index: BigInt, siblings: Vector[BigInt]): JsonLogicValue =
    MapValue(
      Map(
        "leaf"     -> StrValue(fr(leaf)),
        "index"    -> IntValue(index),
        "siblings" -> ArrayValue(siblings.map(s => StrValue(fr(s))).toList)
      )
    )

  private val validWitness: JsonLogicValue = merkleWitness(proveLeaf, provePos, proof.siblings)

  // Tamper a single sibling (a well-formed but WRONG inclusion proof → opcode returns false).
  private val tamperedWitness: JsonLogicValue =
    merkleWitness(proveLeaf, provePos, proof.siblings.updated(0, leafFor(999L)))

  /**
   * A guard that calls `pmt_verify[<root-literal>, witness.leaf, witness.index, witness.siblings]`. The
   * root is a literal baked into the policy; the leaf/index/siblings are read from the injected `witness`
   * context. This is exactly the §8 ZkVerify-gated-morphism shape.
   */
  private val pmtGuard: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.PmtVerifyOp,
      List(
        ConstExpression(StrValue(rootHex)),
        VarExpression(Left("witness.leaf")),
        VarExpression(Left("witness.index")),
        VarExpression(Left("witness.siblings"))
      )
    )

  /**
   * A guard that calls `groth16_verify[<dummy-vkey>, witness.publicValues, witness.proof]`. With a
   * garbage/empty proof witness this either returns false or fails to verify — both are a graceful guard
   * denial. The vkey is a correct-WIDTH (32-byte) dummy so the opcode reaches the verifier rather than
   * erroring on shape.
   */
  private val groth16Guard: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.Groth16VerifyOp,
      List(
        ConstExpression(StrValue("0x" + "00" * 32)), // 32-byte dummy vkey (correct width)
        VarExpression(Left("witness.publicValues")),
        VarExpression(Left("witness.proof"))
      )
    )

  // A garbage groth16 witness (empty public values + a short, cryptographically-invalid proof).
  private val garbageGroth16Witness: JsonLogicValue =
    MapValue(
      Map(
        "publicValues" -> StrValue("0x"),
        "proof"        -> StrValue("0x" + "00" * 8)
      )
    )

  // ── Policy builders ────────────────────────────────────────────────────────────────────────────

  // Open supply (mint always allowed) unless overridden, all morphisms Public except where we Govern.
  private def openSupply(
    mintGuard: Option[JsonLogicExpression] = Some(ConstExpression(BoolValue(true)))
  ): SupplyPolicy =
    SupplyPolicy(maxSupply = None, mintPolicy = mintGuard, burnPolicy = None, decimals = Some(0))

  /** A policy whose Transfer is `Governed` by `guard` (the ZkVerify gate); other morphisms Public. */
  private def transferGovernedBy(name: String, guard: JsonLogicExpression): CreateAssetPolicy =
    CreateAssetPolicy(
      asset(name),
      SemVer(1, 0, 0),
      TokenBehavior.Fungible, // T=1 so Transfer is structurally allowed
      openSupply(),
      SortedMap(MorphismKind.Transfer -> MorphismSpec(MorphismVisibility.Governed, None, None, Some(guard))),
      stateShape
    )

  /** A policy whose `mintPolicy` is the ZkVerify gate (proof-gated mint). */
  private def mintGovernedBy(name: String, guard: JsonLogicExpression): CreateAssetPolicy =
    CreateAssetPolicy(
      asset(name),
      SemVer(1, 0, 0),
      TokenBehavior.Fungible,
      openSupply(mintGuard = Some(guard)),
      SortedMap(MorphismKind.Transfer -> MorphismSpec(MorphismVisibility.Public, None, None, None)),
      stateShape
    )

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // pmt_verify-gated morphism — the headline test (positive + negative)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  test("pmt_verify-gated Transfer SUCCEEDS with a valid inclusion proof and is REJECTED with a tampered one") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))

      val policy = transferGovernedBy("pmtgold", pmtGuard)
      val mint = MintAsset(a1, SchemaRef(asset("pmtgold"), VersionReq.Latest), aliceHolder, 100L)
      // (a) Transfer carrying the VALID inclusion proof → guard passes → holder changes.
      val transferOk =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(bobHolder),
          witness = Some(validWitness)
        )
      // (b) Transfer carrying a TAMPERED witness (wrong sibling) → guard false → graceful CombineRejected.
      val transferBad =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(bobHolder),
          witness = Some(tamperedWitness)
        )
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        // (b) tampered witness on a fresh state (seq 0) — must reject, leave the asset at Alice/seq 0.
        prBad <- fixture.registry.generateProofs(transferBad, Set(Alice))
        sBad  <- combiner.insert(s2, Signed(transferBad, prBad))
        badReject = wasRejected(sBad)
        stillAlice = assetOf(sBad, a1).map(_.holder).contains(aliceHolder)
        seqUnchanged = assetOf(sBad, a1).map(_.sequenceNumber).contains(FiberOrdinal.MinValue)
        // (a) valid witness on the original state (seq 0) — must succeed, holder → Bob.
        prOk <- fixture.registry.generateProofs(transferOk, Set(Alice))
        sOk  <- combiner.insert(s2, Signed(transferOk, prOk))
        okClean = !wasRejected(sOk)
        movedToBob = assetOf(sOk, a1).map(_.holder).contains(bobHolder)
      } yield expect(assetOf(s2, a1).isDefined) and
      // (b) tampered inclusion proof → graceful rejection, asset untouched
      expect(badReject) and expect(stillAlice) and expect(seqUnchanged) and
      // (a) valid inclusion proof → morphism applied, holder moved
      expect(okClean) and expect(movedToBob)
    }
  }

  test("pmt_verify-gated Transfer is REJECTED when the witness is absent entirely (guard cannot verify)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))
      val policy = transferGovernedBy("pmtnowit", pmtGuard)
      val mint = MintAsset(a1, SchemaRef(asset("pmtnowit"), VersionReq.Latest), aliceHolder, 50L)
      // No witness → `witness` is NullValue → `{"var":"witness.leaf"}` resolves to null → opcode errors/false.
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
  // groth16_verify wiring — negative path (garbage proof → graceful rejection)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  test("groth16_verify-gated Transfer with a garbage proof witness is gracefully CombineRejected") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val bobHolder = AssetHolder.Wallet(fixture.registry.addresses(Bob))
      val policy = transferGovernedBy("grothgold", groth16Guard)
      val mint = MintAsset(a1, SchemaRef(asset("grothgold"), VersionReq.Latest), aliceHolder, 70L)
      val transfer =
        ApplyMorphism(
          a1,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(bobHolder),
          witness = Some(garbageGroth16Witness)
        )
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prM <- fixture.registry.generateProofs(mint, Set(Alice))
        s2  <- combiner.insert(s1, Signed(mint, prM))
        prT <- fixture.registry.generateProofs(transfer, Set(Alice))
        s3  <- combiner.insert(s2, Signed(transfer, prT))
      } yield expect(wasRejected(s3)) and
      // asset unchanged (still Alice, seq 0) — a failed zk proof never mutates state
      expect(assetOf(s3, a1).map(_.holder).contains(aliceHolder)) and
      expect(assetOf(s3, a1).map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // Proof-gated mint — the bridge use case: "mint iff this inclusion proof verifies"
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  test("a pmt_verify mintPolicy MINTS with a valid witness and is REJECTED with a bad/absent one") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val policy = mintGovernedBy("bridged", pmtGuard)
      val ref = SchemaRef(asset("bridged"), VersionReq.Latest)
      // (a) mint carrying the valid inclusion proof → mints.
      val mintOk = MintAsset(a1, ref, aliceHolder, 10L, witness = Some(validWitness))
      // (b) mint carrying a tampered witness → rejected, not created.
      val mintBad = MintAsset(a2, ref, aliceHolder, 10L, witness = Some(tamperedWitness))
      // (c) mint with NO witness → rejected, not created.
      val mintNone = MintAsset(a3, ref, aliceHolder, 10L)
      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        prA <- fixture.registry.generateProofs(mintOk, Set(Alice))
        sA  <- combiner.insert(s1, Signed(mintOk, prA))
        prB <- fixture.registry.generateProofs(mintBad, Set(Alice))
        sB  <- combiner.insert(s1, Signed(mintBad, prB))
        prC <- fixture.registry.generateProofs(mintNone, Set(Alice))
        sC  <- combiner.insert(s1, Signed(mintNone, prC))
      } yield
      // (a) valid proof mints
      expect(!wasRejected(sA)) and expect(assetOf(sA, a1).map(_.amount).contains(10L)) and
      // (b) tampered proof is rejected; asset not created
      expect(wasRejected(sB)) and expect(assetOf(sB, a2).isEmpty) and
      // (c) absent proof is rejected; asset not created
      expect(wasRejected(sC)) and expect(assetOf(sC, a3).isEmpty)
    }
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // Sanity: the off-chain PoseidonMerkleTree builder and the on-chain pmt_verify opcode agree
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  pureTest("the constructed inclusion proof verifies against metakit's PoseidonMerkleTree (builder/opcode parity)") {
    expect(PoseidonMerkleTree.verifyInclusion(proveLeaf, proof, tree.root)) and
    expect(
      !PoseidonMerkleTree.verifyInclusion(
        proveLeaf,
        proof.copy(siblings = proof.siblings.updated(0, leafFor(999L))),
        tree.root
      )
    )
  }
}
