# adjudicated-htlc — griefing-resistant hash-time-lock (live e2e)

An extension of [`atomic-swap`](../atomic-swap) that closes the HTLC **griefing / free-option attack**,
and the first live exercise of **`schnorr_verify`** alongside **`poseidon`**.

## The attack

A plain HTLC is `claim(preimage)` or `refund(after timeout)`. The party who controls the preimage can
simply **withhold it**: the counterparty's funds are then frozen until the timeout, and in a two-leg swap
the withholder holds a free American option — wait, watch the market, and complete or abort at no cost
while the counterparty's capital is hostage. Lengthening timeouts makes the hostage window worse.

## The fix — an adjudicator path

Add a third route: when a swap stalls, either party `dispute`s (`LOCKED → DISPUTED`), and a **trusted,
high-reputation adjudicator** resolves it *before* the timeout by signing a ruling:

```
LOCKED ──claim (poseidon([secret]) === hashLock, within timeout)─────────▶ CLAIMED
   ├───refund ($ordinal > timeoutOrdinal)──────────────────────────────▶ REFUNDED
   └───dispute (reason) ─▶ DISPUTED ──adjudicate_release (Schnorr ruling)─▶ CLAIMED   (→ recipient)
                                   └──adjudicate_refund  (Schnorr ruling)─▶ REFUNDED  (→ sender)
```

The adjudicator can't be impersonated (the ruling is a BN254 Schnorr signature verified by
`schnorr_verify` against the contract's pinned `adjudicatorPubKey`), and can't act unilaterally — a
`dispute` must be open first. So funds are never hostage past a short dispute window, yet no one can
steal them by faking a resolution.

## "High enough reputation"

The `adjudicatorPubKey` is the signing key of an agent the parties selected **because of their on-chain
reputation** (`adjudicatorReputation: 95 ≥ minAdjudicatorReputation: 90` here, informational). In
production that selection is enforced live, not pinned: the contract declares the identity-registry as a
runtime dependency (`_addDependency`, #24) and gates adjudication with the SDK's
`signerHasReputationVia(registryId, threshold)` / `signerHasRoleVia(registryId, "arbiters")` — so only an
agent whose **current** registry reputation (or `ARBITER` attestation) clears the bar can resolve. This
e2e pins the adjudicator key because the e2e harness is single-fiber (no cross-fiber registry read), but
the guard shape is identical — one reads the key from state, the other reads reputation from the registry.

## Opcodes

- **`poseidon`** — the hash-lock (`poseidon([secret]) === state.hashLock`), as in `atomic-swap`.
- **`schnorr_verify`** — `[pk(64B), msg, proof(96B)]` on BN254 G1. The ruling `proof = R‖s` with
  `c = SHA256(R‖pk‖msg) mod r`, `s = k + c·x`; produced off-chain with the same construction the chain
  verifies (byte-aligned via `@noble/curves` bn254 + `@noble/hashes` sha256).

## Flows (`example.json`)

| Flow | Result |
|---|---|
| honest `claim` (correct preimage) | LOCKED → **CLAIMED** |
| `claim` wrong preimage | **ml0-rejected** |
| `dispute` → `adjudicate_release` (valid ruling) | LOCKED → DISPUTED → **CLAIMED** — griefing resolved |
| `dispute` → `adjudicate_release` (forged ruling) | **ml0-rejected** — adjudicator can't be faked |

## A note on message binding

The ruling message is pinned per outcome (`"release:HTLC-ADJ-001"` / `"refund:HTLC-ADJ-001"`), so a
release ruling can't be replayed as a refund or onto another contract id. For reusable, multi-instance
deployment the message should be **chain-computed and nonce-bound** rather than a literal — the same
discipline the `sigma_verify` message-binding spec (PR #170) applies to Σ-gated asset guards.

## Run

```bash
cd e2e-test && npx tsx runner.ts --target local
```
