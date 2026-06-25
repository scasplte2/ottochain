/**
 * `buy` event for the CONSUMER (signed by carol). In ONE transition the consumer's effect:
 *   (a) `_triggers` the retailer's `process_sale` (carrying buyerId = the consumer machineId + quantity),
 *   (b) `_transferAsset` moves the pre-minted RVD payment instance (RVD_PAY_ID, held by the consumer
 *       fiber) into the retailer fiber's custody.
 * The retailer's `process_sale` then `_transferAsset`s the GOODS instance back to the consumer (buyerId),
 * so this single causal step moves money one way and goods the other. retailerId/goodsAssetId/payAssetId
 * are carried on the payload; retailerId resolves from `context.session.fibers.retailer`.
 */
import { GOODS_ASSET_ID, RVD_PAY_ID } from './ids.ts';

export default (context: {
  session: { fibers: Record<string, string> };
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'buy',
  payload: {
    retailerId: context.session.fibers.retailer,
    quantity: 5,
    goodsAssetId: GOODS_ASSET_ID,
    payAssetId: RVD_PAY_ID,
  },
});
