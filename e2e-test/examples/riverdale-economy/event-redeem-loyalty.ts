/**
 * `redeem_loyalty` event for the RETAILER (signed by bob) — a transition that exists ONLY in retailer
 * v2. Awards `points` (50) onto the `loyaltyPoints` field the v1→v2 migration introduced (0 → 50). Proves
 * the upgraded definition is live: this event has no transition in v1 and would be a NoTransitionFound
 * there, but resolves on v2.
 */
export default (): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'redeem_loyalty',
  payload: { points: 50 },
});
