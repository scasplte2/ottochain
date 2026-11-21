// The purchasing agent's `authorize` request. `payee` is a fiber alias in the session (the supplier's
// order desk) or a literal id (an unapproved supplier). The credit piece is a held store-credit shard.
import { DELIVERY_ID } from './ids.ts';

export default ({ session, eventData }: {
  session: { fibers: Record<string, string> };
  eventData: { requestNumber: number; amountCents: number; payeeFiber?: string; payeeId?: string; creditId: string; item: string };
}) => {
  const payee = eventData.payeeFiber ? session.fibers[eventData.payeeFiber] : eventData.payeeId;
  if (!payee) throw new Error('authorize.ts needs payeeFiber (session alias) or payeeId');
  return {
    eventName: 'authorize',
    payload: {
      requestNumber: eventData.requestNumber,
      amountCents: eventData.amountCents,
      payee,
      creditId: eventData.creditId,
      deliveryId: DELIVERY_ID,
      item: eventData.item,
    },
  };
};
