/**
 * Riverdale economy — a 3-fiber "supply-chain + upgrade" de-risk slice.
 *
 * Parties (CI lane passes `--wallets alice,bob,carol`):
 *   alice = Manufacturer   bob = Retailer   carol = Consumer
 *
 * Two REAL assets:
 *   GOODS.asset (behavior 20 = T|C: transferable + combinable inventory)
 *   RVD.asset   (behavior 28 = T|S|C = Fungible currency)
 * Fixed, deterministic asset ids live in ./ids.ts (NEVER crypto.randomUUID — the custody assertions must
 * observe the SAME instance across mint → transfer → assert).
 *
 * One worked flow proves, end-to-end:
 *   (a) cross-fiber `_triggers`        — manufacturer.fulfill_order fires retailer.receive_shipment.
 *   (b) real `_transferAsset` custody  — the GOODS instance moves manufacturer-fiber → retailer-fiber in
 *                                        the SAME transition, asserted via the new `assertAsset` step.
 *   (c) versioned upgrade + migration  — retailer is verified-bound to retailer.machine@1.0.0, upgraded
 *                                        to @2.0.0 with a migration that adds loyaltyPoints:0, then drives
 *                                        a v2-ONLY transition (redeem_loyalty).
 *   (d) the new poll-only assert steps  — assertState / assertAsset (no tx; observe indirect + custody state).
 *
 * ── On-wire shapes that decide this slice (verified against the chain sources) ──────────────────────────
 *  • `_transferAsset` recipient is a BARE STRING that the extractor disambiguates: a UUID-shaped string →
 *    AssetHolder.Fiber, a DAG address → AssetHolder.Wallet (EffectExtractor.parseRecipient). We pass the
 *    retailer's fiberId, so the GOODS lands in Fiber(retailer) custody — NOT an AssetHolder object.
 *  • A cross-fiber trigger needs NO declared dependency: TriggerDispatcher routes purely by the directive's
 *    `targetMachineId`. The only gate is FiberPolicy.acceptedCallers, which is UNSET here (Unconstrained),
 *    so the manufacturer (caller) is accepted. The retailer's receive_shipment guard is `{"==":[1,1]}`.
 *  • The manufacturer must HOLD the GOODS before fulfill_order — minted into Fiber(manufacturer) in P2 — to
 *    satisfy the R1 holder defense (AssetCombiner.applyFiberTransfer requires holder == Fiber(emitter)).
 *
 * Signers = the party model: a step's `signers` ARE the proofs ⇒ the owners/authorizers. alice owns the
 * manufacturer, bob owns + publishes the retailer package, carol owns the consumer.
 *
 * NOTE on the consumer fiber: the deliverable set has no dedicated consumer machine, so the consumer is
 * created from the manufacturer definition (a generic, valid state machine). It is a third PARTY proving the
 * multi-fiber/signers model; its internal transitions are intentionally not exercised by the flow.
 */
import { GOODS_ASSET_ID, RVD_ASSET_ID } from './ids.ts';

const RETAILER_PKG = 'retailer.machine';

