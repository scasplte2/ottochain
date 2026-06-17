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
import xyz.kd5ujc.schema.fiber.FiberLogEntry
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser.decode
import weaver.SimpleIOSuite

/**
 * Σ-protocol (`sigma_verify`)-gated `mintPolicy` — the "richer dynamics" companion to
 * [[ZkGatedMorphismSuite]]. Where the sibling suite gates on a Poseidon-Merkle membership proof
 * (`pmt_verify`) or a Groth16 SNARK (`groth16_verify`), this one gates a proof-gated mint on a
 * recursive CDS Σ-threshold proof (`sigma_verify`) AND a dynamic context predicate, demonstrating
 * boolean COMPOSITION of an on-chain threshold proof with a guard-context check:
 *
 * {{{
 *   { "and": [ { "<=": [ {"var":"amount"}, CAP ] },
 *              { "sigma_verify": [ <proposition>, {"var":"witness.proof"}, {"var":"witness.message"} ] } ] }
 * }}}
 *
 * The Σ vector (proposition / proof / message) is LIFTED VERBATIM from metakit's conformance
 * corpus (`zk_opcode_test_vectors.json`, `"sigma"` category, the
 * "THRESHOLD 2-of-3 (exactly k witnesses) -> true" case, whose `expected == "true"`), so we never
 * hand-roll a Σ prover. The proposition is FIXED in the policy guard; the proof tree and message
 * ride on the reserved `witness` key the combiner injects into `mintContext`.
 *
 * Three cases prove both gates are live and that they compose:
 *   1. amount ≤ CAP + the valid (proof, message) witness   → mint SUCCEEDS,
 *   2. amount > CAP + the valid witness                     → CombineRejected (predicate gates, even
 *                                                             though the Σ proof is valid),
 *   3. amount ≤ CAP + a tampered witness (wrong message)    → CombineRejected (`sigma_verify` → false).
 *
 * CAVEAT (carried from §8 / `AssetCombiner.morphismContext`): metakit's Σ verifier has no public
 * security audit — a `sigma_verify` guard must not protect real value until that verifier is audited.
 */
object SigmaGatedMorphismSuite extends SimpleIOSuite {

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
  private val a1 = UUID.fromString("dddddddd-0000-4000-8000-000000000001")
  private val a2 = UUID.fromString("dddddddd-0000-4000-8000-000000000002")
  private val a3 = UUID.fromString("dddddddd-0000-4000-8000-000000000003")

  // ── Known-good Σ vector, lifted VERBATIM from metakit's conformance corpus ──────────────────────
  // metakit `src/test/resources/conformance/zk_opcode_test_vectors.json`, `"sigma"` category, the
  // case noted "THRESHOLD 2-of-3 (exactly k witnesses) -> true" (`expected == "true"`). Its `expr`
  // is `{"sigma_verify":[<proposition>,<proof>,<message>]}`; we split out the three args. Because the
  // whole vector evaluates to `true`, `sigma_verify(proposition, proof, message)` verifies.

  /** arg0 — the threshold PROPOSITION (2-of-3 over three dlog public keys). FIXED in the guard. */
  private val sigmaPropositionJson: String =
    """{"type":"threshold","k":2,"children":[""" +
    """{"type":"dlog","pk":"0x20418c4b55f7576f6c62f9d0e1e0e52cf09a179566d72c2c8f2c301f54e4a536064573dfb6d9d4f1870737375e39282e99ae8b403c2b7f4ff57add81d9532a51"},""" +
    """{"type":"dlog","pk":"0x19f5b932805050d67ac80bb61305891dc27068a18b9c4589addfbd520ac1e1cf0f45f70a06ed93bc4888508d91263fecc172fcfc8ec1f6dd91310d0c73cc09d1"},""" +
    """{"type":"dlog","pk":"0x2d7566e6e023fe09ff134db395c315cd2ccf3a991e1540eac153180b2fb97ace1301357a95587073a2181744f47c66b62d1406997ee2f05327e5fd777110b850"}]}"""

  /** arg1 — the PROOF tree (per-node `e`/`z`). Carried on the witness under `proof`. */
  private val sigmaProofJson: String =
    """{"type":"threshold","e":"0xce420da67e40248441b4c4146bc16da56a3e2929d7f5e5ff62640e2bd877dc","k":2,"children":[""" +
    """{"type":"dlog","e":"0x15f2ae21031ba7b77cf9769d3e37104c31f11ab90ebcd5c50ac9165a616e59","z":"0x2d93c770d287eb098e1300bd307339648ff1431149a2fd8117f1a89cedbb1e08"},""" +
    """{"type":"dlog","e":"0x633950b384f639e23b2ebb1dc136976cdcbb4f127e67858bb2253ec9b145cd","z":"0x21fc6fb5c553a654f8f3258061797a26f569506c704916fbe4de86b16502eb46"},""" +
    """{"type":"dlog","e":"0xb889f334f9adbad10663099494c0ea8587747c82a72eb5b1da8826b8085c48","z":"0x2da853b72c74b1d9a09ccd066474e11df57eb55fe1234dd16e723e11960e283e"}]}"""

  /** arg2 — the MESSAGE (hex of ascii "authorize sigma"). Carried on the witness under `message`. */
  private val sigmaMessageHex: String = "0x617574686f72697a65207369676d61"

