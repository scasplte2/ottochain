# Fiber Authoring Safety — Offline Definition Validator & State-Shape Defaults — RFC

**Status:** draft / design. **Date:** 2026-06-25. **Addresses:** F4, F5, F9, F10 (see
[`README.md`](./README.md)). **Risk:** Low (additive advisory tooling — no chain change).

A fiber definition is hand-written JSON-Logic (`states` / `transitions` with deeply nested
`{"var":…}` / `{"+":[…]}`). There is no offline safety net: a typo'd directive key, an unseeded
accumulator, a `var` into a path the engine never populates, an unreachable state, or a guard reading
an undeclared `machines.$id` all parse, sign, submit, and **only misbehave at combine** — silently, on
the cluster, with no error pointing at the source. This RFC proposes two additive moves, both off-chain
and non-consensus:

- **(a) a pure, offline `validateDefinition`** — resolves every `var` path against the known context
  roots, rejects unknown `_`-directive keys, checks reachability, checks effect-output conformance, and
  surfaces the existing depth/gas limits — as structured diagnostics, before the message is signed.
- **(b) a declared STATE-SHAPE with DEFAULTS** — let a definition declare its state fields and their
  zero-values (`0` / `""` / `[]`) so accumulators auto-initialize and the validator can check reads,
  applied **off-chain when building `initialData`** so the signed canonical is unchanged.

It **extends** the existing conformance machinery (`ConformanceChecker`, `MessageShape`,
`SchemaShape`) rather than reinventing it, and implements the *static* conformance check that
[`strong-typing-and-conformance.md`](./../strong-typing-and-conformance.md) §3 specified but never built.

**Companion proposals:**
- [`00-sdk-stdlib-and-templates.md`](./00-sdk-stdlib-and-templates.md) — the SDK builders that *emit*
  correct forms; this validator is what they validate against, and the safety net for everyone still
  hand-rolling.
- [`strong-typing-and-conformance.md`](./../strong-typing-and-conformance.md) — the two dials (binding
  strength / conformance) and the `SchemaShape` projection the validator and state-defaults plug into.
- [`schema-architecture.md`](./../schema-architecture.md) — the registry/schema commitment model.
- `docs/signing-canonical-and-validation.md` — invariant #1 (Option/omit-safe; no defaulted signed
  fields), which the state-defaults design must respect.

---

## 0. Today (baseline)

A definition is authored as raw JSON (`e2e-test/examples/riverdale-economy/*.definition.json`), base64'd,
and submitted. Some **structural** sanity checks exist — but they run **on the cluster**, at
block-acceptance (`Validator.validateSignedUpdate`,
`modules/shared-data/.../lifecycle/Validator.scala`) and combine, never offline pre-send, and they do
**not** cover the footguns below. The checks that *do* exist, in
`modules/shared-data/.../lifecycle/validate/rules/FiberRules.scala`:

- `L1.validStateMachineDefinition` (`FiberRules.scala:38`) — `states` non-empty; `initialState` is a
  known state; every `transition.from`/`.to` references a declared state; no exact-duplicate and no
  ambiguous (same `from`+`eventName`, unconditional) transitions.
- `L1.definitionWithinLimits` (`:96`) — `MaxStates` / `MaxTransitions` caps.
- `L1.definitionExpressionsWithinDepthLimits` (`:128`) — guard/effect expression depth caps.
- `L1.noReservedOperatorFieldNames` (`:149`) — a *state field* may not collide with a JSON-Logic
  **operator** name (`var`, `+`, …). **Note:** this is the operator namespace, **not** the `_`-directive
  set — a typo'd `_triggers` sails straight through it.

None of these resolve `var` paths, validate directive-key spelling, check reachability, check
state-shape conformance of reads/writes, or run before the cluster sees the message. The five concrete
failure modes an author hits:

### (i) Typo'd reserved key — silent, no error (F4)

