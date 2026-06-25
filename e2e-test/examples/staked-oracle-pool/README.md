# Staked oracle pool

A reusable **staked-epoch-pool** std-lib base (`@ottochain/sdk/apps/staked-pool`) specialized as an
**oracle pool** — proving the OttoChain account layer can carry real economic coordination. It composes
existing SDK guards/effects only (plus two helpers added this workstream: `transferAsset`,
`actorNotInArray`).

## Lifecycle

```
FORMING (initial) ──open_first_epoch──▶ COLLECTING ──finalize──▶ SETTLED ──reset_epoch──▶ COLLECTING …
   │ bind_registry (×, authority)          │ submit (×)            │ claim_reward (×)
   │ stake_and_join (×, FORMING+COLLECTING) │                       │ withdraw_stake (×)
   └────────────────────────── close (FORMING/COLLECTING/SETTLED) ──▶ CLOSED (final)
```

Multi-`from` arms are SPLIT (the chain `from` is a single string): `stake_and_join` ×2, `claim_reward` ×2,
`withdraw_stake` ×2, `close` ×3 — 14 transitions total.

## The worked oracle flow (`example.json`)

1. **`bind_registry`** (authority) — binds the identity registry as a runtime dependency (#24). MUST
   precede any reputation-gated join: the reputation gate reads `machines.<registryId>`, which only exists
   after this `_addDependency`.
2. **`stake_and_join`** ×4 — each participant Transfers their whole stake instance into `Fiber(poolId)`
   BEFORE the event; the guard re-verifies custody via `heldAssets[stakeAssetId].amount >= stakeAmount`
   (**H5** — stake reality), is reputation-gated via `signerHasReputationVia(registryId, minReputation)`
   (the #24 dynamic-dependency pattern), and pins the written participant key to the verified signer
   (`actorIsSigner` — anti-S1).
3. **`open_first_epoch`** (authority) — FORMING → COLLECTING.
4. **`submit`** ×4 — joined participants append `{addr, value}` datapoints to the `submissions` array via
   `merge[submissions, [record]]`. Values: alice=100, bob=102, carol=98, **dave=500** (the outlier). Dedup
   is `actorNotInArray` (a `none`-over-array check — there is no `(array,int)` index op).
5. **`finalize`** (authority, after quorum + window) — computes the outlier-bounded **trimmed mean** (drop
   single min + single max, then `|value - center| <= outlierBound`), publishes
   `result = { value: 100, epoch: 1, finalizedAt }`, and records `inConsensus = [alice, bob, carol]` (dave
   excluded). **Emits ZERO asset transfers** — the in-consensus set IS the entitlement ledger.
6. **`claim_reward`** (in-consensus participant) — pulls ONE whole reward instance the pool holds to the
   claimant's wallet (`_transferAsset`, per-claim on a shared fungible), and sets `claimed[addr] = true`.
   A second claim from the same address, or a claim from the outlier dave, is rejected.
7. **`reset_epoch`** (authority) — SETTLED → COLLECTING; clears `submissions` / `inConsensus` / `claimed`.

`withdraw_stake` returns a participant's staked instance with exactly one `_transferAsset` (H4-safe: the
transfer reads `stakeAssetIds[agent]` against PRE-merge state while a sibling key unsets it).

### Rejection flows asserted

- `finalize` before quorum → `expectRejected: ml0`.
- double-`submit` from the same address → `expectRejected: ml0` (array dedup).
- `submit` from a non-participant → `expectRejected: ml0`.

## Cross-fiber consumer read (`consumer-definition.json`)

A separate consumer fiber pulls the pool's published answer via the **epoch-pinned two-phase** (#24)
pattern:

- **`bind_pool`** (phase 1) — `_addDependency(poolId)` AND pins `expectedPoolEpoch = machines.<poolId>.state.epoch`.
- **`read_answer`** (phase 2) — gates on `depInState(poolId, "SETTLED")` **AND**
  `machines.<poolId>.state.epoch === expectedPoolEpoch`, then reads `result.value` into the consumer's own
  `answer`. The epoch pin (M4) makes the read epoch-EXACT: if the pool has cycled to a later epoch between
  bind and read, the gate is `false` and the consumer must re-bind — it never silently reads a stale
  answer. (Verified: read gate `true`/answer `100` for the pinned settled epoch; `false` once the pool
  advances to epoch 2.)

The single-fiber e2e harness binds one live fiber per example, so the consumer's two-phase read is shipped
as a VM-validated definition rather than a live two-fiber harness flow; the pool flow above runs against
the harness directly.

## Security invariants (design §9)

- **S1 coupling** — every `event.agent`-keyed write (`stake_and_join`, `submit`, `claim_reward`,
  `withdraw_stake`) is paired with `actorIsSigner`/`actorHasEntry` on the same actor.
- **H5 stake reality** — no membership without verified `heldAssets` custody.
- **≤1 transfer** per claim/withdraw, **0** at finalize → never the 32 asset-mutation cap.
- **M1 totality** — every map is `{}` and every array `[]` at genesis (reads on `null` hard-error).
- **H4 pre-merge** — `merge`-payload values read pre-merge state; withdraw's transfer is not stranded.
- **M4 epoch pin** — the consumer read gates on both `SETTLED` and the pinned epoch.

Aggregation is a trimmed mean (no `sort`/`median` opcode exists); hard-slashing of outliers is deferred to
v2 (soft slash = no reward credit for outliers is the shipped economic security).
