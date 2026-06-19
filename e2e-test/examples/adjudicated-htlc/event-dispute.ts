// Either party flags the contract as disputed when the swap stalls — e.g. the counterparty is
// WITHHOLDING the preimage to hold these funds hostage until the timeout (the griefing / free-option
// attack). Moving to DISPUTED opens the contract for a trusted adjudicator to resolve before timeout.
// Raising a dispute is harmless on its own (resolution is gated on the adjudicator's signature); a
// non-empty reason is required so the dispute is on the record.
export default () => ({
  eventName: "dispute",
  payload: {
    reason: "counterparty withholding preimage past the agreed exchange window",
  },
});
