# Strong Typing & Conformance — proto ↔ JLVM ↔ registry alignment

**Status:** draft / design (the "secondary pass" to do after the registry + binding land green). Date:
2026-06-04. Branch: `feat/versionable-contracts`.
**Question (from the design discussion):** what does *typing the schema shape* look like, and how do we keep
the **proto schemas**, the **JLVM definitions**, and the **registry** aligned, synchronized, and
evolvable/maintainable — ideally validating a JSON-Logic definition *against* its schema?

## 0. The three artifacts and the relation between them

| Artifact | Is the source-of-truth for | Today (opaque) | After this pass (typed) |
|---|---|---|---|
| **Proto schema** | the *shape* (State message + Command/Event messages) | `schemaB64: String` (chain hashes, never parses) | a typed `SchemaShape` projection on-chain; full descriptor off-chain |
| **JLVM definition** | the *behavior* (states, guards, effects) | `definitionB64: String` (opaque) | the typed `StateMachineDefinition` / `JsonLogicExpression` the chain already has |
| **Registry** | the *versioned binding* (which shape + which behavior, at which version) | commits two opaque hashes | commits typed shape + typed logic + a **conformance** assertion |

The relation between logic and schema is **conformance**: the JLVM definition *could* be required to factor
through the schema (only read/write declared fields). But — see §0.5 — **that relation is opt-in, not the
default.** The default is loose binding: the schema is the typed *domain definition* (the interface), the
JLVM definition is the free-form *source code*, and the registry binds them at a version.

## 0.5 How much structure? — two independent dials, and where it gets constraining

Keeping the JLVM generic (`JsonLogicExpression`) is preferable for the computation side. So separate two
dials that are easy to conflate:

- **Dial 1 — binding strength (identity, NOT shape):** *declaration* (the fiber claims a version; #24 today)
  vs *verified* (the chain checks the fiber runs the registered definition). Verified just hashes the
  definition's *native container* (`StateMachineDefinition`, whose guards/effects stay generic
  `JsonLogicExpression`) the same way a fiber does — it verifies **which logic**, never **what shape the
  logic must have**. Neither option constrains the computation.
- **Dial 2 — conformance (shape):** does the logic only read/write schema-declared fields? **This is the
  only dial that constrains the JLVM**, so it is **opt-in, never enforced by default.** An app that wants a
  strict typed contract runs the conformance check (Bridge-side); everyone else keeps full flexibility.

**The structure principle:** structure that *describes* (the SchemaShape domain-def) or *verifies identity*
(binding) is pure win — discovery, typed clients, trust — with zero constraint on the logic. Structure that
*constrains the computation* (conformance) is where it becomes over-constraining, so it stays opt-in.
**Describe + bind, don't constrain.** The schema is the `.d.ts` (interface for consumers); the JLVM
definition is the `.js` (impl); the registry binds them by version; conformance is the optional typecheck.

## 1. Type the LOGIC first — it's already a first-class type (enables verified binding)

The JLVM definition is *already* a typed thing in ottochain: `StateMachineDefinition` (state machines) and
`JsonLogicExpression` (oracles). The registry should hold the **same typed value**, not a base64 blob:

