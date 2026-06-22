# sigma-mixer — CDS OR-of-dhtuple ring mixer (live e2e)

An Ergo-style equal-denomination **privacy mixer** whose anonymity comes from a CDS (Cramer–Damgård–Schoenmakers)
**OR-of-`dhtuple`** Σ-protocol ring proof, verified on-chain by the **`sigma_verify`** JLVM opcode. The first live
exercise of `sigma_verify` and the **witness-bound-nullifier** discipline.

## States & lifecycle

```
filling ──deposit (depositCount+1 < target) ─────────────────▶ filling   (ring grows; points := merge[points,[point]])
   └──────deposit (depositCount+1 == target, declared FIRST) ─▶ open      (ring frozen)
open ──withdraw (ring proof ∧ !spent ∧ msg-bind ∧ wc+1 < target) ─▶ open     (records Nf, withdrawCount++)
   └───withdraw (… ∧ wc+1 == target, declared FIRST) ───────────▶ drained  (one-shot)
```

`initialState: "filling"`. Transition **ordering is load-bearing**: the engine fires the FIRST transition (in
declaration order) for `(state, eventName)` whose guard is `true`. The `→open` deposit and the `→drained` withdraw
are declared FIRST with **mutually-exclusive** boundary guards (`depositCount+1 == target` vs `< target`), so the
Nth deposit deterministically flips the lifecycle (closes the B1 boundary bug) and `withdrawCount ≤ target` bounds
the spent-set.

## Why OR-of-`dhtuple`, not OR-of-`dlog` (the B2 fix)

A `dlog` OR-proof proves only "I know *some* discrete log in this set" — the nullifier would be free prover-chosen
bytes merely folded into the Fiat–Shamir message, so one depositor could withdraw `n` times under `n` arbitrary
nullifiers and **drain the ring**.

The fix uses the `dhtuple` (DDH / Chaum–Pedersen) leaf. A satisfied `dhtuple(G, H, P_i, Nf)` proves knowledge of one
`x` with **`P_i = x·G` AND `Nf = x·H`** (the verifier uses a SINGLE shared response `z` for both coordinate
reconstructions: `a1 = z·G − e·P_i`, `a2 = z·H − e·Nf`). The withdrawer supplies
`OR_i( dhtuple(G, H, P_i, event.nullifier) )` — the **same `v = event.nullifier` in every branch** — so the OR hides
*which* branch while forcing `event.nullifier = x_j·H` for the one real branch. The map `x ↦ x·H` is deterministic and
injective, so the nullifier is cryptographically bound to the witness: the same secret always yields the same `Nf`
(a second spend hits the spent-set), and no one can mint a different `Nf` for a branch they don't have the secret for.

## The withdraw guard (3 load-bearing clauses)

1. **Witness-bound ring membership in ZK** — `sigma_verify(OR(dhtuple(G,H,P_i,Nf)))`. The `u` points read from the
   FROZEN on-chain `state.points`, so the ring is not attacker-supplied; `v = event.nullifier` in every branch.
2. **No double-withdraw** — `{"!":{"has":[state.spentNullifiers, event.nullifier]}}`. Because `Nf` is witness-bound,
   the same secret cannot produce a second distinct nullifier.
3. **Recipient binding (anti-front-run)** — `message == Nf ‖ recipientHex`. Strong Fiat–Shamir folds the message into
   the root challenge, so the proof is a signature over `(Nf, recipient)`; a mempool front-runner swapping the
   recipient breaks both the `===` and the proof.

## `H` — nothing-up-my-sleeve (INV-7)

`H` is a NUMS BN254 G1 base with **unknown discrete log w.r.t. `G=(1,2)`**. A botched `H` (where `H = c·G` for a known
`c`) re-opens the drain-the-ring attack — a withdrawer could forge `Nf = c·P_i` for any victim branch. `H` is derived by
**try-and-increment hash-to-curve** over `SHA256("sigma-mixer:nullifier-base:v1")` onto `y² = x³ + 3`; recovering `c`
would require solving the discrete log of a hash output. The derivation is documented and reproducible in
`ottochain-sdk/scripts/gen-sigma-mixer-fixture.ts`. BN254 G1 has prime order (cofactor 1), so every on-curve point is
in the prime-order group; `H` is asserted on-curve, `≠ identity`, and `≠ G`.

## Fixtures are REAL BN254

Every proof in this example is a genuine BN254 transcript produced offline by
`ottochain-sdk/scripts/gen-sigma-mixer-fixture.ts` (`@noble/curves/bn254`), then **verified through the real
`sigma_verify` opcode** before check-in: the honest proof returns `true`; the forged-nullifier and bad-recipient
proofs return `false`. Regenerate with `npx tsx scripts/gen-sigma-mixer-fixture.ts`.

## Scope (custody is production-only, H1)

This single-fiber e2e is **crypto + lifecycle plumbing**. Economic custody (`_transferAsset` in on deposit, out on
withdraw) is exercised only in the production SDK app (`@ottochain/sdk/apps/privacy`), NOT here. Crucially, the **B2
double-spend is observable WITHOUT custody** (flow 4): the forged nullifier is rejected at the proof layer, not merely
at a transfer, so the e2e detects the original drain-the-ring flaw even with no value movement.

## Privacy guarantee (INV-3, disclosed)

Anonymity set = **exactly `n = 4`** and the ring size is **public**. A 1:1 timing-correlated deposit/withdraw still
narrows linkage; this is inherent to fixed-ring mixers and is disclosed, not assumed away. Production hardening
(equal-denomination custody, `mixerId`-prefixed message for cross-instance replay safety, larger/pinned rings, an `H`
NUMS audit) is out of e2e scope.

## Test flows

| # | Flow | Asserts |
|---|------|---------|
| 1 | B1 fill-flip: 4 deposits | 3× `filling`, 4th → `open` (mutually-exclusive open-first deposit ordering) |
| 2 | Honest withdraw | ring proof verifies; stays `open` (1st of 4) |
| 3 | Replay (same Nf) | `expectRejected: ml0` — `has(spentNullifiers, Nf)` denies |
| 4 | **B2** forged nullifier | `expectRejected: ml0` — shared-`z` dhtuple rejects `Nf' ≠ x_j·H` |
| 5 | **H2** bad recipient | `expectRejected: ml0` — message-binding `===` fails |
