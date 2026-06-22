# FiberPolicy

> The fiber's hash-pinned constitution.

A **FiberPolicy** is the set of invariants an external observer can verify about a
fiber by reading **one** `logicHash`-anchored field — without trusting the fiber's
operators, its history, or any off-chain attestation. The fiber voluntarily
surrenders power; in exchange it earns a guarantee that anyone can check by
resolving a single hash. Because the policy is part of the hash-pinned definition,
it cannot drift: the constitution an observer reads *is* the constitution the engine
enforces.

## The two layers: ENGINE-DEFAULT vs OPT-IN

FiberPolicy spans two distinct layers that must not be confused:

- **ENGINE-DEFAULT (always-on, not dials).** Unconditional correctness the engine
  enforces for *every* fiber, with no opt-out: emit emitter-stamping, surfacing the
  calling fiber as `$caller` in the guard context, and DoS fan-out bounds. These are
  not part of any fiber's voluntary constitution — they are the substrate the
  constitution sits on. See the `engine-default-fixes` stream.
- **OPT-IN (the dials).** A fiber chooses to *give up* a capability (restrict its
  effects, lock its upgrade path, allowlist its callers/recipients, freeze itself)
  in return for a verifiable guarantee. Default = no policy = today's unconstrained
  behavior. See the `fiberpolicy-dials` and `version-compat-family` streams.

## The hard invariant: TIGHTEN-ONLY across migration

A policy may only ever **tighten** across a migration — never loosen. Without this
the guarantee is worthless: an observer who reads "this fiber can only emit, never
spawn" must be able to trust that a later upgrade cannot quietly re-grant the spawn
capability. The engine enforces a per-dial partial order at the migration site and
**aborts** (total discard, `FailureReason.PolicyViolation`) on any loosening. An
absent old policy is treated as fully-unconstrained, so the first policy a fiber
adopts is always a valid tightening from "anything goes."

## The carrier: typed `policy: Option[FiberPolicy]` (greenfield-chosen)

The canonical carrier across all FiberPolicy streams is a **typed
`policy: Option[FiberPolicy]` field on `StateMachineDefinition`**, not a reserved
key inside `metadata`.

Earlier specs (engine-hardening §A.2, and this family's `version-compat-family`
stream §0/D1) chose the metadata-reserved-key carrier *solely* to avoid the B3
malleability footgun — that `dropNulls` drops `None` but a `Some(FiberPolicy(default))`
serializes to a non-null object, so `None ≠ Some(default)` under canonicalization,
which would have broken the #37 verified binding (`definition.computeDigest === logicHash`)
for any legacy fiber whose digest had to stay byte-identical.

**OttoChain is greenfield.** There is no backward-compatibility or legacy-hash-stability
requirement, so the *only* reason for the metadata workaround is gone. The
`fiberpolicy-dials` stream further showed the typed field is `logicHash`-stable anyway.
The typed field is the cleaner, ergonomically-superior model, and it is what every
stream below now targets. (The engine-hardening doc's §A.2 carries a SUPERSEDED
callout pointing here.)

### The normalization invariant is retained — independent of back-compat

One rule survives the greenfield change unchanged and remains **mandatory**:

> An all-default `FiberPolicy` is canonically identical to an absent one
> (`Some(FiberPolicy.empty) ⇒ None`, realized via `dropNulls` + a normalizing
> decoder/encoder).

This is **not** a back-compat concession — it is the internal-determinism rule that
lets the verified re-bind `definition.computeDigest === logicHash` succeed regardless
of which client (chain, SDK, or third-party) wrote the definition. Two definitions
that mean the same thing must hash the same. Keep it, with fixtures regenerated where
a stream's older spec leaned on byte-identical *legacy* hashes.

---

This document consolidates three completed design streams. Each `##` section gives a
one-line summary, then the stream's full design spec verbatim, then its
implementation checklist, open questions, and risks.


## fiberpolicy-dials

> Opt-in, hash-stable, tighten-only FiberPolicy dials on StateMachineDefinition; all BLOCKER/HIGH review findings fixed (conserveValue dropped, record flags made Option, empty-policy normalization, lineage/caller bound or rejected).


# FiberPolicy — opt-in self-tightening dials (stream: fiberpolicy-dials) — FINAL

Implementation-ready. Incorporates the adversarial review: BLOCKERs B1–B3 and HIGHs H1–H4 are fixed; MEDIUM/LOW are addressed or consciously deferred (noted inline). All code claims re-verified against canonical `/home/euler/repos/ottochain` (+ `-sdk`, + metakit) on this branch.

## 0. Verified ground truth (re-checked this pass)

- `StateMachineDefinition = {states, initialState, transitions, metadata: Option[JsonLogicValue] = None}` — `modules/models/src/main/scala/xyz/kd5ujc/schema/fiber/StateMachineDefinition.scala:11-15`. No `policy` field exists; this stream adds it. `@derive(customizableEncoder, customizableDecoder)` + `import CodecConfiguration._` (`:5,10`).
- `dropNulls` drops only object fields whose value `isNull` — `JsonBinaryCodec.scala:110-122` (metakit). **Confirmed: a JSON `false` is NOT dropped** (`!v.isNull` keeps it). An empty object `{}` is also NOT dropped (not null). This is the basis of B2/B3 fixes.
- `serialize` = `dropNulls ∘ asJson` then canonicalize (`JsonBinaryCodec.scala:135-141`); digest = `Hash.fromBytes(serialize)` (`JsonBinaryHasher.scala:18-22`); `useDefaults = true` (`CodecConfiguration.scala:18`). ⇒ a legacy definition with no `policy` key decodes to `policy = None`, re-encodes with the key dropped, hashes byte-identically. **`policy: Option[FiberPolicy] = None` is logicHash-stable for every existing fiber.**

  > **Greenfield note:** Greenfield removes any *requirement* that legacy `logicHash` values stay byte-identical — the typed `policy: Option[FiberPolicy]` carrier is chosen on ergonomic merit (see the intro), and its hash-stability here is a welcome *bonus* rather than the deciding constraint. The all-default `FiberPolicy` ≡ absent normalization (`Some(empty) ⇒ None` via `dropNulls`) is **still required** — it is the internal-determinism rule that lets the verified re-bind `definition.computeDigest === logicHash` succeed regardless of which client wrote the definition, independent of any back-compat question.
- `logicHash` IS the version: verified re-bind checks `definition.computeDigest === rv.logicHash` (`FiberCombiner.scala:303-304`).
- `FiberEffect.AssetTransferred(assetId: UUID, recipient: AssetHolder)` — **NO amount** (`FiberEffect.scala:45`). Transfers are whole-record custody moves (`AssetCombiner.applyFiberTransfer`, `holder := recipient`). ⇒ B1: `conserveValue` is a category error; **dropped**.
- `StateMachineFiberRecord` is hashed into `CalculatedState` → committed root. It has trailing defaulted fields, all `Option`/collection-with-default (`Records.scala:36-48`). Adding a **non-Option `Boolean = false`** would serialize as `"paused":false` (not dropped) and change every record's calculated-state hash. ⇒ B2 fix: record flags must be `Option`.
- `DependencyLedger.step` keys new-vs-existing off `ledger.indexWhere(_.fiberId == m.fiberId)` (`DependencyLedger.scala:36`), NOT directive type. ⇒ M3 fix.
- Cascade path drops `spawns`/`dependencyMutations`: `TriggerHandler.scala:86` calls `FiberEvaluator.evaluate(sm, trigger.input, List.empty)`; comment at `:87-90` confirms those directives are honoured only on the PRIMARY transition. ⇒ H4 fix.
- `SpawnValidator.validateSpawns(directives, parent, knownFibers: Set[UUID], contextData)` — receives only an id **set**, not ancestor records (`SpawnValidator.scala:39-45`). `calculatedState` is in scope only in the engine (`FiberEngine.scala:528`, `knownFibers = calculatedState.stateMachines.keySet ++ calculatedState.scripts.keySet`). ⇒ H2 fix: ancestor walk must move to the engine or be plumbed in.
- Migration re-bind: `FiberCombiner.upgradeFiber` (`:188-267`) enforces monotonic version advance + verified re-bind, drives `FiberEngine.migrateStateMachine`. The engine site at `:258-269` has `sm.definition` (OLD) and `newDefinition` (NEW) both in scope. ⇒ tighten-only check site.
- `FailureReason` has NO `PolicyViolation` variant today (`FailureReason.scala:68-96`). **This stream adds one** (see §2.0).
- `migrateStateMachine` clears to `aborted(reason)` on a `Left` (`FiberEngine.scala:263-264`); abort = total discard.

## 1. Architecture: ENGINE-DEFAULT vs OPT-IN (unchanged from design, retained)

- **ENGINE-DEFAULT (always-on, NOT dials; sibling streams):** source-stamping `_emit`, surfacing `sourceFiberId` as a `caller` key in `buildStateMachineContext`, existing DoS bounds (`ExecutionLimits.scala:21-28`). A fiber cannot opt out.
- **OPT-IN (THIS stream):** a fiber voluntarily surrenders power, earning a guarantee an external party verifies by reading one hash-pinned field. Default `policy = None` ⇒ legacy behavior, hash-identical to today. Every dial is `Option`/defaulted so `dropNulls` keeps a partial policy stable.
- **Hard invariant:** a policy may only ever TIGHTEN across migration (§4). Without it the guarantee is worthless.

## 2. The FiberPolicy ADT (chain-side, Scala) — REVISED

### 2.0 New `FailureReason.PolicyViolation`
Add to `modules/models/src/main/scala/xyz/kd5ujc/schema/fiber/FailureReason.scala` (alongside `:68-96`):
```scala
case class PolicyViolation(dial: String, detail: String) extends FailureReason
```
Wire it into the same `@derive`/circe discriminator block as the other variants. Every dial below fails via this.

### 2.1 New file `modules/models/.../fiber/FiberPolicy.scala`
```scala
package xyz.kd5ujc.schema.fiber

import java.util.UUID
import io.constellationnetwork.schema.address.Address
import xyz.kd5ujc.schema.CodecConfiguration._
import enumeratum._
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/** The 5 directive families EffectExtractor scrapes (EffectExtractor.scala:84-88). */
sealed trait EffectKind extends EnumEntry with EnumEntry.Uppercase
object EffectKind extends Enum[EffectKind] with CirceEnum[EffectKind] {
  val values = findValues
  case object Trigger    extends EffectKind  // _triggers + _scriptCall
  case object Spawn      extends EffectKind  // _spawn
  case object Emit       extends EffectKind  // _emit
  case object Transfer   extends EffectKind  // _transferAsset
  case object Dependency extends EffectKind  // _add/_setDependencyActive
}

sealed trait SpawnOwnerPolicy extends EnumEntry with EnumEntry.Uppercase
object SpawnOwnerPolicy extends Enum[SpawnOwnerPolicy] with CirceEnum[SpawnOwnerPolicy] {
  val values = findValues
  case object InheritParent  extends SpawnOwnerPolicy // child.owners forced == parent.owners
  case object SubsetOfParent extends SpawnOwnerPolicy // child.owners ⊆ parent.owners
  case object Explicit       extends SpawnOwnerPolicy // any (current behavior; loosest)
}

sealed trait DependencyMode extends EnumEntry with EnumEntry.Uppercase
object DependencyMode extends Enum[DependencyMode] with CirceEnum[DependencyMode] {
  val values = findValues
  case object Open      extends DependencyMode
  case object Allowlist extends DependencyMode
  case object Frozen    extends DependencyMode
}

/** B1 FIX: no `conserveValue` — AssetTransferred carries no amount. Recipient allowlist only. */
@derive(customizableEncoder, customizableDecoder)
final case class TransferPolicy(
  allowedRecipientFibers:  Option[Set[UUID]]    = None,
  allowedRecipientWallets: Option[Set[Address]] = None
)

@derive(customizableEncoder, customizableDecoder)
final case class DependencyPolicy(
  mode:    DependencyMode    = DependencyMode.Open,
  allowed: Option[Set[UUID]] = None // meaningful iff mode == Allowlist
)

@derive(customizableEncoder, customizableDecoder)
final case class FiberPolicy(
  selfReproducing:  Option[Boolean]          = None,  // Dial #1 (already designed; carried)
  allowedEffects:   Option[Set[EffectKind]]  = None,  // None ⇒ all families (legacy)
  spawnOwnerPolicy: Option[SpawnOwnerPolicy] = None,
  maxGenerations:   Option[Int]              = None,  // same-definition-hash spawn-lineage depth
  maxSpawnFanout:   Option[Int]              = None,  // children per single transition
  acceptedCallers:  Option[Set[UUID]]        = None,  // GATED: see §3.3 / H1
  sealedStates:     Option[Set[StateId]]     = None,
  transferPolicy:   Option[TransferPolicy]   = None,
  dependencyPolicy: Option[DependencyPolicy] = None,
  pausable:         Option[Boolean]          = None,  // authorizes PauseFiber op (§3.7)
  freezeAuthority:  Option[Set[Address]]     = None   // authorizes FreezeFiber op (§3.7)
) {
  /** B3 FIX: an all-default policy is semantically None. */
  def isEmpty: Boolean =
    selfReproducing.isEmpty && allowedEffects.isEmpty && spawnOwnerPolicy.isEmpty &&
    maxGenerations.isEmpty && maxSpawnFanout.isEmpty && acceptedCallers.isEmpty &&
    sealedStates.isEmpty && transferPolicy.isEmpty && dependencyPolicy.isEmpty &&
    pausable.isEmpty && freezeAuthority.isEmpty
}

object FiberPolicy {
  val empty: FiberPolicy = FiberPolicy()
  /** Normalize Some(empty) ⇒ None so the canonical form matches legacy (B3). */
  def normalize(p: Option[FiberPolicy]): Option[FiberPolicy] = p.filterNot(_.isEmpty)
}
```

### 2.2 `StateMachineDefinition` change (hash-stable per §0)
```scala
// StateMachineDefinition.scala:11-15
case class StateMachineDefinition(
  states:       Map[StateId, State],
  initialState: StateId,
  transitions:  List[Transition],
  metadata:     Option[JsonLogicValue] = None,
  policy:       Option[FiberPolicy]    = None   // NEW
)
```
**B3 normalization on the chain (load-bearing):** in `StateMachineDefinition`'s decoder, post-process `policy` through `FiberPolicy.normalize` so a wire `"policy":{}` (or any all-default object) decodes to `None`. Two equivalent implementations — pick one and pin in a test:
- (a) a custom `Decoder` (wrap the derived one): `.map(d => d.copy(policy = FiberPolicy.normalize(d.policy)))`; OR
- (b) keep the derived decoder but normalize at the single ingestion boundary where definitions enter (`createStateMachineFiber` and `upgradeFiber` both read `update.newDefinition` / the create definition — normalize there before `computeDigest` is taken).
**Preferred: (a)**, because `computeDigest` is taken in multiple places and (a) makes the digest of `Some(empty)` and `None` provably identical everywhere, closing B3 regardless of SDK discipline. Add a custom `Encoder` symmetry too: emit no `policy` key when `normalize` yields `None`, so re-serialize of a normalized definition is byte-identical to legacy (belt-and-suspenders; `dropNulls` already removes the `None`).

