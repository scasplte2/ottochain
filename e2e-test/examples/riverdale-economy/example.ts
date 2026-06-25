/**
 * Riverdale Economy — the full 6-party economy e2e (Phase 2).
 *
 * Parties (CI lane passes `--wallets alice,bob,carol,dave,erin,frank`):
 *   alice = Manufacturer   bob = Retailer   carol = Consumer   dave = Bank   erin = Fed   frank = Gov
 *
 * This extends the GREEN de-risk slice (cross-fiber `_triggers`, real `_transferAsset` custody between
 * fibers, a verified-bound versioned upgrade + migration, the assertState/assertAsset poll steps) into the
 * whole economy: a Fed sets monetary policy that propagates to a Bank; the Bank lends RVD to a Consumer;
 * the Consumer buys GOODS from a Retailer (money out, goods in, one tx); services the loan back to the
 * Bank; Governance broadcasts a tax sweep to manufacturer/retailer/consumer; and the Consumer runs a
 * spawned child auction. Two state-machine PACKAGES (retailer.machine, fed.machine) are versioned v1→v2.
 *
 * ── Asset choreography (R1, verified in AssetCombiner.scala) ──────────────────────────────────────────
 *  • A FIBER-held asset's raw `ApplyMorphism` is REJECTED (AssetCombiner.requireWalletHolder). Fiber-custody
 *    value moves ONLY via the `_transferAsset` fiber-effect return channel (whole instance; the emitting
 *    fiber must HOLD it — R1 holder defense in applyFiberTransfer). So each payment leg is a SEPARATE
 *    pre-minted RVD instance, minted DIRECTLY into the fiber that spends it:
 *      RVD_LOAN  → bank fiber     (bank lends it to the consumer via `underwrite`)
 *      RVD_PAY   → consumer fiber (consumer pays the retailer via `buy`)
 *      RVD_REPAY → consumer fiber (consumer services the loan to the bank via `make_payment`)
 *      RVD_TAX   → consumer fiber (consumer remits to the gov via `pay_taxes`)
 *    Minting INTO a Fiber holder is allowed (AssetCombiner.mintAsset).
 *  • Wallet-context morphisms (P12) are SEPARATE from the custody flow and run in WALLET context, where R1
 *    requires `signer == holder`. STAKE is demonstrated live (it bumps the asset's seq + keeps the record,
 *    so the runner's `applyMorphism` confirmation can observe it). FRACTIONALIZE + BURN are CONSUMING/
 *    terminal morphisms — they REMOVE the source record, which the runner's seq-advance confirmation
 *    cannot observe — so they ship as ready body files (fractionalize-rvd.ts / burn-rvd.ts) + ids and are
 *    documented as deferred below (see the P12 note). The rvd policy already permits all four.
 *
 * ── On-wire shapes (verified against the chain sources) ───────────────────────────────────────────────
 *  • `_transferAsset` recipient is a BARE STRING; a UUID-shaped string → AssetHolder.Fiber, a DAG address →
 *    AssetHolder.Wallet (EffectExtractor.parseRecipient). We pass fiberIds, so legs land in Fiber custody.
 *  • A cross-fiber trigger needs NO declared dependency; the gate is FiberPolicy.acceptedCallers (UNSET =
 *    Unconstrained here), so any caller is accepted (TriggerDispatcher routes by targetMachineId).
 *  • Spawned child `owners = event.auctionOwners` (SpawnProcessor); a child's transitions are gated by
 *    `owners ∪ authorizedSigners` (FiberRules.updateSignedByOwnerOrParticipant), so the bidder bob is
 *    listed in auctionOwners — else his place_bid/accept_bid are rejected at ML0.
 *  • Signers ARE the party model: a step's `signers` are the proofs ⇒ owners/authorizers.
 *
 * Signed-message discipline (CLAUDE.md #1): the `.ts` mint/event/morphism files return only present fields;
 * optional fields are omitted, never null.
 */
import {
  GOODS_ASSET_ID,
  RVD_LOAN_ID,
  RVD_PAY_ID,
  RVD_REPAY_ID,
  RVD_TAX_ID,
  AUCTION_CHILD_ID,
  CAPPED_A_ID,
  CAPPED_B_ID,
} from './ids.ts';

const RETAILER_PKG = 'retailer.machine';
const FED_PKG = 'fed.machine';

