# Engine hardening: spawn & effects

> **Foundation note.** This is the engine-level invariant layer the forthcoming `fiber-policy.md` builds on: `selfReproducing` is **FiberPolicy dial #1**, and **Part B** of this spec is the effect audit (spawn/emit/cascade) that the policy framework sits atop.

Opt-in fiber-level self-reproduction (code-preservation) invariant on `_spawn`, enforced in SpawnValidator via canonical definition-digest equality, plus a re-scoped adversarial audit whose true concentrated risk is the cascade path that bypasses the existing owner/participant signer gate.

---


# FINAL DESIGN SPEC — engine-hardening (ottochain Scala)

All file:line refs verified against the canonical tree (`/home/euler/repos/ottochain/modules/...`), not the `.claude/worktrees/*` copies. Read-only investigation; no files were modified.

## 0. Corrected ground truth (the review's BLOCKER B1 was right)

The original design's premise — "the only gate before a directive is honored is `guard == true`, so a fiber can be driven by *anyone* who satisfies *some* guard" — is **FALSE for the primary/external path** and has been removed. Verified:

- `FiberValidator.L0Validator.processEvent` (FiberValidator.scala:115-121) runs `FiberRules.L0.updateSignedByOwnerOrParticipant` **before** any transition is evaluated.
- That rule (FiberRules.scala:299-319) requires the inbound `TransitionStateMachine` to be signed by a member of `record.owners ++ record.authorizedSigners` (FiberRules.scala:308: `case sm: Records.StateMachineFiberRecord => sm.owners ++ sm.authorizedSigners`). Failure ⇒ `Errors.NotSignedByAuthorizedParty`, the update is rejected.
- `authorizedSigners` is populated at create from `update.participants` (FiberCombiner.scala:62-63, written at :79). It is a real, engine-enforced authorization field (Records.scala:47, default `Set.empty`).

**Therefore the primary path is signer-gated.** An arbitrary external caller cannot drive a fiber; only its owners/participants (a trust-bounded set) can. The "confused-deputy driven by whoever poked it" framing (old F6) and the "any fiber can `_emit`/`_transfer`/…" abuse vectors (old F1/F2) **do not apply to the primary path** and are deleted.

**The genuine concentrated risk is the cascade path.** `StateMachineTriggerHandler.handleStateMachine` (TriggerHandler.scala:79-131) calls `FiberEvaluator.make[F, G](calculatedState).evaluate(sm, trigger.input, List.empty)` (TriggerHandler.scala:86) — note **`proofs = List.empty`** — which **bypasses `processEvent`'s signer gate entirely**. `trigger.sourceFiberId` is used only to build the receipt (TriggerHandler.scala:99), never surfaced into the target's evaluation context. So *any fiber A reachable to fiber B via `_triggers` can drive B through a transition that runs with no authenticated caller context at all, and B's guards cannot learn A triggered it.* This is the headline finding (was B.3); it is promoted to the single HIGH of Part B.

Verified-unchanged facts the original design got right (do not re-litigate): F4 asset-holder defense is genuinely strong (`AssetCombiner.applyFiberTransfer` re-checks holder/transferability/recipient-liveness against authoritative state, emitter keyed by the engine); id-collision protection is solid (`ChildIdCollision`); cascade-only honoring of spawns/dep-mutations (TriggerHandler.scala:88-91); `computeDigest` = `dropNulls` → RFC-8785 canonicalize (JsonBinaryCodec.scala:135-138) so reordered `states` Map PASSES and reordered `transitions` List FAILS.

---

## PART A — opt-in `_spawn` self-reproduction (code-preservation) invariant

### A.1 Goal & threat model
An external observer wants to trust that a given fiber *only ever spawns exact code-copies of itself* (quine / cell-division fibers: replicators, sharded registries, self-similar org trees). Today a transition can spawn an arbitrary inline `StateMachineDefinition` (SpawnDirective carries the full definition; parsed at EffectExtractor.scala spawn path). The invariant to enforce **when opted in**: `hash(spawned.definition) == hash(self.definition)`, checked by the engine, fail-closed.

### A.2 Where the policy lives — REVISED (avoids the B3 malleability footgun)

> **SUPERSEDED (greenfield):** The chosen carrier is now the typed `policy: Option[FiberPolicy]` field on `StateMachineDefinition`, not a reserved `metadata` key. See [`fiber-policy.md`](./fiber-policy.md). The metadata workaround below existed *only* to keep legacy `logicHash` values byte-identical (the B3 footgun: `None ≠ Some(default)` under canonicalization). OttoChain is greenfield — there is no legacy-hash-stability requirement, so that reason is gone. The FiberPolicy `fiberpolicy-dials` stream further verified the typed field is `logicHash`-stable anyway (via the `Some(empty) ⇒ None` normalization), so the typed `policy: Option[FiberPolicy]` field is both clean and safe. `selfReproducing` is FiberPolicy dial #1 and lives on that typed field. The original metadata reasoning is retained below for context.

