# SDK Std-Lib + Templates — Executable Handoff (RFC)

**Status:** executable handoff (for an agent working primarily in `~/repos/ottochain-sdk`, plus the
genesis side in `~/repos/ottochain`). **Origin:** friction authoring the `riverdale-economy` e2e
(`README.md` findings F1–F10). **Phases:** maps to the README roadmap P2 (SDK template library, off-chain,
low risk) and P3 (genesis std-lib, chain-touching, medium risk).

This is the centerpiece deliverable of the fiber-ergonomics program: a self-contained spec another agent
can execute against. It does NOT implement anything — it is the plan. Read
`docs/proposals/fiber-ergonomics/README.md` first (the findings catalog + the P0→P4 roadmap), then this.

Three moves:

1. A **typed template/builder library** in `@ottochain/sdk` that emits canonical, correct forms for the
   shapes app authors currently hand-roll as raw JSON (asset policies, versioned machines, effect
   directives, migrations, seeded state shapes).
2. A **genesis-loaded canonical std-lib** of vetted registry packages (policy presets, machine skeletons)
   so apps *reference* a blessed `std.*` package rather than redefine it.
3. **Apps consume templates** from the SDK instead of hand-rolling. The `riverdale-economy` example is the
   migration proof.

The hard guardrail throughout (CLAUDE.md rule #1): **nothing may shift the signed-message canonical.** A
template must emit byte-identical canonical to what the chain re-derives — `JCS(dropNulls(payload))` — or
the create/publish signature breaks with an opaque `InvalidSignature` / HTTP 400. Every builder is
additive: hand-rolled JSON keeps working.

---

## 0. Today (baseline)

### 0.1 The hand-rolled status quo

A `riverdale-economy` app author writes, by hand, deeply-nested JSON-Logic across a dozen files. The
recurring shapes (all under `~/repos/ottochain-riverdale-e2e/e2e-test/examples/riverdale-economy/`):

- **Asset policies** as raw JSON with a magic behavior bitmask and a free-form morphisms map —
  `rvd-policy.json:1-19` (`"behavior": 28`, `supply.{mintPolicy,burnPolicy}`, `morphisms:{TRANSFER,
  FRACTIONALIZE, BURN, STAKE}`), `goods-policy.json:1-13` (`"behavior": 20`, `morphisms:{}`),
  `capped-policy.json:1-14` (`supply.maxSupply: 100`). The `28`/`20` are opaque T/S/C/E/G bit sums; a typo
  is undetectable until mint time.
- **Versioned state-machine definitions** as raw `states`/`initialState`/`transitions` JSON —
  `consumer.definition.json:1-174` (6 transitions, an inline 40-line `_spawn` child definition),
  `retailer-v1.definition.json` / `retailer-v2.definition.json` (the v1→v2 verified-binding pair).
- **Effects** as `_`-reserved directive blobs interleaved with state-merge keys —
  `consumer.definition.json:26-47` (`_triggers` + `_transferAsset` + `status`/`purchaseCount` in one
  effect object); the `_transferAsset` recipient is `{ "var": "event.retailerId" }` resolving to a **bare
  string** (`consumer.definition.json:38-43`).
- **Migrations** as `{"merge":[{"var":""},{…}]}` where the context root is the *bare* prior state —
  `retailer-migration.json:1-3` (`{"merge":[{"var":""},{"loyaltyPoints":0}]}`),
  `fed-migration.json:1-3`.
- **Seeded accumulators**: every counter (`purchaseCount`, `taxesPaid`, `loyaltyPoints`) must be pre-seeded
  in `*.initial.json` or the `{"+":[{"var":"state.x"},…]}` reads `null` (F5).

The harness consumes these by FILE NAME in a declarative step DSL —
`example.ts:124-142` (`createAssetPolicy`/`publishVersion`/`create` steps each name a `*.json`), `:193`,
`:208` (`upgradeFiber` names `newDefinition` + `migration` files). Errors surface only at ML0 combine.

### 0.2 The exact SDK gap

The SDK already ships a real, typed builder layer — this proposal *extends* it, it does not start from
zero. What EXISTS (`~/repos/ottochain-sdk/src/`):

| Capability | Where | Status |
|---|---|---|
| Typed message envelopes (`createMintAssetPayload`, `createAssetPolicyPayload`, `createTransitionPayload`, …) | `ottochain/transaction.ts:70-295` | EXISTS — but thin: "the chain message types already model every field … these builders just apply the `{ MessageName: ... }` envelope" (`transaction.ts:266-271`). No domain construction. |
| Typed message/registry/asset types (`CreateAssetPolicy`, `MintAsset`, `SupplyPolicy`, `MorphismSpec`, `AssetHolder`, `SchemaRef`, `TOKEN_BEHAVIOR_BITS`) | `ottochain/types.ts:803-812, 692-716, 723-725, 131-135, 679-685` | EXISTS — types only, no constructors. |
| Typed machine builder `defineFiberApp` + `toProtoDefinition` (projects to the wire `StateMachineDefinition`, omit-on-unconstrained policy, strips authoring-only fields) | `schema/fiber-app.ts:554-561, 641-691` | EXISTS — but `defineFiberApp` is an identity passthrough ("Runtime validation could go here", `:559`). |
| Fiber-policy dial builders `constrained`/`unconstrained`/`immutable` | `schema/fiber-app.ts:379-461` | EXISTS. |
| Effect-directive builders `transferAsset`, `addDependency`, `setDependencyActive` | `schema/effects.ts:29-72` | EXISTS — but only THREE of the directives. |
| Authorization-guard builders (`signerIsParty`, `actorInSet`, `signerHasRole`, `depInState`, …) | `schema/guards.ts:23-285` | EXISTS. |
| Genesis manifest exporter `buildGenesisManifest` | `ottochain/genesis-manifest.ts:269-298` | EXISTS — but covers only 3 std MACHINE apps (identity/governance/markets); content-only (no hashes). |

What is MISSING (the gap this handoff fills):

1. **No asset-policy preset builders.** There is no `fungiblePolicy()`/`nftPolicy()`/`soulboundPolicy()`/
   `customPolicy()`. Authors compute `behavior: 28` by hand and assemble the `supply`/`morphisms` maps raw
   (`rvd-policy.json`). `TOKEN_BEHAVIOR_BITS` exists (`types.ts:679-685`) but nothing consumes it.
2. **No effect-directive builders for `_triggers`, `_spawn`, `_emit`.** Only `transferAsset` /
   `addDependency` / `setDependencyActive` exist (`effects.ts`). The two HEAVIEST riverdale shapes —
   cross-fiber `triggers` (`consumer.definition.json:27-37`) and the inline `spawn` child
   (`:101-153`) — are hand-rolled.
3. **No `migration()` helper.** The `{"merge":[{"var":""},{…}]}` bare-state-root idiom (F9,
   `retailer-migration.json`) is written by hand and is a known foot-gun (root is `{"var":""}`, NOT
   `state.x` like effects).
4. **No state-shape-with-defaults declaration** that auto-seeds accumulators (F5). Authors hand-maintain
   `*.initial.json`.
5. **No versioned-machine skeleton** that ties `PublishMachineVersion` and `CreateStateMachine` to the
   SAME definition for verified binding (F9) — the riverdale README warns the same definition file must be
   reused byte-for-byte (`riverdale-economy/README.md:110-113`); nothing enforces it.
6. **No genesis std-lib of POLICY presets / machine skeletons.** `buildGenesisManifest` ships only machine
   apps, and the chain manifest model carries only `machineShape` (`ottochain/.../GenesisManifest.scala:27-34`)
   — there is no path to pre-register an asset-policy package at genesis at all.

---

## 1. The template catalog

The catalog is a new SDK subpath, `@ottochain/sdk/templates`, re-exporting (and extending) the existing
`schema/*` builders plus the new ones below. Each builder emits the EXACT wire shape and is named so call
sites read as intent. Every example below is a real riverdale form.

### 1.1 Asset-policy presets — removes F10 (and the `behavior: 28` magic of F1's redesign)

Emits the `CreateAssetPolicy` body (`types.ts:803-812`). On-chain the policy's `schemaHash` and
`logicHash` are both `RegistryShape.AssetPolicy.computeDigest` (no JSON-Logic body) —
`ottochain/.../AssetCombiner.scala:82-123` — so the presets are pure data and fully deterministic.

Raw form it replaces (`rvd-policy.json:1-19`):

```jsonc
{ "name": "rvd.asset", "version": "1.0.0", "behavior": 28,
  "supply": { "mintPolicy": { "==": [1, 1] }, "burnPolicy": { "==": [1, 1] } },
  "morphisms": { "TRANSFER": { "visibility": "PUBLIC" }, "FRACTIONALIZE": { "visibility": "PUBLIC" },
                 "BURN": { "visibility": "PUBLIC" }, "STAKE": { "visibility": "PUBLIC" } },
  "stateShape": { "typeName": "RvdState", "fields": [] } }
```

Proposed API (`src/templates/asset-policy.ts`):

```ts
import type { CreateAssetPolicy, SupplyPolicy, MorphismSpec, MorphismKind } from '../ottochain/types.js';

/** Fungible currency: T|S|C = 28. TRANSFER + FRACTIONALIZE + BURN(opt) + STAKE morphisms, all PUBLIC. */
export function fungiblePolicy(p: {
  name: string;               // must end `.asset`
  version: string;            // SemVer
  decimals?: number;          // supply.decimals
  maxSupply?: number;         // omit => uncapped
  mintable?: boolean;         // true => supply.mintPolicy = {"==":[1,1]} (or pass a guard)
  burnable?: boolean;         // true => supply.burnPolicy AND BURN morphism
  mintGuard?: unknown;        // JSON-Logic predicate, overrides mintable's default
  stakeable?: boolean;        // default true => STAKE morphism (codomain E:=1)
  stateTypeName?: string;     // stateShape.typeName, default `${PascalName}State`
  metadata?: Record<string, string>;
}): CreateAssetPolicy;

/** Non-fungible: T = 16 (or T|C = 20 with `combinable: true`, the riverdale `goods.asset`). No morphisms by default. */
export function nftPolicy(p: {
  name: string; version: string;
  combinable?: boolean;       // 16 -> 20
  transferable?: boolean;     // default true; false => 0/4 (a bound collectible)
  metadata?: Record<string, string>;
}): CreateAssetPolicy;

/** Soulbound: non-transferable, governable only (G = 1). No TRANSFER morphism; mint closed after issue. */
export function soulboundPolicy(p: {
  name: string; version: string; expirable?: boolean; metadata?: Record<string, string>;
}): CreateAssetPolicy;

/** Escape hatch: declare behavior bits by NAME (never the magic int) + raw supply/morphisms. */
export function customPolicy(p: {
  name: string; version: string;
  behavior: (keyof typeof TOKEN_BEHAVIOR_BITS)[];   // ['transferable','combinable'] -> 20
  supply: SupplyPolicy;
  morphisms: Partial<Record<MorphismKind, MorphismSpec>>;
  stateTypeName?: string; metadata?: Record<string, string>;
}): CreateAssetPolicy;
```

`behavior` is summed from `TOKEN_BEHAVIOR_BITS` (`types.ts:679-685`: T=16, S=8, C=4, E=2, G=1) — never a
literal. Presets: Fungible=28, NFT=16, goods-style NFT=20, soulbound=1. `morphisms` is REQUIRED on the wire
(presence required, emptiness meaningful — `Updates.scala:251-263`), so `nftPolicy` emits `morphisms: {}`
explicitly, never omits it.

### 1.2 Versioned-machine skeleton + `transition()`/`guard()`/`effect()` — removes F9, F10

`defineFiberApp` (`fiber-app.ts:554`) already gives a typed machine. Add (a) a `transition()`/`guard()`/
`effect()` composition layer so an effect is built from typed fragments rather than a JSON blob, and (b) a
`machine()` skeleton that binds publish + create to ONE definition for verified binding.

```ts
// src/templates/machine.ts

/** A typed transition. `effect()` composes state-merge fields + the `_`-directive fragments (§1.3). */
export function transition<S extends string, E extends string>(t: {
  from: S; to: S; on: E;
  guard?: GuardRule;                 // default {"==":[1,1]}; reuse schema/guards.ts builders
  effect?: Record<string, unknown>;  // built by effect()
  dependencies?: string[];           // bare UUID strings only (toProtoDefinition drops the rest)
}): Transition<S, E>;

/** Compose an effect: the state-update fields PLUS any `_`-directive fragments, in one map.
 *  `effect({ status: 'received' }, transferAsset([...]), triggers([...]))` spreads the directives in. */
export function effect(
  stateUpdate: Record<string, unknown>,
  ...directives: Record<string, unknown>[]
): Record<string, unknown>;

/** Re-export the guard builders so `guard.signerIsParty('state.borrower')` reads at the call site. */
export * as guard from '../schema/guards.js';

/**
 * A versioned package skeleton. Holds ONE canonical FiberAppDefinition and emits BOTH:
 *   - publishVersion(): the PublishMachineVersion body (definition = toWireDefinition(def))
 *   - create(opts):     the CreateStateMachine body with schemaRef name@version + the SAME definition
 * The definitions are byte-identical, so the chain's `definition.computeDigest` matches the registered
 * `logicHash` and the verified bind admits the fiber (F9; Updates.scala:166-179, riverdale README:110-113).
 */
export function machine<S extends string, E extends string>(spec: {
  name: string;            // `<pkg>.package`
  version: string;
  app: FiberAppDefinition<S, E>;
  schemaShape: MachineShape;          // the proto projection (advisory)
}): {
  publishVersion(o?: { strict?: boolean; metadata?: Record<string,string> }): PublishMachineVersion;
  create(o: { fiberId: string; initialData: unknown; participants?: string[] }): CreateStateMachine;
  upgradeFrom(o: { fiberId: string; targetSequenceNumber: number; migration?: unknown }): UpgradeFiber;
  wireDefinition(): StateMachineDefinition;   // for golden round-trip tests
};
```

This directly encodes the F9 invariant: the skeleton OWNS the definition, so publish and create cannot
drift. `toWireDefinition`/`toProtoDefinition` already enforce the wire-parity rules (required
`dependencies: []`, omit-on-unconstrained `policy`, stripped authoring fields — `fiber-app.ts:641-691`,
`genesis-manifest.ts:227-255`); the skeleton reuses them.

### 1.3 Effect-directive builders — removes F4 (typo'd `_trigger` silently becomes state)

Complete the `effects.ts` set. Existing: `transferAsset`, `addDependency`, `setDependencyActive`
(`effects.ts:29-72`). Add `triggers`, `spawn`, `emit`. Each emits the EXACT reserved-key shape the chain's
`EffectExtractor` reads (`ottochain/.../EffectExtractor.scala`, keys in `ReservedKeys.scala:12-49`). All
return a `Record` so `effect()` (§1.2) spreads them into the effect map.

**`transferAsset(transfers)`** — EXISTS (`effects.ts:70-72`). Emits `{ _transferAsset: [{ assetId,
recipient }] }`. Critical F2 detail it already gets right: `recipient` resolves to a **bare string**, NOT
an `AssetHolder` object — `EffectExtractor.parseRecipient` (`EffectExtractor.scala:242-246`) maps a
UUID-shaped string → `Fiber`, a DAG address → `Wallet` (UUID tried first). The catalog keeps this and adds
two typed convenience wrappers so authors stop reasoning about the disambiguation:

```ts
export const toFiber  = (fiberId: JsonLogicValue) => fiberId;      // identity; documents intent
export const toWallet = (address: JsonLogicValue) => address;      // identity; documents intent
// transferAsset([{ assetId, recipient: toFiber({ var: 'event.retailerId' }) }])
```

Replaces `consumer.definition.json:38-43`.

**`triggers(triggers)`** — NEW. Emits `{ _triggers: [{ targetMachineId, eventName, payload }] }`
(`ReservedKeys.scala:25-27`, `EffectExtractor.scala:104-141`). Replaces `consumer.definition.json:27-37`:

```ts
export const triggers = (
  ts: { target: JsonLogicValue; event: string; payload?: Record<string, unknown> }[],
): Record<string, unknown> => ({
  _triggers: ts.map(t => ({ targetMachineId: t.target, eventName: t.event, payload: t.payload ?? {} })),
});
```

**`spawn(directives)`** — NEW. Emits `{ _spawn: [{ childId, definition, initialData, owners }] }`
(`ReservedKeys.scala:35-38`, `EffectExtractor.scala:323-376`). Note the chain extracts `_spawn` from the
effect EXPRESSION, not the evaluated result — the `definition` must be a literal machine. Replaces the
40-line inline child in `consumer.definition.json:101-153`:

```ts
export const spawn = (
  ds: { childId: JsonLogicValue; definition: ProtoStateMachineDefinition;
        initialData: Record<string, unknown>; owners: JsonLogicValue }[],
): Record<string, unknown> => ({ _spawn: ds });
```

`owners` is the F8 gotcha: a spawned child's transitions are gated by `owners ∪ authorizedSigners`
(`riverdale-economy/README.md:103-107`), so the builder's doc-comment must say "every party that will
drive the child (e.g. bidders) must be in `owners`." Accept `definition` as the output of a nested
`machine().wireDefinition()` so the child is itself a typed template.

**`emit(events)`** — NEW. Emits `{ _emit: [{ name, data, destination? }] }` (`ReservedKeys.scala:108-111`,
`EffectExtractor.scala:307-321`):

```ts
export const emit = (
  es: { name: string; data: JsonLogicValue; destination?: string }[],
): Record<string, unknown> => ({ _emit: es });
```

Because these are builders, a typo is a TypeScript error, not a silent state field (F4). The catalog must
also export a `RESERVED_EFFECT_KEYS` set (`['_triggers','_spawn','_emit','_transferAsset','_scriptCall',
'_addDependency','_setDependencyActive']`, from `ReservedKeys.scala:12-18`) that Proposal 01's validator
checks against.

### 1.4 `migration()` helper — removes F9

The migration context root is the BARE prior state — `FiberEngine.scala:300`
(`MeteredEvaluator.eval(expr, sm.stateData, Migration)`), so `{"var":""}` is the whole prior state and
`{"var":"loyaltyPoints"}` reads a field directly (NOT `state.loyaltyPoints` like effects do). This
asymmetry is the foot-gun. Helper (replaces `retailer-migration.json:1-3` / `fed-migration.json`):

```ts
// src/templates/migration.ts

/** Seed/overwrite top-level state fields on upgrade. Emits {"merge":[{"var":""},{...seedFields}]}.
 *  The root is the BARE prior state ({"var":""}), unlike an effect's `state.x` — this hides that. */
export const seedFields = (fields: Record<string, unknown>): unknown =>
  ({ merge: [{ var: '' }, fields] });

/** General transform with the bare-state root made explicit: priorState resolves to {"var":""}. */
export const migration = (build: (priorState: { var: '' }) => unknown): unknown =>
  build({ var: '' });
```

`seedFields({ loyaltyPoints: 0 })` ⇒ `{"merge":[{"var":""},{"loyaltyPoints":0}]}` — byte-identical to
`retailer-migration.json`. `migration` is `Option` on `UpgradeFiber` (`Updates.scala:90-95`), so omit it
when absent (do not send `null`).

### 1.5 State-shape-with-defaults — removes F5 (ties to Proposal 01)

A declared state shape with defaults serves THREE consumers: it seeds `initialData` (so accumulators are
never `null`), it feeds the dry-run validator (Proposal 01), and it can carry into the policy/machine
`stateShape`. This is the SDK half of Proposal 01's "declared state-shape with defaults":

```ts
// src/templates/state-shape.ts

export interface StateShape { fields: Record<string, { default: unknown; type?: SchemaFieldType }>; }

/** Build initialData by overlaying explicit values on the declared defaults — no field reads null. */
export function seedState(shape: StateShape, overrides?: Record<string, unknown>): Record<string, unknown>;

/** The set of state field names the effects may read — for Proposal 01's var-path checks. */
export function declaredFields(shape: StateShape): Set<string>;
```

`seedState({ fields: { purchaseCount: { default: 0 }, status: { default: 'debt_current' } } })` replaces
the hand-kept `consumer.initial.json`.

---

## 2. The genesis std-lib

A canonical, vetted set of `std.*` registry packages pre-registered at ordinal 0, so apps reference a
blessed package (verified-bound) instead of re-publishing one. Grounded in
`~/repos/ottochain/docs/proposals/genesis-and-engine-versioning.md`.

### 2.1 Which packages to publish at genesis

- **Policy presets as `std.*.asset` packages** — `std.fungible.asset`, `std.nft.asset`,
  `std.soulbound.asset` (the §1.1 presets at `1.0.0`). An app's `MintAsset.policyRef`
  (`types.ts:815-827`) then resolves `{ name: 'std.fungible.asset', version: {Exact:{version:'1.0.0'}} }`
  instead of every app publishing its own `rvd.asset`.
- **Machine skeletons as `std.*.package`** — the existing `std.identity.package` /
  `std.governance.package` / `std.markets.package` (`genesis-manifest.ts:269-298`), plus any riverdale
  archetypes worth blessing (e.g. a generic `std.wallet.package`, `std.escrow.package`). These already
  flow through the manifest path.

Naming: `RegistryName` reserves the `std` label in-protocol (`RegistryName.isReserved`), so ordinary user
registrations of `std.*` are REJECTED — only the privileged genesis path may claim them
(`genesis-manifest.ts:23-28`, `genesis-and-engine-versioning.md:75-77`). That reservation is exactly what
makes genesis the trust root.

### 2.2 How: the genesis registry-seeding mechanism

The seam (`genesis-and-engine-versioning.md:16-77`):

```
pinned ottochain-sdk release
  └─ buildGenesisManifest()  →  GenesisManifest (CONTENT: machineShape + definition; NO hashes)
        └─ genesis.json  (GenesisData codec)
              └─ ML0Service.genesis loads it  (GenesisLoader.load, GenesisLoader.scala)
                    └─ GenesisManifestLoader.fromManifest  (computes schemaHash/logicHash with the chain's
                       OWN computeDigest)  →  GenesisBuilder.withPackages  →  DataState
```

What LANDED already:
- `GenesisBuilder.withPackages` builds a consistent genesis `DataState` (`registry` + `registryCommits` via
  each entry's `computeDigest`), first version stamped `Active` at `SnapshotOrdinal.MinValue`
  (`ottochain/.../genesis/GenesisBuilder.scala:30-105`).
- `GenesisManifestLoader.fromManifest` turns an SDK `GenesisManifest` into that genesis, computing
  `logicHash = definition.computeDigest`, `schemaHash = machineShape.computeDigest`
  (`GenesisManifestLoader.scala:24-45`).
- `GenesisLoader.load` reads the configured path, decodes `GenesisData`, or falls back to the empty genesis
  (`GenesisLoader.scala`); wired into `ML0Service.make` (`ottochain/.../ML0Service.scala:57-70`).
- The SDK exporter `buildGenesisManifest` (`genesis-manifest.ts:269-298`) and the chain manifest model
  `GenesisManifest`/`ManifestPackage` (`ottochain/.../schema/GenesisManifest.scala:20-34`).

What MUST be BUILT for the policy std-lib (this is the chain-side delta, the P3 work):
- **Asset-policy genesis support.** Both `GenesisBuilder.PackageSpec` (`GenesisBuilder.scala:33-42`) and
  the chain `ManifestPackage` (`GenesisManifest.scala:27-34`) carry ONLY `machineShape` and build
  `RegistryTarget.SchemaPackage` with `RegistryShape.Machine` (`GenesisBuilder.scala:89`). An asset policy
  is a `RegistryTarget.AssetPolicyPackage` whose version shape is `RegistryShape.AssetPolicy`
  (`ottochain/.../registry/RegistryTarget.scala:38`, `SchemaShape.scala:115`), with
  `schemaHash = logicHash = AssetPolicy.computeDigest` (mirror `AssetCombiner.scala:82-123`). So:
  - Extend the manifest model with an asset-policy package arm (a sum: machine | asset), or a parallel
    `assetPackages: List[AssetPolicyManifestPackage]` carrying `behavior`/`supply`/`morphisms`/`stateShape`.
  - Extend `GenesisBuilder` with an `assetPolicy` PackageSpec arm that builds the `AssetPolicyPackage`
    target and computes the digest the SAME way the combiner does (reuse `RegistryShape.AssetPolicy.computeDigest`).
  - Extend the SDK `buildGenesisManifest` to emit the policy packages (content only — `behavior`/`supply`/
    `morphisms`/`stateShape`, from the §1.1 presets).
- The "genesis-prep tool" (off-chain, deferred per `genesis-and-engine-versioning.md:168`) that reads the
  pinned SDK release and writes `genesis.json`. The SDK side is `buildGenesisManifest`; the chain side is
  `GenesisManifestLoader`. The remaining glue is the CLI that calls one and serializes for the other.

### 2.3 Ownership / governance

`PackageSpec.owner` / `GenesisManifestLoader.fromManifest(owner = …)` set the `RegistryEntry.owner`
addresses (`GenesisBuilder.scala:39, 94`; `GenesisManifestLoader.scala:26`). For std packages this is a
governance/foundation address (the blessed-apps trust root — `genesis-and-engine-versioning.md:75-77`).
Decisions to record: (a) is `std.*` owner a single foundation key, a multisig, or `Set.empty` (immutable,
no future versions)? (b) may std packages be versioned post-genesis (owner publishes `v2`) or are they
frozen to genesis? Frozen is safest for reproducibility; pick it unless an upgrade story is needed.

### 2.4 Determinism — genesis must be reproducible

The genesis-prep pipeline is reproducible iff the SDK release → `genesis.json` is a pure function
(`genesis-and-engine-versioning.md:48-77`: "same release ⇒ byte-identical genesis"). Rules for the std-lib
builders:
- **No `Date.now()`, no `crypto.randomUUID()`, no `Math.random()`** anywhere in a template that feeds the
  manifest. Std package names, versions, definitions, policy shapes are all fixed literals. (Riverdale
  already learned this for instance ids — `ids.ts:1-10`.)
- **The SDK ships CONTENT, never consensus hashes.** `buildGenesisManifest` deliberately emits no
  `logicHash`/`schemaHash` (`genesis-manifest.ts:1-44` DESIGN note) — the chain derives them via its own
  `computeDigest`, so there is ZERO cross-language hash-parity risk. Keep this for asset policies: emit
  `behavior`/`supply`/`morphisms`/`stateShape`, let the chain hash. This is the single most important
  determinism property — do not replicate the chain's hashing in TS.
- **Pin the manifest schema version** (`GENESIS_MANIFEST_VERSION`, `genesis-manifest.ts:135`) and bump it on
  any shape change.
- **Fixed iteration order.** Emit packages in a stable, declared order (the chain stores them in a
  `SortedMap` keyed by name — `GenesisBuilder.scala:97-99` — so order is normalized on-chain, but keep the
  manifest stable for byte-identical `genesis.json` diffs).

---

## 3. App consumption

The target developer experience: an app author imports templates and submits, never touching raw
JSON-Logic.

```ts
import {
  fungiblePolicy, machine, transition, effect, guard,
  transferAsset, triggers, spawn, seedState, seedFields,
} from '@ottochain/sdk/templates';
import { createAssetPolicyPayload, signTransaction } from '@ottochain/sdk';

// 1. A policy (or just reference the genesis std.fungible.asset — see below)
const rvd = fungiblePolicy({ name: 'rvd.asset', version: '1.0.0', mintable: true, burnable: true });

// 2. A versioned machine, ONE definition shared by publish + create (verified binding)
const consumer = machine({
  name: 'consumer.package', version: '1.0.0', schemaShape: consumerShape,
  app: defineFiberApp({
    metadata: { name: 'Consumer', app: 'riverdale', type: 'consumer', version: '1.0.0' },
    states: { ACTIVE: { id: 'ACTIVE', isFinal: false }, debt_current: { id: 'debt_current', isFinal: false } },
    initialState: 'ACTIVE',
    transitions: [
      transition({
        from: 'debt_current', to: 'debt_current', on: 'buy',
        effect: effect(
          { status: 'debt_current', purchaseCount: { '+': [{ var: 'state.purchaseCount' }, 1] } },
          triggers([{ target: { var: 'event.retailerId' }, event: 'process_sale',
                      payload: { buyerId: { var: 'machineId' }, quantity: { var: 'event.quantity' } } }]),
          transferAsset([{ assetId: { var: 'event.payAssetId' }, recipient: { var: 'event.retailerId' } }]),
        ),
      }),
    ],
  }),
});

// 3. Submit: publish the version, then create the fiber bound to it
const pub = createPublishMachineVersionPayload(consumer.publishVersion({ strict: false }));
const create = createStateMachinePayload(consumer.create({
  fiberId: CONSUMER_ID,
  initialData: seedState({ fields: { purchaseCount: { default: 0 }, status: { default: 'debt_current' } } }),
}));
await signTransaction(pub, bobKey);    // then POST to DL1 /data
```

### 3.1 The verified-binding flow

The load-bearing guarantee: `consumer.publishVersion().definition` and `consumer.create().definition` are
the SAME object (the skeleton owns it, §1.2), so the chain's `definition.computeDigest` equals the
registered `logicHash` and the bind admits the fiber (`Updates.scala:166-179` "a fiber referencing this
version is admitted only if its definition hashes to `logicHash`"). The riverdale README confirms the
discipline this automates: "the SAME definition file is used for both publish + create"
(`riverdale-economy/README.md:110-113`). For a GENESIS-published `std.*` package, the app skips
`publishVersion` and only `create`s with `schemaRef: { name: 'std.fungible.asset', version:
{Exact:{version:'1.0.0'}} }` — the SDK template that emits the app's definition MUST hash to the SAME
`logicHash` the genesis manifest produced (this is why the genesis manifest and the §1 templates share the
SAME `toWireDefinition`/preset code — one source of truth, no drift).

### 3.2 Migration of `riverdale-economy` (the proof)

The harness step DSL names `*.json` files (`example.ts:124-142`). Two migration options, pick per the
execution plan:
- **Minimal**: add a `build:templates` script that runs the §1 builders and writes the SAME `*.json` files
  (a golden round-trip: builder output === current checked-in JSON, byte-for-byte). The harness is
  unchanged; the JSON becomes generated, not hand-written.
- **Full**: teach the harness to accept an in-memory definition/policy object (not just a filename) and
  pass `consumer.create(...)` / `fungiblePolicy(...)` directly. Larger harness change.

The round-trip (builder → JSON === checked-in JSON) is the acceptance test either way: it proves the
templates emit the exact canonical the chain already accepts in the green lane.

---

## 4. Safety & compatibility

- **Additive.** Every builder is a pure function returning the same wire shape hand-rolled JSON produces.
  The raw-JSON path keeps working; the harness file-name DSL is untouched in the minimal migration. No
  chain change is needed for §1 (P2) at all.
- **Signed-canonical invariant (CLAUDE.md #1).** A template MUST emit byte-identical canonical to what the
  chain re-derives: the SDK signs `batchSign(dropNulls(payload))` and the chain re-encodes + verifies over
  `JCS(dropNulls(payload))`. Templates therefore:
  - **Omit** absent optionals, never emit `null` (so `dropNulls` is a no-op on the difference) —
    `migration`/`metadata`/`participants` (`Updates.scala:90-95`, `transaction.ts:266-271`).
  - **Always send** required-no-default fields: `strict` on publish (`types.ts:600`), `morphisms` on
    policy (`Updates.scala:251-263`), `dependencies: []` per transition, `repeated`/`optional` on every
    `FieldShape` (`types.ts:146-152`). `toProtoDefinition` already guarantees the `dependencies`/`policy`
    rules (`fiber-app.ts:646-688`); the new builders must hold the same line.
  - **Reuse the existing projectors** (`toProtoDefinition`, `projectFiberPolicy`) rather than re-deriving
    the wire shape, so there is one canonicalization path.
  - **Any NEW optional field** added to a signed message stays `Option`/omit-safe and is added to the
    chain's `PublishVersionSigningCanonicalSuite` (CLAUDE.md #1) — but §1 adds NO new message fields, only
    builders that fill existing ones, so this risk is confined to §2.1's asset-policy genesis arm.
- **No registry-lineage reads leak into block acceptance (CLAUDE.md #3).** Templates and genesis seeding
  touch only message construction and the genesis `DataState`; they add NO `validateSignedUpdate` logic, so
  the TOCTOU block-poisoning hazard does not apply. (Asset-policy resolution stays combine-only —
  `AssetCombiner.scala`.)
- **Std-lib versioning.** Genesis packages are pinned at `1.0.0` at ordinal 0. An app bound to
  `std.fungible.asset@1.0.0` keeps resolving that exact version forever (existing pinned bindings keep
  running even across status changes — `RegistryStatus` semantics, `types.ts:96-103`). A future genesis
  that adds `std.fungible.asset@2.0.0` does NOT break the bound app. Choose §2.3's frozen-vs-owned model so
  a genesis change is purely additive to lineage.

---

## 5. Execution plan for the SDK agent

Ordered, risk-ascending. **[SDK]** = `~/repos/ottochain-sdk`, **[CHAIN]** = `~/repos/ottochain`.

### Phase A — SDK template library (P2, additive, no chain change)

1. **[SDK]** Create `src/templates/` with subpath export `@ottochain/sdk/templates` (mirror
   `src/schema/index.ts` and the `package.json` `exports` map). Re-export the existing builders
   (`schema/effects.ts`, `schema/guards.ts`, `schema/fiber-app.ts`'s `defineFiberApp`/`toProtoDefinition`/
   `constrained`).
2. **[SDK]** `src/templates/asset-policy.ts` — `fungiblePolicy`/`nftPolicy`/`soulboundPolicy`/`customPolicy`
   (§1.1). Behavior via `TOKEN_BEHAVIOR_BITS` (`types.ts:679-685`); emit `CreateAssetPolicy` (`types.ts:803-812`).
3. **[SDK]** Extend `src/schema/effects.ts` with `triggers`/`spawn`/`emit` + `toFiber`/`toWallet` and a
   `RESERVED_EFFECT_KEYS` export (§1.3). Keys verbatim from `ReservedKeys.scala:12-49`.
4. **[SDK]** `src/templates/machine.ts` — `transition()`/`effect()`/`guard` re-export + the `machine()`
   skeleton (§1.2); the skeleton reuses `toProtoDefinition` (`fiber-app.ts:641`) for the wire definition.
   Add `createPublishMachineVersionPayload` if absent (envelope only).
5. **[SDK]** `src/templates/migration.ts` (`seedFields`/`migration`, §1.4) and `src/templates/state-shape.ts`
   (`seedState`/`declaredFields`, §1.5).
6. **[SDK]** Tests:
   - Unit: each builder emits the exact object (snapshot tests against the riverdale raw JSON — e.g.
     `fungiblePolicy({name:'rvd.asset',…})` === `rvd-policy.json`).
   - **Golden canonical round-trip** (the load-bearing one): for each template output, sign with
     `signTransaction` and assert the `dropNulls`→JCS bytes match a fixture captured from the chain's green
     lane (reuse `tests/ottochain/signing-parity.test.ts` machinery). This is the proof that templates
     don't shift the signed canonical (§4).
   - Verified-binding test: `machine().publishVersion().definition` === `machine().create().definition`.
7. **[SDK]** Migrate `riverdale-economy` (§3.2, minimal option first): a `build:templates` script that
   regenerates the `*.json` files from builders and a CI check that the regenerated files === the
   checked-in files (byte-for-byte). This is the end-to-end proof for Phase A.

### Phase B — Genesis std-lib (P3, chain-touching, lands after A stabilizes)

8. **[SDK]** Extend `buildGenesisManifest` (`genesis-manifest.ts:269`) to emit the policy std packages
   (`std.fungible.asset` etc.) using the §1.1 presets — content only (`behavior`/`supply`/`morphisms`/
   `stateShape`), no hashes.
9. **[CHAIN]** Extend the manifest model `GenesisManifest`/`ManifestPackage`
   (`schema/GenesisManifest.scala:20-34`) with an asset-policy package arm.
10. **[CHAIN]** Extend `GenesisBuilder.PackageSpec`/`build` (`genesis/GenesisBuilder.scala:33-105`) and
    `GenesisManifestLoader.fromManifest` (`GenesisManifestLoader.scala:24-45`) to build a
    `RegistryTarget.AssetPolicyPackage` with `RegistryShape.AssetPolicy` and
    `schemaHash = logicHash = AssetPolicy.computeDigest`, mirroring `AssetCombiner.scala:82-123`.
11. **[CHAIN]** Tests: extend `GenesisBuilderSuite` (an asset-policy genesis entry is consistent —
    `registry` ↔ `registryCommits`); a fiber/mint bound to a genesis `std.*.asset` resolves and admits.
    Add a `PublishVersionSigningCanonicalSuite` case if any new signed-message field is introduced (§4).
12. **[CHAIN/SDK]** The genesis-prep CLI (deferred, `genesis-and-engine-versioning.md:168`): SDK release →
    `genesis.json` → `ML0Service` HOCON path. Reproducibility test: same SDK release ⇒ byte-identical
    `genesis.json`.
13. **[E2E]** Add a riverdale lane (or extend FLOW 1 P0) that boots from a genesis std-lib and has an app
    reference `std.fungible.asset` instead of publishing `rvd.asset` — the genesis std-lib proof.

### Cross-repo version pin (the SDK ↔ JAR compatibility)

The JAR and the SDK release MUST match (durable ops fact): an environment is the triple (node binary
`engineVersion`, pinned SDK release `std.*` bytes, genesis pre-registering that set —
`genesis-and-engine-versioning.md:9-13`). Concretely:
- Phase B genesis is produced by a SPECIFIC pinned SDK release; the node binary that boots it must
  understand that `engineVersion` (`genesis-and-engine-versioning.md:105-159`). Record the SDK release ↔
  JAR version pin alongside the `genesis.json` (e.g. in the manifest `metadata` and the deploy config).
- The §1 templates (Phase A) carry no version coupling — they emit shapes the current chain already
  accepts (proven by the golden round-trip), so they ship on the SDK's own cadence.

---

## 6. Open questions (the SDK agent must resolve)

1. **Can genesis publish ASSET-POLICY registry entries today, or only machine packages?** As of this doc,
   `GenesisBuilder.PackageSpec` and the chain `ManifestPackage` are machine-only
   (`GenesisBuilder.scala:33-42, 89`; `GenesisManifest.scala:27-34`). Confirm whether the policy std-lib is
   (a) a chain-side extension (steps 9–10), or (b) a post-genesis bootstrap transaction signed by the std
   owner at ordinal 1 (no chain change, but std names are reserved against non-genesis publish —
   `genesis-manifest.ts:23-28` — so the bootstrap would need a privileged path or a non-`std` name). Decide
   the mechanism before Phase B.
2. **Genesis vs post-genesis for the FIRST environment.** `genesis-and-engine-versioning.md:163-171` lists
   the `GenesisData` codec + `ML0Service.genesis` file-load as "deferred (#39)" — verify the file-load path
   is actually wired (it appears to be: `GenesisLoader.scala` + `ML0Service.scala:57-70`). If the genesis
   file path is not yet honored in the deployed JAR, Phase B's machine std-lib may already work in-process
   but not from a config file.
3. **Std-package ownership/governance + frozen-vs-versioned** (§2.3): single key, multisig, or
   `Set.empty`? May `std.*` get a `v2` post-genesis? This drives §4's versioning guarantee.
4. **Asset-policy `logicHash` stability across SDK refactors.** Since `logicHash =
   AssetPolicy.computeDigest` over `behavior`/`supply`/`morphisms`/`stateShape`
   (`AssetCombiner.scala:82-123`), ANY change to a preset's emitted shape changes the genesis `logicHash`
   and breaks apps bound to it. Confirm the presets' output is frozen once published, and add a golden
   fixture pinning each preset's exact JSON.
5. **Subpath vs root export.** Should the catalog be a distinct `@ottochain/sdk/templates` subpath (tree-
   shakeable, opt-in) or merged into the root `index.ts`? The README handoff says `@ottochain/sdk/templates`
   (`README.md:39`); confirm against the SDK's `exports` map + bundler conventions.
6. **`spawn` literal-definition constraint.** `EffectExtractor.extractSpawnDirectivesFromExpression`
   (`EffectExtractor.scala:323-376`) reads `_spawn` from the effect EXPRESSION, requiring the child
   `definition` to be a literal (not an evaluated value). Confirm the `spawn()` builder's child definition
   survives `toProtoDefinition` projection without being treated as an expression to evaluate.
7. **Harness consumption depth** (§3.2): minimal (regenerate `*.json`) vs full (in-memory objects). The
   full path needs a harness change to accept definition/policy objects in the step DSL (`example.ts:124-142`).
   Decide before step 7/13.
