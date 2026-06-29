# Fiber & Asset Authoring Ergonomics — Improvement Program

**Status:** proposal set (RFC) · **Branch:** `feat/fiber-ergonomics-proposals`
**Origin:** friction discovered authoring the `riverdale-economy` e2e (6 fibers, 2 versioned packages,
real asset custody, cross-fiber triggers, spawned auction, wallet morphisms — all hand-written JSON-Logic).

## North star

App authors should **consume vetted templates from the SDK and a genesis-loaded canonical std-lib**
instead of hand-rolling JSON-Logic, and should **see their mistakes before the cluster does** via a
dry-run validator. Most of the friction below is *not* "the model is wrong" — it's "the model is
invisible until combine time." Two high-leverage moves fix the bulk:

1. **SDK template library + genesis std-lib** — canonical builders that emit correct forms; standard
   packages (fungible/NFT policy, versioned-machine skeleton, guarded-transfer effect, spawn directive,
   migration) registered at genesis so apps reference rather than redefine. → [`00-sdk-stdlib-and-templates.md`](./00-sdk-stdlib-and-templates.md) (the SDK-repo **handoff** doc).
2. **Definition dry-run validator** — resolve every `var` path, validate reserved `_`-keys, check schema
   conformance + reachability, all offline. → [`01-authoring-safety.md`](./01-authoring-safety.md).

The remaining proposals are targeted model-clarity / canonicalization changes.

## Findings catalog (what hurt, and where it's addressed)

| # | Friction (from the riverdale-economy build) | Severity | Addressed by |
|---|---|---|---|
| F1 | **Asset custody is bifurcated**: a fiber-held asset CANNOT take `ApplyMorphism` (R1 `requireWalletHolder`) — fiber value moves only via the `_transferAsset` effect, whole-instance. Forced a redesign (separate pre-minted instances instead of fractionalizing a loan). | High | [02](./02-asset-effect-ergonomics.md), [00](./00-sdk-stdlib-and-templates.md) |
| F2 | **`_transferAsset` recipient was a BARE STRING** (UUID→Fiber, DAG-addr→Wallet via `parseRecipient`) — shaped *unlike* the `{Fiber:{fiberId}}`/`{Wallet:{address}}` `AssetHolder` it becomes everywhere else. **Resolved (#193): object form ONLY + fail-loud; bare string removed.** | Med | [02](./02-asset-effect-ergonomics.md) |
| F3 | **"Who holds it" decides "whose effect transfers it"** — the GOODS→consumer leg had to live on the *retailer's* `process_sale` effect (it holds the GOODS), not the consumer's `buy`. Custody-aware effect placement. | Med | [02](./02-asset-effect-ergonomics.md), [00](./00-sdk-stdlib-and-templates.md) |
| F4 | **Effect object is dual-purpose**: `_`-prefixed keys are reserved directives (stripped by `StateMerger`), everything else merges into state — so a typo'd `_trigger`/`_transfer` silently becomes a *state field* with no error. | Med | [01](./01-authoring-safety.md) |
| F5 | **No state-field defaults**: `{"+":[{"var":"state.x"},…]}` on an unseeded `x` reads null and misbehaves; every accumulator must be seeded in `initialData` (or use the clunkier `{"var":["x",0]}`). | Med | [01](./01-authoring-safety.md) |
| F6 | **Cross-fiber asymmetry**: a `_triggers` needs NO declared dependency (only `FiberPolicy.acceptedCallers`), but a `machines.$id.state` READ requires declaring the dependency. Opposite ceremony for "fire at" vs "look at". | Med | [03](./03-cross-fiber-and-authorization.md) |
| F7 | **Transition owner-gate DIVERGES by code path — a likely authorization GAP** (test-confirmed, `TransitionOwnerGateDivergenceSuite`): `validateSignedUpdate` ENFORCES `owners ∪ authorizedSigners` (rejects a non-owner), but the **combiner does NOT** — it applies a non-owner's transition (the guard is its only gate). The live ML0 path follows the combiner, so **in production transitions are effectively guard-only-gated** (our riverdale e2e, distinct keys, saw bob transition alice's fiber). The pre-existing `MultiPartyTransitionSigningSuite` encodes both halves and is self-contradictory. | **High (security)** | [03](./03-cross-fiber-and-authorization.md) |
| F8 | **Spawned-child transition auth** is the exception: a child's transitions are gated by `owners ∪ authorizedSigners`, so a bidder must be listed in `event.auctionOwners` or `place_bid` is ML0-rejected. | Low-Med | [03](./03-cross-fiber-and-authorization.md) |
| F9 | **Versioning foot-guns**: verified binding needs byte-identical publish/create definition files; lineage is monotonic append-only; the migration context root is the *bare* prior state (`{"var":""}`), unlike effects' `state.x`. | Low | [00](./00-sdk-stdlib-and-templates.md), [01](./01-authoring-safety.md) |
| F10 | **Root cause — hand-written JSON-Logic, no safety net**: deeply nested `{"var":…}`/`{"+":[…]}`, no types, no autocomplete, mistakes surface only at combine. | High | [00](./00-sdk-stdlib-and-templates.md), [01](./01-authoring-safety.md) |