**Decision: carry the policy inside the existing `StateMachineDefinition.metadata: Option[JsonLogicValue]` field (StateMachineDefinition.scala:15) under a reserved key, NOT a new typed `policy: Option[FiberPolicy]` field.**

Rationale (this supersedes the original A.2 proposal and resolves review B3):
- `StateMachineDefinition` already has exactly four fields: `states`, `initialState`, `transitions`, `metadata` (StateMachineDefinition.scala:11-16). `metadata` is *already* part of `definition`, hence already hash-pinned by #37 verified binding (`definition.computeDigest === logicHash`, FiberCombiner.scala:303-304) and already immutable except via explicit `UpgradeFiber`.
- Adding a new `policy: Option[FiberPolicy] = None` field is tempting but introduces the **B3 malleability footgun**: `dropNulls` (JsonBinaryCodec.scala:110-118) drops `None` so legacy digests are unchanged, BUT `Some(FiberPolicy(selfReproducing = false))` is NOT null and serializes to `{"selfReproducing":false}`, so `None ≠ Some(FiberPolicy(false))` under canonicalization. Any tooling that "normalizes" by inserting a default `Some` silently changes the digest and breaks #37 binding for self-repro fibers. Carrying the flag in `metadata` under an explicit reserved key sidesteps the typed-Option default entirely: a fiber either has the key present-and-true in its metadata JSON, or it does not, and the digest is whatever the author actually wrote.
- The self-reproduction policy lives at `metadata[ReservedKeys.SELF_REPRODUCING] == BoolValue(true)`. Absence (the default for every existing fiber) ⇒ opt-out ⇒ normal spawns are completely unaffected and every legacy `logicHash` is byte-identical.

**Whole-fiber property** (the trust theorem): because the policy is inside `definition`, and a self-reproducing child's `definition` is byte-equal to the parent's (hash-equal ⇒ field-equal ⇒ metadata carried), the property is **transitive**: a self-reproducing fiber can only spawn self-reproducing copies, for the entire lineage. State this as a theorem in the doc.

**Caveat to the theorem — the B7 upgrade hole (now closed, see A.5):** `migrateStateMachine` (FiberEngine.scala:282-290) sets `definition = newDefinition` with no preservation of metadata policy. An owner could `UpgradeFiber` to a definition without the self-reproducing key, revoking the guarantee. The "for its entire life" claim is only true if we add a one-way-latch upgrade rule. A.5/A.7 makes that latch a required deliverable.

### A.3 The hash check (corrected type: `computeDigest` is `F[Hash]`, resolves B4)

`computeDigest` is monadic: `HasherOps.computeDigest` returns `F[Hash]` (JsonBinaryHasher.scala:27-28), backing onto `serialize` = `dropNulls` → RFC-8785 `canonicalizeJson` (JsonBinaryCodec.scala:135-138). The codebase always sequences it: FiberCombiner.scala:303 `definition.computeDigest.flatMap{…}`, SpawnProcessor.scala:130 `initialData.computeDigest.liftTo[G]`. Use the existing `.liftTo[G]` idiom (there is a `lift: F ~> G` / `liftTo[G]` in scope in SpawnValidator/SpawnProcessor) — do NOT introduce a `liftHash` helper.

```
selfHash  <- parent.definition.computeDigest.liftTo[G]      // hoisted once per transition (B5)
childHash <- directive.definition.computeDigest.liftTo[G]
require(childHash === selfHash)                              // Hash has Eq
```

Compare digests, never structural equality (Map key order is non-deterministic; the digest canonicalizes it).

### A.4 Where the check executes & failure mode

Place it in **`SpawnValidator.validateSingle`** (SpawnValidator.scala:91-101), the existing pre-execution pass that already runs `evaluateChildId` + `evaluateOwners` and returns typed `ValidatedNel[FailureReason, …]`. Add a third validator and `mapN` it in:

