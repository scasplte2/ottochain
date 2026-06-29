# Asset-Effect Ergonomics — RFC

**Status:** draft / design. **Date:** 2026-06-25. **Theme:** findings **F1, F2, F3** of
[`README.md`](./README.md). **Builds on:** [`../asset-model.md`](../asset-model.md) (the R1 custody
model, typed morphisms, `_transferAsset` return channel — read §4/§9/§10 first; this RFC refines, it does
not restate). **Sibling docs:** [`00-sdk-stdlib-and-templates.md`](./00-sdk-stdlib-and-templates.md) (the
SDK helpers this proposes), [`01-authoring-safety.md`](./01-authoring-safety.md) (the dry-run validator).

This RFC is about **ergonomics, not safety**. The custody invariant at the core of all three findings
(R1 — a fiber's value moves only under its own transition logic) is **sound and stays**. The friction is
that the model is shaped inconsistently with itself (F2), and invisible/undocumented until combine time
(F1, F3). Every change here is **additive** and **canonical-safe** (§4 traces why).

---

## 1. Today (baseline)

There are **two** ways an asset's custody changes, gated by **who holds it**, and they are shaped
differently from each other.

### 1a. Wallet-held → `ApplyMorphism` (a signed message)

A wallet-held asset moves via a signed `Updates.ApplyMorphism` `OttochainMessage`
(`modules/models/.../schema/Updates.scala:299`). The combiner's first gate is **holder-ownership (R1)**:

```scala
// AssetCombiner.scala:858 — requireWalletHolder (the gate, verbatim)
private def requireWalletHolder(source: AssetRecord, signers: Set[Address]): F[Unit] =
  source.holder match {
    case AssetHolder.Wallet(addr) =>
      raiseRejected(signers.contains(addr), s"not asset holder of ${source.assetId}")
    case AssetHolder.Fiber(_) =>
      Async[F].raiseError(
        CombineRejected("fiber-held assets move only via fiber transitions — phase 5")
      )
  }
```

`requireWalletHolder` gates **every** `ApplyMorphism` kind — it is called once per morphism
(`AssetCombiner.scala:257`), per `AuthorizeCompose` (`:326`), and per part of a `Pool`
(`:796`, via `parts.traverse_(requireWalletHolder(_, signers))`). **A fiber-held asset's raw
`ApplyMorphism` is therefore unconditionally rejected** — there is no signer who can authorize a morphism
on it, because no wallet holds the fiber's key.

### 1b. Fiber-held → `_transferAsset` (an effect directive)

A fiber-held asset moves **only** through the `_transferAsset` effect directive, emitted from inside a
fiber transition's `effect` expression and landing in `AssetCombiner.applyFiberTransfer`
(`AssetCombiner.scala:413`). That path re-derives the same R1 defense, but keyed on the **emitting
fiber** rather than a signer:

```scala
// AssetCombiner.scala:429 — the R1 defense for the fiber channel (verbatim)
_ <- raiseRejected(
  source.holder == AssetHolder.Fiber(emittingFiberId),
  s"fiber $emittingFiberId does not hold asset ${transfer.assetId} (holder=${source.holder}) — transfer rejected"
)
```

then `behavior.transferable` (`:435`), the optional `FiberPolicy.transferPolicy` recipient allowlist
(`:444`), recipient-fiber liveness (`:462`), and finally `holder := recipient` (`:471`). It is a
**whole-instance custody move**: `FiberEffect.AssetTransferred(assetId, recipient: AssetHolder)`
(`modules/models/.../schema/fiber/FiberEffect.scala:45`) carries **no amount** — the `TransferPolicy`
doc-comment is explicit: *"a whole-record custody move with no amount, so the only meaningful dial is WHO
may receive"* (`modules/models/.../schema/fiber/FiberPolicy.scala`). There is no fiber-initiated `Burn`,
`Fractionalize`, or partial transfer; and **a fiber cannot mint** (minting is a wallet-signed
`MintAsset`), so a fiber's held balance is fixed at whatever was transferred/minted into it.

### Why R1 exists (and why we keep it)

`requireWalletHolder` and the `applyFiberTransfer` holder check are **the same invariant** seen from the
two custody forms: *value may move only under the authority of whoever holds it.* For a wallet that
authority is a signature; for a fiber **there is no key** — the fiber's transition logic (its guard plus
the fact that the transition ran) **is** the authorization (asset-model.md §10, "Authorization chain").
If raw `ApplyMorphism` on a fiber-held asset were allowed, any external signer could drain a vault/escrow
the fiber is supposed to govern. `EffectExtractor` carries **zero** authorization — it scrapes the
reserved key verbatim — so the combiner *must* never trust it (asset-model.md §9, "the single highest-risk
item"). **This RFC proposes nothing that removes `requireWalletHolder` for external `ApplyMorphism`.**

### The three frictions

- **F1 — custody is bifurcated.** A fiber-held asset can take *only* a whole-instance `_transferAsset`;
  it cannot `Fractionalize`/`Burn`/partially-transfer without first being moved out to a wallet, split
  there, and (optionally) moved back — a wallet round-trip. The riverdale economy worked around this by
  **minting separate whole RVD instances per payment leg** instead of fractionalizing one held balance.
- **F2 — the recipient is a bare string.** `_transferAsset`'s `recipient` is a single string that
  `EffectExtractor.parseRecipient` disambiguates (UUID → `Fiber`, DAG address → `Wallet`) — shaped
  **unlike** the `{Fiber:{fiberId}}` / `{Wallet:{address}}` `AssetHolder` object used everywhere else.
- **F3 — custody decides whose effect transfers.** Because `applyFiberTransfer` requires
  `holder == Fiber(emittingFiberId)`, the **GOODS → consumer** leg of a sale had to be emitted by the
  *retailer's* `process_sale` effect (the retailer fiber held the GOODS), not by the consumer's `buy`.
  The placement is correct but unobvious, and there is no template that encodes it.

---

## 2. F2 — canonicalize the recipient

> **Revised decision — shipped in #193 (object-form ONLY + fail-loud).** This section originally proposed
> *accepting both* the bare string and the object form (additive, soft-deprecation). On review that was
> **reversed** for a greenfield codebase: the bare-string form is **removed**, the recipient is the
> `AssetHolder` object form **only**, and a malformed recipient now **fails loud** (a graceful
> `CombineRejected`) rather than being silently dropped. The static recipient-shape check is **linter-only**
> (advisory; no hard registration gate). The "accept both / soft-deprecation" prose below is kept for
> history but is **superseded** — see the revised proposal and §4/§5.

### The surprise, exactly

`_transferAsset` is extracted by `EffectExtractor.extractAssetTransfers` (`EffectExtractor.scala:188`);
the recipient is resolved and disambiguated by `parseRecipient`:

```scala
// EffectExtractor.scala:242 — parseRecipient (verbatim)
private def parseRecipient(s: String): Option[AssetHolder] =
  scala.util.Try(UUID.fromString(s)).toOption match {
    case Some(uuid) => Some(AssetHolder.Fiber(uuid))
    case None       => refineV[DAGAddressRefined](s).toOption.map(refined => AssetHolder.Wallet(Address(refined)))
  }
```

So inside a directive the author writes a **bare string**:

```json
{ "_transferAsset": [ { "assetId": { "var": "state.goodsId" },
                        "recipient": { "var": "state.buyer" } } ] }
```

but the value it becomes — and the value every *other* surface uses — is the **`AssetHolder` object**
(`modules/models/.../schema/asset/AssetHolder.scala:24`, wire form
`{"Wallet":{"address":..}}` / `{"Fiber":{"fiberId":..}}`; the SDK type at
`ottochain-sdk/src/ottochain/types.ts:723` and the `walletHolder`/`fiberHolder` builders at
`ottochain-sdk/src/apps/lending/assets.ts:31`). `MintAsset.holder`, `ApplyMorphism.recipient`, and
`AssetRecord.holder` are all the object form; only `_transferAsset` is the bare string. (A *third* shape
exists in passing — the guard-context `holderJlv` emits lowercase `{"wallet":..}`/`{"fiber":..}` at
`AssetCombiner.scala:1098` — out of scope here, but the same "one concept, three encodings" smell.)

### Proposal (as shipped, #193): the object form ONLY + fail-loud

In `parseAssetTransfer` (`EffectExtractor.scala`), the evaluated recipient MUST be the canonical
`AssetHolder` wire form (`{"Fiber":{"fiberId": <uuid-str>}}` / `{"Wallet":{"address": <dag-addr>}}`),
decoded **strictly** through the magnolia `AssetHolder` codec. The bare-string `parseRecipient`
disambiguation is **deleted** — there is no longer a UUID-vs-DAG-address guess.

A malformed directive — a non-object item, a missing/non-UUID `assetId`, a recipient that is not a
well-formed `AssetHolder`, or a gas/eval failure — raises a graceful `CombineRejected` (caught at
`Combiner.insert` → `RejectionReceipt`, the same authoritative-gate pattern `AssetCombiner`'s R1 holder
check uses), **not** a silent drop. A silently-dropped transfer is a latent bug; surfacing it is the point.

Authors build the recipient with the SDK `toFiber(...)`/`toWallet(...)` helpers (which now emit the object
form, mirroring `fiberHolder`/`walletHolder`); it is authorable with a dynamic id
(`{"Fiber":{"fiberId":{"var":"event.x"}}}`) because a single-key non-operator map decodes to a literal
`MapExpression`. The static recipient SHAPE is also flagged offline (advisory) by the Proposal 01
`DefinitionLinter`. (The original "accept both / soft-deprecation" plan is superseded — see the banner.)

### Confirm it is canonical-safe (no longer additive)

The directive lives in the fiber **definition's** `transition.effect` expression, which is evaluated
**pre-combine** at `FiberEvaluator.evaluateEffectExpression` (`FiberEvaluator.scala:257`) →
`EffectExtractor.extractEffects` (`:305`). The **signed message** that triggers all this is
`TransitionStateMachine(fiberId, eventName, payload, targetSequenceNumber)`
(`Updates.scala:65`) — it carries an event name and payload, **never the directive or its recipient**.
The recipient form is therefore *never* a signed-message field; it is an interpretation detail of the
already-registered definition, resolved at evaluation time. Restricting `parseAssetTransfer` to the object
form changes no wire shape on any `OttochainMessage`, touches no canonical, and re-uses the existing
(already-canonical) `AssetHolder` codec. Existing definitions emitting bare strings are now **rejected**
(the bare-string form is removed) — a breaking change to authored effects, but **canonical-safe by
construction** since the recipient is never a signed-message field (full trace + CLAUDE.md rule mapping in §4).

---

## 3. F1 / F3 — soften where safe + make legible

Two options, each with a verdict. They are independent; (a) is recommended unconditionally, (b) is
conditional.

### Option (a) — document the boundary + ship SDK helpers/templates · **RECOMMENDED, do first**

Most of F1/F3's cost is *invisibility*, not the model. Fixes, all off-chain (Proposal 00 territory):

1. **Doc-comments at the surprising sites** (P0, zero risk): `requireWalletHolder`
   (`AssetCombiner.scala:858` — "a fiber-held asset takes NO raw `ApplyMorphism`; it moves only via
   `_transferAsset`"), `parseRecipient` (`EffectExtractor.scala:242` — the bare-string disambiguation +
   the object-form alias from §2), and `applyFiberTransfer` (`AssetCombiner.scala:413` — "the emitting
   fiber must be the holder; this is why a transfer leg lives on the holder's effect").
2. **SDK builders** (Proposal 00): `transferAsset(assetId, toFiber(id) | toWallet(addr))` that emits the
   canonical object-form directive from §2, so authors never hand-encode the recipient or guess UUID-vs-
   address. Mirror the existing `fiberHolder`/`walletHolder` helpers (`lending/assets.ts:31`).
3. **A custody-aware "sell" template** (Proposal 00) that encodes F3 directly: a sale is *two* custody
   legs — the **payment** leg (buyer → seller) and the **goods** leg (seller's fiber → buyer) — and the
   template places each `_transferAsset` on **the fiber that custodies that asset**, because
   `applyFiberTransfer` will reject it otherwise. This turns F3 from a debugging surprise into a filled-in
   template slot. A "custody table" doc (asset → holding fiber → which effect may move it) backs it.

**Verdict: ship (a) first.** It removes the bulk of F1/F3's real cost (legibility) at zero consensus risk,
and it is a prerequisite for evaluating whether (b) is even needed.

### Option (b) — fiber-initiated value transforms via new effect directives · **CONDITIONAL, defer**

To let an app subdivide or destroy *fiber-held* value without a wallet round-trip, add effect directives
that run **inside the fiber's transition** — so the **fiber authorizes its own** split/burn, exactly as
`_transferAsset` lets it authorize its own custody move. R1 is preserved: the authority is still "the
holder's own transition logic," never an external signer. Concretely:

- **`_burnHeld`** → new `FiberEffect.AssetBurned(assetId)`, extracted like `AssetTransferred`, applied by
  a new `applyFiberBurn` that **mirrors `applyFiberTransfer`'s R1 defense** (`holder == Fiber(emitter)`),
  then runs the existing `Burn` codomain — evaluate the policy's `burnPolicy` guard and `removeAsset`
  (the logic already exists at `AssetCombiner.scala:523`).
- **`_splitHeld`** (the genuinely useful one) → `FiberEffect.AssetSplit(assetId, amounts | shardIds)`,
  applied by `applyFiberSplit` mirroring the R1 defense + the `behavior.splittable` gate, then the
  `Fractionalize` codomain — partition `amount`, shards inherit `behavior` with `combinable = false`
  (existing logic at `:551`) — with **all shards staying fiber-held** (`holder = the same Fiber`).

**Feasibility against `AssetCombiner`:**

- The pattern is established: `applyFiberTransfers` (`:379`) already drives a list of fiber-emitted
  mutations deterministically (sorted by emitter id, then list order), bounded by
  `ExecutionLimits.maxAssetMutations`, single-pass / non-reentrant (asset-model.md §9). New directives
  slot into the same driver and inherit those bounds.
- **Gap (the real cost):** today's `Fractionalize` partitions the amount **evenly** across `shardIds`
  (`AssetCombiner.scala:563`, *"remainder goes to the first shard"*). The riverdale need is *"split off
  exactly amount X for this leg"* — an **amount-aware** split the morphism does not currently express. So
  `_splitHeld` is not a thin wrapper over `applyFractionalize`; it needs an explicit per-shard `amounts`
  vector with a conservation check (`Σ shard.amount == source.amount`). That is a new codomain, not a
  reuse.
- Each directive also needs: a new `EffectKind` for the fail-closed `allowedEffects` gate
  (`FiberPolicy.scala:22` lists `Trigger/Spawn/Emit/Transfer/Dependency`; the gate runs at
  `FiberEvaluator.scala:~338`), a gas phase (reuse `GasExhaustionPhase.Morphism`), `committedView`
  coverage (assets are already a total key — no new projection), and golden round-trip + a riverdale e2e
  lane.

**Worth it?** The whole-instance + **separate-mint** pattern the riverdale economy already used *does*
express the need — each payment leg becomes its own auditable `AssetRecord` with its own provenance,
which is arguably *better* than splintering one instance. (b) is therefore **net-new combiner state-
transition surface — the riskiest layer — for a need the existing pattern already covers.** **Verdict:
defer.** Build (b) only if a concrete app hits a wall the pre-mint/whole-instance pattern cannot express
ergonomically — e.g. an AMM/vault that must split a *single* held balance at runtime by a *computed*
amount it could not pre-mint. If/when built: start with `_burnHeld` (trivial, low risk), then amount-aware
`_splitHeld`, **one at a time, behind golden + e2e**, per the README's P4 ordering. Never as a bundle.

---

## 4. Safety & compatibility

**Canonical-safe, not additive (F2).** The bare-string recipient is **removed** (§2 narrows to the object
form), so an existing definition that emitted a bare string is now rejected — a breaking change to authored
effects, but the signed canonical is untouched (the recipient is never a signed-message field; trace below).
The (b) directives remain opt-in: a definition that never emits `_burnHeld`/`_splitHeld` is byte-identical
and behaves identically;
the fail-closed `allowedEffects` gate means a policy that does not list the new `EffectKind`s *rejects*
them rather than silently honoring them.

**The signed canonical is unaffected — traced.** The submitted, signed object for a fiber transition is
`TransitionStateMachine(fiberId, eventName, payload, targetSequenceNumber)` (`Updates.scala:65`). Its
canonical is the JCS(`dropNulls`) of *those four fields*. The `_transferAsset` (and any new
`_burnHeld`/`_splitHeld`) directive is **not** in that message — it lives in the **registered
definition's** `transition.effect` (set at `CreateStateMachine.definition` / `PublishMachineVersion`,
`Updates.scala:47`/`:167`) and is evaluated **pre-combine** at `FiberEvaluator.scala:257` → `:305`. So:

- **F2** changes only `EffectExtractor.parseAssetTransfer` (evaluation-time interpretation of a value
  already produced inside the VM). No `OttochainMessage` field changes shape or default → **CLAUDE.md
  rule #1** (signed fields are `Option`/required-no-default) is untouched.
- **(b)** adds new `FiberEffect` *variants* (in-process engine types, never a signed canonical — exactly
  the property asset-model.md §9 relies on for `FiberResult.assetTransfers`) plus new combiner code. No
  signed message gains a field.
- All of this is **combiner/evaluator** code. None of it is `validateSignedUpdate`, and none of it adds a
  `CalculatedState.registry` *lineage* read to the block-acceptance gate → **CLAUDE.md rule #3** (no
  registry-lineage reads in `validateSignedUpdate`; lineage checks stay combine-only) is untouched. The
  stateful holder/policy reads these directives need already live in the combiner as graceful
  `CombineRejected` (asset-model.md §6 "the rule #3 boundary").

**Guardrail for (b):** any new fiber-emitted mutation MUST be applied through the same single-pass /
non-reentrant driver as `applyFiberTransfers` (`AssetCombiner.scala:379`) and counted against
`ExecutionLimits.maxAssetMutations`, or it reopens the reentrancy/DoS surface §9 closed.

---

## 5. Alternatives, effort/risk, open questions

### Alternatives considered

- **F2: accept BOTH the object form and the bare string (additive alias).** Considered, then **rejected**
  on review: for a greenfield codebase, two encodings for one concept is the "one concept, three encodings"
  smell this RFC set out to remove, and a silently-dropped malformed recipient is a latent bug. **Shipped
  instead (#193): object form ONLY + fail-loud** (`CombineRejected`), with the call sites migrated in
  lockstep (chain e2e defs + the SDK `toFiber`/`toWallet` builders and apps, e.g. `lending-zk-loan`
  borrower/lender → `toWallet`).
- **F1: relax `requireWalletHolder` to let a fiber's *owner* sign an `ApplyMorphism` on a fiber-held
  asset.** Rejected — re-introduces an external-signer authority over fiber-custodied value, the exact
  hole R1 closes; and "fiber owner" is not the fiber's custody authority (the transition logic is).
- **F1: a fiber-initiated `MintAsset`.** Out of scope — minting is supply policy, wallet-signed by
  design; a fiber that needs more units should be minted *into* (`MintAsset(holder = Fiber(id))`,
  already allowed, asset-model.md §10 "Minting directly into a fiber").

### Effort / risk

| Change | Surface | Risk | Order |
|---|---|---|---|
| Doc-comments (a.1) | comments only | none | P0 |
| SDK `transferAsset()` + sell template (a.2/a.3) | SDK (Proposal 00) | low (off-chain) | P2 |
| F2 object-form-only recipient + fail-loud (§2) | `EffectExtractor.parseAssetTransfer` + migrate all call sites (chain + SDK) | med (breaking authored form; canonical-safe) | P4 |
| (b) `_burnHeld` | new `FiberEffect` + `applyFiberBurn` + `EffectKind` + tests | med (combiner state-transition) | conditional, after F2 |
| (b) `_splitHeld` (amount-aware) | as above + new conservation codomain | med-high (new codomain) | conditional, last |

### Open questions

1. **Object-form recipient validation depth — RESOLVED (strict + fail-loud).** `parseAssetTransfer` reuses
   the full magnolia `AssetHolder` decoder (strict — rejects unknown keys / missing fields), and a malformed
   directive raises a graceful `CombineRejected` rather than being dropped (the extractor's old
   fail-silent-drop contract was changed deliberately — see the §2 banner).
2. **Static recipient-shape catch — RESOLVED (linter-only).** The Proposal 01 `DefinitionLinter` flags a
   recipient that is not a well-formed `AssetHolder` object at authoring time (a literal bare string →
   Warning, a malformed literal object → Error, a dynamic `{var}` → left to the combiner). This is
   **advisory only** — there is deliberately no hard registration gate (the maintainer-chosen posture).
3. **`_splitHeld` amount source.** If (b) is built: do shard amounts come from the directive (explicit
   `amounts`, conservation-checked) or from a computed expression evaluated against the transition context
   (more powerful, more gas, more validation)? The riverdale "split off leg amount X" need wants the
   computed form — confirm against a concrete app before committing the shape.
4. **Should the guard-context `holderJlv` lowercase form (`{"wallet":..}`/`{"fiber":..}`,
   `AssetCombiner.scala:1098`) be canonicalized to the `AssetHolder` wire form too**, for one consistent
   holder encoding everywhere? Additive (guard authors read context keys), but it is a third shape worth
   folding into the F2 cleanup — tracked here, not proposed.
</content>
</invoke>