## Proposal index

- **[00 — SDK std-lib + templates](./00-sdk-stdlib-and-templates.md)** — *the SDK-repo handoff doc.* Canonical builders/templates in `@ottochain/sdk`; a genesis-loaded std-lib of vetted packages; apps consume templates instead of hand-rolling. Executable spec for an agent working in `~/repos/ottochain-sdk`.
- **[01 — Authoring safety](./01-authoring-safety.md)** — offline definition validator (var-path resolution, reserved-key validation, reachability, conformance) + declared state-shape with defaults. (F4, F5, F9, F10)
- **[02 — Asset-effect ergonomics](./02-asset-effect-ergonomics.md)** — canonicalize the `_transferAsset` recipient (accept the `AssetHolder` object form), document + (where safe) soften the fiber/wallet morphism boundary, the custody-aware transfer pattern. (F1, F2, F3)
- **[03 — Cross-fiber & authorization model](./03-cross-fiber-and-authorization.md)** — reconcile the trigger-vs-read dependency asymmetry, clarify (and optionally opt-in-gate) transition authorization, smooth spawned-child owners. (F6, F7, F8)

## Safe-improvement roadmap (the task list)

Ordered by **risk-ascending** and **leverage-descending**. The hard guardrail throughout: **nothing may
shift the signed-message canonical** (CLAUDE.md rule #1 — a field that changes shape/default re-encodes to
a different `JCS(dropNulls)` and breaks `InvalidSignature`), and **registry-lineage checks stay
combine-only** (rule #3). Every change is **additive-first**: hand-rolled JSON keeps working.

| Phase | Work | Risk | Why this order |
|---|---|---|---|
| **P0** | **Documentation** — land this proposal set; add a "fiber-definition authoring gotchas" page + inline doc-comments at the surprising sites (`EffectExtractor.parseRecipient`, `requireWalletHolder`, the `machines.$id` dep requirement, spawned-child owners). | None | Captures the knowledge immediately; zero code risk; informs everything else. |
| **P1** | **Dry-run validator** (Proposal 01) — a pure, offline `validateDefinition(def, schema?)` in metakit/SDK that resolves var paths, flags unknown `_`-keys, checks state-field reads vs `initialData`/shape, verifies reachability. Wire it into the e2e runner + SDK. | Low (additive tooling, no chain change) | Highest leverage / lowest risk — makes every later change *and* every app author safer; catches F4/F5/F10 before the cluster. |
| **P2** | **SDK template library** (Proposal 00, SDK side) — typed builders that emit canonical fiber defs, asset policies, effects (`transferAsset()`, `triggers()`, `spawn()`), migrations. Apps opt in. | Low (additive, off-chain) | Stops hand-rolling for new apps without touching the chain; consumes the validator from P1. |
| **P3** | **Genesis std-lib** (Proposal 00, chain side) — a vetted set of canonical registry packages (fungible/NFT policy presets, machine skeletons) published at genesis; the SDK references them. Needs versioning + ownership/governance + determinism (genesis must be reproducible). | Med (genesis surface + registry; builds on `genesis-and-engine-versioning.md`) | Depends on the template forms (P2) being settled; genesis is consensus-critical so it lands after the off-chain forms stabilize. |
| **P4** | **Targeted model changes** — (a) accept the `AssetHolder` object form in `_transferAsset` *alongside* the bare string (Proposal 02); (b) declared state-shape **defaults** so accumulators auto-init (Proposal 01); (c) make `machines.$id` reads dependency-free or auto-declare (Proposal 03); (d) optional opt-in transition owner-gating via `FiberPolicy` (Proposal 03). | Med-High (touches the combiner/evaluator + canonical) | Each is additive + backward-compatible by construction, but touches consensus code — lands last, behind the validator + golden canonical tests, one at a time. |

**Cross-cutting safety rails:** additive-only (old forms keep parsing); the signed canonical is frozen
(new optional fields are `Option`/omit-safe, guarded by `PublishVersionSigningCanonicalSuite`); registry
lineage checks remain combine-only; every chain change ships with golden round-trip + a riverdale-economy
e2e lane run; genesis std-lib changes are reproducible + versioned.

## Relationship to existing RFCs (build on, don't duplicate)

- `genesis-and-engine-versioning.md` — the genesis + engine-version surface the std-lib (P3) plugs into.
- `jlvm-engine-foundations.md` — the evaluator/effect model these ergonomics sit on.
- `strong-typing-and-conformance.md` + `schema-architecture.md` — the conformance/typing layer the validator (P1) and state-shape defaults extend.
- `asset-model.md` — the asset/morphism model Proposal 02 refines (R1, `_transferAsset`, custody).

## Execution

- **00** is an **executable handoff** for an agent in `~/repos/ottochain-sdk` (+ the genesis side in
  ottochain). **01–03** are **RFCs to review** before implementing the chain-touching parts. Follow the
  P0→P4 ordering; the validator (P1) and SDK templates (P2) are the safe, high-value first steps that need
  no consensus change.
