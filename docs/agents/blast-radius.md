# Blast-radius map — the consensus-critical surface

A subtle mistake in any file below = chain halt / fork / `InvalidSignature` / block-poisoning. All
paths under `modules/`. Cheap models: **propose, don't decide** here.

## Tiers (route by the minimum tier that can safely do the job)
- **T0–T1 — do freely.** New tests, docs, `bin/`, worksheets, OpenAPI regen, comments.
- **T2 — review-first.** Routes, `handlers/`, the e2e harness (`e2e-test/runner.ts` sync gates are
  coupled to wire shapes), non-consensus wiring. Small commits + a persona pass.
- **T3+ — propose-only for cheap models.** Every file listed below. A senior-model session or a human
  decides. Record the decision in the worksheet.

## Escalation protocol
1. A change touching any T3 file requires a **senior-model session or explicit human sign-off**,
   recorded in the worksheet's "decisions needing human/senior model" section.
2. The PR description MUST name **which blast-radius files changed and why each is safe** (which
   invariant it respects, which guard suite covers it).
3. When in doubt, split the diff: land the T0–2 parts, isolate the T3 change behind its own PR + review.

---

## Signed-message schemas & canonicals
- `models/…/schema/Updates.scala` — the `OttochainMessage` variants. EVERY field must be `Option`
  (omit-safe) or required-no-default, else latent `InvalidSignature` (Inv. 1). Encoder/decoder dispatch
  must list every variant; the `Sequenced` mixin defines seq ordering.
- `models/…/schema/CodecConfiguration.scala` — the `customizable{En,De}coder` config (`dropNulls`,
  field-name policy). A change re-hashes everything signed.
- `models/…/schema/registry/{SchemaShape,RegisteredVersion,RegistryTarget}.scala` — hand-rolled
  `RegistryShape` codec (field-name discrimination, `.or` chain). A field-name collision breaks decode/hash.
- **Guards:** `PublishVersionSigningCanonicalSuite`, `AssetOpSigningCanonicalSuite`,
  `E2eSignedPayloadCompatSuite`, `SdkCompatibilitySuite` — add a case per new signed message.

## Committed-state / roots (light-client-provable, rooted into the signed snapshot)
- `models/…/schema/CalculatedState.scala` — the `CommittedView` projection into MPT `CommitKey`s
  (`fiber/`, `script/`, `registry/`, `reverse/`, `asset/`, `nonce/`, `commit/*`). Key derivation is
  **TOTAL** — a non-total key throws inside combine = halt. A new field invisible to `entries` is absent
  from the proof.
- `models/…/schema/OnChain.scala` — the whole snapshot binary is hard-capped at **512,000 B**; cumulative
  maps here hit the cap and cause `UnableToReduceProposalByCutting` = permanent halt. `OnChainWireSizeSuite`
  guards the byte budget.
- `models/…/schema/CommitIndex.scala` *(arrives with PR #210)* — `fold` is valid ONLY at
  `index.ordinal+1`; folding across a gap loses `touched*` writes and the gate **fails open**.
- `shared-data/…/syntax/DataStateOps.scala` — the `.focus(...).modify(...)` folds that write OnChain
  delta + CalculatedState cumulative + record from ONE computed hash. If the three diverge, delta ≠
  cumulative and DL1 heal returns wrong state.
- `shared-data/…/lifecycle/CommitIndexService.scala` + heal clients *(arrive with PR #210)* — DL1
  contiguous fold + heal-from-ML0; must verify subtree completeness, never fold across a gap.

## Validators (block-acceptance gate — TOCTOU / block-poisoning surface)
- `shared-data/…/lifecycle/Validator.scala` — `validateSignedUpdate` does L1-structural-only for
  registry/asset ops (Inv. 3). One `Invalid` drops the whole all-or-nothing block. Read the long
  comment blocks before editing.
- `shared-data/…/lifecycle/validate/{Fiber,Script,Registry,Asset}Validator.scala` +
  `validate/rules/*` — the audit's C3/M1/H1 live here. Any lineage read on a `SchemaRef`/`SchemaBinding`
  parameter = poisoning hazard. `RegistryValidator.CombinedValidator` MUST NOT be called from `validateSignedUpdate`.
- `shared-data/…/fiber/spawning/SpawnValidator.scala` — the child-owners ⊆ parent fail-closed floor (H1);
  relaxing it re-opens owner-forgery.

## Combiners (authoritative deterministic stateful gate; runs the VM on every validator)
- `shared-data/…/lifecycle/Combiner.scala` — dispatch + the load-bearing `recoverWith { case
  CombineRejected }`. ONLY `CombineRejected` may escape the fold; any other throwable aborts the whole
  snapshot combine (halt).
- `shared-data/…/lifecycle/combine/AssetCombiner.scala` — Σ-amount conservation, holder-ownership (R1),
  compose-consent, nonce linearity, faithful Decompose witness. C2 lived here (cross-holder theft /
  unbounded mint if consent/dedup regress).
- `shared-data/…/lifecycle/combine/FiberCombiner.scala` — exact-sequence check + atomic bump, migration
  re-bind, asset-transfer application in sorted emitter order. Replay/atomicity anchor.
- `shared-data/…/lifecycle/combine/{Registry,Script}Combiner.scala` — `RegistryCombiner` holds the C1 fee
  TODO; `resolveScriptBinding` re-verifies gracefully what C3 removed from L0.
- `shared-data/…/lifecycle/combine/CombineRejected.scala` — the sole graceful-rejection channel; its
  message text is committed → use a stable reasonCode (Inv. 2 / L1).

## Fiber engine (execution / determinism)
- `shared-data/…/fiber/FiberEngine.scala`, `evaluation/{FiberEvaluator,EffectExtractor,StateMerger}.scala`,
  `core/{MeteredEvaluator,ExecutionState,ExecutionOps}.scala`, `triggers/*`, `spawning/SpawnProcessor.scala`
  — cascade/spawn bound (`maxDepth=10`), shared `maxGas` threading, `$caller` stamping, fail-silent
  extraction (L5). Any non-determinism reaching committed bytes forks the chain.
- `shared-data/…/fiber/UpgradeGate.scala`, `models/…/schema/fiber/{FiberPolicy,ExecutionLimits,FailureReason}.scala`
  — the `tightens` lattice, the consensus-critical constants, the `reasonCode` (L1 mixed-version fix).

## Genesis
- `shared-data/…/genesis/{GenesisBuilder,GenesisLoader,GenesisManifestLoader}.scala`,
  `models/…/schema/{GenesisData,GenesisManifest}.scala`, fixture
  `modules/shared-data/src/test/resources/genesis/std-manifest.json` — genesis seeds BOTH OnChain and
  CalculatedState commit maps; a mismatch means DL1 heal returns empty for seeded fibers. Migration =
  genesis redeploy at 0.8.0. Guards: `GenesisBuilderSuite`, `GenesisManifestLoaderSuite`, `StdManifestContractSuite`.

## ML0 service + e2e coupling (T2/T3 boundary)
- `l0/…/ML0Service.scala` — orderedCombiner clear semantics, rejection sink.
- `e2e-test/runner.ts` sync gates — coupled to wire shapes. Any wire-format change must grep `e2e-test/`
  for consumers (runner.ts sync helpers, terminal.ts queries) in the SAME PR.

## Allowed freely (T0–T1)
New tests, docs, `bin/`, worksheets, OpenAPI regen, comments.
