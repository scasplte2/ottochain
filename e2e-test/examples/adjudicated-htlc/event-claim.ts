// Honest path — recipient reveals the correct preimage; poseidon([secret]) === state.hashLock and we
// are within the timeout => LOCKED -> CLAIMED. (Same hash-lock as the basic atomic-swap example.)
export default () => ({
  eventName: "claim",
  payload: {
    secret:
      "0x00000000000000000000000000000000000000000000000000000000075bcd15",
  },
});
