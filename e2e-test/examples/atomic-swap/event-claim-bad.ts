// WRONG preimage: poseidon([secret]) != state.hashLock => the hash-lock stays shut => the guard denies
// the transition => ML0 records a RejectionReceipt and the fiber stays at LOCKED (sequence unchanged).
//
// Anyone can attempt a claim, but only the holder of the true preimage can open the lock — exercising
// the `poseidon` opcode as a real cryptographic gate (not a comparison the submitter controls).
export default () => ({
  eventName: "claim",
  payload: {
    secret:
      "0x0000000000000000000000000000000000000000000000000000000000000063",
  },
});