  /**
   * A wrong message — the valid message with its last byte flipped (`...6d61` → `...6d62`), i.e.
   * same length/shape but a different transcript → the Σ challenges no longer reconstruct →
   * `sigma_verify` returns false.
   */
  private val sigmaMessageHexWrong: String = "0x617574686f72697a65207369676d62"

  private def parseJlv(json: String): JsonLogicValue =
    decode[JsonLogicValue](json).fold(throw _, identity)

  private val proposition: JsonLogicValue = parseJlv(sigmaPropositionJson)
  private val proofValue: JsonLogicValue = parseJlv(sigmaProofJson)

  /** Witness = `{proof: <arg1>, message: <arg2>}` (the proof tree + the message the guard hashes). */
  private val validWitness: JsonLogicValue =
    MapValue(Map("proof" -> proofValue, "message" -> StrValue(sigmaMessageHex)))

  /** Tampered witness: same valid proof, but the WRONG message → `sigma_verify` → false. */
  private val tamperedWitness: JsonLogicValue =
    MapValue(Map("proof" -> proofValue, "message" -> StrValue(sigmaMessageHexWrong)))

  // ── The guard: boolean composition of a context predicate AND a Σ-threshold proof ───────────────

  private val Cap: BigInt = 100

  /**
   * `{ "and": [ { "<=": [ {"var":"amount"}, CAP ] },
   *             { "sigma_verify": [ <proposition literal>, {"var":"witness.proof"}, {"var":"witness.message"} ] } ] }`
   *
   * The proposition is a literal baked into the policy; the proof tree and message are read from the
   * injected `witness` context. This is the §8 ZkVerify-gated-mint shape, enriched with a dynamic
   * `amount <= CAP` predicate so the two gates compose.
   */
  private val sigmaGuard: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.AndOp,
      List(
        ApplyExpression(
          JsonLogicOp.Leq,
          List(VarExpression(Left("amount")), ConstExpression(IntValue(Cap)))
        ),
        ApplyExpression(
          JsonLogicOp.SigmaVerifyOp,
          List(
            ConstExpression(proposition),
            VarExpression(Left("witness.proof")),
            VarExpression(Left("witness.message"))
          )
        )
      )
    )

  // ── Policy builders (mirrors ZkGatedMorphismSuite) ──────────────────────────────────────────────

  private def openSupply(mintGuard: Option[JsonLogicExpression]): SupplyPolicy =
    SupplyPolicy(maxSupply = None, mintPolicy = mintGuard, burnPolicy = None, decimals = Some(0))

  /** A policy whose `mintPolicy` is the Σ-gate (proof-gated mint). */
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
  // sigma_verify-gated mint with a composed dynamic predicate — the headline test
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  test(
    "a sigma_verify mintPolicy composed with a dynamic predicate: ≤CAP+valid mints, >CAP rejects, tampered rejects"
  ) {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val aliceHolder = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val policy = mintGovernedBy("sigmabridge", sigmaGuard)
      val ref = SchemaRef(asset("sigmabridge"), VersionReq.Latest)

      // (1) amount ≤ CAP (50) + the valid (proof, message) witness → both gates pass → mints.
      val mintOk = MintAsset(a1, ref, aliceHolder, 50L, witness = Some(validWitness))
      // (2) amount > CAP (150) + the SAME valid witness → `<=` predicate fails → CombineRejected,
      //     even though the Σ proof is valid (proves the boolean composition gates).
      val mintOverCap = MintAsset(a2, ref, aliceHolder, 150L, witness = Some(validWitness))
      // (3) amount ≤ CAP (50) + a TAMPERED witness (wrong message) → `sigma_verify` false → rejected.
      val mintTampered = MintAsset(a3, ref, aliceHolder, 50L, witness = Some(tamperedWitness))

      for {
        prP <- fixture.registry.generateProofs(policy, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(policy, prP))
        // (1) below cap + valid Σ proof → mints
        prOk <- fixture.registry.generateProofs(mintOk, Set(Alice))
        sOk  <- combiner.insert(s1, Signed(mintOk, prOk))
        // (2) above cap + valid Σ proof → rejected, asset not created
        prCap <- fixture.registry.generateProofs(mintOverCap, Set(Alice))
        sCap  <- combiner.insert(s1, Signed(mintOverCap, prCap))
        // (3) below cap + tampered Σ proof → rejected, asset not created
        prTmp <- fixture.registry.generateProofs(mintTampered, Set(Alice))
        sTmp  <- combiner.insert(s1, Signed(mintTampered, prTmp))
      } yield
      // (1) both gates satisfied → mint applied
      expect(!wasRejected(sOk)) and expect(assetOf(sOk, a1).map(_.amount).contains(50L)) and
      expect(assetOf(sOk, a1).map(_.holder).contains(aliceHolder)) and
      // (2) dynamic predicate gate fires despite a valid Σ proof → rejected, not created
      expect(wasRejected(sCap)) and expect(assetOf(sCap, a2).isEmpty) and
      // (3) Σ gate fires (tampered proof) → rejected, not created
      expect(wasRejected(sTmp)) and expect(assetOf(sTmp, a3).isEmpty)
    }
  }
}
