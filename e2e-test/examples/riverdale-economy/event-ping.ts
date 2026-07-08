/**
 * `ping` event for the FLOW-2 negative-test fiber — a self-loop on state `s0` (s0 → s0). Used by the
 * wrong-party test: alice owns the fiber, so a `ping` signed by bob is rejected at ML0 combine
 * (NotSignedByAuthorizedParty — FiberRules.updateSignedByOwnerOrParticipant runs in the L0Validator).
 */
export default (): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'ping',
  payload: {},
});
