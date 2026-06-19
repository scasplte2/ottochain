// A FORGED ruling: the valid release signature with its final nibble flipped. schnorr_verify against
// state.adjudicatorPubKey fails => the guard denies the resolution => ML0 rejects and the fiber stays at
// DISPUTED. This is the security property — only the real adjudicator can resolve; nobody can steal the
// funds by faking a ruling.
export default () => ({
  eventName: "adjudicate_release",
  payload: {
    ruling:
      "0x20897bdbd54e64e2978ecf40364b400fbbbe702e0f04db4149be54938b2adf290990d9797f99b0eafd73ddf4a6201584392c019b3b537d1ccc9157bffeaed0be2aca7f8b7577a3a98999714adf9b08580a26cffac9d613ad593c6d05857daba0",
  },
});