```scala
private def validateSingle(
  directive: SpawnDirective, parent: Records.StateMachineFiberRecord, contextData: JsonLogicValue
): G[ValidatedNel[FailureReason, ValidatedSpawn]] =
  for {
    childIdResult <- evaluateChildId(directive, contextData)
    ownersResult  <- evaluateOwners(directive, parent, contextData)
    selfRepResult <- validateSelfReproduction(directive, parent, selfHash)  // selfHash hoisted by caller
  } yield (childIdResult, ownersResult, selfRepResult).mapN { case (childId, owners, _) =>
    ValidatedSpawn(directive, childId, owners)
  }

private def isSelfReproducing(parent: Records.StateMachineFiberRecord): Boolean =
  parent.definition.metadata.exists {
    case MapValue(m) => m.get(ReservedKeys.SELF_REPRODUCING).contains(BoolValue(true))
    case _           => false
  }

private def validateSelfReproduction(
  directive: SpawnDirective, parent: Records.StateMachineFiberRecord, selfHash: Hash
): G[ValidatedNel[FailureReason, Unit]] =
  if (!isSelfReproducing(parent)) Validated.validNel(()).pure[G]      // opt-out default: untouched
  else
    directive.definition.computeDigest.liftTo[G].map { childHash =>
      if (childHash === selfHash) Validated.validNel(())
      else Validated.invalidNel(
        FailureReason.SelfReproductionViolation(parent.fiberId, expected = selfHash, got = childHash))
    }
```

`selfHash` MUST be hoisted once per transition (computed in `validateSpawns`, SpawnValidator.scala:85-89, before the per-directive `traverse`) and threaded in — see B5. The parent's definition is invariant across the directive list; do not re-hash it per spawn.

Add to the `FailureReason` ADT (FailureReason.scala:67+, alongside `ChildIdCollision`/`DuplicateChildId` at ~:50/:52 render + definitions below):
```scala
case class SelfReproductionViolation(fiberId: UUID, expected: Hash, got: Hash) extends FailureReason
```
and a render arm in the `describe`/render match (FailureReason.scala:50-52 region).

**Failure mode — fail-closed, abort the whole transition.** On any `Invalid`, `validateSpawns` aggregates and `SpawnProcessor.processSpawnsValidated` returns `Left(errors)` (SpawnProcessor.scala:101-102), which `FiberEngine.commitStateMachineSuccess` turns into `TransactionResult.Aborted(errors.head, …)`. The whole transition — state mutation and every other effect — is discarded. This matches the existing posture for `ChildIdCollision` and the dependency-ledger breach. No partial apply. A self-reproducing fiber that tries to spawn a non-copy is compromised/malfunctioning and its step must not land.

### A.5 `authorizedSigners` + `schemaBinding` propagation (resolves review B2) and the upgrade latch (resolves B7)

**B2 — spawned children silently lose `authorizedSigners` (and `schemaBinding`).** Verified: `SpawnProcessor.createFiberRecord` (SpawnProcessor.scala:132-145) constructs `StateMachineFiberRecord(...)` WITHOUT `authorizedSigners` and WITHOUT `schemaBinding`, so both default (`Set.empty` / `None`, Records.scala:46-47). `authorizedSigners` is *half the transition-authorization gate* (FiberRules.scala:308). Consequences:
- A spawned child's entire transition-authorization set is whatever `evaluateOwners` produced (`owners`); its `authorizedSigners` is empty.
- For a self-reproducing child, "byte-equal definition" does NOT make it a faithful copy of the *running fiber* — the original fiber's control surface is `owners ++ authorizedSigners`, but the child's is only `owners`. The A.2 trust theorem ("trustworthy copy") is **false for the control surface** unless we propagate.

**Required for Part A:** when `isSelfReproducing(parent)`, the child MUST inherit the parent's *control surface*. Set in `createFiberRecord`:
```scala
authorizedSigners = if (isSelfReproducing(parent)) parent.authorizedSigners else Set.empty,
schemaBinding     = if (isSelfReproducing(parent)) parent.schemaBinding     else None
```
Propagating `schemaBinding` is safe by byte-identity: the child's `definition` is byte-equal to the parent's, so it trivially satisfies #37 (`definition.computeDigest === logicHash`). This also closes F5b for self-repro children (they become registry-anchored). For owners, see F5a in Part B; for self-repro fibers the recommended dial is `preserveControllers = owners ++ authorizedSigners` (the original A.5 "preserveOwners" generalized per B2).

**B7 — the upgrade revocation hole.** Verified: `FiberValidator.upgrade` (FiberValidator.scala:131-137) checks `bindingNameMatches` (same registry package name, FiberRules.scala:364-381) + `currentStateInDefinition`, then `migrateStateMachine` (FiberEngine.scala:282-290) sets `definition = newDefinition` — the *content* is free. An owner can upgrade a self-reproducing fiber to a definition lacking the metadata key, revoking the guarantee. For "for its entire life" to be literally true, add a **one-way latch** in upgrade validation:

> If the *current* fiber's `definition` is self-reproducing, the upgrade is rejected unless `newDefinition` is *also* self-reproducing (i.e. `selfReproducing` may never be cleared by an upgrade).

