# Schema Architecture — typed, versioned, agent-discoverable contracts over JLVM

**Status:** draft / design. Date: 2026-06-04. Branch: `feat/versionable-contracts`.
**Goal:** give fibers (state machines + script oracles) **typed, versioned, self-describing schemas** in
protobuf — so logic stays in JSON Logic (JLVM) but the *abstraction* (state shape + commands/events) is a
mechanically-validatable, evolvable contract that powers auto-generated dashboards/UIs and
**agent capability-discovery**. Capstone that unifies the versioning, naming, sharding/commitment, and
effects-as-data RFCs.

Grounded in the actual ecosystem (`ottobot-ai/*`): the schemas, the VM, the services, the explorer, the
agent layer. **Most of the substrate already exists** — this is largely *connecting and lifting on-chain*
what's already built, plus one real hardening (buf breaking rules).

## 1. The binding gap (the crux)

Your protobuf app schemas already exist and are rich — but **entirely client-side, in `ottochain-sdk`**:
- `proto/ottochain/apps/{identity,governance,contracts,markets,corporate}/v1/*.proto`, generated to TS
  via **buf** (`buf.yaml` v2, `BREAKING_CHANGES.md` migration log). Variants named `<app>-<variant>`
  (`identity-agent`, `dao-multisig`, `market-prediction`, …). governance has `DAOType ∈ {SINGLE, MULTISIG,
  TOKEN, THRESHOLD}` → `SingleOwnerDAO/MultisigDAO/TokenDAO/ThresholdDAO`; `corporate` is its own app;
  the "constitutional" governance machines were archived in v2.0.0; "small-form" ≈ `ThresholdDAO`/`dao-reputation`.