- `RegisteredVersion.logicHash = definition.computeDigest` (the canonical hash of the typed definition,
  computed the *same way* a fiber's definition is). 
- This immediately upgrades the fiber binding from **declaration (#24)** to **verified (option B)**: the
  combiner checks `hash(fiber.definition) == version.logicHash` on-chain. A fiber can no longer *falsely*
  claim to instantiate `escrow@1.2.0` — the chain enforces it runs that exact logic. No new machinery —
  `computeDigest` already exists and is used for fibers.

So the first concrete move is: **`PublishVersion` carries the typed definition (not `definitionB64`)**, and
`logicHash` is its canonical digest. The registry stays "agnostic" about the *proto schema* but is honestly
typed about the *logic* — which is correct, because the logic is ottochain's own native type.

## 2. Type the SCHEMA shape — a minimal, chain-legible projection (not the whole descriptor)

The full protobuf `FileDescriptorSet` is large and awkward to parse deterministically on-chain. Don't put
*that* on-chain typed. Instead derive a **canonical structural projection** — a `SchemaShape`:

```scala
final case class FieldShape(name: String, number: Int, typeName: String, repeated: Boolean, optional: Boolean)
final case class MessageShape(fqName: String, fields: SortedMap[String, FieldShape])
final case class SchemaShape(
  stateMessage: MessageShape,                  // the fiber's State read-model
  commands:     SortedMap[String, MessageShape] // eventName/method -> Command message shape
)
```

- Small, deterministic, **chain-legible** — exactly the field set + types needed for conformance + typed
  discovery, nothing more.
- Derived from the proto descriptor by the Bridge (deterministically); the **full descriptor stays
  off-chain** (Bridge/history) for codegen/tooling.
- `schemaHash` then commits to the canonical `SchemaShape` (so a fiber/agent verifies the shape on-chain),
  while the full descriptor is claimed/served like today (Etherscan-style).

This is "typing the schema shape": you lift the *verifiable structural core* on-chain and keep the heavy
descriptor off-chain.

## 3. Conformance — validate the JLVM definition AGAINST the schema (the check you want)

With both typed, conformance becomes a **static analysis** over `(StateMachineDefinition, SchemaShape)`:

- **State conformance:** walk every `transition.effect`; collect the keys it writes into state (the effect's
  output `MapValue` keys, minus reserved `_`-keys); assert each is a field of `stateMessage` with a
  compatible type. ⇒ the machine never writes a field the schema doesn't declare.
- **Event conformance:** every `transition.eventName` must have a `commands[eventName]` message; every
  `var event.X` the guard/effect reads must be a field of that Command. ⇒ the machine only consumes declared
  inputs.
- **Read conformance:** every `var state.X` must be a field of `stateMessage`. ⇒ no reads of undeclared state.

This is the "validate a JSON-Logic definition against the schema" you described — and yes, it needs the
"piping for lookups": resolve the `SchemaShape` field types and check the JSON-Logic AST's `var`-paths +
effect-output keys against them. It's a tree-walk + a schema lookup; entirely feasible. It runs **at publish
time** (static, once) — not per transition — so it's cheap and it's the alignment gate.

> Locus, same as `classify`: the conformance check runs in the **Bridge by default** (re-verifiable by
> anyone against the committed `schemaHash`/`logicHash`), **promotable to chain-enforced** behind a flag.
> Strong-typing the minimal core on-chain is what *makes* it re-verifiable.

## 4. Keeping the three aligned, synchronized, evolvable (the maintainability story)

- **One version bundles all three.** A `RegisteredVersion` = (SchemaShape, typed definition, conformance-OK).
  They version *together*; you can't publish a version whose logic doesn't conform to its shape.
- **Co-evolution under one rule set.** A new version's diff is classified across *both* axes: a breaking
  **shape** change (buf WIRE/PACKAGE — field renumber/retype/remove) **or** a breaking **logic** change
  (conformance now fails, or behavior changes) → **major**; additive shape/new command + still-conforming
  logic → **minor**; logic-only-within-conformance → **patch**. `schemaEvolution.classify` extends to cover
  the logic axis too.
- **Migration preserves conformance.** The `migrate∘step = step∘migrate` law (versionable-contracts §7) gets
  a companion obligation: a migration from `v_old` to `v_new` maps `v_old`-conforming state to
  `v_new`-conforming state. So upgrades can't silently break the shape contract.
- **Single source of truth, no drift.** Proto is the SOT for *shape*; the typed `StateMachineDefinition` is
  the SOT for *behavior*; the registry is the SOT for *which (shape, behavior) at which version, and that
  they conform*. Codegen (SDK/UI/agents) derives from the on-chain `SchemaShape` + the served descriptor, so
  clients can't drift from what's deployed.

## 5. Reconciling strong-typing with "keep the chain agnostic"

These aren't in tension if you split by *what* is typed:
- **On-chain (typed, minimal):** the `SchemaShape` projection, the typed `StateMachineDefinition`, the
  hashes, the conformance verdict's anchor. Enough to *verify*, small enough to be cheap + deterministic.
- **Off-chain (opaque to the chain, served by the Bridge):** the full `FileDescriptorSet`, the heavy buf +
  conformance *computation* (re-verifiable against the on-chain typed core).

So strong-typing **lifts the verifiable core on-chain** without dragging the whole protobuf toolchain into
consensus — exactly the same "define the rule once, choose the locus (Bridge-default / chain-promotable)"
pattern as `classify`.

## 6. The secondary-pass plan (sequenced; do after the registry + binding are green)

1. **Type the logic:** `PublishVersion` carries the typed definition (`StateMachineDefinition` |
   `JsonLogicExpression`) instead of `definitionB64`; `logicHash = definition.computeDigest`. Upgrade the
   fiber binding (#24) from declaration → **verified** (`hash(fiber.definition) == logicHash` at combine).
2. **Type the shape:** add `SchemaShape` (FieldShape/MessageShape); `PublishVersion` carries it (derived
   from the descriptor by the Bridge); `schemaHash` commits the canonical `SchemaShape`. Full descriptor
   stays off-chain.
3. **Conformance check:** implement the static `(definition, SchemaShape)` conformance analysis; run it in
   the Bridge registrar at publish (re-verifiable); flag to promote on-chain.
4. **Evolution:** extend `classify` to the logic/conformance axis; add the migration conformance obligation.

Net: the opaque base64 blobs become a typed logic + a typed shape + a checkable conformance relation —
which is what makes the proto/JLVM/registry triangle *stay* aligned as it evolves, and what lets agents/UIs
trust that a deployed fiber's behavior matches its advertised typed interface.
