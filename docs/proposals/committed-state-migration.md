# Committed-State Migration: a verifiable calculated-state root, rooted into the currency snapshot

**Status:** Plan of record (Phase 0 in progress)
**Supersedes / unifies:** PR #158 (`feat: committed-state adapter`, Approach B) and PR #117
(`feat: metagraph phase 1 — per-fiber stateRoot`, Approach A). Both are closed into this one
workstream.
**Decisions taken (2026-06-14):** adopt **B (metakit committed-state) and salvage A's
field-level proof endpoint**; journal strategy = **LevelDB persistence + upstream the metakit
required-journal fix**.

---

## 1. The reframe: tessellation already roots our calculated-state hash

The previous attempt was framed around "we cannot get a verifiable state root into the currency
snapshot until we fork tessellation." That premise is **false**. The framework already roots
whatever `hashCalculatedState` returns:

- `CurrencyIncrementalSnapshot.dataApplication: Option[DataApplicationPart]`
  (`currency.scala:232`), and `DataApplicationPart.calculatedStateProof: Hash`
  (`currency.scala:191`) is populated **verbatim** from the metagraph's
  `hashCalculatedState(state): F[Hash]`.
- That field lives inside the `Signed[CurrencyIncrementalSnapshot]`, so **consensus signs it**,
  and the framework re-verifies it on accept (`CalculatedStateHashDoesNotMatchMajority`) and on
  replay (`DataApplicationTraverse`: "Calculated state proof mismatch at ordinal=…").
- It is transitively committed into the **global GL0 state proof** via the Merkle tree over
  currency snapshots (`GlobalSnapshotStateProof.lastCurrencySnapshotsProof`).

The framework treats those 32 bytes as opaque — it does not care whether they are a flat digest
or a Merkle/MPT root. **Therefore the groundwork is simply: make `hashCalculatedState` return a
structured root instead of a flat hash.** That is exactly what metakit's `CommittedApp.makeL0`
does (`hashCalculatedState → sha256(mptRoot ++ catalogRoot)`), and it needs **zero tessellation
changes** to land in the signed snapshot.

The tessellation update we had in mind is real, but it is **Phase 3 and optional**: promoting that
opaque `Hash` to a *typed* `Option[MerkleRoot]` field on `DataApplicationPart`. That is an
additive, omit-safe `Option` field (same pattern as the existing `updateHashes: Option[...] =
None`), backward-compatible with V1 decoders. Because Phase 1 freezes the leaf layout, Phase 3 is
a field-add, not a data migration.

## 2. What actually killed PR #158, and what has changed since

Two things — both now resolvable:

1. **The journal stall (the real roadblock).** `CommittedApp.makeL0(journal:
   Option[CatalogJournal[F]] = None)`. With `None`, any node that *seeds* (restart, or
   download-to-join) lands in a `SeededCatalog` (root known, contents absent); the next `combine`
   calls `advanceWork → resolveCatalog → BreadcrumbUnresolvable` and the node cannot participate →
   the metagraph stalls. The journal is **load-bearing, not optional**. This is consensus-critical,
   not a restart nicety: an unhydrated node that *guessed* a root would poison the snapshot via the
   verify path in §1.
