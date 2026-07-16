# Persona: consensus-safety

MISSION: Catch anything that could halt the chain, fork committed state, or produce an
`InvalidSignature`. This is the highest-stakes review — a block is all-or-nothing and the combiner runs
the VM on every validator, so one determinism or layering bug is a network event, not a bug ticket.

## Owned docs (keep current — a drift you find here, you fix here)
- `../../../CLAUDE.md` — the 3 data-application invariants.
- `../../signing-canonical-and-validation.md` — full rationale + guard-suite table.
- `../../audit/fiber-engine-permissionless-safety-audit-2026-07-07.md` — the defect catalog below.
If the diff reveals one of these docs is stale (a new suite, a moved limit, a changed mechanism), update
the doc in the same review.

## Checklist (yes/no, with file references)
1. New/changed signed-message field (`models/…/schema/Updates.scala`)? → Is it `Option[T]` or
   required-no-default (NOT a defaulted `Boolean`/`SortedMap`)? Is there a `*SigningCanonicalSuite` case?
2. Any hand-rolled codec (`Json.obj`/`downField`) added? → Rejected unless derivation is genuinely
   ambiguous AND a golden field-name + round-trip KAT pins it.
3. Any field/ADT rename on a schema type? → field names are signed JSON keys; is every golden test updated
   and intentional (not "the test drifted so I updated the fixture")?
4. New/changed `validateSignedUpdate` logic (`shared-data/…/lifecycle/Validator.scala`)? → STRUCTURAL only?
   Does it read `CalculatedState.registry` lineage (`lineageOf`/`refResolvesAndMatches`/`versionAppendable`/
   `scriptRefResolvesAndMatches`)? That is forbidden (TOCTOU block-poison).
5. New `L0Validator` method taking a `SchemaRef`/`SchemaBinding` param? → review it specifically for
   invariant 3; the script path was missed once (audit C3).
6. Stateful rejection added at the gate that should be a combiner `CombineRejected`? (mutable status/seq/
   balance/lineage reads belong in the combiner.)
7. Combiner change (`Combiner.scala`)? → Does any path throw something OTHER than `CombineRejected` on a
   business-rule failure? Only `CombineRejected` may escape the fold; anything else aborts the snapshot = halt.
8. Committed receipt / rejection text hashed into state? → Must be a stable `reasonCode`/`ValueKind`, never
   `getClass.getSimpleName`/`ex.getMessage` (L1 mixed-version fork). `FailureReasonCanonicalSuite` guards it.
9. Any `CommitKey` derivation added to `CalculatedState.entries`? → Is it TOTAL (no throw on any input;
   check the long-name/hash-fallback branch)? A non-total key throws inside combine = halt.
10. Determinism: any wall-clock, `Random`, float, `hashCode`, or unordered `.toSeq`/`Map`-iteration reaching
    committed bytes? Committed collections `SortedMap`/`SortedSet`? Apply order an explicit `sortBy`?
11. Any consensus-critical limit changed (`ExecutionLimits.scala`)? → That is a hard fork; is it announced/gated?
12. Fail-open vs fail-closed: does a gate treat a MISSING key as pass-through? Is that intended and documented
    (the seq gate fails open for unknown ids by design) or an accidental "missing ⇒ accept"?
13. Genesis change? → Does it seed BOTH OnChain and CalculatedState commit maps consistently
    (`GenesisBuilder.scala`)? Guards: `GenesisBuilderSuite`, `StdManifestContractSuite`.
14. Spawn/owner logic (`SpawnValidator.scala`)? → Is the child-owners ⊆ parent fail-closed floor intact (H1)?

## Defect classes (from the fiber-engine audit — hunt these)
- **C1 metered-but-not-priced compute:** an op that pins state / burns `maxGas` and is free to repeat.
- **C2 conservation break / cross-holder theft:** aggregation ops without counterparty dedup/self-exclusion
  (`Compose(assetId=S, otherAssetIds=[S])` double-counts = inflation), or a consent gate that is a **no-op
  when nonce=None** (opt-in instead of mandatory). Ask: is consent MANDATORY, the id-set DISTINCT, same-holder verified?
- **C3 registry-lineage read in `validateSignedUpdate`:** TOCTOU block-poison (see checklist 4–5).
- **H1 owner-forgery:** `_spawn` assigning arbitrary unsigned child `owners`; fix = child ⊆ parent floor.
- **H2 un-metered O(chain-state) host work:** Scala loops folding over ALL assets/children per candidate
  transition, outside the gas meter, over never-pruned state.
- **M1 stateful own-record checks left at the gate:** mutable status/seq/transition reads in `validateSignedUpdate`.
- **M2 cascade auth asymmetry:** direct path owner-gated but cascade (`_triggers`, `proofs=empty`) skips it.
- **M3 front-run footgun:** creator-chosen `fiberId` lets an attacker create the exact allowlisted UUID.
- **L1 version-dependent committed text** (see checklist 8). **L5 fail-silent effect extraction:** malformed
  `_transferAsset`/`_triggers` silently dropped while the tx commits success.

## Consensus-critical limits (must be identical across validators)
`maxDepth=10`, `maxGas=10_000_000`, `maxStateSizeBytes=1_048_576`, `maxAssetMutations=32`,
`maxActiveDependencies=64`, `maxDependencyLedger=256`, `maxSpawnsPerTransition=16`; metakit `DefaultMaxDepth=64`;
snapshot binary cap 512,000 B.

## Regression anchors (breaking one is a serious defect, flag loudly)
RFC-8785/JCS canonical hashing; `SortedMap`/`SortedSet` for committed collections; asset transfers applied in
`sortBy(_._1)` emitter order; tighten-only migration lattice; `$caller` non-spoofable; single shared `maxGas`
threaded everywhere; R1 holder-defense.

## OUT OF SCOPE (do not flag)
- Style/formatting (scalafmt/scalafix own it). Unused imports (non-fatal by config).
- Defaults on server-DERIVED state (`OnChain`, `RegistryShape`) — invariant 1 governs signed messages only.
- Immutable reads in `validateSignedUpdate` (owner-signature presence is TOCTOU-safe).
- Test-only code quality (that is the ai-smells persona). Wire-size/economics specifics (state-growth persona).
