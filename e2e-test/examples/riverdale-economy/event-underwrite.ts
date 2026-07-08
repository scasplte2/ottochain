/**
 * `underwrite` event for the BANK (signed by dave). In ONE transition the bank's effect:
 *   (a) `_triggers` the consumer's `loan_funded` (carrying the loan `amount`), and
 *   (b) `_transferAsset` moves the pre-minted RVD loan instance (RVD_LOAN_ID, held by the bank fiber)
 *       into the consumer fiber's custody (recipient = the consumer fiberId → AssetHolder.Fiber).
 * The consumer goes ACTIVE → debt_current. consumerId/loanAssetId are carried on the payload.
 */
import { RVD_LOAN_ID } from './ids.ts';

export default (context: {
  session: { fibers: Record<string, string> };
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'underwrite',
  payload: {
    consumerId: context.session.fibers.consumer,
    amount: 5000,
    loanAssetId: RVD_LOAN_ID,
  },
});