`ReservedKeys.isInternal(key) = key.startsWith("_")`
(`modules/models/.../schema/fiber/ReservedKeys.scala:121`). The effect object is dual-purpose: a
`_`-prefixed key is a directive, every other key merges into state. `StateMerger.mergeMapValue` filters
out **any** `_`-key before merging (`modules/shared-data/.../fiber/evaluation/StateMerger.scala:70`),
and `EffectExtractor` only ever pulls the **known** directive names (`_triggers`, `_spawn`,
`_scriptCall`, `_emit`, `_transferAsset`, `_addDependency`, `_setDependencyActive` —
`ReservedKeys.scala:12-18`, dispatched at `EffectExtractor.scala:77-91`). So there are **two** silent
failure modes, neither raising an error:

| Mistake | `isInternal`? | Outcome |
|---|---|---|
| `"_triger": [...]` (misspell, underscore kept) | `true` → stripped by `StateMerger` | matched by **no** extractor → the trigger **never fires**, and it is **not** in state. Vanishes. |
| `"transferAsset": [...]` (dropped the underscore) | `false` → merged into state | becomes a **junk state field** named `transferAsset`; the transfer **never happens**. |

`StateMerger`'s array form behaves identically (`StateMerger.scala:86-90`). The author sees a
transition that "succeeds" but does nothing, with no diagnostic.

### (ii) Missing state field — null silently coerces to 0 (F5)

`{"var":"state.taxesPaid"}` on a `taxesPaid` that was never seeded does **not** error. metakit's var
resolver returns `NullValue` for an absent map key
(`io.constellationnetwork.metagraph_sdk.json_logic` — `JsonLogicSemantics.getVar` → `getChild`,
`case None => NullValue.asRight`), and `lookupVar` only substitutes a default when the `{"var":["x",0]}`
two-arg form is used (`JsonLogicRuntime.lookupVar`). The null then flows into arithmetic, where
`NumericOps.promoteToNumeric(NullValue) = IntResult(0)` — so `{"+":[{"var":"state.taxesPaid"},…]}`
silently treats the missing accumulator as `0`. The author's only defenses today are **seed every
accumulator by hand** in `initialData` (e.g. `manufacturer.initial.json` carries `"taxesPaid": 0` for
the `{"+":[{"var":"state.taxesPaid"},…]}` read in `manufacturer.definition.json:38`), or use the
clunkier `{"var":["taxesPaid",0]}` default form. Forget one accumulator (`consumer.definition.json`
alone seeds `purchaseCount`, `paymentsMade`, `taxesPaid`, `activeListings`, `loanBalance`, `balance`,
`marketplaceSales`) and the bug is a wrong number, never an error.

### (iii) `var` into a nonexistent path — same silent null

A typo'd read — `{"var":"state.invetory"}`, or `{"var":"event.qty"}` when the command field is
`quantity` — resolves to `NullValue` by the exact same path (`getChild` `None => NullValue`), coerces
to `0`/empty, and a guard like `{">=":[{"var":"state.invetory"},{"var":"event.quantity"}]}`
(cf. `manufacturer.definition.json:12`) misfires (`0 >= q`) with no signal.

### (iv) Unreachable / dead-end state

`FiberRules` checks transitions reference *declared* states but **not** reachability from
`initialState`, nor that a non-`isFinal` state has an outgoing transition. A state added but never
wired (`marketplace_selling` reachable only via a `list_item` that was renamed) is dead weight the chain
happily accepts.

### (v) Guard/effect referencing an undeclared `machines.$id`

Cross-fiber reads are dependency-gated (F6): a `{"var":"machines.<id>.state.…"}` read resolves only if
`<id>` is in the transition's declared `dependencies` — `ContextProvider.buildMachinesContext` populates
`machines` **only** from the declared dependency set
(`modules/shared-data/.../fiber/core/ContextProvider.scala:266`). Read a `machines.<id>` that was never
declared (or a `parent.…` with no parent) and you get `NullValue`, silently — the asymmetry F6
describes, with no author-time warning.

The root cause (F10): the model is invisible until combine time. Every one of these is statically
detectable from the definition alone.

---

## 1. Goals & non-goals

**Goals.**
1. A **pure, offline, deterministic** `validateDefinition` that an author (or the SDK, or the e2e
   runner) runs **before signing**, returning structured diagnostics that point at the offending
   transition/path.
