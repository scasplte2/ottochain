# OttoChain Indexer & Explorer Architecture

**Date**: 2026-02-03
**Status**: Design Proposal

## Overview

Real-time explorer powered by a PostgreSQL indexer that ingests data from two sources:
1. **ML0 node callbacks** (`onSnapshotConsensusResult`) — early notification of pending state
2. **Hypergraph snapshot stream** — source of truth for confirmed state

This dual-source approach enables:
- Real-time UI updates before hypergraph confirmation
- Fork detection when ML0 and hypergraph diverge
- Optimistic display with confirmation status

---

## Data Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           OttoChain Metagraph                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────┐   ┌─────────┐   ┌─────────┐                                   │
│  │  ML0-0  │   │  ML0-1  │   │  ML0-2  │   (Metagraph L0 nodes)           │
│  └────┬────┘   └────┬────┘   └────┬────┘                                   │
│       │             │             │                                         │
│       └─────────────┼─────────────┘                                         │
│                     │ onSnapshotConsensusResult                             │
│                     ▼                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                      │
                      │ HTTP POST (push)
                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Indexer Service                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐      │
│  │  ML0 Webhook     │    │  Hypergraph      │    │  Reconciliation  │      │
│  │  Receiver        │    │  Poller          │    │  Engine          │      │
│  └────────┬─────────┘    └────────┬─────────┘    └────────┬─────────┘      │
│           │                       │                       │                 │
│           ▼                       ▼                       ▼                 │
│  ┌──────────────────────────────────────────────────────────────────┐      │
│  │                      Event Processor                              │      │
│  │  - Parse snapshots                                                │      │
│  │  - Extract fiber updates                                          │      │
│  │  - Detect forks                                                   │      │
│  │  - Update confirmation status                                     │      │
│  └────────────────────────────────────────────────────────────────────┘      │
│                     │                                                       │
│                     ▼                                                       │
│  ┌──────────────────────────────────────────────────────────────────┐      │
│  │                      PostgreSQL                                   │      │
│  └──────────────────────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────────────┘
                      │
                      │ REST API / WebSocket
                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Explorer Frontend                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│  - Real-time fiber list                                                     │
│  - State machine visualization                                              │
│  - Transaction history                                                      │
│  - Fork alerts                                                              │
│  - Node health dashboard                                                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Sources

### 1. ML0 Node Callbacks (Push)

Each ML0 node implements `onSnapshotConsensusResult` to push snapshot data:

```scala
// In ML0 module
override def onSnapshotConsensusResult(
  snapshot: Hashed[CurrencyIncrementalSnapshot],
  state: CurrencySnapshotStateProof
): F[Unit] = {
  val payload = IndexerPayload(
    nodeId = selfId,
    ordinal = snapshot.ordinal,
    hash = snapshot.hash,
    timestamp = Instant.now,
    onChainState = extractOnChainState(snapshot),
    calculatedState = state.calculatedState
  )
  indexerClient.pushSnapshot(payload)
}
```

**Benefits:**
- Sub-second latency (no polling delay)
- Multiple nodes provide redundancy
- Fork detection via hash comparison

**Payload structure:**
```json
{
  "nodeId": "DAG...",
  "ordinal": 12345,
  "hash": "abc123...",
  "timestamp": "2026-02-03T21:00:00Z",
  "metagraphId": "...",
  "onChainState": {
    "fiberCommits": {...},
    "latestLogs": {...}
  },
  "calculatedState": {
    "stateMachines": {...},
    "scripts": {...}
  }
}
```

### 2. Hypergraph Snapshot Stream (Poll)

Poll global L0 for metagraph state proofs:

```
GET /global-snapshots/latest
GET /global-snapshots/{ordinal}/metagraph/{metagraphId}
```

**Benefits:**
- Source of truth (cryptographic finality)
- Catches any ML0 misses
- Provides hypergraph ordinal mapping

**Polling interval:** 5-10 seconds (hypergraph block time)

---

## PostgreSQL Schema

### Core Tables

