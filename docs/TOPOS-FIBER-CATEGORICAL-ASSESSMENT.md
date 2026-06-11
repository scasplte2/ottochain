# Fibers, Oracles, and Topos — a categorical assessment

**Status:** investigation complete (read-only, 6-facet deep dive). Date: 2026-06-04.
**Question:** can the encapsulation of *state-machine fibers* and *script oracles* be cleaned up "as a
Topos or something likewise categorical"? Could a topos generalize **fibers**, or **relationships
between versionable JSON-Logic programs**? Where is *topos* useful as a concept?

> Context worth stating up front: the sibling project (tessellation-nakamoto) carries a kernel `Cell`
> construct that was *literally* an attempted Topos — `// TODO: was Topos but we aren't using it yet` —
> which went vestigial and is now being torn out (see `CELL-CONSTRUCT-ASSESSMENT.md` there). That is the
> cautionary tale this assessment is measured against: **category theory earns its keep as a design
> compass and a source of checkable laws, not as a runtime artifact you wrap your types in.**

---

## TL;DR

- **Do NOT encapsulate fibers+oracles "as a Topos."** The two fiber kinds are a 2-case sum with a
  dispatch (`FiberEvaluator`); wrapping them in a categorical god-object would recreate the Cell
  mistake — ceremony that deletes no code and rules out no bug.
- **The genuinely-fitting categorical tools here are not "topos" but three more specific ones:**
  **coalgebras** (the state machines), **a free category on a message graph** (the inter-fiber
  relationships), and **a presheaf/diagram over a version poset** (versioning). A *topos* is only the
  *ambient setting* these live in (presheaf toposes), which buys you an *internal logic* — useful for
  specification/verification, not for the engine's plumbing.
- **The single highest-ROI categorical bet is versioning.** It is *entirely unbuilt today* (no version
  field, no definition hash, no upgrade update, no migration — confirmed across the model + proto), and
  the categorical framing hands you exactly the law you need for safe on-chain hot-upgrades:
  **a behavior-preserving upgrade is a natural transformation — "migrate-then-step = step-then-migrate."**
  That framing is valuable; the *implementation* should remain plain records + functions + property tests.
- **The real cleanups the code is asking for are MTL-hygiene, not category theory:** unify the two
  state notions (`ExecutionState` vs hand-threaded `CalculatedState`), tame the dual gas channels /
  `.liftTo[G]` / `EitherT` re-wrapping at the metakit seam, and materialize the inter-fiber graph for
  pre-validation. None of these is a topos.

---

## 1. What ottochain actually is (compressed)

A Constellation/Tessellation metagraph that runs a **deterministic JSON-Logic VM** for two kinds of
on-chain **fibers**:

| Kind | Record | Input | Body |
|---|---|---|---|
| State machine | `StateMachineFiberRecord` (`models/.../Records.scala:28`) | `Transition(eventName, payload)` | guarded transitions: `guard`/`effect` are `JsonLogicExpression` (`Transition.scala:13`) |
| Script oracle | `ScriptFiberRecord` (`Records.scala:46`) | `MethodCall(method, args, caller)` | `scriptProgram: JsonLogicExpression`, access-controlled |

- **VM = metakit** (external lib `io.constellationnetwork:metakit:1.7.0-rc.9`):
  `JsonLogicEvaluator.tailRecursive[F]: F[Either[JsonLogicException, EvaluationResult]]` — gas
  **returned**, not threaded.
- **Engine MTL stack** (`shared-data/.../fiber/core/package.scala:12,15`):
  `ExecutionT[F,A] = StateT[F, ExecutionState, A]`, `FiberT[F,A] = ReaderT[ExecutionT[F,*], FiberContext, A]`,
  with tagless-final algebras (`FiberEvaluator`, `TriggerDispatcher`, `SpawnProcessor`, `ContextProvider`,
  `StateMerger`, …).
- **Metagraph seam** (`shared-data/.../lifecycle/Combiner.scala:42`): the state-transition function is
  `insert(prev: DataState[OnChain, CalculatedState], u: Signed[OttochainMessage]): F[DataState]`,
  dispatching to `FiberCombiner`/`ScriptCombiner` → `FiberEngine.process`.
- **State split:** `OnChain` = `SortedMap[UUID, FiberCommit]` of hashes (`OnChain.scala`); `CalculatedState`
  = `SortedMap[UUID, …Record]` (`CalculatedState.scala:14`). **No MPT** — the whole `CalculatedState` is
  hashed (`ML0Service.hashCalculatedState`).
- **Inter-fiber relationships** = an **un-materialized runtime DAG**: persistent parent↔child edges,
  *static* `dependencies: Set[UUID]` per transition (read-only state views), and *dynamic*
  triggers/oracle-calls/spawns extracted from effect results via reserved keys (`_triggers`,
  `_oracleCall`, `_spawn`, `_emit`). Cycles caught only at execution (`processedInputs` set), never
  pre-validated. No graph object exists.