2. **Kill F4 outright**: an unknown `_`-key is a hard validator **error** (it can only be a typo or an
   unimplemented directive).
3. **Catch F5/F10 before the cluster**: flag every `var` read of an undeclared state field, and let a
   declared **state-shape with defaults** auto-initialize accumulators.
4. **Extend, don't reinvent**: reuse `MessageShape`/`SchemaShape` and the static-conformance design
   already written in `strong-typing-and-conformance.md` §3.

**Non-goals.**
- **No chain change, no consensus surface.** The validator is advisory tooling; it never gates block
  acceptance or combine. (Whether a *strict* combine reject is offered later is an open question, §6.)
- **Not a type system for the JLVM logic.** Guards/effects stay generic `JsonLogicExpression`
  (the "describe + bind, don't constrain" principle, `strong-typing-and-conformance.md` §0.5). The
  validator checks *var-path resolvability and key spelling*, not totality or value types of arbitrary
  expressions.
- **Not a replacement** for the runtime `ConformanceChecker` (which gates *produced values* at combine
  for `strict` versions) — it is the complementary *static* gate over the *definition*.

---

## 2. The validator — `validateDefinition`

### Signature & placement

A **pure** function — no `F[_]`, no `IO`, no `CalculatedState`, no network:

```scala
def validateDefinition(
  definition: StateMachineDefinition,        // already a first-class typed value on-chain
  shape:      Option[MachineShape] = None,   // the typed schema projection, if the author has one
  deps:       Option[DeclaredDeps] = None    // declared machines.$id / scripts.$id, parent presence
): List[Diagnostic]                          // empty == clean; advisory, ordered, source-located
```

```scala
final case class Diagnostic(
  severity: Severity,           // Error | Warning | Info
  code:     String,             // stable, e.g. "unknown-directive", "undeclared-state-read"
  message:  String,
  location: Location            // transitionIndex, field ("guard"|"effect"|"initialData"), var-path
)
sealed trait Severity
```

**Where it lives.** The vocabulary it needs — the reserved directive set, the context roots, the typed
`StateMachineDefinition`, `MessageShape`/`SchemaShape` — already lives in **ottochain** (`ReservedKeys`,
`schema/registry/SchemaShape.scala`, `ConformanceChecker`). So the **pure core** belongs alongside them
in ottochain (`modules/shared-data/.../fiber/`, next to `ConformanceChecker`), as a no-`F` object
(`DefinitionLinter` / `validateDefinition`). It is then consumed by:

- **the e2e runner** — as a JVM **pre-send dry-run**: lint every `*.definition.json` before submitting,
  fail fast with the diagnostic list (no cluster round-trip to discover a typo).
- **the SDK** (`@ottochain/sdk`, Proposal 00) — a **TypeScript mirror** of the same logic, so authors
  get the check in their editor/CI; the SDK's typed builders emit forms that lint clean by construction.

The single generic primitive it builds on — *"extract every `var`-path and every map-key from a
`JsonLogicExpression` AST"* (plus the existing expression-depth walk) — is logic-generic and could be
contributed to **metakit** as a pure helper, so the Scala and TS validators share one AST-walk spec.
(See §5 for metakit-vs-ottochain placement trade-offs.) The check is **non-consensus** either way: it
runs in tooling, never in `validateSignedUpdate` or a combiner.

### What it checks

**(a) Every `var` path resolves against a known root.** Extract every `{"var":"…"}` (and the
`{"var":["…",default]}` form) from every guard, effect, trigger payload, script-call args, and `_spawn`
sub-expression. Resolve the **first segment** against the context roots the engine actually injects
(`ReservedKeys.scala:63-97`, built in `ContextProvider.buildStateMachineContext`,
`ContextProvider.scala:164-186`):

