# 03 — Cross-fiber consistency & the authorization model — RFC

**Status:** draft / design. **Date:** 2026-06-25. **Program:** [Fiber & Asset Authoring Ergonomics](./README.md).
**Addresses:** findings **F6** (trigger-vs-read dependency asymmetry), **F7** (transition authorization),
**F8** (spawned-child owners). Doc only — no implementation here.

This RFC sits on `jlvm-engine-foundations.md` (the evaluator/effect substrate — the `ContextProvider`
context build, the `TriggerDispatcher` cascade-to-fixpoint, effects-as-data) and follows the format of
`asset-model.md` (a precise **Today / baseline** section, then additive, back-compatible proposals that
respect the three `signing-canonical-and-validation.md` invariants).

The headline deliverable is **§1 — the authorization matrix**, because the model turned out to be
*non-obvious AND internally inconsistent*: authoring the riverdale-economy e2e produced F7 ("a transition
is not owner-gated — any signer can transition any fiber"), and a unit test
(`TransitionOwnerGateDivergenceSuite`, §1) **confirms F7 for the live apply path** while showing the
*validator* codes the opposite. The two halves of the chain disagree with each other. The matrix below is
the corrected, cited, **test-settled** ground truth, and §3 reframes F7 as a real **enforcement gap**
(likely a security bug) plus the proposed fix — the most important clarification in this document.

> **Correction to an earlier draft.** A prior version of this RFC "reconciled" F7 by claiming the owner gate
> is real but *invisible in a single-key harness*. **That was wrong** and is retracted: the riverdale e2e
> used **distinct** keys (Alice creates → `owners = {Alice}`; Bob signs the transition) and Bob's transition
> **still applied** — because the **combiner** (the live apply path) enforces no owner gate at all. The
> divergence is by code path, not by key reuse. See §1's test note and §3.1.

---

## 0. Today (baseline) — how a fiber talks to, and is authorized by, another fiber

A fiber interacts with the rest of the metagraph along two cross-fiber axes and one authorization axis:

- **Fire-at another fiber** — a transition's effect emits a `_triggers` directive (`ReservedKeys.TRIGGERS`,
  `modules/.../schema/fiber/ReservedKeys.scala:12`) naming a `targetMachineId`
  (`ReservedKeys.scala:25`). The engine routes it.
- **Look-at another fiber** — a guard/effect reads `machines.$id.state` (`ReservedKeys.MACHINES = "machines"`,
  `ReservedKeys.scala:81`). The engine must first *project* that fiber into the evaluation context.
- **Be authorized to drive a fiber** — who may submit a `TransitionStateMachine` (or create/archive/upgrade)
  is decided by the validator + combiner.

All three are governed by **different gates with different ceremony**, and that inconsistency is the
subject of this RFC.

### The trigger path (fire-at) — no dependency, gated by `acceptedCallers`

`EffectExtractor.parseTriggerEvent` reads the directive's `targetMachineId` and builds a `FiberTrigger`
(`modules/.../fiber/evaluation/EffectExtractor.scala:115-124`). `TriggerDispatcher.processSingleTrigger`
routes purely by id — it looks the target up directly in the evolving `CalculatedState`, with **no
dependency check** of any kind:

```scala
// modules/shared-data/.../fiber/triggers/TriggerDispatcher.scala:153
state.getFiber(fiberId) match {
  case None        => … TriggerTargetNotFound …
  case Some(fiber) => processWithHandler(trigger, fiber, state)   // fires regardless of any declared dep
}
```

The *only* gate on the receiving side is the target fiber's `FiberPolicy.acceptedCallers`, checked
**before** any guard runs, in `FiberEvaluator.policyShortCircuit`:

```scala
// modules/shared-data/.../fiber/evaluation/FiberEvaluator.scala:129-147
lazy val callerHit =
  (policy.flatMap(_.acceptedCallers), caller) match {
    case (Some(allowed), Some(c)) if !allowed.contains(c) =>
      Some(FailureReason.PolicyViolation("acceptedCallers", s"caller $c is not in the accepted-callers allowlist"))
    case _ => None
  }
```

`acceptedCallers` is `Option[Set[UUID]]` (`modules/.../schema/fiber/FiberPolicy.scala:270`). **Unset ⇒ any
fiber may fire at you.** `$caller` is the engine-stamped, non-spoofable source fiber id (`ContextProvider`
`caller` param, `ContextProvider.scala:79`); a primary/external (wallet) trigger has `caller = None` and is
unaffected by `acceptedCallers`. **Net: firing at another fiber requires the emitter to declare nothing.**

### The read path (look-at) — requires a declared dependency

`machines.$id` is populated **only for declared dependencies**. The context builder projects exactly the
dependency set into `machines`, nothing more:

```scala
// modules/shared-data/.../fiber/core/ContextProvider.scala:266-271
private def buildMachinesContext(dependencies: Set[UUID]): F[MapValue] =
  resolveFibers(dependencies, calculatedState.stateMachines.get,
    (f: Records.StateMachineFiberRecord) => buildFiberSummary(f))
```

and the dependency set handed to it is this transition's **static** dependencies ∪ the fiber's **active
dynamic** dependencies:

```scala
// modules/shared-data/.../fiber/evaluation/FiberEvaluator.scala:172-175
contextProvider.buildContext(fiber, input, proofs,
  transition.dependencies ++ DependencyLedger.activeIds(fiber.dynamicDependencies))
```

`Transition.dependencies: Set[UUID]` is a first-class, hash-pinned field of every transition
(`modules/.../schema/fiber/Transition.scala:19`, *"Other machines this transition reads from"*). A
`{"var":"machines.<id>.state"}` whose `<id>` is **not** in `dependencies` resolves to `null` — the read
silently returns nothing. **Net: reading another fiber requires the reader to declare the dependency.**

### The authorization path — who may drive a fiber

The critical fact, settled by test (§1): the signer gate **diverges by code path** — the *validator* codes
it, the *combiner* (the live apply path) does not.

| Update | Signer gate — **validator** (`validateSignedUpdate`) | Signer gate — **combiner** (live apply path) | Cited |
|--------|------------------------------------------------------|----------------------------------------------|-------|
| `CreateStateMachine` | none (anyone creates) | none — **establishes** `owners` from create proofs; `authorizedSigners = participants` | `FiberCombiner.createStateMachineFiber:50,76,64` |
| `TransitionStateMachine` | `owners ∪ authorizedSigners` (`updateSignedByOwnerOrParticipant`, `FiberRules.scala:299-319`, `:308`) | **NONE** — only sequence + the transition **guard** (`FiberCombiner.processFiberEvent:101-144`) | `TransitionOwnerGateDivergenceSuite`, `MultiPartyTransitionSigningSuite` |
| `ArchiveStateMachine` | **`owners` only** (`updateSignedByOwners`, `:272-288`) | sequence only (`archiveFiber:151-181`) | — |
| `UpgradeFiber` | **`owners` only** + binding/tighten/state (`FiberValidator.scala:131-140`) | sequence + binding/version + engine `UpgradeGate` (`migrationAuthority`) | — |
| Registry ops (`PublishMachineVersion`, `SetVersionStatus`, `RegisterAlias`, …) | structural-only here (rule #3; `Validator.scala:152`, `:172-175`) | **`RegistryEntry.owner` + lineage — enforced HERE** (authoritative) | — |
| Asset morphism / fiber-held asset (R1) | structural-only here (rule #3) | **policy + holder — enforced HERE** (authoritative) | `asset-model.md` §8/§10 |
| Spawned-child transition | child `owners` (= `spawn.resolvedOwners`, no participants) | **NONE** — same as `TransitionStateMachine`: sequence + guard only | `SpawnProcessor.createFiberRecord:142`; `SpawnValidator.resolveOwners:230-237` |

The asymmetry is glaring: for **registry and asset** ops the owner/lineage gate lives **only in the combiner**
(by deliberate design — rule #3, the combiner is the authoritative deterministic gate). For
**transitions**, the owner gate lives **only in the validator** — the *opposite* placement — and the
combiner, which is what actually mutates committed state, enforces nothing but the guard. The transition
gate is therefore in the *wrong layer to be effective*.

**The two surprises this RFC targets:**

1. **F6 — opposite ceremony for fire-at vs look-at.** To *fire at* `B`, `A` declares **nothing** (B's
   `acceptedCallers` is B's choice). To *read* `B`, `A` must declare `B` in `transition.dependencies`.
   The reach-out is permissionless-by-default and dep-free; the read is dep-mandatory. An author who
   wires a cross-fiber `_triggers` and then tries to also read the target's state in the **same** guard
   hits a silent `null` until they additionally declare the dependency — for what feels like the *same*
   relationship.

2. **F7/F8 — the transition owner gate is declared but NOT ENFORCED on the live apply path.** The validator
   codes an `owners ∪ authorizedSigners` gate; the **combiner — the authoritative apply path — does not
   enforce it**, so committed state advances for any signer whose transition passes the **guard**. The
   riverdale e2e observed exactly this (distinct keys; a non-owner's transition advanced the fiber). This is
   a real **enforcement gap** (a likely security bug), settled by `TransitionOwnerGateDivergenceSuite`; §3
   has the corrected analysis and the fix.

---

## 1. The authorization matrix (the deliverable)

Consolidating the baseline into one decision table. The **"Net effective gate"** column is what governs
committed state — i.e. what the **combiner** (the live apply path) enforces; the **"Validator says"** column
is the *declared* gate, which for transitions is **not** the effective one (see the test note below). Every
row also requires the structural L1 checks (cid found, payload/size, sequence) from
`FiberValidator.L1Validator` / `FiberRules.L1`.

| Action | Validator says (declared) | Combiner does (LIVE / effective) | Net effective gate |
|--------|---------------------------|----------------------------------|--------------------|
| **Create** a fiber | none | establishes `owners` from proofs | **anyone** (then owns) |
| **Transition** a *primary* fiber | `owners ∪ authorizedSigners` | **no owner check** — sequence + **guard** only | **guard-only** (≈ `Open`) ‡ |
| **Transition** a *spawned child* | child `owners` (no participants) | **no owner check** — sequence + **guard** only | **guard-only** (≈ `Open`) ‡ |
| **Archive** a fiber | **`owners` only** | sequence only | **owners declared, not combiner-enforced** ‡ |
| **Upgrade** a fiber | **`owners` only** + tighten/re-bind | sequence + binding/version + engine `UpgradeGate` (`migrationAuthority`) | binding/version + `UpgradeGate`; owner check is validator-only ‡ |
| **Registry op** (publish / setStatus / alias) | structural-only (rule #3) | **`RegistryEntry.owner` + lineage** | **owner+lineage (combiner)** — correctly placed |
| **Asset morphism** (Transfer/Compose/…) | structural-only (rule #3) | **policy + holder (R1)** | **policy+holder (combiner)** — correctly placed |
| **Fire** `_triggers` at a fiber | n/a (engine) | target's **`acceptedCallers`** (unset ⇒ anyone) | `acceptedCallers` (engine) |
| **Read** `machines.$id` | n/a (engine) | reader must **declare the dependency** | declared deps (engine) |

‡ **The defect.** For transition/archive/upgrade the owner gate is coded in `validateSignedUpdate` but **not**
in the combiner — the inverse of the registry/asset placement, where the combiner is (correctly) the
authoritative gate. Because the combiner is what mutates committed state (and CLAUDE.md rule #2 names it
*"the authoritative deterministic gate"*), a check that lives only in `validateSignedUpdate` does **not**
govern committed state on the apply path. The transition row is therefore **effectively guard-only** — what
F7 reported. (Archive/upgrade are partially shielded by the engine `UpgradeGate` and by the fact that the
*only* meaningful archive/upgrade payload is owner-shaped, but the owner *signer* check has the same
placement smell and warrants the same audit.)

> **Settled by test — `TransitionOwnerGateDivergenceSuite`** (`modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/TransitionOwnerGateDivergenceSuite.scala`, passing, no cluster).
> Alice creates a fiber → `owners = {Alice}`, **no participants**; Bob — a non-owner, non-participant — signs
> a `ping` transition (guard `true`).
> - `Validator.validateSignedUpdate(afterCreate, Bob-signed ping)` → **Invalid** (`:92`,`:100`) — the
>   owner-or-participant gate *is* coded in the validator.
> - `Combiner.insert(afterCreate, Bob-signed ping)` → **APPLIES** — the fiber advances `s0 → s1` and the
>   sequence increments (`:95`,`:101-102`). `FiberCombiner.processFiberEvent` does no owner check; it checks
>   the sequence number then runs the engine guard.
>
> The pre-existing **`MultiPartyTransitionSigningSuite`** already encodes BOTH halves, and both pass:
> *"counterparty can sign … a fiber they didn't create"* asserts a non-owner, **non-participant** Bob's
> transition **APPLIES via `combiner.insert`** (`:23`,`:72` create has no `participants`, `:88` apply); while
> *"unauthorized third party CANNOT sign transitions"* asserts a non-owner, non-participant Charlie is
> **Invalid via `validator.validateSignedUpdate`** (`:197`,`:251`,`:253`). **Bob and Charlie are the same
> authorization class** — the suite is **self-contradictory in intent**, green only because each half probes
> a different layer. Reconciling it (which layer is the contract?) is part of the F7 fix (§3).

---

## 2. F6 — reconcile the dependency asymmetry

**The asymmetry, restated.** Fire-at is dependency-free and gated on the *receiver* (`acceptedCallers`);
look-at is dependency-mandatory and gated on the *caller* (must declare). They are two halves of the same
inter-fiber relationship, with opposite declaration ceremony, and the read half fails **silently** (a
`null`, not an error) when forgotten.

**Why the read side requires a declared dependency (the real constraint — don't break it).** The
dependency set **bounds the context build**. `buildMachinesContext` projects *only* the declared
dependencies (`ContextProvider.scala:266-271`), and dynamic dependencies are capped by
`ExecutionLimits.maxActiveDependencies`. This bound is load-bearing: it makes the per-evaluation context
size a function of the fiber's **own declared** surface, not of the global fiber graph. If any fiber could
read any other fiber on demand, a single transition could force-load an arbitrary number of foreign records
into one evaluation context — an unbounded state-read / gas / committed-proof-size vector (the same DoS
class `asset-model.md` guards with bounded `usedNonces`). **So the dependency requirement on reads is a
feature; the problem is purely the *manual, silent, asymmetric* ceremony.**

### Option (a) — AUTO-DECLARE a dependency from static `machines.$id` references — **RECOMMENDED**

At create/upgrade, the engine statically scans the definition's guard/effect expressions for
`{"var":"machines.<uuid>.…"}` paths with a literal `<uuid>`, and **adds those ids to the transition's
`dependencies`** (or surfaces them as an additive, engine-derived dependency set). The author writes the
read; the dependency is inferred. This removes the surprise entirely for the common case (a literal target)
while preserving the bound — the projected set is still finite and statically known, so context size stays
bounded.

- **Pro:** kills F6 for static references; zero new runtime cost (a parse-time scan, reusing the
  reserved-key/var walk already in `FiberRules.L1.extractMapKeys`-style traversal); the bound is preserved
  because the auto-added set is exactly the literally-referenced ids.
- **Con / boundary:** a *computed* target id (`{"var":"event.target"}`) cannot be statically resolved, so
  it still needs an explicit static `dependencies` entry **or** a runtime `_addDependency` (the existing
  dynamic-dependency path; `ReservedKeys.ADD_DEPENDENCY`, gated by `DependencyPolicy`). That residue is
  acceptable and honest: a dynamic read is exactly where an explicit, gas-charged, policy-bounded
  declaration belongs.
- **Visibility:** the auto-declared set should be **observable** (e.g. surfaced in the `_policy`/dependency
  projection or a definition-introspection endpoint) so an author can see what the scan inferred — see the
  open question on immutability.

**Verdict: adopt (a).** It is additive (only *adds* deps that the definition already references), needs no
signed-message change, and is caught entirely offline by the Proposal 01 validator (which already resolves
`var` paths) — the validator can both *warn* on an undeclared `machines.$id` read and *show* the
auto-declared set.

### Option (b) — make `machines.$id` dependency-free by projecting any referenced fiber on demand — **DECLINE**

Resolve `machines.<id>` lazily against `CalculatedState` whenever a guard/effect dereferences it, with no
declaration.

- **Fatal con:** removes the context bound. Context-build cost becomes a function of how many foreign
  fibers an expression dereferences, which an adversary controls — a committed-path DoS and a gas-accounting
  hole (the read happens *inside* metered evaluation, but the *projection* cost in `ContextProvider` is paid
  before metering). It also makes the committed-state read-set of a transition non-obvious, complicating any
  future KPN/sharded-evaluation story (`jlvm-engine-foundations.md` §3.3 — independent fibers can run in
  parallel **only** if their read-sets are declared).
- **Verdict: decline.** Keep dependencies; auto-declare them (a). If a bounded form of (b) is ever wanted,
  it must come with an explicit per-evaluation **read-fanout cap** charged as gas — but (a) already solves
  the ergonomic complaint without that complexity.

**Symmetry note.** (a) leaves a pleasing symmetry: *fire-at* is gated on the **receiver** (`acceptedCallers`),
*look-at* is gated on the **caller** (declared deps, now auto-inferred). Both directions are explicit and
bounded; neither requires the author to hand-maintain a list for the static case.

---

## 3. F7 — the transition-authorization enforcement gap, and the fix

### 3.1 The finding is REAL — it is an enforcement gap, not a perception artifact

F7 as recorded — *"a transition is NOT owner-gated; any signer can transition any fiber; the guard is the
only gate"* — **is what the live apply path actually does.** The owner gate is *coded* (in the validator),
*declared*, and *not enforced*:

```
DECLARED (validator)                                  EFFECTIVE (combiner — the apply path)
Validator.validateSignedUpdate      (:166)            Combiner.insert                       (Combiner.scala:70)
  → CombinedValidator.processEvent  (:163)              → FiberCombiner.processFiberEvent    (:101-144)
    → updateSignedByOwnerOrParticipant                    sequence-number check + engine.process(guard)
        owners ∪ authorizedSigners  (FiberRules:308)      — NO owner / authorizedSigners check at all
        → Invalid for a non-owner                         → APPLIES a non-owner's transition
```

**Why "declared but not effective":** the combiner is the layer that mutates committed state, and CLAUDE.md
rule #2 names it *"the authoritative deterministic gate."* The riverdale e2e (and
`TransitionOwnerGateDivergenceSuite`, §1) show a non-owner's transition **advancing the fiber** — so
whatever `validateSignedUpdate` returns, it is **not** the gate that governs committed state for transitions.
The owner check is in the *wrong layer to bind*: for registry/asset ops the chain (correctly, by rule #3)
puts the owner/lineage gate **in the combiner**; for transitions it put it **only in the validator** — the
inverse — so it does not bind.

**Retraction (important).** An earlier draft of this RFC explained F7 as *"the gate is real but invisible in
a single-key harness."* **That is false and is withdrawn.** The riverdale e2e used **distinct** keys (Alice
creates → `owners = {Alice}`; Bob signs the transition) and Bob's transition **still applied**. The cause is
not key reuse; it is that the combiner enforces no owner gate. Git history is consistent with this: #161
("multi-party fiber signing", `9a7fe81`) relaxed the *validator* from `updateSignedByOwners` to
`updateSignedByOwnerOrParticipant`, but **never added an owner check to the combiner** — so the combiner has
been guard-only the entire time.

**Net:** today, **every** transition is effectively `Open` (guard-only) on the live path, regardless of
owners/participants. For an app that *wanted* owner gating (the common case — a contract whose state only
its owner should advance), this is a **silent authorization bypass**: a likely **security bug**. For an app
that *wanted* a public state machine, it is accidentally correct. The model is currently **looser than
anyone declared**, which is the opposite of safe defaults.

### 3.2 What the right model is — make the posture explicit *and actually enforced*

Two things are simultaneously true and must both be fixed:

1. **There is a legitimate "public state machine" posture** — a fiber whose access control lives **entirely
   in the guard** (a prediction market anyone may resolve-attempt, a public-good counter, a permissionless
   queue). That posture is *useful* and should be **expressible on purpose**.
2. **The current default IS that posture, by accident, for everyone** — including apps that wanted
   owner-only transitions and have no way to get them on the apply path. That is the bug.

So the fix is **not** merely "add an `Open` mode." It is: **make signer-authorization actually enforced on
the apply path (the combiner), and make the posture an explicit, opt-in choice** — so an app *declares*
whether it is owner-gated, participant-gated, or public, and the chain *enforces* that declaration where it
counts.

### 3.3 The fix — enforce in the combiner, then layer an opt-in `transitionPolicy` dial

**Step 1 (the bug fix): move signer-authorization into the combiner** as a graceful `CombineRejected →
RejectionReceipt` (the same authoritative-gate pattern registry/asset ops already use, `#154`). This is
what makes *any* transition signer gate bind on committed state. `FiberCombiner.processFiberEvent`
(`:101-144`) already resolves the fiber record and checks the sequence number; the owner/participant check
slots in right there, before `orchestrator.process`, against the **verified** signer addresses. The
validator may keep its check as a cheap fail-fast *preview* (structural-ish, owners are stable), but the
combiner becomes the binding gate.

**Step 2 (make the posture explicit): add an opt-in `transitionPolicy` dial** to `FiberPolicy.Constrained`
(`modules/.../schema/fiber/FiberPolicy.scala:264-293`), `Option`/omit-safe like every other dial, enforced
**in the combiner** alongside Step 1:

```scala
// new sealed ADT, modeled on the existing UpgradePolicy / DependencyMode tighten-lattice ADTs
sealed trait TransitionPolicy { def rank: Int }
object TransitionPolicy {
  case object Open                 extends TransitionPolicy { val rank = 0 } // any signer; guard is the sole gate (LOOSEST)
  case object OwnersOrParticipants extends TransitionPolicy { val rank = 1 } // owners ∪ authorizedSigners
  case object Owners               extends TransitionPolicy { val rank = 2 } // strict owners-only (TIGHTEST)
}
// FiberPolicy.Constrained += transitionPolicy: Option[TransitionPolicy] = None
```

`Allowlist` is **not** a separate mode: the existing `authorizedSigners`/`participants` set *is* the
allowlist, so `OwnersOrParticipants` already covers it. The dial joins the **tighten-only lattice**
(`FiberPolicy.tightens`, `FiberPolicy.scala:411-433`) as a `rankUp` dial — a migration may only move toward
**stricter** (`Open → OwnersOrParticipants → Owners`), never loosen — so a fiber can never launder itself
from `Owners` down to `Open`.

### 3.4 The back-compat trap — what the absent-dial default must be is a DELIBERATE decision (lead open question)

This is the crux, and it is genuinely hard because **the live behavior and the declared behavior disagree**,
so "back-compat" is ambiguous:

- **Back-compat with the LIVE behavior** ⇒ absent-dial default = **`Open`** (guard-only). Step 1 would then
  *not* change any existing fiber's effective gate (they are all guard-only today); apps opt **up** to
  `OwnersOrParticipants`/`Owners`. **Safe to ship (no behavior change), but it blesses the bypass as the
  default** — every existing owner-gated-by-intent app stays unprotected until it opts in.
- **Back-compat with the DECLARED/intended behavior** ⇒ absent-dial default = **`OwnersOrParticipants`**.
  Step 1 then **starts enforcing** the gate the validator always claimed — which is a **tightening** that
  **will break** any app relying on the current guard-only reality, including the deliberate
  *"counterparty can sign a fiber they didn't create"* pattern that `MultiPartyTransitionSigningSuite`
  encodes (Bob is neither owner nor participant, yet is *supposed* to be able to sign). This restores the
  intended security posture but is a **breaking change** for live apps.

**These cannot both be satisfied by a default; it is a policy call, not a code detail.** It is the **lead
open question** of this RFC (§6 Q1). A reasonable path: default **`OwnersOrParticipants`** (restore the
intended gate — security-first), but (a) treat it as a **breaking change** gated behind an engine-version
bump and a migration window, and (b) **first reconcile `MultiPartyTransitionSigningSuite`** — decide whether
"counterparty signing" is a supported feature (then it needs an *explicit* mechanism: the counterparty must
be a declared `participant`/`authorizedSigner`, or the fiber must declare `transitionPolicy = Open`) or an
accident of the bypass (then the test's "counterparty can sign" half is asserting the bug and must change).
**The self-contradictory suite must be resolved as part of this fix, not around it.**

### 3.5 Rule #3 safety of combiner enforcement

Enforcing signer-auth + `transitionPolicy` in the combiner is **rule-#3-safe**: it reads only the fiber's
own hash-pinned `definition.policy` dial and the **stable** `owners`/`authorizedSigners` record fields — no
`lineageOf`/`resolve`/`versionAppendable`, no registry/asset lineage. It is therefore **not** the TOCTOU
block-poisoning hazard rule #3 guards against; it is exactly the graceful, deterministic combine-reject the
rule *prescribes* for stateful gates. A bonus: moving the check off `validateSignedUpdate` also removes the
latent `Create`+`Transition`-batched-in-one-block edge (the acceptance-time owner read sees `None` for a
fiber created earlier in the same block, because the validator sees pre-block state; the combiner sees
intra-batch state and resolves it correctly).

---

## 4. F8 — spawned-child owners ergonomics

### 4.1 What the code does (confirmed) — and what F8's "rejection" actually was

A spawned child is an ordinary `StateMachineFiberRecord`, so it goes through the **same divergent**
authorization as any transition (§1/§3): the validator declares an `owners`-only gate, the combiner enforces
**none**. Its only structural differences are **where `owners` come from** and that it has **no
`authorizedSigners`**:

```scala
// modules/shared-data/.../fiber/spawning/SpawnProcessor.scala:132-146
childFiber = Records.StateMachineFiberRecord(
  …,
  owners = spawn.resolvedOwners,   // ← from the directive; authorizedSigners NOT set ⇒ Set.empty (Records.scala:47)
  …)
```

```scala
// modules/shared-data/.../fiber/spawning/SpawnValidator.scala:230-237
directive.ownersExpr match {
  case None       => Validated.validNel(parent.owners)             // inherit the parent's owners
  case Some(expr) => …evaluate expr → Set[Address]…                // e.g. {"var":"event.auctionOwners"}
}
```

`SpawnDirective.ownersExpr: Option[JsonLogicExpression]` (`modules/.../schema/fiber/SpawnDirective.scala:15`).
A spawn that sets `ownersExpr = {"var":"event.auctionOwners"}` gives the child `owners = auctionOwners`,
with no participants — so the child's **declared** (validator) gate is `owners` only, and an *omitted*
`ownersExpr` inherits the parent's owners.

**But F8's reported mechanism needs re-examination in light of §3.** F8 recorded that *"a bidder must be in
`event.auctionOwners` or `place_bid` is ML0-rejected"* and attributed the rejection to the
`owners ∪ authorizedSigners` gate. Under the §1 test finding, the **combiner does not enforce that gate**,
so on the live apply path the child's `owners` do **not** by themselves block a non-owner bidder. The
bidder's rejection in the riverdale run was therefore **one of**:

- **(most likely) the `place_bid` GUARD** — the auction app's own guard checks the signer (e.g.
  `proofs[].address` ∈ `state.auctionOwners`), which *is* the correct place for app-level access control and
  *would* reject the bidder regardless of the owner gate; or
- **a `validateSignedUpdate` rejection that bound in that run** — if so, it is the **same enforcement gap**
  from the other side (a validator owner-gate that may or may not bind on the apply path), making F8 a
  **second witness** of the §3 divergence rather than independent confirmation of an owner gate.

Either way, **the durable F8 takeaway stands but is reframed**: the child's `owners` are set at spawn time
from an expression the author may not have reasoned about as "the auth list," AND — because the framework
owner gate does not bind in the combiner — *the only reliably-enforced access control on a child's
transitions today is the guard*. So an auction that wants bidder-restriction must put that check **in the
`place_bid` guard**, not rely on the child's `owners`. This is itself a strong argument for §3 (make the
posture explicit and actually enforced).

(The parent's `FiberPolicy.spawnOwnerPolicy` dial — `Explicit | SubsetOfParent | InheritParent`,
`FiberPolicy.scala:39-47`, applied in `SpawnValidator.applySpawnOwnerPolicy:210-228` — constrains the
resolved owner *set* but, like the rest of the owner machinery, only governs the validator-declared gate.)

### 4.2 Proposals

- **Put bidder-restriction in the GUARD (the only reliably-enforced gate today).** Until §3 lands, an
  auction that wants to restrict who may `place_bid` must check the signer **inside the `place_bid` guard**
  (`proofs[].address` ∈ `state.auctionOwners`), because the child's `owners` do not bind in the combiner
  (§4.1). The SDK template (Proposal 00) and the offline validator (Proposal 01) should make this the
  default pattern and **warn** when a child relies on `owners` for access control without a corresponding
  guard check.
- **SDK helper + offline validator support.** A `spawn({ owners })` builder in `@ottochain/sdk`
  (Proposal 00) that makes `ownersExpr` an explicit, named argument, plus a Proposal-01 rule: when a
  definition `_spawn`s a child whose transitions are *intended* to be owner/participant-restricted, **warn**
  that today only a guard enforces it — caught offline, before the author ships an auction that silently
  accepts bids it meant to forbid (the live failure mode under §4.1, *worse* than an ML0 rejection because
  it is silent).
- **Interaction with §3 `transitionPolicy` (the real fix).** Once §3 makes the gate combiner-enforced, a
  spawned auction declares its posture explicitly: `transitionPolicy = Open` for a public auction (anyone
  may attempt `place_bid`; the guard alone decides), or `Owners`/`OwnersOrParticipants` for a closed one
  (and then the child's `owners` actually bind). The child sets the dial in its own (hash-pinned)
  `definition.policy`, so no new spawn field is needed — and the "is the child public or closed?" question
  becomes an explicit, enforced, author-visible choice instead of an accident of `ownersExpr` + a
  non-binding gate.

---

## 5. Safety / compatibility

F6 and F8's *tooling* changes are additive and back-compatible. The F7 fix (§3) is **deliberately NOT
fully additive** — it closes a security gap, and closing it changes effective behavior for at least one
posture. All of it respects the three `signing-canonical-and-validation.md` invariants:

- **Rule #1 (signed canonical).** The only new signed-message surface is `FiberPolicy.transitionPolicy:
  Option[TransitionPolicy] = None`, an `Option`/omit-safe dial that encodes inside the existing
  `Constrained` dials object and is stripped by `dropNulls` when absent — exactly like the existing dials,
  so a pre-dial definition is **hash-identical** to one that omits it. Add a case to
  `PublishVersionSigningCanonicalSuite`. (The *meaning* of an absent dial — §3.4 — is a runtime-enforcement
  choice, independent of the bytes: absence encodes identically regardless of which default the engine
  assigns.) F6 auto-declared dependencies are an **engine-derived** projection, not a new signed field; the
  scan must augment the *runtime* dependency set only and never mutate the signed `Transition.dependencies`.
- **Rule #2 (structural gate vs stateful combiner).** Both the signer-auth fix (Step 1) and `transitionPolicy`
  (Step 2) are enforced as **graceful `CombineRejected`** in the combiner — the *authoritative* gate — never
  as a block-acceptance `Invalid`. This is the **correct placement** the transition gate lacks today, and it
  brings transitions in line with how registry/asset owner gates already work. Block acceptance keeps its
  structural L1 checks (cid, payload, sequence) unchanged.
- **Rule #3 (no lineage at acceptance).** The combiner enforcement reads only the fiber's own hash-pinned
  dial and the stable `owners`/`authorizedSigners` record fields — no registry/asset **lineage** — so it is
  outside the TOCTOU hazard class (§3.5). Registry/asset owner gates remain combine-only, unchanged.
- **The absent-dial default is the LEAD open question, NOT settled here (§3.4 / §6 Q1).** Because today's
  *live* behavior (guard-only) and *declared* behavior (`owners ∪ authorizedSigners`) disagree, "back-compat"
  is ambiguous: a `Open` default preserves live behavior but blesses the bypass; an `OwnersOrParticipants`
  default restores the intended gate but is a breaking change. This must be decided deliberately (with an
  engine-version bump + migration window if tightening) and the self-contradictory
  `MultiPartyTransitionSigningSuite` reconciled — it is **not** an additive, ship-anytime change.
- **Tighten-only preserved.** `transitionPolicy` joins `FiberPolicy.tightens` as a `rankUp` dial, so a
  *migration* can only make transitions *stricter* (`Open → OwnersOrParticipants → Owners`), never launder a
  fiber from `Owners` down to `Open` — the same monotone trust guarantee the other dials give.

---

## 6. Alternatives, effort/risk, open questions

### Alternatives considered

- **F6 (b) on-demand projection** — declined (§2): removes the context bound (DoS/gas/sharding).
- **F7 keep status quo, document only** — declined: F7 is a real enforcement gap (a likely security
  bypass), not a perception issue. Documenting it without fixing it leaves every owner-gated-by-intent fiber
  unprotected on the apply path.
- **F7 fix it by enforcing only in `validateSignedUpdate`** (make the validator's verdict actually bind
  before combine) — declined: it is the wrong layer (rule #2 names the combiner authoritative), it carries
  the create+transition-same-block hazard (§3.5), and an acceptance-time `Invalid` is a silent block-drop
  rather than a `RejectionReceipt`. Enforce in the combiner.
- **F8 a new `SpawnDirective.participantsExpr` field** — declined: a new signed-message field for what the
  child's own `transitionPolicy` (or a guard check) already expresses; avoid widening the signed spawn
  surface.

### Effort / risk

| Change | Effort | Risk | Notes |
|--------|--------|------|-------|
| **Docs** — authoring-gotchas page + inline comments at `buildMachinesContext`, `policyShortCircuit`, `FiberCombiner.processFiberEvent` (the missing owner check), `SpawnDirective.ownersExpr`; land the corrected, test-cited F7 matrix | XS | None | Land first (program P0); records the **enforcement gap** so no one relies on the validator-only gate. |
| **F6 (a)** auto-declare static `machines.$id` deps (+ validator warn/show) | S–M | Low | Parse-time scan; additive to the **runtime** dep set; preserves the bound; offline-validatable. |
| **F8** SDK `spawn({owners})` + validator "owners don't bind — check in guard" warning | S | None (off-chain) | Off-chain; consumes Proposal 01 var-resolution. |
| **F7 Step 1** — enforce signer-auth in `FiberCombiner.processFiberEvent` (graceful `CombineRejected`) | S–M | **High** | **The security fix.** Changes effective behavior (see Q1). MUST settle the default (Q1) + reconcile `MultiPartyTransitionSigningSuite` + engine-version bump + a riverdale-economy e2e lane. Not additive. |
| **F7 Step 2** — opt-in `transitionPolicy` dial (ADT + `tightens` + combiner check) | M | Med | Builds on Step 1; touches signed canonical (one `Option` dial); golden round-trip + `PublishVersionSigningCanonicalSuite`. |

### Open questions

1. **(LEAD) What is the absent-`transitionPolicy` default, given live ≠ declared behavior?** `Open`
   (preserve today's live guard-only behavior; non-breaking but blesses the bypass as the default) vs
   `OwnersOrParticipants` (restore the intended/declared gate; security-first but a **breaking change** for
   apps relying on the current reality, including the deliberate "counterparty can sign" pattern). This is a
   policy decision with security and compatibility weight, **not settled by this RFC** (§3.4). It is coupled
   to: **reconciling `MultiPartyTransitionSigningSuite`** — is "a non-owner/non-participant counterparty can
   sign" a *supported feature* (then it needs an explicit mechanism — declared `participant` or
   `transitionPolicy = Open`) or an artifact of the bypass (then that test half asserts the bug)? Recommend
   `OwnersOrParticipants` default behind an engine-version bump + migration window, with counterparty
   signing made explicit — but flag for the maintainers to decide.
2. **Does `validateSignedUpdate` actually bind at all in production today?** The combiner clearly applies a
   non-owner transition (test). Whether the framework *also* drops the update on a `validateSignedUpdate`
   `Invalid` before/around combine — and under which deployment topology (the riverdale local cluster had
   drifted) — should be confirmed by tracing the ML0 apply path, since it determines whether the gap is
   "validator gate is dead" or "validator and combiner disagree and the combiner wins on committed state."
   Either way the §3 fix (enforce in the combiner) is correct; this only affects how we *describe* the
   current behavior.
3. **Auto-declared deps — visible and/or immutable?** Should the F6 (a) scan's inferred dependency set be
   (i) surfaced read-only in the dependency/`_policy` projection, and (ii) runtime-only (never written into
   the signed `Transition.dependencies`)? Recommendation: **yes to both**; confirm no canonical-divergence
   path.
4. **`transitionPolicy` vs cross-fiber triggers — what is "the signer" of a cascaded transition?** A
   `_triggers`-driven transition has `caller = Some(sourceFiberId)` and no fresh wallet proofs on the
   cascaded leg. Under `Open` it is moot. Under `OwnersOrParticipants`/`Owners`, define whether the gate
   reads the originating wallet's proofs (already threaded), the source fiber's id, or both. Today
   `acceptedCallers` governs the *fiber* caller and `proofs` the *wallet* caller, deliberately orthogonal
   (`policyShortCircuit` comment, `FiberEvaluator.scala:124-127`). Recommendation: `transitionPolicy` gates
   the **wallet/owner** axis only; `acceptedCallers` remains the fiber-caller axis; they compose.
5. **F8 default owners.** Should an omitted `ownersExpr` inherit the parent's owners (today) or default to
   the participants seeded into the child's `initialData`? Settle in the SDK helper (Proposal 00) before any
   chain-side default change.
