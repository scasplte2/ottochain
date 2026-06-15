# OttoChain API Reference

This document covers all HTTP API endpoints for the OttoChain metagraph stack.

## Layers & Default Ports

| Layer | Port | Description |
|-------|------|-------------|
| GL0   | 9000 | Global L0 — DAG consensus, global snapshots |
| GL1   | 9100 | Global L1 — DAG transaction batching |
| ML0   | 9200 | Metagraph L0 — currency + data snapshots |
| CL1   | 9300 | Currency L1 — currency transaction batching |
| DL1   | 9400 | Data L1 — data transaction validation |

---

## Shared Endpoints (All Layers)

These endpoints are available on **all** tessellation nodes (GL0, GL1, ML0, CL1, DL1).

### Node Info

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/node/info` | Node state, version, peer ID, ports |
| `GET` | `/node/state` | Current node state (Ready, Observing, etc.) |
| `GET` | `/node/health` | Health check |
| `GET` | `/node/session` | Current session ID |

### Cluster Management

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/cluster/info` | List of peers in cluster |
| `GET` | `/cluster/peers` | Peer details |
| `GET` | `/cluster/session` | Cluster session token |
| `POST` | `/cluster/join` | Join a cluster |
| `POST` | `/cluster/leave` | Leave cluster |

### Metrics

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/metrics` | Prometheus metrics |

---

## GL0 — Global L0 (Port 9000)

### Snapshots

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/global-snapshots/latest` | Latest global snapshot |
| `GET` | `/global-snapshots/latest/ordinal` | Latest ordinal number |
| `GET` | `/global-snapshots/latest/metadata` | Latest snapshot metadata |
| `GET` | `/global-snapshots/latest/combined` | Combined snapshot (full state) |
| `GET` | `/global-snapshots/{ordinal}` | Snapshot by ordinal |
| `GET` | `/global-snapshots/{hash}` | Snapshot by hash |

### DAG Transactions

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/dag/{address}/balance` | DAG balance for address |
| `GET` | `/dag/total-supply` | Total DAG supply |
| `GET` | `/dag/circulated-supply` | Circulated DAG supply |
| `GET` | `/dag/wallet-count` | Number of wallets |

### State Channels (Metagraphs)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/state-channels` | List registered metagraphs |
| `GET` | `/metagraph/info` | Metagraph registration info |

### Staking & Delegation

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/delegated-stakes/{address}/info` | Delegation info for address |
| `GET` | `/delegated-stakes/last-reference/{address}` | Last delegation reference |
| `GET` | `/delegated-stakes/rewards-info` | Staking rewards info |

### Token Locks

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/token-locks/{hash}` | Token lock by hash |
| `GET` | `/token-locks/last-reference/{address}` | Last token lock reference |

### Allow Spends

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/allow-spends/{hash}` | Allow spend by hash |
| `GET` | `/allow-spends/last-reference/{address}` | Last allow spend reference |

### Trust & Collateral

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/trust` | Trust scores |
| `GET` | `/node-collateral` | Node collateral requirements |
| `GET` | `/node-parameters` | Network parameters |

---

## GL1 — Global L1 (Port 9100)

### Transactions

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/transactions` | Submit DAG transaction |
| `GET` | `/transactions/{hash}` | Transaction by hash |
| `GET` | `/transactions/last-reference/{address}` | Last transaction reference for address |

### Peers

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/l0/peers` | Connected L0 peers |

---

## ML0 — Metagraph L0 (Port 9200)

### Snapshots

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/snapshots/latest` | Latest metagraph snapshot |
| `GET` | `/snapshots/latest/ordinal` | Latest ordinal |
| `GET` | `/snapshots/{ordinal}` | Snapshot by ordinal |
| `GET` | `/snapshots/{hash}` | Snapshot by hash |

### Currency

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/currency/{address}/balance` | Token balance for address |
| `GET` | `/currency/total-supply` | Total token supply |
| `GET` | `/currency/circulated-supply` | Circulated supply |
| `GET` | `/currency/wallet-count` | Number of wallets |

### Data Application State

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/data/state/calculated` | Current calculated state |

### Consensus

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/consensus/latest/peers` | Peers in latest consensus |

