# Riverdale Economy — supply-chain + upgrade de-risk slice

A thin, 3-fiber e2e that exercises the cross-fiber + asset-custody + versioned-upgrade surface in one
worked flow. It is the first example to use **real asset custody** (`createAssetPolicy` / `mintAsset` /
`_transferAsset`) together with **cross-fiber `_triggers`** and a **verified-bound versioned upgrade**.

## Parties & assets

CI lane: `--wallets alice,bob,carol`.

| Wallet | Role          | Owns / acts on            |
| ------ | ------------- | ------------------------- |
| alice  | Manufacturer  | `manufacturer` fiber, mints |
| bob    | Retailer      | `retailer.machine` package + `retailer` fiber |
| carol  | Consumer      | `consumer` fiber, holds RVD |

| Asset       | Behavior bits | Meaning                                              |
| ----------- | ------------- | --------------------------------------------------- |
| `goods.asset` | `20` = T\|C | transferable + **combinable** inventory             |
| `rvd.asset`   | `28` = T\|S\|C = Fungible | the fungible currency               |

Fixed, deterministic asset ids live in [`ids.ts`](./ids.ts) — **never** `crypto.randomUUID()`, because the
custody assertions must observe the SAME instance across mint → transfer → assert.

## The flow (single testFlow)

- **P0 genesis** — `createAssetPolicy goods.asset` + `rvd.asset` (alice); `publishVersion retailer.machine
  1.0.0` and `2.0.0` (bob).
- **P1 create** — `manufacturer` (alice), `retailer` verified-bound to `retailer.machine@1.0.0` (bob),
  `consumer` (carol).
- **P2 mint** — GOODS → `Fiber(manufacturer)` amount 500 (+ `assertAsset`); RVD → `Wallet(carol)` amount
  1000 (+ `assertAsset`).
- **P3 supply chain** — `manufacturer.fulfill_order` (alice). Its effect, in ONE transition:
  - `_triggers` the retailer's `receive_shipment` (carrying `quantity`), and
  - `_transferAsset` moves the GOODS instance into the retailer fiber's custody.
  - Asserted via `assertState fiber:retailer` (changed indirectly by the trigger) and `assertAsset`
    (custody now `Fiber(retailer)`).
- **P4 upgrade** — `upgradeFiber retailer → retailer.machine@2.0.0` with `retailer-migration.json`
  (adds `loyaltyPoints:0`); `assertState` checks the migrated + preserved fields.
- **P5 v2-only** — `redeem_loyalty` (a transition that exists ONLY in v2); `assertState` checks the result.

Retailer sequence numbers across the flow: create `0` → receive_shipment (triggered) `1` → upgrade `2` →
redeem_loyalty `3`.

## On-wire shapes (verified against the chain sources)

- **`_transferAsset` recipient is a BARE STRING**, not an `AssetHolder` object. The extractor
  disambiguates: a UUID-shaped string → `AssetHolder.Fiber`, a DAG address → `AssetHolder.Wallet`
  (`EffectExtractor.parseRecipient`). We pass the retailer's fiberId, so the GOODS lands in
  `Fiber(retailer)`. The directive is `{"_transferAsset":[{"assetId":<expr>,"recipient":<expr>}]}` with
  both sub-values resolved against the transition context.
- **Cross-fiber triggers need NO declared dependency.** `TriggerDispatcher` routes purely by the
  directive's `targetMachineId` (`{"_triggers":[{"targetMachineId":<expr>,"eventName":<str>,"payload":
  <expr>}]}`). The only gate is `FiberPolicy.acceptedCallers`, which is **unset** here (Unconstrained), so
  the manufacturer (the engine-stamped `$caller`) is accepted. The retailer's `receive_shipment` guard is
  `{"==":[1,1]}`.
- **The manufacturer must HOLD the GOODS before `fulfill_order`** (minted into `Fiber(manufacturer)`), to
  satisfy the R1 holder defense: `AssetCombiner.applyFiberTransfer` requires
  `source.holder == AssetHolder.Fiber(emittingFiberId)` and `behavior.transferable` and a live recipient
  fiber.
- **Holder wire forms**: `{"Fiber":{"fiberId":<uuid>}}` / `{"Wallet":{"address":<dag>}}`.
- **`MintAsset.policyRef`** is a `SchemaRef`: `{"name":"goods.asset","version":{"Exact":{"version":
  "1.0.0"}}}` (`VersionReq.Exact`; `{"Latest":{}}` also valid).
- **`CreateAssetPolicy`**: `{name (".asset" TLD, required), version, behavior (packed Int), supply,
  morphisms (required map, may be `{}`), stateShape, metadata?}`. `supply.mintPolicy` is a JSON-Logic
  predicate that must ALLOW the mint (`{"==":[1,1]}`); `maxSupply`/`burnPolicy`/`decimals` are omitted
  (Option ⇒ omit-safe). `morphisms` is left `{}` — the GOODS custody move uses the fiber `_transferAsset`
  return channel, not a wallet-signed `ApplyMorphism`, so no morphism spec is needed.
- **Verified binding**: the retailer is created with `schemaRef retailer.machine@1.0.0`, so its definition
  must hash-equal the published version's `logicHash`. We therefore use the **same** definition file for
  both `publishVersion` and `create` (and again for v2 publish + upgrade). Note `.machine` is accepted as a
  package name for `PublishMachineVersion` (no `.package`-TLD enforcement on publish), matching the task.
- **Migration** runs against the prior state as the context ROOT, so `{"var":""}` is the whole prior
  state; `retailer-migration.json` = `{"merge":[{"var":""},{"loyaltyPoints":0}]}` and its OUTPUT becomes
  the new state.

## Signed-message discipline (CLAUDE.md #1)

Optional message fields are **omitted**, never set to `null` — the runner's `dropNulls` path strips nulls,
so the client-signed and chain-re-derived canonicals match. The `.ts` mint/event files return only the
present fields.

## Note on the consumer fiber

The deliverable set has no dedicated consumer state machine, so the `consumer` fiber is created from
`manufacturer.definition.json`. It is a third PARTY proving the multi-fiber / signers model; its internal
transitions are intentionally not exercised by the flow.
