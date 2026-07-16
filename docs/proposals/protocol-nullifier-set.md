# Protocol Nullifier Set — design of record

Status: **ACTIVE — in implementation** (2026-07-16). Supersedes the nullifier-set portion of
`asset-shielded-mode.md` §3.3 (that RFC's `ShieldedSpend`/viewing-keys remain future work and
will consume this subsystem). Maintainer decisions herein are final for v1; pre-launch, so no
state-migration constraints apply.

## 1. Decisions (and the reasoning that settled them)

1. **Unbounded is accepted.** The classic "nullifier sets grow forever" objection targets the
   wrong layer here: post-rc.7 the committed MPT updates incrementally (per-touched-key cost),
   and post-#210 only per-batch `OnChain` deltas ride the wire. Disk is ~100 B/entry. The two
   real unbounded costs are managed instead of designed around:
   - the JVM working copy (`CalculatedState` is in-memory): mitigated by **excluding the set
     from `/v1/checkpoint`** (dedicated routes instead) — the only whole-state JSON
     serialization path;
   - monotonic growth (a nullifier can never be pruned — pruning re-enables a double-spend):
     the `nullifier/<domain>/<nf>` key layout leaves room to retrofit epoch partitioning
     later without changing the light-client story. **Revisit trigger:** sustained heap
     pressure from the map or ≳10M entries.
2. **No audit gate on building.** External audit of the verifier stack is a *pre-mainnet-value*
   gate, not a pre-implementation gate. (`asset-shielded-mode.md` updated accordingly.)