| Root | Resolves against | Source |
|---|---|---|
| `state.X` | the declared **state-shape** fields (else a Warning that the field is unseeded → reads null) | `ContextProvider.scala:166` |
| `event.X` | the **command shape** for *this* transition's `eventName` (`MachineShape.commands(eventName)`) | `:167` |
| `machines.<id>.…` | the transition's **declared `dependencies`** (else Error: undeclared cross-fiber read — failure mode (v)) | `:180`, `:266` |
| `scripts.<id>.…` | declared script dependencies | `:183`, `:287` |
| `parent.…` / `children.…` | present only if the fiber is spawned / has children | `:181-182` |
| `heldAssets.<id>.{behavior,amount,expiresAt}` | reserved asset projection (dynamic key open; leaf keys checked) | `:184`, `:108-124` |
| `$ordinal`, `$lastSnapshotHash`, `$epochProgress`, `$caller`, `proofs`, `machineId`, `currentStateId`, `sequenceNumber`, `eventName` | reserved roots — always present, well-typed | `:169-179` |

An unknown first segment (`{"var":"stat.x"}`, `{"var":"machine.y"}`) is an **Error** — it can only ever
resolve to `null`. A known root with an undeclared sub-field (`state.taxesPaidd`) is the heart of
**(iii)**: an Error against a declared state-shape, a Warning without one.

**(b) Every `_`-key is a KNOWN reserved directive (kills F4).** Walk every effect-output map. Any
key where `ReservedKeys.isInternal(key)` is `true` (`startsWith("_")`) **must** be one of the known
directive constants (`ReservedKeys.scala:12-18`). An unknown `_`-key — `_triger`, `_transfers`,
`_trigger` — is a hard **Error** (`code: "unknown-directive"`): the engine would silently strip it
(failure mode (i), first row). Symmetrically, a **non-`_` key whose name is one-edit-distance from a
known directive** (`transferAsset`, `triggers`, `spawn`) is a **Warning** (`"likely-dropped-underscore"`)
— failure mode (i), second row, where it becomes a junk state field. This is the check that closes F4.

**(c) State / transition reachability.** BFS from `definition.initialState` over `transitions`; any
state not reached is an **Error** (`"unreachable-state"`). Any non-`isFinal` state with **no** outgoing
transition is a **Warning** (`"dead-end-state"`). Complements `FiberRules.validStateMachineDefinition`
(`:38`), which checks references but not reachability (failure mode (iv)).

**(d) Conformance of effect output to the state-shape.** This implements
`strong-typing-and-conformance.md` §3's static conformance — *the check that was specified but never
built* (the runtime `ConformanceChecker` only checks produced **values** at combine,
`ConformanceChecker.scala:50`). Statically, given a `MachineShape`:

- **Write conformance:** every effect-output key (minus `_`-directives) must be a declared field of
  `shape.stateMessage` (reusing the `MessageShape`/`FieldShape` model, `SchemaShape.scala:24-36`). ⇒ the
  machine never writes an undeclared field. (Mirrors `ConformanceChecker.check`,
  `ConformanceChecker.scala:29`, but over *static keys* instead of a produced value.)
- **Read conformance:** every `var state.X` must be a declared `stateMessage` field (= (a) with a
  shape).
- **Event conformance:** every `transition.eventName` must have a `commands[eventName]` message
  (`MachineShape.commands`, `SchemaShape.scala:44-47`), and every `var event.X` must be a field of it.

Without a `shape`, (d) degrades to *internal consistency* — a field that is **written** by some
transition is treated as a known state field for **read** checks, so an accumulator written in one
transition and read in another lints clean even with no schema.

**(e) Expression-depth / gas sanity.** Surface the **existing** caps offline so the author sees them
before the cluster does: run `FiberRules.definitionExpressionsWithinDepthLimits`
(`FiberRules.scala:128`) and a static `FiberGasEstimator` pass
(`modules/shared-data/.../fiber/FiberGasEstimator.scala`) and report any guard/effect over the depth cap
or whose estimated worst-case gas exceeds the per-transition budget — as Errors (depth) / Warnings
(gas), not as new limits.

### Diagnostic example

```
manufacturer.definition.json
  ERROR  unknown-directive        t[0].effect            key "_transferAssets" is not a directive
                                                         (did you mean "_transferAsset"?)
  ERROR  undeclared-state-read    t[1].effect "taxesPaid" {"var":"state.taxesPaid"} — field not in
                                                         state-shape and never written (reads null → 0)
  WARN   undeclared-dep-read      t[0].guard             {"var":"machines.<uuid>.state.x"} — <uuid> not
                                                         in transition.dependencies (resolves to null)
  ERROR  unreachable-state        states.archived        not reachable from initialState "stocked"
```

