# OnChain incrementals — per-batch deltas + server-side recreation

**Status:** draft / RFC. Date: 2026-07-11. Branch: `feat/onchain-incrementals`.
**Goal:** remove the unbounded cumulative maps from the `OnChain` wire format so the metagraph
cannot hit the tessellation 512KB state-channel snapshot cap, while preserving the DL1
structural gate and every existing verification property. BREAKING wire change — ships in the
0.8.0 window alongside the SDK alignment sequence.

Reads with: `committed-state-migration.md` (the committed MPT/SMT substrate this builds on),
`sharded-ml0-and-commitments.md` §2 (the "OnChain = commitments, CalculatedState = how to
rebuild them" framing this realizes), `asset-model.md` §6 (why the L1 fast-path commit exists),
`economics-and-state-rent.md` (the orthogonal node-storage answer).

---

## 1. The forcing constraint (verified, measured)

tessellation caps the **whole state-channel snapshot binary** at
`max-state-channel-snapshot-binary-size-in-bytes = 512000`
(node-shared `application.conf`, develop:52 + release/mainnet:39). Enforcement is in
`CurrencySnapshotCreator.createProposalArtifact`:

```
maxArtifactSize = 512000 − facilitators × singleSignatureSizeInBytes
artifactSize    = |serialize(CurrencyIncrementalSnapshot)|   // includes dataApplication.onChainState
```

When a proposal exceeds the budget, `currencyEventsCutter.cut` drops **events** (data blocks,
messages) and retries. But the cumulative `OnChain` maps are not events — they ride in
`dataApplicationPart.onChainState` verbatim in **every** incremental. Once
`fiberCommits + assetCommits + registryCommits` alone exceed the budget, cutting can never
converge and the round raises `UnableToReduceProposalByCutting`: **the metagraph can never
produce another snapshot. Permanent halt, no recovery path** (state can only grow; nothing is
ever pruned — archive is a status flip).

Measured marginal wire costs via the production path (`CommittedOnChain[OnChain].toBinary`,
pinned by `OnChainWireSizeSuite` in shared-data):

| Entry | Marginal cost | Ceiling (alone) |
|---|---|---|
| `FiberCommit` | 226 B | **~2,265 fibers** |
| `AssetCommit` | 157 B | ~3,261 assets |
| registry commit | 94 B | ~5,446 entries |

A modest mixed economy — 1,500 fibers + 750 assets + 50 registry entries — already serializes
to 461,676 B = **90.2% of the budget**. The ceiling binds long before any interesting scale.

## 2. What OnChain is actually for (consumption audit)

| Field | Growth | Consumers |
|---|---|---|
| `fiberCommits` | cumulative, never pruned | DL1 seq `<=` gate + parent-existence (`FiberRules.scala:196-233`); ML0 gate; `/v1/onchain` |
| `assetCommits` | cumulative − burns | DL1/ML0 asset structural gate (behavior bitmask + seq, `AssetRules.scala:39,54`) |
| `registryCommits` | cumulative | audit/lookup only — no validator reads it |
| `latestLogs` | **per-batch already** (cleared each combine, `ML0Service.scala:107`) | `/events`, `/invocations` routes; webhooks |

Two observations drive the design:

1. **Only the per-fiber/per-asset *triple* is load-bearing at L1** — `{recordHash,
   stateDataHash, seqNum}` and `{behavior, seq, recordHash, origin}`. The gate is
   batching-tolerant (`<=`), fails open for unknown ids (create case), and is a best-effort
   spam filter — the combiner remains the authoritative gate (invariant #2/#3).
2. **Since #164, the chain already commits all of CalculatedState** behind
   `sha256(mptRoot ++ catalogRoot)` = `calculatedStateProof` in the *signed* snapshot, with the
   constant-size breadcrumb `(ordinal, mptRoot, catalogRoot)` riding in the `CommittedOnChain`
   wrapper. There is a consensus-signed anchor available to verify *any* recreated state
   against. `sharded-ml0-and-commitments.md` §2 asked for "OnChain = succinct commitments";
   the commitment part already shipped — what remains is removing the flat cumulative maps.

## 3. Design

### 3.1 The move: cumulative maps become CalculatedState; OnChain carries the batch delta

**CalculatedState** (never wire-shipped — only its root is) gains the commit maps:

```scala
case class CalculatedState(
  stateMachines: SortedMap[UUID, StateMachineFiberRecord],
  scripts:       SortedMap[UUID, ScriptFiberRecord],
  registry:      SortedMap[RegistryName, RegistryEntry],
  reverseNames:  SortedMap[UUID, RegistryName],
  assets:        SortedMap[UUID, AssetRecord],
  usedNonces:    SortedMap[UUID, SortedSet[Long]],
  fiberCommits:    SortedMap[UUID, FiberCommit]     = SortedMap.empty,  // moved from OnChain
  assetCommits:    SortedMap[UUID, AssetCommit]     = SortedMap.empty,  // moved from OnChain
  registryCommits: SortedMap[RegistryName, Hash]    = SortedMap.empty   // moved from OnChain
)
```

`CommittedView[CalculatedState]` projects them as new namespaces so every commit triple is a
leaf under the consensus-signed `mptRoot`:

```
commit/f/<uuid>  ← FiberCommit        commit/a/<uuid>  ← AssetCommit
commit/r/<name>  ← registry Hash      (over-long names: commit/r/h/<sha256hex>, as registry/)
```

The triples cannot diverge from the records: the same `DataStateOps` write that updates the
record updates the commit map, in the same combine fold, from the same hash — the projection
just moves which struct holds it. (The triple is *not* derivable inside `CommittedView.entries`
because `entries` is pure/total and `recordHash` requires a `Hasher`; hence the explicit map.)

**OnChain v2** carries only what changed in this batch, plus the existing per-batch logs:

```scala
case class OnChain(
  touchedFiberCommits:    SortedMap[UUID, FiberCommit],      // this batch's writes (incl. creates)
  touchedAssetCommits:    SortedMap[UUID, AssetCommit],
  burnedAssets:           SortedSet[UUID],                   // removals can't be an upsert
  touchedRegistryCommits: SortedMap[RegistryName, Hash],
  latestLogs:             SortedMap[UUID, List[FiberLogEntry]] // unchanged (already per-batch)
) extends DataOnChainState
```

The `touched*` maps use the **same clear-then-accumulate mechanism `latestLogs` already uses**
(cleared at the top of `orderedCombiner`'s fold, repopulated by `DataStateOps`) — a proven
pattern in this codebase, deterministic under ML0's re-combine-until-GL0-finalizes behavior.

**Resulting size property:** snapshot bytes become O(churn per batch), not O(total state).
Churn *is* events — so when a batch is too large, the framework's events-cutter now genuinely
converges by deferring data blocks: the hard-halt failure mode becomes graceful backpressure.

### 3.2 ML0: no new machinery

`validateSignedUpdate` already receives `DataState[OnChain, CalculatedState]`. The fiber/asset
gates (`FiberValidator`, `AssetValidator.L1Validator`) switch from `current.onChain.*Commits`
to `current.calculated.*Commits` — the identical triple, same freshness (both are the previous
committed state folded forward). This does NOT touch invariant #3: the TOCTOU rule bars
*registry lineage* reads (`lineageOf`/`refResolvesAndMatches`/`versionAppendable`) in the block
gate; the commit maps are the same L1-safe subset as today, merely relocated. No registry
lineage is consulted.

### 3.3 DL1: `CommitIndexService` (fold + heal)

DL1 cannot see CalculatedState — today it decodes the full cumulative OnChain from the latest
snapshot per ordinal (`Validator.withOnChainCache:189-221`). v2 replaces that with a node-local
index:

```scala
trait CommitIndexService[F[_]] {
  def current: F[CommitIndex]                    // fiberCommits + assetCommits at some ordinal
  def advance(ord: SnapshotOrdinal, onChain: OnChain): F[Unit]  // fold touched* + burns
  def heal(target: SnapshotOrdinal): F[Unit]     // full re-seed, verified against breadcrumb
}
```

- **Common path (no extra HTTP):** on each new snapshot, if `snapshot.ordinal ==
  index.ordinal + 1`, fold `touched*` upserts and `burnedAssets` removals into the index.
  The decoded snapshot already contains everything needed.
- **Heal path (restart / skipped ordinal / join):** fetch the full commit index from ML0 and
  verify it against the **signed** breadcrumb before adopting:
  1. `GET /v1/commit-index?proof=true` on ML0 → `{ ordinal, roots, entries, batchProof }`
     where `entries` is the full `commit/` namespace slice (values included) and `batchProof`
     is the `MerklePatriciaPrefixProver` batch proof over the slice.
  2. Verify with `MerklePatriciaBatchInclusionVerifier` against `breadcrumb.mptRoot` from the
     latest signed snapshot: (a) every entry's re-hashed value digest matches its leaf, and
     (b) **completeness** — the proof covers the entire `commit/` subtree, no omitted keys.
  3. Adopt as the index at `ordinal`; resume folding.
- **Gate semantics preserved:** the folded index at the latest contiguous ordinal is
  content-identical to today's latest-snapshot cumulative map. Staleness behaves exactly like
  today's per-ordinal cache staleness (the `<=` gate is batching-tolerant by design).

**Completeness is security-load-bearing.** `sequenceNumberMatches` **fails open** for unknown
ids (`state.fiberCommits.get(id).fold(valid)(...)` — the create case). An index missing
entries silently accepts what it should filter. Hence: heal MUST verify subtree completeness,
and `advance` MUST refuse non-contiguous ordinals (heal instead) — never fold onto a gap.

### 3.4 Routes / SDK surface

- `GET /v1/onchain` — serves the v2 (delta) shape on both ML0 and DL1. Consumers that want the
  full picture use:
- `GET /v1/commit-index` (new, ML0 + DL1) — the recreated full maps
  (`fiberCommits`/`assetCommits`/`registryCommits` as today's shapes); `?proof=true` on ML0
  adds the batch proof for trustless healing. This is the back-compat surface for the SDK/e2e
  asserts that previously read `/v1/onchain`.
- Registry lookup stays on the committed-state routes (the registry is already in the MPT);
  `registryCommits` is retained in CalculatedState for audit parity, not consulted by any gate.
- Genesis: `GenesisBuilder` seeds the CalculatedState commit maps and emits the seed entries as
  the genesis snapshot's `touched*` (so a from-genesis replayer folds correctly from ordinal 0).

### 3.5 Rejected alternatives

- **(a) Full `CommittedReplica` on DL1** (metakit rc.7, root-checked `fromSnapshot` +
  `applyDelta`): works today with zero chain-side changes, but replicates the *entire*
  CalculatedState trie (records, scripts, registry lineages) onto every DL1 — O(total state)
  memory/transfer for nodes that need only the commit triples. Kept as the fallback if the
  commit-namespace slice proves awkward; the `StateDelta` machinery is also the natural
  transport if DL1 ever needs more than the triples.
- **(b) Compress `onChainState`** (gzip/binary encoding): buys a ~3-5× constant factor against
  a *linear* growth curve — delays the halt to ~10k fibers, solves nothing. Viable only as an
  emergency stopgap on a live network approaching the cap.
- **(c) Prune/rent instead of restructure:** state rent (`economics-and-state-rent.md`) bounds
  *node storage* economically but cannot bound the *wire format* — even rent-solvent state
  exceeds 512KB at trivial scale. Orthogonal; both are eventually needed.
- **(d) OnChain = roots only** (pure `sharded-ml0` §2 shape): maximally succinct, but then the
  DL1 common path needs an HTTP fetch + proof verification *per ordinal* (it can no longer
  fold deltas it already has). The touched-delta shape keeps the common path free and matches
  the shard seam — per-shard `touched*` partitions are exactly what a `ShardResult` carries
  later (§6 of that doc).

## 4. Reconciliation with sharded-ml0-and-commitments.md §2

That design predates #164. Its components land as follows: `metagraphStateRoot` → **shipped**
as the committed `mptRoot` (+ catalog) anchored via `calculatedStateProof`;
`packageRegistryRoot` → subsumed by the `registry/` namespace under the same root; "OnChain =
succinct commitments" → realized as breadcrumb roots + per-batch deltas (this RFC);
`shardRoots`/`commitmentStore` → still future, and this RFC keeps the seam: `touched*` maps
keyed by UUID partition by `shardIdFor` naturally, and the `commit/` namespace can split into
per-shard sub-namespaces without another wire break.

## 5. Migration (0.8.0 boundary)

No production mainnet exists; testnets redeploy from genesis. Two supported paths:

1. **Genesis redeploy (default):** 0.8.0 nodes start from a genesis whose CalculatedState
   carries the commit maps (per §3.4). No decoder for the old shape is kept.
2. **One-shot migration (if a network must carry state across):** at the upgrade ordinal,
   decode the final v1 `OnChain`, move `fiberCommits`/`assetCommits`/`registryCommits` into
   the CalculatedState seed, emit them as the first v2 snapshot's `touched*`. Gated behind a
   startup flag; deleted after the window.

SDK sequencing rides the existing 0.8.0 alignment train (`project_ottochain_sdk_chain_alignment`):
chain types → ottochain-sdk decoders (`/v1/onchain` v2 + `/v1/commit-index` client) → e2e.
Note: the metakit-sdk committed light-client port (`committed-roots.ts`,
`mpt_verify`/`mpt_prefix_verify`) exists on its `dev` branch only — client-side slice
verification needs that release, but is not on the critical path (server-side healing verifies
with metakit Scala classes).

## 6. What this does NOT change

- Signing canonicals: `OnChain` is server-derived state, not a client-signed message —
  invariant #1 untriggered (golden serde tests are still added for the new wire shape).
- The combiner's authority: all stateful rejection stays `CombineRejected` → `RejectionReceipt`.
- The committed-state machinery: same `CommittedApp.makeL0`, same breadcrumb, same proofs; the
  MPT gains the `commit/` namespaces (leaf count grows ~2×, small leaves). The known
  ~3-full-rebuilds-per-ordinal committed-layer cost is unchanged here and tracked as a separate
  metakit workstream (thread `prev.trie` through `hashFor`/`advanceWork`; make the
  `RootDivergence` full-rebuild assert a `CommittedConfig` flag).
- `latestLogs` semantics, webhooks, `/events`/`/invocations`.

## 7. Verification plan

- `OnChainWireSizeSuite` (already landed): replace the ceiling test with an O(churn)
  assertion — v2 wire size at N cumulative entities is flat in N, linear only in batch churn.
- Golden serde tests for OnChain v2 + the commit-map CalculatedState (round-trip + fixed
  fixtures, per the derive-codecs/golden-fieldnames rule).
- `CommittedViewSuite`: `commit/` namespace projection totality (incl. over-long registry
  names) and count arithmetic.
- DL1 integration: kill/restart mid-stream → heal → identical index vs a never-restarted node;
  skipped-ordinal injection → refuse-fold + heal; tampered slice (omitted key, altered value)
  → heal rejects on completeness/digest.
- Seq-gate property tests unchanged and green (`FiberRules`/`AssetRules`).
- Full riverdale economy e2e (10/10) green on the v2 wire format.
- End-to-end cap demonstration: seed a cluster past the v1 ceiling (>2,265 fibers), show v2
  keeps snapshotting (the v1 format provably could not).

## 8. Implementation phases (chain repo)

1. **Schema:** OnChain v2 + CalculatedState commit maps + `CommittedView` `commit/` namespaces
   + golden tests. (models, shared-data)
2. **Combine plumbing:** `DataStateOps` writes both the CalculatedState map and the `touched*`
   delta; `orderedCombiner` clears `touched*` like `latestLogs`; genesis seeding. (shared-data, l0)
3. **Gates:** ML0 validators read `current.calculated.*Commits`; DL1 `CommitIndexService`
   (fold + heal + completeness verification) replacing `withOnChainCache`. (shared-data, data_l1)
4. **Routes:** `/v1/onchain` v2, `/v1/commit-index` (+`?proof=true` on ML0). (l0, data_l1)
5. **Tests per §7**, then SDK/e2e alignment (Phase 3 of the workstream).
