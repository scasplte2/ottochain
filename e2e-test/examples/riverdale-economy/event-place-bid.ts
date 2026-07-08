/**
 * `place_bid` event for the spawned AUCTION child (signed by bob). The child's `place_bid` transition
 * (listed → bid_received) records `highestBid = event.bidAmount` and `highestBidder = event.bidderId`.
 * bidderId is bob's wallet address (the bidder party). bob must be one of the child's `owners` (set via
 * `auctionOwners` in list_item) for this transition to be authorized.
 */
export default (context: {
  wallets: Record<string, { address: string }>;
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'place_bid',
  payload: {
    bidAmount: 150,
    bidderId: context.wallets.bob.address,
  },
});
