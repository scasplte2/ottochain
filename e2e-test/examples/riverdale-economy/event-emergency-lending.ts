/**
 * `emergency_lending` event for the FED (signed by erin) — a transition that exists ONLY in fed.machine
 * v2 (stable → emergency_lending). Its schema command `EmergencyLending` has NO fields and the effect
 * reads no event data (it just bumps `emergencyLoans`), so the payload is empty. Proves the upgraded
 * definition is live: this event has no transition in v1 (NoTransitionForEvent there) but resolves on v2.
 */
export default (): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'emergency_lending',
  payload: {},
});
