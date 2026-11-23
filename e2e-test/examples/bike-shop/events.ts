// Small fixed events: the supplier's dispatch, the courier's delivery confirmation, the owner's revoke.
import { DELIVERY_ID, PADS_ID } from './ids.ts';

export default ({ eventData }: { eventData: { name: 'dispatch' | 'deliver' | 'revoke' } }) => {
  switch (eventData.name) {
    case 'dispatch':
      return { eventName: 'dispatch', payload: { deliveryId: DELIVERY_ID, goodsId: PADS_ID } };
    case 'deliver':
      return { eventName: 'deliver', payload: {} };
    case 'revoke':
      return { eventName: 'revoke', payload: {} };
  }
};
