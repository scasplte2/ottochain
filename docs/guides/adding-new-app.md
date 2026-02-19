# Adding a New OttoChain Application Domain

> **Skill Level**: Intermediate — requires familiarity with TypeScript, JSON Logic, and the OttoChain stack.
> **Time Estimate**: 4–8 hours for a complete domain from scratch.

This guide walks through every layer of adding a new application domain (e.g., Escrow, Voting, Subscription, Reputation) to OttoChain. The **Contract** domain is used as the running example.

---

## Architecture Overview

OttoChain applications are **state machines on-chain**. Each layer of the stack has a specific role:

```
┌─────────────────────────────────────────────────────────┐
│  ottochain-sdk  (TypeScript)                            │
│  • State machine definition (JSON)                      │
│  • Proto types (for typed apps)                         │
│  • Exported via @ottochain/sdk/apps/<domain>            │
├─────────────────────────────────────────────────────────┤
│  ottochain-services/packages/bridge  (Node.js)          │
│  • HTTP API routes for domain operations                │
│  • Validates, signs, submits to DL1                     │
│  • Mounted in bridge/src/index.ts                       │
├─────────────────────────────────────────────────────────┤
│  ottochain-services/packages/traffic-generator          │
│  • Simulated traffic workflows                          │
│  • fiber-definitions.ts + workflows.ts                  │
├─────────────────────────────────────────────────────────┤
│  ottochain-services/packages/gateway  (GraphQL)         │
│  • Queries, subscriptions for explorer                  │
│  • Optional — for read-heavy apps                       │
├─────────────────────────────────────────────────────────┤
│  ottochain-explorer  (React/Next.js)                    │
│  • UI views for the domain                              │
│  • Uses gateway GraphQL or indexer REST                 │
└─────────────────────────────────────────────────────────┘
     ↕ on-chain consensus
┌─────────────────────────────────────────────────────────┐
│  ottochain  (Scala / Tessellation)                      │
│  • Fiber engine processes state machine transitions     │
│  • No domain-specific code needed here (generic)        │
└─────────────────────────────────────────────────────────┘
```

---

## Step 1: Design the State Machine

Before writing any code, design the state machine on paper:

1. **States** — What states can the fiber be in?
2. **Transitions** — What events move between states? Who can trigger them?
3. **Guards** — JSON Logic conditions that must be true for a transition
4. **Effects** — How `state` changes after each transition
5. **Initial data** — What `stateData` is created with the fiber

### Example: A simple Escrow domain

```
States: FUNDED → RELEASED | REFUNDED | DISPUTED
Initial state: FUNDED

Transitions:
  FUNDED →(release)→ RELEASED   [guard: event.agent === state.recipient]
  FUNDED →(refund)→ REFUNDED    [guard: event.agent === state.creator AND timeout expired]
  FUNDED →(dispute)→ DISPUTED   [guard: any party]
  DISPUTED →(resolve)→ RELEASED | REFUNDED  [guard: event.agent === state.arbiter]
```

---

## Step 2: Define the State Machine in the SDK

**Repo:** `ottobot-ai/ottochain-sdk`
**Path:** `src/apps/<domain>/state-machines/<name>.json`

### 2a. Create the JSON definition

```json
{
  "metadata": {
    "name": "Escrow",
    "description": "Two-party escrow with arbiter dispute resolution",
    "version": "1.0.0"
  },
  "states": {
    "FUNDED": {
      "id": { "value": "FUNDED" },
      "isFinal": false,
      "metadata": null
    },
    "RELEASED": {
      "id": { "value": "RELEASED" },
      "isFinal": true,
      "metadata": null
    },
    "REFUNDED": {
      "id": { "value": "REFUNDED" },
      "isFinal": true,
      "metadata": null
    },
    "DISPUTED": {
      "id": { "value": "DISPUTED" },
      "isFinal": false,
      "metadata": null
    }
  },
  "initialState": { "value": "FUNDED" },
  "transitions": [
    {
      "from": { "value": "FUNDED" },
      "to":   { "value": "RELEASED" },
      "eventName": "release",
      "guard": {
        "===": [{ "var": "event.agent" }, { "var": "state.recipient" }]
      },
      "effect": {
        "merge": [
          { "var": "state" },
          { "status": "RELEASED", "releasedAt": { "var": "event.timestamp" } }
        ]
      }
    },
    {
      "from": { "value": "FUNDED" },
      "to":   { "value": "DISPUTED" },
      "eventName": "dispute",
      "guard": {
        "or": [
          { "===": [{ "var": "event.agent" }, { "var": "state.creator"   }] },
          { "===": [{ "var": "event.agent" }, { "var": "state.recipient" }] }
        ]
      },
      "effect": {
        "merge": [
          { "var": "state" },
          { "status": "DISPUTED", "disputedAt": { "var": "event.timestamp" } }
        ]
      }
    }
  ]
}
```

