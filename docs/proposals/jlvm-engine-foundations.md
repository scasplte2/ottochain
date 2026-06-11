# JLVM Fiber Engine — foundations, refactors, and the right theory

**Status:** draft / design. Date: 2026-06-04. Branch: `feat/versionable-contracts`.
**Scope:** (1) concrete engine refactors (gas seam, state unification, error channel); (2) which
*category-theory* concepts genuinely improve the engine; (3) the **computation-theory / streams** lens
(the engine's real lineage). Discipline throughout, learned from tessellation's vestigial `Cell`: a
concept earns inclusion **only if it deletes code, removes a footgun, or makes a law checkable** — never
because it is "tidy." (See `docs/TOPOS-FIBER-CATEGORICAL-ASSESSMENT.md`.)

---

## Part 1 — Concrete refactors (do these regardless of any theory)

### 1.1 Gas seam — ottochain-only, no metakit change

metakit's contract is already the right boundary: `evaluateWithGas(expr, ctx, gasLimit, gasConfig)` takes
a budget, raises `GasExhaustedException` past it, and **returns** `EvaluationResult.gasUsed`. The friction
is that ottochain hand-rolls "call → match → `chargeGas` → map error" at 10+ sites
(`FiberEvaluator`, `EffectExtractor`, `TriggerHandler`), plus a `.liftTo[G]` each time.

**Fix:** one adapter algebra in ottochain.

```scala
trait MeteredEvaluator[G[_]] {
  // charges gas into ExecutionState, lifts F~>G once, surfaces failures as FailureReason
  def eval(expr: JsonLogicExpression, ctx: JsonLogicValue, phase: GasExhaustionPhase): G[Either[FailureReason, JsonLogicValue]]
}
```

`make[F, G]` reads remaining budget from `ExecutionState`+`FiberContext`, calls metakit in `F`, lifts via
the existing `F ~> G`, charges `gasUsed`, and converts `JsonLogicException` via `toFailureReason`. Every
call site collapses to `evaluator.eval(...)`. **metakit untouched.** The only reason to ever touch metakit
would be to push gas *budgeting* into the VM — but it already takes a `GasLimit`, so it already aborts
mid-evaluation. Nothing to do there.

### 1.2 Unify the two state notions

Today `ExecutionState` (gas/depth/cycle-set/logs) rides in `StateT`, but `CalculatedState` (the actual
fiber records) is **threaded by hand** through tuple returns and args — `TriggerDispatcher` even invents a
`QueueState(pending, txnState)` to carry it. That split is the biggest "creak."

**Fix:** one engine state, with optics onto the parts.

```scala
final case class EngineState(exec: ExecutionState, world: CalculatedState, queue: List[FiberTrigger])
type FiberT[F, A] = ReaderT[StateT[F, EngineState, *], FiberContext, A]
```

Fiber-record updates become `modify(_.focus(_.world)...)` (Monocle, already a dependency) instead of
return-tuple plumbing. Removes a whole class of "did we propagate the updated state?" bugs.

### 1.3 Single error channel

The oracle handler wraps everything in `EitherT[G, TriggerHandlerResult, *]` and unwraps with `.merge`,
re-lifting each step — because the oracle path invented its own result type. Collapse to the one
`Either[FailureReason, A]` channel used everywhere else (or `MonadError`-style `raise`/`handle` over
`FailureReason`). Removes the unwrap/rewrap boilerplate.

---

## Part 2 — Category-theory concepts that genuinely help (ranked by ROI)

> Each is tied to a concrete pain point and rated **adopt / consider / avoid**.

### A. Effects as data (free algebra / algebraic effects) — **ADOPT**
**Pain:** effects are evaluated, then *post-hoc scraped* for reserved keys (`EffectExtractor`), and
trigger/spawn payloads are **re-evaluated** in a fresh context — an "interpret, then re-interpret" hack.
**Change:** an effect yields a typed command structure
`FiberEffect = SetState | Trigger | OracleCall | Spawn | Emit` (a small free/command ADT) that the engine
*interprets*. The JSON `_`-keys become the surface syntax; the ADT is the semantics.
**Payoff:** kills the re-evaluation hack, makes gas/atomicity uniform across effect kinds, makes the
effect language explicit and testable. This is the highest-value categorical idea — and it is *exactly*
the design behind FS2's `Pull` (see Part 3), so it doubles as the "streams" answer.

### B. Monoidal accumulation (Writer) for the write-only state — **ADOPT (with 1.2)**
**Pain:** gas, logs, and emitted outputs are append-only, but they're poked into `StateT` by hand
("forgot to `chargeGas`" is a latent bug).
**Change:** treat the accumulators as monoids — gas (`Sum`), logs (`Chain`, the free monoid), outputs —
and accumulate them via the state's monoidal append rather than ad-hoc writes; the *budget/limit* lives in
`Reader`. Gas charging becomes automatic at the `MeteredEvaluator` boundary (§1.1).
**Payoff:** removes manual charge sites and the forget-to-charge footgun; clarifies "accumulate-only" vs
"read-modify-write" (the latter stays in `StateT`).

### C. `Traverse` + canonical order as the determinism contract — **ADOPT (cheap)**
**Pain:** determinism relies on `SortedMap`/ordered `traverse`, but `dependencies: Set[UUID]` is iterated
unordered (latent non-determinism flagged in the relationship review).
**Change:** make "consensus-visible ⇒ ordered traversal" a typed rule: forbid `Set`-iteration in the
combine path; require a `Traverse` over a canonically-ordered structure. A small `Deterministic[A]`
marker / lint keeps it honest.
**Payoff:** turns the determinism convention into something checkable; closes a real fork risk for ~free.

### D. Mealy/coalgebra view of the cascade — **CONSIDER**
A guarded transition `(state, event) → (state', outputs)` is a **Mealy machine**; the trigger cascade is
**Mealy-machine composition run to fixpoint**. Framing `TriggerDispatcher` as "compose transducers, drain
to fixpoint" can simplify it — but the current `tailRecM`-over-a-queue is already a faithful encoding, so
this is clarity, not deletion. Adopt the *vocabulary* (it seeds versioning's bisimulation law); rewrite
only if §1.2 makes it fall out naturally.

### E. Order theory (lattice/poset) for versions — **ADOPT (in the versioning RFC)**
Version constraints form a lattice; "latest compatible" is a join; deprecation/yank is a poset/status
order. This directly structures `resolve` in the versionable-contracts RFC. Order theory, not topos.

### Avoid (ceremony risk)
- **A `Topos`/`Cell` god-object over fibers+oracles** — they're a 2-case sum + dispatch; wrapping recreates
  the vestigial-`Cell` mistake.
- **Hylomorphisms/recursion-schemes over the JSON-Logic AST** — metakit owns the AST and evaluates it
  stack-safely already; re-skinning it buys nothing (and is literally what `Cell` reached for).
- **Comonadic context, profunctor-optic gymnastics, adjunction framing** — interesting, but no concrete
  win here today; revisit only if a specific pain demands them.

---

## Part 3 — Computation theory: the streams lineage (your FS2 intuition is right)

The instinct that "these state machines were inspired by FS2 streams — the directed nature" is **correct
and load-bearing**, and arguably a *better* lens for the *engine* than topos (topos is the better lens for
*versioning*). Two precise statements:

### 3.1 A guarded fiber IS a stream transducer
A state machine consuming a sequence of events and producing state-changes/outputs is a
`Pipe[F, Event, Output]` — an FS2 transducer / a Mealy machine (Part 2D). FS2's own core, `Pull[F, O, R]`,
is a **free monad over a stream-step functor** — i.e. *exactly* "effects as data, then interpret"
(Part 2A). So your two follow-up questions (CT concepts vs streams) converge on the **same** refactor: an
effect produces a directed structure of steps that the engine interprets. The "directedness" you intuited
is the transducer/coalgebra structure. (Historical note: tessellation's dead `PipeArrow` was literally
"make FS2 `Pipe` a cats `Arrow`" — the abandoned Cell apparatus reached for this exact idea; here it's
*live and apt* instead of vestigial.)

### 3.2 The decisive constraint: two regimes, and FS2 belongs to only one
FS2's *superpowers* are concurrency, backpressure, resource-safety, and time. **Inside metagraph consensus
those are forbidden** — concurrency ⇒ non-determinism ⇒ chain fork. So:

| Regime | Where | Use FS2? | Borrow from streams |
|---|---|---|---|
| **Edge / IO** | node ingest, gossip, HTTP/L1 API, webhook dispatch, *replaying* snapshot history | **Yes** — already how Tessellation works | full FS2 (concurrent, resource-safe) |
| **Consensus core** | the combine / fiber engine / cascade | **No** — must be pure & deterministic | only the *pure algebra*: transducer shape, `scan`/`fold` over ordered events, effects-as-data |

The mistake to avoid is pulling FS2's *concurrent* combinators (`parEvalMap`, `merge`, `concurrently`)
into the combine. Inside consensus, the cascade is a **pure deterministic `scan`/`unfoldEval` over a
canonically-ordered work queue** — which is precisely the `tailRecM` they already have. Borrow the
*shape*, not the runtime.

### 3.3 The computation-theory model that actually fits the engine
If we name the engine's true computational model, it is a **synchronous, deterministic reaction system**,
and three classical theories map onto it cleanly — useful as *specs*, not libraries:

- **Synchronous dataflow / Esterel-Lustre "synchronous hypothesis"** — a deterministic *reaction function*
  per logical instant ("tick"). The metagraph's "process all updates in this snapshot deterministically"
  **is** a synchronous reaction, with `SnapshotOrdinal` as the logical clock. This is the closest existing
  model to the combine; it justifies the no-concurrency-inside discipline as a *feature*, not a limitation.
- **Kahn Process Networks (KPN)** — a network of sequential processes over FIFO channels is **determinate
  regardless of scheduling**. This is the theorem to reach for **if ottochain ever parallelizes fiber
  evaluation** (sharding independent fibers): materialize the fiber relationship graph (assessment §3.2)
  as a KPN and KPN-determinacy *licenses* parallel execution without forking consensus. High-value,
  forward-looking.
- **Labelled transition systems / process calculi (CCS/π-calculus)** — the inter-fiber trigger graph is an
  LTS; **bisimulation** is its behavioral equivalence (the same notion versioning's commute law needs).
  π-calculus is a strikingly good fit for the *dynamic* topology: fresh-name generation = spawn directives,
  message passing = triggers. Good *spec vocabulary* for "relationships between fibers."

### 3.4 What to actually do with this
- **Now:** adopt the **effects-as-data / transducer** refactor (Part 2A = the pure stream core). Keep FS2
  only at the edges. Make "ordered traversal" the determinism contract (Part 2C). This makes the engine
  *simpler*, which is the stated goal.
- **Document the regime split** as an architectural invariant so no one pulls concurrency into combine.
- **Later (RFC):** if parallel/sharded evaluation is ever wanted, design it as a **KPN over the
  materialized fiber graph** — that's the theory that keeps it deterministic.

---

## Summary

- **Refactors:** metered-evaluator (gas, ottochain-only), one `EngineState` + optics, single error channel.
- **CT to adopt:** effects-as-data (A), monoidal/Writer accumulation (B), Traverse+canonical-order (C);
  order-theory for versions (E, in the versioning RFC). Consider Mealy/coalgebra (D). Avoid topos-as-object.
- **Streams:** your FS2 intuition names the right *shape* (transducer / `Pull` = effects-as-data). The
  discipline is the **two-regime split** — FS2 at the edges, a pure deterministic synchronous reaction in
  consensus. The fitting computation-theory models are synchronous dataflow (now), KPN (if parallelized),
  and LTS/π-calculus (spec for relationships).
- Net: the *simplest* win is **A + the streams shape + 1.2** — they collapse the re-evaluation hack and the
  dual-state plumbing into one directed, interpreted, deterministic pipeline. That's elegance that removes
  code, not adds it.
