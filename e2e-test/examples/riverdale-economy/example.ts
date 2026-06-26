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
 *    requires `signer == holder`. All three wallet-context morphisms run LIVE: STAKE (non-consuming —
 *    bumps the seq, the record survives), FRACTIONALIZE (consuming — removes the source + writes shards;
 *    the runner confirms it via the first shard's EXISTENCE), and BURN (terminal — removes the record;
 *    the runner confirms it via the source's ABSENCE). The rvd policy permits all four morphisms.
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
  RVD_STAKE_ID,
  RVD_FRAC_ID,
  RVD_FRAC_A_ID,
  RVD_FRAC_B_ID,
  RVD_FRAC_C_ID,
  RVD_BURN_ID,
  AUCTION_CHILD_ID,
  CAPPED_A_ID,
  CAPPED_B_ID,
} from './ids.ts';

const RETAILER_PKG = 'retailer.machine';
const FED_PKG = 'fed.machine';

// ── Observability scaffolding (poll-only `phase` banners + `economy` snapshot tables) ──────────────
// These steps send NO transactions: `phase` prints a section banner; `economy` reads the ML0
// checkpoint ONCE and prints a compact per-party table (state + a few stateData fields + held assets),
// rendering deltas against the previous `economy` step. They make the economy VISIBLE as it moves,
// without changing any functional/assert step. (The runner handles both as early-continue no-ops.)

type EconParty = { fiber?: string; wallet?: string; label: string; show: string[] };

/** asset-id → friendly label for the economy table's `×amount` cells. */
const ECON_ASSETS = [
  { id: GOODS_ASSET_ID, label: 'GOODS' },
  { id: RVD_LOAN_ID, label: 'RVD-loan' },
  { id: RVD_PAY_ID, label: 'RVD-pay' },
  { id: RVD_REPAY_ID, label: 'RVD-repay' },
  { id: RVD_TAX_ID, label: 'RVD-tax' },
  { id: RVD_STAKE_ID, label: 'RVD-stake' },
  { id: RVD_FRAC_ID, label: 'RVD-frac' },
  { id: RVD_FRAC_A_ID, label: 'RVD-fracA' },
  { id: RVD_FRAC_B_ID, label: 'RVD-fracB' },
  { id: RVD_FRAC_C_ID, label: 'RVD-fracC' },
  { id: RVD_BURN_ID, label: 'RVD-burn' },
];

/** The six party fibers + the two demo wallets, each with the few stateData fields worth watching. */
const ECON_PARTIES: EconParty[] = [
  { fiber: 'manufacturer', label: 'manufacturer', show: ['inventory', 'taxesPaid'] },
  { fiber: 'retailer', label: 'retailer', show: ['revenue', 'loyaltyPoints', 'taxesPaid'] },
  { fiber: 'consumer', label: 'consumer', show: ['balance', 'purchaseCount'] },
  { fiber: 'bank', label: 'bank', show: ['loanPortfolio'] },
  { fiber: 'fed', label: 'fed', show: ['baseRate'] },
  { fiber: 'gov', label: 'gov', show: ['totalTaxesCollected'] },
  // Wallet parties only render once they actually hold an instance (the table skips empty rows).
  { wallet: 'carol', label: 'carol·wallet', show: [] },
  { wallet: 'dave', label: 'dave·wallet', show: [] },
];

/** A `phase` banner step. */
const phase = (label: string) => ({ action: 'phase', label });
/** An `economy` snapshot step (all parties + assets; an optional sub-label tags the moment). */
const economy = (label?: string) => ({ action: 'economy', label, parties: ECON_PARTIES, assets: ECON_ASSETS });

