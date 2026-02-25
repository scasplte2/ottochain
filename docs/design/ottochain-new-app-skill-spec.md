# Spec: `ottochain-new-app` OpenClaw Agent Skill

**Status:** 📝 Specification  
**Author:** @think (OttoThink)  
**Date:** 2026-02-25  
**Trello Card:** [skill] Create 'ottochain-new-app' skill (6987a9d11f69072dafc1b699)  
**Upstream Guide:** `docs/guides/adding-new-app.md` (PR #86)

---

## 1. Overview

Package the `docs/guides/adding-new-app.md` guide + `memory/ottochain-new-app-pipeline.md` into a reusable OpenClaw AgentSkill (`ottochain-new-app`) so that any agent can scaffold a new OttoChain application domain with a single natural-language trigger.

The skill walks the agent step-by-step through all five layers of the stack (SDK → Bridge → Traffic Generator → Explorer → Deployment), with copy-paste-ready templates verified against current code patterns.

---

## 2. Problem Statement

Adding a new app domain to OttoChain requires touching 5 repositories and remembering dozens of conventions (proto wire format, Zod schemas, `waitForSequence`, `$epochProgress` vs `$timestamp`, etc.). Without a skill, agents must re-derive all of these from scratch each time, risking drift from the current patterns.

This skill encodes the current canonical patterns once and keeps them in sync with the codebase.

---

## 3. User Stories

| ID | As a... | I want to... | So that... |
|----|---------|-------------|-----------|
| US-1 | Agent (any) | Say "add a new OttoChain app" | The skill provides step-by-step templates without me searching docs |
| US-2 | James (human) | Say "scaffold an Escrow domain" | The skill generates all required files in one session |
| US-3 | Agent | Reference specific templates | I can copy the SM JSON, bridge route, or traffic-gen definition individually |
| US-4 | Agent | Follow the deployment checklist | I don't accidentally skip a required step (e.g., `waitForSequence`) |

---

## 4. Acceptance Criteria

| # | Criterion |
|---|-----------|
| AC-1 | Skill triggers on: "add a new OttoChain app", "scaffold [domain]", "create a new domain", "new app domain", "ottochain-new-app" |
| AC-2 | State machine JSON template uses the **proto wire format** (`{ "id": { "value": "..." } }` for state IDs, `{ "value": "..." }` for initialState) — verified against `ottochain-sdk/src/apps/` |
| AC-3 | Bridge route template uses **Zod** for input validation and `submitTransaction()` from `metagraph.js` — verified against `ottochain-services/packages/bridge/src/routes/contract.ts` |
| AC-4 | Traffic gen template uses the **`FiberDefinition`** type with `generateStateData`, `workflowType`, `finalStates` — verified against `ottochain-services/packages/traffic-generator/src/fiber-definitions.ts` |
| AC-5 | Explorer component stub correctly imports from indexer REST API (`GET /api/fibers?schema=<domain>`) — not a nonexistent gateway pattern |
| AC-6 | Deployment checklist is the canonical **6-step checklist** from `docs/guides/adding-new-app.md` |
| AC-7 | All code patterns are verified against the actual repos at `/home/euler/repos/` (no hallucinated APIs) |
| AC-8 | Skill is **self-contained**: no external MCP tools or network calls required — works offline from local files |

---

## 5. Skill File Structure

```
/home/euler/.openclaw/skills/ottochain-new-app/
├── SKILL.md                          ← Main skill file (loaded by OpenClaw)
└── templates/
    ├── state-machine.json            ← SM definition template (proto wire format)
    ├── bridge-route.ts               ← Express route with Zod + submitTransaction
    ├── fiber-definition.ts           ← Traffic gen FiberDefinition template
    ├── explorer-component.tsx        ← React explorer stub (indexer REST)
    └── checklist.md                  ← 6-step deployment checklist
```

---

## 6. SKILL.md Specification

### 6.1 Front Matter

```yaml
---
name: ottochain-new-app
description: Scaffold a new OttoChain application domain. State machine definition, bridge routes, traffic generator, explorer UI, deployment checklist. Use when adding a new domain type to the OttoChain ecosystem.
metadata: { "openclaw": { "emoji": "🦦", "requires": { "bins": ["git", "npm", "curl"] }, "scope": "internal" } }
---
```

### 6.2 Trigger Phrases (AC-1)

The following phrases MUST trigger this skill:

- "add a new OttoChain app"
- "add a new OttoChain domain"
- "scaffold [domain name]"
- "create a new domain"
- "new app domain"
- "ottochain-new-app"
- "add [domain] to OttoChain"

### 6.3 Step-by-Step Procedure

The SKILL.md MUST walk the agent through these steps in order:

#### Step 0: Identify Domain Name and Gather Requirements
- Ask user (or derive from context): domain name (e.g., `Escrow`, `Subscription`, `Voting`)
- List the states, transitions, and roles
- Reference: `docs/guides/adding-new-app.md` §1 "Design the State Machine"

#### Step 1: Create SDK State Machine (AC-2)

**Location:** `~/repos/ottochain-sdk/src/apps/<domain>/state-machines/<name>.json`

Load template from `templates/state-machine.json`. Fill in:
- `metadata.name` = domain name (PascalCase)
- `states` = dictionary of state objects in **proto wire format** (see §7.1)
- `initialState` = `{ "value": "INITIAL_STATE" }`
- `transitions` = array with `from`, `to`, `eventName`, `guard`, `effect`

Key guard/effect patterns to document:
- Use `$epochProgress` NOT `$timestamp` for deadline guards
- Use `{ "merge": [{ "var": "state" }, {...new_fields}] }` in effects
- Include `schema: '<Domain>'` in `initialData`

**Create SDK module:**
- `src/apps/<domain>/index.ts` — export `get<Domain>Definition()`
- `src/apps/index.ts` — add `export * as <domain> from './<domain>/index.js'`
- Run `pnpm build` to rebuild `dist/`

**PR target:** `ottobot-ai/ottochain-sdk` → `main`

#### Step 2: Add Bridge Routes (AC-3)

**Location:** `~/repos/ottochain-services/packages/bridge/src/routes/<domain>.ts`

Load template from `templates/bridge-route.ts`. Fill in:
- Replace `<Domain>` / `<domain>` placeholders
- Define Zod schemas for each operation
- Implement `POST /<domain>/create` (uses `submitTransaction` from `../metagraph.js`)
- Implement `POST /<domain>/<eventName>` for each state transition
- Implement `GET /<domain>/:id` for state query

**Mount:** Add `import { <domain>Routes } from './routes/<domain>.js'` + `app.use('/<domain>', <domain>Routes)` in `packages/bridge/src/index.ts`

**PR target:** `ottobot-ai/ottochain-services` → `main`

#### Step 3: Add Traffic Generator Definition (AC-4)

**Location:** `~/repos/ottochain-services/packages/traffic-generator/src/fiber-definitions.ts`

Load template from `templates/fiber-definition.ts`. Fill in:
- `type` = domain name string
- `workflowType` = `'Custom'` (or extend the union type if needed)
- `roles` = array of participant role names
- `states`, `initialState`, `finalStates`
- `transitions` = array of `{ from, to, event, actor }`
- `generateStateData(participants, context)` = function returning initial stateData

Also add workflow execution cases to `workflows.ts` for create + each transition.

**PR target:** `ottobot-ai/ottochain-services` → `main`

#### Step 4: Add Explorer UI (AC-5)

**Location:** `~/repos/ottochain-explorer/src/`

Load template from `templates/explorer-component.tsx`. Fill in:
- Component name and domain query schema (`schema=<Domain>`)
- Uses indexer REST API: `GET /api/fibers?schema=<Domain>&limit=50`
- No gateway GraphQL changes required for basic listing

For custom views (charts, order books, etc.) — add GraphQL types in `packages/gateway/src/schema.graphql`.

**PR target:** `ottobot-ai/ottochain-explorer` → `main`

#### Step 5: Deploy (AC-6)

Load checklist from `templates/checklist.md`.

Deployment order:
1. If Scala changes → rebuild metagraph JARs, deploy nodes
2. SDK — pnpm build, tag release
3. Services (bridge, indexer) — merge PR, tag release, `docker compose pull && docker compose up -d`
4. Explorer — merge PR, tag release, rebuild
5. Traffic generator config — update weights, restart

---

## 7. Template Specifications

### 7.1 `templates/state-machine.json` (AC-2)

Must use **proto wire format** — verified against `~/repos/ottochain-sdk/src/apps/contracts/state-machines/*.json`:

```json
{
  "metadata": {
    "name": "<Domain>",
    "description": "<One-line description>",
    "version": "1.0.0"
  },
  "states": {
    "INITIAL": {
      "id": { "value": "INITIAL" },
      "isFinal": false,
      "metadata": null
    },
    "ACTIVE": {
      "id": { "value": "ACTIVE" },
      "isFinal": false,
      "metadata": null
    },
    "COMPLETED": {
      "id": { "value": "COMPLETED" },
      "isFinal": true,
      "metadata": null
    },
    "CANCELLED": {
      "id": { "value": "CANCELLED" },
      "isFinal": true,
      "metadata": null
    }
  },
  "initialState": { "value": "INITIAL" },
  "transitions": [
    {
      "from": { "value": "INITIAL" },
      "to": { "value": "ACTIVE" },
      "eventName": "activate",
      "guard": {
        "===": [{ "var": "event.agent" }, { "var": "state.creator" }]
      },
      "effect": {
        "merge": [
          { "var": "state" },
          {
            "status": "ACTIVE",
            "activatedAt": { "var": "event.timestamp" }
          }
        ]
      }
    },
    {
      "from": { "value": "ACTIVE" },
      "to": { "value": "COMPLETED" },
      "eventName": "complete",
      "guard": {
        "===": [{ "var": "event.agent" }, { "var": "state.creator" }]
      },
      "effect": {
        "merge": [
          { "var": "state" },
          {
            "status": "COMPLETED",
            "completedAt": { "var": "event.timestamp" }
          }
        ]
      }
    },
    {
      "from": { "value": "ACTIVE" },
      "to": { "value": "CANCELLED" },
      "eventName": "cancel",
      "guard": {
        "===": [{ "var": "event.agent" }, { "var": "state.creator" }]
      },
      "effect": {
        "merge": [
          { "var": "state" },
          {
            "status": "CANCELLED",
            "cancelledAt": { "var": "event.timestamp" }
          }
        ]
      }
    }
  ]
}
```

**Key wire format rules:**
- State IDs: `{ "id": { "value": "STATE_NAME" }, "isFinal": bool, "metadata": null }`
- `initialState`: `{ "value": "STATE_NAME" }` (wrapped Value, NOT plain string)
- `from`/`to` in transitions: `{ "value": "STATE_NAME" }` (same wrapped format)
- Guards/effects: JSON Logic expressions
- `$epochProgress` for deadlines, NOT `$timestamp`

**Common guard patterns:**

| Pattern | JSON Logic |
|---------|-----------|
| Creator check | `{"===": [{"var": "event.agent"}, {"var": "state.creator"}]}` |
| Either of two parties | `{"or": [{"===": [{"var": "event.agent"}, {"var": "state.party1"}]}, {"===": [{"var": "event.agent"}, {"var": "state.party2"}]}]}` |
| Epoch deadline | `{"<=": [{"var": "$epochProgress"}, {"var": "state.deadline"}]}` |
| State field exists | `{"!!": {"var": "state.arbiter"}}` |
| Amount > 0 | `{">": [{"var": "state.amount"}, 0]}` |

### 7.2 `templates/bridge-route.ts` (AC-3)

Must use `submitTransaction` + `waitForSequence` from `metagraph.js`, Zod validation — verified against `~/repos/ottochain-services/packages/bridge/src/routes/contract.ts`:

```typescript
// packages/bridge/src/routes/<domain>.ts
// Replace all occurrences of <domain>, <Domain> with actual names.

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
import { get<Domain>Definition } from '@ottochain/sdk/apps/<domain>';

const DEFINITION = get<Domain>Definition() as StateMachineDefinition;

export const <domain>Routes: RouterType = Router();

// ── Schemas ──────────────────────────────────────────────────────────────────

const Create<Domain>Schema = z.object({
  privateKey:  z.string().length(64),
  // add domain-specific fields:
  // description: z.string().optional(),
});

const Transition<Domain>Schema = z.object({
  privateKey: z.string().length(64),
  fiberId:    z.string().uuid(),
  // add event-specific payload fields
});

// ── POST /<domain>/create ────────────────────────────────────────────────────

<domain>Routes.post('/create', async (req, res) => {
  try {
    const input   = Create<Domain>Schema.parse(req.body);
    const keyPair = keyPairFromPrivateKey(input.privateKey);
    const fiberId = randomUUID();

    const message = {
      CreateStateMachine: {
        fiberId,
        definition:  DEFINITION,
        initialData: {
          schema:    '<Domain>',           // ← required for indexer filtering
          creator:   keyPair.address,
          status:    'INITIAL',
          createdAt: new Date().toISOString(),
          // add domain-specific fields
        },
        parentFiberId: null,
      },
    };

    const result = await submitTransaction(message, input.privateKey);

    res.status(201).json({
      fiberId,
      creator: keyPair.address,
      hash:    result.hash,
    });
  } catch (err) {
    if (err instanceof z.ZodError) {
      return res.status(400).json({ error: 'Invalid request', details: err.errors });
    }
    console.error('[<domain>/create] Error:', err);
    res.status(500).json({ error: err instanceof Error ? err.message : 'Create failed' });
  }
});

// ── POST /<domain>/activate (or any other event name) ────────────────────────
// Repeat this pattern for each event/transition in the state machine.

<domain>Routes.post('/activate', async (req, res) => {
  try {
    const input = Transition<Domain>Schema.parse(req.body);
    const keyPair = keyPairFromPrivateKey(input.privateKey);

    // 1. Verify current state
    const fiber = await getStateMachine(input.fiberId) as {
      sequenceNumber?: number;
      currentState?: { value: string };
    } | null;
    if (!fiber) return res.status(404).json({ error: '<Domain> not found' });
    if (fiber.currentState?.value !== 'INITIAL') {
      return res.status(400).json({
        error: '<Domain> is not in INITIAL state',
        currentState: fiber.currentState?.value,
      });
    }

    // 2. Get sequence number immediately before submit (prevents stale seq race)
    const targetSequenceNumber = await getFiberSequenceNumber(input.fiberId);

    const message = {
      TransitionStateMachine: {
        fiberId:              input.fiberId,
        eventName:            'activate',
        payload:              { agent: keyPair.address },
        targetSequenceNumber,
      },
    };

    const result = await submitTransaction(message, input.privateKey);

    // 3. Wait for DL1 to reflect new sequence (prevents race on rapid back-to-back calls)
    await waitForSequence(input.fiberId, targetSequenceNumber + 1, 30, 1000);

    res.json({
      hash:    result.hash,
      fiberId: input.fiberId,
      status:  'ACTIVE',
    });
  } catch (err) {
    if (err instanceof z.ZodError) {
      return res.status(400).json({ error: 'Invalid request', details: err.errors });
    }
    console.error('[<domain>/activate] Error:', err);
    res.status(500).json({ error: err instanceof Error ? err.message : 'Activate failed' });
  }
});

// ── GET /<domain>/:fiberId ────────────────────────────────────────────────────

<domain>Routes.get('/:fiberId', async (req, res) => {
  try {
    const fiber = await getStateMachine(req.params.fiberId);
    if (!fiber) return res.status(404).json({ error: '<Domain> not found' });
    res.json(fiber);
  } catch (err) {
    console.error('[<domain>/get] Error:', err);
    res.status(500).json({ error: 'Query failed' });
  }
});
```

**Mount in `packages/bridge/src/index.ts`:**
```typescript
import { <domain>Routes } from './routes/<domain>.js';
app.use('/<domain>', <domain>Routes);
```

**Critical patterns:**
- `waitForSequence(fiberId, targetSeq + 1, 30, 1000)` after ALL state-changing transitions
- `getFiberSequenceNumber` called immediately before `submitTransaction` (not cached)
- `schema: '<Domain>'` in `initialData` (required for indexer filtering)
- Zod parse BEFORE any async operations (fail fast)

### 7.3 `templates/fiber-definition.ts` (AC-4)

Verified against `~/repos/ottochain-services/packages/traffic-generator/src/fiber-definitions.ts`:

```typescript
// packages/traffic-generator/src/fiber-definitions.ts (additions)

// ── Type additions ───────────────────────────────────────────────────────────

// Add '<Domain>' to the workflowType union:
// export type WorkflowType = 'AgentIdentity' | 'Contract' | 'Market' | '<Domain>' | ...;

// ── Fiber definition ─────────────────────────────────────────────────────────

const <domain>Definition: FiberDefinition = {
  type:            '<Domain>',
  name:            '<Domain Name>',
  workflowType:    'Custom',           // or extend WorkflowType union
  roles:           ['creator', 'counterparty'],
  isVariableParty: false,
  states:          ['INITIAL', 'ACTIVE', 'COMPLETED', 'CANCELLED'],
  initialState:    'INITIAL',
  finalStates:     ['COMPLETED', 'CANCELLED'],
  transitions: [
    { from: 'INITIAL', to: 'ACTIVE',    event: 'activate', actor: 'creator'      },
    { from: 'ACTIVE',  to: 'COMPLETED', event: 'complete', actor: 'creator'      },
    { from: 'ACTIVE',  to: 'CANCELLED', event: 'cancel',   actor: 'creator'      },
  ],
  generateStateData: (participants, _context) => ({
    schema:       '<Domain>',
    creator:      participants.get('creator')!,
    counterparty: participants.get('counterparty')!,
    status:       'INITIAL',
    createdAt:    new Date().toISOString(),
    // add domain-specific fields with random test data
  }),
};

// ── Add to exports / weight map ──────────────────────────────────────────────
// In defaultWeights:
//   <domain>: 0.05,  // 5% of generated traffic

// In FIBER_DEFINITIONS or exported map:
//   <domain>: <domain>Definition,
```

**Workflow execution (`workflows.ts` additions):**
```typescript
// In createFiber() switch or equivalent:
case '<Domain>': {
  const response = await post('/<domain>/create', {
    privateKey: actor.privateKey,
    // domain-specific fields from stateData
  });
  return response.fiberId;
}

// In executeTransition() switch or equivalent:
case 'activate': {
  await post('/<domain>/activate', {
    privateKey: actor.privateKey,
    fiberId,
  });
  break;
}
case 'complete': {
  await post('/<domain>/complete', {
    privateKey: actor.privateKey,
    fiberId,
  });
  break;
}
```

### 7.4 `templates/explorer-component.tsx` (AC-5)

Uses indexer REST API directly (no gateway GraphQL needed for basic listing) — verified against `~/repos/ottochain-explorer/src/`:

```tsx
// src/components/<Domain>View.tsx
// Replace <Domain>, <domain> with actual names.

import { useState, useEffect } from 'react';

const INDEXER_URL = process.env.NEXT_PUBLIC_INDEXER_URL || 'http://localhost:3031';

interface FiberState {
  fiberId:      string;
  workflowType: string;
  currentState: { value: string };
  stateData:    Record<string, unknown>;
  owners:       string[];
  sequenceNumber: number;
  createdAt:    string;
}

interface <Domain>ViewProps {
  limit?: number;
  onSelect?: (fiber: FiberState) => void;
}

export function <Domain>View({ limit = 50, onSelect }: <Domain>ViewProps) {
  const [fibers, setFibers] = useState<FiberState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);

  useEffect(() => {
    setLoading(true);
    fetch(`${INDEXER_URL}/api/fibers?schema=<Domain>&limit=${limit}&offset=${offset}`)
      .then(r => r.json())
      .then(data => {
        setFibers(data.fibers ?? []);
        setTotal(data.total ?? 0);
        setLoading(false);
      })
      .catch(e => {
        setError(e.message);
        setLoading(false);
      });
  }, [limit, offset]);

  if (loading) return <div>Loading <domain>s…</div>;
  if (error)   return <div>Error: {error}</div>;
  if (fibers.length === 0) return <div>No <domain>s found.</div>;

  return (
    <div>
      <h2><Domain> List ({total})</h2>
      <ul>
        {fibers.map(f => (
          <li key={f.fiberId} onClick={() => onSelect?.(f)} style={{ cursor: 'pointer' }}>
            <strong>{f.fiberId.slice(0, 8)}</strong> — {f.currentState.value}
            {' '}<small>({new Date(f.createdAt).toLocaleDateString()})</small>
          </li>
        ))}
      </ul>
      <div>
        <button disabled={offset === 0} onClick={() => setOffset(o => Math.max(0, o - limit))}>
          Previous
        </button>
        <button disabled={offset + limit >= total} onClick={() => setOffset(o => o + limit)}>
          Next
        </button>
      </div>
    </div>
  );
}
```

**Usage in App.tsx or equivalent:**
```tsx
import { <Domain>View } from '../components/<Domain>View';

// In navigation/routing:
{ view === '<domain>' && <Domain>View onSelect={setSelectedFiber} /> }
```

### 7.5 `templates/checklist.md` (AC-6)

Matches the canonical checklist from `docs/guides/adding-new-app.md`:

```markdown
# New Domain Deployment Checklist: <Domain>

## SDK (ottobot-ai/ottochain-sdk)
- [ ] `src/apps/<domain>/state-machines/<name>.json` — state machine definition (proto wire format)
- [ ] `src/apps/<domain>/index.ts` — exports `get<Domain>Definition()`
- [ ] `src/apps/index.ts` — `export * as <domain> from './<domain>/index.js'`
- [ ] `pnpm build` — dist/ rebuilt successfully
- [ ] PR to `ottobot-ai/ottochain-sdk` targeting `main`

## Bridge (ottobot-ai/ottochain-services)
- [ ] `packages/bridge/src/routes/<domain>.ts` — HTTP API routes (Zod + submitTransaction)
- [ ] `packages/bridge/src/index.ts` — router mounted at `/<domain>`
- [ ] `waitForSequence` used after every state-changing transition
- [ ] Error handling: Zod 400, not-found 404, server 500
- [ ] PR to `ottobot-ai/ottochain-services` targeting `main`

## Traffic Generator (ottobot-ai/ottochain-services)
- [ ] `FiberDefinition` added to `fiber-definitions.ts`
- [ ] `generateStateData` returns correct stateData shape (includes `schema: '<Domain>'`)
- [ ] Workflow cases in `workflows.ts` (create + all transitions)
- [ ] Weight configured in `defaultWeights` (rebalance to sum to 1.0)

## Tests
- [ ] Lifecycle test: create → happy path to final state (all transitions succeed)
- [ ] Rejection tests: invalid state guard → HTTP 400
- [ ] `schema` field present in indexer query results

## Explorer (ottobot-ai/ottochain-explorer)
- [ ] Component renders fiber list from indexer REST API
- [ ] Pagination: Previous/Next work
- [ ] PR to `ottobot-ai/ottochain-explorer` targeting `main`

## Deployment Order
1. Metagraph: Only if Scala changes — `just build-all`, update JARs on all 3 nodes
2. SDK: Tag release → CI publishes → update `pnpm update @ottochain/sdk` in services
3. Services: Merge PR → tag release → `docker compose pull && docker compose up -d` on services node
4. Explorer: Merge PR → tag release → rebuild → rsync to nginx
5. Traffic generator: Update weights → PM2 restart
6. Verify: `curl http://localhost:3030/<domain>/create` with test payload
```

---

## 8. Test Cases (TDD)

These 12 tests must be written before implementation and must all pass.

### T-1: Skill triggers on expected phrases
```
Input: "add a new OttoChain app called Escrow"
Expected: Skill loads SKILL.md, begins Step 0 interview
```

### T-2: Skill triggers on "scaffold" variant
```
Input: "scaffold a Voting domain for OttoChain"
Expected: Skill loads SKILL.md, identifies domain as "Voting"
```

### T-3: SM template uses proto wire format for state IDs
```
Expected: templates/state-machine.json contains
  "states": { "INITIAL": { "id": { "value": "INITIAL" }, ... } }
NOT: "states": { "INITIAL": "INITIAL" }
```

### T-4: SM template uses wrapped initialState
```
Expected: templates/state-machine.json contains
  "initialState": { "value": "INITIAL" }
NOT: "initialState": "INITIAL"
```

### T-5: SM template uses $epochProgress (not $timestamp) in deadline guards
```
Expected: templates/state-machine.json guard examples use
  { "var": "$epochProgress" }
NOT: { "var": "$timestamp" }
```

### T-6: SM template uses merge pattern for effects
```
Expected: templates/state-machine.json effect uses
  { "merge": [{ "var": "state" }, {...} ] }
NOT: direct object assignment
```

### T-7: Bridge route template imports from metagraph.js (not metagraph.ts)
```
Expected: templates/bridge-route.ts contains
  from '../metagraph.js'  (ESM .js extension)
```

### T-8: Bridge route template calls waitForSequence after submitTransaction
```
Expected: templates/bridge-route.ts contains
  await waitForSequence(input.fiberId, targetSequenceNumber + 1, 30, 1000)
in every transition handler
```

### T-9: Bridge route initialData includes schema field
```
Expected: templates/bridge-route.ts initialData contains
  schema: '<Domain>'
```

### T-10: Traffic gen template uses FiberDefinition type with finalStates
```
Expected: templates/fiber-definition.ts contains
  finalStates: ['COMPLETED', 'CANCELLED']
  generateStateData: (participants, _context) => ({...})
```

### T-11: Explorer template uses indexer REST API (not gateway GraphQL)
```
Expected: templates/explorer-component.tsx fetches from
  ${INDEXER_URL}/api/fibers?schema=<Domain>
NOT from a GraphQL endpoint
```

### T-12: Checklist covers all 5 SDK, Bridge, Traffic Gen, Tests, Explorer sections
```
Expected: templates/checklist.md contains checkboxes for all 5 sections
and includes "waitForSequence" as a checkbox item in Bridge section
```

---

## 9. Out of Scope

- Proto definitions for typed domains (covered by `ottochain-sdk` skill)
- Scala unit test suites (covered by `ottochain-sm` skill references)
- Deployment automation (covered by `ottochain-deploy` skill)
- GraphQL gateway extensions (mentioned in checklist but not templated — add only if domain needs custom queries)

---

## 10. Implementation Plan

| Step | Owner | Effort | Depends on |
|------|-------|--------|-----------|
| Create skill directory + SKILL.md | @code | 1h | This spec |
| Write templates/state-machine.json | @code | 30m | AC-2, T-3–T-6 |
| Write templates/bridge-route.ts | @code | 45m | AC-3, T-7–T-9 |
| Write templates/fiber-definition.ts | @code | 30m | AC-4, T-10 |
| Write templates/explorer-component.tsx | @code | 30m | AC-5, T-11 |
| Write templates/checklist.md | @code | 15m | AC-6, T-12 |
| Write TDD tests (verify templates match patterns) | @code | 1h | All above |
| Integration test: invoke skill, verify step flow | @code | 30m | T-1, T-2 |

**Total estimated effort:** ~5 hours

---

## 11. Source Accuracy Verification

All patterns in this spec were verified against real repo code at `/home/euler/repos/`:

| Pattern | Verified in |
|---------|------------|
| Proto wire format `{ "id": { "value": "..." } }` | `ottochain-sdk/src/apps/contracts/state-machines/` |
| `submitTransaction` + `waitForSequence` | `ottochain-services/packages/bridge/src/routes/contract.ts` |
| `FiberDefinition` type with `generateStateData` | `ottochain-services/packages/traffic-generator/src/fiber-definitions.ts` |
| Indexer REST `GET /api/fibers?schema=` | `ottochain-services/packages/bridge/test/e2e.test.ts` |
| `$epochProgress` (not `$timestamp`) | `ottochain/docs/guides/adding-new-app.md` §2 "JSON Logic Patterns" |
| `merge` effect pattern | Same guide, §7 "Common Mistakes" |
| `schema` field requirement | Same guide, §7 "Common Mistakes" |
| Skill structure | Existing `ottochain-domains/SKILL.md` in `/home/euler/.openclaw/skills/` |

---

*🧠 @think perspective: The main complexity risk is pattern drift — templates need to stay in sync with actual implementations. Suggest adding a script that diffs templates against live code patterns on each deploy. The `ottochain-domains` skill already covers proto definitions and more advanced patterns; this skill is intentionally lower-level (first-time scaffolding) and higher-level (skip protobuf until needed). Edge cases documented: `waitForSequence` timing race, `$epochProgress` vs `$timestamp`, schema field for indexer, proto wire format for state IDs. All verified against real code.*
