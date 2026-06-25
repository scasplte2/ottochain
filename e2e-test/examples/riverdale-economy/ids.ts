/**
 * FIXED, deterministic asset-instance + spawned-fiber ids for the riverdale-economy example.
 *
 * Ids MUST be stable across the whole flow (mint → assertAsset → _transferAsset → assertAsset, or
 * spawn → bid → assert), so they are defined ONCE here and imported everywhere — NEVER
 * `crypto.randomUUID()` (a fresh id each read would make the custody/spawn assertions un-observable).
 * These are valid v4-shaped UUIDs (version nibble `4`, variant nibble `8`); `UUID.fromString` on the
 * chain parses them, and they are statistically disjoint from the random fiber ids the runner mints for
 * `as:` aliases. The `_transferAsset` extractor disambiguates a UUID-shaped recipient → AssetHolder.Fiber.
 */

/** GOODS.asset — combinable + transferable inventory the manufacturer holds, then ships down the chain. */
export const GOODS_ASSET_ID = 'a55e7000-0000-4000-8000-000000000001';

/** RVD.asset — the fungible currency, minted to carol's wallet in the de-risk slice. */
export const RVD_ASSET_ID = 'a55e7000-0000-4000-8000-000000000002';

// ── Fiber-custody RVD instances (asset choreography). Each is a SEPARATE pre-minted RVD instance,
//    minted DIRECTLY into the fiber that will spend it, then moved WHOLE between fibers via the
//    definitions' `_transferAsset` effects (R1: the emitting fiber must hold the instance). ──

/** RVD lent: minted to the BANK fiber, transferred bank→consumer by `underwrite`. */
export const RVD_LOAN_ID = 'a55e7000-0000-4000-8000-000000000010';
/** RVD payment: minted to the CONSUMER fiber, transferred consumer→retailer by `buy`. */
export const RVD_PAY_ID = 'a55e7000-0000-4000-8000-000000000011';
/** RVD repayment: minted to the CONSUMER fiber, transferred consumer→bank by `make_payment`. */
export const RVD_REPAY_ID = 'a55e7000-0000-4000-8000-000000000012';
/** RVD taxes: minted to the CONSUMER fiber, transferred consumer→gov by `pay_taxes`. */
export const RVD_TAX_ID = 'a55e7000-0000-4000-8000-000000000013';

// ── Wallet-context morphism demos (SEPARATE from the fiber-custody flow; R1: signer == wallet holder). ──

/** RVD staked: minted to dave's WALLET, then `applyMorphism STAKE` (codomain E:=1; bumps seq, asset stays). */
export const RVD_STAKE_ID = 'a55e7000-0000-4000-8000-000000000014';

/**
 * Fractionalize demo source + its three shard outputs (DEFERRED — see example.ts P12 note):
 * Fractionalize CONSUMES the source record, which the runner's `applyMorphism` confirmation
 * (poll the source's state-proof for a seq advance) cannot observe. Body file + ids are shipped
 * for when the runner gains consuming-morphism confirmation; the live flow uses STAKE instead.
 */
export const RVD_FRAC_ID = 'a55e7000-0000-4000-8000-000000000020';
export const RVD_FRAC_A_ID = 'a55e7000-0000-4000-8000-000000000021';
export const RVD_FRAC_B_ID = 'a55e7000-0000-4000-8000-000000000022';
export const RVD_FRAC_C_ID = 'a55e7000-0000-4000-8000-000000000023';

/** Burn demo source (DEFERRED — Burn is terminal: it REMOVES the record, so the runner's seq-advance
 * confirmation can never satisfy. Body file + id shipped; not wired live. */
export const RVD_BURN_ID = 'a55e7000-0000-4000-8000-000000000024';

/** Deterministic child auction fiber id spawned by `consumer.list_item` (`_spawn` childId). */
export const AUCTION_CHILD_ID = 'a55e7000-0000-4000-8000-000000000030';

// ── FLOW 2 (negative tests) capped-policy mint instances. ──
/** First mint under the capped policy — fits the cap (accepted). */
export const CAPPED_A_ID = 'a55e7000-0000-4000-8000-000000000040';
/** Second mint under the capped policy — pushes derived supply over maxSupply (rejected at ML0). */
export const CAPPED_B_ID = 'a55e7000-0000-4000-8000-000000000041';