---

## 3. State-shape with defaults

### The shape

Let a definition (or its schema) declare its state fields **and their zero-values**, so accumulators
auto-initialize and (a)/(d) have a field list to check against. This is a thin extension of the existing
`MessageShape`/`FieldShape` (`SchemaShape.scala:24-36`) — conceptually a per-field `default`:

```jsonc
// authoring-side state-shape (off-chain artifact, NOT a new on-chain field — see §4)
{
  "taxesPaid":      { "type": "uint64", "default": 0  },
  "purchaseCount":  { "type": "uint64", "default": 0  },
  "status":         { "type": "string", "default": "" },
  "activeListings": { "type": "uint64", "default": 0  }
}
```

Defaults are the JSON-Logic zero-values — `0` (`IntValue`/`FloatValue`), `""` (`StrValue`), `[]`
(`ArrayValue`), `{}` (`MapValue`), matching how the absent-then-coerced read behaves today
(`NumericOps.promoteToNumeric(NullValue)=0`) — so applying a default is **semantics-preserving** for the
`{"+":[…]}` accumulator pattern, only now it is explicit and check-able.

### When defaults are applied — OFF-CHAIN at create-time (recommended)

Two options; we **prefer the off-chain/SDK path**:

- **Off-chain (recommended).** The SDK / genesis builder merges the declared defaults under the
  author-supplied `initialData` when constructing the `CreateStateMachine` message:
  `effectiveInitialData = defaults ++ authorInitialData` (author values win). The **submitted, signed
  message already carries the full state** — the chain sees an ordinary, fully-populated `initialData`,
  identical to today's hand-seeded `manufacturer.initial.json`. **Zero chain change, zero canonical
  change.**
- **Combiner (rejected).** Applying defaults inside the combiner at create would put a defaulting rule
  on the consensus path and change what a bare submitted `initialData` canonicalizes to — exactly the
  kind of decoder-side re-fill CLAUDE.md rule #1 forbids. Not pursued.

The validator's (a)/(d) checks consult the **same** declared state-shape, so "field reads as null"
(failure mode (ii)) becomes either *auto-seeded* (default present) or a *diagnostic* (no default, no
write) — never a silent `0`.

### Tie to existing machinery

The state-shape **is** a `MessageShape` over `FieldShape`s (`SchemaShape.scala`); the validator's write/
read conformance **is** the static form of `ConformanceChecker.check` (`ConformanceChecker.scala:29`).
The only new authoring concept is the per-field `default`, and it lives **off-chain** (see §4) — so the
on-chain `MessageShape` / strict `ConformanceChecker` gate are untouched.

---

## 4. Safety & compatibility

- **The validator is additive advisory tooling.** It introduces **no** new on-chain type, message, or
  combiner branch, and is never called from `validateSignedUpdate` or a combiner. Hand-rolled JSON keeps
  working unchanged; the validator only *reports*. Zero consensus risk.

- **State-defaults must NOT shift the signed canonical.** Defaults are applied **when building
  `initialData` off-chain**, so the submitted `CreateStateMachine` already carries the full state — the
  chain decodes a normal, complete `initialData`, with no field it must re-fill. This is the direct
  application of **CLAUDE.md rule #1** (a signed field is `Option`/omit-safe **or** required-no-default;
  a defaulted field re-encodes to a different `JCS(dropNulls)` and breaks `InvalidSignature`). Concretely:
  we do **not** add a `default` field to the on-chain `FieldShape`/`MessageShape` carried inside the
  signed `PublishVersion` / `CreateAssetPolicy` messages (that *would* be a canonical change, and would
  need a new case in `PublishVersionSigningCanonicalSuite`). The `default` lives only in the off-chain
  authoring artifact / proto field-options; the chain never sees it.

- **No `_`-key vocabulary drift.** The validator reads the directive set from `ReservedKeys`
  (`ReservedKeys.scala:12-18`) — the same source the engine dispatches on — so it cannot disagree with
  the runtime about which `_`-keys are real.

