# Riverdale Economy — Phase 2 full-economy contract (authoring spec)

This is the SHARED CONTRACT for the full-economy e2e. The de-risk slice (flow #1) is GREEN on CI and
PROVES the mechanisms (named multi-fiber, per-party signers, cross-fiber `_triggers`, real
`_transferAsset`, `assertState`/`assertAsset`, `createAssetPolicy`/`mintAsset`, verified-bound
upgrade+migration). Phase 2 EXTENDS this same `riverdale-economy` example into the full 6-party economy.

Everything here is the FIXED interface — author against it exactly so the independently-authored pieces
compose without coordination. Adapt the detailed state machines from
`modules/shared-data/src/test/scala/.../examples/RiverdaleEconomyStateMachineSuite.scala`
(manufacturer/retailer/bank/consumer/federalReserve/governance JSON builders), TRIMMED to the
transitions listed here, and copy the proven e2e patterns from the existing slice files in this dir
(definition shape, `_triggers`/`_transferAsset` effect form, event `.ts` reading `context.session.fibers`).

## Wallets / parties (CI lane will pass `--wallets alice,bob,carol,dave,erin,frank`)
alice=Manufacturer · bob=Retailer · carol=Consumer · dave=Bank · erin=Fed · frank=Governance.
A step's `signers:[...]` ARE the owners/authorizers. Each fiber is created + transitioned by its party.

## Fibers (aliases — used as `as:`/`fiber:` and as `_triggers` targets via event payload)
- `manufacturer` (alice) — EXISTS (slice). Produces, ships GOODS.
- `retailer` (bob) — EXISTS (slice; versioned `retailer.machine` v1→v2). Receives GOODS, sells.
- `consumer` (carol) — NEW. States: ACTIVE → debt_current (loan) → debt_current; + marketplace_selling (auction).
- `bank` (dave) — NEW. States: operating → loan_servicing. Holds RVD, lends it.
- `fed` (erin) — NEW; versioned `fed.machine` v1→v2 (v2 adds `emergency_lending`). States: stable → (v2) emergency_lending.
- `gov` (frank) — NEW. States: monitoring → tax_collection → monitoring.

## Assets (GOODS + RVD policies + ids already exist in ids.ts / goods-policy.json / rvd-policy.json)
Reuse `GOODS_ASSET_ID` / `RVD_ASSET_ID` from `ids.ts`. ADD new fixed UUIDs to `ids.ts` for the new
instances (NEVER random): `RVD_BANK_LOAN_ID` (minted to bank, lent to consumer), and shard ids
`RVD_SHARD_PAY_ID` / `RVD_SHARD_REPAY_ID` / `RVD_SHARD_TAX_ID` (Fractionalize outputs).
- RVD policy MUST allow Fractionalize + Burn: `rvd-policy.json` `behavior` must include S (8) and the
  `morphisms` map must declare `"FRACTIONALIZE": {"visibility":"PUBLIC"}` and `"BURN": {"visibility":"PUBLIC"}`
  (and `"TRANSFER"`). GOODS keeps `behavior 20` (T|C). Confirm morphism-spec + behavior bit requirements in
  `modules/.../schema/asset/{MorphismKind,MorphismSpec,TokenBehavior}.scala` + `AssetCombiner.scala`.

## Cross-fiber edges + asset flows (the heart — each is ONE submitted tx mutating ≥2 fibers / moving assets)
1. SUPPLY (exists): `manufacturer.fulfill_order` → `_triggers retailer.receive_shipment` + `_transferAsset GOODS manufacturer→retailer`.
2. MONETARY: `fed.set_rate` → `_triggers bank.rate_adjustment` (payload newBaseRate). Broadcast-capable (loop over bank ids; one bank here).
3. LENDING: `bank.underwrite` → `_triggers consumer.loan_funded` (payload amount) + `_transferAsset RVD_BANK_LOAN_ID bank→consumer`. Consumer ACTIVE→debt_current.
4. COMMERCE: `consumer.buy` → `_triggers retailer.process_sale` (payload qty) + `_transferAsset RVD_SHARD_PAY_ID consumer→retailer` + `_transferAsset GOODS retailer→consumer`.
5. SERVICING: `consumer.make_payment` → `_triggers bank.payment_received` + `_transferAsset RVD_SHARD_REPAY_ID consumer→bank`.
6. TAX SWEEP (broadcast): `gov.collect_taxes` → `_triggers` pay_taxes on manufacturer + retailer + consumer (3 trigger directives in one effect). consumer.pay_taxes ALSO `_transferAsset RVD_SHARD_TAX_ID consumer→gov`. manufacturer/retailer pay_taxes update `taxesPaid` stateData only.
7. AUCTION (spawn): `consumer.list_item` → `_spawn` a child auction fiber with a DETERMINISTIC childId (pass it on the event payload, exported from ids.ts as `AUCTION_CHILD_ID`). A 2nd party bids (`auction.place_bid` signed by bob), then `auction.accept_bid` → `_triggers consumer.sale_completed`. Confirm `_spawn` directive shape + child addressing in `EffectExtractor.scala` (extractSpawnDirectivesFromExpression) + `SpawnMachinesSuite.scala`.

Trigger discipline (proven): a cross-fiber trigger needs NO declared dependency — only `FiberPolicy.acceptedCallers`
(leave UNSET = Unconstrained = any caller accepted). `_transferAsset` recipient is a BARE UUID STRING
(→ AssetHolder.Fiber). The emitting fiber must HOLD the asset (R1: holder == Fiber(emitter)).

## Master flow (replace the slice's single flow with TWO flows in example.ts)
FLOW 1 "full economy" (concurrency 1, ~one causal narrative). Order:
P0 genesis: createAssetPolicy goods + rvd (rvd now with FRACTIONALIZE/BURN morphisms); publishVersion retailer.machine 1.0.0 + 2.0.0; publishVersion fed.machine 1.0.0 + 2.0.0.
P1 create: manufacturer(alice), retailer(bob, schemaRef retailer.machine@1.0.0), consumer(carol), bank(dave), fed(erin, schemaRef fed.machine@1.0.0), gov(frank).
P2 mint: GOODS→manufacturer (amount 500); RVD_BANK_LOAN_ID→bank fiber (amount 10000). asserts.
P3 supply: manufacturer.fulfill_order → assert retailer received + GOODS@retailer.
P4 monetary: fed.set_rate → assert bank baseRate updated.
P5 lending: bank.underwrite → assert consumer debt_current + RVD_BANK_LOAN_ID@consumer fiber.
P6 make-change: applyMorphism FRACTIONALIZE RVD_BANK_LOAN_ID into [RVD_SHARD_PAY_ID, RVD_SHARD_REPAY_ID, RVD_SHARD_TAX_ID, remainder] held by consumer fiber (signer carol — note: fiber-held asset morphisms: check AssetCombiner whether ApplyMorphism on a fiber-held asset is allowed, OR if Fractionalize must be driven via a fiber effect. If raw ApplyMorphism on fiber-held assets is rejected (R1), instead Fractionalize BEFORE the loan transfer while bank holds it, or mint the shards directly. Document the choice.). assert shards exist.
P7 commerce: consumer.buy → assert retailer revenue + RVD_SHARD_PAY_ID@retailer + GOODS@consumer.
P8 upgrade retailer: upgradeFiber retailer v1→v2 (migration loyaltyPoints) → assert; retailer.redeem_loyalty (v2-only) → assert.
P9 servicing: consumer.make_payment → assert bank payment_received + RVD_SHARD_REPAY_ID@bank.
P10 fed upgrade: upgradeFiber fed v1→v2 (migration) → assert; fed.emergency_lending (v2-only) → assert.
P11 tax sweep: gov.collect_taxes → assert manufacturer/retailer/consumer taxesPaid + RVD_SHARD_TAX_ID@gov; applyMorphism BURN RVD_SHARD_TAX_ID by gov → assert asset gone / amount 0.
P12 auction: consumer.list_item (spawn AUCTION_CHILD_ID) → assert child auction state listed; auction.place_bid (signers:[bob]) → auction.accept_bid → assert child sold + consumer sale_completed.

FLOW 2 "negative tests" (own fibers/assets, graceful rejections — all `expectRejected:"ml0"` unless noted):
- wrong-party: create a fiber as alice, then processEvent with signers:[bob] → reject.
- replay/seq-regression: processEvent a valid transition, then re-submit the SAME (stale targetSequenceNumber) → reject.
- mint-over-cap: a capped RVD-like policy (maxSupply small), mint beyond it → reject.
- non-monotonic publishVersion: publish v1.0.0 then v1.0.0 again (or a lower version) → reject (expectRejected may be ml0).

## Wall-clock / CI
Flow 1 is ~40 mutating steps; keep guards generous + initialData large so nothing stalls. I (the integrator)
will bump the `riverdale-economy` lane `timeout` to ~55 and keep `concurrency: 1`, `wallets: alice,bob,carol,dave,erin,frank`.

## Deliverables split
- DEFINITIONS subagent: consumer.definition.json (+ auction child def, inline in the `_spawn` or as a referenced fragment), bank.definition.json, fed-v1.definition.json, fed-v2.definition.json, fed-migration.json, + *.schema.json + *.initial.json for each. Trim from the unit test; satisfy the edges above.
- ORCHESTRATION subagent: all new event `.ts` files (event-set-rate, event-underwrite, event-buy, event-make-payment, event-collect-taxes, event-list-item, event-place-bid, event-accept-bid, event-emergency-lending, plus pay_taxes/loan_funded/receive-shipment payloads as needed), the morphism body files (fractionalize-rvd.json, burn-tax.json), the new mint body (mint-rvd-bank.ts), the additions to ids.ts, and the TWO testFlows in example.ts (replacing the single slice flow — but KEEP all slice steps as the P0-P8 supply+upgrade backbone). Negative-test flow files.
- INTEGRATOR (me): reconcile, tune ids.ts, the CI lane, run/iterate on CI.

Constraints: CLAUDE.md signing rule (omit optional fields, never null). Deterministic ids only. Read AssetCombiner/EffectExtractor/FiberPolicy for exact shapes and QUOTE the lines for any non-obvious decision (esp. fiber-held-asset Fractionalize, `_spawn` child addressing, Burn semantics).