**On-chain, a fiber is schema-blind.** `StateMachineFiberRecord.definition` and `state_data` are opaque
`google.protobuf.Value`/`Struct` (JSON). There is **no `schemaVersion`, no app link, no on-chain registry**.
Consequences, confirmed across the stack:
- type discovery is **offline** (you must have the SDK to know a fiber's shape);
- validation is **client-side only** (Zod in the Bridge);
- the **Indexer** hardcodes projections per app (`workflowType === 'AgentIdentity'`…);
- the **Explorer** auto-renders the state *diagram* from `definition` but hardcodes *content* views per app;
- the **Bridge** hardcodes a REST route per app; agents can't *discover* what a fiber accepts.

Everything hardcoded-per-app becomes schema-driven once the schema lives **on-chain and versioned**. That
is the whole RFC.

## 2. What already exists (lean on it)

- **Schemas:** the 5 app domains in proto (client-side). Reuse verbatim; lift on-chain.
- **Deterministic bytes:** metakit `JsonCanonicalizer` (RFC 8785) + `JsonBinaryCodec` → canonical bytes for
  any `JsonLogicValue`. This is the hashing/commitment substrate (ties to the sharding/commitment RFC's
  field-level roots). protobuf↔JSON is a standard mapping. (Correction 2026-06-11: the chain does **not**
  currently bundle ScalaPB / `scalapb-circe` and has no `.proto` sources — descriptors stay off-chain by
  design; ScalaPB would only be added if on-chain descriptor parsing is ever pursued.)
- **CQRS query side:** the **Indexer** is already a read-model builder (webhook-fed ML0 snapshots → Postgres
  projections + a `FiberTransition` log). It just isn't schema-driven and lacks a full event/fact table.
- **Auto-rendering precedent:** the Explorer already draws the state machine from `definition` — proof that
  descriptor-driven UI is natural here.
- **Evolution tooling:** buf is wired (`breaking: FILE`). We tighten it (§6), we don't introduce it.
- **Agent requirements:** agent-swarm already specifies "agent-friendly" = typed boundaries + capability
  discovery + narrow scope + explicit authority. That's the spec for §8.

## 3. Three versioned layers (separation of concerns)

| Layer | Language | Carries | Evolves under |
|---|---|---|---|
| **Schema** (what) | protobuf | the **State** message + the **Command/Event** messages a machine accepts | buf WIRE/PACKAGE → semver |
| **Logic** (how) | JSON Logic / JLVM | guards, effects, transitions over the schema-typed value | logic-only = patch; behavior change = migration |
| **Identity** (who) | naming/fingerprint registry | UUID ↔ fingerprint ↔ nickname | naming RFC |

Proto types the *boundary and the abstraction*; JLVM keeps the *behavior* dynamic and LLM-native. They meet
at validation: proto descriptors validate the `JsonLogicValue` command payloads (on ingress) and the
post-effect state (on egress); the engine still evaluates JSON Logic over the decoded value.

## 4. The Registry — one top-level asset (names + schema commitments + versions)

Make the registry a **single top-level on-chain asset** that binds, in one place: **names** (the naming
RFC's `NameRecord`s — hierarchical, owned, reverse records), **schema commitments** (a hash per
`(module, version)`), **versions + status** (semver; Active/Deprecated/Yanked), and the **binding** from a
fiber to its `(name, version)`. There is really *one* registry — a namespace of versioned, named,
schema-committed, owned entries — subsuming the naming RFC's NameRegistry and the versioning RFC's package
registry. Crucially the chain stays **content-agnostic**: it anchors *names → versions → content hashes →
ownership* without parsing or understanding the schemas — like **npm/Cargo** (`name → version →
integrity-hash`, not the package) or **DNS** (records, not the site). It is a package / fiber / machine(script)
registry, nothing more.

```
RegistryEntry {
  name:      Name                          // hierarchical, owned (naming RFC); reverse UUID->name for audit
  owner:     Set[Address]
  versions:  SortedMap[SemVer, RegisteredVersion]
}
RegisteredVersion {
  version:      SemVer
  schemaHash:   DescriptorHash              // COMMITMENT to the protobuf FileDescriptorSet — NOT the bytes
  logicHash:    Hash                        // COMMITMENT to the JSON-Logic definition — NOT the bytes
  stateMessage: String                      // FQN of the State message inside the descriptor
  commands:     SortedMap[String, String]   // eventName/method -> Command message FQN (small; kept)
  status:       Active | Deprecated | Yanked
  registeredAt: SnapshotOrdinal
}
```

**Commit the hash, not the contract; the Bridge stores the bytes.** Live `CalculatedState` keeps only
`schemaHash` + `logicHash` + a small command index + name + status + owner. The chain enforces **only
content-agnostic invariants** — name ownership/namespacing, version monotonicity + immutability (a version's
hashes never change once set), and that a fiber's pinned `(schemaHash, logicHash)` matches a registered
version. It does **not** parse protobuf or JSON-Logic. The heavy bytes live in the **Bridge** (the off-chain
registrar/helper, §4a) and in the registration update's history. A fiber pins its resolved
`(schemaHash, logicHash)` (replayable); resolution is at a fixed ordinal → deterministic.

## 4a. Deploy-and-claim — the Bridge registers, the chain anchors (Solidity/Etherscan, improved)

The **Bridge** is the registrar/helper — just as it's already the helper that deploys machines. It does the
heavy, content-aware work *off-chain*:
1. accepts the **bundle** `{ name, protobuf FileDescriptorSet, JSON-Logic definition, initial data, version }`;
2. **validates** it — descriptor well-formed; **buf WIRE/PACKAGE** breaking checks vs the prior version (§6);
   the definition + initial data **conform** to the descriptor (state ↔ `stateMessage`, each event ↔ a
   `commands` message);
3. **stores** the schema + definition (Bridge/indexer storage) for serving;
4. **submits** a registration update carrying the bundle. The **chain** runs only its agnostic checks
   (name ownership, monotonic version, hash-binding), **commits**
   `{ name, schemaHash, logicHash, commands, version, status, owner }`, and **drops** the bytes from live
   state — *launch without storing the contract.*

This keeps `ottochain` **agnostic** (no protobuf/buf in the chain) while still anchoring the minimal binding.
It is "Solidity deploys bytecode; Etherscan claims the source later" — improved:
- **Trustless, universal re-verification.** The bundle lives in the registration update's history (like
  Ethereum calldata) and in the Bridge store. Because `schemaHash`/`logicHash` are **on-chain**, *anyone* — a
  second Bridge, an indexer, an agent, a peer — can fetch the schema, verify `hash == committed`, and re-run
  the buf/conformance checks themselves. Etherscan trusts one indexer to vouch *after* deploy; here the
  anchor is on-chain and the validation is reproducible by everyone, forever.
