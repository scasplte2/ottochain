# Riverdale Economy — E2E Design

**Branch:** `feat/riverdale-economy-e2e` (off `origin/main` @ 0ff9792)
**Goal:** Promote the in-process `RiverdaleEconomyStateMachineSuite` (2,843-line unit test) into a
*real-cluster* e2e on par with `rule110` / `sigma-mixer` / `staked-oracle-pool`, simulating a
**real-world multi-party, multi-(versioned)fiber economy with assets evolving over time.**

---

## 0. The two axes of "concurrency" (why the sigma raise doesn't get us there)

Commit `d5ec899` raised the **sigma-mixer lane to `concurrency: 2`**. That `E2E_CONCURRENCY` knob
runs **K independent single-fiber flows in parallel** — a *throughput/stress* axis (N copies of the
same isolated scenario, stressing the gas budget + chain-keepalive).

Riverdale needs the orthogonal axis: **one scenario spanning M distinct fibers that interact.**

| Axis | Meaning | Today |
|------|---------|-------|
| Lane concurrency (`E2E_CONCURRENCY`) | K independent single-fiber flows at once | ✅ exists |
| **Intra-flow multi-fiber** | A's event mutates B (cross-fiber `_triggers`/`_spawn` + asset moves), then assert B | ❌ **missing — this is the build** |

The **combiner** already does cross-fiber triggers, spawns, and asset custody (proven by
`TriggerEventsSuite`, `SpawnMachinesSuite`, `AssetOpCombinerSuite`). The **runner** can't drive or
observe any of it. So the work is ~70% **harness enablement** and ~30% scenario authoring.

---

## 1. What we're porting (Riverdale recap)

`modules/shared-data/src/test/scala/.../examples/RiverdaleEconomyStateMachineSuite.scala`:

- **6 machine types:** Manufacturer, Retailer, Bank, Consumer, Federal Reserve, Governance.
- **17 instances + 2 spawned auction children**, 26 keypairs (13 active), 54 transitions in 17 phases.
- **Signature features that make it "the economy":**
  - **Cross-machine `_triggers`** — manufacturer→retailer shipment, bank→consumer loan, fed→banks
    rate broadcast, governance→all-taxpayers tax sweep.
  - **Parent-child `_spawn`** — consumer `list_item` spawns a child auction machine that pays the
    parent on `accept_bid`.
  - **Dynamic fiber addition mid-run** — retailers Grace/Ivan and manufacturer Dave added later
    (a natural fit for **versioned schemas** + **late `create`** in e2e).
  - **`machines.$id.state` cross-reads** — retailer checks payment-processor `ACTIVE`, bank checks
    Fed `stable`.
  - **Value evolution over time** — balances, inventory, `totalProduced`, `taxesPaid`, loan
    amortization tracked across the whole run (today purely as fiber `stateData`, *not* real assets).

**The faithful e2e port turns the bookkeeping numbers into real on-chain assets** (RVD currency +
GOODS inventory) so "assets evolving over time" is literally true, not just fiber-local counters.

---

## 2. Capability gap (what the harness can/can't do today)

From `e2e-test/runner.ts` (~1,200 lines) + `lib/*`:

| Need | Today | Gap |
|------|-------|-----|
| Multiple fibers in one flow | One `session.cid` per flow (`runner.ts:420`) | **No named-fiber registry; can't target a 2nd fiber** |
| Assert a fiber changed *indirectly* (by a trigger) | Validator polls only the session fiber | **No assert-arbitrary-fiber step** |
| Per-party signing/ownership | `batchSign` with **all** wallets every message (`sendDataTransaction.ts:61`) | **Can't sign as one party → no real ownership/authorization** |
| Real assets (mint/transfer/morphism) | No asset step actions; SDK builders exist but unused | **No `createAssetPolicy`/`mintAsset`/`applyMorphism` steps; assets nominal today** |
| Spawn-child addressing | Child id is internal to the effect | **Need deterministic `childId` to poll it** |
| Cross-fiber `machines.$id` reads | Engine resolves from `CalculatedState` if dep declared | Need to **declare deps** at create / via `_addDependency` |

