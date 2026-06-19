// REFUND attempted BEFORE the timeout: the refund guard requires $ordinal > state.timeoutOrdinal, but
// timeoutOrdinal is set far in the future, so $ordinal <= timeoutOrdinal and the guard is FALSE =>
// rejected. This proves the time-lock half of the HTLC: the sender cannot reclaim early and front-run a
// legitimate claim. (After the timeout the same event would advance LOCKED -> REFUNDED.)
export default () => ({
  eventName: "refund",
  payload: {},
});
