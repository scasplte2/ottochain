# Signing-canonical & validation-layering invariants

The full rationale behind the three data-application invariants in `CLAUDE.md`. Read this before
touching any signed message, codec, validator, or combiner. Owned by the **consensus-safety** review
persona (`agents/review-personas/consensus-safety.md`).

These rules exist because a Constellation data block is accepted **all-or-nothing**: one `Invalid`
verdict on one transaction drops the *entire* snapshot's block for *every* transaction in it. And the
combiner runs the full VM synchronously on *every* ML0 validator — any divergence in committed bytes
forks the chain. So the cost of getting these wrong is a halt or a fork, not a failed request.

---

## Invariant 1 — signed-message fields are `Option[T]` or REQUIRED, never defaulted

On-chain signature verification is over `JCS(dropNulls(payload))` — RFC-8785 canonical JSON of the
payload after null object-fields are recursively stripped. `dropNulls` strips `null`; it does **not**
strip `false` or `{}` or `[]`.

The trap: a signed-message field declared `Boolean = false` or `SortedMap = empty`.
- The client (SDK) omits it → it is absent from the signed canonical.
- The chain's derived decoder re-fills the default → it is *present* in the verified canonical.
- Signed bytes ≠ verified bytes → **`InvalidSignature`**, surfaced as an opaque empty-body HTTP 400.

Rule: every field on an `OttochainMessage` variant is either `Option[T]` (omit-safe: `None` → `null`
→ dropped) or REQUIRED with no default. Never a non-`Option` field with a default. Server-*derived*
state (`OnChain`, `RegistryShape`) MAY carry defaults — this invariant governs **signed messages only**.

Field names are signature-load-bearing (they are the JSON keys hashed). A rename or reorder re-hashes
everything. Therefore: **derive codecs** (`@derive(customizableEncoder, customizableDecoder)`), never
hand-roll `Json.obj("field" -> …)`/`downField`. Where derivation is genuinely ambiguous
(`RegistryShape`/`RegistryTarget` field-name discrimination, `ScriptShape` ADT) the hand-rolled codec
is pinned by a golden field-name KAT + round-trip test.

Do NOT write lenient decoders that "fix" shapes (e.g. accepting `{"value":"x"}` for a bare string).
Standardize on the canonical form and make fixtures canonical; auto-correcting a shape is a signing hazard.

**Guard:** add a case to `PublishVersionSigningCanonicalSuite` / `AssetOpSigningCanonicalSuite` for any
new signed message. The suite builds the payload with every Option = `None`, applies `dropNulls`, and
asserts `dropNulls(decode(payload).asJson) == payload`. `SdkCompatibilitySuite` /
`E2eSignedPayloadCompatSuite` pin cross-repo wire agreement with the SDK.

---

## Invariant 2 — two-tier validation: structural at the gate, stateful in the combiner

There are two places a transaction can be rejected, and they are NOT interchangeable:

- **`validateUpdate` / `validateSignedUpdate`** (the block-acceptance gate) — STRUCTURAL checks only:
  field presence, expression depth, owner-signature presence, sequence number against `OnChain`.
  A verdict of `Invalid` here drops the whole all-or-nothing block.
- **The combiner** (`Combiner.scala` + `combine/*`) — the authoritative deterministic stateful gate. It
  runs the VM and may reject *gracefully* via `CombineRejected(reason)` → a `RejectionReceipt`, leaving
  `previous` state unmutated and the rest of the block intact.

So: any rejection that needs to read `CalculatedState` (balances, lineage, status, prior seq) belongs in
the combiner as `CombineRejected`, never as an `Invalid` at the gate. Registry stateful checks are
combine-only (L1 cannot see version lineage). Fiber stateful checks stay at L1 only where the datum is
immutable and locally available (`OnChain.fiberCommits` has the seqNum).

**The one escape hatch:** in `Combiner.scala` only `CombineRejected` may escape the fold (via
`recoverWith`). ANY other throwable aborts the whole snapshot combine by design — that is a consensus
halt. A stateful condition that should have been a graceful `CombineRejected` but is thrown as something
else, or asserted as `Invalid` at the gate, is a halt/block-poison waiting to happen.

Committed rejection receipts must use a **stable `reasonCode` / `ValueKind`**, never version-dependent
text (`getClass.getSimpleName`, `ex.getMessage`). Hashing prose into committed state means a reword
forks the state hash of a *rejected* tx across mixed validator versions. Prose goes to logs only.
**Guard:** `FailureReasonCanonicalSuite` asserts byte-identical committed receipts across differing
exception messages.

