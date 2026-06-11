# Sharded ML0 + Succinct State Commitments — design (set-up, not switch-on)

**Status:** draft / design. Date: 2026-06-04. Branch: `feat/versionable-contracts`.
**Goal:** organize fiber-record state so the on-chain commitment is **succinct and proof-rich** (roots
that support inclusion proofs, cross-shard reads, and a zk/aggregate-signature substrate), and carve a
**shard-friendly ML0 sub-processor seam** so the metagraph can later shard *fiber execution* the way
Tessellation shards *metagraph execution* — **without changing today's behavior** (ML0 still processes
everything; `numShards = 1` is the degenerate, byte-identical path).

Reads with: `jlvm-engine-foundations.md` (effects-as-data + the FS2/KPN regime split — the prerequisite
that turns a cross-shard trigger into a *message*), `versionable-contracts.md` (content-addressed
definitions = leaves in the commitment tree), `docs/TOPOS-FIBER-CATEGORICAL-ASSESSMENT.md`.

> The nesting you flagged is real and fine: the hypergraph (Global L0) anchors **metagraph** snapshots;
> Tessellation shards *across metagraphs* inside gl0. OttoChain is **one** metagraph, so here we shard
> *one layer deeper* — across **fibers** inside ML0 — then roll up to a single metagraph snapshot that
> the hypergraph anchors exactly as today. Same pattern, one level down.

---

## 1. The proven pattern to mirror (Tessellation gl0, verified in code)

Tessellation already implements execution sharding, fully wired and gated behind `numShards=1`:

- **Keyspace partition** — `ShardAssignment.shardIdFor(addr) = hash(addr) mod numShards`
  (`node-shared/.../nakamoto/ShardAssignment.scala:54`), config `nakamoto.sharding.num-shards`
  (default 1; env `NAKAMOTO_NUM_SHARDS`).
- **Committee per shard** — `CommitteeSortition.isInShardCommittee` draws a committee via a VRF-VK hash
  `H("shard-committee", eta, shardId, epoch, vrfVk) < threshold(kTarget, σ)`, per eta-period
  (`CommitteeSortition.scala:175`); deterministic cluster-wide.
- **Per-shard checkpoint** — `ShardCheckpoint(shardId, parentCheckpointHash, shardOrdinal,
  gl0AnchorOrdinal, derivedStateDelta, emittedReceipts, committeeSignatures, epoch)`
  (`shared/.../sharding/ShardCheckpoint.scala:50`). The shard runs a *mini-chain*; its
  `ShardDerivedStateDelta.perMetagraphMptRoots: SortedMap[Address, Hash]` carries **per-subject roots**,
  and `committeeSignatures` is a `≥⌈2/3·K⌉` quorum.
- **Cross-shard = async proof-carrying receipts** — `CrossShardReceipt.MetagraphSyncDataWrite(sourceShard,
  …, targetShard, …, increment)` (`CrossShardReceipt.scala:28`): a shard that can't write another shard's
  subtree records *intent* as a receipt; gl0 routes it; the target shard consumes it next round. Receiver
  re-derives `shardIdFor` to verify routing.
- **Aggregation** — gl0 `verifyEmbedded` deterministically checks the committee quorum (no re-exec on the
  fast path), then `adoptShardCheckpoints` unions per-shard deltas and re-derives one global state; the
  GSI carries `shardCheckpoints: SortedMap[ShardId, ShardCheckpoint]`
  (`GlobalIncrementalSnapshot.scala:114`).
- **Commitment substrate** — `GlobalSnapshotStateProof` exposes `mptRoot` (Merkle-Patricia over the full
  state), ~20 **per-field subtree roots** (for targeted inclusion proofs), and a separate `smtRoot`
  (Sparse Merkle Tree over finalized ordinals, leaves = `PerOrdinalCommitment(hypergraphRoot,
  incrementalSnapshotHash, towerEligibility)`). All via one `Hasher[F]` (Blake2b over canonical bytes).
  BLS aggregate certs are **designed** (`docs/nakamoto/BLS-AGGREGATE-SIGNATURE-DESIGN.md`) but not yet in
  the tree.

**Lesson to carry forward:** intra-shard is deterministic + synchronous; the *only* async boundary is the
cross-shard receipt; aggregation is "verify committee quorum, union deltas, roll up roots." Also carry the
**safety bound**: sharding across committees needs honest-stake `α_total ≤ 1/(2S)` per shard or
honest-majority breaks (the cross-shard CQ-collapse result). At `S=1` (today) this is moot.