```sql
-- Snapshot tracking (both ML0 and hypergraph)
CREATE TABLE snapshots (
  id BIGSERIAL PRIMARY KEY,
  metagraph_ordinal BIGINT NOT NULL,
  hypergraph_ordinal BIGINT,  -- NULL until confirmed
  metagraph_hash TEXT NOT NULL,
  hypergraph_hash TEXT,       -- NULL until confirmed
  timestamp TIMESTAMPTZ NOT NULL,
  confirmation_status TEXT NOT NULL DEFAULT 'pending',  -- pending, confirmed, orphaned
  on_chain_state JSONB NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  
  UNIQUE(metagraph_ordinal, metagraph_hash)
);

CREATE INDEX idx_snapshots_ordinal ON snapshots(metagraph_ordinal DESC);
CREATE INDEX idx_snapshots_status ON snapshots(confirmation_status);
CREATE INDEX idx_snapshots_timestamp ON snapshots(timestamp DESC);

-- Node health tracking
CREATE TABLE node_reports (
  id BIGSERIAL PRIMARY KEY,
  node_id TEXT NOT NULL,
  metagraph_ordinal BIGINT NOT NULL,
  metagraph_hash TEXT NOT NULL,
  reported_at TIMESTAMPTZ NOT NULL,
  latency_ms INT,
  
  UNIQUE(node_id, metagraph_ordinal)
);

CREATE INDEX idx_node_reports_ordinal ON node_reports(metagraph_ordinal DESC);

-- Fibers (materialized from calculated state)
CREATE TABLE fibers (
  id BIGSERIAL PRIMARY KEY,
  fiber_id TEXT NOT NULL UNIQUE,
  fiber_kind TEXT NOT NULL,  -- 'state_machine' | 'script'
  created_at_ordinal BIGINT NOT NULL,
  latest_ordinal BIGINT NOT NULL,
  status TEXT NOT NULL,  -- 'active' | 'archived' | 'failed'
  owners TEXT[] NOT NULL,
  current_state JSONB NOT NULL,  -- Full fiber record
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_fibers_kind ON fibers(fiber_kind);
CREATE INDEX idx_fibers_status ON fibers(status);
CREATE INDEX idx_fibers_updated ON fibers(updated_at DESC);

-- Fiber history (log entries)
CREATE TABLE fiber_logs (
  id BIGSERIAL PRIMARY KEY,
  fiber_id TEXT NOT NULL REFERENCES fibers(fiber_id),
  ordinal BIGINT NOT NULL,
  sequence_number BIGINT NOT NULL,
  log_type TEXT NOT NULL,  -- 'event_receipt' | 'script_invocation'
  log_data JSONB NOT NULL,
  success BOOLEAN,
  gas_used BIGINT,
  timestamp TIMESTAMPTZ NOT NULL,
  
  UNIQUE(fiber_id, sequence_number)
);

CREATE INDEX idx_fiber_logs_fiber ON fiber_logs(fiber_id, ordinal DESC);
CREATE INDEX idx_fiber_logs_ordinal ON fiber_logs(ordinal DESC);

-- Transactions (DataUpdate messages)
CREATE TABLE transactions (
  id BIGSERIAL PRIMARY KEY,
  tx_hash TEXT NOT NULL UNIQUE,
  ordinal BIGINT NOT NULL,
  sender TEXT NOT NULL,
  message_type TEXT NOT NULL,  -- 'CreateStateMachine', 'TransitionStateMachine', etc.
  fiber_id TEXT,
  payload JSONB NOT NULL,
  status TEXT NOT NULL,  -- 'pending', 'confirmed', 'failed'
  error_message TEXT,
  timestamp TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_transactions_ordinal ON transactions(ordinal DESC);
CREATE INDEX idx_transactions_sender ON transactions(sender);
CREATE INDEX idx_transactions_fiber ON transactions(fiber_id);

-- Fork tracking
CREATE TABLE forks (
  id BIGSERIAL PRIMARY KEY,
  detected_at TIMESTAMPTZ NOT NULL,
  ordinal BIGINT NOT NULL,
  hash_a TEXT NOT NULL,
  hash_b TEXT NOT NULL,
  nodes_a TEXT[] NOT NULL,
  nodes_b TEXT[] NOT NULL,
  resolved_at TIMESTAMPTZ,
  winning_hash TEXT,
  
  UNIQUE(ordinal, hash_a, hash_b)
);

CREATE INDEX idx_forks_unresolved ON forks(resolved_at) WHERE resolved_at IS NULL;
```

### Views for Explorer

```sql
-- Recent activity feed
CREATE VIEW recent_activity AS
SELECT 
  'fiber_log' as type,
  fl.timestamp,
  fl.fiber_id,
  fl.log_type,
  fl.log_data,
  f.fiber_kind
FROM fiber_logs fl
JOIN fibers f ON f.fiber_id = fl.fiber_id
ORDER BY fl.timestamp DESC
LIMIT 100;

-- Fiber summary stats
CREATE VIEW fiber_stats AS
SELECT
  fiber_kind,
  status,
  COUNT(*) as count,
  MAX(updated_at) as last_update
FROM fibers
GROUP BY fiber_kind, status;

-- Node health summary
CREATE VIEW node_health AS
SELECT
  node_id,
  MAX(metagraph_ordinal) as latest_ordinal,
  MAX(reported_at) as last_seen,
  AVG(latency_ms) as avg_latency_ms,
  COUNT(*) as reports_24h
FROM node_reports
WHERE reported_at > NOW() - INTERVAL '24 hours'
GROUP BY node_id;
```

