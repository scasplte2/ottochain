package xyz.kd5ujc.schema.asset

import io.constellationnetwork.metagraph_sdk.json_logic.core.JsonLogicOp
import io.constellationnetwork.metagraph_sdk.json_logic.{
  ApplyExpression,
  ConstExpression,
  IntValue,
  JsonLogicExpression,
  StrValue,
  VarExpression
}

/**
 * Builds the SEMI-PRIVATE guard: the JSON-Logic predicate a `Governed` [[MorphismSpec.guard]] (or a
 * `SupplyPolicy.mintPolicy`) embeds so that a holder can move/mint an asset by proving, OFF-CHAIN
 * (SP1 zk-jlvm), that some PRIVATE data satisfies a PUBLIC, pinned JLVM rule — carrying only
 * `{publicValues, proof}` on the signed op (the `witness` field the combiner injects).
 *
 * The guard pins three facts using only opcodes metakit already gas-meters — there is NO
 * `jlvm_pv_decode`; the opaque public-values blob is parsed with native `substr`/`cat`:
 *
 *   1. `groth16_verify[vkey, witness.publicValues, witness.proof]` — the SP1 proof is valid.
 *   2. the `exprHash` word of the public values == the policy's `logicHash` — the proof ran the
 *      INTENDED, legible rule (the pinned predicate, e.g. `{">=":[{"var":"score"},700]}`).
 *   3. the `outputHash` word == `keccak256("true")` — that rule evaluated to TRUE on the hidden data.
 *
 * `publicValues` rides as a `0x` + 256-hex-char string encoding `abi_encode(JlvmPublicValues)` =
 * `0x | exprHash | dataHash | outputHash | ok`, each word 32 bytes / 64 hex chars. So word `w` begins
 * at char `2 + 64*w` (skipping `0x`) and is 64 wide; `cat("0x", substr(pv, off, 64))` lifts it back to
 * a `bytes32` `0x`-hex value comparable with a literal. metakit's `substr` is 0-based `(start, length)`
 * and byte-matches the Rust/TS ports, so these offsets are identical on every VM.
 *
 * NOTE: there is no keccak opcode on-chain — `logicHash` and `keccakTrue` are off-chain keccak256
 * literals baked into the policy at creation time (computed by `@ottochain/sdk/zk`: `exprHash(rule)` and
 * `KECCAK_TRUE`). The chain only compares; binding `exprHash == logicHash` is what makes "the hidden
 * value satisfies THIS public rule" meaningful. Mirrors `@ottochain/sdk/zk` `semiPrivateGuard`.
 *
 * AUDIT CAVEAT: metakit's `groth16_verify` has no public security audit; a semi-private guard must not
 * protect real value until that verifier is audited (see ZkGatedMorphismSuite / asset-model.md §8).
 */
object SemiPrivateGuard {

  /** Char offset of public-values word `w` (0=exprHash, 1=dataHash, 2=outputHash, 3=ok) in the `0x` string. */
  def wordOffset(w: Int): Int = 2 + 64 * w

  private val publicValues: JsonLogicExpression = VarExpression(Left("witness.publicValues"))
  private val proof: JsonLogicExpression = VarExpression(Left("witness.proof"))

  /** Lift public-values word `w` back to a `0x`-hex value: `cat("0x", substr(witness.publicValues, off, 64))`. */
  private def pvWord(w: Int): JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.CatOp,
      List(
        ConstExpression(StrValue("0x")),
        ApplyExpression(
          JsonLogicOp.SubStrOp,
          List(publicValues, ConstExpression(IntValue(BigInt(wordOffset(w)))), ConstExpression(IntValue(BigInt(64))))
        )
      )
    )

  /** `groth16_verify[vkey, witness.publicValues, witness.proof]` — the SP1 proof verifies. */
  def groth16(vkey: String): JsonLogicExpression =
    ApplyExpression(JsonLogicOp.Groth16VerifyOp, List(ConstExpression(StrValue(vkey)), publicValues, proof))

  /** Binds the proof's `exprHash` word == the pinned rule's `logicHash` (the proof ran the intended rule). */
  def exprBinding(logicHash: String): JsonLogicExpression =
    ApplyExpression(JsonLogicOp.EqOp, List(pvWord(0), ConstExpression(StrValue(logicHash))))

  /** Binds the proof's `outputHash` word == `keccak256("true")` (the pinned rule evaluated to true). */
  def outputBinding(keccakTrue: String): JsonLogicExpression =
    ApplyExpression(JsonLogicOp.EqOp, List(pvWord(2), ConstExpression(StrValue(keccakTrue))))

  /**
   * The full semi-private guard: the proof verifies AND binds to the pinned rule AND (by default) to a
   * `true` result. `vkey` is the zk-jlvm program verification key (`bytes32` `0x`-hex); `logicHash` and
   * `keccakTrue` are the off-chain keccak literals (`exprHash(rule)` and `KECCAK_TRUE`). Set
   * `requireTrue = false` to bind only WHICH rule ran, not its boolean outcome.
   */
  def guard(
    vkey:        String,
    logicHash:   String,
    keccakTrue:  String,
    requireTrue: Boolean = true
  ): JsonLogicExpression = {
    val clauses =
      if (requireTrue) List(groth16(vkey), exprBinding(logicHash), outputBinding(keccakTrue))
      else List(groth16(vkey), exprBinding(logicHash))
    ApplyExpression(JsonLogicOp.AndOp, clauses)
  }
}