## 2. OnChain reorg — succinct commitments; CalculatedState holds the data + schema

Today: `OnChain = (fiberCommits: SortedMap[UUID, FiberCommit], latestLogs)` and `hashCalculatedState`
hashes the *whole* serialized `CalculatedState`. That's flat — no inclusion proofs, no per-shard rollup.

**Target split (your framing — OnChain = commitments, CalculatedState = how to rebuild them):**

```
OnChain (succinct, anchored to hypergraph):
  shardRoots:          SortedMap[ShardId, Hash]   // per-shard SMT/MPT root over that shard's fiber commits
  metagraphStateRoot:  Hash                        // top tree over shardRoots (the value pushed up)
  packageRegistryRoot: Hash                        // content-addressed definitions/versions (versionable-contracts)
  aggregateCert:       Option[BlsAggregateSig]     // optional: committee/aggregate sig over metagraphStateRoot
  // (logs/receipts commitments as today)

CalculatedState (the data + schema to reconstruct the above):
  stateMachines / scripts:  SortedMap[UUID, …Record]   // the fibers (as today)
  commitmentStore:          per-shard SMT/MPT node store (or content-addressed node refs)
  packageRegistry:          ContractPackage map (definitions = content-addressed leaves)
  relationshipIndex:        materialized fiber graph (for cross-shard routing + cycle pre-validation)
```

Each fiber's leaf = `FiberCommit(recordHash, stateDataHash, sequenceNumber)` keyed by `UUID` inside its
shard's tree. Mirror Tessellation's **per-field/per-subject subtree roots** so a light client can prove
"fiber X has stateDataHash = h at ordinal N" with an O(log n) path to `shardRoot` to `metagraphStateRoot`
to the hypergraph anchor. This is the **zk/aggregate-ready substrate**: SMT inclusion + a BLS aggregate over
`metagraphStateRoot` gives succinct verification and cross-shard reads-with-proof; it's also SNARK-friendly
(fixed-shape Merkle paths). Reuse the metagraph's `Hasher[F]` (Blake2b, canonical bytes) for determinism.

> Note: ottochain has a live `state-commitment-mpt` proposal + auth-trie branches
> (`feat/metagraph-phase1-state-roots`, `docs/authenticated-trie-integration-spec`). This reorg should
> *land on top of / reconcile with* that work, not duplicate it — those provide the trie; this adds the
> per-shard partitioning + rollup + cert.

## 3. The ShardProcessor seam (the "L1-pattern sub-processor")

Factor the combine so a shard is an interface, with `S=1` = today inline:

```scala
final case class ShardId(value: Int)                 // keyspace partition of fiber UUIDs
def shardIdFor(fiberId: UUID, numShards: Int): ShardId   // = hash(fiberId) mod numShards

trait ShardProcessor[F[_]] {
  def process(
    shardId:      ShardId,
    updates:      List[Signed[OttochainMessage]],   // only those whose fiberId is in this shard
    inbound:      List[CrossShardMessage],          // proof-carrying messages addressed to this shard
    priorState:   ShardState
  ): F[ShardResult]
}

final case class ShardResult(
  newState:   ShardState,            // updated fibers in this shard
  shardRoot:  Hash,                  // SMT/MPT root over this shard's fiber commits
  outbound:   List[CrossShardMessage],   // triggers/oracle-calls/spawns targeting OTHER shards
  logs:       List[FiberLogEntry]
)

trait ShardAggregator[F[_]] {        // ML0 role: fold shard results into the metagraph snapshot
  def aggregate(results: SortedMap[ShardId, ShardResult]): F[(OnChain, CalculatedState)]
}
```

**`S=1` degenerate path = today's `Combiner`:** one shard owns all fibers; `outbound` is always empty;
`aggregate` is identity over a single shard; `metagraphStateRoot = shardRoot(0)`. Byte-identical to current
behavior — the seam is pure refactor until `numShards > 1`. (Exactly Tessellation's regression bar: all
sharding wiring no-ops at `numShards ≤ 1`.)

## 4. Cross-shard triggers as proof-carrying messages (where effects-as-data pays off)