**Verified facts driving the design:**

- **`staked-oracle-pool` assets are nominal** — its `stakeAssetId`s are random UUIDs never backed by
  an `AssetRecord`; the `heldAssets` guards are relaxed/never-reached (`initial-data.ts:9-12` admits
  this). So *no e2e exercises real asset custody yet* — Riverdale will be the first.
- **SDK already supports assets** — `ottochain-sdk/src/ottochain/types.ts:803-880` +
  `transaction.ts:273-295` export `CreateAssetPolicy`/`MintAsset`/`ApplyMorphism`/`AuthorizeCompose`
  as first-class `OttochainMessage` members. The e2e pin is `github:ottobot-ai/ottochain-sdk#main`
  (`package.json:17`) — a fresh `npm install` picks them up (current `node_modules` just predates them).
- **Asset effects are real in the combiner** — `_transferAsset` (`ReservedKeys.scala:16`,
  `EffectExtractor.scala:188-235`) moves custody with an R1 holder-ownership gate; `heldAssets` is
  injected into guard/effect context (`ContextProvider.scala:108-124`).
- **Versioning works in e2e today** — `versionable-lifecycle/` proves
  `publishVersion → create(schemaRef) → upgradeFiber(migration)` end-to-end.

---

## 3. Harness extensions (Phase 1 — the linchpin, general-purpose)

These are **reusable by every future e2e**, not Riverdale-specific. All are **back-compatible**
(every new field is optional; default behavior = today).

### 3a. Named multi-fiber within a flow
- `create*` steps accept **`as: "alice"`** → registers `alias → cid` in a flow-local
  `fibers: Record<string,string>` map (seeded alongside `session.cid`).
- **Any** step accepts **`fiber: "alice"`** → resolves `activeCid`/`entityPath` from the alias map
  instead of `session.cid`. Omitted ⇒ current behavior (session fiber).
- Touch points: `runner.ts` step-dispatch (`~551-720`), the `TestStep` interface (`~340-355`),
  confirmation/validation plumbing that currently hard-codes `session.cid`.