---

## Indexer Service Components

### 1. ML0 Webhook Receiver

```typescript
// POST /webhooks/ml0-snapshot
interface ML0SnapshotPayload {
  nodeId: string;
  ordinal: number;
  hash: string;
  timestamp: string;
  onChainState: OnChainState;
  calculatedState: CalculatedState;
}

async function handleML0Snapshot(payload: ML0SnapshotPayload) {
  // 1. Check for fork (different hash at same ordinal)
  const existing = await db.snapshots.findByOrdinal(payload.ordinal);
  if (existing && existing.metagraphHash !== payload.hash) {
    await detectFork(existing, payload);
  }
  
  // 2. Upsert snapshot (pending confirmation)
  await db.snapshots.upsert({
    metagraphOrdinal: payload.ordinal,
    metagraphHash: payload.hash,
    timestamp: payload.timestamp,
    confirmationStatus: 'pending',
    onChainState: payload.onChainState
  });
  
  // 3. Record node report
  await db.nodeReports.insert({
    nodeId: payload.nodeId,
    metagraphOrdinal: payload.ordinal,
    metagraphHash: payload.hash,
    reportedAt: new Date()
  });
  
  // 4. Process fiber updates
  await processFiberUpdates(payload.calculatedState);
  
  // 5. Emit WebSocket event for real-time UI
  ws.broadcast('snapshot', { ordinal: payload.ordinal, status: 'pending' });
}
```

### 2. Hypergraph Poller

```typescript
async function pollHypergraph() {
  const latest = await hypergraphClient.getLatestGlobalSnapshot();
  const metagraphProof = await hypergraphClient.getMetagraphProof(
    latest.ordinal, 
    METAGRAPH_ID
  );
  
  if (!metagraphProof) return; // No update for our metagraph
  
  // Confirm pending snapshots
  await db.snapshots.update(
    { metagraphOrdinal: metagraphProof.lastSnapshotOrdinal },
    { 
      confirmationStatus: 'confirmed',
      hypergraphOrdinal: latest.ordinal,
      hypergraphHash: latest.hash
    }
  );
  
  // Mark orphaned snapshots (forks that lost)
  await db.snapshots.update(
    { 
      metagraphOrdinal: metagraphProof.lastSnapshotOrdinal,
      metagraphHash: { not: metagraphProof.lastSnapshotHash }
    },
    { confirmationStatus: 'orphaned' }
  );
  
  // Resolve any detected forks
  await resolveForks(metagraphProof);
  
  ws.broadcast('confirmation', { 
    metagraphOrdinal: metagraphProof.lastSnapshotOrdinal,
    hypergraphOrdinal: latest.ordinal 
  });
}
```

### 3. Fork Detection

```typescript
async function detectFork(existing: Snapshot, incoming: ML0SnapshotPayload) {
  const fork = await db.forks.findOrCreate({
    ordinal: incoming.ordinal,
    hashA: existing.metagraphHash,
    hashB: incoming.hash
  });
  
  // Track which nodes reported which hash
  if (incoming.hash === fork.hashA) {
    fork.nodesA.push(incoming.nodeId);
  } else {
    fork.nodesB.push(incoming.nodeId);
  }
  
  await db.forks.save(fork);
  
  // Alert if fork persists
  ws.broadcast('fork_detected', {
    ordinal: incoming.ordinal,
    hashes: [fork.hashA, fork.hashB]
  });
  
  logger.warn('Fork detected', { ordinal: incoming.ordinal, fork });
}
```

---

## Explorer API

### REST Endpoints

```
GET  /api/v1/snapshots                    # List snapshots (paginated)
GET  /api/v1/snapshots/:ordinal           # Get snapshot by ordinal
GET  /api/v1/snapshots/latest             # Get latest confirmed snapshot

GET  /api/v1/fibers                       # List fibers (filtered by kind, status)
GET  /api/v1/fibers/:fiberId              # Get fiber details
GET  /api/v1/fibers/:fiberId/logs         # Get fiber log history
GET  /api/v1/fibers/:fiberId/state        # Get current state data

GET  /api/v1/transactions                 # List transactions
GET  /api/v1/transactions/:hash           # Get transaction by hash
GET  /api/v1/address/:address/fibers      # Fibers owned by address
GET  /api/v1/address/:address/txs         # Transactions from address

GET  /api/v1/stats                        # Global statistics
GET  /api/v1/nodes                        # Node health status
GET  /api/v1/forks                        # Active/recent forks
```