---

## CL1 — Currency L1 (Port 9300)

### Transactions

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/transactions` | Submit currency transaction |
| `GET` | `/transactions/{hash}` | Transaction by hash |
| `GET` | `/transactions/last-reference/{address}` | Last reference for address |

### Peers

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/l0/peers` | Connected L0 peers |

---

## DL1 — Data L1 (Port 9400)

### Data Submission

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/data` | Submit data transaction |
| `GET` | `/data` | Current pending data |

### Peers

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/l0/peers` | Connected L0 peers |

---

## OttoChain Custom Endpoints

These endpoints are specific to OttoChain and extend the base tessellation APIs.

### ML0 Custom Routes (Port 9200)

All custom routes are prefixed with `/v1/`.

#### State Machines (Fibers)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/state-machines` | List all state machines |
| `GET` | `/v1/state-machines?status={status}` | Filter by status (ACTIVE, COMPLETED, etc.) |
| `GET` | `/v1/state-machines/{fiberId}` | Get specific state machine by UUID |
| `GET` | `/v1/state-machines/{fiberId}/events` | Event receipts for a fiber |

#### Scripts

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/scripts` | List all scripts |
| `GET` | `/v1/scripts?status={status}` | Filter by status |
| `GET` | `/v1/scripts/{scriptId}` | Get specific script by UUID |
| `GET` | `/v1/scripts/{scriptId}/invocations` | Invocation history for script |

#### State

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/onchain` | Current on-chain state |
| `GET` | `/v1/checkpoint` | Current checkpoint (calculated state) |

#### Utilities

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/util/hash` | Compute hash for signed message |

**Request body:**
```json
{
  "value": { ... },  // OttochainMessage
  "proofs": [ ... ]  // Signatures
}
```

**Response:**
```json
{
  "protocol message hash": "abc123...",
  "protocol message": { ... }
}
```

#### Webhooks

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/webhooks/subscribe` | Register webhook subscriber |
| `DELETE` | `/v1/webhooks/subscribe/{id}` | Unregister subscriber |
| `GET` | `/v1/webhooks/subscribers` | List all subscribers |

**Subscribe request:**
```json
{
  "callbackUrl": "https://your-server.com/webhook",
  "secret": "optional-hmac-secret"
}
```

**Subscribe response:**
```json
{
  "id": "subscriber-uuid",
  "callbackUrl": "https://...",
  "createdAt": "2026-02-06T12:00:00Z"
}
```

### DL1 Custom Routes (Port 9400)

All custom routes are prefixed with `/v1/`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/onchain` | Current on-chain state (from L1 perspective) |
| `POST` | `/v1/util/hash` | Compute hash for signed message |

---

## FiberStatus Values

Used in `?status=` query parameter:

| Status | Description |
|--------|-------------|
| `ACTIVE` | State machine is running |
| `COMPLETED` | Reached final state |
| `SUSPENDED` | Paused/waiting |
| `FAILED` | Error state |

---

## Common Response Patterns

### Success
```json
{
  "data": { ... }
}
```

### Not Found
```
Not found
```
(Plain text, HTTP 404)

### Validation Error
```json
{
  "code": 3,
  "message": "Invalid request body",
  "retriable": false,
  "details": { "reason": "..." }
}
```

---

## Example Queries

### Check ML0 health
```bash
curl http://localhost:9200/node/info | jq '.state'
# "Ready"
```

### Get token balance
```bash
curl http://localhost:9200/currency/DAG5VpYPJCqdv4K3VnpNrpTABvC8RjqrfZN8rUvE/balance
# {"data":{"balance":100000000000000}}
```

### List active state machines
```bash
curl http://localhost:9200/v1/state-machines?status=ACTIVE | jq
```

### Submit data transaction to DL1
```bash
curl -X POST http://localhost:9400/data \
  -H "Content-Type: application/json" \
  -d '{"value":{"CreateStateMachine":{...}},"proofs":[...]}'
```

### Get total supply with ordinal
```bash
curl http://localhost:9200/currency/total-supply
# {"ordinal":2600,"total":220000000000000}
```