This is why the engine refactor comes first. With **effects-as-data**, a fiber's effect yields a typed
`FiberEffect` (Trigger/OracleCall/Spawn/Emit) — a *value*, not a function call. When the target fiber lives
in another shard, that value becomes a **`CrossShardMessage`** carrying the payload **plus an inclusion
proof** that the emitting fiber, in the source shard at ordinal N, produced it (proof against
`shardRoot(source)`). The target shard admits it next round and verifies the proof against the committed
source root — exactly Tessellation's `CrossShardReceipt`, but at fiber granularity.

```scala
final case class CrossShardMessage(
  sourceShard: ShardId, targetShard: ShardId,
  sourceFiberId: UUID, targetFiberId: UUID,
  effect: FiberEffect,                  // the effect-as-data (Trigger/OracleCall/Spawn)
  sourceRoot: Hash, inclusionProof: MerkleProof   // verifiable emission
)
```

Intra-shard triggers stay synchronous (today's cascade). Only **cross-shard** triggers cross the async
boundary. This keeps atomicity per-shard-per-round and makes the cross-shard relation explicit data.

## 5. Theory backbone (why this is sound, and what it licenses)

- **π-calculus** models the topology exactly: shards are processes with private name spaces (fiber UUIDs);
  cross-shard triggers are messages on channels; **spawns are fresh-name creation**. Intra-shard =
  synchronous reduction; cross-shard = async message passing — the single communication primitive.
- **Kahn Process Networks (KPN)** give the determinacy guarantee that *licenses parallel shards*: a network
  of sequential deterministic processes communicating over **FIFO channels** produces the same result
  regardless of scheduling. So if (a) each `ShardProcessor.process` is deterministic and (b) cross-shard
  channels are FIFO + ordered (canonical order by `(sourceShard, sourceFiberId, seq)`), the aggregated
  metagraph snapshot is **determinate across nodes** even when shards execute in parallel. KPN is the
  theorem that makes sharding safe for consensus.
- **Synchronous-reaction (Esterel/Lustre)** frames the per-snapshot tick: one deterministic reaction per
  `SnapshotOrdinal`; cross-shard messages emitted in tick N are consumed in tick N+1 (or a bounded
  fixpoint within N) — never within the same instant, which is what keeps reactions well-defined.
- **Determinism invariants** (from the foundations doc, now load-bearing): no wall-clock/random
  (randomness from `lastSnapshotHash`); ordered traversal everywhere consensus-visible (no `Set`
  iteration); FIFO cross-shard channels; canonical hashing.

## 6. Phasing (each step additive; behavior unchanged until the last)

1. **Seam, `S=1`** — introduce `ShardId`/`shardIdFor`/`ShardProcessor`/`ShardResult`/`ShardAggregator`;
   route today's `Combiner` through a single shard. No behavior change. (Pairs with effects-as-data so
   `FiberEffect` exists.)
2. **Commitment reorg, `S=1`** — `OnChain` becomes `shardRoots`+`metagraphStateRoot` over a per-shard
   SMT/MPT (reconcile with the auth-trie work); add inclusion-proof queries. Still one shard.
3. **Multi-shard execution** — `numShards>1`: partition updates by `shardIdFor(fiberId)`; intra-shard
   cascades synchronous; cross-shard triggers become `CrossShardMessage`s consumed next round (KPN/FIFO).
4. **Committees + aggregate cert** — per-shard committee sortition (mirror `CommitteeSortition`), `≥⌈2/3⌉`
   quorum per shard, BLS aggregate over `metagraphStateRoot` for succinct/zk verification. Inherit the
   `α ≤ 1/(2S)` safety bound + (later) non-participation slashing.
5. **Hypergraph anchoring** — push `metagraphStateRoot` (+ optional aggregate cert) as the metagraph
   snapshot's calculated-state hash, exactly as today — the hypergraph is oblivious to internal sharding.

## 7. Open decisions

- **Shard key** — `hash(fiberId) mod S` (simple, balanced) vs grouping related fibers (parent+children,
  frequent trigger partners) into the same shard to minimize cross-shard traffic? The relationship index
  (§2) enables affinity-based assignment later; start with hash-mod-S.
- **Cross-shard cascade latency** — next-round delivery (simple, +1 ordinal latency per hop) vs bounded
  intra-round fixpoint (lower latency, more complex determinism). Start next-round.
- **Reconcile with auth-trie branches** before building the commitment store (§2 note).
- **Keep `S=1` as the only production mode** until committees + the safety bound + slashing are real;
  ship the seam + commitment reorg first (both safe at `S=1`).
