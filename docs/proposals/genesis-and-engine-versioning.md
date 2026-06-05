# Genesis Pre-Registration & Engine Versioning

Status: design (with chain-side core landed). Covers three coupled questions:

1. **How do we bake a pinned `std.*` package set into genesis?**
2. **Can we engineer genesis states to test edge cases / state evolutions in e2e?**
3. **What is `engineVersion`, and how do we upgrade it?**

They are coupled because *an environment* is defined by exactly three pinned things — a node
**binary** (which fixes an `engineVersion`), a pinned **ottochain-sdk release** (which fixes the
`std.*` package bytes), and a **genesis** that pre-registers that package set at ordinal 0. Change any
one and you have a new environment.

---

## 1. The genesis seam

A metagraph's genesis is whatever `DataState` its L0 service hands back:

```scala
// modules/l0/.../metagraph_l0/ML0Service.scala
override def genesis: DataState[OnChain, CalculatedState] =
  DataState(OnChain.genesis, CalculatedState.genesis)   // empty registry today
```

`CalculatedState.genesis` ships an **empty** `registry`. Pre-registration and e2e-state-crafting are the
same move: **hand back a non-empty `DataState`** instead. Nothing else in the pipeline changes — the
chain treats genesis state as authoritative and builds from it.

The only correctness obligation is *internal consistency*: every `CalculatedState.registry` entry must
have a matching `OnChain.registryCommits` commitment (the per-entry hash light clients prove against).
Hand-building that by hand is error-prone, so the chain-side builder owns it.

### `GenesisBuilder` (landed — `7093b8b`)

`xyz.kd5ujc.shared_data.genesis.GenesisBuilder.withPackages` takes a list of `PackageSpec`
`(name, version, schemaHash, logicHash, schemaShape, owner, strict, metadata)` and returns a genesis
`DataState` whose `registry` and `registryCommits` are guaranteed consistent (it computes each entry's
canonical digest via `computeDigest`). The first version of each package is stamped `Active` at
`SnapshotOrdinal.MinValue`. Empty list ⇒ the empty genesis. This is the one place that knows how to
assemble a *valid* pre-state; both the genesis-prep tool and e2e build on it.

---

## 2. Baking `std.*` packages into genesis (#39)

The std package **bytes** (the JSON-Logic definition, the proto/`schemaShape`, the hashes) belong to a
pinned ottochain-sdk release, off-chain. The chain never build-depends on protos. The pipeline:

```
pinned ottochain-sdk release
        │  (off-chain genesis-prep tool)
        ▼
  List[PackageSpec]   ──GenesisBuilder.withPackages──▶  DataState
        │                                                   │ (GenesisData codec)
        ▼                                                   ▼
   genesis.json  ◀───────────────────────────────────  serialize
        │
        ▼  (HOCON: genesis path)
  ML0Service.genesis  ── load + parse, else empty ──▶  boot from non-empty registry
```

Concretely:

- **`GenesisData(onChain, calculated)`** — a small `@derive`d case class wrapping the two halves so a full
  genesis `DataState` round-trips through JSON (the parts already have codecs; `DataState` itself does
  not). The off-chain tool emits this; the node loads it.
- **`ML0Service.genesis`** loads the configured genesis file *once at bootstrap* (effectful, inside the
  service `Resource`), parses `GenesisData`, and returns it — falling back to the empty genesis when no
  path is configured. (Reading happens in the Resource, not in the pure `genesis` accessor.)
- **genesis-prep tool** (off-chain, separate from consensus): reads a pinned SDK release, computes
  `schemaHash`/`logicHash`/`schemaShape` per std app, emits `genesis.json`. Reproducible: same release ⇒
  byte-identical genesis, so every operator boots the same pre-state.

Std packages own the reserved namespace (`std.*` / the reserved-labels set), so genesis is also where the
"these are the blessed, conformance-verified apps" trust root is planted — see
[trust-and-verification-handoff.md](trust-and-verification-handoff.md).

---

## 3. Engineering genesis states for e2e (#40) — yes, possible

Same seam, no new mechanism. An e2e harness supplies a `genesis.json` describing **any** desired
pre-state, and the metagraph boots from it. That lets a test start from a precise mid-life situation
rather than driving dozens of txs to get there:

- a package already at `v2` with `v1` `Deprecated` and `v0` `Yanked` → test version resolution / status
  gates;
- a fiber bound to `v1` of a package that now has a `v2` → test `UpgradeFiber` migration from a known
  state;