### 2.3 Record flags — B2 FIX (Option, not Boolean)
For `pausable`/`freezeAuthority` runtime state, add to `StateMachineFiberRecord` (`Records.scala:36-48`), as trailing **Option** defaults so `dropNulls` keeps legacy records byte-identical:
```scala
    pausedSince:  Option[SnapshotOrdinal] = None,  // Some ⇒ paused at that ordinal
    frozenSince:  Option[SnapshotOrdinal] = None   // Some ⇒ frozen at that ordinal
```
`None ⇒ "pausedSince" key absent ⇒ dropped by dropNulls ⇒ every existing record hashes identically.` Using `Option[SnapshotOrdinal]` (not `Option[Boolean]`) also records *when*, which observers/audits want. (M1/M3-record-interaction: see §3.7.)

> **Greenfield note:** OttoChain is greenfield — there is no calculated-state-hash-stability requirement, so the `Option` form is no longer *forced* by back-compat. Keep it anyway when it is the cleaner model (it records *when* a fiber paused/froze, which audits want); a plain `Boolean = false` is also acceptable now, with record fixtures regenerated. The hash-stability this paragraph cites is now a *bonus*, not a constraint.

## 3. Per-dial enforcement (exact hook + fail mode) — REVISED

All read-points reach policy via `fiber.definition.policy` (record carries `definition` verbatim, `Records.scala:30`). Convention: `policy.flatMap(_.allowedEffects).forall(_.contains(k))` ⇒ **`None`/absent ⇒ no constraint**.

### 3.1 `allowedEffects`
- **Hook:** `FiberEvaluator.buildSuccessOutcome`, immediately after the `collect` partition (`FiberEvaluator.scala:246-250`). For each non-empty family present, if not in `allowedEffects` ⇒ return `FailureReason.PolicyViolation("allowedEffects", "<k> not permitted")` via the existing `.pureOutcome[G]` failure channel (`:264-266`). Fail-closed: abort = total discard (NOT the fail-silent extraction at `:138/:232/:295`, which would *strip* the directive — explicitly avoided).
- **H4 FIX (cascade coverage — corrected claim):** on cascade, `spawns` and `dependencyMutations` are structurally empty (`TriggerHandler.scala:86-90`). Therefore the **Spawn** and **Dependency** families can only ever be produced on the PRIMARY transition; the gate enforces them there. **Trigger / Emit / Transfer** are produced on both primary and cascade, so the gate (running in `buildSuccessOutcome`, which executes for both) enforces those on both. Spec states this explicitly; do NOT claim "identical coverage."