3. **Domain = the consuming fiber's own id, always (v1).** `nullifier/<fiberId>/<nf>`. This
   eliminates the cross-app griefing vector (nobody can insert into another app's namespace)
   without needing an authorization design. Cross-fiber shared domains are follow-on work.
4. **Consumption is an effect token, not a message or opcode.** `_consumeNullifier` joins
   `_spawn/_transferAsset/_emit/_addDependency/...` in the existing `FiberDirective`/
   `EffectKind` architecture: the transition's *effect* emits it (array form), the extractor
   scrapes it from the AUTHORED AST only (injection-immune for free), and the combiner
   enforces it. Zero VM/JLVM changes; any app (mixer, shieldApp, riverdale-health) opts in by
   emitting the token.
5. **Combiner-only enforcement (CLAUDE.md rule #2/#3).** All nullifiers in a transition are
   checked absent and inserted atomically; any hit ⇒ graceful `CombineRejected("nullifier
   already consumed …")` ⇒ `RejectionReceipt`. The check must NEVER appear in
   `validateSignedUpdate` (stateful read at block validity = TOCTOU block poisoning).
   Sequential combine gives intra-batch double-spend protection for free.
6. **No `OnChain` / `CommitIndex` changes.** DL1 never reads nullifiers (stateful checks are
   combiner-only), so the #210 delta shape and the L1 fast-path are untouched. The set lives
   ONLY in `CalculatedState` + the committed projection.
7. **Value = spend ordinal.** `nullifiers: SortedMap[UUID, SortedMap[Hash, SnapshotOrdinal]]`
   — a spent-at receipt for near-zero cost; the committed leaf value is the ordinal JSON.
8. **Light-client surface, two phases.**
   - Phase A (this repo only): `GET /v1/nullifiers/{domain}/{nf}` (spent? + ordinal) and
     **presence** proofs via the existing state-proof machinery (`CommitKey`
     `nullifier/<domain>/<nf>` fits: 111 chars, 3 segments, charset-valid).
   - Phase B (one metakit change): **MPT absence proofs**. Verified finding: the MPT layer is
     inclusion-only today (`MerklePatriciaProver.attestPath` errors `PathNotFound` on a
     missing key; the verifier fold must terminate in a Leaf; `mpt_prefix_verify` can prove
     absence only by O(domain) complete enumeration and cannot represent an empty domain).
     Fix mirrors the SMT package, which already models absence first-class
     (`SparseMerkleProof.{Inclusion,Absence}`, `AbsenceWitness.{Default,OtherLeaf}`): add an
     `Absence` MPT proof variant whose witness chain terminates in branch-missing-nibble /
     divergent-extension / other-leaf, a prover arm where `PathNotFound` is raised today, a
     verifier arm reusing the existing fold, and `Committed.proveKey` returning it. Then
     ottochain serves it and `@ottochain/sdk verifyStateProof` gains the absence arm. This is
     the one piece that needs a metakit release (1.8.0-rc.8).

## 2. What this buys the flagship demo (riverdale-health)

- "One script, one fill" moves from app-authored guard clause to **protocol-enforced
  uniqueness** — a definition that forgets the check is still protected.
- Fiber records stop accumulating nullifier maps in `stateData`: records and every state-proof
  over them become **constant-size**.
- The PDMP query: prove "this prescription is spent/unspent" with a **single
  `nullifier/<domain>/<nf>` key against the consensus-signed root** — no fiber read, no
  patient data. (Unspent needs Phase B absence proofs.)
- No proof fixture regeneration: the M5 `exprHash` binds the in-guest effect, not the
  on-chain definition, so migrating defs to `_consumeNullifier` is fixture-neutral.

## 3. Implementation map (seams verified at file:line on main, post-#210)

The `_transferAsset` trail is the model; `_addDependency` (self-fiber, emitter-keyed) is the
closer analogue for domain-keying. Chain work (PR-1):

- **models**: `ReservedKeys.CONSUME_NULLIFIER` (+ directive-field keys); `FiberDirective.
  ConsumeNullifier` (total-match ⇒ forgetting a handler is a compile error);
  `EffectKind.Nullifier` (UPPERCASE wire name; joins the `allowedEffects` dial);
  `FiberEffect.NullifierConsumed(nullifier: Hash)`; `FiberResult.Success.
  nullifierConsumptions = List.empty`; `TransactionResult.Committed.nullifierConsumptions:
  Map[UUID, List[NullifierConsumed]] = Map.empty`; `ExecutionLimits.maxNullifierConsumptions`
  (cap 32, mirroring `maxAssetMutations`); `CalculatedState.nullifiers` (trailing default —
  test blast radius ≈ zero) + `committedView.entries` projection (TOTAL key derivation).
- **shared-data**: `EffectExtractor` handler arm + `extractNullifierConsumptions` (clone
  `extractAssetTransfers`: array directive, `evalOrReject` gas-charged, malformed = loud
  `rejectDirective`, absent = no-op); `FiberEvaluator.buildSuccessOutcome` collect + the
  fail-closed `allowedEffects` row; `FiberEngine` threading through
  `processStateMachineSuccess → commitStateMachineSuccess → completeStateMachineTransaction`
  with the emitter-keyed map (`Map(originalFiber.fiberId -> …)` — this IS the domain) +
  cascade merge (clone `mergeAssetTransfers`; thread `TriggerHandler`/`TriggerDispatcher`);
  `FiberCombiner.handleCommittedOutcome` applies via a new `NullifierCombiner`
  (deterministic emitter sort, cap check, absent-check → insert at current ordinal, hit ⇒
  `CombineRejected`); `DefinitionLinter` shape rule for the nf expression + auto-recognition
  via `ReservedKeys.directiveKeys`.
- **l0**: checkpoint slimming at the handler (`MetaHandler.checkpoint` returns
  `state.copy(nullifiers = empty)` — canonical encoder untouched); `GET
  /v1/nullifiers/{domain}/{nf}` route + `StateProofHandler.nullifier` (presence proof via
  `proofFor(s"nullifier/$domain/$nf")`).
- **Free wins confirmed**: `StateMerger` already strips `_`-prefixed keys from stateData;
  directive injection from data-computed keys is dead by construction (authored-AST walk);
  `RejectionReceipt.reason` is a free string (no enum change); genesis needs no seeding;
  signing-canonical suites are unaffected (nullifiers is not a signed message);
  `CommittedViewSuite` pins keys/sizes, not root hashes, so existing cases stay green.
- **Tests**: extractor unit suite (clone `AssetTransferRecipientObjectFormSuite`); combiner
  suite (clone `AssetFiberTransferSuite`): consume-ok, double-spend ⇒ receipt, cross-fiber
  isolation (same nf, two domains, both succeed), cap exceeded, malformed directive,
  `allowedEffects` denial; committed-view projection test (`nullifier/<uuid>/<hex>` keys).

SDK work (PR-3, after PR-1 shape settles): `_consumeNullifier` in the fiber-app types +
linter mirror, nullifier route client, checkpoint type note, Phase-B absence arm in
`verifyStateProof`.

## 4. Rollout

PR-1 ottochain subsystem → PR-2 metakit MPT absence (+ rc.8) → PR-3 sdk surface →
PR-4 riverdale-health migration (after PR-1 and the health lane PR both land) + README/design
doc guarantee-table updates + privacy-handoff P0.1 status flip.
