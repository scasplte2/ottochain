# Asset Shielded Mode — RFC (future / gated)

**Status:** future / design (gated). **Date:** 2026-06-16.

**Goal.** Specify a **shielded `AssetPolicy` mode** — confidential amounts plus unlinkable transfer —
as an opt-in *subsystem* on the existing OttoChain asset model, built on cryptography that **already
ships** (metakit-sdk's SP1 shielded-transfer circuit + the on-chain `groth16_verify` / `pmt_verify` /
`poseidon` opcodes + the shipped ZkVerify-gated morphism). The novel work is the **on-chain
integration**: a shielded representation on `AssetRecord`, a `ShieldedSpend` morphism, a **nullifier
set as a TOTAL committed-view key** with a bounded-growth design, and selective-disclosure viewing
keys. No new cryptography is proposed.

> **This is a future, gated RFC — NOT low-hanging.** Per
> `docs/proposals/asset-model-zk-extension.md`, the near-term zk wins are the **ZkVerify-gated
> morphism** (already *shipped* — PR #166, `5267cc1`) and the **`Pool` morphism** (the lossy compose,
> *in progress / recommended next*). This RFC is the *large* item: a subsystem gated on (1) a public
> security audit of metakit's verifier — a **hard prerequisite** before it guards real value — and
> (2) a nullifier-set state-rent / bounded-growth design. It is deliberately scoped *after* those two
> wins. It does not change the public-by-default posture of `CalculatedState`; shielded is an opt-in
> `AssetPolicy` flavor, never the base ledger.

**Companions:** `docs/proposals/asset-model-zk-extension.md` (the reconciled findings of record —
this RFC is its §4 + roadmap items #4/#5 written up), `docs/proposals/zk-coin-audit.md` (the external
survey + the "what we should not do" line), `docs/proposals/asset-model.md` (the internal model —
`AssetPolicy`, `AssetRecord`, typed morphisms, `AssetCombiner`, derived-supply conservation,
`usedNonces`), `docs/proposals/asset-interop-functor.md` (provenance / `Option[OriginProvenance]`,
the wrapping hazards, the residual-trust/receipts framing). **Invariants honored:**
`docs/signing-canonical-and-validation.md` and `CLAUDE.md` rules #1 (Option-or-required signed
fields), #2 (structural-only block-validity gate), #3 (no stateful/lineage reads in
`validateSignedUpdate` — the nullifier check is **combiner-only**).

---

## Contents

