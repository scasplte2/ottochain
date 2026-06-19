// The trusted high-reputation adjudicator rules RELEASE. `ruling` is a real BN254 Schnorr signature by
// the adjudicator's key (state.adjudicatorPubKey) over the message "release:HTLC-ADJ-001" — produced
// off-chain with the same construction the metakit `schnorr_verify` opcode checks (proof = R||s,
// c = SHA256(R||pk||msg) mod r, s = k + c*x). The guard re-verifies it, so the adjudicator cannot be
// impersonated and the ruling is bound to this contract + outcome (DISPUTED -> CLAIMED, funds to recipient).
export default () => ({
  eventName: "adjudicate_release",
  payload: {
    ruling:
      "0x20897bdbd54e64e2978ecf40364b400fbbbe702e0f04db4149be54938b2adf290990d9797f99b0eafd73ddf4a6201584392c019b3b537d1ccc9157bffeaed0be2aca7f8b7577a3a98999714adf9b08580a26cffac9d613ad593c6d05857dabae",
  },
});