---

## Invariant 3 — `validateSignedUpdate` must NEVER read `CalculatedState.registry` lineage

This is the TOCTOU block-poisoning rule and the sharpest of the three.

`validateSignedUpdate` runs at block acceptance. If it reads *mutable* registry lineage — any call to
`lineageOf` / `refResolvesAndMatches` / `versionAppendable` / `scriptRefResolvesAndMatches` on a
`SchemaRef` / `SchemaBinding` — then a concurrent third-party publish or yank can flip the answer
`Valid → Invalid` between two validators or two moments (time-of-check vs time-of-use). One `Invalid`
drops the entire block for every transaction in the snapshot. That is a griefing vector: an attacker
publishing/yanking a version can poison unrelated transactions' blocks.

Rule: registry/script lineage checks belong **ONLY** in the combiner as graceful `CombineRejected`
(`resolveScriptBinding` / `RegistryCombiner` re-verify there, gracefully). `validateSignedUpdate` for
fiber/registry/script create/upgrade ops does structural checks only. Immutable reads (owner-signature
presence) are TOCTOU-safe and allowed. The fiber path was hardened first; the script path was missed
in the first pass (audit C3) — so **review every new `L0Validator` method that takes a `SchemaRef` or
`SchemaBinding` parameter.** `RegistryValidator.CombinedValidator` is documented "MUST NOT be used from
validateSignedUpdate."

---

## Consensus-critical limits (must be byte-identical across all validators)
From `modules/models/.../schema/fiber/ExecutionLimits.scala` — changing any of these is a hard fork:
`maxDepth=10`, `maxGas=10_000_000`, `maxStateSizeBytes=1_048_576`, `maxAssetMutations=32`,
`maxActiveDependencies=64`, `maxDependencyLedger=256`, `maxSpawnsPerTransition=16`; metakit codec
`DefaultMaxDepth=64`. The snapshot binary is separately hard-capped at **512,000 bytes**
(`max-state-channel-snapshot-binary-size-in-bytes`, tessellation node-shared config).

## Determinism discipline (committed bytes only)
Committed collections are `SortedMap` / `SortedSet`; apply order is an explicit `sortBy`. No wall-clock,
`Random`, floats, `hashCode`, or unordered-to-seq conversions may reach committed bytes. Canonical
hashing is RFC-8785/JCS with sorted keys — no map-order forks.

## Guard-suite table (invariant → suite that fails when you break it)
| Invariant / property | Guard suite |
|---|---|
| Signed-field Option-or-required + dropNulls round-trip | `PublishVersionSigningCanonicalSuite`, `AssetOpSigningCanonicalSuite` |
| Cross-repo wire agreement with SDK | `SdkCompatibilitySuite`, `E2eSignedPayloadCompatSuite` |
| Hand-rolled codec field-names / ADT discriminators | golden field-name + round-trip KATs |
| Committed receipt stable across exception text (Inv. 2) | `FailureReasonCanonicalSuite` |
| Committed-state projection totality | `CommittedViewSuite` |
| Genesis seeds OnChain + CalculatedState together | `GenesisBuilderSuite`, `GenesisManifestLoaderSuite`, `StdManifestContractSuite` |
| Wire-size budget / codec bloat | `OnChainWireSizeSuite` (ships with OnChain-v2, PR #210) |

## Note: the OnChain-v2 / CommitIndex world (PR #210) changes some wording
On the current `main`, `OnChain` holds **cumulative** `fiberCommits`/`registryCommits`/`assetCommits`
maps. PR #210 (`onchain-incrementals` RFC) moves the cumulative maps into `CalculatedState`, leaves
per-batch `touched*` **deltas** + `burnedAssets` on `OnChain`, and adds `CommitIndex` (contiguous
`fold` valid ONLY at `index.ordinal+1`; folding across a gap loses writes and the gate **fails open**)
plus a DL1 `GET /commit-index` heal route. After #210 lands: "structural checks against `OnChain`" for
fiber ops read the relocated commit-index triple (allowed) — but registry *lineage* reads stay barred
by Invariant 3, and the 512KB cap becomes the reason cumulative maps had to leave `OnChain` at all.
Update this section when #210 merges.
