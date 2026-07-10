/**
 * `list_item` event for the CONSUMER (signed by carol). The consumer's effect `_spawn`s a child auction
 * fiber at the DETERMINISTIC childId AUCTION_CHILD_ID (so later steps can address it via `fiber:` the
 * literal id) and moves the consumer ACTIVE-loop state debt_current → marketplace_selling.
 *
 * `auctionOwners` becomes the spawned child's `owners` (SpawnProcessor: child.owners = resolvedOwners).
 * A spawned fiber's transitions are gated by `owners ∪ authorizedSigners` (FiberRules.updateSignedBy-
 * OwnerOrParticipant), and a spawn CANNOT set `authorizedSigners` — so BOTH carol (seller) and bob
 * (bidder) must be `owners`, otherwise bob's `place_bid` / `accept_bid` would be rejected
 * `NotSignedByAuthorizedParty` at ML0. Under the H1 fail-closed subset floor the child owners must be
 * ⊆ the parent consumer's owners, so the consumer is created co-owned by [carol, bob] to match (see
 * example.ts). This models a CLOSED auction (bidder set fixed at spawn); an open/public auction would
 * instead rely on `transitionPolicy: Open` (not yet wired end-to-end).
 */
import { AUCTION_CHILD_ID } from './ids.ts';

export default (context: {
  wallets: Record<string, { address: string }>;
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'list_item',
  payload: {
    auctionId: AUCTION_CHILD_ID,
    reservePrice: 100,
    itemName: 'vintage-comic',
    auctionOwners: [context.wallets.carol.address, context.wallets.bob.address],
  },
});