Implement as a new L0 rule `FiberRules.L0.selfReproductionLatchPreserved(cid, newDefinition, state)` added to `FiberValidator.L0Validator.upgrade`'s `for`-comprehension (FiberValidator.scala:131-137), returning a new `Errors.SelfReproductionRevoked(cid)`. This makes the observer's "read one metadata key, trust forever" claim sound. (Alternative, documented but not chosen: leave the hole and require the observer to additionally pin "no upgrades" by inspecting `owners`/upgradeability — weaker, rejected.)

### A.6 Edge cases & hardening notes
- **`initialData` intentionally NOT constrained.** Two cells with identical *code* but different *seed state* is the desired case (tree node spawns children with different ids/payloads). Only `definition` is hash-checked. Document so reviewers don't expect state-equality.
- **Gas / DoS (resolves B5):** each self-repro spawn does one `serialize+hash` over a potentially near-1MB inline definition (`maxStateSizeBytes = 1_048_576`, ExecutionLimits.scala). `computeDigest` is NOT metered by the JLVM gas model. By the audit's own logic (unmetered fan-out bounded only by gas mis-pricing is a defense-in-depth gap), Part A MUST (a) hoist `selfHash` out of the per-directive loop (mandatory, not "optional"), and (b) depend on the `maxSpawnsPerTransition` cap (Part B F5c) landing first, so the per-child hash count is bounded by an explicit cap rather than only by `spawnDirective` gas. Per-spawn gas IS charged (FiberEvaluator.scala:255: `spawnMachines.size.toLong * fiberGasConfig.spawnDirective.amount`), but it is charged in `buildSuccessOutcome` *after* extraction, so it bounds count but does not meter the validator's hashing — hence the explicit cap is required, not merely defense-in-depth (resolves B9).
- **Transitivity theorem** (state explicitly): self-repro child has byte-equal `definition` ⇒ carries the metadata key ⇒ is itself self-reproducing ⇒ can only spawn copies. With A.5 propagation, it also carries the same `authorizedSigners`/`schemaBinding`, so the *whole control surface* is preserved across the lineage, and the upgrade latch keeps it preserved across upgrades.

### A.7 Test plan (Part A)
Unit (`SpawnMachinesSuite.scala` exists at modules/shared-data/src/test/…/SpawnMachinesSuite.scala — extend it, or add `SpawnValidatorSuite`):
1. self-hash vs child-hash equality on byte-identical definitions ⇒ valid.
2. perturbed child: reordered `transitions` List ⇒ FAILS (`SelfReproductionViolation`), because list order is significant in canonical encoding.
3. reordered `states` Map ⇒ PASSES (codec sorts object keys; confirmed JsonBinaryCodec.scala:135-138). Pin this as a regression test.
4. non-self-reproducing parent (no metadata key) spawning any definition ⇒ unaffected (opt-out).
5. malleability guard: assert a definition whose metadata has `selfReproducing:false` (or no key) has a digest distinct from one with `selfReproducing:true` — and that the validator treats only `true` as opted-in.
6. `authorizedSigners`/`schemaBinding` propagation: spawn from a self-reproducing parent with non-empty `authorizedSigners` + `Some(binding)` ⇒ child inherits both; spawn from a non-self-repro parent ⇒ child has `Set.empty`/`None` (current behavior preserved).
7. upgrade latch: upgrading a self-reproducing fiber to a non-self-reproducing definition ⇒ rejected (`SelfReproductionRevoked`); to another self-reproducing definition ⇒ allowed.

E2e (`/home/euler/repos/ottochain/e2e-test/examples/self-repro-cell/`), mirroring existing examples (e.g. atomic-swap/example.json uses `expectRejected: "ml0"`):
- `definition` (2-state `idle -[divide]-> idle`) whose effect emits `{"_spawn":[{"childId":…,"definition":<SELF>,"initialData":{…}}]}`, with `metadata.selfReproducing = true`.
- Flow 1: `divide` spawning an identical definition ⇒ `expectedState: idle`, child present.
- Flow 2: `divide` spawning a definition with one extra transition ⇒ `expectRejected: "ml0"` (the verified code for "admitted then combine-denied", runner.ts:335-336; this is exactly the `SpawnProcessor`→`Aborted` path). Parent state unchanged, no child. Mirror an existing aborting-spawn example's `expectRejected` shape (resolves B10).

---

## PART B — re-scoped adversarial audit

