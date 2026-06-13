- keep the work log up to date in .workspace/ when we have reached a stopping point or completed a task

## Data-application invariants (read before touching signed messages or validators)

See `docs/signing-canonical-and-validation.md` for the full rationale. The two rules:

1. **Signed-message fields are `Option[T]` (omit-safe) or REQUIRED (no default) — never a
   non-`Option` field with a default.** A `Boolean = false` / `SortedMap = empty` on a signed
   message is a latent `InvalidSignature`: the client omits it, the chain's decoder re-fills it,
   and the signed vs verified canonicals diverge (dropNulls strips `null`, not `false`/`{}`).
   Guarded by `PublishVersionSigningCanonicalSuite` — add a case for any new signed message.

2. **The block-validity gate (`validateUpdate`/`validateSignedUpdate`) does STRUCTURAL checks
   only; stateful rejection that needs `CalculatedState` goes in the combiner** (graceful
   `CombineRejected` → `RejectionReceipt`, the authoritative deterministic gate). tessellation
   block acceptance is all-or-nothing, so a stateful Invalid on one tx drops the whole block.
   Registry stateful checks are combine-only (L1 can't see the version lineage); fiber stateful
   checks stay at L1 (`OnChain.fiberCommits` has the seqNum).

3. **`validateSignedUpdate` (block acceptance) must NEVER read `CalculatedState.registry` lineage.**
   Any validator that calls `lineageOf` / `refResolvesAndMatches` / `versionAppendable` inside
   `validateSignedUpdate` is a block-poisoning hazard: a TOCTOU race (concurrent publish, yank)
   returns `Invalid` → the entire block is dropped for ALL transactions in the snapshot.
   Rule: registry lineage checks belong ONLY in the combiner as graceful `CombineRejected`.
   `validateSignedUpdate` for fiber upgrade/create ops: structural checks only (field presence,
   expression depth, sequence number against `OnChain`). Guarded by this comment — review every
   new L0Validator method that takes a `SchemaRef` or `SchemaBinding` parameter.
