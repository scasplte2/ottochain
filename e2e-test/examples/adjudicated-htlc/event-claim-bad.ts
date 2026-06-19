// WRONG preimage: poseidon([secret]) != state.hashLock => the hash-lock stays shut => the guard denies
// the claim => ML0 rejects and the fiber stays at LOCKED.
export default () => ({
  eventName: "claim",
  payload: {
    secret:
      "0x0000000000000000000000000000000000000000000000000000000000000063",
  },
});