Trust boundary, corrected: **primary path** = signer-gated (`updateSignedByOwnerOrParticipant`, FiberRules.scala:299-319); effects are attributed to the fiber and producible only by its owners/participants. **Cascade path** = NOT signer-gated (`evaluate(sm, input, List.empty)`, TriggerHandler.scala:86); any fiber reachable via `_triggers` can drive the target. The audit's severities are re-scoped accordingly: the two old HIGHs (F1 external emit-spoofing, F2 "any caller") are downgraded because "any caller" is false on the primary path; the cascade signer-bypass becomes the single HIGH.

### B.1 Findings (re-scoped, severity-ranked)

| # | Directive / path | Severity | Re-scoped abuse vector | Existing defense |
|---|---|---|---|---|
| **C1** | cascade `_triggers` → SM target | **HIGH** | The only signer-check-bypassing edge. `StateMachineTriggerHandler.handleStateMachine` evaluates the target with `proofs = List.empty` (TriggerHandler.scala:86) and never surfaces `trigger.sourceFiberId` into the guard context. Any fiber A that can reach B drives B through any transition whose guard does not independently authenticate; B's guards cannot learn A triggered it. Apps using `proofs`-based guards (`signerIsParty`; corporate/governance) reached via cascade evaluate against an **empty proofs array** — fail-closed if written as allow-listing, fail-OPEN if written as deny-listing. | Script targets DO authenticate the caller (`ScriptProcessor.validateAccess` against `accessControl`, TriggerHandler.scala:162-176). **SM targets have NO equivalent.** Cycle detection `(fiberId,eventName)` + `maxDepth=10` (ExecutionLimits.scala) bound amplification, not authorization. |
| **F3** | `_addDependency`/`_setDependencyActive` | **MED (HIGH under semi-private — the live case, B6)** | A fiber adds a dependency on ANY `fiberId` (unknown ids explicitly permitted) then reads that fiber's `state`/`currentStateId`/`sequenceNumber` summary via the `machines` context (ContextProvider.scala:258-263). Read-amplification / state-exfiltration with no consent. On the active `feat/zk-semi-private` branch this directly bypasses intended read-scoping. | Bounds only: `maxActiveDependencies=64`, `maxDependencyLedger=256`, fail-closed (DependencyLedger.scala:36-59). **No relationship/consent gate on WHICH fiber may be depended upon** (verified: `applyMutations` enforces only size bounds). |
| **F5a** | `_spawn` owners + control surface | **MED** | `evaluateOwners` lets a transition set a child's `owners` to arbitrary addresses (SpawnValidator.scala:142-196; defaults to `parent.owners` only when `ownersExpr=None`). A fiber can mint a child *owned by a victim* (griefing / privilege-laundering). Compounded by B2: child's `authorizedSigners` is always `Set.empty` (SpawnProcessor.scala:132-145) — its full control surface is attacker-chosen `owners`. | id-collision/duplicate protection is solid + fail-closed (`ChildIdCollision`, `DuplicateChildId`, SpawnValidator.scala:204-223). No authority constraint on owners. |
| **F1** | `_emit` source-stamping | **MED (was HIGH)** | Downgraded: a spoofed emit can only be produced by an owner/participant of the *emitting* fiber (primary path) — still a real *attributability* gap (consumers can't trust a self-declared emitter), but not "any fiber". `EmittedEvent` carries no emitter id (EmittedEvent.scala:10-14). No `maxEmittedEvents` cap. | None on-chain; `parseEmittedEvent` validates only presence of `name`+`data` (EffectExtractor.scala:300-310). |
| **F5c** | `_spawn` count cap | **MED (now required, B5/B9)** | No explicit `maxSpawnsPerTransition`; count bounded only by `spawnDirective` gas (FiberEvaluator.scala:255, charged post-extraction). Part A's unmetered `computeDigest` per child makes an explicit cap a prerequisite. | id-uniqueness + gas only. |
| **F4** | `_transferAsset` | **LOW (reference template)** | Already the gold standard. | `AssetCombiner.applyFiberTransfer` re-checks holder/transferability/recipient-liveness against authoritative state; emitter keyed by engine; all-or-nothing; `maxAssetMutations=32`. |

### B.2 Remediations (ordered by the corrected severity)

**1. C1 (HIGH) — surface caller identity to SM cascade targets + accept-list.**
- **R-C1a:** thread `trigger.sourceFiberId` into the SM evaluation context under a reserved key (`ReservedKeys.SOURCE_FIBER_ID`), populated in `ContextProvider.buildStateMachineContext`/`buildTriggerContext` (ContextProvider.scala:147-178). Then a target guard can write `{"==":[{"var":"sourceFiberId"},"<uuid>"]}` or check membership. This is the SM analogue of the script `validateAccess` path. Touch: `TriggerHandler.handleStateMachine` (TriggerHandler.scala:86 — pass `sourceFiberId` through instead of dropping it), `ContextProvider` (new key), `ReservedKeys`.
- **R-C1b:** optional per-definition `acceptedCallers`/`acceptAnyCaller` policy carried in `metadata` (same hash-pinned mechanism as Part A), enforced in the dispatcher/handler *before* running the target transition, mirroring `ScriptProcessor.validateAccess`. Rejects a trigger whose `sourceFiberId` is not accepted even if the target's guard is sloppy.
- **R-C1c (doc rule):** cascade-reachable transitions MUST authenticate via `sourceFiberId`, not `proofs` (which is `[]` on the cascade path). Add a test asserting a `signerIsParty`-style guard reached via cascade sees `proofs == []` and rejects (fail-closed), and that any deny-list-style guard is flagged as a footgun.

**2. F3 (MED→HIGH live) — relationship or consent gate on dep-add (B6: promote, this is the read-scoping breach the branch exists to close).**
- **R-F3a (relationship gate):** restrict addable deps to fibers the source structurally relates to — `parentFiberId`, `childFiberIds`, static `transition.dependencies`, or shared owner — checked in `DependencyLedger.applyMutations` or `EffectExtractor.extractDependencyMutations` (EffectExtractor.scala:265-296). Reject (abort, consistent with existing fail-closed bounds) an `_addDependency` on an unrelated id.
- **R-F3b (consent flag, lower-friction):** target opts in via hash-pinned `dependable:true` in its `metadata`; non-dependable targets reject inbound dep-adds. The #21 identity-registry pattern (`signerHasReputationVia`) sets `dependable=true`. Recommend R-F3b as the primary mechanism (it preserves the legitimate dynamic-dep registry pattern) with R-F3a as a stricter alternative.

**3. F5a (MED) — restrict spawn owners + propagate control surface.**
- Default-restrict `owners ⊆ parent.owners` in `evaluateOwners` (SpawnValidator.scala:142-196); to set broader owners require an explicit hash-pinned `allowArbitrarySpawnOwners` metadata flag. For self-reproducing fibers, additionally propagate `parent.authorizedSigners` + `parent.schemaBinding` to the child (Part A A.5 — resolves B2).

**4. F1 (MED) — engine-stamp emitter + bound emit count.**
- **R-F1a:** stamp emitter at the engine boundary. `EffectExtractor.extractEffects` already receives `sourceFiberId: UUID` (EffectExtractor.scala:74) and uses it for triggers/scriptCall (:136,:169) but NOT for emit (`extractEmittedEvents` takes no `sourceFiberId`, EffectExtractor.scala:298-299). Thread it through: either add `emitterFiberId: UUID` to `EmittedEvent` (EmittedEvent.scala) populated from `sourceFiberId`, or key emitted events by emitter as a `(UUID, EmittedEvent)` pair exactly like `transfersByEmitter`. Consumers read the engine-stamped emitter, never a self-declared field.
- **R-F1c:** add `maxEmittedEvents` to `ExecutionLimits` (ExecutionLimits.scala) and enforce in `buildSuccessOutcome` (FiberEvaluator.scala:248 collects `emittedEvents`; cap there alongside the per-effect gas charges :252-256).
- **R-F1b (downstream contract):** until a `destination` allowlist exists (optional, hash-pinned metadata), downstream consumers MUST treat `destination` as untrusted.

**5. F5c (MED, required) — add `maxSpawnsPerTransition` to `ExecutionLimits`** (ExecutionLimits.scala), enforced in `buildSuccessOutcome`/`SpawnValidator.validateBatchConstraints` (SpawnValidator.scala:198+), mirroring `maxAssetMutations`. Part A depends on this (A.6/B5).

**6. F4 (LOW) — tests only.** Pin the `maxAssetMutations`-under-cascade invariant (cap applies to flattened `transfersByEmitter`). **Drop** the original "recipient self-transfer no-op" micro-hardening (review B8: cosmetic, could break legitimate idempotent/rebalancing patterns, no security gain).

### B.3 Recommended order of work
1. **C1 / R-C1a+b (HIGH)** — surface `sourceFiberId` + accept-list. Smallest blast radius, largest security gain; closes the one signer-bypass edge.
2. **F3 / R-F3b (MED→HIGH live)** — consent flag on dep-add. The read-scoping breach the `feat/zk-semi-private` branch exists to close (B6).
3. **F5a (MED)** — restrict spawn owners ⊆ parent.owners + control-surface propagation.
4. **F5c (MED, required)** — `maxSpawnsPerTransition` (prereq for Part A).
5. **F1 / R-F1a+c (MED)** — emitter stamping + `maxEmittedEvents`.
6. **F4 (LOW)** — cap-under-cascade tests.

No finding is a clean BLOCKER given F4 (asset holder defense) and the verified primary-path signer gate. The real exposure is the **cascade signer-bypass (C1)** and, on the active semi-private branch, **dep-add read-scoping (F3)**.

---

## Appendix — verified file:line anchors
- Primary-path signer gate (the overlooked B1 defense): `FiberValidator.scala:115-121`, `FiberRules.scala:299-319` (`updateSignedByOwnerOrParticipant`, `owners ++ authorizedSigners` at :308).
- `authorizedSigners` set at create from participants: `FiberCombiner.scala:62-63, :79`. Lost on spawn: `SpawnProcessor.scala:132-145`. Field/default: `Records.scala:46-47`.
- Cascade signer-bypass (C1): `TriggerHandler.scala:79-131`, esp. `:86` (`evaluate(sm, input, List.empty)`) and `:99` (`sourceFiberId` used only for receipt). Script-path caller auth (parity target): `:162-176`.
- `computeDigest` is `F[Hash]`: `JsonBinaryHasher.scala:18-22, 27-28`. `serialize` = `dropNulls`→RFC-8785: `JsonBinaryCodec.scala:110-118, 135-138`. Existing #37 use: `FiberCombiner.scala:303-304`.
- `StateMachineDefinition` (only `states/initialState/transitions/metadata`): `StateMachineDefinition.scala:11-16`.
- Spawn validation (insert self-repro check): `SpawnValidator.scala:85-101` (`validateSpawns`/`validateSingle`), `:142-196` (`evaluateOwners`), `:198-223` (batch constraints, id-collision). Child record build (add propagation): `SpawnProcessor.scala:112-146`.
- Effect extraction + per-effect gas: `FiberEvaluator.scala:234-282` (`buildSuccessOutcome`; spawn gas `:255`, emit collect `:248`). `EffectExtractor.scala:70-89` (dispatch, `sourceFiberId` param `:74`), `:265-296` (dep mutations), `:298-310` (emit, no emitter id).
- Upgrade path / latch site (B7): `FiberValidator.scala:131-137`, `FiberRules.scala:364-381` (`bindingNameMatches`), `FiberEngine.scala:282-290` (`definition = newDefinition`).
- Caps: `ExecutionLimits.scala` (`maxAssetMutations=32`, `maxActiveDependencies=64`, `maxDependencyLedger=256`; no `maxEmittedEvents`/`maxSpawnsPerTransition`). Dep ledger bounds-only: `DependencyLedger.scala:36-59`.
- `FailureReason` ADT (add `SelfReproductionViolation`): `FailureReason.scala:50-52` (render), `:67+` (definitions).
- `EmittedEvent` (add emitter): `EmittedEvent.scala:10-14`.
- e2e reject convention: `runner.ts:335-336` (`'dl1'`=structural / `'ml0'`=admitted-then-combine-denied); example: `e2e-test/examples/atomic-swap/example.json:44,52`.

## Implementation checklist

- Add `ReservedKeys.SELF_REPRODUCING` (and `SOURCE_FIBER_ID`, `DEPENDABLE`) string constants in the ReservedKeys object.
- Add `FailureReason.SelfReproductionViolation(fiberId: UUID, expected: Hash, got: Hash)` to FailureReason.scala (~:67) plus a render arm (~:50).
- In SpawnValidator: add `isSelfReproducing(parent)` (reads `parent.definition.metadata` MapValue for `SELF_REPRODUCING == BoolValue(true)`); hoist `selfHash = parent.definition.computeDigest.liftTo[G]` once in `validateSpawns` (SpawnValidator.scala:85-89) before the per-directive traverse.
- Add `validateSelfReproduction(directive, parent, selfHash)` to SpawnValidator and `mapN` it into `validateSingle` (SpawnValidator.scala:91-101); opt-out (valid) when not self-reproducing, else compare `directive.definition.computeDigest === selfHash`.
- In SpawnProcessor.createFiberRecord (SpawnProcessor.scala:132-145): set `authorizedSigners = if (isSelfReproducing(parent)) parent.authorizedSigners else Set.empty` and `schemaBinding = if (isSelfReproducing(parent)) parent.schemaBinding else None`.
- Add `maxSpawnsPerTransition` to ExecutionLimits.scala and enforce in SpawnValidator.validateBatchConstraints (SpawnValidator.scala:198+) BEFORE landing Part A (Part A depends on it).
- Add upgrade latch: new `FiberRules.L0.selfReproductionLatchPreserved(cid, newDefinition, state)` + `Errors.SelfReproductionRevoked(cid)`, wired into FiberValidator.L0Validator.upgrade (FiberValidator.scala:131-137); reject upgrades that clear a previously-set self-reproducing metadata key.
- Unit tests in SpawnMachinesSuite (or new SpawnValidatorSuite): byte-identical PASS, reordered transitions FAIL, reordered states-Map PASS, non-self-repro untouched, malleability (false/absent != true), authorizedSigners/schemaBinding propagation, upgrade latch.
- E2e example self-repro-cell: flow 1 identical-def -> idle/child present; flow 2 perturbed-def -> expectRejected 'ml0' (mirror an existing aborting-spawn example).
- C1 (HIGH): thread `trigger.sourceFiberId` into SM eval context (TriggerHandler.scala:86 stop dropping it; ContextProvider.buildStateMachineContext :147-178 add SOURCE_FIBER_ID key); add optional hash-pinned acceptedCallers metadata policy enforced before running the target transition; add cascade-proofs-empty fail-closed test.
- F3 (MED->HIGH live): add hash-pinned `dependable` metadata consent gate (or relationship gate) in DependencyLedger.applyMutations / EffectExtractor.extractDependencyMutations (EffectExtractor.scala:265-296).
- F5a (MED): restrict `evaluateOwners` to `owners ⊆ parent.owners` by default (SpawnValidator.scala:142-196); broader owners require explicit hash-pinned policy.
- F1 (MED): stamp emitter into EmittedEvent from the already-available sourceFiberId in extractEffects (EffectExtractor.scala:74) by threading it to extractEmittedEvents (:298); add `maxEmittedEvents` to ExecutionLimits and enforce in buildSuccessOutcome (FiberEvaluator.scala:248).
- F4 (LOW): add tests pinning maxAssetMutations-under-cascade; do NOT add the recipient self-transfer no-op rejection.

## Open questions

1. Policy carrier: this spec recommends `metadata[SELF_REPRODUCING]` (avoids the None != Some(false) digest malleability and changes zero existing digests). Confirm the maintainer prefers metadata over a typed `policy: Option[FiberPolicy]` field — the typed field is cleaner ergonomically but carries the B3 footgun and perturbs the case-class shape.
2. Should `dependable`/`acceptedCallers` (F3/C1) likewise live in `metadata`, or warrant a typed policy object now that several hash-pinned flags are accumulating? A single `metadata.policy` sub-object may be cleaner than scattered top-level keys.
3. Upgrade latch (B7): one-way latch (selfReproducing may never be cleared) vs. documenting that the guarantee holds only between upgrades and the observer must pin no-upgrades. This spec chose the latch; confirm that does not conflict with any intended legitimate 'graduate out of self-reproduction' lifecycle.
4. F5a default-restrict `owners ⊆ parent.owners` may break existing examples/apps that legitimately spawn children with new owners. Need a sweep of current spawn-using apps before flipping the default.
5. Whether to gate `_emit` `destination` routing (R-F1b) depends on what `destination` actually routes to off-chain — out of scope for this read-only investigation; needs the consumer-side contract.

## Risks

1. Carrying the policy in `metadata` means it is only as tamper-evident as #37 binding: an UNBOUND fiber (schemaBinding=None) has no logicHash anchor, so the metadata key is self-asserted. Self-repro children now inherit schemaBinding (A.5), but a top-level self-repro fiber created unbound is only trustworthy once bound. Document that the observer must check the fiber is bound.
2. The upgrade latch adds a new rejection class to UpgradeFiber; if any existing self-reproducing-flagged fiber legitimately needs to upgrade away, this blocks it. Mitigated by the flag being net-new (no existing fiber sets it), but the rule is permanent once shipped.
3. C1's `sourceFiberId` surfacing changes the cascade evaluation context shape; any guard or app currently relying on the exact absence of that key (unlikely) could shift behavior. New key, default-absent, so low risk — but cascade tests must confirm no existing example regresses.
4. F1 changing EmittedEvent shape (adding emitterFiberId) is a serialization-surface change for any off-chain consumer parsing events; coordinate with downstream. The (UUID, EmittedEvent) keyed-pair alternative avoids changing EmittedEvent's own codec but changes the engine result type.
5. maxSpawnsPerTransition / maxEmittedEvents are consensus-affecting limits: every node must agree on the same value or they fork. Must be added to ExecutionLimits with a fixed default and rolled out atomically, not per-node configurable.
6. If the codec does NOT canonicalize Map key order as assumed (verified for JsonBinaryCodec RFC-8785, but StateMachineDefinition.states is a Map[StateId, State] whose StateId key encoding must be a JSON object key/string), a reordered-states definition could produce a different digest and a spurious SelfReproductionViolation. The reordered-states-Map PASS test (A.7 #3) is the guard; if it fails, document states as order-sensitive.
