/**
 * Bike shop — the recorded run behind the OttoChain film (docs/worksheets/2026-11-21-bike-shop-film.md).
 *
 * Wallets (`--wallets maya,agent,spoke,guild,rosa`):
 *   maya  = bike-shop owner       agent = her purchasing agent     spoke = supplier (Spoke & Sprocket)
 *   guild = template publisher    rosa  = the customer
 *
 * Acts (each `phase` banner is a film act):
 *   1 Registry   — guild publishes `purchasing-mandate.machine@1.0.0`; spoke names its order desk
 *                  `spoke-and-sprocket.machine`; maya creates a mandate PINNED to 1.0.0.
 *   2 Assets in  — $100 store credit minted to maya, FRACTIONALIZEd into five $20 pieces, two moved into
 *                  the mandate fiber's custody; brake pads minted into the supplier's order desk.
 *   3 Mandate    — the agent pays $20 for brake pads: the guard binds amount ↔ held credit piece and the
 *                  payee ↔ the transfer recipient ↔ the trigger target. One atomic cascade: credit moves to
 *                  the desk and the desk's `receive_order` runs.
 *   4 Poisoned   — a $500 invoice to an unapproved supplier (QuickParts) fails FOUR rules (per-order limit,
 *                  budget, listed supplier, credit match): refused, nothing moves, the desk never hears of it.
 *   5 Cross-fiber— the supplier dispatches (custody of the payment checked), brake pads go to maya's wallet,
 *                  and a delivery tracker is SPAWNED; on delivery it signals back to the mandate.
 *   6 ZK         — maya proves a credit score ≥ 700 without revealing it (real SP1 Groth16 bundle from
 *                  zk-eligibility, now also requiring maya's signature) → trade pricing approved.
 *   7 Types      — pads (T|C) + an expiring tune-up voucher (T|C|E) COMPOSE into a repair kit whose type is
 *                  the meet (T|C|E); the kit goes to rosa; rosa's soulbound warranty can't be resold (DL1).
 *   8 Registry   — guild publishes 1.1.0; maya's mandate stays on the version she signed up for.
 *   9 Revoke     — maya revokes; the agent's next request is refused.
 *
 * The combiner-level twin of this flow is BikeShopSuite (modules/shared-data).
 */
import {
  CREDIT_ID, CREDIT_SHARDS, KIT_ID, MANDATE_PKG, PADS_ID, QUICKPARTS_ID, SUPPLIER_ALIAS, VOUCHER_ID, WARRANTY_ID, DELIVERY_ID,
} from './ids.ts';

const phase = (label: string) => ({ action: 'phase', label });
const authorize = (requestNumber: number, amountCents: number, creditShard: number, payee: { payeeFiber?: string; payeeId?: string }, item = 'brake pads') => ({
  action: 'processEvent', fiber: 'mandate', event: 'authorize.ts', signers: ['agent'],
  eventData: { requestNumber, amountCents, creditId: CREDIT_SHARDS[creditShard], item, ...payee },
});