### 3b. Per-step signer selection
- Mutating steps accept **`signers: ["alice"]`** (subset of the wallet map). Omitted ⇒ all wallets
  (today's behavior). Enables genuine **ownership** (fiber owned by its creator-signer) and
  **wrong-party negative tests** (bob can't transition alice's fiber).
- Touch point: thread the subset into `sendDataTransaction`'s `batchSign`.

### 3c. Assert-only steps (poll, don't submit)
- **`assertState`**: `{ action:"assertState", fiber:"bob", expectedState?, expectedStateData?,
  minSequenceNumber? }` — polls `/data-application/v1/state-machines/{cid}` for the aliased fiber
  with the existing retry/ordinal budget; reuses `processEvent` deep-equal. No transaction.
- **`assertAsset`**: `{ action:"assertAsset", assetId, holder:{Fiber:"bob"}|{Wallet:"carol"}, amount? }`
  — polls `/data-application/v1/assets/{assetId}` (or the `asset/{id}` proof endpoint,
  `StateProofHandler.scala:37`) and asserts holder + amount.
- These are how we observe **cross-fiber ripples**: submit one trigger to A, then cheaply assert B,
  C, and the moved asset.

### 3d. Asset step actions
- New actions **`createAssetPolicy`**, **`mintAsset`**, **`applyMorphism`** with `lib/asset/*.ts`
  generators wrapping the SDK builders (`createAssetPolicyPayload` etc.). Confirmation model:
  - `createAssetPolicy` → confirm via `/registry/{name}` (non-sequenced, one-shot like `publishVersion`).
  - `mintAsset` → confirm via `/assets/{assetId}` existence.
  - `applyMorphism` → confirm via asset `sequenceNumber` advance.
- `expectRejected: "ml0"` supported (mint-over-cap, soulbound-transfer, etc.).

### 3e. SDK pin refresh
- `npm install` in `e2e-test/` (pin already `#main`); optionally pin a **commit SHA** for CI
  reproducibility. **JAR↔SDK compat:** the metagraph JAR already understands these message types
  (asset model merged via #166/#167; `AssetCombiner.scala`), so no chain change needed.

### 3f. (Stretch) spawn-child addressing
- Pass a **deterministic `childId`** in the `list_item` event payload so `assertState` can poll the
  child by literal cid. Allow `fiber:` to accept a raw UUID (not just an alias).

> **Why this is the right investment:** these six extensions turn the harness from "single-fiber
> smoke test" into "multi-party orchestration + asset ledger" — unlocking Riverdale *and* every
> future composite scenario (DAOs, markets, escrows-with-real-assets).

---

## 4. The economy design — "Riverdale Lite" (faithful, scoped for wall-clock)

Scoped from 17 instances → **6 fibers + 1 spawned child**, one per real-world role, but exercising
**every** signature feature (cross-fiber triggers, broadcast, spawn, versioning, real assets).

### Parties (wallets) — CI sets `wallets: alice,bob,carol,dave,erin,frank`
| Wallet | Role | Fiber |
|--------|------|-------|
| `alice` | Manufacturer | `manufacturer` |
| `bob`   | Retailer | `retailer` (bound to versioned `retailer.machine`) |
| `carol` | Consumer | `consumer` |
| `dave`  | Bank | `bank` |
| `erin`  | Federal Reserve | `fed` |
| `frank` | Governance | `gov` |

### Assets (the "evolving over time" pillar)
| Policy | Behavior | Purpose |
|--------|----------|---------|
| `RVD.asset` (Riverdale Dollar) | Fungible (T+S+C), capped supply, `mintPolicy` open to Fed | Loans, purchases, repayments, taxes; **Fed mints** (monetary expansion), **Gov burns** tax remittance |
| `GOODS.asset` | Combinable inventory unit | Manufacturer mints → ships to Retailer → sells to Consumer |

Morphisms exercised: **Transfer** (the workhorse, via `_transferAsset` from fibers and
`ApplyMorphism(Transfer)` from wallets), **Mint** (Fed expands RVD), **Burn** (Gov sinks tax). One
stretch morphism: **Fractionalize** (consumer "makes change") or **Stake** (bank locks reserve).

### Versioned fiber (the "multi-versioned" pillar)
`retailer.machine` published **v1** and **v2**:
- **v1:** `open → stocking → open`, `process_sale`, `receive_shipment`.
- **v2:** adds `loyaltyPoints`/`tier` fields + a `redeem_loyalty` transition (and loyalty accrual in
  `process_sale`). **Migration** `merge(state, {loyaltyPoints:0, tier:"standard"})`.
- Flow: bob's retailer created **bound to v1**, runs sales, **`upgradeFiber` to v2 mid-run**, then a
  **v2-only** `redeem_loyalty` proves the migration + new transition landed.
- (Optional second versioned type: `fed.machine` v1→v2 adding `emergency_lending`.)

### Cross-fiber interaction map (the heart — each is ONE tx that mutates ≥2 fibers + moves assets)
1. **Supply chain:** alice `fulfill_order` → triggers bob `receive_shipment` **+** `_transferAsset(GOODS: alice→bob)` → *assert* bob `stocking` & bob holds GOODS.
2. **Monetary policy (broadcast):** erin `set_rate` → triggers dave `rate_adjustment` (loop over banks) → *assert* dave `baseRate`.
3. **Lending:** dave `underwrite` → triggers carol `loan_funded` **+** `_transferAsset(RVD: bank→carol)` → *assert* carol `debt_current` & RVD balance.
4. **Commerce:** carol `browse_products` → triggers bob `process_sale` **+** RVD carol→bob **+** GOODS bob→carol → *assert* bob revenue, carol balances.
5. **Loan servicing:** carol `make_payment` → triggers dave `payment_received` **+** RVD carol→dave → *assert* both.
6. **Taxation (broadcast sweep):** frank `collect_taxes` → triggers `pay_taxes` on alice/bob/carol → each `_transferAsset(RVD → gov fiber)` → *assert* each `taxesPaid` + gov `totalTaxesCollected` + gov RVD custody, then **Gov burns** the collected RVD.
7. **(Stretch) Auction spawn:** carol `list_item` → `_spawn` child auction (deterministic `childId`); another wallet `place_bid` → `accept_bid` → triggers carol `sale_completed` + RVD transfer → *assert* child `sold` + carol balance.

### Narrative flow (one primary flow, ~26 mutating steps + cheap asserts)
```
P0 Genesis     createAssetPolicy RVD; createAssetPolicy GOODS;
               publishVersion retailer.machine v1; publishVersion v2;
               mintAsset RVD→carol; mintAsset RVD→dave(bank); mintAsset GOODS→manufacturer-fiber
P1 Create      create manufacturer(as alice) ... create gov(as frank)   [deps declared]
P2 Supply      alice produce → fulfill_order  → assert bob stocking + GOODS custody
P3 Policy      erin quarterly_meeting → set_rate → assert dave baseRate
P4 Lending     dave underwrite → assert carol debt_current + RVD
P5 Commerce    carol browse_products → assert bob revenue + carol RVD/GOODS
P6 Upgrade     upgradeFiber bob v1→v2 (migration) → assert v2 binding + loyaltyPoints
               bob process_sale(v2) → redeem_loyalty → assert loyalty
P7 Servicing   carol make_payment → assert dave + carol RVD
P8 Taxes       frank collect_taxes → assert alice/bob/carol taxesPaid + gov custody → gov burn RVD
P9 (stretch)   carol list_item (spawn) → bid → accept → assert child sold + carol balance
```
**Second (parallel) flow — negatives** (own fiber set, runs under lane concurrency):
- wrong-party transition (`signers:["bob"]` on alice's fiber) → `expectRejected: ml0`
- replay / sequence regression → `expectRejected`
- `mintAsset` over `maxSupply` → `expectRejected`
- `upgradeFiber` to non-monotonic version → `expectRejected`

---

## 5. Pillar coverage

| Pillar | How it's exercised |
|--------|--------------------|
| **Multi-party** | 6 role-owned wallets; per-step `signers`; ownership-gated transitions; wrong-party negative test |
| **Multi-(versioned) fiber** | `retailer.machine` v1→v2 with real migration + v2-only transition; (opt.) fed v1→v2; late `create` of additional fibers |
| **Assets evolving over time** | RVD + GOODS real `AssetRecord`s; mint (Fed expansion), transfer (every cross-fiber economic event), burn (tax sink); custody asserted at each step — *first e2e to exercise real asset custody* |
| **(Bonus) cross-fiber + spawn** | 6 trigger edges + broadcast sweep + auction child spawn |

---

## 6. Wall-clock budget

- ~26 mutating round-trips (each ~15-25s at ML0 ordinal cadence) ⇒ **~8-15 min** for the primary
  flow; asserts are cheap polls (read-only, retry against the same snapshot).
- Cross-fiber triggers are *efficient*: one submitted tx mutates several fibers, so we get
  multi-fiber coverage without N transactions.
- The primary flow is inherently **sequential** (causal chain). The negatives flow runs as a 2nd
  parallel flow. **Lane: `concurrency: 1` for the primary** (causal), bump later if split.
- Lane budget: **`timeout: 45`** (staked-oracle precedent), `ordinalThreshold: 15-30`,
  `maxResubmits: 2`.

---

## 7. CI lane wiring (`.github/workflows/e2e.yml`)

Add to the `matrix.lane` array (each lane = own job/own cluster, parallel to others):
```yaml
- name: riverdale-economy
  filter: '--only riverdale-economy'
  concurrency: '1'
  wallets: 'alice,bob,carol,dave,erin,frank'
  ordinalThreshold: '20'
  maxResubmits: '2'
  timeout: 45
```
And exclude it from `core`'s `--exclude` list (like the other heavy lanes).

---

## 8. Phased delivery plan (+ where subagents parallelize)

**Phase 1 — Harness enablement** *(serial-ish; shared infra; do carefully)*
1. 3a named multi-fiber + 3b per-step signers + 3c assert steps in `runner.ts`/`lib`.
2. 3d asset step actions (`lib/asset/*`) + 3e SDK refresh.
3. Unit-smoke the runner changes against an existing example (back-compat: `core` still green).

**Phase 2 — Fiber definitions** *(MAX parallelism — one subagent per fiber, independent JSON)*
- `manufacturer/definition.json`, `retailer/definition-v1.json`, `retailer/definition-v2.json` +
  `migration.json`, `consumer/definition.json`, `bank/definition.json`, `fed/definition.json`,
  `gov/definition.json`. Each agent ports its riverdale machine, trimmed to the cross-fiber edges
  above, validated against the JLVM opcode catalog (§ effect opcodes + `machines.$id` reads).

**Phase 3 — Asset policies + scenario wiring** *(depends on 1+2)*
- `RVD`/`GOODS` policy JSON; `example.json` primary + negatives flows; event `*.ts` files;
  deterministic ids for assets/child.

**Phase 4 — Local bring-up + iterate** *(`tessellation-cluster` skill / `just e2e-up`)*
- Run `npm test -- --only riverdale-economy` against a local cluster; fix read-lag/dep-declaration
  issues; tune ordinal budget.

**Phase 5 — CI lane + docs + PR**
- Add the lane; `README.md` for the example; update work log.

**Subagent parallelization:** Phase 2 fans out cleanly (N independent fiber-definition authors).
Phase 1 is best done by one focused implementer (shared `runner.ts`); a second agent can build the
asset `lib/` helpers in parallel since they're new files. Verification (does a trigger require a
declared dependency? does the proof endpoint expose `heldAssets`?) can run as parallel Explore tasks.

---

## 9. Risks & verification items (resolve during Phase 1)

1. **Trigger ↔ dependency requirement.** Does a cross-fiber `_triggers` require the target to be a
   declared dependency / pass `acceptedCallers`? `TriggerEventsSuite` proves triggers fire; confirm
   the dep/`acceptedCaller` preconditions and declare deps at `create` (or via `_addDependency`).
2. **`machines.$id` read projection.** Cross-reads (retailer↔payment-processor, bank↔fed) need the
   referenced fiber declared as a dependency to be projected into context (`ContextProvider`).
3. **All-or-nothing block poisoning.** Asset structural checks live at L1 (`assetCommits` bits +
   seq monotonicity); stateful (holder/policy/lineage) in combiner as graceful `CombineRejected`
   (CLAUDE.md rule #3). Negative-test mints/morphisms must be *graceful rejects*, not block drops.
4. **Read-lag on indirect assert.** A triggered fiber updates in the *same* snapshot as its source;
   `assertState` must still poll with the ordinal/DL1-sync budget (the target's commit propagates
   like any other).
5. **Signed-message canonical (CLAUDE.md rule #1).** Asset messages are `Option`/required-no-default
   in the SDK already (`dropNulls` path); no new signed-field hazards expected, but add any new
   message to the canonical golden test if we touch message shapes.
6. **Spawn child id.** Must be deterministic + collision-free across CI re-runs (derive from a fixed
   UUID in the event payload, not `crypto.randomUUID()`).
7. **JAR↔SDK drift.** Confirm the metagraph JAR built in CI accepts `CreateAssetPolicy`/`MintAsset`/
   `ApplyMorphism` (asset model is on `main`; verify the e2e JAR build includes it).
```
