/**
 * `make_payment` event for the CONSUMER (signed by carol). The consumer's effect `_triggers` the bank's
 * `payment_received` (carrying the `amount`) AND `_transferAsset`s the pre-minted RVD repayment instance
 * (RVD_REPAY_ID, held by the consumer fiber) into the bank fiber's custody — loan servicing in one step.
 * bankId resolves from `context.session.fibers.bank`; repayAssetId is the fixed RVD_REPAY_ID.
 */
import { RVD_REPAY_ID } from './ids.ts';

export default (context: {
  session: { fibers: Record<string, string> };
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'make_payment',
  payload: {
    bankId: context.session.fibers.bank,
    amount: 300,
    repayAssetId: RVD_REPAY_ID,
  },
});
