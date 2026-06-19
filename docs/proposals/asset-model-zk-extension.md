# Asset Model — zk Extension: reconciled feasibility, decisions & roadmap

**Status:** research / design (reconciled). **Date:** 2026-06-16.

This is the *reconciled* synthesis of four investigations into extending the OttoChain asset model
with zero-knowledge utility, framed by the ethos *readable JLVM, lean on one verifier, determinism,
light-client-provable, no huge fanciness*:

1. a metakit-feasibility audit of the actual zk-opcodes in `metakit 1.8.0-rc.4`,
2. an external-protocol audit (Ergo, Midnight, Zcash, Monero, Starknet, Aztec, Penumbra, Aleo) —
   `docs/proposals/zk-coin-audit.md`,
3. an **adversarial** re-evaluation of the "don't chase the isomorphism" verdict, and
4. an exploration of a lossy/mixing compose hunch.

Companion: `docs/proposals/zk-coin-audit.md`, `docs/proposals/asset-model.md`,
`docs/proposals/asset-interop-functor.md`.

---

## 0. TL;DR

- **Shipped:** the **ZkVerify-gated morphism** (a `Governed` morphism / `mintPolicy` whose guard runs
  metakit's `groth16_verify` / `pmt_verify` / `poseidon` over a `witness` carried on the tx). Zero new
  crypto; reuses the combiner verbatim. Implemented + tested on PR #166 (commit `5267cc1`).
- **Recommended next (low-hanging):** a **`Pool` morphism** — the *deliberately lossy* dual of Compose
  (forgets per-component provenance/identity, conserves amount) — the holder-side complement to the
  interop functor's canonical-`policyId` anti-fragmentation cure.
- **Future RFC (a subsystem, not low-hanging):** a **shielded `AssetPolicy` mode** (confidential
  amounts + unlinkability). The crypto already exists (metakit-sdk ships an SP1 shielded-transfer
  circuit verified by `groth16_verify`); the *subsystem* (a nullifier set as TOTAL committed-state with
  a state-rent/bounded-growth design) is the work.
- **Decision held:** `Decompose ∘ Compose` stays a **retraction**. zk buys *hiding* and *proof-gating*,
  not an algebraic upgrade. The isomorphism is not worth building.

---

## 1. The opcode reality (reconciled — one agent was wrong)

metakit `1.8.0-rc.7` (pinned in `project/Dependencies.scala`) **does expose zk-verifier opcodes to the
JLVM, and they work in the combiner today.** This is not a claim — it is *demonstrated* by the shipped
`ZkGatedMorphismSuite`, which builds a real `PoseidonMerkleTree` inclusion proof, gates a `Transfer`
morphism on `{"pmt_verify": [...]}`, and asserts the on-chain opcode agrees with metakit's tree builder
(valid proof accepted, tampered/absent rejected), plus a `groth16_verify` negative path.

Reachable opcodes (verified against the resolved jar via `javap` and by running them):
`groth16_verify` (SP1-Groth16-BN254, 250k gas), `pmt_verify` (Poseidon-Merkle inclusion),
`poseidon` (≤4 inputs), `bn254_add/mul/pairing`, `smt_verify`/`mpt_verify`, `bls_verify`,
`schnorr_verify`, `ecvrf_verify` — **and, new in `1.8.0-rc.5`, the Σ-protocol family**:
`prove_dlog_verify` (Schnorr / DLog leaf), `prove_dhtuple_verify` (DDH / Diffie–Hellman-tuple leaf),
and `sigma_verify` (recursive CDS AND/OR/THRESHOLD over BN254 G1 — i.e. threshold + ring authorization,
hiding which signer(s) participated). They dispatch through the exact `JsonLogicEvaluator.evaluateWithGas`
path the `AssetCombiner` guard uses.

> **Honesty note (a real cross-agent contradiction, resolved):** the lossy-compose investigation
> reported "metakit's JLVM has 61 opcodes, all arithmetic — no zk verifier opcode." That was read off a
> **stale `metakit-fork` tree** and is **false** for the pinned rc.4. The empirical tiebreaker is the
> passing `ZkGatedMorphismSuite` (real `pmt_verify`/`groth16_verify` calls in-combiner). The
> lossy-compose `Pool` recommendation (§3) is unaffected — it needs no zk — but that agent's reason for
> parking the *mixing* flavor ("no verifier exists") is corrected here: the verifier exists; mixing is
> parked for the *other* reasons (§3).

---

## 2. ZkVerify-gated morphism — SHIPPED (the consensus win)

A policy can require a proof / Merkle-membership witness as a morphism (or mint) **precondition**:
the tx carries `witness: Option[JsonLogicValue]`; the combiner injects it under the `witness` context
key; a `Governed` guard reads e.g.
`{"pmt_verify":[<root>,{"var":"witness.leaf"},{"var":"witness.index"},{"var":"witness.siblings"}]}` or
`{"groth16_verify":[<vkey>,{"var":"witness.publicValues"},{"var":"witness.proof"}]}`. Failure →
graceful `CombineRejected`. This is **zk-as-integrity, not privacy**: readable JSON-Logic stays the
contract language; zk enters only as a guard, rooted in the committed-state MPT a light client checks.

Unlocks today: a **proof-gated `mintPolicy`** for bridges ("mint iff this SP1 proof of a foreign lock
verifies" — directly serves `asset-interop-functor.md` Open-Q3), **zk compliance/age gates**, and
**Merkle-airdrop / allowlist** claims via `pmt_verify`.

**New in `1.8.0-rc.5` — Σ-protocol guards (threshold / ring authorization).** The same witness →
`evalGuardOrReject` path now also serves `sigma_verify` / `prove_dlog_verify` / `prove_dhtuple_verify`,
so a guard can express **k-of-n / ring authorization without revealing which signer(s) participated** —
e.g. a `mintPolicy` "any 2-of-3 issuers may mint, hidden which" (`sigma_verify` over a
`threshold(2,[dlog A, dlog B, dlog C])` proposition), or a `Governed` `Compose` authorized by a ring
`or([dlog A, dlog B])`. This was the **motivating use case** for adding the Σ opcodes to metakit, and it
is the SAME zero-new-ottochain-crypto win as the Groth16 / Merkle guards (one reused deterministic
verifier, witness on the tx, graceful `CombineRejected` on failure). It is authorization, **not**
confidential amounts — orthogonal to the 5-bit behavior model and to the shielded-mode subsystem (§4).
Policy JSON in `asset-model.md` §8.3; exercised end-to-end by `SigmaGatedMorphismSuite`.

> **CAVEAT (load-bearing):** none of metakit's verifier opcodes — Groth16 / Poseidon-Merkle, nor the
> Σ-protocol family (`prove_dlog` / `prove_dhtuple` / `sigma_verify`, whose strong-FS + CDS surface is
> implemented + live but **not yet externally audited**, metakit `docs/sigma-verify.md` §0) — has a
> public security audit. A ZkVerify guard is sound only up to that verifier; it **must not protect real
> value until the verifier is audited**. (We lean on *one reused deterministic verifier* — far better
> than hand-rolling per use — but "audited" is struck from the pitch.)

---

## 3. The `Pool` morphism — the lossy compose (recommended low-hanging)

The faithful `Compose`/`Decompose` we built is a **retraction**: Compose stores a component witness so
Decompose can restore originals exactly. Its **dual** is a deliberately lossy compose — a
**coequalizer / quotient-by-relabeling** that *identifies* the components and keeps only the conserved
scalar:

- **`Pool` (provenance-forgetting melt) — LOW-HANGING.** Consume N same-`policyId` components → one
  output with `amount = Σ`, `provenance = None`, `componentFiberIds = None`, `componentsCommitment =
  None` (so it is **not** a composite and has **no** `Decompose` — the model already enforces
  "you can't un-pool" for free, because un-pooling needs the witness `Pool` deliberately didn't write).
  Same-policy gating keeps `behavior` unambiguous and `derivedSupply` provably invariant (Pool can't
  mint/burn). It is a small, deterministic, additive combiner branch (a witness-free sibling of
  `applyCompose`), no new signed message, no new committed key, no zk.
- **Why it's useful:** it is the **holder-side complement** to the interop functor's *structural*
  anti-fragmentation cure (`asset-interop-functor.md` §6.4): even when wrapped-USDC-from-bridge-A and
  -B legitimately carry different `OriginProvenance`, a holder can `Pool` them into one fungible
  balance, *knowingly and publicly* trading the per-bridge attestation lineage for fungibility.
  Preserve-by-default (interop) and forget-by-opt-in (`Pool`) are **different axes** (which policy an
  asset belongs to vs. whether a holder keeps the origin breadcrumb), so offering both is coherent —
  *provided* `Pool` is gated to a single canonical `policyId`.
- **`Mix` (holder-mixing coinjoin/ZeroJoin) — PARKED (over-complication).** A quotient by a *hidden*
  permutation. The verifier opcodes exist (so the lossy-compose agent's "can't, no verifier" reason is
  wrong), but it is still parked because: (a) a commit-reveal shuffle reveals the permutation to the
  public, light-client-provable combiner — so it is **not actually unlinkable**; (b) real unlinkability
  needs a zk shuffle circuit + a nullifier set (unbounded TOTAL committed-state) — a subsystem; (c) it
  **fights** the public-readable-state ethos head-on (unlinkability requires that virtue to fail for the
  linkage data). Revisit only if a confidential-pool RFC (§4) is undertaken.

**Files for `Pool` (eventual implementer):** `schema/asset/MorphismKind.scala` (new `Pool` case),
`AssetCombiner.scala` (`applyKind` branch + `applyPool` + `structuralOk`/L1 reusing the `C` bit),
`AssetMorphismLawSuite` (pool conserves Σamount; pool output `isComposite == false`; Decompose of a
pooled asset rejected; mixed-policy rejected). Frame in `asset-model.md` §4 as the coequalizer dual.

---

## 4. Confidential amounts / shielded mode — reachable, but a subsystem (future RFC)

The two earlier assessments **conflicted** (external audit: "P0 low-hanging"; metakit feasibility:
"blocked, no range opcode"). Reconciled truth, from the adversarial pass:

- **In-VM homomorphic-commitment confidential amounts are blocked / unwise:** metakit has no Pedersen
  or range-proof opcode. A Pedersen commitment is buildable from `bn254_add`/`bn254_mul` (and is *not*
  itself the SPL trap — see §5), but it is **useless without a range proof**, and a hand-rolled range
  proof *is* the SPL-class liability. Don't.
- **Circuit-based confidential transfer already exists:** metakit-sdk ships a complete **SP1
  shielded-transfer circuit** (Poseidon note commitments + nullifiers + Poseidon-Merkle membership +
  **value conservation + range**) wrapped to Groth16-BN254 and verified on-chain by the existing
  `groth16_verify` opcode. So confidential amounts are **reachable via the ZkVerify path**, not via an
  in-VM commitment.
- **Therefore it's a subsystem, not a low-hanging slice:** the crypto is done; the *work* is the
  on-chain integration — a shielded `AssetPolicy` mode, a **nullifier set as a TOTAL committed-view
  key** (combiner-only per CLAUDE.md #3; TOCTOU-safe), and a **bounded-growth / state-rent** design
  (nullifier sets are monotonic and unbounded, unlike `usedNonces` which prunes). Plus a verifier audit
  (§2 caveat) before it guards real value. **Separate RFC.**

---

## 5. Categorical verdict — retraction stays (with two prose corrections)

The decision **survives a genuine adversarial attack**: `Decompose ∘ Compose` is a retraction, and the
isomorphism is not worth building. Reasons that held under attack:

- The identity/behavior axis is an aggregation monoid `(⊎, ∅)` with **no inverses** — you can't
  "un-union" a multiset without remembering its contents. No cryptographic accumulator exists in
  metakit, and even one would compress the *witness storage*, not give the monoid an inverse.
- The only group-eligible coordinate is **amount**, which is already a cleanly conserved scalar. Making
  Compose a commitment-group isomorphism would upgrade *only* that axis, at real cost (per-Compose
  `bn254_mul` at 40k gas, blinding-factor management, *and* a range proof) — and a bare Pedersen
  homomorphism on Compose **without** a range proof is *less safe* than the retraction (amount
  wraparound mod R mints value). The safe way to hide split amounts is the shielded circuit (§4), not
  an isomorphism.
- shield/unshield is **not** an adjunction/Galois connection either: `shield ∘ unshield ≠ id` (fresh
  randomness → a different commitment) — a left inverse, i.e. a retraction again. zk **hides** data; it
  does not **invert maps** or remove the interop functor's forgotten-decoration obstruction.

**Two corrections to make in `asset-interop-functor.md` prose** (the decision is right; two supporting
arguments were wrong):

1. A **bare Pedersen commitment** (3 calls to the already-audited `bn254_add`/`bn254_mul`; the additive
   homomorphism is already exercised in metakit's `ZkOpsWave2Suite`) is **not** "the SPL ZK-ElGamal
   trap." The SPL failure was a bespoke *ElGamal encryption + custom range/equality proof system*. The
   real liability is the **range proof**, not the commitment. Re-state the constraint as **"no novel
   proof system / encryption"** (which *admits* commit-reveal, BLS-threshold gates, and a Pedersen
   commitment from audited primitives), not "no group ops / lean only on the SP1 verifier."
2. Confidential transfer is **not** a "deliberately omitted novel-ZK liability" — metakit-sdk already
   ships the audited-pattern circuit, reachable via the endorsed ZkVerify path. It is omitted from
   *JLVM-state-in-the-clear*, not from what's buildable.

---

## 6. Are the chosen constraints optimal?

Near-optimal **for our thesis** (a verifiable trust commons anyone can audit; light clients verify
state). They are not universally optimal — a privacy-maximalist chain picks the opposite trades. Within
our thesis:

- **public-readable state** is the one constraint that genuinely bounds privacy utility (we can prove
  in zero-knowledge via guards, but our *state* is public, so hiding amounts/holders needs the §4
  subsystem). That's a conscious, defensible trade, not an oversight.
- **"no novel ZK"** is too conservative *as phrased* — sharpen it to **"no novel proof-system /
  encryption."** metakit exposes a composable, audited-primitive toolkit (`poseidon`, `pmt_verify`,
  `bls_aggregate_verify`, `schnorr_verify`, `bn254_*`) that builds commit-reveal, threshold gates, and
  allowlists *without any new ZK*. Those are in-bounds.
- **determinism** is a non-issue: every crypto opcode is pure/deterministic; it neither blocks nor is
  blocked.

---

## 7. Honesty caveats (carry into any pitch)

- metakit's verifier has **no public security audit** → never say "audited"; a verifier audit is a hard
  prerequisite before any zk-gated path guards real value.
- Everything here except the **ZkVerify-gated morphism** (shipped) is **proposed, not implemented**:
  `Pool`, the shielded mode, the nullifier set, viewing keys.
- We deliberately decline: a second crypto stack or VM; hand-rolled proofs; Ergo's sigma/ring idiom;
  Plonkish/Halo2/Cairo engines; Penumbra's validator-DKG DEX; Aztec client-side execution; Midnight's
  Compact compiler + client-held private state. (See `zk-coin-audit.md` "what we should not do.")

---

## 8. Recommendations / roadmap

| # | Item | Effort | Status |
|---|---|---|---|
| 1 | **ZkVerify-gated morphism** (proof/Merkle-witness guards) | low | **DONE** (PR #166, `5267cc1`) |
| 2 | **`Pool` morphism** (lossy, provenance-forgetting; single-policy; the coequalizer dual) | low | recommended next |
| 3 | Prose fix in `asset-interop-functor.md` (Pedersen ≠ SPL trap; ack the shipped shielded circuit; "no novel proof-system") | trivial | recommended |
| 4 | Selective-disclosure **viewing keys** (auditability-with-privacy; fits the residual-trust thesis) | moderate | after a shielded mode exists |
| 5 | **Shielded `AssetPolicy` mode** (confidential amounts + nullifier set, via the shipped SP1 circuit) | large (subsystem) | separate RFC; gated on a verifier audit + nullifier state-rent design |
| — | `Mix`/coinjoin; in-VM homomorphic-commitment isomorphism | — | **declined** (over-complication / unsafe / fights the ethos) |

**Superset verdict (from `zk-coin-audit.md`):** **Ergo — clean superset** on both axes; **Midnight —
partial** (match/beat on the confidential-asset subset + public-tier legibility; decline its
client-held-private-state Kachina model — a different trust architecture, not a feature gap).
