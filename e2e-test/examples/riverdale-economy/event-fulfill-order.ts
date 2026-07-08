/**
 * `fulfill_order` event for the MANUFACTURER (signed by alice).
 *
 * The manufacturer's `fulfill_order` effect does TWO cross-fiber things in one transition:
 *   (a) `_triggers` the RETAILER's `receive_shipment` (the targetMachineId resolves from `event.retailerId`),
 *   (b) `_transferAsset` moves the GOODS instance into the retailer's custody (recipient = the retailer's
 *       fiberId; EffectExtractor.parseRecipient maps a UUID-shaped string → AssetHolder.Fiber).
 *
 * Both the retailer fiberId and the GOODS assetId are carried on the payload — resolved dynamically here
 * from `context.session.fibers.retailer` (alias→fiberId) and the fixed GOODS id. `quantity` (500) is
 * forwarded into the trigger payload as the retailer's `receive_shipment` quantity AND used by the
 * manufacturer's `inventory -= quantity` book-keeping (initial inventory 1000 ⇒ guard 1000 >= 500 holds).
 */
import { GOODS_ASSET_ID } from './ids.ts';

export default (context: {
  session: { fibers: Record<string, string> };
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'fulfill_order',
  payload: {
    retailerId: context.session.fibers.retailer,
    goodsAssetId: GOODS_ASSET_ID,
    quantity: 500,
  },
});
