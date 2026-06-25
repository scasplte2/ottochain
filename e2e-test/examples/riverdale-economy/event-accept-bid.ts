/**
 * `accept_bid` event for the spawned AUCTION child (signed by bob). The child's `accept_bid` transition
 * (bid_received → sold) reads `state.highestBid` / `state.highestBidder` (NOT the event payload), so no
 * payload fields are needed. Its effect `_triggers` the parent consumer's `sale_completed` (carrying the
 * winning bid amount + buyer), closing the marketplace loop (consumer marketplace_selling → debt_current).
 */
export default (): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'accept_bid',
  payload: {},
});