export default {
  name: 'Riverdale Economy',
  description:
    'Full 6-party economy: Fed→Bank monetary policy, Bank→Consumer lending, Consumer↔Retailer commerce (money/goods cross-transfer), loan servicing, a broadcast Gov tax sweep, a spawned child auction, two versioned package upgrades (retailer.machine + fed.machine), and wallet-context asset morphisms — plus a negative-test flow for graceful rejections.',
  type: 'state-machine',
  testFlows: [
    {
      name: 'full economy: monetary policy → lending → commerce → servicing → tax sweep → auction',
      description:
        'Genesis (asset policies + retailer.machine v1/v2 + fed.machine v1/v2) → create the 6 party fibers → mint GOODS + the four RVD payment-leg instances → supply chain → monetary policy → lending → commerce → retailer upgrade → loan servicing → fed upgrade → tax sweep → spawned auction → wallet STAKE / FRACTIONALIZE / BURN morphisms.',
      steps: [
        // ── P0 genesis: asset policies + the two versioned state-machine packages ──
        phase('P0  genesis · policies + versioned packages'),
        { action: 'createAssetPolicy', name: 'goods.asset', policy: 'goods-policy.json', signers: ['alice'] },
        { action: 'createAssetPolicy', name: 'rvd.asset', policy: 'rvd-policy.json', signers: ['alice'] },
        { action: 'publishVersion', name: RETAILER_PKG, version: '1.0.0', definition: 'retailer-v1.definition.json', schemaShape: 'retailer-v1.schema.json', signers: ['bob'] },
        { action: 'publishVersion', name: RETAILER_PKG, version: '2.0.0', definition: 'retailer-v2.definition.json', schemaShape: 'retailer-v2.schema.json', signers: ['bob'] },
        { action: 'publishVersion', name: FED_PKG, version: '1.0.0', definition: 'fed-v1.definition.json', schemaShape: 'fed-v1.schema.json', signers: ['erin'] },
        { action: 'publishVersion', name: FED_PKG, version: '2.0.0', definition: 'fed-v2.definition.json', schemaShape: 'fed-v2.schema.json', signers: ['erin'] },

        // ── P1 create the six party fibers (signers ⇒ owners). retailer + fed are verified-bound. ──
        phase('P1  create the six party fibers'),
        { action: 'create', as: 'manufacturer', definition: 'manufacturer.definition.json', initialData: 'manufacturer.initial.json', signers: ['alice'] },
        { action: 'create', as: 'retailer', definition: 'retailer-v1.definition.json', initialData: 'retailer.initial.json', schemaRef: { name: RETAILER_PKG, version: '1.0.0' }, signers: ['bob'] },
        { action: 'create', as: 'consumer', definition: 'consumer.definition.json', initialData: 'consumer.initial.json', signers: ['carol'] },
        { action: 'create', as: 'bank', definition: 'bank.definition.json', initialData: 'bank.initial.json', signers: ['dave'] },
        { action: 'create', as: 'fed', definition: 'fed-v1.definition.json', initialData: 'fed.initial.json', schemaRef: { name: FED_PKG, version: '1.0.0' }, signers: ['erin'] },
        { action: 'create', as: 'gov', definition: 'gov.definition.json', initialData: 'gov.initial.json', signers: ['frank'] },
        economy('after genesis'),

        // ── P2 mint the real assets: GOODS into the manufacturer, the four RVD legs into their spenders ──
        phase('P2  mint GOODS + the four RVD payment legs'),
        { action: 'mintAsset', mint: 'mint-goods.ts', signers: ['alice'] },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, label: 'GOODS', expectedHolder: { Fiber: 'manufacturer' }, expectedAmount: 500 },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_LOAN_ID, holderFiber: 'bank', amount: 10000 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_LOAN_ID, label: 'RVD-loan', expectedHolder: { Fiber: 'bank' }, expectedAmount: 10000 },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_PAY_ID, holderFiber: 'consumer', amount: 500 }, signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_REPAY_ID, holderFiber: 'consumer', amount: 300 }, signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_TAX_ID, holderFiber: 'consumer', amount: 50 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_PAY_ID, label: 'RVD-pay', expectedHolder: { Fiber: 'consumer' }, expectedAmount: 500 },
        { action: 'assertAsset', assetId: RVD_TAX_ID, label: 'RVD-tax', expectedHolder: { Fiber: 'consumer' }, expectedAmount: 50 },
        economy('after mint'),

        // ── P3 SUPPLY: manufacturer.fulfill_order ⇒ triggers retailer.receive_shipment + moves GOODS custody ──
        phase('P3  supply chain'),
        { action: 'processEvent', fiber: 'manufacturer', event: 'event-fulfill-order.ts', signers: ['alice'], expectedState: 'shipped' },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 1 },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, label: 'GOODS', expectedHolder: { Fiber: 'retailer' }, expectedAmount: 500, minSequenceNumber: 1 },
        economy(),

        // ── P4 MONETARY: fed.set_rate ⇒ triggers bank.rate_adjustment (rate propagates cross-fiber) ──
        phase('P4  monetary policy'),
        { action: 'processEvent', fiber: 'fed', event: 'event-set-rate.ts', signers: ['erin'], expectedState: 'stable' },
        { action: 'assertState', fiber: 'bank', expectedState: 'operating', minSequenceNumber: 1 },
        economy(),

        // ── P5 LENDING: bank.underwrite ⇒ triggers consumer.loan_funded + transfers RVD_LOAN bank→consumer ──
        phase('P5  lending'),
        { action: 'processEvent', fiber: 'bank', event: 'event-underwrite.ts', signers: ['dave'], expectedState: 'loan_servicing' },
        { action: 'assertState', fiber: 'consumer', expectedState: 'debt_current', minSequenceNumber: 1 },
        { action: 'assertAsset', assetId: RVD_LOAN_ID, label: 'RVD-loan', expectedHolder: { Fiber: 'consumer' }, expectedAmount: 10000, minSequenceNumber: 1 },
        economy(),

        // ── P6 COMMERCE: consumer.buy ⇒ triggers retailer.process_sale; RVD_PAY consumer→retailer, GOODS retailer→consumer ──
        phase('P6  commerce'),
        { action: 'processEvent', fiber: 'consumer', event: 'event-buy.ts', signers: ['carol'], expectedState: 'debt_current' },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 2 },
        { action: 'assertAsset', assetId: RVD_PAY_ID, label: 'RVD-pay', expectedHolder: { Fiber: 'retailer' }, expectedAmount: 500, minSequenceNumber: 1 },
        { action: 'assertAsset', assetId: GOODS_ASSET_ID, label: 'GOODS', expectedHolder: { Fiber: 'consumer' }, expectedAmount: 500, minSequenceNumber: 2 },
        economy(),

        // ── P7 UPGRADE retailer v1→v2 (migration adds loyaltyPoints) + a v2-only redeem_loyalty ──
        phase('P7  retailer upgrade v1→v2'),
        { action: 'upgradeFiber', fiber: 'retailer', targetRef: { name: RETAILER_PKG, version: '2.0.0' }, newDefinition: 'retailer-v2.definition.json', migration: 'retailer-migration.json', signers: ['bob'] },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 3 },
        { action: 'processEvent', fiber: 'retailer', event: 'event-redeem-loyalty.ts', signers: ['bob'], expectedState: 'received' },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 4 },
        economy(),

        // ── P8 SERVICING: consumer.make_payment ⇒ triggers bank.payment_received + transfers RVD_REPAY consumer→bank ──
        phase('P8  loan servicing'),
        { action: 'processEvent', fiber: 'consumer', event: 'event-make-payment.ts', signers: ['carol'], expectedState: 'debt_current' },
        { action: 'assertState', fiber: 'bank', expectedState: 'loan_servicing', minSequenceNumber: 3 },
        { action: 'assertAsset', assetId: RVD_REPAY_ID, label: 'RVD-repay', expectedHolder: { Fiber: 'bank' }, expectedAmount: 300, minSequenceNumber: 1 },
        economy(),

        // ── P9 UPGRADE fed v1→v2 (migration adds emergencyLoans) + a v2-only emergency_lending ──
        phase('P9  fed upgrade v1→v2'),
        { action: 'upgradeFiber', fiber: 'fed', targetRef: { name: FED_PKG, version: '2.0.0' }, newDefinition: 'fed-v2.definition.json', migration: 'fed-migration.json', signers: ['erin'] },
        { action: 'assertState', fiber: 'fed', expectedState: 'stable', minSequenceNumber: 2 },
        { action: 'processEvent', fiber: 'fed', event: 'event-emergency-lending.ts', signers: ['erin'], expectedState: 'emergency_lending' },
        { action: 'assertState', fiber: 'fed', expectedState: 'emergency_lending', minSequenceNumber: 3 },
        economy(),

        // ── P10 TAX SWEEP (broadcast): gov.collect_taxes ⇒ triggers pay_taxes on manufacturer/retailer/consumer; consumer also transfers RVD_TAX consumer→gov ──
        phase('P10  tax sweep'),
        { action: 'processEvent', fiber: 'gov', event: 'event-collect-taxes.ts', signers: ['frank'], expectedState: 'tax_collection' },
        { action: 'assertState', fiber: 'gov', expectedState: 'tax_collection', minSequenceNumber: 1, expectedStateData: { totalTaxesCollected: 50, taxpayersBilled: 3, status: 'tax_collection' } },
        { action: 'assertState', fiber: 'manufacturer', expectedState: 'shipped', minSequenceNumber: 2, expectedStateData: { inventory: 500, taxesPaid: 50, status: 'shipped' } },
        { action: 'assertState', fiber: 'retailer', expectedState: 'received', minSequenceNumber: 5 },
        { action: 'assertState', fiber: 'consumer', expectedState: 'debt_current', minSequenceNumber: 4 },
        { action: 'assertAsset', assetId: RVD_TAX_ID, label: 'RVD-tax', expectedHolder: { Fiber: 'gov' }, expectedAmount: 50, minSequenceNumber: 1 },
        economy(),

        // ── P11 AUCTION (spawn): consumer.list_item spawns the child auction; bob bids + accepts; sale_completed loops back to the consumer ──
        phase('P11  spawned auction'),
        { action: 'processEvent', fiber: 'consumer', event: 'event-list-item.ts', signers: ['carol'], expectedState: 'marketplace_selling' },
        { action: 'assertState', fiber: AUCTION_CHILD_ID, expectedState: 'listed' },
        { action: 'processEvent', fiber: AUCTION_CHILD_ID, event: 'event-place-bid.ts', signers: ['bob'], expectedState: 'bid_received' },
        { action: 'processEvent', fiber: AUCTION_CHILD_ID, event: 'event-accept-bid.ts', signers: ['bob'], expectedState: 'sold' },
        { action: 'assertState', fiber: AUCTION_CHILD_ID, expectedState: 'sold', minSequenceNumber: 2 },
        { action: 'assertState', fiber: 'consumer', expectedState: 'debt_current', minSequenceNumber: 6 },
        economy(),

        // ── P12 WALLET MORPHISM: mint RVD into dave's WALLET, then STAKE it (R1: signer == holder) ──
        phase('P12  wallet stake'),
        // STAKE is non-consuming (codomain E:=1, bumps the seq, the record + holder + amount survive), so
        // the runner's `applyMorphism` source-seq-advance confirm observes it. Re-enabled now that the runner
        // gates on the mint's `assetCommit` reaching every DL1 node before the morphism (waitForDl1AssetSync)
        // — without that the morphism raced DL1's OnChain.assetCommits and was structurally rejected (400).
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_STAKE_ID, holderWallet: 'dave', amount: 200 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_STAKE_ID, label: 'RVD-stake', expectedHolder: { Wallet: 'dave' }, expectedAmount: 200 },
        { action: 'applyMorphism', morphism: 'stake-rvd.ts', signers: ['dave'] },
        { action: 'assertAsset', assetId: RVD_STAKE_ID, label: 'RVD-stake', expectedHolder: { Wallet: 'dave' }, expectedAmount: 200, minSequenceNumber: 1 },
        economy('final · staked'),

        // ── P12b FRACTIONALIZE: mint RVD into carol's WALLET, split it into 3 shards (CONSUMING) ──
        // Fractionalize REMOVES the source + writes one shard per shardId (combinable:=false, amount
        // partitioned 900→3×300). The runner confirms this consuming morphism via the first shard's existence.
        phase('P12b  wallet fractionalize'),
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_FRAC_ID, holderWallet: 'carol', amount: 900 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_FRAC_ID, label: 'RVD-frac', expectedHolder: { Wallet: 'carol' }, expectedAmount: 900 },
        { action: 'applyMorphism', morphism: 'fractionalize-rvd.ts', signers: ['carol'] },
        { action: 'assertAsset', assetId: RVD_FRAC_A_ID, label: 'RVD-fracA', expectedHolder: { Wallet: 'carol' }, expectedAmount: 300 },
        { action: 'assertAsset', assetId: RVD_FRAC_B_ID, label: 'RVD-fracB', expectedHolder: { Wallet: 'carol' }, expectedAmount: 300 },
        { action: 'assertAsset', assetId: RVD_FRAC_C_ID, label: 'RVD-fracC', expectedHolder: { Wallet: 'carol' }, expectedAmount: 300 },
        economy('final · fractionalized'),

        // ── P12c BURN: mint RVD into frank's WALLET, then burn it (CONSUMING / terminal) ──
        // Burn evaluates the policy burnPolicy then REMOVES the record. The runner confirms it via the
        // source's ABSENCE (exists→404). No post-assert — a burned record is gone (absence isn't assertable);
        // the economy snapshot shows frank's RVD-burn simply disappear.
        phase('P12c  wallet burn'),
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: RVD_BURN_ID, holderWallet: 'frank', amount: 200 }, signers: ['alice'] },
        { action: 'assertAsset', assetId: RVD_BURN_ID, label: 'RVD-burn', expectedHolder: { Wallet: 'frank' }, expectedAmount: 200 },
        { action: 'applyMorphism', morphism: 'burn-rvd.ts', signers: ['frank'] },
        economy('final · burned'),
      ],
    },
    {
      name: 'negative tests: graceful rejections leave state unchanged',
      description:
        'Own fibers/policies/packages (disjoint from flow 1). Each rejection is admitted by DL1 (structurally valid) then DENIED at ML0 combine — the fiber/asset/registry never changes: replay/seq-regression, mint-over-cap, non-monotonic publish. (NOTE: a "wrong-party transition" is intentionally NOT a case here — the first CI run confirmed a primary state-machine transition is NOT owner-gated; the guard is the gate. Ownership gates registry ops, script callers, asset holders (R1), and spawned children — those are the real wrong-party surfaces.)',
      steps: [
        // ── replay / seq-regression: re-submitting a one-way transition after the fiber moved on (NoTransitionForEvent) ──
        phase('N1  replay / seq-regression (ML0 reject)'),
        { action: 'create', as: 'negReplay', definition: 'neg.definition.json', initialData: 'neg.initial.json', signers: ['alice'] },
        { action: 'processEvent', fiber: 'negReplay', event: 'event-advance.ts', signers: ['alice'], expectedState: 's1' },
        { action: 'processEvent', fiber: 'negReplay', event: 'event-advance.ts', signers: ['alice'], expectRejected: 'ml0' },

        // ── mint-over-cap: a capped policy (maxSupply 100); the first mint fits, the second pushes derived supply over ──
        phase('N2  mint-over-cap (ML0 reject)'),
        { action: 'createAssetPolicy', name: 'capped.asset', policy: 'capped-policy.json', signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: CAPPED_A_ID, holderWallet: 'alice', amount: 50, policyName: 'capped.asset' }, signers: ['alice'] },
        { action: 'mintAsset', mint: 'mint-rvd.ts', eventData: { assetId: CAPPED_B_ID, holderWallet: 'alice', amount: 60, policyName: 'capped.asset' }, signers: ['alice'], expectRejected: 'ml0' },

        // ── non-monotonic publish: publish a HIGHER version then a LOWER one (the LOWER is not in the lineage, so "did not land" is observable) ──
        phase('N3  non-monotonic publish (ML0 reject)'),
        { action: 'publishVersion', name: 'negtest.machine', version: '2.0.0', definition: 'neg.definition.json', schemaShape: 'retailer-v1.schema.json', signers: ['alice'] },
        { action: 'publishVersion', name: 'negtest.machine', version: '1.0.0', definition: 'neg.definition.json', schemaShape: 'retailer-v1.schema.json', signers: ['alice'], expectRejected: 'ml0' },
      ],
    },
  ],
};
