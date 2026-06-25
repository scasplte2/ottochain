/**
 * `collect_taxes` event for the GOVERNANCE fiber (signed by frank). The gov's effect is a BROADCAST tax
 * sweep: THREE `_triggers` directives fire `pay_taxes` on the manufacturer, retailer, and consumer in one
 * transition. Each trigger payload carries `taxAmount`, `taxAssetId`, and `govId` (= the gov machineId,
 * stamped by the effect via `{var:"machineId"}`):
 *   - manufacturer / retailer `pay_taxes` are stateData-only (taxesPaid += taxAmount) — they ignore the
 *     extra payload fields.
 *   - consumer `pay_taxes` ALSO `_transferAsset`s the pre-minted RVD tax instance (RVD_TAX_ID, held by the
 *     consumer fiber) into the gov fiber's custody (recipient = event.govId → AssetHolder.Fiber).
 * All three target fiberIds resolve from `context.session.fibers`.
 */
import { RVD_TAX_ID } from './ids.ts';

export default (context: {
  session: { fibers: Record<string, string> };
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'collect_taxes',
  payload: {
    manufacturerId: context.session.fibers.manufacturer,
    retailerId: context.session.fibers.retailer,
    consumerId: context.session.fibers.consumer,
    govId: context.session.fibers.gov,
    taxAmount: 50,
    taxAssetId: RVD_TAX_ID,
  },
});