- **Versioning:** **absent.** Definitions are embedded inline at creation and immutable-by-absence — no
  `version` field, no definition hash, no `ReplaceDefinition`/`Upgrade` update, no registry, no lineage
  (`parentFiberId` is an *instance* link; spawned children may carry entirely different definitions).
- **Determinism** is load-bearing for consensus: canonical update ordering (`Updates.ordering`),
  `SortedMap`, immutable `FiberContext`, sealed `TransactionResult`, randomness only from `lastSnapshotHash`.

## 2. The question, reframed: three senses of "topos"

People mean three different things by "use a topos":

1. **A god-object abstraction your types extend** (the `Cell`/`Topos` Scala-trait sense).
2. **The ambient category your structures live in**, which — when it's a presheaf/sheaf topos — comes
   with a *subobject classifier `Ω`* and an *internal higher-order intuitionistic logic*.
3. **A design compass**: the categorical vocabulary (functor, coalgebra, natural transformation,
   sheaf/gluing) that names the right structure and the laws it must satisfy.

Sense (1) is what tessellation tried with `Cell` and what you should refuse here. Senses (2) and (3)
are where the value is — and they point at **coalgebras, free categories, and presheaves**, with the
topos appearing as the setting rather than the tool.

## 3. Where category theory genuinely fits (and what each buys)

### 3.1 A state machine is a **coalgebra** (right tool), living in a presheaf **topos** (the setting)

The honest categorical model of a guarded, effectful state machine is a **coalgebra** of a polynomial
functor — roughly `S → (Event ⇒ (1 + S × Output))` — i.e., Rutten-style universal coalgebra, whose
canonical equivalence is **bisimulation** (behavioral equality). This is the tool that *fits the
dynamics*, and it directly seeds §3.3 (a behavior-preserving upgrade = a coalgebra morphism).

The *total, unguarded* fragment (a set of states with an `Event*`-action) is a **presheaf on the
one-object category of the event monoid** — i.e. an `M`-set — and `M`-sets form a **topos**. In that
topos:
- **subobjects = transition-closed state subsets = invariants**, and
- **guards are characteristic maps into `Ω = Bool`** (`guard: Context → BoolValue` is exactly a map to
  the subobject classifier).

So the topos's `Ω` is not imaginary here — it's `Bool`, and your guards literally are `Ω`-classifiers;
the Heyting/Boolean algebra of guard-definable regions is the topos's internal logic. **Payoff:** this
is a *verification/specification* lens (reason about invariants, compose guards with logical laws,
prove a guard set partitions the state space). It would matter for a **guard/invariant static-checker**.
It does **nothing** for the engine's runtime. Real concept, speculative ROI today.

### 3.2 Inter-fiber relationships are a **category/graph**, not a topos

Vertices = fibers; edges = parent/child (static) + dependencies (static reads) + triggers/calls/spawns
(dynamic). The right object is **the free category on the message multigraph** (or just a materialized
`FiberRelationshipIndex`). Making it explicit buys concrete things the code lacks today:
- **pre-validate cycles** (today only caught mid-execution via `processedInputs`),
- **impact/reachability analysis** ("if X changes, who reads it" — the dependency edges),
- cleaner cascade reasoning.

This is exactly the index the relationship facet already recommended. It is a graph/category
materialization — **decidedly not** topos machinery.

### 3.3 Dependencies form a **sheaf over the snapshot site — but a *degenerate* one**

A transition reads its `dependencies`' state *all at the same snapshot ordinal* (resolved against one
`CalculatedState`). "All local reads agree on one global snapshot" is precisely a **gluing condition** —
the signature of a **sheaf**. But the site is essentially a *point* (one global snapshot), so the sheaf
condition is **automatically satisfied and trivial**: there is exactly one global section, and there is
no inconsistent local data to glue. **A topos buys nothing where the site is a point.** This is an
important honesty check: the very feature that makes sheaves powerful (locality, partial/over­lapping
views, gluing) is exactly what a global-snapshot blockchain *eliminates by fiat*.

Where it would become **non-degenerate**: the `state-commitment-mpt` proposal (per-fiber MPT roots +
cross-metagraph inclusion proofs). There, each metagraph's state is a genuine *local section* glued
against a shared root via Merkle proofs — a real presheaf over the hypergraph. Even then the gluing is
mediated by **proofs**, not topos internal logic; the categorical view *organizes* it but you implement
MPT proofs.

### 3.4 Versioning is a **presheaf over a version poset** — the one high-ROI bet