### JSON Logic Patterns (common guards)

| Pattern | JSON Logic |
|---------|-----------|
| Only creator can trigger | `{"===": [{"var": "event.agent"}, {"var": "state.creator"}]}` |
| Any of N parties | `{"or": [{"===": [{"var": "event.agent"}, {"var": "state.party1"}]}, ...]}` |
| Value exceeds threshold | `{">": [{"var": "state.amount"}, 0]}` |
| Epoch deadline not passed | `{"<=": [{"var": "$epochProgress"}, {"var": "state.deadline"}]}` |
| Field exists in state | `{"!!": {"var": "state.arbiter"}}` |

> ⚠️ **Epoch vs timestamp**: Use `$epochProgress` (not `$timestamp`) for deadline guards — it's a monotonic counter that doesn't burst under high traffic. See `docs/guides/json-logic-primer.md`.

### 2b. Create the SDK module

```typescript
// src/apps/escrow/index.ts

import escrowDef from './state-machines/escrow.json';

export type EscrowDefinitionType = 'Standard';

export const ESCROW_DEFINITIONS: Record<EscrowDefinitionType, unknown> = {
  Standard: escrowDef,
};

export function getEscrowDefinition(type: EscrowDefinitionType = 'Standard'): unknown {
  return ESCROW_DEFINITIONS[type];
}
```

### 2c. Export from SDK root

```typescript
// src/apps/index.ts  (add to existing exports)
export * as escrow from './escrow/index.js';
```

---

## Step 3: Add Bridge Routes

**Repo:** `ottobot-ai/ottochain-services`
**Path:** `packages/bridge/src/routes/escrow.ts`

### 3a. Create the routes file