### WebSocket Events

```typescript
// Client subscribes to real-time updates
ws.subscribe('snapshots');      // New snapshot notifications
ws.subscribe('fiber:abc123');   // Updates to specific fiber
ws.subscribe('forks');          // Fork alerts
ws.subscribe('activity');       // Global activity feed

// Server pushes events
{ event: 'snapshot', data: { ordinal, status, hash } }
{ event: 'fiber_update', data: { fiberId, state, log } }
{ event: 'fork_detected', data: { ordinal, hashes } }
{ event: 'fork_resolved', data: { ordinal, winningHash } }
```

---

## ML0 Integration

Add to existing ML0 module:

```scala
// modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0.scala

class ML0[F[_]: Async](
  indexerClient: IndexerClient[F],
  // ... existing deps
) extends CurrencyL0App {

  override def onSnapshotConsensusResult(
    snapshot: Hashed[CurrencyIncrementalSnapshot],
    state: CurrencySnapshotStateProof,
    trigger: ConsensusTrigger
  ): F[Unit] = {
    val payload = IndexerPayload(
      nodeId = nodeId,
      ordinal = snapshot.ordinal.value,
      hash = snapshot.hash.value,
      timestamp = Instant.now,
      onChainState = state.onChainState,
      calculatedState = state.calculatedState
    )
    
    indexerClient.pushSnapshot(payload)
      .handleErrorWith { err =>
        Logger[F].warn(s"Failed to push to indexer: ${err.getMessage}")
      }
  }
}
```

Configuration:
```hocon
indexer {
  enabled = true
  endpoint = "https://indexer.ottochain.io/webhooks/ml0-snapshot"
  timeout = 5.seconds
  retries = 2
}
```

---

## Deployment Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Hetzner Cloud                    │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────┐  │
│  │   ML0 + DL1  │  │   ML0 + DL1  │  │  ML0+DL1 │  │
│  │   Node 1     │  │   Node 2     │  │  Node 3  │  │
│  └──────┬───────┘  └──────┬───────┘  └────┬─────┘  │
│         │                 │               │        │
│         └─────────────────┼───────────────┘        │
│                           │                        │
│                           ▼                        │
│  ┌────────────────────────────────────────────┐   │
│  │            Services Node                    │   │
│  │  ┌─────────────┐  ┌─────────────────────┐  │   │
│  │  │  Indexer    │  │     PostgreSQL      │  │   │
│  │  │  (Node.js)  │──│   (with replicas)   │  │   │
│  │  └─────────────┘  └─────────────────────┘  │   │
│  │  ┌─────────────┐  ┌─────────────────────┐  │   │
│  │  │  Explorer   │  │       Redis         │  │   │
│  │  │  (Next.js)  │  │   (pub/sub cache)   │  │   │
│  │  └─────────────┘  └─────────────────────┘  │   │
│  └────────────────────────────────────────────┘   │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Core Indexer (1 week)
- [ ] PostgreSQL schema + migrations
- [ ] ML0 webhook receiver
- [ ] Basic fiber extraction
- [ ] REST API for fibers/snapshots

### Phase 2: Hypergraph Integration (3-5 days)
- [ ] Hypergraph poller
- [ ] Confirmation status tracking
- [ ] Fork detection logic

### Phase 3: Explorer Frontend (1 week)
- [ ] Next.js app with real-time updates
- [ ] Fiber list/detail views
- [ ] Transaction explorer
- [ ] WebSocket integration

### Phase 4: ML0 Integration (2-3 days)
- [ ] Add IndexerClient to ML0
- [ ] Implement onSnapshotConsensusResult callback
- [ ] Deploy and test with live nodes

### Phase 5: Production Hardening (ongoing)
- [ ] Monitoring/alerting
- [ ] Rate limiting
- [ ] Authentication for admin endpoints
- [ ] Backup/recovery procedures

---

## Open Questions

1. **Retention policy** — How long to keep historical data? Archive to cold storage?
2. **Multi-metagraph** — Should indexer support multiple metagraphs?
3. **State data storage** — Full `stateData` in Postgres or external (S3/IPFS)?
4. **Auth for webhooks** — HMAC signatures from ML0 nodes?
5. **Public API** — Rate limits for public explorer API?

---

## Related Work

- `identity-service/` — Will use this indexer for agent identity queries
- `ottochain-bridge/` — Can submit transactions through explorer API
- Landing page — Will pull live stats from explorer API
