# OttoChain Fee Schedule Specification

**Status:** Draft  
**Target:** Scratch/Testnet  
**Last Updated:** 2026-02-27

---

## Overview

OttoChain uses Tessellation's built-in `FeeTransaction` mechanism to charge for data operations. Fees serve two purposes: spam prevention and treasury funding for metagraph operations. This spec defines the fee schedule, collection strategy, estimation API, and rollout plan.

---

## 1. Fee Schedule

### Design Principles

- **Create operations** are expensive — they allocate persistent state (fiber or script entry)
- **Transition/Invoke operations** are cheap — they modify existing state
- **Archive** is free — it reclaims state, so we incentivize cleanup
- **Payload scaling** applies a small multiplier for large messages to deter abuse

### Base Fees (in datum, 1 DAG = 100,000,000 datum)

| Operation             | Base Fee (datum) | Notes                                  |
|-----------------------|-----------------|----------------------------------------|
| `CreateStateMachine`  | 1,000,000       | ~0.01 DAG; allocates fiber + definition |
| `TransitionStateMachine` | 100,000      | ~0.001 DAG; modifies existing fiber    |
| `ArchiveStateMachine` | 0               | Free; incentivize cleanup              |
| `CreateScript`        | 1,000,000       | ~0.01 DAG; allocates script + program  |
| `InvokeScript`        | 100,000         | ~0.001 DAG; runs method on script      |

### Payload Size Surcharge

For messages exceeding a base size threshold, apply a per-KB surcharge:

```
totalFee = baseFee + max(0, (payloadBytes - baselineSizeBytes) / 1024) * perKbFee
```

| Config Key              | Default Value |
|-------------------------|--------------|
| `fees.baselineSizeBytes` | 4096 (4 KB) |
| `fees.perKbFee`          | 10,000 datum |

This keeps small messages at fixed cost while taxing large definitions/programs.

---

## 2. Treasury Address

**Decision: Single configurable treasury address.**

Rationale:
- Per-node fee collection adds complexity and requires consensus on distribution
- A single treasury is simple, auditable, and sufficient for testnet
- Can be changed via metagraph config without code changes

The treasury address is set in metagraph config:

```hocon
ottochain {
  fees {
    treasuryAddress = "DAG..."   # Required; no default — must be explicit
    enabled = false              # Master switch (false = estimation only, no enforcement)
  }
}
```

If `treasuryAddress` is not set and `enabled = true`, metagraph should fail to start with a clear error.

---

## 3. Fee Estimation

### Flow

1. Client constructs an unsigned `DataUpdate` (e.g., `CreateStateMachine`)
2. Client POSTs to `POST /data/estimate-fee` with the serialized update
3. Metagraph calls `estimateFee(ordinal)(dataUpdate)` and returns:

```json
{
  "amount": 1000000,
  "treasuryAddress": "DAG...",
  "currency": "datum"
}
```

4. Client signs a `FeeTransaction(source, treasuryAddress, amount, dataUpdateRef)` and submits both together

### `estimateFee` Implementation

```scala
override def estimateFee(ordinal: SnapshotOrdinal)(dataUpdate: DataUpdate): F[Long] =
  dataUpdate match {
    case u: CreateStateMachine  => payloadFee(config.fees.createStateMachineFee, u)
    case u: TransitionStateMachine => payloadFee(config.fees.transitionFee, u)
    case _: ArchiveStateMachine => 0L.pure[F]
    case u: CreateScript        => payloadFee(config.fees.createScriptFee, u)
    case u: InvokeScript        => payloadFee(config.fees.invokeFee, u)
  }

private def payloadFee(baseFee: Long, update: DataUpdate): F[Long] = {
  val bytes = encode(update).length
  val surplus = math.max(0, bytes - config.fees.baselineSizeBytes)
  val perKb = surplus / 1024 * config.fees.perKbFee
  (baseFee + perKb).pure[F]
}
```

---

## 4. Fee Validation

### `validateFee` Implementation

Behavior is controlled by `fees.enabled`:

```scala
override def validateFee(
  ordinal: SnapshotOrdinal
)(signedUpdate: Signed[DataUpdate], maybeFee: Option[Signed[FeeTransaction]]): F[Either[String, Unit]] =
  if (!config.fees.enabled) {
    // Grace period: accept all, log missing fees
    maybeFee match {
      case None => logger.warn(s"Fee missing for ${signedUpdate.value.getClass.getSimpleName} — enforcement not yet active")
      case Some(fee) => logger.info(s"Fee submitted voluntarily: ${fee.value.amount}")
    }
    Right(()).pure[F]
  } else {
    // Enforcement mode
    maybeFee match {
      case None => Left("Fee required but not provided").pure[F]
      case Some(fee) =>
        for {
          required <- estimateFee(ordinal)(signedUpdate.value)
          result = if (fee.value.amount >= required && fee.value.destination == config.fees.treasuryAddress)
            Right(())
          else
            Left(s"Fee insufficient or wrong destination: got ${fee.value.amount}, need $required to ${config.fees.treasuryAddress}")
        } yield result
    }
  }
```

**Note:** Validation accepts fees ≥ required (not exact), so clients can overpay safely.

---

## 5. Grace Period / Rollout

### Phase 1: Estimation Only (current / immediate)

- Deploy `estimateFee` — endpoint returns correct amounts
- `validateFee` logs but accepts all transactions (missing fee = warning, not error)
- `fees.enabled = false` in config
- SDK and bridge updated to optionally attach fees
- **No breaking changes** to existing integrations

### Phase 2: Soft Enforcement (after SDK/bridge update)

- `fees.enabled = true`
- `validateFee` rejects transactions without a valid fee
- SDK automatically attaches fee on every submission
- Bridge gateway handles fee signing transparently
- Announce with a minimum 1-week notice window on testnet

### Phase 3: Production (future mainnet consideration)

- Review fee amounts against real DAG price
- Consider fee burning vs. treasury split
- Possibly introduce governance over fee parameters

---

## 6. Configuration Reference

Full config schema (with testnet defaults):

```hocon
ottochain {
  fees {
    enabled = false                    # false = grace period, true = enforcement
    treasuryAddress = "DAG..."         # Required when enabled = true

    # Base fees in datum (1 DAG = 100,000,000 datum)
    createStateMachineFee = 1000000    # 0.01 DAG
    transitionFee = 100000             # 0.001 DAG
    archiveFee = 0                     # Free
    createScriptFee = 1000000          # 0.01 DAG
    invokeFee = 100000                 # 0.001 DAG

    # Payload size surcharge
    baselineSizeBytes = 4096           # No surcharge below this
    perKbFee = 10000                   # datum per KB above baseline
  }
}
```

All values are overridable without code changes. On testnet, fees are intentionally low to avoid friction during development.

---

## 7. Open Questions (for later)

- **Fee burning**: Should archived state fees (currently 0) eventually become negative (rebate)? Requires DAG transfer back to submitter — more complex.
- **Governance**: Fee schedule changes via on-chain proposal vs. metagraph operator config?
- **Multi-step transactions**: If a client batches multiple updates, should there be a per-bundle discount?
- **Free tier**: Should registered agents get N free transitions per epoch?

These are deferred to post-testnet. Keep it simple for now.