This is where the categorical framing is *most* apt **and** the code is *most* absent — a rare
alignment. Model:
- **Versions form a poset/DAG `P`** of revisions.
- **A versioned program is a diagram/presheaf `P → Cat`** assigning a definition (an automaton) to each
  version; the functor's action on `v ≤ v'` is the **migration**. (Presheaves on a poset form a
  Grothendieck topos — the topos is again just the ambient setting.)
- **A behavior-preserving in-place upgrade is a natural transformation** between the old and new
  behavior functors — equivalently a **coalgebra morphism / bisimulation** (ties back to §3.1). Spelled
  out, the law is the commuting square:

  > **migrate(step(s, e)) = step(migrate(s), e)** — *migrating then stepping equals stepping then
  > migrating* (modulo the events that survive the upgrade).

That commute law is *exactly* the safety property you want for hot-upgrading a deployed contract while
instances are mid-flight. The categorical lens hands it to you for free and tells you a migration that
*isn't* a natural transformation is unsafe. **Payoff is concrete and checkable** (it's a property test
and an admission-time validator), and it directly fills the gap the domain facet flagged.

**But implement it plainly:** you need (1) a version poset / content-addressed definition hashes, (2) a
`MigrateDefinition` update carrying old→new + a state-migration expression, (3) the commute property as
a test + a combine-time check. You do **not** need — and should not build — a `Topos`/`Cell`-style
abstraction in Scala to get this. The math is the spec; the code is records and functions.

## 4. Where a topos would be ceremony (the Cell trap, re-stated for ottochain)

- **"Encapsulate state machines and oracles as one Topos object."** They're a sealed 2-case sum already
  cleanly dispatched in `FiberEvaluator`. A categorical wrapper adds indirection, deletes nothing,
  prevents no bug. This is the exact shape of tessellation's `Cell` (a dispatch that extended a
  categorical trait and never used the machinery).
- **"Route evaluation through a hylomorphism / recursion-scheme because it's elegant."** metakit already
  evaluates expressions (with a stack-safe `tailRecursive`), and the cascade uses `tailRecM`. Re-skinning
  working code as a topos morphism is speculative rewrite, and consensus code is the worst place to take
  elegance risk.
- **Symptom to watch for:** if a proposed abstraction's payoff is "it's categorically uniform" rather
  than "it deletes this code / checks this law / rules out this fork," it's ceremony.

## 5. The refactors the code is actually asking for (none are topos)

The deep dive surfaced real, fixable friction — all MTL/engineering, not categorical:

1. **Two un-integrated state notions.** `ExecutionState` rides in `StateT`, but `CalculatedState` (the
   actual fiber records) is threaded *by hand* through tuple returns and function args
   (`TriggerDispatcher` invents a `QueueState(pending, txnState)` to carry it). **Fix:** put
   `CalculatedState` in the monad too (second `StateT` layer or a combined state) so fiber updates flow
   implicitly. Biggest single creak.
2. **The metakit seam.** Dual gas channels (metakit *returns* gas; the engine *charges* it via `StateT`
   at 10+ sites), pervasive `.liftTo[G]`, and `EitherT[G, TriggerHandlerResult, *]` re-wrapping in the
   oracle handler. **Fix:** wrap the metakit call in one algebra that charges gas and lifts once; pick a
   single error channel.
3. **Un-materialized relationship graph + runtime-only cycle detection** → materialize the index (§3.2).
4. **No versioning** → design it (§3.4) as the one place to *spend* categorical thinking.

## 6. Recommendation

1. **Reject "fibers+oracles as a Topos object."** Keep the sealed sum + dispatch. (Same call as Cell.)
2. **Spend the category theory on versioning (§3.4)** — it's unbuilt, it's the most categorically-apt
   part, and it yields a checkable hot-upgrade safety law. Treat coalgebra/natural-transformation as the
   *spec*; ship plain records + a `MigrateDefinition` update + a commute property test. This is the
   deliverable worth an RFC.
3. **Do the MTL-hygiene refactors (§5.1–5.3)** independently; they improve the engine regardless of any
   categorical narrative.
4. **Hold "topos as verification substrate" (§3.1, guard-invariant logic) and "non-degenerate sheaf"
   (§3.3, cross-metagraph MPT) as future RFCs**, explicitly contingent on those features existing.

**Net:** a topos is useful here as a *concept that names laws and organizes the cross-metagraph/versioning
future* — not as an abstraction to implement in the engine. The most valuable categorical move is to let
the **presheaf-over-versions + migration-as-natural-transformation** view drive the (currently missing)
upgrade design; everything else the code wants is monad-transformer cleanup.

## Key files (anchors)
- Engine MTL: `shared-data/.../fiber/core/package.scala:12,15`, `ExecutionState.scala`, `ExecutionOps.scala`
- Evaluator/seam: `shared-data/.../fiber/evaluation/FiberEvaluator.scala`, `EffectExtractor.scala`; metakit `JsonLogicEvaluator.tailRecursive[F]`
- Relationships: `fiber/triggers/TriggerDispatcher.scala`, `fiber/spawning/SpawnProcessor.scala`, `Transition.scala:19` (`dependencies`)
- Metagraph seam: `lifecycle/Combiner.scala:42`, `combine/FiberCombiner.scala`, `l0/.../ML0Service.scala`
- Model/versioning gap: `models/.../schema/Records.scala:28,46`, `StateMachineDefinition.scala`, `Updates.scala`, `OnChain.scala`, `CalculatedState.scala:14`