- **Validated *before* launch.** The Bridge rejects an ill-formed or **incompatibly-evolved** schema *before
  submission*; Ethereum deploys arbitrary bytecode and discovers problems at runtime.
- **Ownership + lineage anchored.** The chain records *who* registered *which hash* at *which version*,
  immutably — so the name→version→schema binding and its compatibility lineage are consensus-anchored even
  though the chain never parses the schema. Ethereum has neither (ENS is separate; no version lineage).

**Trust model (explicit):** the chain guarantees *who* committed *which hash* at *which version* (ownership,
immutability, lineage). It does **not** itself guarantee the schema is well-formed or compatibly-evolved —
that's the Bridge's job, and it is **re-verifiable by anyone** against the on-chain hash. Honest verifiers
detect a misbehaving registrar; the on-chain anchor makes that detection objective. (An app that wants
*consensus-enforced* validation uses the opt-in proof-carrying mode in §5 — pay-as-you-go, still no global
storage.)

**Claiming / discovery:** fetch the schema (from the Bridge or chain history), verify against the on-chain
hash, serve it. A not-yet-served fiber still runs, showing its `schemaHash` (+ fingerprint) until
"claimed" — exactly like an unverified Etherscan contract.

## 4b. Lifecycle — instances, append-only versions, deprecation, auto-versioning

- **One schema·version = a type; fibers = instances.** A `RegisteredVersion` is a *class*; **many fibers may
  instantiate the same `(name, version)`** (like many objects of one class, or many deploys against one ABI).
  A fiber references `(name, version)` and pins the resolved `(schemaHash, logicHash)`.
- **Append-only + immutable.** An entry's `versions` map is **append-only**; a version's
  `(schemaHash, logicHash, commands, stateMessage)` is **immutable once committed** — chain-enforced and
  agnostic ("this hash for this version never changes"). The only mutable field is `status`.
- **Deprecation lifecycle (npm/Cargo-style) — never breaks running fibers:** `Active → Deprecated → Yanked`.
  - **Deprecated** — still resolvable + runnable; flagged in queries/receipts; discouraged for new instances.
  - **Yanked** — excluded from *new* resolutions (`Latest`/range skip it); **existing instances that pinned
    it keep running unchanged.** Pinning is *why* this is safe: a running fiber resolved its version at
    creation, so status changes only affect *future* resolutions, never live fibers.
  - Status transitions are owner-gated; the chain treats `status` as an opaque enum (agnostic).
- **Auto-versioning from a fixed ruleset.** The publisher should not hand-pick the bump; a **deterministic
  ruleset** computes it from the diff: `bump = classify(diff(prevDescriptor, newDescriptor))` → patch
  (logic-only) / minor (additive) / major (breaking) → `nextVersion`. Removes human mislabeling and gives the
  core safety property: **a `^1.0` pin can never silently receive a breaking change.**

  **Where the ruleset runs (your question) — make it a *deployment* choice over *one* function:** define the
  ruleset **once** as a single deterministic, versioned pure function `schemaEvolution.classify(prev, new)`
  (a codified subset of buf's rules — field numbers never reused/retyped/renumbered, additions=minor,
  removals/retypes=major). Then:
  - **Chain always enforces the agnostic structural part** — append-only, immutable hashes, monotonic
    version, ownership. No protobuf parsing.
  - **The compatibility / auto-version verdict** runs in the **Bridge** by default (off-chain, no protobuf in
    the chain) and is **re-verifiable by anyone** against the on-chain content hashes (Etherscan-but-
    trustless). A registrar that mislabels a bump is *objectively detectable* by re-running `classify` on the
    committed descriptors.
  - **Promote to chain-enforced when the trust model demands it.** Because it's one deterministic function,
    pulling it into consensus (the chain parses the two descriptors and runs `classify`) is a **flag, not a
    redesign** — at the cost of a consensus-critical protobuf-descriptor differ (more code + a determinism
    obligation: byte-identical across nodes forever). **Recommendation:** ship Bridge-enforced first (keeps
    the chain agnostic and lean); promote `classify` on-chain only if the registry must be trustlessly
    permissionless. Either way the rule and the verdict are identical.

