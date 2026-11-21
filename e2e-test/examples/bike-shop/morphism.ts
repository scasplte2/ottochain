// ApplyMorphism bodies for the wallet-side asset beats (the runner fills targetSequenceNumber).
import { CREDIT_ID, CREDIT_SHARDS, KIT_ID, PADS_ID, VOUCHER_ID, WARRANTY_ID } from './ids.ts';

export default ({ wallets, session, eventData }: {
  wallets: Record<string, { address: string }>;
  session: { fibers: Record<string, string> };
  eventData: { op: 'split-credit' | 'fund-mandate' | 'compose-kit' | 'gift-kit' | 'resell-warranty'; shard?: number };
}) => {
  switch (eventData.op) {
    case 'split-credit': // $100 of store credit → five $20 pieces (Fractionalize: even split, C := 0)
      return { assetId: CREDIT_ID, kind: 'FRACTIONALIZE', shardIds: CREDIT_SHARDS };
    case 'fund-mandate': // a $20 piece moves from Maya's wallet into the mandate fiber's custody
      return { assetId: CREDIT_SHARDS[eventData.shard ?? 0], kind: 'TRANSFER', recipient: { Fiber: { fiberId: session.fibers.mandate } } };
    case 'compose-kit': // brake pads (T|C) + tune-up voucher (T|C|E) → repair kit = meet = T|C|E
      return { assetId: PADS_ID, kind: 'COMPOSE', otherAssetIds: [VOUCHER_ID], compositeId: KIT_ID };
    case 'gift-kit':
      return { assetId: KIT_ID, kind: 'TRANSFER', recipient: { Wallet: { address: wallets.rosa.address } } };
    case 'resell-warranty': // soulbound (T=0) — refused at the network's front door
      return { assetId: WARRANTY_ID, kind: 'TRANSFER', recipient: { Wallet: { address: wallets.guild.address } } };
  }
};