export default {
  name: 'Riverdale Economy',
  description:
    'Supply-chain slice: cross-fiber _triggers, real _transferAsset custody between fibers, a verified-bound versioned upgrade + migration + v2-only transition, and the assertState/assertAsset poll steps.',
  type: 'state-machine',
  testFlows: [
    {
      name: 'manufacturer ships goods to retailer, then retailer upgrades to v2 loyalty',
      description:
        'Genesis (asset policies + retailer package v1/v2) → create the 3 party fibers → mint GOODS to the manufacturer + RVD to carol → fulfill_order (trigger + asset custody move) → upgrade the retailer across schema versions with a migration → drive a v2-only redeem_loyalty.',
      steps: [
        // ── P0 genesis: asset policies + the retailer state-machine package (two versions) ──
        { action: 'createAssetPolicy', name: 'goods.asset', policy: 'goods-policy.json', signers: ['alice'] },
        { action: 'createAssetPolicy', name: 'rvd.asset', policy: 'rvd-policy.json', signers: ['alice'] },
        {
          action: 'publishVersion',
          name: RETAILER_PKG,
          version: '1.0.0',
          definition: 'retailer-v1.definition.json',
          schemaShape: 'retailer-v1.schema.json',
          signers: ['bob'],
        },
        {
          action: 'publishVersion',
          name: RETAILER_PKG,
          version: '2.0.0',
          definition: 'retailer-v2.definition.json',
          schemaShape: 'retailer-v2.schema.json',
          signers: ['bob'],
        },

        // ── P1 create the three party fibers (signers ⇒ owners) ──
        {
          action: 'create',
          as: 'manufacturer',
          definition: 'manufacturer.definition.json',
          initialData: 'manufacturer.initial.json',
          signers: ['alice'],
        },
        {
          action: 'create',
          as: 'retailer',
          definition: 'retailer-v1.definition.json',
          initialData: 'retailer.initial.json',
          schemaRef: { name: RETAILER_PKG, version: '1.0.0' },
          signers: ['bob'],
        },
        {
          // The consumer (carol) reuses the manufacturer definition — see the header note. Third party only.
          action: 'create',
          as: 'consumer',
          definition: 'manufacturer.definition.json',
          initialData: 'manufacturer.initial.json',
          signers: ['carol'],
        },

        // ── P2 mint the two real assets ──
        { action: 'mintAsset', mint: 'mint-goods.ts', signers: ['alice'] },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, expectedHolder: { Fiber: 'manufacturer' }, expectedAmount: 500 },
        { action: 'mintAsset', mint: 'mint-rvd-carol.ts', signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_ASSET_ID, expectedHolder: { Wallet: 'carol' }, expectedAmount: 1000 },

        // ── P3 supply chain: fulfill_order triggers the retailer AND moves GOODS custody ──
        {
          action: 'processEvent',
          fiber: 'manufacturer',
          event: 'event-fulfill-order.ts',
          signers: ['alice'],
          expectedState: 'shipped',
        },
        // The retailer changed INDIRECTLY via the cross-fiber trigger (seq 0 → 1).
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 1 },
        // The GOODS instance is now in the retailer fiber's custody (asset seq advanced 0 → 1 on transfer).
        {
          action: 'assertAsset',
          assetId: GOODS_ASSET_ID,
          expectedHolder: { Fiber: 'retailer' },
          expectedAmount: 500,
          minSequenceNumber: 1,
        },

        // ── P4 versioned upgrade across schema versions + migration ──
        {
          action: 'upgradeFiber',
          fiber: 'retailer',
          targetRef: { name: RETAILER_PKG, version: '2.0.0' },
          newDefinition: 'retailer-v2.definition.json',
          migration: 'retailer-migration.json',
          signers: ['bob'],
        },
        // Migrated: loyaltyPoints:0 added; the v1 fields (preserved by the merge) intact; currentState kept.
        {
          action: 'assertState',
          fiber: 'retailer',
          minSequenceNumber: 2,
          expectedStateData: {
            shipmentsReceived: 1,
            status: 'received',
            receivedQuantity: 500,
            loyaltyPoints: 0,
          },
        },

        // ── P5 v2-only transition ──
        {
          action: 'processEvent',
          fiber: 'retailer',
          event: 'event-redeem-loyalty.ts',
          signers: ['bob'],
          expectedState: 'received',
        },
        {
          action: 'assertState',
          fiber: 'retailer',
          minSequenceNumber: 3,
          expectedState: 'received',
          expectedStateData: {
            shipmentsReceived: 1,
            receivedQuantity: 500,
            loyaltyPoints: 50,
            status: 'loyalty_redeemed',
          },
        },
      ],
    },
  ],
};