```typescript
// packages/bridge/src/routes/escrow.ts

import { Router, type Router as RouterType } from 'express';
import { z } from 'zod';
import { randomUUID } from 'crypto';
import {
  submitTransaction,
  getStateMachine,
  keyPairFromPrivateKey,
  getFiberSequenceNumber,
  waitForSequence,
  type StateMachineDefinition,
} from '../metagraph.js';
import { getEscrowDefinition } from '@ottochain/sdk/apps/escrow';

const ESCROW_DEFINITION = getEscrowDefinition() as StateMachineDefinition;

export const escrowRoutes: RouterType = Router();

// ── Request Schemas ──────────────────────────────────────────────────────────

const CreateEscrowSchema = z.object({
  privateKey:       z.string().length(64),
  recipientAddress: z.string(),
  amount:           z.number().positive(),
  description:      z.string().optional(),
});

const EscrowActionSchema = z.object({
  privateKey: z.string().length(64),
  escrowId:   z.string().uuid(),
});

// ── POST /escrow/create ──────────────────────────────────────────────────────

escrowRoutes.post('/create', async (req, res) => {
  try {
    const input   = CreateEscrowSchema.parse(req.body);
    const keyPair = keyPairFromPrivateKey(input.privateKey);
    const escrowId = randomUUID();

    const message = {
      CreateStateMachine: {
        fiberId:    escrowId,
        definition: ESCROW_DEFINITION,
        initialData: {
          schema:    'Escrow',
          creator:   keyPair.address,
          recipient: input.recipientAddress,
          amount:    input.amount,
          status:    'FUNDED',
          createdAt: new Date().toISOString(),
        },
        parentFiberId: null,
      },
    };

    const result = await submitTransaction(message, input.privateKey);

    res.status(201).json({
      escrowId,
      creator:   keyPair.address,
      recipient: input.recipientAddress,
      amount:    input.amount,
      hash:      result.hash,
    });
  } catch (err) {
    if (err instanceof z.ZodError) return res.status(400).json({ error: 'Invalid request', details: err.errors });
    console.error('[escrow/create] Error:', err);
    res.status(500).json({ error: err instanceof Error ? err.message : 'Create failed' });
  }
});

// ── POST /escrow/release ─────────────────────────────────────────────────────
// IMPORTANT: call waitForSequence after any state-changing transition to prevent
// race conditions on rapid back-to-back operations.

escrowRoutes.post('/release', async (req, res) => {
  try {
    const input = EscrowActionSchema.parse(req.body);

    const state = await getStateMachine(input.escrowId) as {
      sequenceNumber?: number;
      currentState?: { value: string };
    } | null;

    if (!state) return res.status(404).json({ error: 'Escrow not found' });
    if (state.currentState?.value !== 'FUNDED') {
      return res.status(400).json({ error: 'Escrow is not in FUNDED state', currentState: state.currentState?.value });
    }

    const keyPair = keyPairFromPrivateKey(input.privateKey);
    const targetSequenceNumber = await getFiberSequenceNumber(input.escrowId);

    const message = {
      TransitionStateMachine: {
        fiberId:              input.escrowId,
        eventName:            'release',
        payload:              { agent: keyPair.address },
        targetSequenceNumber,
      },
    };

    const result = await submitTransaction(message, input.privateKey);

    // Wait for DL1 to reflect the new sequence (prevents race on rapid calls)
    await waitForSequence(input.escrowId, targetSequenceNumber + 1, 30, 1000);

    res.json({ hash: result.hash, escrowId: input.escrowId, status: 'RELEASED' });
  } catch (err) {
    if (err instanceof z.ZodError) return res.status(400).json({ error: 'Invalid request', details: err.errors });
    console.error('[escrow/release] Error:', err);
    res.status(500).json({ error: err instanceof Error ? err.message : 'Release failed' });
  }
});

// ── GET /escrow/:escrowId ────────────────────────────────────────────────────

escrowRoutes.get('/:escrowId', async (req, res) => {
  try {
    const state = await getStateMachine(req.params.escrowId);
    if (!state) return res.status(404).json({ error: 'Escrow not found' });
    res.json(state);
  } catch (err) {
    res.status(500).json({ error: 'Query failed' });
  }
});
```

### 3b. Mount the router in bridge/src/index.ts

```typescript
// packages/bridge/src/index.ts  (add near other route mounts)
import { escrowRoutes } from './routes/escrow.js';

app.use('/escrow', escrowRoutes);
```

### 3c. Sequence Race Condition — Critical Pattern

> ⚠️ **Always call `waitForSequence` after submit for transitions that will be followed by another rapid call.**

When a client immediately calls `/escrow/release` after `/escrow/create`, the sequence at DL1 may not have updated yet, causing HTTP 400 from the validator.

```typescript
// After submitTransaction for transitions that mutate seq:
const synced = await waitForSequence(fiberId, targetSequenceNumber + 1, 30, 1000);
if (!synced) {
  console.warn(`[escrow] Sequence ${targetSequenceNumber + 1} not synced within 30s`);
}
```

**`submitTransaction` also retries on 400** (up to 3× with exponential backoff) as an additional safety net. See `packages/bridge/src/metagraph.ts`.

---

## Step 4: Add Traffic Generator Definition

**Path:** `packages/traffic-generator/src/fiber-definitions.ts`

Add a new entry to the `FIBER_DEFINITIONS` array (or export map):