- [1. What it buys, and who it's for](#1-what-it-buys-and-who-its-for)
- [2. What already exists (so this is integration, not novel crypto)](#2-what-already-exists-so-this-is-integration-not-novel-crypto)
- [3. The on-chain subsystem (the actual work)](#3-the-on-chain-subsystem-the-actual-work)
- [4. Selective-disclosure viewing keys](#4-selective-disclosure-viewing-keys)
- [5. Constraints & honesty](#5-constraints--honesty)
- [6. Out of scope / declined](#6-out-of-scope--declined)
- [7. Phasing, prerequisites & companions](#7-phasing-prerequisites--companions)

---

## 1. What it buys, and who it's for

A shielded `AssetPolicy` is the opt-in confidential flavor of an otherwise public asset. It delivers
three user-facing properties, each a property the public model structurally cannot offer:

1. **Confidential amounts.** An observer sees a **note commitment**, not a `Long`. Balances cannot be
   aggregated across a holder; transfer values are private. The combiner's derived-supply conservation
   law (`Σ inputs = Σ outputs ± mint/burn`) is enforced *inside the proof* (value conservation), so
   correctness survives without the chain reading the amounts.
2. **Transfer-graph unlinkability.** A spend reveals a deterministic **nullifier** (not the spent
   note's commitment), so a spent note cannot be linked back to its creation. The custody graph is
   private; double-spends are rejected by a uniqueness check on the nullifier set.
3. **Auditability-with-privacy (viewing keys).** A holder can hand an auditor a **read-only key**
   (FVK/IVK/OVK split, §4) that reveals shielded amounts/parties **without conferring spend
   authority** — the regulator-aligned escape hatch that mandatory-privacy systems (Monero)
   structurally cannot offer. This is the direct expression of OttoChain's *residual-trust / receipts*
   thesis: privacy with a holder-controlled disclosure dial, not opacity.

**Who it's for.** Compliant / institutional assets whose *amounts and counterparties* must be
confidential while remaining provably conservative and auditable on demand (treasury operations,
B2B settlement, payroll-style flows); and **sealed-bid flows** (auctions, RFQs, batch markets) where
bid amounts must be hidden until reveal but conservation and one-time-spend must hold throughout. It
is **not** for assets that want default whole-graph privacy (that is "become Midnight/Zcash," §6) —
shielded mode is one asset's opt-in, not the chain's posture.

---

## 2. What already exists (so this is integration, not novel crypto)

The load-bearing point: the cryptography is **done and shipped**; this RFC is wiring, not invention.

- **metakit-sdk's SP1 shielded-transfer circuit.** metakit `1.8.0-rc.4` (pinned in
  `project/Dependencies.scala`) ships a complete Zcash-style shielded-transfer construction —
  **Poseidon note commitments + nullifiers + Poseidon-Merkle membership + value conservation +
  range** — proven 2-in/2-out in SP1 and wrapped to Groth16-BN254. This is the exact bundle a
  confidential transfer needs (hide amounts, prove conservation, prove membership, prevent
  double-spend, bound values), and it is the **audited-pattern** construction — *not* the bespoke
  ElGamal-encryption + custom range/equality proof system whose Fiat-Shamir transcript omission
  caused the June-2025 SPL break. (Honesty: "audited-pattern" means the *design* is the well-trodden
  Sapling/Orchard one; metakit's *implementation* of it is **not yet publicly audited** — see §5.)
- **The on-chain verifier opcodes.** The JLVM exposes, and the `AssetCombiner` already calls in
  production: `groth16_verify` (SP1-Groth16-BN254, **250k gas**), `pmt_verify` (Poseidon-Merkle
  inclusion), `poseidon` (**≤4 inputs**), alongside `bn254_*`, `smt_verify` / `mpt_verify`,
  `bls_verify`, `schnorr_verify`. These dispatch through the same `JsonLogicEvaluator.evaluateWithGas`
  path an `AssetPolicy` guard uses — so a shielded spend's proof is verified *deterministically in the
  combiner*, with no new evaluator surface.
- **The committed-state MPT.** The committed calc-state root is merged and live (PR #164): a verifiable
  tree root in the signed currency snapshot, with field-level light-client proofs. This is what makes
  a **note-commitment tree root** and a **nullifier set** light-client provable *structurally*, rather
  than via recursion (the route Midnight/Aztec take).
- **The ZkVerify-gated morphism foundation (shipped).** PR #166 (`5267cc1`) shipped a `Governed`
  morphism / `mintPolicy` whose guard runs `groth16_verify` / `pmt_verify` / `poseidon` over a
  `witness: Option[JsonLogicValue]` carried on the tx, with graceful `CombineRejected` on failure
  (proven by `ZkGatedMorphismSuite`). A `ShieldedSpend` (§3) is the *specialization* of this
  already-shipped pattern: the same witness-carrying, combiner-verified, `CombineRejected`-on-failure
  shape, with shielded-specific state effects bolted on.

In sum, the proof system, the verifier, the membership opcode, the light-client root, and the
proof-carrying-morphism plumbing are all present. What is **absent from OttoChain source today** —
and is the subject of §3 — is the *on-chain state subsystem*: a shielded representation, the
`ShieldedSpend` effects, the nullifier set, and viewing keys.

---

## 3. The on-chain subsystem (the actual work)

Four pieces. Each is **additive** to the existing sealed traits and rides the existing three-layer
validation (L1 structural on `AssetCommit` bits → stateful verify in the combiner → JLVM guard).

### 3.1 A shielded `AssetPolicy` mode

A new opt-in flavor (`RegistryShape.AssetPolicy` flag, e.g. `shielded: Boolean` *in the policy shape*,
**not** on any signed message — see invariant note below). Under a shielded policy an `AssetRecord`
carries a **note commitment / shielded representation instead of a plaintext `amount`**:

```
public AssetRecord:   (policyId, behavior, holder, amount: Long, …)
shielded AssetRecord: (policyId, behavior, noteCommitment: Hash, …)   // amount lives only inside the commitment
```

The behavior lattice (`T/S/C/E/G`), the `policyId`, and provenance (`Option[OriginProvenance]`) stay
**in the clear** — only the *amount* and the *spend linkage* are hidden. A shielded asset is still a
typed asset in the same model; conservation moves from a plaintext combiner sum to a *proven* value
conservation inside the circuit. Per Penumbra/Zcash-ZSA, the canonical `policyId` is the natural
**per-asset value base**, so one shielded mode spans many policies rather than a per-asset deployment.

> **Invariant #1 (signing-canonical).** No new *signed* field may be a non-`Option` with a default.
> The `shielded` discriminator lives in the policy *shape* (a registry projection), and any shielded
> spend's payload (proof, public values, revealed nullifiers, output commitments) is carried as
> **`Option[T]` or required-no-default** on the signed message — never a defaulted `Boolean`/map/list,
> which would re-inflate on decode and diverge the canonical → `InvalidSignature`. Add the new
> shielded signed fields to `PublishVersionSigningCanonicalSuite`.

### 3.2 A `ShieldedSpend` morphism

`ShieldedSpend` is a new typed-graph morphism (a specialization of the shipped ZkVerify-gated pattern,
§2). Its combiner action is a single atomic sequence:

```
1. verify proof            groth16_verify(vkey, witness.publicValues, witness.proof)   // value conservation + range + membership, in-circuit
2. check nullifiers absent  ∀ n ∈ revealedNullifiers : n ∉ nullifierSet                 // non-membership on the committed set
3. on success, atomically:  insert revealedNullifiers  +  insert output noteCommitments into the note-commitment tree
   on any failure:          CombineRejected → RejectionReceipt                          // graceful, deterministic
```

- **Verification (step 1)** is one `groth16_verify` call — value conservation, range, and
  note-commitment **membership** are all inside the circuit's public statement (membership is proven
  against the note-commitment tree **root**, §3.4). The public inputs bind the proof to the current
  root and to the revealed nullifiers / output commitments.
- **Nullifier check (step 2)** is the double-spend gate — a **non-membership-then-mark**, atomic with
  the insert in step 3 (read-then-mark inside one combiner pass, signature-tiebreaking the loser),
  *structurally identical to the asset model's existing `usedNonces` commit-reveal discipline*.
- **`ShieldedSpend` subsumes shielded Transfer and shielded mint/burn-into-shield (shield) and
  unshield.** Shield (public → shielded) and unshield (shielded → public) are the boundary cases; per
  `asset-model-zk-extension.md` §5, `shield ∘ unshield` is a **retraction**, not an inverse (fresh
  randomness ⇒ a different commitment) — exactly the same stored-witness retraction shape as
  `Wrap`/`Unwrap` and `Compose`/`Decompose`. No algebraic upgrade is claimed: zk buys hiding and
  one-time-spend, not a group inverse on the aggregation monoid.

The **L1 structural check** stays on `AssetCommit` bits only (a shielded-mode discriminator + the
morphism kind — an O(1) bitmask check). The proof verification and the nullifier non-membership are
**stateful → combiner-only** (§5, invariant #3).

### 3.3 The nullifier set — a TOTAL committed-view key, with bounded growth

The nullifier set is the heart of the subsystem and the part that is genuinely new state.

- **Shape.** A new **combiner-only** committed-state projection, `CommitKey nullifier/<hash>`, exposed
  as a **TOTAL committed-view key** like `asset/` and `nonce/` — so it inherits **field-level
  light-client proofs** from the merged committed-state root (§2). A light client can prove a given
  nullifier is *present* (the note was spent) or *absent* against the snapshot root.
- **Combiner-only (CLAUDE.md #3, the load-bearing rule).** The nullifier membership read is a
  *stateful* check; it **MUST NOT** appear in `validateSignedUpdate`. A nullifier read at block-validity
  time is a textbook **TOCTOU block-poisoning hazard**: two concurrent spends of the same note both
  pass structural validation, and a membership read there returns `Invalid` → tessellation's
  all-or-nothing block acceptance drops the **entire block for every transaction in the snapshot**.
  The check lives **only** in the combiner, as a graceful `CombineRejected` → `RejectionReceipt`
  (the authoritative deterministic gate). L1 sees only structural bits.
- **The bounded-growth problem (the design that distinguishes this from `usedNonces`).** A nullifier
  set is **monotonic and unbounded** — a nullifier can *never* be removed (removing it would re-enable
  a double-spend), unlike `usedNonces`, which **prunes** once a nonce's window passes. So the naive set
  grows forever. The design must bound it:
  - **State rent / committed-state GC.** Charge rent against shielded activity to fund the growing
    set, following the `economics-and-state-rent.md` direction and Ergo's in-production storage-rent
    precedent (a live protocol-primitive proof that state-bloat GC is feasible).
  - **Epoch-windowing.** Partition the nullifier set into epochs; a spend's proof binds to the
    **current (and a bounded number of recent) epoch root(s)**, so only a sliding window of nullifier
    sub-trees needs to be retained in hot committed state, with older epochs archived behind a root
    commitment. The note-commitment tree (§3.4) is windowed in lockstep so spends always prove
    membership against a retained root.
  - **Net rule:** the *security* invariant (a nullifier is never re-spendable) is preserved by
    *committing* every epoch's nullifier root permanently, while only a bounded **hot** window lives in
    the active committed map. This is the one piece with real research weight and is an explicit
    prerequisite (§7).

### 3.4 Note-commitment tree root in committed state

Output note commitments are inserted into a **note-commitment (Poseidon-Merkle) tree** whose **root is
held in the committed state** (committed-calc-state MPT, PR #164). Membership proofs (`pmt_verify` /
the in-circuit membership statement) are checked against this root, and a light client verifies the
root against the signed snapshot. The tree is epoch-windowed in lockstep with the nullifier set
(§3.3) so a `ShieldedSpend` always proves membership against a retained root. This is the structural
route to light-client-provable shielded state that `asset-model-zk-extension.md` and
`zk-coin-audit.md` both call out as a free win of the committed-state architecture (vs. recursion).

---

## 4. Selective-disclosure viewing keys

Auditability-with-privacy is what keeps shielded mode aligned with the residual-trust / receipts
thesis (`asset-interop-functor.md`): privacy with a **holder-controlled disclosure dial**, not opacity.

- **The key hierarchy (FVK / IVK / OVK split).** Model a holder-granted **view capability** on the
  standard full / incoming / outgoing viewing-key split (Zcash IVK/FVK/OVK; Penumbra's FVK split): a
  key that **decrypts but cannot sign** — it reveals shielded amounts and counterparties **without
  conferring spend authority**. Visibility is cleanly separated from spend authority.
- **Placement.** On the public OttoWeb tier this is free (state is already readable); a viewing key is
  load-bearing only *over* a shielded policy, where it gates who can open commitments. It maps onto the
  PUBLIC / SEMI-PRIVATE / PRIVATE tiers — the *same* readable JLVM policy runs with a different
  disclosure dial — and pairs naturally with the interop functor's `Option[OriginProvenance]` and with
  reputation/attestation signals for compliant disclosure.
- **Honesty (not a flag).** A real key hierarchy (spend-vs-view separation, diversified addresses) is
  more than a boolean; it is the *third* cut (§7), only meaningful once amounts/links are actually
  hidden — there is nothing to selectively disclose until §3 ships.

---

## 5. Constraints & honesty

- **Deterministic verification in the combiner — fine.** Every verifier opcode is pure/deterministic
  (`verify(proof, publicInputs) → bool`); proof verification and nullifier non-membership are
  combiner-resident with no consensus-surface change. Determinism neither blocks nor is blocked.
- **The verifier audit is a HARD PREREQUISITE.** metakit's Groth16 / Poseidon-Merkle verifier and its
  shielded-transfer circuit have **no public security audit on record**. A shielded `AssetPolicy`
  **must not guard real value until that audit exists.** Throughout, say *"metakit's
  (not-yet-publicly-audited) verifier"*, never "audited." The ethos point — lean on *one reused
  deterministic verifier* rather than hand-roll per use — still holds and is exactly why the June-2025
  SPL hand-rolled-transcript break is the failure mode we avoid; but the word "audited" is struck until
  it is true.
- **Poseidon arity.** `poseidon` accepts **≤ 4 inputs**; note-commitment and nullifier hashing must be
  designed to that 4-input arity (domain-separated, tree-structured where wider inputs are needed).
- **Gas.** `groth16_verify` is **250k gas** per call; a `ShieldedSpend` is at least one such call, so
  shielded transfers are materially more expensive than public ones — acceptable for an opt-in
  confidential flavor, surfaced in fee estimation.
- **Public-by-default, shielded as opt-in.** `CalculatedState` stays public-by-default. Shielded mode
  is an opt-in `AssetPolicy` variant — never the base ledger. The behavior lattice, `policyId`, and
  provenance remain in the clear; only amount + spend-linkage are hidden. This is the conscious,
  defensible trade of `asset-model-zk-extension.md` §6, not an oversight.

---

## 6. Out of scope / declined

Per `zk-coin-audit.md` "what we should not do" and `asset-model-zk-extension.md` §7, this RFC
**explicitly declines**:

- **A second VM or a second cryptographic stack.** No Plonkish/Halo2/Cairo proving engine, no Ergo
  native sigma-protocol/ring-signature idiom (it has no home in our account/record model; the
  SP1+Groth16 path dominates it for our ethos).
- **Hand-rolled proofs / a hand-rolled Fiat-Shamir transcript.** Every proof rides metakit's
  `groth16_verify` / `pmt_verify`; nothing novel. The June-2025 SPL ZK-ElGamal soundness break is
  exactly the hand-rolled-transcript mistake we refuse.
- **Midnight's client-held private state.** No Kachina off-chain private-state model, no UC
  state-oracle-transcript concurrency model, no Compact compiler / compile-time information-flow
  (`disclose()`) guarantee. We adopt proof-carrying **state** (commitments, nullifiers), never
  client-side-proven **logic** — readable JSON-Logic stays the contract language.
- **Penumbra's validator-DKG flow-encryption DEX.** No validator-side threshold decryption / per-block
  DKG (heavyweight validator crypto our combiner-deterministic metagraph can't host — even Penumbra
  ships it specified-not-deployed).
- **In-VM homomorphic-commitment amounts.** Do **not** put a Pedersen/ElGamal-commitment amount
  *in JLVM state* with in-VM group ops: metakit has **no range-proof opcode**, and a bare commitment
  *without a range proof* is *less safe* than the plaintext (amount wraparound mod the group order
  mints value). Confidential amounts go through the **shielded circuit** (§2/§3), where conservation
  *and* range are proven together — never via an in-VM commitment. (A Pedersen commitment from audited
  `bn254_add`/`bn254_mul` is *not itself* the SPL trap — the trap is the missing range/proof-system —
  but it is useless for amounts without the range proof the circuit already provides.)
- **An unlinkable `Mix` / coinjoin morphism.** A commit-reveal shuffle reveals the permutation to the
  public, light-client-provable combiner, so it is **not actually unlinkable**; real unlinkability
  needs the shielded subsystem here anyway. `Mix` **fights** the public-readable-state ethos head-on
  (it requires that virtue to fail for the linkage data) and is declined (see
  `asset-model-zk-extension.md` §3). Unlinkable transfer is delivered *by this RFC's nullifier
  construction*, not by a mixer.

---

## 7. Phasing, prerequisites & companions

**Hard prerequisites (gates, in order):**

1. **A public security audit of metakit's verifier + shielded-transfer circuit** — before shielded
   mode guards real value (§5).
2. **A nullifier-set state-rent / bounded-growth design** (§3.3) — the monotonic-unbounded set must be
   bounded (state rent + epoch-windowing) before it can be a permanent committed projection. Tracks
   `economics-and-state-rent.md`.

**Near-term companions that come first (NOT this RFC):**

- **ZkVerify-gated morphism — SHIPPED** (PR #166, `5267cc1`). The foundation `ShieldedSpend`
  specializes.
- **`Pool` morphism — in progress / recommended next** (`asset-model-zk-extension.md` §3): the lossy,
  provenance-forgetting compose; single-`policyId`; no zk. The holder-side complement to the interop
  functor's anti-fragmentation cure.

**Suggested phasing within this RFC (each a deliberate cut, gated on the prerequisites above):**

| phase | slice | depends on |
|---|---|---|
| A | shielded `AssetPolicy` mode + `ShieldedSpend` over a single shielded transfer (amounts hidden, conservation + range in-circuit) | verifier audit; `groth16_verify` (shipped) |
| B | nullifier set as a TOTAL committed-view key + note-commitment tree root in committed state (unlinkable, double-spend-safe) | state-rent / epoch-windowing design; committed-state MPT (shipped, PR #164) |
| C | selective-disclosure viewing keys (FVK/IVK/OVK) | A + B (nothing to disclose until amounts/links are hidden) |

**Companions:** `docs/proposals/asset-model-zk-extension.md` (findings of record),
`docs/proposals/zk-coin-audit.md` (external survey + decline list),
`docs/proposals/asset-model.md` (the internal model this extends),
`docs/proposals/asset-interop-functor.md` (provenance, wrapping hazards, residual-trust framing).

**Net.** Strictly more *utility* (confidential amounts + unlinkable, auditable transfer), the **same
algebra** (a stored-witness retraction + obstructed adjunction — zk hides and enforces one-time-spend,
it does not invert maps), built on **shipped** crypto, gated on a **verifier audit** and a
**nullifier state-rent** design. A subsystem — not low-hanging — and deliberately sequenced after the
ZkVerify-gated morphism (shipped) and `Pool` (in progress).
