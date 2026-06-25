/**
 * MintAsset body — mint 500 GOODS into the MANUFACTURER fiber's custody.
 *
 * Holder is `{ Fiber: { fiberId } }` (AssetHolder.Fiber wire form). The manufacturer must HOLD the GOODS
 * before `fulfill_order` so its `_transferAsset` (R1 holder defense in AssetCombiner.applyFiberTransfer:
 * `source.holder == AssetHolder.Fiber(emittingFiberId)`) is satisfied. The runner calls this with
 * `context = { wallets, session, eventData }`, so the manufacturer's minted fiberId is resolved
 * dynamically from `context.session.fibers.manufacturer` (the alias→fiberId map).
 *
 * Signed-message discipline (CLAUDE.md #1): only required + present fields — `expiresAt`/`provenance`/
 * `witness` are omitted (Option ⇒ dropNulls keeps the signed canonical aligned).
 */
import { GOODS_ASSET_ID } from './ids.ts';

export default (context: {
  session: { fibers: Record<string, string> };
}): Record<string, unknown> => ({
  assetId: GOODS_ASSET_ID,
  policyRef: { name: 'goods.asset', version: { Exact: { version: '1.0.0' } } },
  holder: { Fiber: { fiberId: context.session.fibers.manufacturer } },
  amount: 500,
});