## 5. Binding proto ↔ JLVM — registration-time vs runtime validation

Not storing the schema shapes *where* validation happens:
- **Registration-time conformance is enforced by the Bridge** (the off-chain registrar), before submission,
  and is **re-verifiable by anyone** against the committed `schemaHash` — the chain itself stays agnostic.
  The chain's guarantee is the hash anchor + ownership + version lineage (§4a).
- **Runtime stays JLVM** (untyped `JsonLogicValue` evaluation, as today): the engine needs no schema at each
  transition, so not-storing costs nothing at runtime. The schema is an *admission-time + discovery*
  contract, not an execution dependency (unlike EVM bytecode, which *must* be stored because it executes).
- **Optional strict mode (proof-carrying / claimed-cache):** for an app that wants per-transition typed
  validation, the submitter includes the relevant Command message bytes (or the chain uses a claimed-and-
  cached descriptor); the chain validates them against the committed `schemaHash`. Opt-in, pay-as-you-go,
  no global storage. Ingress = validate `commands[eventName]`; egress = validate the post-effect state
  against `stateMessage`.
- **Field-level commitments still work regardless:** they hash the canonical bytes of the *state value*
  (which is in state), not the schema — so the sharding/commitment RFC's per-field roots ("prove
  `state.balance = N`") are unaffected by not storing the descriptor.
- **Determinism:** all validation is a pure function of (bytes, committed hash) at a fixed ordinal; gated
  (§13) so rollout can't fork. No wall-clock; ordered traversal.

## 6. Evolution discipline (the "evolve under PB rules, validated" you asked for)

