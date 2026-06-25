/**
 * FIXED, deterministic asset instance ids for the riverdale-economy slice.
 *
 * Asset ids MUST be stable across the whole flow (mint → assertAsset → _transferAsset → assertAsset),
 * so they are defined ONCE here and imported everywhere — NEVER `crypto.randomUUID()` (a fresh id each
 * read would make the custody assertions un-observable). These are valid v4-shaped UUIDs (version nibble
 * `4`, variant nibble `8`); `UUID.fromString` on the chain parses them, and they are statistically
 * disjoint from the random fiber ids the runner mints for `as:` aliases.
 */

/** GOODS.asset — combinable + transferable inventory the manufacturer holds, then ships to the retailer. */
export const GOODS_ASSET_ID = 'a55e7000-0000-4000-8000-000000000001';

/** RVD.asset — the fungible currency, minted to carol's wallet. */
export const RVD_ASSET_ID = 'a55e7000-0000-4000-8000-000000000002';