```typescript
// In fiber-definitions.ts, add to the exported definitions:

const escrowDefinition: FiberDefinition = {
  type:           'Escrow',
  name:           'Escrow Agreement',
  workflowType:   'Custom',       // or add 'Escrow' to the union type
  roles:          ['creator', 'recipient'],
  isVariableParty: false,
  states:         ['FUNDED', 'RELEASED', 'REFUNDED', 'DISPUTED'],
  initialState:   'FUNDED',
  finalStates:    ['RELEASED', 'REFUNDED'],
  transitions: [
    { from: 'FUNDED',   to: 'RELEASED',  event: 'release', actor: 'recipient' },
    { from: 'FUNDED',   to: 'REFUNDED',  event: 'refund',  actor: 'creator'   },
    { from: 'FUNDED',   to: 'DISPUTED',  event: 'dispute', actor: 'creator'   },
    { from: 'DISPUTED', to: 'RELEASED',  event: 'resolve', actor: 'creator'   },
  ],
  generateStateData: (participants, context) => ({
    schema:    'Escrow',
    creator:   participants.get('creator')!,
    recipient: participants.get('recipient')!,
    amount:    Math.floor(Math.random() * 1000) + 100,
    status:    'FUNDED',
    createdAt: new Date().toISOString(),
  }),
};
```

### Adding workflows

```typescript
// packages/traffic-generator/src/workflows.ts

// Add to the createFiber function or workflow registry:
case 'Escrow': {
  const response = await post('/escrow/create', {
    privateKey:       actor.privateKey,
    recipientAddress: participants.get('recipient')!,
    amount:           stateData.amount,
  });
  return response.escrowId;
}

// Add to the executeTransition function:
case 'release': {
  await post('/escrow/release', { privateKey: actor.privateKey, escrowId: fiberId });
  break;
}
```

---

## Step 5: Add Integration Tests

**Path:** `packages/bridge/test/lifecycle.test.ts` (or a new `test/escrow.test.ts`)

```typescript
import { describe, it, before } from 'node:test';
import assert from 'node:assert';

const BRIDGE_URL = process.env.BRIDGE_URL || 'http://localhost:3030';
const ML0_URL    = process.env.ML0_URL    || 'http://localhost:9200';

describe('Escrow Lifecycle: create → release', () => {
  let creatorWallet:    Wallet;
  let recipientWallet:  Wallet;
  let escrowId:         string;

  before(async () => {
    creatorWallet   = await makeWallet();
    recipientWallet = await makeWallet();
  });

  it('should create an escrow', async () => {
    const result = await post('/escrow/create', {
      privateKey:       creatorWallet.privateKey,
      recipientAddress: recipientWallet.address,
      amount:           500,
      description:      'Test escrow',
    });
    assert.ok(result.escrowId);
    escrowId = result.escrowId;
  });

  it('should appear on ML0 in FUNDED state', async () => {
    const fiber = await waitForFiber(escrowId);
    assert.strictEqual(fiber!.currentState.value, 'FUNDED');
  });

  it('should release funds to recipient', async () => {
    await settle(3000); // Let DL1 sync
    const result = await post('/escrow/release', {
      privateKey: recipientWallet.privateKey,
      escrowId,
    });
    assert.ok(result.hash);
  });

  it('should transition to RELEASED on ML0', async () => {
    const fiber = await waitForState(escrowId, 'RELEASED');
    assert.ok(fiber, 'Did not reach RELEASED state');
  });
});
```

---

## Step 6: Explorer UI (ottochain-explorer)

**Path:** `components/domains/Escrow.tsx` (or equivalent)

The explorer reads from either:
- The **indexer REST API** (direct Postgres queries): `GET /api/fibers?schema=Escrow`
- The **gateway GraphQL**: custom queries added to gateway schema

### Adding to Gateway GraphQL schema

```graphql
# packages/gateway/src/schema.graphql

type EscrowFiber {
  fiberId:   String!
  creator:   String!
  recipient: String!
  amount:    Float!
  status:    String!
  createdAt: String!
}

extend type Query {
  escrows(status: String, creator: String, limit: Int, offset: Int): [EscrowFiber!]!
  escrow(fiberId: String!): EscrowFiber
}
```

### Using the generic fiber indexer (simpler)

The indexer already stores all state machines with their `schema` field. No gateway changes needed for basic listing:

```typescript
// Fetch from indexer
const escrows = await fetch(`${INDEXER_URL}/state-machines?schema=Escrow&status=FUNDED`);
const data    = await escrows.json();
// Returns: { fibers: [...], total, hasMore }
```

---

## Checklist

```
SDK (ottochain-sdk):
  [ ] src/apps/<domain>/state-machines/<name>.json  — state machine definition
  [ ] src/apps/<domain>/index.ts                    — module exports
  [ ] src/apps/index.ts                             — registered in apps index
  [ ] dist rebuilt (pnpm build)
  [ ] PR to ottobot-ai/ottochain-sdk targeting main

Bridge (ottochain-services):
  [ ] packages/bridge/src/routes/<domain>.ts        — HTTP API routes
  [ ] packages/bridge/src/index.ts                  — router mounted
  [ ] waitForSequence used after state-changing ops
  [ ] submitTransaction retry enabled (default: 3 retries, 5s backoff)
  [ ] PR to ottobot-ai/ottochain-services targeting main

Traffic Generator:
  [ ] FiberDefinition added to fiber-definitions.ts
  [ ] Workflow cases added to workflows.ts
  [ ] Weight configured in traffic-generator config

Tests:
  [ ] Lifecycle test: happy path (all transitions to final state)
  [ ] Lifecycle test: rejection paths (guard violations → 4xx)
  [ ] Lifecycle test: pagination/query (if applicable)

Explorer (optional):
  [ ] GraphQL types added (if domain needs custom queries)
  [ ] UI component created
```

---

## Common Mistakes

### 1. Forgetting `waitForSequence`
After any `submitTransaction` for a transition, if another transition immediately follows, DL1's onchain state may lag behind. Always call:
```typescript
await waitForSequence(fiberId, targetSequenceNumber + 1, 30, 1000);
```

### 2. Using `$timestamp` instead of `$epochProgress` in guards
`$timestamp` is the wall clock and can behave unexpectedly under high load. Use `$epochProgress` for deadline enforcement.

### 3. Non-deterministic effects
State machine `effect` must produce the same output for the same input. Avoid `Date.now()` in effects — use `event.timestamp` which is injected by the fiber engine.

### 4. Mutable `state` in effect
The `effect` field is a pure transformation. Always start with `{"merge": [{"var": "state"}, ...new_fields]}` to preserve existing state data:
```json
"effect": {
  "merge": [
    { "var": "state" },
    { "status": "RELEASED", "releasedAt": { "var": "event.timestamp" } }
  ]
}
```

### 5. Missing `schema` field in initialData
The indexer uses `schema` to categorize fibers. Always include it:
```typescript
initialData: {
  schema: 'Escrow',   // ← required for indexer filtering
  ...
}
```

### 6. SDK version drift
Bridge and other services pin `github:ottobot-ai/ottochain-sdk#main`. After adding domain code to the SDK, update services with:
```bash
pnpm update @ottochain/sdk --force
```

---

## Reference: Existing Domain Implementations

| Domain   | SDK Path                       | Bridge Route              | States                          |
|----------|-------------------------------|--------------------------|----------------------------------|
| Contract | `src/apps/contracts/`          | `routes/contract.ts`      | PROPOSED → ACTIVE → COMPLETED    |
| Market   | `src/apps/markets/`            | `routes/market.ts`        | PROPOSED → OPEN → SETTLED        |
| Oracle   | `src/apps/oracles/`            | `routes/oracle.ts`        | REGISTERED → ACTIVE → FINALIZED  |
| Agent    | `src/apps/identity/`           | `routes/agent.ts`         | REGISTERED → ACTIVE              |
| DAO      | `src/apps/governance/`         | `routes/governance.ts`    | PROPOSED → ACTIVE → DISSOLVED    |
| Corporate| `src/apps/corporate/`          | `routes/corporate.ts`     | FORMATION → ACTIVE → DISSOLVED   |

---

## Scala Unit Tests (ottochain)

For testing state machine logic **before** SDK integration, use the Scala test suites in ottochain. This is useful for validating guards, effects, and edge cases at the fiber engine level.

**Repo:** `scasplte2/ottochain` (upstream) or `ottobot-ai/ottochain` (fork)
**Path:** `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/`