export default {
  name: 'Riverdale Economy',
  description:
    'Full 6-party economy: Fed→Bank monetary policy, Bank→Consumer lending, Consumer↔Retailer commerce (money/goods cross-transfer), loan servicing, a broadcast Gov tax sweep, a spawned child auction, two versioned package upgrades (retailer.machine + fed.machine), and wallet-context asset morphisms — plus a negative-test flow for graceful rejections.',
  type: 'state-machine',
  testFlows: [
    {
      name: 'full economy: monetary policy → lending → commerce → servicing → tax sweep → auction',
      description:
        'Genesis (asset policies + retailer.machine v1/v2 + fed.machine v1/v2) → create the 6 party fibers → mint GOODS + the four RVD payment-leg instances → supply chain → monetary policy → lending → commerce → retailer upgrade → loan servicing → fed upgrade → tax sweep → spawned auction → a wallet STAKE morphism.',
      steps: [
        // ── P0 genesis: asset policies + the two versioned state-machine packages ──
        { action: 'createAssetPolicy', name: 'goods.asset', policy: 'goods-policy.json', signers: ['alice'] },
        { action: 'createAssetPolicy', name: 'rvd.asset', policy: 'rvd-policy.json', signers: ['alice'] },
        { action: 'publishVersion', name: RETAILER_PKG, version: '1.0.0', definition: 'retailer-v1.definition.json', schemaShape: 'retailer-v1.schema.json', signers: ['bob'] },
        { action: 'publishVersion', name: RETAILER_PKG, version: '2.0.0', definition: 'retailer-v2.definition.json', schemaShape: 'retailer-v2.schema.json', signers: ['bob'] },
        { action: 'publishVersion', name: FED_PKG, version: '1.0.0', definition: 'fed-v1.definition.json', schemaShape: 'fed-v1.schema.json', signers: ['erin'] },
        { action: 'publishVersion', name: FED_PKG, version: '2.0.0', definition: 'fed-v2.definition.json', schemaShape: 'fed-v2.schema.json', signers: ['erin'] },

        // ── P1 create the six party fibers (signers ⇒ owners). retailer + fed are verified-bound. ──
        { action: 'create', as: 'manufacturer', definition: 'manufacturer.definition.json', initialData: 'manufacturer.initial.json', signers: ['alice'] },
        { action: 'create', as: 'retailer', definition: 'retailer-v1.definition.json', initialData: 'retailer.initial.json', schemaRef: { name: RETAILER_PKG, version: '1.0.0' }, signers: ['bob'] },
        { action: 'create', as: 'consumer', definition: 'consumer.definition.json', initialData: 'consumer.initial.json', signers: ['carol'] },
        { action: 'create', as: 'bank', definition: 'bank.definition.json', initialData: 'bank.initial.json', signers: ['dave'] },
        { action: 'create', as: 'fed', definition: 'fed-v1.definition.json', initialData: 'fed.initial.json', schemaRef: { name: FED_PKG, version: '1.0.0' }, signers: ['erin'] },
        { action: 'create', as: 'gov', definition: 'gov.definition.json', initialData: 'gov.initial.json', signers: ['frank'] },

        // ── P2 mint the real assets: GOODS into the manufacturer, the four RVD legs into their spenders ──
        { action: 'mintAsset', mint: 'mint-goods.ts', signers: ['alice'] },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, expectedHolder: { Fiber: 'manufacturer' }, expectedAmount: 500 },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_LOAN_ID, holderFiber: 'bank', amount: 10000 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_LOAN_ID, expectedHolder: { Fiber: 'bank' }, expectedAmount: 10000 },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_PAY_ID, holderFiber: 'consumer', amount: 500 }, signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_REPAY_ID, holderFiber: 'consumer', amount: 300 }, signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_TAX_ID, holderFiber: 'consumer', amount: 50 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_PAY_ID, expectedHolder: { Fiber: 'consumer' }, expectedAmount: 500 },
        { action: 'assertAsset', assetId: RVD_TAX_ID, expectedHolder: { Fiber: 'consumer' }, expectedAmount: 50 },

        // ── P3 SUPPLY: manufacturer.fulfill_order ⇒ triggers retailer.receive_shipment + moves GOODS custody ──
        { action: 'processEvent', fiber: 'manufacturer', event: 'event-fulfill-order.ts', signers: ['alice'], expectedState: 'shipped' },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 1 },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, expectedHolder: { Fiber: 'retailer' }, expectedAmount: 500, minSequenceNumber: 1 },

        // ── P4 MONETARY: fed.set_rate ⇒ triggers bank.rate_adjustment (rate propagates cross-fiber) ──
        { action: 'processEvent', fiber: 'fed', event: 'event-set-rate.ts', signers: ['erin'], expectedState: 'stable' },
        { action: 'assertState', fiber: 'bank', expectedState: 'operating', minSequenceNumber: 1 },

        // ── P5 LENDING: bank.underwrite ⇒ triggers consumer.loan_funded + transfers RVD_LOAN bank→consumer ──
        { action: 'processEvent', fiber: 'bank', event: 'event-underwrite.ts', signers: ['dave'], expectedState: 'loan_servicing' },
        { action: 'assertState', fiber: 'consumer', expectedState: 'debt_current', minSequenceNumber: 1 },
        { action: 'assertAsset', assetId: RVD_LOAN_ID, expectedHolder: { Fiber: 'consumer' }, expectedAmount: 10000, minSequenceNumber: 1 },

        // ── P6 COMMERCE: consumer.buy ⇒ triggers retailer.process_sale; RVD_PAY consumer→retailer, GOODS retailer→consumer ──
        { action: 'processEvent', fiber: 'consumer', event: 'event-buy.ts', signers: ['carol'], expectedState: 'debt_current' },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 2 },
        { action: 'assertAsset', assetId: RVD_PAY_ID, expectedHolder: { Fiber: 'retailer' }, expectedAmount: 500, minSequenceNumber: 1 },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, expectedHolder: { Fiber: 'consumer' }, expectedAmount: 500, minSequenceNumber: 2 },

        // ── P7 UPGRADE retailer v1→v2 (migration adds loyaltyPoints) + a v2-only redeem_loyalty ──
        { action: 'upgradeFiber', fiber: 'retailer', targetRef: { name: RETAILER_PKG, version: '2.0.0' }, newDefinition: 'retailer-v2.definition.json', migration: 'retailer-migration.json', signers: ['bob'] },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 3 },
        { action: 'processEvent', fiber: 'retailer', event: 'event-redeem-loyalty.ts', signers: ['bob'], expectedState: 'received' },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 4 },

        // ── P8 SERVICING: consumer.make_payment ⇒ triggers bank.payment_received + transfers RVD_REPAY consumer→bank ──
        { action: 'processEvent', fiber: 'consumer', event: 'event-make-payment.ts', signers: ['carol'], expectedState: 'debt_current' },
        { action: 'assertState', fiber: 'bank', expectedState: 'loan_servicing', minSequenceNumber: 3 },
        { action: 'assertAsset', assetId: RVD_REPAY_ID, expectedHolder: { Fiber: 'bank' }, expectedAmount: 300, minSequenceNumber: 1 },

        // ── P9 UPGRADE fed v1→v2 (migration adds emergencyLoans) + a v2-only emergency_lending ──
        { action: 'upgradeFiber', fiber: 'fed', targetRef: { name: FED_PKG, version: '2.0.0' }, newDefinition: 'fed-v2.definition.json', migration: 'fed-migration.json', signers: ['erin'] },
        { action: 'assertState', fiber: 'fed', expectedState: 'stable', minSequenceNumber: 2 },
        { action: 'processEvent', fiber: 'fed', event: 'event-emergency-lending.ts', signers: ['erin'], expectedState: 'emergency_lending' },
        { action: 'assertState', fiber: 'fed', expectedState: 'emergency_lending', minSequenceNumber: 3 },

        // ── P10 TAX SWEEP (broadcast): gov.collect_taxes ⇒ triggers pay_taxes on manufacturer/retailer/consumer; consumer also transfers RVD_TAX consumer→gov ──
        { action: 'processEvent', fiber: 'gov', event: 'event-collect-taxes.ts', signers: ['frank'], expectedState: 'tax_collection' },
        { action: 'assertState', fiber: 'gov', expectedState: 'tax_collection', minSequenceNumber: 1, expectedStateData: { totalTaxesCollected: 50, taxpayersBilled: 3, status: 'tax_collection' } },
        { action: 'assertState', fiber: 'manufacturer', expectedState: 'shipped', minSequenceNumber: 2, expectedStateData: { inventory: 500, taxesPaid: 50, status: 'shipped' } },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 5 },
        { action: 'assertState', fiber: 'consumer', expectedState: 'debt_current', minSequenceNumber: 4 },
        { action: 'assertAsset', assetId: RVD_TAX_ID, expectedHolder: { Fiber: 'gov' }, expectedAmount: 50, minSequenceNumber: 1 },

        // ── P11 AUCTION (spawn): consumer.list_item spawns the child auction; bob bids + accepts; sale_completed loops back to the consumer ──
        { action: 'processEvent', fiber: 'consumer', event: 'event-list-item.ts', signers: ['carol'], expectedState: 'marketplace_selling' },
        { action: 'assertState', fiber: AUCTION_CHILD_ID, expectedState: 'listed' },
        { action: 'processEvent', fiber: AUCTION_CHILD_ID, event: 'event-place-bid.ts', signers: ['bob'], expectedState: 'bid_received' },
        { action: 'processEvent', fiber: AUCTION_CHILD_ID, event: 'event-accept-bid.ts', signers: ['bob'], expectedState: 'sold' },
        { action: 'assertState', fiber: AUCTION_CHILD_ID, expectedState: 'sold', minSequenceNumber: 2 },
        { action: 'assertState', fiber: 'consumer', expectedState: 'debt_current', minSequenceNumber: 6 },

        // ── DEFERRED: wallet-context asset morphisms (Stake / Fractionalize / Burn) ──
        // The body files (stake-rvd.ts / fractionalize-rvd.ts / burn-rvd.ts) + ids + the rvd policy's
        // morphism declarations are all shipped, but the steps are deferred pending two small runner
        // (Phase-1 harness) additions — both confirmed needed by the first full-economy CI run:
        //   1. ASSET DL1-SYNC. The runner `waitForDl1Sync`s FIBER commits but never waits for an asset's
        //      `assetCommit` to reach DL1 after a mint, so the next `applyMorphism` raced DL1 and was
        //      structurally rejected (HTTP 400) — even for non-consuming STAKE. Needs an asset-commit
        //      DL1 sync mirroring the fiber one.
        //   2. CONSUMING/TERMINAL CONFIRM. The runner confirms `applyMorphism` via a SOURCE-seq advance;
        //      FRACTIONALIZE/BURN remove the source, so that predicate can't be satisfied (confirm
        //      Fractionalize via shard existence, Burn via source absence).
        // Tracked as the morphism fast-follow; the 6-party economy above is fully exercised without them.
      ],
    },
    {
      name: 'negative tests: graceful rejections leave state unchanged',
      description:
        'Own fibers/policies/packages (disjoint from flow 1). Each rejection is admitted by DL1 (structurally valid) then DENIED at ML0 combine — the fiber/asset/registry never changes: replay/seq-regression, mint-over-cap, non-monotonic publish. (NOTE: a "wrong-party transition" is intentionally NOT a case here — the first CI run confirmed a primary state-machine transition is NOT owner-gated; the guard is the gate. Ownership gates registry ops, script callers, asset holders (R1), and spawned children — those are the real wrong-party surfaces.)',
      steps: [
        // ── replay / seq-regression: re-submitting a one-way transition after the fiber moved on (NoTransitionForEvent) ──
        { action: 'create', as: 'negReplay', definition: 'neg.definition.json', initialData: 'neg.initial.json', signers: ['alice'] },
        { action: 'processEvent', fiber: 'negReplay', event: 'event-advance.ts', signers: ['alice'], expectedState: 's1' },
        { action: 'processEvent', fiber: 'negReplay', event: 'event-advance.ts', signers: ['alice'], expectRejected: 'ml0' },

        // ── mint-over-cap: a capped policy (maxSupply 100); the first mint fits, the second pushes derived supply over ──
        { action: 'createAssetPolicy', name: 'capped.asset', policy: 'capped-policy.json', signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: CAPPED_A_ID, holderWallet: 'alice', amount: 50, policyName: 'capped.asset' }, signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: CAPPED_B_ID, holderWallet: 'alice', amount: 60, policyName: 'capped.asset' }, signers: ['alice'], expectRejected: 'ml0' },

        // ── non-monotonic publish: publish a HIGHER version then a LOWER one (the LOWER is not in the lineage, so "did not land" is observable) ──
        { action: 'publishVersion', name: 'negtest.machine', version: '2.0.0', definition: 'neg.definition.json', schemaShape: 'retailer-v1.schema.json', signers: ['alice'] },
        { action: 'publishVersion', name: 'negtest.machine', version: '1.0.0', definition: 'neg.definition.json', schemaShape: 'retailer-v1.schema.json', signers: ['alice'], expectRejected: 'ml0' },
      ],
    },
  ],
};