export default {
  name: 'Bike shop',
  description:
    'The OttoChain film run: registry-pinned purchasing mandate, typed store credit, an atomic pay→order cascade, a refused poisoned invoice, a spawned delivery tracker that signals back, a zero-knowledge credit check, a composed repair kit, a soulbound warranty and revocation.',
  type: 'state-machine',
  testFlows: [
    {
      name: 'bike shop: mandate → order → delivery → zk → kit → revoke',
      description: 'Nine acts; every on-screen beat of the film replays a step here.',
      steps: [
        phase('ACT 1  registry · a mandate template anyone can read'),
        { action: 'publishVersion', name: MANDATE_PKG, version: '1.0.0', definition: 'mandate.definition.json', schemaShape: 'mandate.schema.json', signers: ['guild'] },
        { action: 'createAssetPolicy', name: 'shop-credit.asset', policy: 'shop-credit.policy.json', signers: ['maya'] },
        { action: 'createAssetPolicy', name: 'warranty.asset', policy: 'warranty.policy.json', signers: ['maya'] },
        { action: 'createAssetPolicy', name: 'brake-pads.asset', policy: 'brake-pads.policy.json', signers: ['spoke'] },
        { action: 'createAssetPolicy', name: 'tune-up-voucher.asset', policy: 'tune-up.policy.json', signers: ['spoke'] },
        { action: 'create', as: 'desk', definition: 'order-desk.definition.json', initialData: 'initial.ts', eventData: { fiber: 'desk' }, signers: ['spoke'] },
        { action: 'registerAlias', name: SUPPLIER_ALIAS, fiber: 'desk', signers: ['spoke'] },
        { action: 'create', as: 'mandate', definition: 'mandate.definition.json', initialData: 'initial.ts', eventData: { fiber: 'mandate' }, schemaRef: { name: MANDATE_PKG, version: '1.0.0' }, signers: ['maya'] },
        { action: 'create', as: 'trade', definition: 'trade-account.definition.json', initialData: 'initial.ts', eventData: { fiber: 'trade' }, signers: ['spoke'] },

        phase('ACT 2  assets · typed store credit moves into the mandate'),
        { action: 'mintAsset', mint: 'mint.ts', eventData: { assetId: CREDIT_ID, policy: 'shop-credit.asset', holderWallet: 'maya', amount: 10000 }, signers: ['maya'] },
        { action: 'assertAsset', assetId: CREDIT_ID, label: 'credit', expectedHolder: { Wallet: 'maya' }, expectedAmount: 10000 },
        { action: 'applyMorphism', morphism: 'morphism.ts', eventData: { op: 'split-credit' }, signers: ['maya'] },
        { action: 'assertAsset', assetId: CREDIT_SHARDS[0], label: 'credit·1', expectedHolder: { Wallet: 'maya' }, expectedAmount: 2000 },
        { action: 'applyMorphism', morphism: 'morphism.ts', eventData: { op: 'fund-mandate', shard: 0 }, assetId: CREDIT_SHARDS[0], signers: ['maya'] },
        { action: 'applyMorphism', morphism: 'morphism.ts', eventData: { op: 'fund-mandate', shard: 1 }, assetId: CREDIT_SHARDS[1], signers: ['maya'] },
        { action: 'assertAsset', assetId: CREDIT_SHARDS[1], label: 'credit·2', expectedHolder: { Fiber: 'mandate' }, expectedAmount: 2000 },
        { action: 'mintAsset', mint: 'mint.ts', eventData: { assetId: PADS_ID, policy: 'brake-pads.asset', holderFiber: 'desk', amount: 1 }, signers: ['spoke'] },
        { action: 'assertAsset', assetId: PADS_ID, label: 'pads', expectedHolder: { Fiber: 'desk' }, expectedAmount: 1 },

        phase('ACT 3  mandate · the agent pays $20 for brake pads'),
        { ...authorize(1, 2000, 0, { payeeFiber: 'desk' }), expectedState: 'active' },
        { action: 'assertStateProof', fiber: 'mandate', field: 'remainingCents', expectedFieldValue: 8000, signers: ['agent'] },
        { action: 'assertAsset', assetId: CREDIT_SHARDS[0], label: 'paid', expectedHolder: { Fiber: 'desk' }, expectedAmount: 2000 },
        { action: 'assertStateProof', fiber: 'desk', field: 'ordersReceived', expectedFieldValue: 1, signers: ['spoke'] },

        phase('ACT 4  poisoned invoice · $500 to an unapproved supplier'),
        { ...authorize(2, 50000, 1, { payeeId: QUICKPARTS_ID }, 'URGENT parts'), expectRejected: 'eventReceipt' },
        { action: 'assertStateProof', fiber: 'mandate', field: 'remainingCents', expectedFieldValue: 8000, signers: ['agent'] },
        { action: 'assertAsset', assetId: CREDIT_SHARDS[1], label: 'untouched', expectedHolder: { Fiber: 'mandate' }, expectedAmount: 2000 },
        { action: 'assertStateProof', fiber: 'desk', field: 'ordersReceived', expectedFieldValue: 1, signers: ['spoke'] },

        phase('ACT 5  cross-fiber · dispatch spawns a delivery tracker that signals back'),
        { action: 'processEvent', fiber: 'desk', event: 'events.ts', eventData: { name: 'dispatch' }, signers: ['spoke'], expectedState: 'open' },
        { action: 'assertAsset', assetId: PADS_ID, label: 'pads', expectedHolder: { Wallet: 'maya' }, expectedAmount: 1 },
        { action: 'assertState', fiber: DELIVERY_ID, expectedState: 'in_transit' },
        { action: 'processEvent', fiber: DELIVERY_ID, event: 'events.ts', eventData: { name: 'deliver' }, signers: ['spoke'], expectedState: 'delivered' },
        { action: 'assertState', fiber: 'mandate', expectedState: 'active', minSequenceNumber: 2 },

        phase('ACT 6  zero knowledge · a credit check that reveals nothing'),
        { action: 'processEvent', fiber: 'trade', event: 'prove.ts', signers: ['maya'], expectedState: 'approved' },

        phase('ACT 7  typed assets · a repair kit inherits every restriction'),
        { action: 'mintAsset', mint: 'mint.ts', eventData: { assetId: VOUCHER_ID, policy: 'tune-up-voucher.asset', holderWallet: 'maya', amount: 1 }, signers: ['spoke'] },
        { action: 'assertAsset', assetId: VOUCHER_ID, label: 'voucher', expectedHolder: { Wallet: 'maya' }, expectedAmount: 1 },
        { action: 'applyMorphism', morphism: 'morphism.ts', eventData: { op: 'compose-kit' }, assetId: PADS_ID, signers: ['maya'] },
        { action: 'assertAsset', assetId: KIT_ID, label: 'kit', expectedHolder: { Wallet: 'maya' }, expectedAmount: 2 },
        { action: 'applyMorphism', morphism: 'morphism.ts', eventData: { op: 'gift-kit' }, assetId: KIT_ID, signers: ['maya'] },
        { action: 'assertAsset', assetId: KIT_ID, label: 'kit', expectedHolder: { Wallet: 'rosa' }, expectedAmount: 2 },
        { action: 'mintAsset', mint: 'mint.ts', eventData: { assetId: WARRANTY_ID, policy: 'warranty.asset', holderWallet: 'rosa', amount: 1 }, signers: ['maya'] },
        { action: 'assertAsset', assetId: WARRANTY_ID, label: 'warranty', expectedHolder: { Wallet: 'rosa' }, expectedAmount: 1 },
        { action: 'applyMorphism', morphism: 'morphism.ts', eventData: { op: 'resell-warranty' }, assetId: WARRANTY_ID, signers: ['rosa'], expectRejected: 'dl1' },
        { action: 'assertAsset', assetId: WARRANTY_ID, label: 'warranty', expectedHolder: { Wallet: 'rosa' }, expectedAmount: 1 },

        phase('ACT 8  registry · a new template version does not change Maya’s rules'),
        { action: 'publishVersion', name: MANDATE_PKG, version: '1.1.0', definition: 'mandate-v1.1.definition.json', schemaShape: 'mandate-v1.1.schema.json', signers: ['guild'] },

        phase('ACT 9  revoke · the owner pulls the plug'),
        { action: 'processEvent', fiber: 'mandate', event: 'events.ts', eventData: { name: 'revoke' }, signers: ['maya'], expectedState: 'revoked' },
        { ...authorize(3, 1000, 1, { payeeFiber: 'desk' }), expectRejected: 'eventReceipt' },
        { action: 'assertStateProof', fiber: 'mandate', field: 'remainingCents', expectedFieldValue: 8000, signers: ['agent'] },
      ],
    },
  ],
};
