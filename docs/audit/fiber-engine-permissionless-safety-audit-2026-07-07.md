# Fiber Engine — Permissionless-DL Safety Audit

| | |
|---|---|
| **Audit date** | 2026-07-07 |
| **Commit audited** | `d5ec8993f0c763c81a26aadc2beae54e9d8ab4bb` |
| **Branch** | `feat/sigma-concurrency-2` |
| **Metakit (JLVM) version** | `1.8.0-rc.7` (`project/Dependencies.scala:11`) |
| **Auditor** | Claude (Fable 5), driven by J. Aman |
| **Method** | Full manual read of the execution path + 5 adversarial sub-reviews (determinism, DoS/resource, authorization, combiner-gate/block-poisoning, migration/state-integrity). Every Critical/High finding re-verified against source. |

> **Purpose.** This is the baseline safety audit of the OttoChain fiber engine for a public,
> permissionless deployment. It is intended as a durable reference: every claim is grounded to a
> file:line at the commit above so a future audit can `git checkout d5ec899`, confirm each finding,
> and diff subsequent hardening against this baseline instead of starting from zero. When a finding is
> remediated, update its **Status** line with the fixing commit rather than deleting the entry.

---

## Threat model

Public permissionless submission. Any account may submit any `OttochainMessage`. Structural checks run
at Data-L1 (`validateUpdate`); the authoritative gate is the **combiner**, which runs the full fiber
execution synchronously **on every Metagraph-L0 validator** for every accepted update, and hashes the
resulting `CalculatedState` into the signed snapshot (committed MPT root). Consequences of interest:
(1) anything an attacker can force per update is inflicted on all validators; (2) any divergence in the
committed state across honest validators forks the chain; (3) `validateSignedUpdate` returning
`Invalid` on one update drops the **entire** DL1 block (tessellation all-or-nothing — see
`Validator.scala:145-162`). Metakit/JLVM internals are audited separately
(`docs/audit/metakit-sigma-fiat-shamir-audit-2026-06-17.md`); this audit covers the **host/engine**
side: how the engine drives the VM, bounds it, threads state, gates effects, and validates.

## How to reproduce this audit

```
git checkout d5ec8993f0c763c81a26aadc2beae54e9d8ab4bb
# Engine core:
modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/fiber/**
# Combiners + validators (the consensus gate):
modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/**
# Limits / policy / updates:
modules/models/src/main/scala/xyz/kd5ujc/schema/fiber/**
# Canonicalization (determinism anchor), in the metakit dep:
io/constellationnetwork/metagraph_sdk/std/{JsonBinaryCodec,JsonBinaryHasher,JsonCanonicalizer}.scala
```

## Findings at a glance