### Existing example suites

| Suite | Description |
|-------|-------------|
| `TokenEscrowSuite.scala` | Two-party escrow with timeout |
| `TimeLockedEscrowSuite.scala` | Escrow with epoch-based deadlines |
| `AgentIdentityLifecycleSuite.scala` | Agent registration, reputation, suspension |
| `PredictionMarketSuite.scala` | Market creation, betting, settlement |
| `VotingSuite.scala` | Proposal voting with quorum |
| `TicTacToeGameSuite.scala` | Turn-based game state machine |
| `ContractLifecycleSuite.scala` | Multi-party contract lifecycle |
| `NftMarketplaceSuite.scala` | NFT listing and purchase |

### Running tests

```bash
cd ~/repos/ottochain

# Source SDKMAN (required for sbt/java)
source ~/.sdkman/bin/sdkman-init.sh

# Run all shared-data tests
sbt 'sharedData/test'

# Run a specific suite
sbt 'sharedData/testOnly *EscrowSuite*'

# Run with verbose output
sbt 'sharedData/testOnly *EscrowSuite* -- --verbose'
```

### Test framework: Weaver-cats

OttoChain uses [Weaver](https://disneystreaming.github.io/weaver-test/) (not ScalaTest). Test suites extend `SimpleIOSuite`:

```scala
import weaver.SimpleIOSuite
import cats.effect.IO

object MyDomainSuite extends SimpleIOSuite {
  
  test("should transition from FUNDED to RELEASED") {
    for {
      fiber   <- createFiber(initialState = "FUNDED", data = escrowData)
      result  <- transitionFiber(fiber.id, "release", payload)
    } yield expect(result.currentState.value == "RELEASED")
  }
}
```

### Test helpers location

- **Fixtures:** `modules/shared-test/src/main/scala/xyz/kd5ujc/shared_test/`
- **Extractors:** `FiberExtractors.scala` — helpers for asserting fiber state
- **Generators:** Property-based test data generators

---

## E2E / Integration Test Infrastructure

Beyond unit tests, OttoChain has full E2E testing that spins up the metagraph locally.

### Local stack (ottochain-services)

```bash
cd ~/repos/ottochain-services

# Start metagraph + all services (bridge, indexer, gateway)
./scripts/start-local-stack.sh

# Stop everything
./scripts/stop-local-stack.sh
```

This starts:
- 3-node metagraph (L0, CL1, DL1)
- Bridge API (port 3030)
- Indexer (port 3031)
- Gateway GraphQL (port 4000)
- PostgreSQL (port 5432)

### Running E2E tests

```bash
# Full integration test suite
./scripts/integration-test.sh

# Or run specific test files
cd packages/bridge
pnpm test -- test/e2e.test.ts
pnpm test -- test/sm.test.ts
```

### Test files

| File | Purpose | Lines |
|------|---------|------:|
| `packages/bridge/test/e2e.test.ts` | Full lifecycle E2E tests | ~18K |
| `packages/bridge/test/sm.test.ts` | State machine unit tests | ~31K |
| `scripts/integration-test.sh` | CI integration runner | ~12K |

### Smoke tests (deployed environments)

For testing against scratch/staging:

```bash
# Set environment
export BRIDGE_URL=https://bridge.scratch.ottochain.dev
export ML0_URL=https://ml0-0.scratch.ottochain.dev:9200

# Run subset of tests
pnpm test -- test/e2e.test.ts --grep "create contract"
```

---

## Fiber Engine Internals

When debugging guard failures or unexpected transitions, you may need to understand the Scala fiber engine.

### Key source locations

| Component | Path |
|-----------|------|
| State machine types | `modules/models/src/main/scala/xyz/kd5ujc/schema/fiber/` |
| Fiber processing | `modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/fiber/` |
| JSON Logic evaluation | Uses `io.constellationnetwork.metagraph_sdk.json_logic` |
| Update types | `modules/models/src/main/scala/xyz/kd5ujc/schema/Updates.scala` |

### Key types

```scala
// StateMachineDefinition — the schema
case class StateMachineDefinition(
  metadata:     Option[StateMachineMetadata],
  states:       Map[String, StateDefinition],
  initialState: StateId,
  transitions:  List[TransitionDefinition]
)

// TransitionDefinition — a single allowed transition  
case class TransitionDefinition(
  from:      StateId,
  to:        StateId,
  eventName: String,
  guard:     Option[Json],  // JSON Logic expression
  effect:    Option[Json]   // JSON Logic transformation
)

// FiberState — runtime state of a fiber
case class FiberState(
  fiberId:        String,
  currentState:   StateId,
  stateData:      Json,
  sequenceNumber: Long,
  definition:     StateMachineDefinition
)
```

### Context variables available in guards/effects

| Variable | Type | Description |
|----------|------|-------------|
| `state` | Object | Current `stateData` of the fiber |
| `event` | Object | Transition payload (includes `agent`, custom fields) |
| `$epochProgress` | Number | Monotonic epoch counter (use for deadlines) |
| `$timestamp` | String | Wall clock ISO timestamp (avoid for guards) |
| `$ordinal` | Number | Global snapshot ordinal |
| `$parentState` | Object | Parent fiber's state (if child fiber) |

---

## Debugging & Troubleshooting

### Guard failure diagnosis

When a transition fails, the rejection reason appears in:

1. **Bridge response** — HTTP 400 with error details
2. **ML0 rejected updates** — `GET /snapshots/latest` includes rejected transactions
3. **Webhook events** — If configured, `dispatchRejection()` sends failure details
4. **FiberLogEntry** — On-chain log of all transitions (successful and failed)

### Common error messages

| Error | Cause | Fix |
|-------|-------|-----|
| `Guard evaluation failed` | JSON Logic guard returned false | Check guard conditions, verify `event.agent` matches expected |
| `Invalid sequence number` | Stale `targetSequenceNumber` | Use `getFiberSequenceNumber()` immediately before submit |
| `Transition not found` | No matching `from → eventName` | Verify current state, check `eventName` spelling |
| `Fiber not found` | Invalid `fiberId` or not yet indexed | Wait for indexer sync, verify UUID format |

### Viewing fiber state

```bash
# Via bridge
curl http://localhost:3030/fiber/<fiberId>

# Via ML0 directly  
curl http://localhost:9200/state-machines/<fiberId>

# Via indexer (includes history)
curl http://localhost:3031/state-machines/<fiberId>?includeHistory=true
```

### Enabling verbose logging

```bash
# Bridge (Node.js)
DEBUG=ottochain:* pnpm start

# Metagraph (Scala) — set in docker-compose or entrypoint
export JAVA_OPTS="-Dlogback.configurationFile=logback-debug.xml"
```

---

## Proto Definitions (Typed Domains)

For domains that need **strongly typed** messages beyond JSON, add protobuf definitions to the SDK.

**Path:** `ottochain-sdk/src/proto/<domain>.proto`

### When to use proto

- High-frequency domains where type safety prevents bugs
- Domains with complex nested structures
- When you need generated TypeScript types for the explorer/bridge

### Example proto

```protobuf
// src/proto/escrow.proto
syntax = "proto3";
package ottochain.escrow;

message EscrowState {
  string escrow_id = 1;
  string creator = 2;
  string recipient = 3;
  uint64 amount = 4;
  string status = 5;
  string created_at = 6;
}

message ReleasePayload {
  string escrow_id = 1;
  string agent = 2;
}
```

### Generating types

```bash
cd ottochain-sdk
pnpm proto:generate   # Runs buf + ts-proto
pnpm build            # Rebuilds dist/
```

Generated types appear in `src/generated/` and are exported from the SDK.

---

## Further Reading

- `docs/guides/json-logic-primer.md` — JSON Logic operators and patterns
- `docs/guides/state-machine-design.md` — State machine design principles
- `docs/fiber-engine/README.md` — How the Scala fiber engine processes transitions
- `packages/bridge/src/metagraph.ts` — submitTransaction, waitForSequence, retry logic
- `packages/bridge/test/e2e.test.ts` — Complete E2E test examples
- `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/` — Scala unit test examples
