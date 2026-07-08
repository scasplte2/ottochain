/**
 * `list_item` event for the CONSUMER (signed by carol). The consumer's effect `_spawn`s a child auction
 * fiber at the DETERMINISTIC childId AUCTION_CHILD_ID (so later steps can address it via `fiber:` the
 * literal id) and moves the consumer ACTIVE-loop state debt_current → marketplace_selling.
 *
 * `auctionOwners` becomes the spawned child's `owners` (SpawnProcessor: child.owners = resolvedOwners).
 * A spawned fiber's transitions are gated by `owners ∪ authorizedSigners` (FiberRules.updateSignedBy-
 * OwnerOrParticipant), so BOTH carol (seller) and bob (bidder) are listed — otherwise bob's `place_bid`
 * / `accept_bid` would be rejected `NotSignedByAuthorizedParty` at ML0. (The PHASE2 contract sketch said
 * `[carol]`; bob is REQUIRED here because the bid + accept are signed by bob.)
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