---

## 5. Alternatives & effort

**Full proto-typed state vs. lightweight shape.** We could generate a full state struct from the proto
descriptor and run a complete type-checker over every expression. Rejected: the chain already commits
only the **shallow** `MessageShape` projection (top-level fields + immediate primitive types,
`ConformanceChecker.scala:18`, `strong-typing-and-conformance.md` §2), and that is exactly what the
var-path/effect-key checks need. The lightweight shape is already on-chain, deterministic, and
sufficient; a full proto typechecker is heavier tooling for diminishing returns and is the
"over-constrain the computation" trap §0.5 warns against.

**Validator in metakit vs. ottochain-models vs. SDK-only.**
- *metakit* — maximally reusable, but the reserved-directive set and context roots are **ottochain's**
  (`ReservedKeys`), so metakit would need them injected; only the generic *AST var-path/depth walk* is
  truly metakit-shaped. ⇒ contribute that one primitive to metakit; keep the ottochain-specific tables
  out.
- *ottochain-models/shared-data (recommended)* — the natural home: it already has `ReservedKeys`,
  `StateMachineDefinition`, `SchemaShape`, `ConformanceChecker`. A pure object here is callable by the
  e2e runner directly.
- *SDK-only* — needed anyway (TS mirror, for editor/CI), but if it were the *only* home the JVM e2e
  runner couldn't dry-run. ⇒ build both: pure Scala core in ottochain + a TS mirror in the SDK, sharing
  the metakit AST-walk spec.

**Effort: Low.** Pure functions over types that already exist; property tests over the riverdale
definitions (every `*.definition.json` must lint clean; a corrupted copy with `_triger` /
`state.unseeded` must produce the matching diagnostic). No consensus code, no migration, no canonical
change. Estimated a few hundred LOC for the Scala core + the TS mirror, plus the off-chain default-merge
in the SDK builder.

---

## 6. Open questions

1. **Should an unknown `_`-key be a HARD combine reject too, behind a `FiberPolicy` flag?** The validator
   makes it an offline Error, but a malicious/careless author can still submit one and the engine
   silently strips it. A `strict-directives` opt-in on `FiberPolicy` could make an unknown `_`-key a
   graceful `CombineRejected → RejectionReceipt` (combiner-only, per CLAUDE.md rules #2/#3 — never in
   `validateSignedUpdate`, to avoid the TOCTOU block-poisoning hazard). Default off (additive); only
   apps that want fail-closed directive hygiene opt in.

2. **Severity policy for the e2e runner — fail or warn?** Should the pre-send dry-run *block* submission
   on any `Error`, or only on a configurable subset? A reachability Error is a hard bug; an
   `undeclared-dep-read` Warning may be intentional during exploration. Proposed: Errors block, Warnings
   print, both overridable per-run.

3. **Where do state-defaults live as the single source of truth** — a proto field-option (so codegen,
   the SDK builder, and the validator all derive from one descriptor), or a sidecar
   `*.state-shape.json`? The proto-option route keeps the three artifacts aligned
   (`strong-typing-and-conformance.md` §4) but couples authoring to the proto toolchain.

4. **Dynamic-key roots (`machines.<id>`, `heldAssets.<id>`).** The validator can resolve the *root* and
   the *leaf* keys but not the instance `<id>` (unknown at author time). How strict on the
   `_policy`/`heldAssets` sub-trees, and should the validator accept a supplied "expected dependency
   set" to tighten `machines.<id>` checking beyond "is it in `dependencies`"?

5. **Spawned-child `initialData` is built on-chain, so off-chain default-application can't reach it.**
   A `_spawn` directive's `initialData` is evaluated by the engine from the parent's effect context
   (`EffectExtractor.extractSpawnDirectivesFromExpression`, `EffectExtractor.scala:323`;
   `consumer.definition.json:145` builds a child's `initialData` inline). The SDK can't pre-merge
   defaults into a child that doesn't exist until the parent transitions. Do spawned children need a
   different defaulting story (validator-only checking of the inline `initialData`, or a combiner-side
   default keyed off the child's bound shape), and does that reopen the canonical question for the
   spawn path?
