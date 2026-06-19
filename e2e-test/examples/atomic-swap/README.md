# atomic-swap — HTLC hash-time-lock (live e2e)

A **hash-time-locked contract (HTLC)** — the primitive trustless atomic swaps are built from, ported
from the ErgoScript-by-example single-chain swap. It is the first **live-metagraph** exercise of the
metakit `poseidon` opcode as a cryptographic gate (the zk-eligibility example covers `groth16_verify`).

Funds sit in `LOCKED`, gated two ways:

- **Hash-lock** — the recipient `claim`s by revealing a `secret` whose `poseidon([secret])` equals the
  pinned `state.hashLock`. The chain re-hashes the submitted preimage with the same metakit `poseidon`
  the SDK runs, so it can only be opened by someone who knows the true preimage.
- **Time-lock** — the sender can `refund` only **after** `state.timeoutOrdinal` (`$ordinal > timeoutOrdinal`),
  so they cannot reclaim early and front-run a legitimate claim.

```
LOCKED ──claim (poseidon([secret]) === hashLock, within timeout)──▶ CLAIMED   (preimage published)
   └────refund ($ordinal > timeoutOrdinal)──────────────────────▶ REFUNDED
```

## Why this is an atomic swap

`claim` writes the revealed `secret` into `state.preimage`, publishing it on-chain. In a two-party swap
both parties lock with the **same** `hashLock`; the moment the first party claims (revealing the secret),
the counterparty reads it and opens the matching HTLC with the same value — either both claims happen or,
past the timeout, both refund. This single HTLC is one leg; deploy two with a shared `hashLock` for a full
swap.

## The guard (`definition.json`)

```jsonc
"claim": {
  "and": [
    { "===": [ { "poseidon": [[{ "var": "event.secret" }]] }, { "var": "state.hashLock" } ] },
    { "<=": [ { "var": "$ordinal" }, { "var": "state.timeoutOrdinal" } ] }
  ]
}
"refund": { ">": [ { "var": "$ordinal" }, { "var": "state.timeoutOrdinal" } ] }
```

`poseidon` takes an **array of 32-byte lowercase-hex field elements** and returns a 32-byte hex digest.
`event.secret` is one such element; `state.hashLock` in `initial-data.json` is `poseidon([secret])`,
computed off-chain with `@constellation-network/metagraph-sdk-jlvm` (byte-for-byte the chain's evaluator).

## Flows (`example.json`)

| Flow | Result |
|---|---|
| `claim` with the correct preimage | LOCKED → **CLAIMED** (preimage recorded) |
| `claim` with a wrong preimage | **rejected** (`ml0`) — poseidon mismatch, fiber unchanged |
| `refund` before the timeout | **rejected** (`ml0`) — time-lock holds |

## Run

```bash
# from e2e-test/ — stands up / targets a metagraph and picks up this flow with the others
npx tsx runner.ts --target local
```

> The `secret` / `hashLock` literals are byte-aligned with the metakit `poseidon` opcode. To re-roll the
> pair: `poseidon([<your 32-byte hex secret>])` via the jlvm package gives the `hashLock` to pin.
