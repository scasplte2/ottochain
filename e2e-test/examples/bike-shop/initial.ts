// Initial data for the three bike-shop fibers, selected by the step's `eventData.fiber`.
import type { Wallets } from '../../lib/types.ts';

export default ({ wallets, session, eventData }: {
  wallets: Wallets; session: { fibers: Record<string, string> }; eventData?: { fiber?: string };
}) => {
  const need = (name: string) => {
    const a = wallets[name]?.address;
    if (!a) throw new Error(`bike-shop needs wallet "${name}" (--wallets maya,agent,spoke,guild,rosa)`);
    return a;
  };
  switch (eventData?.fiber) {
    case 'desk':
      return { supplier: need('spoke'), ordersReceived: 0, dispatched: 0 };
    case 'mandate': {
      const desk = session.fibers.desk;
      if (!desk) throw new Error('create the supplier order desk before the mandate');
      return {
        owner: need('maya'), agent: need('agent'), approvedSuppliers: [desk],
        perOrderLimitCents: 2500, remainingCents: 10000, expiresAt: 1000000, lastRequestNumber: 0,
      };
    }
    case 'trade':
      return { applicant: need('maya'), supplier: need('spoke'), rule: 'credit score >= 700' };
    default:
      throw new Error('initial.ts needs eventData.fiber = desk | mandate | trade');
  }
};