### 3.2 `spawnOwnerPolicy` + `maxSpawnFanout` + `maxGenerations`
- **Owners hook:** `SpawnValidator.evaluateOwners` (`SpawnValidator.scala:142-196`). After `resolvedOwners` is computed, before `ValidatedSpawn` is built (`:99-101`): `InheritParent` ⇒ force `resolvedOwners := parent.owners`; `SubsetOfParent` ⇒ `Validated.invalidNel(PolicyViolation("spawnOwnerPolicy", "owners not subset of parent"))` unless `⊆ parent.owners`; `Explicit`/`None` ⇒ unchanged. Invalid ⇒ `SpawnPlan` invalid ⇒ `processSpawnsValidated` `Left` ⇒ engine abort (`FiberEngine.scala:493-497`). Clean fail-closed.
- **Fan-out hook:** `SpawnValidator.validateBatchConstraints` (`:198-223`) — add `if spawns.size > maxSpawnFanout ⇒ invalidNel(PolicyViolation("maxSpawnFanout", …))`. Same abort path.
- **Generations hook — H2 FIX (moved to engine):** `validateSpawns` only has `knownFibers: Set[UUID]`; it cannot walk ancestor *records*. Compute the generation count in the **engine** at `FiberEngine.processSpawnsValidated` (where `calculatedState` is in scope, `:528-529`), BEFORE delegating to the validator, OR thread a precomputed `ancestorDepth: Int` (same-definition-hash lineage depth of `parent`) into `validateSpawns` as a new parameter. Definition: walk `parent.parentFiberId` through `calculatedState.stateMachines`, counting only ancestors whose `definition.computeDigest == parent.definition.computeDigest` (same self-reproduction notion as Dial #1). Reject the spawn if `depth >= maxGenerations`. **Incomplete-chain rule (H2 tie-break, must specify):** if an ancestor referenced by `parentFiberId` is absent from `calculatedState` (archived/pruned), **fail-closed**: treat the chain as un-verifiable and reject the spawn under a non-None `maxGenerations` (`PolicyViolation("maxGenerations", "ancestor chain incomplete")`). Rationale: a cap you cannot verify must not silently pass. Composes with (orthogonal to) `ExecutionLimits.maxDepth` (trigger-chain depth, different axis).

### 3.3 `acceptedCallers` — H1 FIX (gated both sides + registration-reject)
- **Unenforceable today:** `TriggerHandler.scala:86` passes `List.empty`; `ContextProvider.buildStateMachineContext` (`:147-178`) has no `caller` key. Depends on the engine-default stream to (a) thread `trigger.sourceFiberId` into `FiberEvaluator.evaluate` for cascades, (b) add a `caller` key, (c) guarantee `sourceFiberId` is engine-stamped/un-spoofable (mirror the `_emit` source-stamping the sibling stream lands).
- **Chain-side registration reject (NEW, H1):** until the engine can surface a *verified* caller, registering a definition whose `policy.acceptedCallers` is `Some(nonEmpty)` must be **rejected at create/upgrade** (`FiberCombiner.createStateMachineFiber` `:45-91` and `upgradeFiber` `:188-267`) with `PolicyViolation("acceptedCallers", "caller surfacing not available")`. Do NOT hash-pin a constitution the chain cannot enforce — an observer would read a guarantee that isn't real. Gate this behind a single feature flag (`FiberPolicyFeatures.callerSurfacingEnabled`) flipped on when the engine-default stream lands, so the reject becomes a no-op then.
- **Enforcement hook (once enabled):** `FiberEvaluator.evaluateStateMachine` pre-guard (`:86-101`): if `acceptedCallers = Some(set)` and resolved caller ∉ set ⇒ `PolicyViolation("acceptedCallers", …)` before the guard runs (prevents guard gas/effects for an unauthorized caller). Design choice (pinned): a non-empty `acceptedCallers` governs **fiber-origin** triggers only; user/wallet-origin transitions (caller = None) are governed by the existing `authorizedSigners`/proof path, NOT by `acceptedCallers`.
- **SDK gate:** the builder refuses to emit `acceptedCallers` until caller-surfacing lands (§5).

### 3.4 `sealedStates` — M2 addressed
- **Hook:** `FiberEvaluator.evaluateStateMachine` at the transition lookup (`:94-100`): before `transitionMap.get((currentState, eventName))`, if `sealedStates.contains(fiber.currentState)` ⇒ `PolicyViolation("sealedStates", "state <id> sealed")`.
- **M2 cascade routing (must specify):** on a PRIMARY transition this is an abort (correct for finality). On a CASCADE, the natural `NoTransitionFound`/`GuardFailed` is handled soft at `TriggerHandler.scala:123-129` (branch failure, not whole-tx abort). For finality semantics, a sealed-state hit during cascade should **abort the entire originating transaction**, not soft-halt the branch. Implementation: emit the distinct `PolicyViolation("sealedStates", …)` from the evaluator and have `TriggerHandler` route `PolicyViolation` to the fatal/abort path (not the soft cascade-fail path) — i.e. policy violations are never swallowed by the cascade soft-fail. Pin this in a cascade test.

### 3.5 `transferPolicy` — B1 FIX (recipient allowlist only; conservation removed)
- **Hook (recipient allowlist):** `AssetCombiner.applyFiberTransfer` (`:413-459`) — the authoritative R1 holder-defense site, the only place a fiber-held asset moves. Alongside the transferable/liveness checks (`:435-448`), reject with `CombineRejected` if the **emitting fiber's** `definition.policy.transferPolicy` disallows `transfer.recipient`. The emitting fiber's definition is read from `st.calculated.stateMachines.get(emitter)` (state is threaded in). Rejected transfer ⇒ `CombineRejected` ⇒ `Combiner.insert` records a `RejectionReceipt` and discards the whole update (`AssetCombiner.scala:376-377`, `FiberCombiner.scala:328-341`) — fail-closed and graceful.
- **No conservation check.** `AssetTransferred` has no amount (B1). `maxAssetMutations` (`AssetCombiner.scala:389-394`) already bounds custody-move *count*; that is the only quantitative bound and it is engine-default, not a dial.

### 3.6 `dependencyPolicy` — M3 FIX
- **Hook:** `FiberEngine.commitStateMachineSuccess`, immediately before `DependencyLedger.applyMutations` (`FiberEngine.scala:454`, applied FIRST/fail-closed `:454-462`). Filter `dependencyMutations`:
  - `Allowlist` ⇒ any mutation whose `fiberId ∉ allowed` ⇒ abort `PolicyViolation("dependencyPolicy", "<id> not in allowlist")`.
  - `Frozen` — **M3 FIX:** reject any mutation whose `fiberId` is **not already present** in `sm.dynamicDependencies`, matching the ledger's own `indexWhere(_.fiberId == m.fiberId)` branch (`DependencyLedger.scala:36`). Do NOT key off the directive keyword (`_addDependency` vs `_setDependencyActive`) — an `_addDependency` targeting an already-present id is an upsert/toggle and must be ALLOWED under `Frozen`. Predicate: `if (mode == Frozen && !sm.dynamicDependencies.exists(_.fiberId == m.fiberId)) abort`.
  - `Open`/`None` ⇒ unchanged.
- Fail mode: `Left(reason)` exactly like the existing bounds breach (`:460-461`).

### 3.7 `pausable` / `freezeAuthority` — M1 FIX (fully specified) — STREAM SCOPE DECISION
These require a new mutable-record op and a new authorization/validation surface. **Decision: descope the runtime ops to a follow-up stream; land only the policy fields + record flags + tighten-only in THIS stream**, OR implement fully per the spec below. The orchestrator/owner should choose. Full spec if implemented:
- **`PauseFiber` / `UnpauseFiber` update ADT** (new `FiberUpdate` variants): `PauseFiber(fiberId, proof)`, owner-signed (validate signer ∈ `record.owners`). Validator (in `FiberCombiner`): require `record.definition.policy.flatMap(_.pausable).contains(true)` else `PolicyViolation("pausable", "fiber not pausable")`; set `record.pausedSince = Some(currentOrdinal)`. Unpause clears to `None`. Reversible.
- **`FreezeFiber` update ADT**: `FreezeFiber(fiberId, proof)`, signer must be ∈ `record.definition.policy.flatMap(_.freezeAuthority)` (NOT necessarily an owner). Sets `record.frozenSince = Some(ordinal)`. One-way latch: only a successful `migrate` clears `frozenSince` (engine resets it on commit of `migrateStateMachine`).
- **Enforcement:** in `FiberEvaluator.evaluateStateMachine` transition-lookup short-circuit (`:94-100`), alongside `sealedStates`: if `record.pausedSince.isDefined` or `record.frozenSince.isDefined` ⇒ `PolicyViolation("paused"/"frozen", …)` abort.
- **Status interaction (M1 threat model, must state):** `pausedSince`/`frozenSince` are **orthogonal** to `FiberStatus.Active/Archived` (`FiberStatus.scala:11-13`). `Archived` is terminal; pause/freeze are not. Freeze defends against a *compromised-key / runaway-transition* scenario where some authority (held by `freezeAuthority`, possibly a guardian distinct from owners) halts transitions while still permitting an owner-driven `migrate` to a fixed definition (which clears freeze). If the owner *is* in `freezeAuthority` and can migrate-unfreeze, freeze is owner-reversible-by-migration and protects only against non-migration transitions — document this explicitly so observers don't over-trust freeze as an owner-proof halt.

## 4. TIGHTEN-ONLY (the trust anchor) — REVISED for lineage (H3)

`FiberPolicy.tightens(old: Option[FiberPolicy], neu: Option[FiberPolicy]): Either[String, Unit]` — new pure method, unit-testable like `DependencyLedger`. **Normalize both sides through `FiberPolicy.normalize` first** so `Some(empty)` is treated as `None`. `old = None` ⇒ any `neu` is a tightening from "unconstrained" (always OK). Per-dial partial order (`neu` must be ≥ `old` in restrictiveness):

| Dial | order (new vs old) | rationale |
|---|---|---|
| `selfReproducing` | `old ⇒ neu` (turn ON, never OFF) | once self-reproducing, stays |
| `allowedEffects` | `neu ⊆ old`; `None`→`Some` OK; `Some`→`None` rejected | only drop families |
| `spawnOwnerPolicy` | `Explicit ⊐ SubsetOfParent ⊐ InheritParent` (move down only) | tighter authority |
| `maxGenerations`/`maxSpawnFanout` | `neu ≤ old`; `None`→`Some` OK; `Some`→`None` rejected | caps only shrink |
| `acceptedCallers` | `neu ⊆ old`; `None`→`Some` OK | allowlist shrinks |
| `sealedStates` | `neu ⊇ old` | seal set only **grows** (opposite direction) |
| `transferPolicy.allowedRecipient*` | `neu ⊆ old`; `None`→`Some` OK | recipient sets shrink |
| `dependencyPolicy` | `Open ⊐ Allowlist ⊐ Frozen`; `Allowlist.allowed` shrinks | tighter |
| `pausable` | `false/None ⇒ true` only | gains pausability, never loses |
| `freezeAuthority` | `neu ⊆ old`; `None`→`Some` OK | rights only reduce |

- **Migration check site:** `FiberEngine.migrateStateMachine`, right after `evalMigration` succeeds and BEFORE the conformance gate (`FiberEngine.scala:263-269`). Both `sm.definition.policy` (OLD) and `newDefinition.policy` (NEW) are in scope here (verified). On violation: `aborted(FailureReason.PolicyViolation("tighten", "<dial> may only tighten"))` (`:263-264`) — total discard; the combiner records the abort as a failure receipt (`FiberCombiner.scala:264-266`).
- **`create`:** `FiberCombiner.createStateMachineFiber` (`:45-91`) sets the *initial* (loosest-allowed) policy unchecked (no prior to tighten against) — like Aptos publish-time `upgrade_policy`.
- **H3 FIX — lineage / spawn-time policy relationship (must decide; DECISION pinned here):** policies are **PER-FIBER, NOT lineage-inherited**. A constrained parent MAY spawn a child whose `policy = None`. This stream **does NOT** require `child.policy ⊆ parent.policy`. Consequences, stated explicitly so no observer is misled:
  - `allowedEffects`, `transferPolicy`, etc. constrain *only the fiber that declares them*, never its descendants.
  - An external observer reading a parent's `allowedEffects` MUST NOT infer anything about what the parent's *children* can do.
  - The lineage dials that DO bind children are exactly `spawnOwnerPolicy` (owner authority), `maxSpawnFanout` (per-transition fan-out), and `maxGenerations` (self-definition spawn depth) — these are enforced at spawn time (§3.2) and are the only lineage guarantees this stream provides.
  - (Deferred alternative, not in scope: a future `inheritPolicy: Boolean` dial could require `child.policy.tightens(parent.policy)` at spawn time. Explicitly out of scope; called out so it is a conscious omission, not a silent gap.)
- **Honest gap (carry to handoff):** commute law `migrate ∘ step = step ∘ migrate` is still NOT verified on-chain (`FiberLogEntry.scala:124-127`). Tighten-only guarantees the *policy envelope* is monotone, not that state semantics commute.
- **M4 (consumer pinning — doc gap, stated):** the engine does NOT re-resolve a producer's binding at trigger-dispatch (no `logicHash`/`schemaBinding` check in `triggers/`). The "external observer reads one hash-pinned field" model holds only if the **consumer** re-resolves the producer's `schemaBinding` and verifies it against the expected `logicHash`. The chain provides **monotonicity** (tighten-only), not call-time interface verification. State this in the handoff.

## 5. Chain-side vs SDK-builder surface — REVISED (B3, L1)

**Chain-side:** everything in §2–§4. The chain treats `policy` as part of the hashed constitution and enforces it; it does NOT trust the SDK. **B3 is closed on the chain by §2.2 normalization** regardless of SDK behavior.

**SDK-builder (`ottochain-sdk`):** wire shape that hashes into `logicHash` is `ProtoStateMachineDefinition` (`src/schema/fiber-app.ts:281-294`), projected by `toProtoDefinition` (`:308-348`).
- Extend `ProtoStateMachineDefinition` with `policy?: FiberPolicyJson`.
- **B3 SDK half:** `toProtoDefinition` must emit `policy` ONLY when at least one dial is set (omit the key entirely for an all-undefined policy, exactly as it deliberately omits `metadata` mechanically — but **L1 note:** `metadata` is omitted because it's a *different type* (FiberAppMetadata), not as a generic Option-omit precedent; `policy` is a *separate explicit builder input* since `FiberAppDefinition` has no policy concept; do not look for a `def.policy`). The chain normalization (§2.2) is the authoritative backstop; SDK omission is UX/perf.
- New `src/schema/fiber-policy.ts`: fluent `policyBuilder()` producing `FiberPolicyJson`, using the **exact UPPERCASE entry-name strings** (`"TRIGGER"`, `"INHERITPARENT"`, `"ALLOWLIST"` — entry name uppercased, NOT snake-case, matching `EnumEntry.Uppercase`; **L2:** pin spelling in a shared cross-language test). Mirror spellings exactly or circe decode fails the whole update (lesson at `fiber-app.ts` dependencies note).
- **Client-side tighten-lint:** a TS analog of `tightens` so an author who fetches the on-chain prior policy gets a lint error before submitting a migration the chain would abort. UX only; chain is authority.
- **H1 SDK gate:** the builder MUST refuse to emit `acceptedCallers` (or mark it experimental/throw) until engine-default caller-surfacing lands — matching the chain-side registration reject (§3.3).

## 6. Prior-art mapping

- **Aptos `upgrade_policy` (immutable/compatible/arbitrary)** → tighten-only order (§4); `selfReproducing`+`freezeAuthority` ≈ immutable-ward.
- **Sui `UpgradeCap` / Solana SPL freeze authority** → `freezeAuthority` (authority-held one-way latch, distinct from terminal `Archived`).
- **OZ Pausable / reinitializer** → `pausable` (reversible record-flag) vs migration (`reinitializer`).
- **CosmWasm cw2 `ensure_from_older_version`** → monotonic version-advance already at `FiberCombiner.scala:235-245`; policy tighten rides on top.
- **object-capabilities / ERC-7579** → `allowedEffects` (fiber surrenders capabilities it will never use).
- **eUTXO terminal scripts** → `sealedStates` (a halted machine). (`conserveValue`/value-preservation analogy dropped with B1.)
- **ERC-165 `supportsInterface`** → an observer reading `policy.allowedEffects` off the hash-pinned definition — introspect capabilities without executing (subject to M4: the observer must self-pin the producer version).

## 7. Security invariants (the contract)

1. **Hash-stability:** `policy = None` ⇒ key dropped ⇒ every legacy fiber's `definition.computeDigest` byte-identical ⇒ existing `logicHash` bindings resolve. Zero migration for legacy fibers.
2. **Empty-policy ≡ None (B3):** chain decoder normalizes `Some(empty)` ⇒ `None`; their digests are provably identical; the SDK cannot fork the hash by emitting `{}`.
3. **Record determinism (B2):** `pausedSince`/`frozenSince` are `Option[SnapshotOrdinal] = None` ⇒ absent ⇒ dropped ⇒ legacy calculated-state hash unchanged.
4. **Fail-closed everywhere:** every dial fails via `PolicyViolation` on an abort/reject path (total discard or `RejectionReceipt`); no dial silently strips a directive (extraction's fail-silent path is explicitly NOT used as a gate).
5. **Tighten-only:** policy may only become more restrictive across migration; checked in the gas-metered re-bind boundary; `create` sets the loosest-allowed baseline.
6. **No false lineage guarantee (H3):** non-lineage dials constrain only the declaring fiber; observers must not infer descendant behavior. Lineage is bounded only by `spawnOwnerPolicy`/`maxSpawnFanout`/`maxGenerations`.
7. **No unenforceable constitution (H1):** `acceptedCallers` cannot be registered until the engine can surface a verified caller.
8. **Consumer responsibility (M4):** the chain provides monotonicity, not call-time interface verification.

## 8. Test plan

Chain (scalatest, alongside existing combiner/ledger tests):
- **Hash-stability:** a legacy definition (no policy) and the same definition decoded→re-encoded after the field is added produce identical `computeDigest`. (Closes §0/§7.1.)
- **Empty-policy normalization (B3):** `computeDigest(def.copy(policy = Some(FiberPolicy.empty))) == computeDigest(def.copy(policy = None))`; a wire `"policy":{}` decodes to `policy = None`.
- **Record determinism (B2):** a `StateMachineFiberRecord` with `pausedSince = None` serializes byte-identically to the pre-field record; `CalculatedState` hash unchanged.
- **allowedEffects:** a transition emitting a forbidden family aborts (no commit, no receipt visible); a permitted family commits. Cascade test: Trigger/Emit/Transfer enforced on cascade; Spawn/Dependency structurally absent on cascade (H4).
- **spawnOwnerPolicy:** `InheritParent` forces parent owners; `SubsetOfParent` rejects a non-subset spawn (abort); `Explicit` unchanged.
- **maxSpawnFanout:** N+1 spawns in one transition aborts.
- **maxGenerations (H2):** depth-cap reject; incomplete-ancestor-chain ⇒ fail-closed reject; enforced from the engine site with `calculatedState` in scope.
- **sealedStates (M2):** sealed-state primary aborts; sealed-state hit during cascade aborts the WHOLE originating transaction (routed to fatal, not soft cascade-fail).
- **dependencyPolicy (M3):** `Allowlist` rejects non-allowlisted add; `Frozen` rejects a *new* fiberId but ALLOWS a toggle of an already-present fiberId (keyed on `dynamicDependencies` membership, not directive keyword).
- **transferPolicy (B1):** transfer to a disallowed recipient ⇒ `CombineRejected` + `RejectionReceipt`, whole update discarded; allowed recipient commits. (No conservation test — field removed.)
- **acceptedCallers (H1):** registering a definition with non-empty `acceptedCallers` while `callerSurfacingEnabled = false` is rejected at create AND upgrade.
- **tightens (§4):** table-driven, one case per dial per direction (tighten OK, loosen rejected, `None`→`Some` OK, `Some`→`None` rejected where applicable; `sealedStates` grows; `dependencyPolicy`/`spawnOwnerPolicy` lattice). Migration loosening aborts with `PolicyViolation("tighten", …)`.
- **pausable/freeze (if in scope, M1):** owner-gated `PauseFiber` flips `pausedSince` only when `pausable=Some(true)`; paused fiber aborts transitions; `FreezeFiber` requires signer ∈ `freezeAuthority`; only `migrate` clears `frozenSince`; orthogonality to `Archived`.

SDK (vitest):
- `policyBuilder()` with nothing set ⇒ `toProtoDefinition` omits `policy` (B3 SDK half).
- Enum-string round-trip: every TS enum string equals the Scala `EnumEntry.Uppercase` entry name (shared fixture, L2).
- TS `tightens` lint mirrors the Scala table.
- Builder refuses `acceptedCallers` while gated (H1).

## 9. Files to touch (implementation map)

**New (chain):** `modules/models/src/main/scala/xyz/kd5ujc/schema/fiber/FiberPolicy.scala` (§2.1, with `EffectKind`/`SpawnOwnerPolicy`/`DependencyMode`/`TransferPolicy`/`DependencyPolicy`/`FiberPolicy`/`tightens`/`normalize`).
**New (SDK):** `src/schema/fiber-policy.ts` (builder + TS enums + tighten-lint, §5).

**Edit (chain):**
- `FailureReason.scala:~96` — add `PolicyViolation(dial, detail)` (§2.0).
- `StateMachineDefinition.scala:11-15` — add `policy`; add normalizing custom Decoder/Encoder (§2.2).
- `Records.scala:36-48` — add `pausedSince`/`frozenSince: Option[SnapshotOrdinal] = None` to `StateMachineFiberRecord` (§2.3, B2).
- `FiberEvaluator.scala:94-100` — sealedStates + paused/frozen short-circuit + acceptedCallers pre-guard; `:246-250` — allowedEffects gate (§3.1/3.3/3.4/3.7).
- `SpawnValidator.scala:142-196` — spawnOwnerPolicy; `:198-223` — maxSpawnFanout (§3.2).
- `FiberEngine.scala:528-529`/`processSpawnsValidated` — maxGenerations ancestor walk (H2); `:454` — dependencyPolicy pre-filter (§3.6, M3); `:263-269` — tighten-only check at migrate (§4).
- `AssetCombiner.scala:413-459` — transferPolicy recipient allowlist (§3.5, B1; conservation removed).
- `FiberCombiner.scala:45-91` (create: set baseline policy, normalize) and `:188-267` (upgrade: normalize + acceptedCallers registration reject H1); `TriggerHandler.scala:123-129` — route `PolicyViolation` to fatal/abort path (M2).
- (If pause/freeze in scope) new `PauseFiber`/`FreezeFiber` `FiberUpdate` variants + validators in `FiberCombiner` (§3.7, M1).

**Edit (SDK):** `src/schema/fiber-app.ts:281-294` (add `policy?` to `ProtoStateMachineDefinition`), `:308-348` (emit `policy` only when non-empty in `toProtoDefinition`, B3).

## 10. Hash-stability & migration (final)

- Legacy fibers: `policy = None` dropped ⇒ digest byte-identical ⇒ no re-registration, no migration. Verified against `useDefaults = true` and `dropNulls`.
- Adopting a policy is a definition change ⇒ new `logicHash` ⇒ new registered version through `upgradeFiber`'s verified re-bind + monotonic advance (`FiberCombiner.scala:224-245`) + the tighten-only gate (§4). Every later policy edit is forced monotone-tighter or aborts.
- `Some(empty) ≡ None` by normalization (§2.2) ⇒ the SDK cannot accidentally fork the chain.
- Record flags `Option` ⇒ calculated-state hash unchanged for in-flight state (B2).
- Open gap carried to handoff: commute-law unverified (`FiberLogEntry.scala:124-127`); consumer-side version pinning is the consumer's responsibility (M4).


### Implementation checklist

- Add FailureReason.PolicyViolation(dial: String, detail: String) and wire its circe codec (FailureReason.scala:~96)
- Create modules/models/.../fiber/FiberPolicy.scala: EffectKind/SpawnOwnerPolicy/DependencyMode enums (EnumEntry.Uppercase + CirceEnum), TransferPolicy (NO conserveValue), DependencyPolicy, FiberPolicy with isEmpty, FiberPolicy.empty/normalize, and the tightens partial-order method
- Add policy: Option[FiberPolicy] = None to StateMachineDefinition (line 11-15) AND a normalizing custom Decoder (Some(empty) => None) + symmetric Encoder so computeDigest(Some(empty)) == computeDigest(None)
- Add pausedSince/frozenSince: Option[SnapshotOrdinal] = None to StateMachineFiberRecord (Records.scala:36-48) — Option, NOT Boolean (B2)
- allowedEffects gate after the collect partition in FiberEvaluator.buildSuccessOutcome (FiberEvaluator.scala:246-250); document cascade coverage (Spawn/Dependency primary-only; Trigger/Emit/Transfer both) (H4)
- sealedStates + paused/frozen short-circuit + acceptedCallers pre-guard in FiberEvaluator.evaluateStateMachine (FiberEvaluator.scala:94-100)
- Route FailureReason.PolicyViolation to the fatal/abort path in TriggerHandler (around :123-129) so sealed-state during cascade aborts the whole tx (M2)
- spawnOwnerPolicy in SpawnValidator.evaluateOwners (:142-196); maxSpawnFanout in validateBatchConstraints (:198-223)
- maxGenerations ancestor-depth walk in FiberEngine.processSpawnsValidated where calculatedState is in scope (:528-529); fail-closed on incomplete ancestor chain (H2)
- dependencyPolicy pre-filter before DependencyLedger.applyMutations (FiberEngine.scala:454); Frozen keys off sm.dynamicDependencies membership not directive keyword (M3)
- transferPolicy recipient allowlist in AssetCombiner.applyFiberTransfer (:413-459), reading emitting fiber policy via st.calculated.stateMachines.get(emitter); no conservation check (B1)
- tighten-only check in FiberEngine.migrateStateMachine after evalMigration, before conformance gate (:263-269), normalize both sides, abort with PolicyViolation(tighten, ...) on loosening
- Set baseline policy (normalized) on create in FiberCombiner.createStateMachineFiber (:45-91); normalize on upgrade (:188-267)
- acceptedCallers registration reject (create + upgrade) gated by FiberPolicyFeatures.callerSurfacingEnabled flag (H1)
- Decide pause/freeze scope: either implement PauseFiber/UnpauseFiber/FreezeFiber update ADTs + validators (owner-gated pause, freezeAuthority-gated freeze, only migrate clears frozen) or descope to follow-up (M1)
- SDK: add policy?: FiberPolicyJson to ProtoStateMachineDefinition (fiber-app.ts:281-294); emit policy only when non-empty in toProtoDefinition (:308-348) (B3)
- SDK: create src/schema/fiber-policy.ts fluent policyBuilder with exact UPPERCASE enum strings, client-side tighten-lint, and acceptedCallers gate
- Tests: hash-stability, empty-policy normalization, record determinism, each dial enforce+abort, tighten table per dial/direction, cascade routing, SDK enum round-trip + omit-when-empty

### Open questions

- Pause/freeze runtime ops (PauseFiber/FreezeFiber update ADTs): land in THIS stream or descope to a follow-up? The policy fields + record flags + tighten-only can land now; the new combiner ops are a larger surface.
- Confirm the exact place to register the FiberPolicyFeatures.callerSurfacingEnabled flag (config vs hard-coded false until the engine-default caller-surfacing stream lands) and who flips it.
- Should the normalizing decoder live as a custom Decoder on StateMachineDefinition (option a) or at the create/upgrade ingestion boundaries (option b)? Spec recommends (a); confirm no other computeDigest call sites would bypass (b).
- freezeAuthority threat model: is the intended authority a guardian distinct from owners, or may owners hold it (making freeze owner-reversible-by-migration)? This changes the observable guarantee.
- Cross-language enum spelling: pin EnumEntry.Uppercase entry-name (INHERITPARENT) vs switching to UpperSnakecase (INHERIT_PARENT) — choose one before the SDK builder is written.

### Risks

- If the chain-side Some(empty) => None normalization is NOT added (only SDK omission), an SDK or third-party client emitting policy:{} forks the logicHash and hard-fails verified re-bind (B3). The normalizing decoder is load-bearing.
- If record flags ship as Boolean = false instead of Option (B2), every existing StateMachineFiberRecord's calculated-state hash changes -> chain split for in-flight state.
- maxGenerations ancestor walk depends on the parent chain being present in calculatedState; archived/pruned ancestors force a fail-closed reject which could surprise authors who expected the spawn to succeed (document behavior).
- acceptedCallers binds to a self-asserted sourceFiberId; if the engine-default stream does not stamp it un-spoofably, enabling the flag would let a fiber forge its caller identity. Keep the registration reject until stamping is verified.
- Lineage decision (H3: policies are per-fiber, not inherited) must be documented prominently; an observer over-trusting a parent's allowedEffects to bound children would be misled.
- Commute-law (migrate o step = step o migrate) remains unverified; tighten-only constrains the envelope only — a migration could still alter state semantics in a way observers don't expect.
- If pause/freeze ops are implemented, the new FiberUpdate variants add a signing/validation surface that must be covered by access-control tests or they become an auth bypass vector.


---

## version-compat-family

> Policy carried in definition.metadata (not a typed field) per the engine-hardening decision; map-form semver projection (JLVM has no int-index get); always-present fail-closed cross-fiber projection; gate threads verified proofs; version gate bound to verified schemaBinding.version, not self-declared.

# FiberPolicy — Version & Compatibility Family — FINAL Implementation-Ready Spec

Stream `version-compat-family`. Built ATOP the migration + conformance system. This revision fixes every BLOCKER/HIGH from the adversarial review and addresses MEDIUM/LOW. All file:line claims below were re-verified against `/home/euler/repos/ottochain` and `-sdk` and `/tmp/mk`.

## 0. The two decisions that reshape the original design

**D1 (resolves B1): Policy lives in `StateMachineDefinition.metadata`, NOT a new typed `policy: Option[FiberPolicy]` field.** This is mandatory, not optional:
- The engine-hardening spec already decided this explicitly: `docs/design/engine-hardening-spawn-and-effects.md` §A.2 — *"Decision: carry the policy inside the existing `StateMachineDefinition.metadata` field under a reserved key, NOT a new typed `policy: Option[FiberPolicy]` field"* — citing the B3 malleability footgun: `dropNulls` drops `None`, but `Some(FiberPolicy(default))` serializes to a non-null object, so `None ≠ Some(default)` under canonicalization, silently breaking #37 verified binding (`definition.computeDigest === logicHash`).
- The SDK already enforces this convention: `src/schema/fiber-app.ts:340-345` deliberately keeps chain `metadata` ABSENT (`None`) unless a caller sets `ProtoStateMachineDefinition.metadata` explicitly. A typed field would fight this.
- `StateMachineDefinition` is `{states, initialState, transitions, metadata: Option[JsonLogicValue] = None}` (`modules/models/src/main/scala/xyz/kd5ujc/schema/fiber/StateMachineDefinition.scala:11-16`). `metadata` is already hash-pinned by #37. The policy is read from `metadata` as a `MapValue` under reserved keys; ABSENCE = "today's behavior" (Arbitrary, non-self-reproducing). No struct change to `StateMachineDefinition` at all — so legacy digests are byte-identical with zero codec risk, and there is no malleability surface (M4 also evaporates — no sealed-trait magnolia encoding inside the hashed definition).

> **Greenfield note:** This consolidated doc supersedes D1's *carrier* decision. OttoChain is greenfield — there is no requirement that legacy digests stay byte-identical, which was the load-bearing reason D1 cited for keeping the policy in `metadata` rather than a typed field. The `fiberpolicy-dials` stream independently verified that a typed `policy: Option[FiberPolicy]` is `logicHash`-stable anyway (via the `Some(empty) ⇒ None` normalization), so the canonical carrier across all FiberPolicy streams is now the **typed `policy: Option[FiberPolicy]` field** (see this doc's intro and the SUPERSEDED note in `engine-hardening-spawn-and-effects.md` §A.2). The cleaner breaking form is acceptable, with fixtures regenerated. The all-default ≡ absent normalization (`dropNulls`) is **retained** — still required for the verified re-bind's internal determinism, independent of back-compat. The version/interface keys this stream designs map onto the typed `FiberPolicy` rather than reserved metadata keys; the gate logic, fail-closed projection, and tighten-only lattice below are otherwise unchanged.

**D2 (resolves B2): The semver projection into the eval context is a MAP `{major,minor,patch}` of ints, NEVER an array.** Verified: `handleGetOp` (`/tmp/mk/io/constellationnetwork/metagraph_sdk/json_logic/semantics/JsonLogicSemantics.scala:892-914`) matches ONLY `MapValue(v) :: StrValue(k)` (and the 3-arg fallback form). Integer-indexed array access falls to `case _ => JsonLogicException` — it raises, it does not fail-closed. The array form is deleted from the design entirely.

### Verified ground truth (file:line)
- migrate path: `FiberEngine.migrate` (`FiberEngine.scala:61-66`, no `proofs` param today) → `migrateInternal` → `migrateStateMachine` (`:249-319`); the gate insertion point is the top of `migrateStateMachine` at `:254`, BEFORE `evalMigration`; `aborted(reason)` there commits no log/state (UpgradeReceipt append + `sm.copy` are at `:273-290`) — fail-closed confirmed.
- combiner: `FiberCombiner.upgradeFiber(Signed[Updates.UpgradeFiber])` (`:188-189`); monotonic-version `SemVer.ordering.gt` (`:235-245`); same-package (`:212-222`); `orchestrator.migrate(...)` call at `:255`; addresses already resolved via `update.proofs.toList.traverse(_.id.toAddress).map(Set.from)` (`:49`); the `process` path already threads `proofsList = update.proofs.toList` into `orchestrator.process(..., proofsList)` (`:124,134`) — the exact pattern to mirror for migrate. Dispatch wraps proofs: `fiberCombiner.upgradeFiber(Signed(u, update.proofs))` (`Combiner.scala:71`).
- summary projection: `buildFiberSummary` (`ContextProvider.scala:283-296`) projects only `{state, currentStateId, sequenceNumber}` (+`machineId` when `includeId`); reused for machines/parent/children. Context keys live in `modules/models/.../schema/fiber/ReservedKeys.scala` (`STATE=63`, `MACHINE_ID=65`, `CURRENT_STATE_ID=66`, `SEQUENCE_NUMBER=67`, `MACHINES=75`).
- conformance: `ConformanceChecker.check` keys declared fields by `f.name` (`ConformanceChecker.scala:32`); `violationsFor(Some(newBinding), state, m)` already runs in `migrateStateMachine` at `FiberEngine.scala:269`; `strictVersion` resolves a `RegisteredVersion` via `b.name`/`b.version`, filtered `.strict`. `MachineShape = {stateMessage: MessageShape, commands: SortedMap[String, MessageShape]}` (`SchemaShape.scala:44-46`), `allMessages` helper at `:50`. `FieldShape = {name, number, typeName, repeated, optional}` (`SchemaShape.scala:24-30`) — has `optional`.
- JLVM ops (verified at `JsonLogicSemantics.scala`): `handleInOp` (`:626-651`) — `NullValue :: _` returns `false`; `prim :: StrValue` does SUBSTRING; `v :: ArrayValue` does membership; `v :: NullValue` (second arg null) → `case _` EXCEPTION. `handleGetOp` map-only (D2). `KNOWN_OPERATORS` includes `if has in or and == > >= get var some map` — no semver compare.
- validator: `FiberValidator.L0.upgrade` (`lifecycle/validate/FiberValidator.scala:131-138`) runs `fiberIsActive`, `updateSignedByOwners`, `bindingNameMatches`, `currentStateInDefinition`, and has `proofs` in scope — the mirror site for H4.
- guards: `depInState` (`src/schema/guards.ts:252-263`), `signerHasRoleVia` (`:210-239`) read flat per-role maps because (their own comment) "metakit `get`/`has` on a null inner map ERROR rather than returning null; a flat map keeps the read total + fail-closed" — directly corroborates H1/H3 fail-closed requirement. `dropNulls` parity at `src/ottochain/drop-nulls.ts`.

## 1. The policy model (metadata-resident, no struct change)

The policy is a JSON object stored at `metadata` under one reserved sub-key. Add to `ReservedKeys.scala` (the metadata namespace, NOT effect keys):
```
val POLICY            = "_policy"   // metadata[POLICY] is a MapValue holding the fields below
val SELF_REPRODUCING  = "selfReproducing"  // Bool (Dial #1, engine-hardening A.2) — shared key
val UPGRADE_POLICY    = "upgradePolicy"    // Str tag: "immutable"|"governed"|"appendOnly"|"arbitrary"
val MIGRATION_AUTH    = "migrationAuthority" // Map present iff governed (see §3.2)
val POLICY_VERSION    = "version"   // Map {major,minor,patch} of ints (D2)
val POLICY_INTERFACES = "interfaces" // Array[Str] of interface ids (ERC-165)
val POLICY_COMPAT_MIN = "compatMin"  // Map {major,minor,patch} | absent
val POLICY_COMPAT_MAXX= "compatMaxExclusive" // Map | absent
```
The underscore on `_policy` mirrors the reserved-key convention (`_triggers`, `_spawn`); it cannot collide with an app's command/state field names which the conformance checker would otherwise flag.

**Typed read model (chain-side helper, NOT a serialized ADT):** `modules/shared-data/.../fiber/FiberPolicy.scala` defines a *parser*, not a wire type:
```scala
sealed trait UpgradePolicy { def rank: Int }
object UpgradePolicy {
  case object Immutable  extends UpgradePolicy { val rank = 3 }
  final case class Governed(authority: MigrationAuthority) extends UpgradePolicy { val rank = 2 }
  case object AppendOnly  extends UpgradePolicy { val rank = 1 }
  case object Arbitrary   extends UpgradePolicy { val rank = 0 }  // == absent metadata
}
sealed trait MigrationAuthority
object MigrationAuthority {
  final case class Signers(addresses: Set[Address]) extends MigrationAuthority
  /** registryFiberId is read from the OLD definition's metadata (pinned at the version being upgraded
    * FROM), never from newDefinition — see H3 fix in §3.2. */
  final case class Role(registryFiberId: UUID, roleField: String) extends MigrationAuthority
}
final case class FiberPolicy(
  selfReproducing: Boolean,
  upgradePolicy:   UpgradePolicy,
  version:         Option[SemVer],          // SemVer string in metadata; parsed total/fail-closed
  interfaces:      Set[String],
  compatMin:       Option[SemVer],
  compatMaxExcl:   Option[SemVer]
)
object FiberPolicy {
  val default = FiberPolicy(false, UpgradePolicy.Arbitrary, None, Set.empty, None, None)
  /** Total: any malformed/absent shape ⇒ default (fail-closed to "Arbitrary, no constraints").
    * Reads metadata.flatMap{ case MapValue(m) => m.get(POLICY) ...}; SemVer.parse(str).toOption. */
  def fromMetadata(metadata: Option[JsonLogicValue]): FiberPolicy = ???
}
```
`UpgradePolicy.Arbitrary` and absent `_policy` are THE SAME value (`FiberPolicy.default`) at every site (resolves the original §1 conflation requirement structurally). `SemVer.parse` can fail; `fromMetadata` swallows it to `None` (M3 fail-closed). `SemVer` wire form is the string `"1.2.3"` (`SemVer.scala:34`) inside the hashed definition; the eval-context projection re-projects to the int map (D2/§5.1) — two surfaces, explicitly.

`VersionRange` is just `(compatMin, compatMaxExcl)` with an inclusive-min / exclusive-max `contains`. Not reusing `VersionReq` (that is lineage resolution, different concept).

## 2. Prior-art mapping (per the task)

| Dial / value | Prior art | Mechanism here |
|---|---|---|
| `upgradePolicy=immutable` | Aptos `upgrade_policy::immutable`; frozen Sui `UpgradeCap`; eUTXO script preservation | reject ALL migrations at the gate (§3.1), mirrored at validation (§3.5) |
| `upgradePolicy=governed` | Aptos `compatible`+signer; CosmWasm cw2 migrate-admin; OZ proxy admin/reinitializer | authority check vs verified signers / pinned identity-registry role (§3.2) |
| `upgradePolicy=appendOnly` | Substrate additive `StorageVersion`; protobuf additive+reserved; EVM storage-gaps | additive-delta over `MachineShape` keyed by field NUMBER, incl. `optional` (§3.3) |
| `upgradePolicy=arbitrary` | Aptos `arbitrary` | today's `migrateStateMachine` unchanged |
| `version` | Substrate `spec_version`; cw2 `set_contract_version`; protobuf-semver | semantic layer atop content-addressed `logicHash`; `SemVer.ordering` (`:21`) |
| `compatMin/Max` | protobuf-semver compat window; Cargo/npm `^`/`~` | bridge-window predicate on migration (§5.3) |
| `interfaces` | ERC-165 `supportsInterface`; ERC-7579 module-type ids | consumer depends on capability; surfaced into `machines.<id>._policy.interfaces` (§5) — TRUST-LAYER, not a security boundary (§5, H2) |
| `migrationAuthority` | cw2 `ensure_from_older_version`+admin; SPL freeze authority; OZ `Ownable` | `Signers`/`Role` (§3.2), pinned to the FROM-version definition |
| TIGHTEN-ONLY lattice | Aptos "upgrade_policy can only become more restrictive"; Sui drop-not-widen | `new.rank >= old.rank` (§4) |
| commute-law obligation | Substrate `try-runtime` pre/post invariant | per-upgrade receipt obligation flag + SDK test-kit (§6) |

## 3. Chain enforcement — `UpgradeGate`

New file `modules/shared-data/.../fiber/UpgradeGate.scala`. One entry consulted at the top of `migrateStateMachine`:
```scala
object UpgradeGate {
  def check(
    old:           Records.StateMachineFiberRecord,
    newDefinition: StateMachineDefinition,
    newBinding:    SchemaBinding,
    state:         CalculatedState,
    addrs:         Set[Address]            // verified signer addresses of the UpgradeFiber update
  ): Option[FailureReason] = {
    val oldP = FiberPolicy.fromMetadata(old.definition.metadata)
    val newP = FiberPolicy.fromMetadata(newDefinition.metadata)
    firstSome(
      gateByUpgradePolicy(oldP, old, newBinding, state, addrs),  // satisfy the OLD policy to migrate at all
      tightenOnly(oldP, newP),                                   // forbid loosening the lattice
      compatBridge(oldP, old.schemaBinding, newBinding)          // §5.3 bridge window
    )
  }
}
```

**Wiring** — `FiberEngine.migrate` (`:61-66`) gains `addrs: Set[Address]`; `migrateStateMachine` (`:254`) inserts, before `evalMigration` is forced:
```scala
UpgradeGate.check(sm, newDefinition, newBinding, calculatedState, addrs) match {
  case Some(reason) => aborted(reason)                 // total discard; nothing committed
  case None         => /* existing :257-318 unchanged */
}
```
`FiberCombiner.upgradeFiber` resolves `addrs` exactly like `:49` (`update.proofs.toList.traverse(_.id.toAddress).map(Set.from)`) and passes them into `orchestrator.migrate` at `:255` (mirroring the `process` path at `:134`). The combiner's monotonic-version (`:235-245`) and same-package (`:212-222`) checks STAY.

### 3.1 Immutable
`gateByUpgradePolicy` returns `Some(ValidationFailed("upgradePolicy=immutable: migrations forbidden", ordinal))` unconditionally.

### 3.2 Governed(authority) — H3 fixes baked in
The authority is read from the **OLD** definition's metadata (the version being upgraded FROM), which is hash-pinned and was authorized at its own creation/prior upgrade — it is NOT re-suppliable in `newDefinition`. (This closes the self-authorizing `Role(registryFiberId=attacker-fiber)` hole.)
- `Signers(authSet)`: permit iff `addrs.intersect(authSet).nonEmpty`. **Relationship to owners (H3):** the L0 `updateSignedByOwners` gate (`FiberValidator.scala:134`) ALREADY requires owner signatures; `Signers` is an ADDITIONAL, narrower gate (authority ⊆ or ≠ owners, both must pass). Document: an owner not in `authSet` is rejected here by design — Governed means "owner-signed AND authority-signed". An empty `authSet` ⇒ nobody ⇒ effectively soft-Immutable (deny all) — `tightenOnly` permits Governed→Governed but `compatBridge`/this check still deny; flag empty-authSet as a no-op-but-safe footgun in docs (L3).
- `Role(registryFiberId, roleField)`: read `state.stateMachines.get(registryFiberId).map(_.stateData)`, project the flat per-role map at `roleField` exactly as `signerHasRoleVia` does (`{<address>: true}`); permit iff any addr in `addrs` is a key. **Total/fail-closed**: missing fiber / missing map / non-map ⇒ DENY. `registryFiberId` comes from the OLD metadata only.

### 3.3 AppendOnly — additive-delta (M1 fix: include `optional`, key by NUMBER)
Resolve both `RegisteredVersion`s (old via `old.schemaBinding`, new via `newBinding`) through the same strict-lookup `ConformanceChecker.strictVersion` uses. If EITHER is non-strict / unresolved ⇒ DENY (`"appendOnly requires both versions strict-bound"`) — fail-closed (open-decision-4 resolved: keep deny). Then, over `stateMessage` and pairwise over `commands` (a removed command ⇒ deny):
```scala
def additive(oldM: MessageShape, newM: MessageShape): Boolean = {
  val byNum = newM.fields.map(f => f.number -> f).toMap
  oldM.fields.forall { o =>
    byNum.get(o.number).exists(n =>
      n.name == o.name && n.typeName == o.typeName &&
      n.repeated == o.repeated && n.optional == o.optional)   // optional INCLUDED (M1)
  }
}
```
Field NUMBER is the protobuf-reserved identity; name/type/repeated/optional pinned per number. This is a SHAPE-monotonicity check ONLY; the produced-state VALUE conformance is the existing gate at `FiberEngine.scala:269` (state this explicitly — M1). Analog of Substrate additive `StorageVersion` + protobuf additive/reserved.

### 3.4 tighten-only — see §4.

### 3.5 Validation-tier mirror (H4)
Mirror the cheap, signer-independent checks in `FiberValidator.L0.upgrade` (`:131-138`, has `proofs`): `Immutable` rejection and `tightenOnly` (both are pure functions of old/new metadata + resolvable addresses). `Governed(Role)` and `AppendOnly` (which need full `CalculatedState`/strict-version resolution) MAY stay engine-only, but `Immutable` and `tightenOnly` rejecting at validation matches the established two-tier pattern (`bindingNameMatches`/`currentStateInDefinition` already do) and avoids metering a doomed migration. New `FiberRules.L0.upgradePolicyPermits`.

## 4. Tighten-only lattice
`Immutable(3) > Governed(2) > AppendOnly(1) > Arbitrary(0)` via `UpgradePolicy.rank`.
```scala
def tightenOnly(oldP, newP): Option[FailureReason] =
  if (newP.upgradePolicy.rank < oldP.upgradePolicy.rank)
    Some(ValidationFailed(s"upgradePolicy may only tighten (got rank ${newP...} < ${oldP...})", ordinal))
  else None
```
Ordering matters: `gateByUpgradePolicy` runs against the OLD policy (must satisfy today's constraint to migrate at all), then `tightenOnly` forbids weakening — so you cannot escape Immutable (it denies first) nor launder Governed→Arbitrary in one hop. `Governed→Governed` with a different authority is permitted (same rank, matches cw2 admin-rotation; the migration is already authority-signed) — but document the L3 caveat that the new authority must not be trivially weaker (empty `Signers`, or a `Role` whose registry the new authority controls — though the registry id is pinned to OLD metadata so this specific vector is closed by §3.2).

## 5. The #24 cross-fiber coupling solution

### 5.1 Surface the producer's policy into `machines.<id>` (chain) — H1/H2/M2 fixes
Extend `buildFiberSummary` (`ContextProvider.scala:283-296`) to ALWAYS add a `_policy` key, well-typed even when the dependency has no policy (H1: never absent, never wrong-typed):
```scala
val p = FiberPolicy.fromMetadata(fiber.definition.policyMetadata) // default if absent
val verifiedVersion = fiber.schemaBinding.map(_.version)          // H2: VERIFIED, not self-declared
val policyMap = MapValue(Map(
  ReservedKeys.POLICY_VERSION ->
    verifiedVersion.fold(MapValue(Map.empty): JsonLogicValue)(v =>
      MapValue(Map("major"->IntValue(v.major), "minor"->IntValue(v.minor), "patch"->IntValue(v.patch)))), // MAP (D2)
  ReservedKeys.POLICY_INTERFACES -> ArrayValue(p.interfaces.toList.sorted.map(StrValue(_)))  // ALWAYS an Array (M2/H1)
))
baseMap + (ReservedKeys.POLICY -> policyMap)
```
**H2 — version is the VERIFIED `schemaBinding.version`, NOT self-declared `policy.version`.** `#37` pins `schemaBinding.version` to a registered `logicHash`, so a producer cannot lie about it; `policy.version` self-attestation is dropped from the projection entirely. **Interfaces remain self-declared** (ERC-165 is also spoofable) — projected as a trust-layer hint; the spec MANDATES that consumers MUST NOT gate authority/funds on `depSupportsInterface`. The version map is `{}` when the producer is unbound (still a MapValue — fail-closed for `get`). This is a read-only projection of already-hashed/already-verified data — no hash change. Applies to parent/children too (harmless).

### 5.2 SDK guards (`src/schema/guards.ts`) — fail-closed, map-form (B2/H1/M2)
```ts
/** ERC-165 supportsInterface over a runtime-bound dep. TRUST-LAYER ONLY — never gate funds/authority. */
export const depSupportsInterface = (refVar: string, iface: string): GuardRule => ({
  if: [
    { and: [
      { has: [{ var: "machines" }, { var: refVar }] },
      { has: [{ get: [{ var: "machines" }, { var: refVar }] }, "_policy"] } ] },   // inner presence (H1)
    { in: [ iface,
      { get: [ { get: [{ get: [{ var: "machines" }, { var: refVar }] }, "_policy"] }, "interfaces" ] } ] },
    false ],   // interfaces is always an Array (§5.1) → in does membership, not substring (M2)
});

/** Substrate spec_version floor over the VERIFIED binding version: dep.version >= [maj,min,pat]. */
export const depVersionAtLeast = (refVar: string, maj: number, min: number, pat: number): GuardRule => {
  const ver = { get: [{ get: [{ get: [{ var: "machines" }, { var: refVar }] }, "_policy"] }, "version"] };
  const f = (k: string) => ({ get: [ ver, k, 0 ] });   // 3-arg get → default 0 when key absent (fail-closed, total)
  return { if: [
    { and: [
      { has: [{ var: "machines" }, { var: refVar }] },
      { has: [{ get: [{ var: "machines" }, { var: refVar }] }, "_policy"] } ] },
    { or: [
      { ">":  [f("major"), maj] },
      { and: [{ "==": [f("major"), maj] }, { ">": [f("minor"), min] }] },
      { and: [{ "==": [f("major"), maj] }, { "==": [f("minor"), min] }, { ">=": [f("patch"), pat] }] } ] },
    false ] };
};
```
All tags (`if and has in or == > >= get var`) are in `KNOWN_OPERATORS` — `guard-lint.ts` needs no change. The `{get:[ver,"major",0]}` 3-arg form returns `0` when the version map is `{}` (unbound producer), so the comparison is total and the guard fails to `false`. Composes with the two-phase `_addDependency`-then-read pattern. A consumer depends on a capability + a VERIFIED version floor, never an exact `logicHash`.

### 5.3 `compatMin/Max` — bridge direction only (L1 fix)
- **Bridge (migration, enforced):** in `compatBridge`, if OLD policy has a window, require it `contains(newBinding.version)`; else unconstrained. The predecessor declares which successor versions it will bridge to.
- **Consumer direction:** REMOVED as a field meaning. The consumer's compat assertion is `depVersionAtLeast` (the runtime gate). Do not ship a decorative second meaning (L1). `compatMin/Max` is exactly one enforced thing: the migration bridge window.

## 6. Commute-law gap (`FiberLogEntry.scala`)
Cannot be proven on-chain (∀-inputs). Two steps:
1. **`UpgradeReceipt.commuteObligation: Boolean = false`** (additive, hash-stable for legacy receipts via dropNulls). Set `true` when `upgradePolicy ∈ {AppendOnly, Governed}` — records that the publisher ASSERTED (off-chain) commute-safety for the stricter tier, making the unproven assumption auditable per-upgrade. `Arbitrary` ⇒ `false`.
2. **SDK test-kit hook:** property generator runs `migrate∘step` vs `step∘migrate` over generated inputs for AppendOnly/Governed upgrades and FAILS authoring on divergence. No chain change beyond the receipt field.

## 7. Security invariants (the gate's guarantees)
- I1 **Hash-stability:** no struct change; policy in `metadata`; legacy `computeDigest` byte-identical (D1). No `None`/`Some(default)` malleability.

  > **Greenfield note:** Greenfield: byte-identical legacy `computeDigest` is a *bonus*, not a required invariant. With the typed-field carrier (see the §0 note above) the equivalent guarantee is the `Some(empty) ⇒ None` normalization — assert the digest of an absent vs. all-default policy is equal, and that a non-default policy differs; regenerate fixtures.
- I2 **Fail-closed everywhere:** unresolved/missing/malformed ⇒ deny (gate) / `false` (guards). Metadata parse is total. Cross-fiber projections are always present + well-typed (H1).
- I3 **No self-authorization:** `Governed.Role` registry id and authority come from OLD (hash-pinned) metadata, never `newDefinition` (H3).
- I4 **Verified, not self-declared, where it gates:** `depVersionAtLeast` is bound to `schemaBinding.version` (#37-pinned); `interfaces` is explicitly trust-layer, not a security boundary (H2).
- I5 **Tighten-only is one-way:** rank monotone; Immutable is terminal (§4).
- I6 **No cascade bypass:** only `FiberCombiner.upgradeFiber`→`migrate` invokes the gate; cascaded/trigger transitions never call `migrate` (verified) — stated as a non-issue (L3).
- I7 **Scripts out of scope (L2):** `migrateScript`/`ScriptCombiner` is NOT gated; document that script-fiber upgrade policy is unsupported in this stream (explicit non-goal).

## 8. Test plan
- Hash-stability: `def.computeDigest == def.copy(metadata = Some(MapValue.empty)).computeDigest`? No — assert that a definition with NO `_policy` key and one with an all-default policy OMITTED both digest identically; and that a non-default `_policy` changes the digest. SDK parity: `dropNulls` leaves absent `_policy` absent.
- `FiberPolicy.fromMetadata`: absent ⇒ default; malformed semver ⇒ version None; garbage shape ⇒ default (total).
- `UpgradeGate`: Immutable denies; Governed.Signers accept(in-set)/deny(out); Governed.Role accept/deny + fail-closed (missing fiber/map); AppendOnly additive-accept / field-removal-deny / retype-deny / optional-flip-deny / removed-command-deny / non-strict-deny; tighten-only lattice all 16 ordered pairs; compatBridge in/out of window.
- Validation mirror: Immutable + tighten-only rejected at `FiberValidator.upgrade` BEFORE combine.
- JLVM eval (existing guard-eval suite): `depVersionAtLeast`/`depSupportsInterface` against `_policy={}` (unbound) ⇒ false (no exception); against present map ⇒ correct; verify `in` uses membership on the Array projection (not substring).
- Receipt: `commuteObligation` true under AppendOnly/Governed, false under Arbitrary; legacy receipt digest unchanged.

## 9. Change inventory
**Chain — new:** `FiberPolicy.scala` (parser + `UpgradePolicy`/`MigrationAuthority` read-model, §1), `UpgradeGate.scala` (§3–§5.1,§5.3).
**Chain — edits:**
- `ReservedKeys.scala` — add `POLICY`, `SELF_REPRODUCING`, `MIGRATION_AUTH`, `POLICY_VERSION`, `POLICY_INTERFACES`, `POLICY_COMPAT_MIN`, `POLICY_COMPAT_MAXX`. (No `StateMachineDefinition` change.)
- `FiberEngine.scala:61-66` — `migrate` gains `addrs: Set[Address]`; `:254` — insert `UpgradeGate.check`.
- `FiberCombiner.scala:255` — resolve addrs (mirror `:49`), pass into `migrate`.
- `ContextProvider.scala:283-296` — `buildFiberSummary` adds always-present `_policy.{version(map, from schemaBinding),interfaces(array)}` (§5.1).
- `FiberValidator.scala:131-138` + `FiberRules` — mirror Immutable + tighten-only (§3.5).
- `FiberLogEntry.scala` — `UpgradeReceipt.commuteObligation: Boolean = false` (§6).
- `FailureReason` — reuse `ValidationFailed` (no new variants required).
**SDK — edits:**
- `src/schema/guards.ts` — `depSupportsInterface`, `depVersionAtLeast` (§5.2).
- `src/schema/fiber-app.ts` — accept optional `policy`, emit it into `ProtoStateMachineDefinition.metadata._policy` ONLY when non-default (omit all-default to preserve hash-stability); add TS `SemVer`/`UpgradePolicy`/`MigrationAuthority` types mirroring §1; emit `version` as the `"x.y.z"` string in metadata (chain re-projects to int map).
- `src/ottochain/drop-nulls.ts` — no change; verify absent `_policy` stays absent.
- Commute test-kit (§6.2).

## 10. Resolved open decisions
1. Version wire form — MAP `{major,minor,patch}` (D2, JLVM has no int-index get; confirmed).
2. proofs/addrs threading — confirmed feasible (`Combiner.scala:71` Signed-wrap; `FiberCombiner:49` addr resolution; `:124,134` process precedent).
3. Governed→Governed rotation — permitted, signer-authorized; empty/weak-authority footgun documented (L3); registry id pinned to OLD metadata closes the spoof.
4. Non-strict AppendOnly — DENY (fail-closed), do not fall through to Arbitrary.
5. Scripts — explicit non-goal (I7).


### Implementation checklist

- Add reserved keys (POLICY="_policy", SELF_REPRODUCING, MIGRATION_AUTH, POLICY_VERSION, POLICY_INTERFACES, POLICY_COMPAT_MIN, POLICY_COMPAT_MAXX) to modules/models/.../schema/fiber/ReservedKeys.scala — do NOT touch StateMachineDefinition (policy lives in metadata, per engine-hardening A.2)
- Create FiberPolicy.scala read-model: UpgradePolicy{Immutable3,Governed2,AppendOnly1,Arbitrary0}, MigrationAuthority{Signers,Role}, FiberPolicy.default, total fromMetadata(Option[JsonLogicValue]) that fail-closes malformed/absent to default and parses SemVer via .toOption
- Create UpgradeGate.scala: check(old,newDefinition,newBinding,state,addrs)=firstSome(gateByUpgradePolicy(OLD policy),tightenOnly,compatBridge); Immutable denies all; Governed.Signers intersect addrs; Governed.Role reads flat role map total/fail-closed with registryFiberId from OLD metadata only; AppendOnly additive() keyed by field NUMBER comparing name+typeName+repeated+optional, deny if either version non-strict
- FiberEngine.scala:61-66 add addrs:Set[Address] to migrate; insert UpgradeGate.check at top of migrateStateMachine (:254) before evalMigration, aborted(reason) on Some
- FiberCombiner.scala:255 resolve addrs via update.proofs.toList.traverse(_.id.toAddress).map(Set.from) (mirror :49) and pass into orchestrator.migrate
- ContextProvider.scala:283-296 buildFiberSummary always-adds _policy key: version as MAP{major,minor,patch} from schemaBinding.version (VERIFIED, not self-declared), {} when unbound; interfaces ALWAYS an Array
- Mirror Immutable + tightenOnly at FiberValidator.upgrade (:131-138) via new FiberRules.L0.upgradePolicyPermits
- FiberLogEntry UpgradeReceipt: add commuteObligation:Boolean=false, set true under AppendOnly/Governed
- SDK guards.ts: depSupportsInterface + depVersionAtLeast with inner _policy presence guard, map-form version via 3-arg {get:[ver,"major",0]}, interfaces membership via in over Array
- SDK fiber-app.ts: accept optional policy, emit into metadata._policy ONLY when non-default (omit all-default), version as "x.y.z" string; add TS types
- Tests: hash-stability (absent vs all-default omitted digest equal; non-default differs), fromMetadata totality, every UpgradeGate branch, tighten-only 16 pairs, validation-mirror, JLVM guard eval against _policy={} = false no-exception, commuteObligation, dropNulls absent-policy parity

### Open questions

- Should Governed.Signers be a SUBSET-of-owners constraint (authority must also be an owner) or an INDEPENDENT additional set? Spec assumes independent-and-additional (both gates must pass); confirm with product intent.
- selfReproducing (Dial #1 / engine-hardening A.5) shares the _policy namespace but its one-way upgrade-latch is owned by the spawn/engine-hardening stream; confirm the latch and this gate's tighten-only do not double-enforce or conflict on the same metadata key.
- Interface-id namespacing/registration: interfaces stay self-declared (ERC-165 parity). Is an on-chain interface registry (binding an interface id to a conformance obligation) wanted later, or is trust-layer attestation permanently sufficient?

### Risks

- fromMetadata is the single trust root for the whole gate — any non-total branch (e.g. a throwing SemVer parse or an unhandled JsonLogicValue shape) turns fail-closed into fail-open or an engine exception; it must be exhaustively total and unit-tested against adversarial metadata shapes.
- Governed.Role reads a role map from a fiber id pinned in OLD metadata, but if that registry fiber is later archived/repurposed the authority silently changes; document that Role authority is only as trustworthy as the referenced registry's own lifecycle.
- interfaces remain spoofable self-attestation; if any downstream app author mistakes depSupportsInterface for a security boundary and gates funds on it, H2 re-opens at the application layer — the MUST-NOT must be loud in SDK docs/types.
- Scripts (migrateScript/ScriptCombiner) are ungated by design; an Immutable expectation on a script fiber is silently unenforceable — must be called out so no one ships a 'frozen' script policy believing it is enforced.
- Validation-tier mirror only covers Immutable+tighten-only; Governed/AppendOnly denials still surface deep in the metered combiner as RejectionReceipt — correct but gas is spent; acceptable but note the asymmetry.


---

## engine-default-fixes

> Three always-on correctness fixes (emit emitter-stamping, $caller surfacing, spawn fan-out cap), with the review's BLOCKER/HIGH defects corrected: real dataflow, de-duplicated 2-field emit stamp, fabricated FiberPolicy/selfReproducing prior art removed, consensus-fork framing and live-fiber-bricking surfaced.

# FiberPolicy `engine-default-fixes` — FINAL Implementation-Ready Spec

**Stream:** `engine-default-fixes` — always-on correctness, NOT opt-in policy dials. All three ship independent of any future `FiberPolicy` field and predate it.

All file:line citations re-verified against canonical `/home/euler/repos/ottochain` (NOT `.claude/worktrees`). Where the original design's citations were wrong, this spec carries the corrected ones and flags the delta.

---

## 0. Hash-stability ground truth (governs all three)

The canonical content-hash rule is `dropNulls ∘ RFC8785-canonicalize`, in the circe `JsonBinaryCodec`; `dropNulls` **recursively drops object fields whose value is `null`**, preserving array-element nulls. Consequences used below:

- Adding `Option[T] = None` to a hashed record is **hash-stable** (serializes to `null`, dropped).
- A **non-Option** field, or a `Some` on the legacy path, is **NOT** hash-stable (survives `dropNulls`).
- An **empty `List`** serializes to `[]`, which `dropNulls` leaves untouched ⇒ a `List`-typed field that is empty on the legacy path is hash-stable; the same field **populated** is not.

**Hashed surfaces in scope:** `StateMachineDefinition` (→ `logicHash`, the pinned constitution) and `OnChain.latestLogs: SortedMap[UUID, List[FiberLogEntry]]` (`OnChain.scala:47`, `OnChain extends DataOnChainState` `:50`). `latestLogs` is **part of the consensus-hashed OnChain snapshot state within an ordinal**, and is reset to `SortedMap.empty` every snapshot (`ML0Service.scala:104`). It is per-ordinal signaling, but it IS consensus-hashed while populated — so any `EmittedEvent` shape change is **consensus-affecting and requires coordinated atomic activation at an upgrade ordinal** (review M1; this is the correct framing, NOT "benign buffer").

### REMOVED prior-art (review B3 — re-verified)

The original design anchored §0 on `policy: Option[FiberPolicy] = None` and a `selfReproducing` `definition.computeDigest` equality in `SpawnValidator`. **Both are fabricated** — re-verified:
- `grep -rln "FiberPolicy" modules/` → **0 hits**.
- `grep -rln "selfReproducing" modules/` → **0 hits**.
- The only `computeDigest` in spawning is `initialData.computeDigest` (`SpawnProcessor.scala:130`); `SpawnValidator` has no `computeDigest` call.

These analogies are dropped entirely. The `dropNulls` reasoning above stands on its own and needs no precedent.

---

## Fix (1) — `_emit` emitter-stamping

### Problem (verified)

`EmittedEvent` (`EmittedEvent.scala:11-15`) is `{name, data, destination: Option[String]=None}` — no emitting-fiber identity. It is built **only** in `EffectExtractor.parseEmittedEvent` (`EffectExtractor.scala:301-310`), reached via `extractEmittedEvents` (`:298-299`), which is called once by `extractEffects` (`:83`). An observer reading `OnChain.latestLogs[*].emittedEvents` cannot attribute an event to a fiber; attribution is forgeable.

### Schema change (corrected per review B2 — de-duplicate against the parent receipt)

`EventReceipt` (`FiberLogEntry.scala:33-46`) **already carries** `fromState: StateId`, `toState: StateId`, `ordinal: SnapshotOrdinal`, and `sourceFiberId: Option[UUID]`. The original design's 5 new fields duplicated 4 of them and **collided semantically**: on the cascaded path `EventReceipt.sourceFiberId = trigger.sourceFiberId` is the *caller*, whereas the emit stamp wants the *emitter*. Same field name, two meanings.

**Stamp only what is NOT recoverable from the parent receipt, and rename to avoid the collision:**

```scala
// EmittedEvent.scala
@derive(customizableEncoder, customizableDecoder)
final case class EmittedEvent(
  name:           String,
  data:           JsonLogicValue,
  destination:    Option[String] = None,
  // ── engine-stamped, always-on, never user-supplied ──
  emitterFiberId: UUID,   // the fiber whose transition ran _emit (NOT the cross-fiber caller)
  emissionIndex:  Int     // position within the raw _emit array (see L2)
)
```

`fromState`/`toState`/`ordinal` are **dropped** from `EmittedEvent` — read them off the enclosing `EventReceipt`. `(emitterFiberId, receipt.ordinal, receipt.fromState, receipt.toState, emissionIndex)` is the unique per-event key. `emitterFiberId` is named distinctly from `EventReceipt.sourceFiberId` so the two never collide.

### Where it is stamped (single site) — corrected dataflow (review B1)

The original plan ("`from/to/ordinal` already in scope; ordinal via `askOrdinal` at `:114`") is **dataflow-impossible** and would not compile:
- `extractEffects` (`:70-75`) has `sourceFiberId` but **NOT** `transition`/`ordinal`; it calls `extractEmittedEvents(effectResult)` single-arg at `:83`.
- `buildSuccessOutcome` (`:234-245`) has `fiberId` and `transition` but **NOT** `ordinal`; `askOrdinal` at `:114` is in `tryTransitions`, a different method up the stack.

Since we no longer need `from`/`to`/`ordinal` (B2 dropped them), the **only** values to thread are `emitterFiberId` (= `fiberId`, already passed to `extractEffects` at `:245` as `sourceFiberId`) and `emissionIndex`. This collapses the change to:

1. `extractEmittedEvents(effectResult: JsonLogicValue, emitterFiberId: UUID): List[EmittedEvent]` (`:298`).
2. `parseEmittedEvent(value, emitterFiberId, emissionIndex)` (`:301`) sets the two fields.
3. `extractEffects` already has `sourceFiberId` — pass it as `emitterFiberId`:
   ```scala
   // EffectExtractor.scala:83  (inside extractEffects, sourceFiberId already in scope :74)
   val emitted = extractEmittedEvents(effectResult, sourceFiberId)
   ```
4. Index assignment preserving raw array position with gaps (review L2):
   ```scala
   def extractEmittedEvents(effectResult: JsonLogicValue, emitterFiberId: UUID): List[EmittedEvent] =
     extractArrayByKey(effectResult, ReservedKeys.EMIT).zipWithIndex.flatMap {
       case (v, i) => parseEmittedEvent(v, emitterFiberId, i)
     }
   ```
   `.zipWithIndex` is over the **raw** `_emit` array *before* `flatMap`-drop, so `emissionIndex` is true authoring-time position; a malformed sibling that `parseEmittedEvent` drops (`:309`) leaves a **sparse** gap. Document indices as sparse.

**No change to `buildSuccessOutcome`/`tryTransitions` signatures** — `ordinal` is not needed at the stamp site anymore. (This is the simplification B1+B2 jointly enable.)

### Why this site (both paths covered)

`extractEffects` is the single chokepoint for both the primary path (`FiberEvaluator.buildSuccessOutcome:245`) and the cascaded path (`TriggerHandler:91`, which reads `emittedEvents` straight out of `FiberResult.Success`). Both consume already-stamped values; no call-site logic change. `FiberResult.Success.emittedEvents` and `EventReceipt.emittedEvents` keep their `List[EmittedEvent]` type.

### Proto (review M2 — re-verified, downgraded to NON-BLOCKING note)

The only `fiber.proto`/`FiberProto.scala` are **generated artifacts under `modules/proto/target/`**. Re-verified: there is **no in-repo `.proto` source**, **no `PB.protoSources` in build.sbt**, and **zero `ottochain.v1.*` references in any non-generated `modules/**.scala`** (`grep` → 0). The proto `EmittedEvent` is therefore **not on the consensus or gossip/storage path** for this engine; the hash path is the circe `JsonBinaryCodec`. Consequently the proto change is **out of scope / no-op for correctness**. If the proto module is later wired live, add `optional uint64`/`string` fields (so legacy zero-value decode is tolerated) and model the Scala bridge with `Option`; do NOT block this fix on it. Action: **defer proto edits**; add a build assertion/test that no `EmittedEvent` crosses an upgrade boundary via proto (vacuously true today).

### Hash / back-compat verdict (corrected framing — review M1)

- `EmittedEvent` is **non-Option**: it appears only as a populated `List[EmittedEvent]` inside `EventReceipt` when a transition emitted. Adding the two non-Option fields **changes the canonical hash of any non-empty `emittedEvents`**.
- **Empty-emit receipts: byte-identical** (`emittedEvents = List.empty` → `[]`, untouched by `dropNulls`). Non-emitting transitions do not fork. ✅
- **Populated-emit receipts: hash changes** — and `latestLogs` IS consensus-hashed within an ordinal (`OnChain extends DataOnChainState`). This is a **consensus-affecting change requiring coordinated atomic activation at an upgrade ordinal**, same class as Fix (3) — NOT a soft "buffer" change. Because `latestLogs` is wiped each snapshot (`ML0Service.scala:104`), there is no *historical stored* populated list to re-hash across the boundary; the constraint is purely "all validators flip at the same ordinal."
- `StateMachineDefinition` / `logicHash`: **unaffected** — `EmittedEvent` is a runtime effect product, never a definition field. ✅
- **Replay determinism:** the stamp is a pure function of `(fiberId, raw-array-index)`, both deterministic engine inputs. ✅

**Failure mode:** none — pure constructor args, no parse/IO. The existing fail-silent drop of a malformed `_emit` item is preserved.

---

## Fix (2) — Caller surfacing to the guard context (`$caller`)

### Problem (verified)

`FiberTrigger.sourceFiberId: Option[UUID]` is engine-stamped when one fiber triggers another (`EffectExtractor.scala:136`, `:169`), but **never reaches the guard**. Cascaded path: `TriggerHandler.scala:86` calls `FiberEvaluator.make(...).evaluate(sm, trigger.input, List.empty)` — `trigger.sourceFiberId` is dropped for evaluation, only written post-hoc into the receipt (`:99`). The guard context (`ContextProvider.buildStateMachineContext`, `:147`, `MapValue` `:160-178`) has no caller key.

### Design: thread `caller` into evaluation, expose `$caller`

**Representation (fail-safe):**

| Trigger origin | `$caller` value |
|---|---|
| Cascaded fiber→fiber | `StrValue(sourceFiberId.toString)` |
| Self-trigger | `StrValue(selfId.toString)` ⇒ naturally `$caller == $machineId` |
| Primary / external (wallet) | `NullValue` ("not a fiber") |

`NullValue` for external is fail-closed-friendly: a future `acceptedCallers` allowlist treats "no fiber caller" as "must be explicitly permitted," distinct from any real fiber id. Self-trigger is NOT a third sentinel — it is just `$caller == $machineId`.

**Reserved key** (`ReservedKeys.scala`, alongside the `$`-vars at `:68-70`):
```scala
val CALLER = "$caller" // emitting fiber id of the cross-fiber trigger; null for external/primary
```

### Plumbing (re-verified call graph)

`evaluate` (`FiberEvaluator.scala:37-41` trait / `:60-80` impl) → `evaluateStateMachine` (`:86`) → `tryTransitions` (`:103`) → `ContextProvider.make(...).buildContext(...)` (`:117-127`). `ContextProvider.make` takes `ordinal/lastSnapshotHash/epochProgress` as **constructor** params (`:77-82`); `$ordinal` etc. are injected from those into the `MapValue` at `:160-178`. `caller` follows the same pattern.

1. **`ContextProvider`:** add `caller: Option[UUID]` as a **constructor param** to `make` (`:77-82`), and inject one entry into the `buildStateMachineContext` `MapValue` (`:160-178`):
   ```scala
   ReservedKeys.CALLER -> caller.fold[JsonLogicValue](NullValue)(id => StrValue(id.toString))
   ```
   (`NullValue`/`StrValue` are already in scope via the `json_logic._` wildcard import, `:9`.)
2. **`FiberEvaluator`:** add `caller: Option[UUID]` to `evaluate` (`:37-41`), thread `evaluateStateMachine` → `tryTransitions` → into `ContextProvider.make(calculatedState, ordinal, snapshotHash, epochProgress, caller)` at `:117`. Default `None` to keep other call sites compiling.
3. **Cascaded fix (load-bearing one-liner) — `TriggerHandler.scala:86`:**
   ```scala
   FiberEvaluator.make[F, G](calculatedState).evaluate(sm, trigger.input, List.empty, caller = trigger.sourceFiberId)
   ```
4. **Primary path — `FiberEngine.processActiveFiber` (`:340-347`):** the `.evaluate(fiber, input, proofs)` at `:347` passes `caller = None` (external wallet). External authentication remains the `proofs` channel (`ReservedKeys.PROOFS`).
5. **`FiberInput` NOT widened** — `caller` is an evaluation-time side-channel param, not part of the content-addressed input ADT. (Avoids entangling with the unrelated `MethodCall.caller: Address`.)
6. **Self-trigger:** free — `EffectExtractor` stamps `sourceFiberId = Some(self)` (`:136`), so the re-entered evaluation sees `$caller == $machineId`.

### Security invariant + scope clarification (review H2)

State explicitly in code comments and the `acceptedCallers` hand-off doc:
- `$caller` binds **only** the engine-stamped fiber→fiber emitter — non-spoofable (a fiber cannot forge being another fiber; the engine writes the id at `:136`/`:169`).
- **External-caller authorization is a `proofs` concern, not `$caller`.** `$caller=null` says "some non-fiber"; it cannot distinguish *which wallet*. An `acceptedCallers` dial keyed on `$caller` answers "only fibers X,Y may trigger me"; "only wallet W" is `proofs`. Do not conflate.
- **Self-trigger hazard:** cycle detection keys on `(fiberId, eventName)` (`FailureReason.CycleDetected`), so a fiber can re-enter itself with a *different* event name and present `$caller == $machineId`. A downstream `acceptedCallers` that allowlists "self" must treat `$caller == $machineId` as an **explicitly-opted, distinct** case (potential self-bounce privilege-escalation channel), never implicitly trusted.

### Hash / back-compat verdict

- `StateMachineDefinition`/`logicHash`: **unaffected** — `$caller` lives only in the transient per-transition `MapValue`, never serialized. ✅
- Legacy guards: `{"var":"$caller"}` on an absent key already resolves to null; adding the key only changes behavior for guards that opt to read it. No legacy guard changes outcome. ✅
- Receipts: unchanged — `sourceFiberId` already lands in `EventReceipt` (`:44`/`:99`); this only additionally surfaces it to the guard. ✅
- Replay: `sourceFiberId` is already deterministic/recorded. ✅

**Failure mode:** none — context build is total.

---

## Fix (3) — Spawn fan-out bound

### Problem (verified)

`SpawnValidator.validateBatchConstraints` (`:198-223`) enforces only duplicate-child-id and known-fiber-collision. **No spawn-count cap exists** (re-verified: no `maxSpawn`/`fanout`/`spawns.size` guard anywhere). A single primary transition can emit an arbitrarily long `_spawn` array; each spawn is gas-metered in `SpawnProcessor.createFiberRecord` (`:130`), so gas is the only incidental backstop — cheap `initialData` lets an adversary mint hundreds of fibers, inflating `calculatedState.stateMachines` (storage-amplification DoS).

### Dep/asset caps — already covered (no new work)

- Dependency growth: `DependencyLedger.applyMutations` enforces `maxDependencyLedger` + `maxActiveDependencies`, `Left ⇒ abort`, before any commit. ✅
- Asset mutations: `AssetCombiner.applyFiberTransfers` enforces `maxAssetMutations`. ✅

### The new cap (with live-fiber-bricking mitigation — review H1)

```scala
// ExecutionLimits.scala  (+ scaladoc entry)
final case class ExecutionLimits(
  maxDepth:               Int  = 10,
  maxGas:                 Long = 10_000_000L,
  maxStateSizeBytes:      Int  = 1_048_576,
  maxAssetMutations:      Int  = 32,
  maxActiveDependencies:  Int  = 64,
  maxDependencyLedger:    Int  = 256,
  maxSpawnsPerTransition: Int  = 16   // ← bounds _spawn fan-out per PRIMARY transition
)
```

**OPEN — value MUST be chosen from on-chain data before ship (review H1):** the cap is a forward-only admit/reject rule that flips a live fiber from "commits" to "aborts every fire" at the upgrade ordinal. A pinned-constitution fiber that *legitimately* spawns >cap children in one transition (sharding/airdrop) is **permanently bricked with no migration path** (its `definition` cannot be edited). Before shipping `16`: survey deployed fiber definitions for max `_spawn` array length and set the cap **strictly above** the legitimate max (or genesis-pin it from on-chain data). `16` is a placeholder ordering (between `maxAssetMutations=32` and `maxActiveDependencies=64`); the enforcement design, not the number, is what this spec fixes.

### Enforcement site + threading (corrected — review H1)

Spawns are honored only on the **primary** transition (cascaded ignore them, `TriggerHandler.scala:88-90`), so one check on the primary path suffices. Site: `SpawnValidator.validateBatchConstraints` (`:198-223`), the canonical fail-closed spawn-batch gate.

`validateBatchConstraints` is **pure** (no `Ask`). `validateSpawns` (`:81-89`) IS in `G` with `Ask[G, FiberContext]` in scope (the `make` builder, `:74-78`). So read limits in `validateSpawns` and pass them down — do NOT add an `askLimits` inside the pure function:

```scala
// validateSpawns (:81)  — add limits read, pass into validateBatchConstraints
def validateSpawns(directives, parent, knownFibers, contextData) =
  ExecutionOps.askLimits[G].flatMap { limits =>
    directives
      .traverse(d => validateSingle(d, parent, contextData))
      .map(_.sequence.andThen(validateBatchConstraints(_, knownFibers, limits)))
  }

// validateBatchConstraints (:198) — add limits param + count error FIRST
private def validateBatchConstraints(
  spawns: List[ValidatedSpawn], knownFibers: Set[UUID], limits: ExecutionLimits
): ValidatedNel[FailureReason, SpawnPlan] = {
  val countErrors: List[FailureReason] =
    if (spawns.size > limits.maxSpawnsPerTransition)
      List(FailureReason.SpawnLimitExceeded(spawns.size, limits.maxSpawnsPerTransition))
    else Nil
  // ... existing duplicateErrors, collisionErrors ...
  val allErrors = countErrors ++ duplicateErrors ++ collisionErrors
  // NonEmptyList.fromList(allErrors) ...
}
```

The trait signature of `validateSpawns` (`:39-44`) is unchanged (limits read internally), so **no change to the `SpawnProcessor` call chain** (`validateAndProcess:97` → `processSpawnsValidated:78` → `processSpawns:72`). This is simpler than the review's "3 signatures deep" worry because `validateSpawns` already owns the `Ask`.

New error mirroring `DependencyLimitExceeded` (`FailureReason.scala:96`), and add a `toMessage` case (the `toMessage` block ends at `:63`):
```scala
case class SpawnLimitExceeded(attempted: Int, max: Int) extends FailureReason
// toMessage:
case FailureReason.SpawnLimitExceeded(attempted, max) =>
  s"Spawn fan-out limit exceeded: $attempted (max: $max)"
```
Make it loud/observable so a bricked fiber is diagnosable (review H1).

### Why fail-closed-correct (re-verified)

An `Invalid` propagates `Left(NonEmptyList[FailureReason])` through `validateAndProcess` (`:97`) → `processSpawnsValidated` → `TransactionResult.Aborted`. Abort = **total discard**: `FiberCombiner.scala:140-141` reads only `(reason, gasUsed)` from `Aborted`; only `Committed` (`:137-138`, `handleCommittedOutcome:335` `appendLogs`) flows `logEntries` to `latestLogs`. The success receipt buffered in the in-memory `ExecutionState` evaporates on abort. ✅ The count check runs **before** `createFibersFromPlan` (`:99`) — no child records constructed for an over-limit batch, and before the per-spawn `initialData` gas burn, pre-empting the amplification path.

### Hash / back-compat verdict

- `ExecutionLimits` is a plain case class supplied as runtime config via `FiberContext` (`Ask` env); re-verified **not** a field of any hashed record (`StateMachineDefinition`/`EventReceipt`/`OnChain`). Adding `maxSpawnsPerTransition` **touches no hash**. ✅
- **Consensus caveat:** the cap decides abort-vs-commit, so every validator MUST use the identical value or fork. Ship as a hard-coded chain constant in `ExecutionLimits()` default (the same source of truth `AssetCombiner` uses), NOT per-operator config. ✅
- **Replay:** forward-only admit/reject rule change — **deploy at the upgrade ordinal, coordinated/atomic** (same activation class as Fix (1)). No stored fiber's hash changes; only the future admit/reject decision does. See OPEN above for the bricking survey. ✅

**Failure mode:** fail-closed abort via `FailureReason.SpawnLimitExceeded`, total discard, pre-construction.

---

## Summary table

| Fix | Change sites (re-verified file:line) | New schema | Failure mode | logicHash? | Receipts/OnChain hash? | Replay |
|---|---|---|---|---|---|---|
| (1) emit stamp | `EmittedEvent.scala:11-15` (+2 fields); `EffectExtractor.scala:298-299` (`zipWithIndex`), `:301` (parse args), `:83` (pass `sourceFiberId`) | `emitterFiberId: UUID`, `emissionIndex: Int` | n/a (pure ctor) | **No** | Empty-emit identical; populated-emit changes — **consensus-affecting, atomic activation** | None |
| (2) `$caller` | `ReservedKeys.scala:70` (+key); `ContextProvider.scala:77-82` ctor param + `:160-178` inject; `FiberEvaluator.scala:37-41`+`:117` thread; `TriggerHandler.scala:86`; `FiberEngine.scala:347` | `$caller` key; `caller: Option[UUID]` param | n/a (total) | **No** | **No** (transient) | None |
| (3) spawn cap | `ExecutionLimits.scala:21-28` (+field); `SpawnValidator.scala:81-89` (read limits) + `:198-223` (+count, +param); `FailureReason.scala` (+`SpawnLimitExceeded` + toMessage) | `maxSpawnsPerTransition: Int` | **fail-closed abort, total discard, pre-construction** | **No** | **No** (runtime config) | Forward-only admit/reject — **atomic activation; survey live fibers first** |

**Cross-cutting invariant:** none of the three alters `StateMachineDefinition`/`logicHash`; the fiber constitution is preserved exactly. Hash-stability comes from "empty default ⇒ unchanged serialization" (Fix 1's `emittedEvents`), and "not hashed at all" (Fix 2's transient `$caller`, Fix 3's `ExecutionLimits`). No fabricated `FiberPolicy`/`selfReproducing` precedent is relied upon.

---

## Test plan

**Fix (1):**
- `EmittedEvent` round-trips through `JsonBinaryCodec`; `emitterFiberId`/`emissionIndex` survive (they are non-null).
- Hash stability: an `EventReceipt` with `emittedEvents = List.empty` hashes **byte-identically** pre/post change (golden-hash test).
- Stamping: a transition emitting N events yields `emitterFiberId == fiberId` and `emissionIndex` = raw-array position; a transition with a malformed `_emit` sibling yields a **sparse** index gap (assert the survivors keep their original positions).
- Cascaded path: a fiber-A→fiber-B trigger where B emits — B's `EmittedEvent.emitterFiberId == B` (the emitter), while the parent `EventReceipt.sourceFiberId == A` (the caller). Assert they differ and do not collide.

**Fix (2):**
- Guard reading `{"var":"$caller"}`: cascaded ⇒ `StrValue(callerId)`; self-trigger ⇒ equals `$machineId`; primary/external ⇒ `null`.
- Legacy guard not referencing `$caller` produces identical outcome pre/post (no behavior drift).
- `logicHash` of a definition is identical pre/post (context-only change).
- Self-bounce: fiber triggers itself with a different event name — `$caller == $machineId`, no `CycleDetected` (different event), and document the allowlist hazard test for the future dial.

**Fix (3):**
- `spawns.size == maxSpawnsPerTransition` ⇒ commits; `== max+1` ⇒ `Aborted(SpawnLimitExceeded)`, **zero** child records created (assert `calculatedState.stateMachines` unchanged), buffered success receipt absent from `latestLogs`.
- Over-limit batch aborts **before** per-spawn `initialData` gas burn (assert gas used < N×spawn-cost).
- Cascaded transition with a `_spawn` array > cap does NOT abort (cascaded ignore spawns).
- `ExecutionLimits` default value is identical across node configs (consensus-constant test).
- Hash: adding the field touches no `StateMachineDefinition`/`EventReceipt`/`OnChain` golden hash.

---

## Concrete file-level changes

1. `modules/models/.../fiber/EmittedEvent.scala` — add `emitterFiberId: UUID`, `emissionIndex: Int`.
2. `modules/shared-data/.../fiber/evaluation/EffectExtractor.scala` — `extractEmittedEvents(effectResult, emitterFiberId)` with `zipWithIndex`; `parseEmittedEvent(value, emitterFiberId, emissionIndex)`; pass `sourceFiberId` at `:83`.
3. `modules/models/.../fiber/ReservedKeys.scala` — `val CALLER = "$caller"`.
4. `modules/shared-data/.../fiber/core/ContextProvider.scala` — add `caller: Option[UUID]` ctor param to `make`; inject `ReservedKeys.CALLER` into `buildStateMachineContext` MapValue (`:160-178`).
5. `modules/shared-data/.../fiber/evaluation/FiberEvaluator.scala` — add `caller: Option[UUID] = None` to `evaluate`; thread through `evaluateStateMachine`/`tryTransitions` into `ContextProvider.make(...)` at `:117`.
6. `modules/shared-data/.../fiber/triggers/TriggerHandler.scala:86` — pass `caller = trigger.sourceFiberId`.
7. `modules/shared-data/.../fiber/FiberEngine.scala:347` — pass `caller = None`.
8. `modules/models/.../fiber/ExecutionLimits.scala` — add `maxSpawnsPerTransition: Int = <chosen>` + scaladoc.
9. `modules/shared-data/.../fiber/spawning/SpawnValidator.scala` — read `ExecutionOps.askLimits` in `validateSpawns`; add `limits` param + count check to `validateBatchConstraints`.
10. `modules/models/.../fiber/FailureReason.scala` — add `SpawnLimitExceeded(attempted, max)` + `toMessage` case.
11. **DEFER:** `modules/proto/...` — no in-repo `.proto` source; proto is a generated artifact off the consensus path. No edit required for correctness.


### Implementation checklist

- EmittedEvent.scala: add emitterFiberId: UUID and emissionIndex: Int (non-Option, engine-stamped); do NOT add fromState/toState/ordinal (recoverable from parent EventReceipt) and do NOT name a field sourceFiberId (collides with EventReceipt.sourceFiberId)
- EffectExtractor.scala: change extractEmittedEvents to (effectResult, emitterFiberId) using extractArrayByKey(...).zipWithIndex.flatMap{ case (v,i) => parseEmittedEvent(v, emitterFiberId, i) } so emissionIndex is raw-array position with sparse gaps on dropped malformed items
- EffectExtractor.scala:83: pass the in-scope sourceFiberId as emitterFiberId; no buildSuccessOutcome/tryTransitions signature change (ordinal no longer needed at stamp site)
- ReservedKeys.scala: add val CALLER = "$caller" next to the $ordinal/$lastSnapshotHash/$epochProgress block
- ContextProvider.scala: add caller: Option[UUID] as a constructor param to make(...); inject ReservedKeys.CALLER -> caller.fold(NullValue)(id => StrValue(id.toString)) into buildStateMachineContext MapValue (:160-178)
- FiberEvaluator.scala: add caller: Option[UUID] = None to evaluate; thread evaluateStateMachine -> tryTransitions -> ContextProvider.make(calculatedState, ordinal, snapshotHash, epochProgress, caller) at :117
- TriggerHandler.scala:86: pass caller = trigger.sourceFiberId into .evaluate(...) (closes the cascaded gap)
- FiberEngine.scala:347: pass caller = None on the primary/external path
- Document/comment: $caller binds ONLY the engine-stamped fiber emitter; external auth stays proofs-based; self-trigger ($caller==$machineId) is a distinct explicitly-opted case for any future acceptedCallers dial
- ExecutionLimits.scala: add maxSpawnsPerTransition with scaladoc; choose the default ABOVE the max _spawn-array length found in deployed fiber definitions (survey on-chain first) to avoid bricking live fan-out fibers
- SpawnValidator.scala: read ExecutionOps.askLimits[G] inside validateSpawns (it has Ask in scope), pass limits into validateBatchConstraints; add countErrors (spawns.size > limits.maxSpawnsPerTransition) prepended to existing errors; keep trait signature unchanged so SpawnProcessor chain is untouched
- FailureReason.scala: add SpawnLimitExceeded(attempted: Int, max: Int) mirroring DependencyLimitExceeded, plus a toMessage case (loud/observable)
- Tests: empty-emit EventReceipt hashes byte-identical pre/post; populated-emit changes; over-cap spawn aborts with zero child records and no latestLogs entry, before gas burn; $caller resolves correctly for cascaded/self/external; legacy guard + logicHash unchanged
- DEFER proto: confirm (and add a guard test) that EmittedEvent never crosses an upgrade boundary via proto; no modules/proto edit needed since proto is generated and off the consensus/hash path

### Open questions

- maxSpawnsPerTransition value: REQUIRES surveying deployed fiber definitions for the legitimate max _spawn-array length before picking a number; 16 is a placeholder. Must the cap be genesis-pinned/versioned rather than a code constant if any live fiber already exceeds the candidate value?
- Activation coordination: Fix (1) (populated-emit hash) and Fix (3) (admit/reject) both require an atomic upgrade-ordinal flip across all validators. Is there an existing version-gate/feature-flag mechanism at a snapshot boundary, or must one be added for this stream?
- Self-trigger allowlist semantics for the downstream acceptedCallers dial: should $caller==$machineId be (a) implicitly allowed, (b) require explicit self-opt-in, or (c) disallowed? This spec only flags the hazard; the dial owner must decide.
- Proto module liveness: is modules/proto a dead/standalone artifact, or is it consumed by an external (non-Scala) client that DOES decode EmittedEvent? If the latter, the optional-field migration in M2 becomes load-bearing.

### Risks

- maxSpawnsPerTransition can permanently brick a live, pinned-constitution fiber that legitimately spawns more than the cap in one transition (no migration path) — must survey on-chain max and set the cap above it before shipping (review H1).
- Fix (1) populated-emit receipts and Fix (3) admit/reject are both consensus-affecting; a non-atomic rollout forks the chain. latestLogs IS consensus-hashed within an ordinal (OnChain extends DataOnChainState), so this is NOT a benign buffer change (review M1).
- $caller is necessary but not sufficient for non-spoofable authorization: it binds only the fiber emitter; external-wallet auth remains proofs-based, and self-bounce ($caller==$machineId via a different event name, no CycleDetected) is a privilege-escalation vector the future acceptedCallers dial must handle explicitly (review H2).
- emissionIndex is sparse when a malformed _emit sibling is dropped; downstream consumers correlating index to authoring-time array position must tolerate gaps (review L2).
- If the proto module is later wired onto a live wire/storage path, proto3 zero-value decode of a legacy EmittedEvent yields an empty UUID rather than a decode error; the deferred proto fields must then be modeled optional/Option (review M2).


---

## Prior-art map

The dials map cleanly onto established upgrade/capability/lifecycle mechanisms from
other ecosystems. This grounds each dial in a known, audited design and clarifies the
guarantee it does (and does not) provide.

| FiberPolicy dial / mechanism | Prior art | What it corresponds to |
| --- | --- | --- |
| `version` / monotonic version advance | **CosmWasm `cw2` set_contract_version** | A hash-pinned `{contract, version}` stamp read before migration. |
| `upgradePolicy` (Immutable / Governed / AppendOnly / Arbitrary) | **Aptos Move `upgrade_policy`** (`arbitrary`/`compatible`/`immutable`) | The exact three/four-tier ladder from loosest to frozen. |
| `upgradePolicy = Immutable`, freeze-the-upgrade-path | **Sui `UpgradeCap` / `UpgradePolicy`** (additive → deps-only → immutable, one-way) | A capability object that can only be ratcheted stricter, never looser. |
| Schema/`StorageVersion` compatibility gating | **Substrate `StorageVersion` + `try_runtime`** | On-chain version pinned to a migration that must run before the new logic. |
| `interfaces` self-declaration / `depSupportsInterface` | **ERC-165 `supportsInterface`** | Self-attested capability advertisement — a discovery hint, never a security boundary. |
| `compatMin` / `compatMax` cross-fiber version bridge | **protobuf wire-compat + semver ranges** | Field-number-keyed additive compatibility + a declared acceptable-version window. |
| `acceptedCallers` / `$caller` allowlist | **object-capabilities / ERC-7579 module allowlists** | An explicit allowlist of who may invoke, rather than ambient authority. |
| `freezeAuthority` | **Solana SPL Token freeze authority** | A distinguished authority that can halt a fiber's activity. |
| `pausable` | **OpenZeppelin `Pausable`** | An owner-gated emergency stop with a recorded `pausedSince`. |
| `transferPolicy` recipient allowlist (whole-record custody moves, no conservation) | **eUTXO custody model (Cardano)** | Custody is a whole-asset move to a constrained recipient set, not an amount-conserving balance edit. |

> The `engine-default-fixes` stream deliberately relies on **no** fabricated
> `FiberPolicy`/`selfReproducing` precedent — its three fixes are unconditional engine
> correctness, justified on their own (emitter provenance, caller surfacing, DoS bound),
> not on any prior-art capability model.


---

## Sequencing

Suggested ship order. The streams are mostly independent at the type level, but there
are two real dependencies that fix the order at the edges.

1. **`engine-default-fixes` first.** These three fixes (emit emitter-stamping,
   `$caller` surfacing, spawn fan-out cap) are **unconditional** — they apply to every
   fiber and are not gated on any opt-in policy, so they carry no policy-design risk and
   can land independently. Critically, the `$caller` surfacing fix is what makes the
   `acceptedCallers` dial *safe*: until the engine stamps the caller un-spoofably, an
   `acceptedCallers` allowlist would bind to a self-asserted id and could be forged
   (dials risk #4 / open question #2). So this stream **unblocks** `acceptedCallers`.
   Note the two consensus-affecting fixes here (populated-emit receipts, admit/reject
   spawn cap) require an atomic upgrade-ordinal flip — settle that activation mechanism
   as part of this first step.

2. **`selfReproducing` (dial #1) + the simpler dials next.** Land the typed
   `policy: Option[FiberPolicy]` carrier, the normalizing decoder/encoder, the
   `FailureReason.PolicyViolation` variant, the tighten-only lattice, and the
   self-contained dials: `allowedEffects`, `spawnOwnerPolicy` / `maxSpawnFanout` /
   `maxGenerations`, `sealedStates`, `transferPolicy`, `dependencyPolicy`. Enable
   `acceptedCallers` only after step 1's caller-stamping is verified. Treat the
   pause/freeze runtime ops (new update ADTs) as a scope decision — they can descope to
   a follow-up without blocking the rest.

3. **The version/compatibility family last.** `version-compat-family` builds *atop* the
   migration + conformance system and the tighten-only machinery from step 2: the
   `UpgradeGate` (Immutable / Governed / AppendOnly), the fail-closed cross-fiber version
   projection, the semver map-form projection, and the `compatMin`/`compatMax` bridge.
   It is the largest surface and the one most dependent on the carrier and lattice being
   settled first.