- The **Bridge registrar** runs **buf breaking checks** vs the prior version at registration — **tightened
  from `FILE` to `WIRE`/`PACKAGE`** (field numbers never reused/retyped/renumbered; `FILE`-only lets a
  field-number reuse silently corrupt a deployed fiber's state — unacceptable for a contract ABI). The chain
  enforces only the agnostic part — monotonic version + immutable hashes — and the compatibility verdict is
  re-verifiable by anyone from the committed prior/new hashes.
- **Semver mapping:** additive field / new command → **minor** (back-compat, no migration); removal /
  retype / breaking → **major** (requires a migration). Logic-only change → **patch**.
- **Migration = the natural transformation** from the versioning RFC: a state-migration that commutes with
  stepping (`migrate∘step = step∘migrate`), now *also* a proto field-migration (renames/defaults). Old
  instances pin their `descriptorHash` and only move at an explicit `UpgradeFiber`.

## 7. Composition (taming the taxonomy)

Your `governance → {single, multisig, token, threshold}`, `corporate → {C_CORP, LLC, …}`, `markets →
{prediction, auction, …}` is a *dependency tree of versioned schema modules*. Manage it like software:
- proto `import` + an on-chain registry of **reusable, versioned schema modules** (`gov.threshold-dao@^1.2`
  imported by an app) — the deps-as-libraries idea (versioning RFC) applied to *schemas*.
- a **lockfile** pinning resolved transitive schema versions for reproducibility.
- "kinds" = proto `oneof`/nested-message variants within a module (matches your existing `DAOType`/`oneof`).

## 8. Agent-facing capability discovery (the payoff)

With schemas on-chain, the whole hardcoded stack becomes **schema-driven**, which is exactly what makes it
agent-grade (per agent-swarm's own criteria):
- **Discovery:** an agent fetches `(fiber → schemaRef → descriptor)` and learns the state shape + every
  command it accepts (FQN, fields, types) — *typed boundaries + capability discovery*, no pre-built SDK.
- **Bridge service-discovery:** `GET /schema/{fiber|app}` returns the descriptor + available operations;
  agents build valid commands from it. Today's hardcoded per-app routes become generic.
- **Indexer:** a generic projector reads the descriptor and normalizes `state_data` into read-models →
  new apps indexed with zero code change.
- **Explorer/UIs:** descriptor (+ optional UI-hint annotations) drives generic forms + state views → new
  apps render with zero code change. (The state diagram already auto-renders; this completes the content.)
- **SDKs:** generated from on-chain descriptors → always in sync with deployed contracts.

> Note on the agent audience: agent-swarm today is *bounded-task execution governance*, not agents
> authoring contracts. This layer is what *enables* the forward goal — agents that discover, deploy, and
> safely interact with typed contracts — while honoring agent-swarm's narrow-scope/explicit-authority
> principles (a deploy is owner-gated; discovery is read-only; commands are schema-validated).

## 9. CQRS + Event Sourcing (the organizing model)

A fiber is an **event-sourced aggregate**: **Command** (tx) → guard → **Event** (effect, as data — the
effects-as-data `FiberEffect`) → **State** = fold of events. Protobuf types all three. The chain already *is*
the immutable event log. To complete it:
- add an **event/fact table** in the Indexer (one row per atomic state change) → replay, **temporal queries**
  ("state at ordinal N"), and rebuildable projections (today it stores current-state + a transition log,
  not a full fact table);
- **read-models = schema-driven projections** (§8); the write side (engine) and query side (indexer) stay
  cleanly separated — textbook CQRS.

## 10. How it unifies the session's RFCs

- **versioning** → a schema version *is* the package version; migration law is shared.
- **naming** → schema modules are `Name`s in the registry; one namespace.
- **sharding/commitments** → proto fields → canonical bytes → per-field SMT/MPT roots + light-client proofs.
- **effects-as-data** → `FiberEffect` is the typed Event; cross-shard `FiberEffect.Triggered` carries a
  typed, schema-validated payload.

## 11. Phasing (additive; each behind a flag)

1. **Registry on-chain (agnostic)** — `RegistryEntry`/`RegisteredVersion` state (name + version + schemaHash
   + logicHash + commands + status + owner) + publish/status updates + append-only/immutable/monotonic +
   ownership enforcement. Chain stores *hashes*; the Bridge stores the bytes. (No fiber behavior change.)
2. **Bind fibers** — `(name, version)` ref on fiber versions; pin resolved `(schemaHash, logicHash)` on instances. Discovery endpoint.
3. **Validation** — registration-time conformance + auto-version `classify` in the **Bridge** (re-verifiable); optional proof-carrying per-tx validation for apps that want it. Chain stays agnostic unless `classify` is later promoted on-chain.
4. **Evolution ruleset** — codify `schemaEvolution.classify` (WIRE/PACKAGE-level) as one deterministic versioned function; Bridge-enforced + re-verifiable, promotable to chain.
5. **Schema-driven services** — generic Indexer projector + event/fact table; descriptor-driven Explorer + Bridge discovery.
6. **Composition + lockfiles**; field-level commitments (with the sharding RFC).

## 12. The synthesis ("most badass smart-contract system for agents")

Typed, self-describing, versioned interfaces (agents discover + interact safely; UIs auto-generate) ·
logic-as-data (JLVM, LLM-native) · event-sourced + CQRS (audit, replay, temporal queries, schema-driven
read-models) · composable versioned schema+logic modules with lockfiles (an ecosystem, not monoliths) ·
human+agent naming · migration-as-natural-transformation (safe hot-upgrades) · succinct field-level
commitments + sharding (scale + light-client proofs). And the substrate is **already in your repos** —
proto+buf, metakit canonicalization, the indexer CQRS side, the explorer auto-renderer, the event log.

## 13. Open decisions

- **Evolution-ruleset locus:** RESOLVED toward one deterministic `classify` function — chain always enforces
  append-only/immutable/monotonic/ownership (agnostic); compatibility + auto-version run Bridge-side +
  re-verifiable, promotable to chain-enforced via a flag (§4b). Recommend Bridge-first.
- **Descriptor storage:** RESOLVED — chain commits `schemaHash`/`logicHash`; the Bridge stores the bytes;
  the registration-update history is the fallback source; re-verifiable vs the on-chain hash (§4a).
- **buf rule level at publish:** WIRE vs PACKAGE vs custom set. (Recommend ≥ WIRE.)
- **UI hints:** a small annotation set (label/widget/format) in proto options for auto-UIs — in scope now or later?
- **Rollout:** the live integrationnet metagraph — fresh genesis / feature flag (same call as versioning).
- **RegistryEntry ownership/governance:** who may publish core app schemas; ownership transfer; the per-tx proof-carrying validation opt-in.
