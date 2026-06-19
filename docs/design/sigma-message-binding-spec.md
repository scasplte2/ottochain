# Σ-protocol message binding — canonical `sigma_verify` message construction

- **Status:** accepted (spec); implementation pending.
- **Scope:** OttoChain asset-model Σ-gated guards (`docs/proposals/asset-model.md` §8.3) and any
  future use of metakit's `sigma_verify` / `prove_dlog_verify` / `prove_dhtuple_verify` opcodes in a
  consensus guard.
- **Origin:** integration contract from the 2026-06-17 Fiat-Shamir audit
  (`docs/audit/metakit-sigma-fiat-shamir-audit-2026-06-17.md`, **Residual Caveat #1** + **Final
  Recommendation**) and metakit `docs/sigma-verify.md` (the verifier binds *exactly* the `messageHex`
  it is handed; making that message canonical and domain-separated is the **integration's** job, not
  the verifier's).

---

## 1. Problem — a witness-supplied message is replayable

A Σ proof proves *"a satisfying set of the proposition's keys signed `messageHex`"*. The metakit
verifier binds **only** the `messageHex` argument — it cannot know what action that message is
*supposed* to authorize. The current asset-model guard examples take the message straight off the
prover-supplied witness:

```json
{ "sigma_verify": [ <proposition>, {"var": "witness.proof"}, {"var": "witness.message"} ] }
```

Because `witness.message` is **attacker-chosen**, a single valid `(proposition, proof, message)`
triple verifies under **every** guard that pins the same proposition. An issuer 2-of-3 mint proof for
asset `A` can be lifted verbatim into a mint of asset `B`, a different amount, a different recipient,
or a replay of the same mint — the proposition (the issuer key set) is identical and the message is
whatever the replayer pastes in. The proof is sound; the *binding* is missing.

This is not a verifier bug. It is the integration contract the audit requires OttoChain to pin.

## 2. Decision

1. The Σ `messageHex` is **computed by the chain** (the combiner), **never** read from the witness.
   The reserved `witness.message` input is removed from every guard.
2. The combiner injects the computed message into the guard evaluation context under a reserved key
   **`sigmaMessage`** (a `0x`-prefixed lowercase hex string). Guards reference `{"var":"sigmaMessage"}`.
3. The message is a **domain-separated digest that binds every replay dimension**: network, operation
   kind, policy, asset, a chain-held single-use expirable **nonce** (§3a), and the canonical operation
   payload.
4. Provers (off-chain) compute the **identical** message from the same public operation fields; a
   mismatch is an ordinary `false` (the proof simply does not verify for this operation).
5. Anti-replay is a **chain-held, single-use, expirable nonce** (§3a) — reusing the asset model's
   existing `usedNonces` set (already pruned past `expiresAt`), not a global monotone counter. This
   supports pre-authorization and out-of-order / concurrent authorizations within a bounded window.

## 3. Canonical construction (normative)

```
sigmaMessage : 0x-hex of a 32-byte digest

digest = SHA-256(
    DomainSep                       // ASCII "ottochain/sigma-msg/v1" (fixed literal)
  ‖ networkMagic   (4 bytes, BE)    // metagraph / network id — anti cross-network replay
  ‖ opTag          (1 byte)         // operation kind: 0x01 MintAsset, 0x02 ApplyMorphism (+ morphism kind)
  ‖ policyId       (16 bytes)       // the AssetPolicy UUID this proof authorizes
  ‖ assetId        (16 bytes)       // the specific asset/fiber UUID; 0x00*16 for a policy-level mint
  ‖ nonce          (8 bytes, BE)    // chain-held SINGLE-USE nonce (Long), scoped per policy|asset — §3a
  ‖ expiresAt      (8 bytes, BE)    // ordinal after which the authorization is no longer valid — §3a
  ‖ bindingHash    (32 bytes)       // SHA-256 of the canonical operation payload (see below)
)
```

- **`opTag`** distinguishes mint from each morphism kind. For `ApplyMorphism`, the morphism kind
  (`Transfer`/`Compose`/…) is folded in via a sub-byte or via `bindingHash` — it MUST be bound.
- **`nonce` / `expiresAt`** are the chain-held expirable single-use anti-replay pair (§3a). They are
  bound into the message so the proof commits to them, and the combiner enforces + consumes them
  against `CalculatedState`.
- **`bindingHash`** = `SHA-256(canonical-operation-bytes)`, where *canonical-operation-bytes* is the
  **existing canonical signed-message encoding** of the operation (`MintAsset` / `ApplyMorphism`)
  **with the `witness` field excluded**. This reuses the chain's one canonical-JSON encoder
  (`docs/signing-canonical-and-validation.md`) — the same bytes the submitter already signs — so the Σ
  proof is bound to *exactly* the operation, minus the proof carrying it. Excluding `witness` is
  mandatory: the message must not depend on the proof it authorizes (circularity), and the witness is
  not part of what the issuers are attesting to.

The 32-byte digest is far under metakit's `SigmaMaxMessageBytes` (4096) and is fixed-width, so it is a
"compact canonical message" per the audit's Final Recommendation.

### 3a. Chain-held expirable nonce (anti-replay)

A monotone per-asset counter is too rigid for Σ authorization — issuers often **pre-authorize**
(sign a proof now, the holder submits later) and authorizations can arrive **out of order /
concurrently**. So replay protection is a **chain-held, single-use, expirable nonce** — the mechanism
the asset model already ships for the `AuthorizeCompose` commit-reveal handshake, reused here:

- `CalculatedState.usedNonces: SortedMap[UUID, SortedSet[Long]]` — the per-(policy|asset) set of
  consumed nonces, already **BOUNDED: pruned past `expiresAt` in combine** (asset-model §5e/§8).
- The authorizer (issuer / ring member) picks a `nonce` and an `expiresAt` ordinal and proves over the
  message that binds them. On submission the **combiner** (stateful gate — CLAUDE.md rule #2):
  1. rejects if `currentOrdinal > expiresAt` → `CombineRejected("authorization expired")`;
  2. rejects if `nonce ∈ usedNonces[scopeId]` → `CombineRejected("nonce already used")`;
  3. otherwise inserts `nonce` into `usedNonces[scopeId]` (consume) and proceeds.
- **Expiry bounds the state**: an entry is only retained while `currentOrdinal ≤ expiresAt`; once past,
  it is pruned. Pruning is safe — a post-expiry replay is rejected by check (1) regardless of whether
  the nonce is still in the set — so the used-set only ever holds *live* nonces (no unbounded growth /
  no state-rent leak).

This gives single-use (anti-replay) **and** a bounded validity window (a stale pre-authorization
can't be used forever) **and** out-of-order / concurrent authorizations (any distinct nonce, any
order, within its window) — without a global counter.

`expiresAt` is a **required** field on the carrying operation, with **no default** (a nonce of `0` or
an absent expiry are not meaningful sentinels — `AuthorizeCompose` already enforces this), per the
signing-canonical invariant (`docs/signing-canonical-and-validation.md` #1).

### Why a two-level hash

`bindingHash` lets the action-specific payload (recipient, amount, components, …) vary in shape per
operation while the outer message stays a fixed 8-field layout. New operation kinds add an `opTag` and
define their payload; the outer construction is stable.

## 4. Guard wiring change

**Before (replayable):**

```json
{ "mintPolicy": { "sigma_verify": [ <proposition>, {"var":"witness.proof"}, {"var":"witness.message"} ] } }
```

**After (chain-bound):**

```json
{ "mintPolicy": { "sigma_verify": [ <proposition>, {"var":"witness.proof"}, {"var":"sigmaMessage"} ] } }
```

- `AssetCombiner.evalGuardOrReject` computes `sigmaMessage` from the operation + `CalculatedState`
  context and injects it alongside the existing reserved keys (`amount` / `holder` / `assetId` /
  `ordinal` / `witness`, morphism adds `kind` / `recipient`) **before** evaluating the guard.
- `sigmaMessage` is reserved: a guard author cannot override it, and the combiner ignores any
  `witness.message` a prover supplies.
- `nonce` / `expiresAt` are bound into `sigmaMessage` and enforced **statefully** by the combiner
  against `usedNonces` (§3a): a used or expired nonce is a graceful `CombineRejected`, and a successful
  apply **consumes** the nonce so the proof is single-use. They ride on the carrying operation (the
  prover picks and proves over them); they are never trusted from a free-floating witness field.

## 5. Security properties

| Replay vector | Bound by |
|---|---|
| Different network / metagraph | `networkMagic` |
| Different operation kind (mint vs morphism) | `opTag` (+ morphism kind in `bindingHash`) |
| Different policy | `policyId` |
| Different asset | `assetId` |
| Same proof replayed | single-use `nonce`, consumed in `usedNonces` (§3a) |
| Stale / pre-signed authorization reused later | `expiresAt` ordinal window (§3a) |
| Tampered recipient / amount / components | `bindingHash` (canonical operation bytes) |

Soundness of the *proof* remains the verifier's job (and is gated on the **unaudited** caveat below);
this spec closes the *binding* gap so a sound proof authorizes one and only one operation.

## 6. Determinism & canonical-encoding requirements

- The combiner computes `sigmaMessage` **deterministically** from consensus-visible data only
  (operation fields + `CalculatedState`); no wall-clock, no node-local state. It runs in the combiner,
  not the block-validity gate (CLAUDE.md rule #2/#3): a Σ failure is a graceful `CombineRejected`.
- The `(nonce, expiresAt)` enforcement (§3a) is the **stateful** part — it reads and mutates
  `usedNonces`. It lives only in the combiner (never `validateSignedUpdate` — CLAUDE.md rule #2/#3),
  and tests `expiresAt` against the **snapshot ordinal** (consensus-visible), never wall-clock.
- `bindingHash` MUST use the chain's single canonical encoder. A divergence between the prover's and
  the chain's operation encoding is the same class of bug as the signing-canonical invariants
  (`docs/signing-canonical-and-validation.md`): it surfaces as a non-verifying proof, not a fork, but
  it breaks usability. Pin it with a golden vector (prover encoding == chain encoding).

## 7. Migration

- `docs/proposals/asset-model.md` §8.3 examples updated from `{"var":"witness.message"}` to
  `{"var":"sigmaMessage"}`, with a caveat that the witness-supplied form is replayable and forbidden.
- Implementation (follow-up): inject `sigmaMessage` in `AssetCombiner`; reject/ignore any
  `witness.message`; enforce `(nonce, expiresAt)` via the existing `usedNonces` set — the same consume
  + prune-past-`expiresAt` path `AuthorizeCompose` already uses (asset-model §5e/§8); add the
  prover-side helper to the SDK so off-chain provers compute the identical digest; golden cross-impl
  vector for the encoding.

## 8. Caveat (unchanged)

metakit's Σ verifier is **not yet externally audited** (`docs/sigma-verify.md` §0). Message binding is
necessary but not sufficient: a Σ-gated guard must not protect real value until the verifier itself is
independently audited. This spec is the binding contract; it does not change the audit status of the
underlying crypto.

## References

- `docs/audit/metakit-sigma-fiat-shamir-audit-2026-06-17.md` — Residual Caveat #1, Final Recommendation.
- metakit `docs/sigma-verify.md` — message binding is an integration requirement; `SigmaMaxMessageBytes`.
- `docs/proposals/asset-model.md` §8.3 — the Σ-gated guard wiring this spec constrains.
- `docs/signing-canonical-and-validation.md` — the canonical encoder `bindingHash` reuses.
