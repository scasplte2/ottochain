# Conventions — the residue linters can't hold

`scalafmt` and `scalafix` enforce formatting and mechanical lints on every compile (and again in CI
via `scalafmtCheckAll` + `scalafixAll --check`). This doc is only the discipline they CANNOT
mechanically enforce. **If a rule here becomes machine-checkable, move it to scalafix/scalafmt and
delete it from this doc.** A convention that lives only in prose is a convention that will drift.

## Codecs & signed messages (consensus-load-bearing)
- **Derive codecs, never hand-roll.** `@derive(customizableEncoder, customizableDecoder)` on all
  schema types. Hand-rolled `Json.obj("field" -> …)` / `downField` is banned — field names are the
  signed JSON keys; a drift re-hashes a root → `InvalidSignature`. Hand-rolled codecs exist ONLY where
  derivation is ambiguous (`RegistryShape`/`RegistryTarget`, `ScriptShape` ADT, Nibble seqs) and are
  then pinned by a golden field-name + round-trip KAT.
- **Signed fields are `Option[T]` or REQUIRED — never a non-`Option` with a default.** A `Boolean=false`
  / `SortedMap=empty` default on a signed message is a latent `InvalidSignature`. Server-derived state
  may default. Every new signed message gets a `*SigningCanonicalSuite` case. (Full rationale:
  `../signing-canonical-and-validation.md`.)
- **Strict decoders — do not auto-correct shapes.** Standardize on the canonical form, make fixtures
  canonical. A lenient decoder that accepts `{"value":"x"}` for a bare string is a signing hazard.

## Validation layering
- **Two-tier:** `validateUpdate`/`validateSignedUpdate` do STRUCTURAL checks only; stateful rejection
  goes in the combiner as graceful `CombineRejected` → `RejectionReceipt`. Block acceptance is
  all-or-nothing.
- `validateSignedUpdate` must NEVER read `CalculatedState.registry` lineage (TOCTOU block-poison).
  Reading the relocated commit-index triple is allowed; *lineage* reads are barred.
- **Only `CombineRejected(reason)` may escape the combine fold.** Every other throwable aborts the whole
  snapshot combine (a halt). Rejections carry a human `reason` for the receipt — but committed receipts
  use a **stable `reasonCode` / `ValueKind`**, never version-dependent exception text.

## Style idioms
- **Prefer chaining over deeply nested `match`.** Flatten nested `match` into chained combinators
  (cite PR #211 / `refactor/nested-match-cleanup`, commit 81941e7 — a 5-file, −67-line pure refactor).
- **Monocle `.focus(_.…).modify(...)`** is the idiom for DataState writes (`DataStateOps.scala`);
  `import monocle.Monocle.toAppliedFocusOps`. Write all of (OnChain delta, CalculatedState cumulative,
  record) in one focus chain from one computed hash.
- **Combiner signer idiom:** `update.proofs.toList.traverse(_.id.toAddress)`. JSON-Logic guards run at
  the metakit boundary via `JsonLogicEvaluator.tailRecursive[F].evaluateWithGas(...)` — pure predicate,
  no FiberT/StateT stack.
- **Heavy WHY-scaladoc on consensus code.** Load-bearing invariants get a comment explaining *why*
  (see `Validator.scala` TOCTOU block, `OnChain.scala` 512KB-cap rationale). Invariants are "guarded by
  this comment" — the comment tells the next reviewer which new methods to re-check.

## Determinism (committed bytes only)
- Committed collections are `SortedMap` / `SortedSet`. Apply order is an explicit `sortBy` (asset
  transfers apply in `sortBy(_._1)` emitter order). No wall-clock, `Random`, floats, `hashCode`, or
  unordered-to-seq in committed bytes. Canonical hashing is RFC-8785/JCS (sorted keys).

## Tests
- **weaver** (`weaver.framework.CatsEffect`). Suites named `*Suite`. GOTCHA: a suite-level
  `private val name` shadows weaver's `name: String` → a baffling type error. Give test values
  non-colliding names. Law suites need a `Cogen[T]` (weaver-discipline + cats-laws).
- **Shared generators** live in `modules/shared-test` (`Generators.scala`, `TestFixture.scala`,
  `Mock.scala`, `KeyHelpers.scala`, `FiberExtractors.scala`, `FiberBuilder.scala`) — reuse them, do not
  re-roll a generator per suite. Keep pure-lattice law suites separate from combiner-level law suites.
- **Golden / KAT patterns:** signing-canonical suites build with all Options=None, apply `dropNulls`,
  assert `dropNulls(decode(payload).asJson) == payload`. Wire-size bands double as codec-bloat tripwires.
  A golden test must NOT regenerate its own fixture in the same run (that asserts a tautology).

## Naming
- **"script", never "oracle"** in new code/routes/namespaces/docs. The two fiber kinds are state
  machines and scripts. "oracle" survives only as a real-world domain noun. Consensus-visible names are
  near-immutable.
- **Reserved directive keys are `_`-prefixed** (`_scriptCall`, `_transferAsset`, `_spawn`, `_triggers`)
  in `ReservedKeys.scala`.

## Commits (commitlint-enforced in CI — `.commitlintrc.json`)
- Conventional: `type(scope): subject`. Types: `feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert`.
- **Lowercase subject start**, no trailing period, **header ≤ 72 chars**. `disallowScopes: release`
  (`feat(openapi)` is fine). PR titles obey the same rules.
- **MEASURE the subject before every commit** (eyeballing 73-vs-72 fails, repeatedly, in CI):
  ```
  s="feat(fiber): your subject here"; printf '%d %s\n' "${#s}" "$s"
  ```
  `bin/preflight` runs the same check over `origin/main..HEAD`. `.githooks/pre-push` is an opt-in mirror
  (`git config core.hooksPath .githooks`) — hooks are kept opt-in by preference; **preflight is the contract.**
- Trailers on every commit:
  ```
  Co-Authored-By: <model> <noreply@anthropic.com>
  Claude-Session: <url>
  Worksheet: docs/worksheets/<file>
  ```