- reserved/std names occupied → test reservation rejection;
- reverse-name records present → test audit rendering;
- a deliberately *inconsistent* commit (only via hand-built `DataState`, bypassing the builder) → test
  the chain's reaction to a bad genesis.

What's needed beyond today: extend `GenesisBuilder` with **alias specs** and **fiber specs** (a fiber
record in a chosen state + its `reverseNames`) so arbitrary pre-states are expressible from data, plus a
`test-shared` helper for ergonomics. The package case already works in-process today.

> Note: this is the metagraph DataApplication genesis (the `std.*`/fiber pre-state), distinct from the
> base-currency `genesis.snapshot`/balances. Same idea (authoritative initial state), different layer.

---

## 4. `engineVersion` — the binary's capability epoch (#41)

A package's executability depends on what the **node binary** can do: which JSON-Logic operators exist,
which `SchemaShape` features are understood, which registry features are wired. `engineVersion` makes
that an explicit, monotonic integer the binary advertises — its capability epoch.

**It is a function of ordinal.** A binary upgrade that adds operators bumps `engineVersion`, but only
*activates* at a governance-signaled ordinal/epoch (a coordinated fork point), so for any ordinal every
honest node agrees on the active `engineVersion`:

```
engineVersionAt(ordinal): Int     // step schedule; constant for a single-binary env
```

**The dependency chain** (each ≥ the next):

```
binary.engineVersion  ≥  sdk-release.targetEngineVersion  ≥  package.minEngineVersion  ≥  what an instance uses
```

`PublishVersion` carries `minEngineVersion`. The chain **rejects publishing** a package whose
`minEngineVersion > engineVersionAt(currentOrdinal)` — so you can never register a package the running
fleet can't execute. Existing instances keep working across a bump because their package pinned a lower
`minEngineVersion`; nothing forces them forward.

This is a publish-time *liveness/safety gate*, not a consensus variable — it prevents un-executable code
from entering the registry, the same spirit as the opt-in conformance gate.

---

## 5. Upgrade path + implementation

**Upgrade sequence (a soft, coordinated fork):**

1. Ship a new binary with a higher `engineVersion` and an **activation ordinal** (in HOCON / genesis
   schedule). Until that ordinal, the new binary *reports* the old `engineVersion`.
2. Operators upgrade binaries **before** the activation ordinal (the normal rollout window).
3. At the activation ordinal, `engineVersionAt` steps up across the fleet simultaneously.
4. Packages requiring the new engine can now be published; a new SDK release targets the new version.
5. No state migration: old instances/packages are unaffected (their `minEngineVersion` is lower).

**Implementation sketch (deferred — first env is implicit v1):**

- `engineVersion: Int` + an `engineVersionAt(ordinal): Int` schedule sourced from HOCON
  (`{ engine.activations = [ {atOrdinal, version}, ... ] }`); constant `1` when absent.
- `PublishVersion.minEngineVersion: Int = 1` (new field; greenfield, no back-compat needed).
- One gate in `RegistryRules` (L0, ordinal-aware): reject when `minEngineVersion > engineVersionAt(ord)`,
  with an `EngineTooOld` error — mirrors the existing reserved/conformance rejection arms.
- SDK releases record their `targetEngineVersion`; the genesis-prep tool refuses to bake a std set whose
  target exceeds the binary's `engineVersion` (catches the mismatch before boot).

**First environment:** `engineVersion` is implicitly `1`, no activation schedule, no `minEngineVersion`
gating wired. The fields/seam go in (cheap, reserve the shape), the *machinery* lands when a second engine
version actually exists — no speculative fork plumbing before there's a fork to plumb.

---

## 6. Built vs deferred

| Piece | State |
|---|---|
| `GenesisBuilder.withPackages` (packages → consistent genesis) | **landed** `7093b8b` |
| `GenesisData` codec + `ML0Service.genesis` file-load | deferred (#39) |
| Off-chain genesis-prep tool (SDK release → genesis.json) | deferred (#39) |
| `GenesisBuilder` alias/fiber specs + e2e helper | deferred (#40) |
| `engineVersion` field + `engineVersionAt` + publish gate | deferred (#41), first env implicit v1 |

See also: [versionable-contracts.md](versionable-contracts.md),
[strong-typing-and-conformance.md](strong-typing-and-conformance.md),
[economics-and-state-rent.md](economics-and-state-rent.md) (registration cost couples to genesis).
