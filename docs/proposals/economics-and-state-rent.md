# Economics & State Rent — design (simple now, notes for the ideal)

**Status:** draft / design. Date: 2026-06-04. Branch: `feat/versionable-contracts`.
**Question:** how do we charge for fibers, pay for putting schema/definition bytes on-chain, and keep the
registry maintainable at scale? Today there is **execution gas but no ongoing storage cost** — so state
(fiber records, registry metadata) grows unbounded and for free, which doesn't scale. This note sets the
three cost dimensions, ships the simple ones, and records the ideal for the rest.

## Three cost dimensions

1. **Execution gas — HAVE.** Per-transition compute: metakit VM gas + `FiberGasConfig` (trigger/spawn/
   context). One-time, charged per transaction. No change.

2. **Registration / data-availability gas — SIMPLE NOW (implement in #23).** Deploy-and-claim option (B)
   puts the schema (FileDescriptorSet) + JSON-Logic definition **bytes** into the registration update's
   history (calldata-style) so the schema is trustlessly re-verifiable from chain history. That has a real
   data cost, so charge proportional to size:

   > `registrationGas = (schemaBytes + definitionBytes) × registrationGasPerByte`

   charged at the registration combiner, on top of the normal fiber gas. A `registrationGasPerByte` knob
   (FiberGasConfig-style profiles) lets us price DA. This is the immediate answer to "charge gas appropriate
   to the cost of putting that data on-chain." (If we later choose hashes-only updates, this cost drops to a
   flat per-version fee.)

3. **State rent — FUTURE / TODO (design below; not built now).** The ongoing cost of *occupying live state*
   — fiber records (`state_data`) and registry metadata — over time. This is the dimension that actually
   bounds state growth; without it, every fiber lives rent-free forever. Stubbed for now; designed here.

## State-rent collection — recommended: Tessellation AllowSpend pull + a solvency FSM

Don't reinvent an accounting layer — **collect rent in the network token via Tessellation's own
primitives** (AllowSpend / SpendAction), not internal scrip. Two complementary tiers:

### Tier 1 — Pull-subscription (pay-as-you-go), via AllowSpend/SpendAction *(the recommended mechanism)*
A fiber owner pre-authorizes the metagraph with a Tessellation **AllowSpend** (an approve-style cap on the
network token). On a fixed **ordinal cadence** (every X snapshots — deterministic, like the eta/participation
epoch sweeps), the metagraph iterates fibers in canonical order, computes `rentDue = sizeBytes ×
ratePerBytePerEpoch × epochsElapsed`, and emits a **SpendAction** pulling `rentDue` from the AllowSpend
(propagated to gl0 like any spend). This is exactly an **Akash/Filecoin escrow-lease** (escrow + per-epoch
draw + lease ends on depletion) and **ENS renewal** — but on real network value. (Tessellation already has
`ActiveAllowSpends` / `LastAllowSpendRefs` / SpendAction state, so this leans on existing wiring.)

**Solvency state machine (graceful degradation — orthogonal to the execution `FiberStatus`):**
`Active → Degraded → Disabled → Reaped`.
- **Active** — rent current.
- **Degraded** — a pull failed (AllowSpend exhausted/expired/invalid); grace period; fiber still readable,
  flagged, may reject *new* transitions. Owner cures by renewing/topping-up the AllowSpend (ENS grace).
- **Disabled** — after K consecutive failed pulls / grace expired: processing halts (no transitions or
  triggers); state stays on-chain, recoverable within a reclaim window.
- **Reaped** — after the reclaim window: state evicted from live `CalculatedState` (commitment retained in
  history), with a **reap bounty** to whoever triggers it (Solana/Akash self-pruning).

This is the user-proposed "metagraph spends from an allow-spend every X snapshots; invalid → degraded →
disable → cleanup" — sound, and matched to the most mature production analogs.

### Tier 2 — Deposit-for-exemption (Solana), *complementary*
A fiber that locks a size-proportional **refundable deposit** covering ≥ N epochs of rent is **rent-exempt**
— no periodic pull, no degradation; the deposit is refunded on archive/delete. So well-funded
"set-and-forget" fibers skip the per-X-snapshot SpendAction overhead; only under-funded "subscription"
fibers get the pull + degradation path. Offer both.

### Decisions
- **Granularity:** prefer a **per-owner** AllowSpend/account funding all of that owner's fibers (one
  subscription, better UX); degrade per-fiber if the shared pool can't cover the canonical-order sweep.
  Per-fiber AllowSpend is the isolated alternative.
- **Schemas vs fibers:** the heavy mutable state is the **fiber instance** (`state_data`) — the rent target.
  **Schema registry entries** are tiny, shared, content-addressed (dedup'd), immutable types → a one-time
  **registration deposit** (anti-spam), not ongoing rent; a Yanked + unreferenced version is reapable.
- **Determinism:** cadence (`ordinal % X == 0`), `rentDue`, AllowSpend validity, and SpendAction emission
  are pure functions of consensus state at a fixed ordinal, iterated in canonical order → every node
  computes identical pulls/degradations/reaps (reuse the epoch-boundary sweep machinery; no wall-clock).

Still a real subsystem (AllowSpend/balance wiring + the ordinal-cadence sweep + the solvency FSM + reap
bounty). **Not now** — but this is the target, and it rests entirely on existing Tessellation primitives.

## Keeping the registry scalable

The registry stays cheap *by construction* — the same reasons it's content-agnostic:
- **Content-addressed dedup:** identical descriptors/definitions share a hash → stored once, referenced by
  hash from many entries. The registry's *committed* footprint never includes duplicate bytes.
- **Lean live state:** `CalculatedState` holds only hashes + small metadata → footprint is
  `O(entries × versions × small)`, **not** `O(schema bytes)`. The heavy bytes live in the append-only
  history (prunable/archivable) + the Bridge store — never in hot live state.
- **GC for dead versions:** a Yanked + unreferenced version (no live fiber pins it) is **reapable from live
  state** — its hash stays re-derivable from history, so nothing is lost. Bounded live registry.
- **Anti-spam:** a registration deposit (state-rent dimension) makes junk-schema spam costly.

## Simple-now vs ideal

| Dimension | Now | Ideal (TODO) |
|---|---|---|
| Execution gas | ✅ metakit VM gas + FiberGasConfig | — |
| Registration / DA gas | ⏳ `bytes × registrationGasPerByte` at #23 | tiered DA pricing; refunds on archive |
| State rent | ❌ none | size-proportional deposit-for-exemption + per-epoch rent + reap/bounty + refunds |
| Registry GC | ❌ grows | reap Yanked + unreferenced versions from live state |
| Anti-spam | partial (gas) | registration deposit |

## TODOs (code)
- `// TODO(economics): charge registrationGas = bytes × registrationGasPerByte at the registration combiner (#23).`
- `// TODO(economics): state-rent subsystem — fiber/registry deposits + per-epoch rent sweep + reap/bounty + refunds; reuse epoch-boundary machinery; see economics-and-state-rent.md.`
- `// TODO(economics): reap Yanked + unreferenced registry versions from live CalculatedState (hash re-derivable from history).`