| ID | Severity | Title | Status |
|----|----------|-------|--------|
| C1 | Critical | No economic gate — free validator-synchronous compute spam | **Deferred** (blocked on tessellation; design owned) |
| C2 | Critical | Asset `Compose`/`Pool` break amount conservation + cross-holder theft | **Remediated** 2026-07-07 (tested; pending commit) |
| C3 | Critical | Script create/upgrade reads registry lineage in `validateSignedUpdate` (Rule #3) | **Remediated** 2026-07-07 (tested; pending commit) |
| H1 | High | Spawn owner-forgery + fiber-origin `_scriptCall` = script access bypass | **Remediated** 2026-07-07 (tested; pending commit) |
| H2 | High | Un-metered O(chain-state) host work amplifies spam | **Deferred** (execution-cost design owned) |
| M1 | Medium | Fiber/script own-record stateful checks in the block-acceptance gate | **Remediated** 2026-07-07 (tested; committed; fiber-upgrade residual now also closed) |
| L1 | Low | Version-dependent text in committed failure receipts (mixed-version fork risk) | **Remediated** 2026-07-07 (tested; committed — `ValueKind` + stable `reasonCode`) |
| M2 | Medium | Cascade transitions unauthenticated (direct-vs-cascade auth asymmetry) | **Documented** 2026-07-07 (pending commit; closed-default deferred) |
| M3 | Medium | `acceptedCallers` semantic footguns + not-yet-created-UUID front-run | **Documented** 2026-07-07 (pending commit) |
| L1–L6 | Low | Hardening notes (see below) | Open |

> **2026-07-07 remediation pass.** C2/C3/H1/M1 implemented + M2/M3 documented in one pass across
> disjoint files; full `sharedData` suite green (**571/571**, incl. new exploit-block regression tests)
> and scalafmt clean. See the remediation-tracking table at the bottom for per-finding detail and the
> two residuals (M1 fiber-upgrade checks; H1 fiber-caller-as-principal redesign).

---

## Critical

### C1 — No economic gate: free, repeatable, validator-synchronous compute spam
**Status:** Deferred — gas metering exists but charging is blocked on underlying tessellation
fee/balance functionality; remediation design is owned by the team.

**Location.** `RegistryCombiner.scala:32` (`TODO(economics): charge registrationGas … once the
fee/balance subsystem [exists]`); `FeeEstimates.scala:11,37-42` + `EstimateHandler.scala` (advisory
quotes only); execution entry `FiberCombiner.scala:127-135`; budget `ExecutionLimits.scala:37`
(`maxGas = 10_000_000L`).

**Mechanism.** Gas is metered per transaction (bounds a single transaction's interpreter work) but is
never deducted from any balance or stake, and there is no fee/nonce-cost/rate-limit anywhere in
`modules/l0`, `modules/l1`, `modules/data_l1`, or any combiner. Every `TransitionStateMachine` is
admitted on structural + ownership checks, then executed with the full 10M-gas budget in the combiner
on every validator.

**Exploit.** Craft a fiber whose guard/effect burns the entire 10M-gas budget; submit repeatedly.
Attacker cost ≈ one signed message; validator cost ≈ 10M JLVM ops × N validators, unbounded in the
block dimension. This is the foundational gap that every DoS finding multiplies on top of.

**Remediation.** Price `gasUsed` against a fee/stake/anti-spam layer. The metering plumbing is already
in place (single `ExecutionState.gasUsed` threaded through the whole transaction — see "Verified
sound"); only the charge is missing. Deferred pending the tessellation fee/balance primitive.

---

### C2 — Asset `Compose`/`Pool` breaks amount conservation and enables cross-holder theft
**Status:** Open → hardening (this pass).

**Location.** `AssetCombiner.scala:239-285` (`applyMorphism`), `:600-649` (`applyCompose`), `:656-678`
(`consumeNonce`), `:773-819` (`applyPool`), `:988-995` (`resolveCounterParties`),
`requireWalletHolder`, `allowlistsOk`. Attacker-controlled fields: `Updates.scala` `ApplyMorphism`
`otherAssetIds` / `nonce` / `compositeId`.

**Mechanism (verified in source).**
1. `applyMorphism` runs the R1 holder check on the **source only**: `requireWalletHolder(source,
   signers)` at `:257`. Counter-parties are resolved by `resolveCounterParties` (`:988`), which does
   **no ownership check, no dedup, no self-exclusion** — it only rejects a missing id.
2. `allowlistsOk` restricts counter-parties by **policy name / behavior bits**, never by ownership.
3. `applyCompose` forms `parts = source :: counterParties` (`:611`), computes `amount =
   parts.map(_.amount).sum` (`:631`), then removes each `componentId` once via `foldLeft … removeAsset`
   (`:644`). The consent gate `consumeNonce` is a **no-op when `nonce = None`** (`:661-662`), and
   nothing verifies that a nonce-less compose is same-holder — the same-holder assumption in the
   scaladoc (`:653`) is never enforced.

**Exploits.**
- *Inflation (no victim, no race):* `Compose(assetId=S, otherAssetIds=[S], nonce=None)` →
  `parts=[S,S]` → `amount = 2·s`, but `removeAsset(S)` applies once → composite worth `2s`, same
  `schemaBinding.name`, still combinable (`foldMeet` idempotent) → repeatable to `4s, 8s…`;
  `derivedSupply` (`:~1137`) inflates without bound. Duplicate ids in `otherAssetIds` give the same
  effect.
- *Cross-holder theft:* signer owns dust `B`; victim holds `A` (amount 100).
  `Compose(assetId=B, otherAssetIds=[A], nonce=None)` passes `requireWalletHolder(B)`, consumes `A`
  with no consent into a signer-held composite (amount `b+a`); the victim's `A` is removed from
  `assets`. `applyPool` (`:796`) checks every part's owner so theft-via-Pool is blocked, but the
  **duplicate/self inflation still applies** to Pool.

**Preconditions.** The source's policy defines a `Compose`/`Pool` morphism that is not `Governed`
(or has a guard the attacker passes), and the parts are `combinable` (C bit) — normal for composable
assets. The `AuthorizeCompose` nonce (`:288-308`) was designed for cross-holder consent but is opt-in:
the attacker simply omits it.

**Impact.** Breaks `Σ amount` conservation (unbounded mint) and enables direct theft of any
combinable asset. Critical.

**Remediation.** In `applyMorphism`/`applyCompose`/`applyPool`, before consuming parts: (a) reject when
`otherAssetIds` contains duplicates or `source.assetId`; (b) when `nonce = None`, require **every**
counter-party to be signer-owned (same-holder); (c) require a live authorization nonce for **every**
cross-holder counter-party — i.e. make consent mandatory, not opt-in. All as graceful
`CombineRejected`.

---

### C3 — Script create/upgrade reads registry lineage in `validateSignedUpdate` (Rule #3 breach → block-poisoning)
**Status:** Open → hardening (this pass).

**Location.** Dispatch: `Validator.scala:140` (`scriptCombined`), `:169` (`CreateScript`), `:171`
(`UpgradeScript`). Offending L0 reads: `ScriptValidator.scala:87` (`createScript` →
`scriptRefResolvesAndMatches`), `:99-103` (`upgradeScript` → same). Lineage read:
`RegistryRules.scala` `scriptRefResolvesAndMatches` → `lineageOf(name, state)` → `state.registry.get(name)`
→ `lineage.resolve(versionReq)` (verified: the rule pattern-matches `resolve` `Left` → `SchemaRefUnresolvable`,
and compares `digest === rv.logicHash` → `SchemaRefLogicMismatch`).

**Mechanism.** `ScriptValidator.CombinedValidator` (used only from `validateSignedUpdate`) runs both L1
and L0 layers (`ScriptValidator.scala:119-139`). The L0 layer reads the registry **version lineage**
for bound `CreateScript` and for every `UpgradeScript` (whose `targetRef` is mandatory).

**Exploit.** An `UpgradeScript` (or bound `CreateScript`) sits in a DL1 block. A concurrent third-party
`SetVersionStatus` (yank) or `PublishScriptVersion` on that package flips `lineage.resolve` to `Left`
(→ `SchemaRefUnresolvable`) or changes the resolved `logicHash` (→ `SchemaRefLogicMismatch`) between
DL1 block formation and ML0 re-validation. ML0 returns `Invalid`; tessellation drops the **entire** DL1
block, killing every unrelated transaction batched with it. Third-party-triggerable.

**Why this is a genuine miss (not a false alarm).** The analogous `RegistryValidator.CombinedValidator`
is explicitly documented "MUST NOT be used from `validateSignedUpdate`"
(`RegistryValidator.scala:91-96`), registry/asset ops correctly use L1-only validators at L0
(`Validator.scala:152,162`), and the **fiber** create path was hardened —
`FiberValidator.L0.createFiber` (`FiberValidator.scala:108-112`) does not resolve the ref. Only the
**script** path was overlooked. This is precisely CLAUDE.md invariant #3.

**Remediation.** Drop `scriptRefResolvesAndMatches` from `ScriptValidator.L0.createScript` and
`upgradeScript`; the `ScriptCombiner` already re-verifies bind + hash as a graceful `CombineRejected`
(`resolveScriptBinding`). Keep the structural L1 checks.

---

## High

### H1 — Spawn owner-forgery + fiber-origin `_scriptCall` = script access-control bypass
**Status:** Open → hardening if feasible (this pass).

**Location.** `SpawnValidator.scala:210-284` (`resolveOwners`/`applySpawnOwnerPolicy`),
`SpawnProcessor.scala:142` (`owners = spawn.resolvedOwners` written verbatim), `TriggerHandler.scala:167-174`
(`callerAddress = state.getFiber(sourceFiberId).flatMap(_.owners.headOption)`), `ScriptProcessor.scala:71-111`
(`validateAccess`: `Whitelist`/`FiberOwned`). Direct-path owner gate (for contrast):
`FiberRules.scala:299-319` (`updateSignedByOwnerOrParticipant`).

**Mechanism.** Two individually-intended behaviors compose:
1. A `_spawn` may assign **arbitrary, unsigned** child `owners`: `resolveOwners` evaluates the
   attacker's `ownersExpr` with no consent check; the default `spawnOwnerPolicy` (`Explicit`/absent)
   leaves them untouched (`:214-215`). (Confirmed by `SpawnMachinesSuite` "spawn with explicit
   ownersExpr sets custom owners" — an Alice+Bob parent spawns a Charlie-only child.)
2. A fiber-origin `_scriptCall` resolves the script caller as the emitting fiber's `owners.headOption`
   and matches it against the script's `AccessControlPolicy`.

**Exploit (one tx, depth 2).** Attacker submits `TransitionStateMachine` to her own fiber P (passes the
direct-path owner gate). P's effect (a) `_spawn`s child C with `owners:["<victim>"]` and a one-transition
definition whose effect is `{"_scriptCall": {fiberId: S, method, args}}`, and (b) `_triggers` C. The
cascade drives C (no owner gate — see M2), C emits the `_scriptCall`, dispatch resolves
`caller = C.owners.head = victim`, and script S with `Whitelist({victim})` or `FiberOwned(G ∋ victim)`
grants access **as the victim with no victim signature.** Any oracle/admin script gated by
`Whitelist`/`FiberOwned` is bypassable by any account.

**Remediation.** Root fix: constrain spawn `owners` to a subset of the parent's owners (or the tx
signers) — make `SubsetOfParent` the enforced floor even under `Explicit`, or reject unsigned owners.
Amplifier fix: represent a fiber-origin `_scriptCall` caller as a distinct fiber-principal rather than
impersonating an owner wallet (requires `AccessControlPolicy` to support a fiber-caller variant). The
subset-of-parent constraint is the lower-risk, higher-value change and is the recommended first step.

### H2 — Un-metered O(chain-state) host work amplifies the free-spam surface
**Status:** Deferred — execution-cost remediation design is owned by the team.

**Location.** `ContextProvider.scala:94-100` (`heldAssetsByFiber` folds over **all**
`calculatedState.assets.values`), rebuilt per candidate transition at `FiberEvaluator.scala:164-165`
(≤ `MaxTransitionsPerState`) and per spawn/trigger context; `:280-312` (`buildChildrenContext` /
`buildFiberSummary` resolves **all** `childFiberIds`, each embedding full `stateData`);
`FiberEngine.scala:703-705` (`childFiberIds` append-only, never pruned); `FiberEvaluator.scala:331`
(flat 10-gas `contextBuild` charge); `FiberCombiner.scala:174-180` (archive = status flip, no removal).

**Mechanism.** Context construction is host-side Scala **outside** the gas meter. Its cost scales with
attacker-inflatable, never-pruned chain state (total assets, cumulative children, total fibers). The
"built once per provider" comment (`ContextProvider.scala:90-93`) dedupes only within a single
provider, not across the ~20 providers per transition or across the cascade.

**Exploit.** Cheaply mint many assets and/or accrue many children over many (currently free — see C1)
blocks; thereafter every guard attempt on any fiber pays O(total-assets) + O(children × child-state)
for a flat 10 gas. Per-transition validator cost grows without bound as the chain fills.

**Remediation (recommended — see also team's owned design).**
- Meter context construction: charge gas proportional to `heldAssets`/`children`/`machines` entries
  actually materialized (fold the count into `ExecutionState.gasUsed` before evaluation), so O(state)
  work is priced like everything else.
- Bound the inputs: cap the number of children/held-assets/deps injected into any one context (e.g. a
  hard per-context entry cap, or lazy/paginated resolution so a guard requests only the ids it reads).
- Cap cumulative `childFiberIds` per fiber (a policy dial or engine constant), or store children as a
  count + on-demand lookup rather than an inline array.
- Longer term, pair with C1's fee layer so state footprint (storage rent) is priced, giving nodes a
  reason-to-prune signal.

Note: the per-transaction **interpreter** budget is sound — every JLVM eval site charges the shared
`maxGas`, and `maxDepth=10` correctly bounds the cascade (see "Verified sound"). H2 is strictly the
host-side O(state) work outside that budget.

---

## Medium

### M1 — Fiber/script own-record stateful checks remain in the block-acceptance gate
**Status:** Open → hardening (this pass).

**Location.** `FiberValidator.scala:114-140` (L0 `processEvent`/`upgrade` read `fiberIsActive`,
`transitionExists`, `updateSignedByOwnerOrParticipant`), `ScriptValidator.scala:89-104` (`scriptIsActive`,
`accessControlCheck`), L1 `sequenceNumberMatches` run at L0 via `CombinedValidator`
(`FiberRules.scala:197-219`; `ScriptRules.L1`). Team acknowledgement: `Validator.scala:142-162`.

**Mechanism.** Unlike registry/asset (moved to L1-structural-only at L0), fiber and script transitions
run the full `CombinedValidator` (L1+L0) in `validateSignedUpdate`, reading mutable
`CalculatedState.stateMachines`/`scripts` and `OnChain.fiberCommits`. A concurrent update to the **same**
fiber that lands first (archive, a state advance so `(currentState,event)` no longer resolves, or a
sequence bump) flips `Valid→Invalid` at ML0 re-validation → whole-block poisoning (same class as C3,
narrower: same-fiber and sequence-gated). The combiner already rejects each of these gracefully
(`FiberCombiner.scala:116-122`, `ScriptCombiner.scala:69-75`), so the L0/L1 gate is redundant here.

**Remediation.** Make fiber/script **transition** L0 validation structural-only (drop `fiberIsActive`,
`transitionExists`, and stateful seq/active reads from the block gate); rely on the combiner's graceful
`CombineRejected`. Keep signature-shape and payload-structure checks. (This mirrors the registry/asset
treatment.) Immutable reads (`owners`/`authorizedSigners`, set at creation) are TOCTOU-safe and may
stay, but the mutable status/seq/transition reads should move.

### M2 — Cascade transitions are unauthenticated (direct-vs-cascade auth asymmetry)
**Status:** Open → hardening (this pass).

**Location.** `TriggerHandler.scala:89-91` (`FiberEvaluator.evaluate(sm, trigger.input, proofs =
List.empty, caller = trigger.sourceFiberId)` — skips the L0 validator), gate: `FiberEvaluator.scala:129-147`
(`policyShortCircuit`: only `sealedStates` + `acceptedCallers`). Direct path (gated):
`FiberRules.scala:299-319`.

**Mechanism.** Direct/wallet transitions **are** owner/participant-gated. The cascade path passes no
proofs and skips `updateSignedByOwnerOrParticipant`, so **any account can drive any fiber's transition**
via a one-hop `_triggers`, subject only to the target's `acceptedCallers` (default unset ⇒
unrestricted) and its guard. An author who assumes owner-only transitions (reasonable — the direct path
enforces it) and omits `$proofs`/`$caller` checks is fully drivable by anyone. This is the enabling gap
for H1.

**Remediation.** (a) Document the asymmetry loudly (guards are the *only* gate on the cascade path).
(b) Provide a per-fiber posture that also governs the cascade default — e.g. an `acceptedCallers`
default that is not fully-open, or an explicit "callable-by" policy dial checked for both paths — so an
unguarded transition is not open to arbitrary cascade callers. Keep it additive/omit-safe per CLAUDE.md
rule #1.

### M3 — `acceptedCallers` semantic footguns + not-yet-created-UUID front-run
**Status:** Open → hardening (this pass).

**Location.** `FiberEvaluator.scala:138-145` (`acceptedCallers` enforced only when `caller = Some`);
`Updates.scala` `CreateStateMachine.fiberId` is creator-chosen.

**Mechanism.** `acceptedCallers` gates only fiber-origin (cascade) callers; a wallet-origin transition
skips it (still owner-gated). And because `acceptedCallers` matches UUIDs while `fiberId` is
creator-chosen, authorizing a not-yet-created fiber id lets an attacker `CreateStateMachine` with that
exact id to satisfy the allowlist (low-likelihood pattern, but real).

**Remediation.** Document that `acceptedCallers` gates only cascade callers (pairs with M2). Optionally
reject `acceptedCallers` entries that do not resolve to an existing fiber at set-time, or warn in the
`DefinitionLinter`. Keep semantics omit-safe.

---

## Lower-severity / hardening

- **L1 — Committed error-receipt text → mixed-version fork risk.** Failure receipts embed
  `getClass.getSimpleName`/`ex.getMessage` (`FiberEvaluator.scala:220`, `StateMerger.scala:56`,
  `AssetCombiner.scala:~1040`) and are hashed into `CalculatedState.stateMachines.lastReceipt` and
  `OnChain.latestLogs`. Node-authored today (safe on a homogeneous jar), but a metakit/ottochain
  reword changes a *rejected* tx's state hash → a mixed-version network forks on a rejected tx. Commit
  a stable enum/code; keep prose in logs.
- **L2 — Script fibers have no `UpgradeGate`/policy.** The tighten-only/`Immutable`/`Governed` trust
  anchor is state-machine-only (`FiberPolicy` lives on `StateMachineDefinition`); a script owner can
  always re-point to any registered same-package version (`ScriptCombiner.upgradeScript`,
  `FiberEngine.migrateScriptFiber:175-247`). A third party cannot pin an immutability/authority
  guarantee on a script.
- **L3 — `maxGenerations` resets across a migration.** The cap counts ancestors by definition-digest
  (`FiberEngine.scala:652-694`, `selfDigest = fiber.definition.computeDigest`); a migration changes the
  digest, so a non-`Immutable` self-reproducing lineage can migrate and re-spawn `cap` more
  generations, repeatably (owner-funded, gas-metered). Setting `upgradePolicy = Immutable` makes the
  cap absolute.
- **L4 — `owners.headOption` picks an arbitrary owner** as the cross-fiber script caller and receipt
  `invokedBy` (`TriggerHandler.scala:169`). Deterministic within-jar but semantically arbitrary for a
  multi-owner fiber; prefer `owners.toList.sorted.headOption`.
- **L5 — Fail-silent effect extraction.** A malformed `_transferAsset`/`_triggers`/`_addDependency`
  directive is silently dropped while the transition still commits as success (`EffectExtractor` uses
  `OptionT`/`flatTraverse` drop-on-`None`). Not a consensus bug, but a value-safety footgun: a contract
  that believes it transferred an asset proceeds as if it did.
- **L6 — `CreateStateMachine.parentFiberId` is not ownership-validated** (existence/active only,
  `FiberCombiner.scala:78`). Traced consequences are benign (`childFiberIds` updated only by `_spawn`,
  not by create; no capability inheritance; `checkMaxGenerations` only tightens) — flag for
  defense-in-depth.
- **INFO — `commuteObligation`** (`FiberEngine.commuteObligationFor:257-261`,
  `FiberLogEntry.UpgradeReceipt`) is an unverifiable off-chain assertion recorded in the receipt;
  `ConformanceChecker` (`:79-86`) is shallow by design. Both documented; restate to consumers that
  `commuteObligation = true` is a claim, not a proof.

---

## Verified sound (regression anchors — a future change that breaks any of these is a serious defect)

- **Determinism / hashing.** Every `computeDigest` routes `JsonBinaryHasher.deriveFromCodec` →
  `codec.serialize` → `dropNulls` → `JsonCanonicalizer.canonicalizeJson`, which sorts object keys by
  RFC 8785 UTF-16 ordering (`JsonCanonicalizer.scala:53-65` `keyOrdering`, `:121` `TreeOrderedMap.from`).
  Committed collections are `SortedMap`/`SortedSet` (`CalculatedState.scala:20-31`, `OnChain.scala:44-49`).
  Asset transfers apply in `sortBy(_._1)` emitter order (`AssetCombiner.scala:384-387`). No
  wall-clock/`Random`/float/`hashCode`/unordered-to-seq reaches committed bytes. **No fork-inducing
  non-determinism on a homogeneous jar** (guarded implicitly by the same-jar assumption; see L1 for the
  mixed-version caveat).
- **Migration tighten-only lattice** (`FiberPolicy.tightens:284-343`) is complete: every dial rejects
  drop-to-`None` (`subset`/`superset`/`capShrinks`/`rankUp`/`latchOn`/`upgradeTierUp`/`versionAdvances`/
  `transferTightens`/`dependencyTightens`), directions correct. `Immutable` denies first/unconditionally
  (`UpgradeGate.scala:75-76`); `Governed` trusts only verified signers pinned to the OLD policy, never
  `newDefinition` (`:95-134`); `compatBridge` reads the OLD window (`:223-233`); verified re-bind +
  monotonic + same-package on both paths (`FiberCombiner.scala:213-246`, `ScriptCombiner.scala:143-174`).
- **`$caller` non-spoofable for state-machine targets** — `sourceFiberId` is always the engine's true
  emitter (`EffectExtractor.scala:74,138,172,305`, surfaced `ContextProvider.scala:178`); a guard/effect
  controls only `targetMachineId`/`eventName`/`payload`. Top-level create owners are signature-derived
  (`FiberCombiner.scala:50`, `ScriptProcessor.scala:50-51`). `_emit` emitter-stamp non-forgeable.
- **Asset holder-defense (R1)** re-reads the holder from threaded combiner state, requires
  `holder == Fiber(emitter)`, transferable + recipient-liveness + `transferPolicy`
  (`AssetCombiner.scala:413-480`); second-transfer/competing-emitter fail R1; fiber-held raw morphisms
  rejected (`requireWalletHolder`).
- **Replay / atomicity / exception-containment.** Exact-sequence check with atomic bump
  (`FiberCombiner.scala:116-122`, `ScriptCombiner.scala:69-75`, `AssetCombiner.scala:250-254`);
  all-or-nothing (partial mutations discarded via `RejectionReceipt`, `Combiner.scala:90-130`); only
  `CombineRejected` escapes the combine fold (`Combiner.scala:90`) — the `new RuntimeException` sites in
  `ContextProvider`/`FiberEngine` are defensively unreachable (fiber-kind/input-kind mismatches are
  folded to graceful `FiberInputMismatch`/`TriggerHandlerResult.Failed` before those sites).
- **Cascade & spawn bounds.** Single shared `maxGas` budget threaded through guard+effect+cascade+
  spawn+extraction+migration (every JLVM eval site charges it); `maxDepth=10` bounds total handled
  triggers (`ExecutionOps.scala:87`, `TriggerDispatcher.scala:84,186-188`); spawn fan-out doubly bounded
  (`maxSpawnsPerTransition` + policy `maxSpawnFanout`), gas-metered before record construction; spawn
  `parentFiberId` hardcoded to the emitter (`SpawnProcessor.scala:144`) — no victim-planting; childId
  collisions checked intra-batch and vs known fibers.

## Consensus-critical limits (as of this commit)

`ExecutionLimits.scala`: `maxDepth=10`, `maxGas=10_000_000`, `maxStateSizeBytes=1_048_576` (1 MB),
`maxAssetMutations=32`, `maxActiveDependencies=64`, `maxDependencyLedger=256`,
`maxSpawnsPerTransition=16`. Metakit codec nesting cap `DefaultMaxDepth=64`. These decide abort-vs-commit
and MUST be identical across all validators.

## Remediation tracking

| Finding | Fixing commit(s) | Notes |
|---------|------------------|-------|
| C2 | _pending commit_ | `AssetCombiner`: `consumeComposeConsent` (per-counterparty signer-owned OR nonce-authorized, mandatory) + `requireDistinctCounterParties` (dedup/self-exclusion) on Compose & Pool. 6 new Σ-conservation tests. Residual: single `nonce` field caps multi-party cross-holder compose (pre-existing shape limit). |
| C3 | _pending commit_ | `ScriptValidator.L0`: dropped `scriptRefResolvesAndMatches` from create/upgrade (combiner `resolveScriptBinding` re-verifies gracefully). |
| H1 | _pending commit_ | `SpawnValidator`: child owners forced ⊆ parent owners under ALL dials (fail-closed floor). `TriggerHandler`: deterministic `owners.toList.sortBy(...).headOption` caller. Residual: fiber-caller-as-distinct-principal redesign (follow-up). |
| M1 | _committed_ | `FiberValidator`/`ScriptValidator` `CombinedValidator`: ML0 gate now structural + immutable-owner-signature only; dropped `fiberIsActive`/`scriptIsActive`/`transitionExists`/`sequenceNumberMatches` (combiner enforces gracefully). DL1 `L1Validator` unchanged. Residual CLOSED: fiber-upgrade `bindingNameMatches`/`currentStateInDefinition`/`upgradePolicyPermits` removed too (re-enforced in combiner/`UpgradeGate`). |
| L1 | _committed_ | New `ValueKind.of` (ottochain-pinned value-kind vocabulary) + `FailureReason.reasonCode`; committed receipts no longer embed exception `getMessage`/metakit `getClass.getSimpleName`/`.tag`/`toString`; raw text → logs. Sites: `JsonLogicExceptionOps`, `FiberEvaluator`, `StateMerger`, `MeteredEvaluator`, `AssetCombiner`, `SpawnValidator`. `FailureReasonCanonicalSuite` guards it. |
| M2 | _pending commit_ | Docs: cascade auth asymmetry at `TriggerHandler.handleStateMachine`. Closed cascade-default deferred (needs a new omit-safe `cascadePosture` dial). |
| M3 | _pending commit_ | Docs: `acceptedCallers` gates cascade callers only + not-yet-created-UUID front-run, at `FiberEvaluator.policyShortCircuit`. |
| C1, H2 | _deferred_ | fee/execution-cost design owned by team (H2 approach documented in-finding). |

_Verification: full `sharedData` suite 571/571 green + scalafmt clean at remediation time; all edits
confined to 7 main + 4 test files, disjoint per finding-group._