2. **The rc.2 dependency.** The "blocked on untagged rc.2" note is **stale**: `v1.8.0-rc.2` is
   tagged, contains the full `committed` module (byte-identical to dev HEAD `bb45039`), and **main
   is already on rc.2** (merged via #163). No dependency blocker remains.

**New complication:** main moved hard since #158's base. **#154** added `registry` + `reverseNames`
to `CalculatedState` (it is now a 4-field case class — `stateMachines`, `scripts`, `registry`,
`reverseNames`), and **#163** rewrote `ML0Service` and collapsed routes into `ML0Routes` +
`handlers/`. So #158 cannot be rebased mechanically — it is a *re-application* of its durable
pieces onto post-#163 main.

## 3. Approach A vs B (decision: B, salvage A's endpoint)

| | **A — #117 hand-rolled** | **B — #158 metakit committed** |
|---|---|---|
| Root location | off-chain `CalculatedState.metagraphStateRoot` only; **stripped before the canonical hash** → never anchored | metakit `combinedHash` → `calculatedStateProof` in the **signed** snapshot + global proof |
| Structure | single-tier MPT, `MerklePatriciaProducer.stateless` rebuilt every round | two-tier: MPT state-dict + SMT epoch catalog (history), correct-by-construction |
| Client value today | **field-level inclusion proofs** (`GET …/state-proof?field=`) | committed root + `/committed/...` routes; no field-granular endpoint yet |
| Drift risk | trie/hex logic duplicated 3× (combiner, service, route) | one `CommittedView` is the single source |
| Fit for the rooting goal | dead-end (root not in snapshot) | the foundation |

**Decision:** adopt **B** as the structural foundation and **re-home A's field-level proof
endpoint** so it serves inclusion proofs against the root that consensus actually signs — closing
A's core defect. Drop A's `metagraphStateRoot`-on-`CalculatedState` + strip-before-hash mechanism
entirely.

## 4. Migration phases

### Phase 0 — Kill the journal stall first (de-risk before touching consensus)

The journal is the thing that stalled #158, and `hashCalculatedState` determinism across nodes is
now consensus-critical, so this comes first and stands alone.

- **Upstream the metakit smell-fix.** Change `CommittedApp.makeL0` and `CommittedState.make` so
  `journal` is **required-in-shape** with an explicit `CatalogJournal.inMemory` opt-out — no silent
  `journal = None` default. This removes the "forgot to pass a journal → silent stall" footgun for
  every adopter. Cut a metakit rc (rc.3) and bump ottochain's pin.
- **Strengthen metakit bootstrap tests** to prove the three hydration paths with both `inMemory`
  and `levelDb` journals: (a) genesis-from-scratch (`LiveCatalog` at ordinal 0), (b) contiguous
  step on a hydrated cell (`transition`), (c) restart / download-seed (`seed` →
  `journalCatalogMatching` recomposes to the attested root → immediate `LiveCatalog`).
- **Prove it in a local 3-node cluster** before wiring the real combiner: wire
  `CatalogJournal.levelDb`, restart a node, confirm no `BreadcrumbUnresolvable` and ordinals keep
  advancing.

### Phase 1 — Adopt the committed root as `hashCalculatedState` (no tessellation change). This *is* the rooting.

- **Write one `CommittedView[CalculatedState]`** projecting **all four** fields into lowercase,
  slash-namespaced `CommitKey`s:
  - `fiber/<uuid>` ← `stateMachines`
  - `script/<uuid>` ← `scripts`
  - `registry/<name>` ← `registry`
  - `reverse/<uuid>` ← `reverseNames`

  CommitKey grammar: 1–16 segments, lowercase `[a-z0-9][a-z0-9._-]{0,63}` per segment, dots
  allowed *within* a segment — so `registry/order.package` is valid. **Verify no uppercase
  registry names**, else lowercase or hex-encode. (#158's view projected only 2 of 4 fields — the
  registry/reverseNames omission is now a correctness requirement, not a follow-up: those fields
  are part of the calculated state and must be in the committed root.)
- **Re-apply `makeL0` + `CatalogJournal` wiring** onto the post-#163 `ML0Service`/`ML0Routes`. The
  route split is compatible: `extraRoutes` receives the `CommittedReader`. `makeL0` owns serde,
  the `CommittedOnChain` wrapping, `combine`, `validateData`, `get/setCalculatedState`,
  `hashCalculatedState`, and the read routes; keep the existing combiner/validator as
  `CombinerService`/`ValidationService`.
- **Port the total `signedOrdering`** (signature tiebreak replacing `Ordering.by(_.value)`) — a
  strict improvement, independent of the committed work.
- **Wrap the two methods `makeL0` does not cover**: `onSnapshotConsensusResult` (webhook dispatch)
  and `setCalculatedState` (checkpoint cache) via a thin `withConsensusHooks` subclass — and flag
  an upstream metakit hook so the wrapper can be deleted later.
- **Outcome:** every currency incremental snapshot's `calculatedStateProof` is the two-tier
  committed root, consensus-signed and globally anchored. The breadcrumb (`CommittedRoots`)
  additionally rides in `onChainState`, so followers validate root transitions in `combine`. The
  rooting is done.

### Phase 2 — Serve element-level proofs (salvage A's client value on the B foundation)

Re-home #117's `GET /v1/state-machines/{id}/state-proof?field=` onto `ML0Routes`, computing
inclusion proofs from the committed MPT (`CommittedReader` / metakit's `/committed/...` routes)
rather than the three hand-rolled stateless tries. Clients get proofs against the root consensus
signed.

### Phase 3 — (Later, optional) typed root field in tessellation `DataApplicationPart`

Add `calculatedStateRoot: Option[MerkleRoot] = None` to `DataApplicationPart` (omit-safe,
V1-decode-compatible) and/or fold a `dataApplicationProof: Option[Hash]` into
`CurrencySnapshotStateProof`. Populate with the same root Phase 1 already computes. Pure field-add;
no recompute, no migration.

## 5. Risks / call-outs

- **OnChain wire-format change.** `makeL0` wraps `OnChain` in `CommittedOnChain[OnChain]`, so the
  serialized on-chain state shape changes. Every reader must unwrap `.inner`: the DL1 L1-validator
  path that reads `OnChain.fiberCommits` for sequence numbers (see CLAUDE.md L1 rules), the e2e
  harness, and the SDK. Acceptable pre-mainnet, but it needs a deliberate pass over the DL1
  `validateUpdate` path.
- **Determinism is consensus-critical.** `hashCalculatedState` must be byte-identical across nodes;
  Phase 0's journal correctness is what guarantees that. Hence Phase 0 first.
- **`CommittedReplica` (delta/snapshot follower verification) is not wired to HTTP yet** —
  metakit's own scaladoc calls it "the ottochain follow-up." Defer unless multi-node proof-serving
  is needed near-term.
- **`onSnapshotConsensusResult` is not implemented by `makeL0`** — carried by the `withConsensusHooks`
  wrapper until upstreamed.

## 6. Key references

- ottochain: `modules/models/.../schema/CalculatedState.scala` (4-field case class),
  `ML0Service.scala` / `ML0Routes.scala` / `handlers/` (#163 structure), `Updates.scala`
  (`signedOrdering`).
- metakit `lifecycle/committed/` (`v1.8.0-rc.2` == dev HEAD): `CommittedApp.makeL0`,
  `CommittedState.make` (`journal: Option[CatalogJournal[F]]` at `:50`; stall paths
  `BreadcrumbUnresolvable`, `CatalogNotHydrated`, `SeededCatalog`), `CommittedView`, `CommitKey`,
  `CatalogJournal` (`levelDb` / `inMemory`).
- tessellation: `currency.scala:191` (`DataApplicationPart`), `:232`
  (`CurrencyIncrementalSnapshot.dataApplication`), `DataApplicationSnapshotAcceptanceManager`
  (produces/verifies `calculatedStateProof`), `package.scala:243` (`hashCalculatedState`),
  `:840-859` (`L0/L1NodeContext.getLastCurrencySnapshot`).
