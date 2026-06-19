// CORRECT preimage: poseidon([secret]) == state.hashLock AND $ordinal <= timeoutOrdinal
// => the hash-lock opens => LOCKED -> CLAIMED.
//
// `secret` is a 32-byte field element (lowercase hex, exactly what the metakit `poseidon` opcode
// expects). The hashLock pinned in initial-data.json is poseidon([secret]) — computed off-chain with
// the SAME @constellation-network/metagraph-sdk-jlvm the chain runs, so the on-chain re-hash matches
// byte-for-byte. Revealing the preimage here is also what makes a two-party swap ATOMIC: the claim
// publishes `secret` on-chain (recorded in state.preimage), letting the counterparty open the matching
// HTLC with the same value.
export default () => ({
  eventName: "claim",
  payload: {
    secret:
      "0x00000000000000000000000000000000000000000000000000000000075bcd15",
  },
});
