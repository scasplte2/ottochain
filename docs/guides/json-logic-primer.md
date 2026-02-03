# JSON Logic Primer for OttoChain

This guide introduces JSON Logic as used in OttoChain for defining state machine guards and effects. It assumes you know JSON but have never used JSON Logic before.

## What is JSON Logic?

[JSON Logic](https://jsonlogic.com) is a way to express rules and computations as JSON data structures. Instead of writing code, you write JSON objects where the key is an operator and the value contains the operands:

```json
{ "operator": [operand1, operand2, ...] }
```

This makes logic portable, serializable, and safe to evaluate in sandboxed environments — exactly what you need for on-chain state machines.

## Basic Operators

### Variable Access: `var`

The `var` operator retrieves values from the evaluation context using dot-notation paths:

```json
{"var": "event.amount"}
```

Returns the `amount` field from the `event` object in the context. You can also provide a default:

```json
{"var": ["event.priority", "normal"]}
```

Returns `"normal"` if `event.priority` doesn't exist.

### Equality: `==`, `===`, `!=`, `!==`

```json
{"==": [{"var": "event.type"}, "payment"]}
```

Checks if `event.type` equals `"payment"`. Use `===` for strict equality (no type coercion):

```json
{"===": [{"var": "state.status"}, "active"]}
```

### Comparisons: `>`, `>=`, `<`, `<=`

```json
{">": [{"var": "event.amount"}, 0]}
```

Checks that the event amount is positive. Note: operands that reference context data must use `{"var": "..."}` — bare strings are treated as literals.

### Logical: `and`, `or`, `!`

Combine conditions:

```json
{
  "and": [
    {"===": [{"var": "state.status"}, "active"]},
    {">": [{"var": "event.amount"}, 0]}
  ]
}
```

Negate a condition:

```json
{"!": [{"var": "state.locked"}]}
```

### Truthiness: `!!`

Coerce a value to boolean. Useful for checking that a field exists and is non-empty:

```json
{"!!": [{"var": "event.playerX"}]}
```

### Conditional: `if`

Works like if/else-if/else chains:

```json
{
  "if": [
    {">": [{"var": "state.balance"}, 10000]}, "premium",
    {">": [{"var": "state.balance"}, 1000]},  "standard",
    "basic"
  ]
}
```

Pattern: `[condition1, result1, condition2, result2, ..., elseResult]`

### Arithmetic: `+`, `-`, `*`, `/`

```json
{"+": [{"var": "state.balance"}, {"var": "event.amount"}]}
```

Adds the event amount to the current balance.

### Array: `merge`

Concatenates arrays:

```json
{"merge": [[1, 2], [3, 4]]}
```

Result: `[1, 2, 3, 4]`

> **Important:** `merge` in standard JSON Logic concatenates *arrays*. For merging objects (key-value maps), OttoChain uses a different pattern — see [Effect Patterns](#effect-patterns) below.

## How OttoChain Uses JSON Logic

Every state machine transition has two JSON Logic expressions:

```json
{
  "from": "idle",
  "to": "processing",
  "eventName": "submit",
  "guard": { ... },
  "effect": { ... }
}
```

### Guards

A **guard** is a boolean JSON Logic expression. The transition only fires if the guard evaluates to a truthy value. Guards are evaluated against the [context object](#the-context-object).

```json
"guard": {
  "and": [
    {"!!": [{"var": "event.playerX"}]},
    {"!!": [{"var": "event.playerO"}]},
    {"!!": [{"var": "event.gameId"}]}
  ]
}
```

This guard requires all three fields to be present and non-empty.

### Effects

An **effect** is a JSON Logic expression that produces the fiber's new state. The result is a JSON object — its top-level keys become the new state data (minus reserved keys that are extracted as side effects).

```json
"effect": {
  "gameId": {"var": "event.gameId"},
  "playerX": {"var": "event.playerX"},
  "playerO": {"var": "event.playerO"},
  "status": "initialized"
}
```

This sets four fields on the new state. Values can be:
- **Literals:** `"initialized"`, `42`, `true`
- **Variable references:** `{"var": "event.gameId"}` — pulls from context
- **Computed values:** `{"+": [{"var": "state.count"}, 1]}` — arithmetic on context values
- **Nested expressions:** Any valid JSON Logic

## The Context Object

Guards and effects are evaluated against a context that includes:

| Key | Type | Description |
|-----|------|-------------|
| `state` | Object | Current fiber state data |
| `event` | Object | Incoming event payload |
| `eventName` | String | Event type (e.g., `"submit"`, `"make_move"`) |
| `machineId` | String | This fiber's UUID |
| `currentStateId` | String | Current state name (e.g., `"idle"`, `"playing"`) |
| `sequenceNumber` | Integer | Event sequence counter |
| `proofs` | Array | Signer information (address, public key, signature) |
| `machines` | Object | States of dependent state machine fibers |
| `parent` | Object/null | Parent fiber's state (if this is a child) |
| `children` | Object | Child fiber states, keyed by UUID |
| `scripts` | Object | States of dependent script fibers |

### Accessing Nested Context

Use dot-notation paths:

```json
{"var": "state.balance"}
{"var": "event.data.amount"}
{"var": "proofs.0.address"}
{"var": "machines.550e8400-e29b-41d4-a716-446655440000.state.status"}
{"var": "scripts.11111111-1111-1111-1111-111111111111.state.board"}
```

### Oracle Context (for Script fibers)

Script oracles receive a different context:

| Key | Type | Description |
|-----|------|-------------|
| `_method` | String | Method name being called |
| `_args` | Object | Method arguments |
| `_state` | Object/null | Current oracle state |

## OttoChain Extensions (Reserved Keys)

Effect results can include special keys (prefixed with `_`) that trigger side effects. These are extracted by the engine and **not** merged into state.

### `_oracleCall` — Invoke a Script

Calls a method on a script fiber:

```json
"effect": {
  "_oracleCall": {
    "cid": {"var": "state.oracleCid"},
    "method": "makeMove",
    "args": {
      "player": {"var": "event.player"},
      "cell": {"var": "event.cell"}
    }
  },
  "lastMove": {
    "player": {"var": "event.player"},
    "cell": {"var": "event.cell"}
  }
}
```

Fields:
- `cid` — UUID of the target oracle (can be a `var` reference)
- `method` — Method name string
- `args` — Arguments object (values are evaluated as expressions)

The oracle processes the call and its return value can influence subsequent evaluation.

### `_triggers` — Cross-Machine Events

Fire events at other state machine fibers:

```json
"effect": {
  "_triggers": [
    {
      "targetMachineId": "550e8400-e29b-41d4-a716-446655440000",
      "eventName": "payment_received",
      "payload": {
        "amount": {"var": "event.amount"},
        "from": {"var": "machineId"}
      }
    }
  ],
  "status": "notified"
}
```

Fields per trigger:
- `targetMachineId` — UUID of the target fiber
- `eventName` — Event type to fire
- `payload` — Event data (values are evaluated as expressions against the current context)

### `_spawn` — Create Child Machines

Spawn new state machine fibers as children:

```json
"effect": {
  "_spawn": [
    {
      "childId": "child-invoice-001",
      "definition": {
        "states": { ... },
        "initialState": {"value": "open"},
        "transitions": [ ... ]
      },
      "initialData": {
        "amount": {"var": "event.amount"},
        "parentId": {"var": "machineId"}
      },
      "owners": ["DAG-address-1", "DAG-address-2"]
    }
  ],
  "childSpawned": true
}
```

Fields per spawn:
- `childId` — Identifier for the child
- `definition` — Full state machine definition (inline)
- `initialData` — Initial state data (values evaluated as expressions)
- `owners` — (Optional) Owner addresses for the child fiber

### `_emit` — User-Defined Events

Emit observable events (for external consumption):

```json
"effect": {
  "_emit": [
    {
      "name": "game_completed",
      "data": {
        "gameId": {"var": "state.gameId"},
        "winner": {"var": "scripts.11111111-1111-1111-1111-111111111111.state.winner"},
        "status": {"var": "scripts.11111111-1111-1111-1111-111111111111.state.status"}
      }
    }
  ]
}
```

Fields per emission:
- `name` — Event name string
- `data` — Event payload (can reference context)
- `destination` — (Optional) Routing hint

## Common Patterns

### Always-True Guard

Allow a transition unconditionally:

```json
"guard": {"==": [1, 1]}
```

### Required Event Fields

Check that multiple fields exist:

```json
"guard": {
  "and": [
    {"!!": [{"var": "event.playerX"}]},
    {"!!": [{"var": "event.playerO"}]},
    {"!!": [{"var": "event.gameId"}]}
  ]
}
```

### Check Dependent Oracle State

Read state from a script via the `scripts` context:

```json
"guard": {
  "===": [
    {"var": "scripts.11111111-1111-1111-1111-111111111111.state.status"},
    "InProgress"
  ]
}
```

### Increment a Counter

```json
"effect": {
  "roundCount": {"+": [{"var": "state.roundCount"}, 1]}
}
```

### Copy Event Data to State

```json
"effect": {
  "gameId": {"var": "event.gameId"},
  "playerX": {"var": "event.playerX"},
  "playerO": {"var": "event.playerO"},
  "status": "initialized"
}
```

### Effect Patterns

Since effects produce a new state object directly, you build the result as a JSON object where each key is a state field. To preserve existing state while adding new fields:

```json
"effect": {
  "balance": {"+": [{"var": "state.balance"}, {"var": "event.amount"}]},
  "lastTransaction": {"var": "event.transactionId"},
  "previousBalance": {"var": "state.balance"}
}
```

> **Note:** Only fields you include in the effect are set on the new state. If you want to keep `state.name` unchanged, include `"name": {"var": "state.name"}` in the effect.

### Conditional Effect Values

Use `if` within an effect to compute values conditionally:

```json
"effect": {
  "tier": {
    "if": [
      {">": [{"var": "state.balance"}, 10000]}, "gold",
      {">": [{"var": "state.balance"}, 1000]}, "silver",
      "bronze"
    ]
  }
}
```

### Combined: Oracle Call + State Update + Emit

A real-world effect combining multiple side effects with state changes:

```json
"effect": {
  "_oracleCall": {
    "cid": {"var": "state.oracleCid"},
    "method": "makeMove",
    "args": {
      "player": {"var": "event.player"},
      "cell": {"var": "event.cell"}
    }
  },
  "_emit": [
    {
      "name": "game_completed",
      "data": {
        "gameId": {"var": "state.gameId"},
        "winner": {"var": "scripts.11111111-1111-1111-1111-111111111111.state.winner"}
      }
    }
  ],
  "finalStatus": {"var": "scripts.11111111-1111-1111-1111-111111111111.state.status"},
  "winner": {"var": "scripts.11111111-1111-1111-1111-111111111111.state.winner"},
  "finalBoard": {"var": "scripts.11111111-1111-1111-1111-111111111111.state.board"}
}
```

The engine processes this by:
1. Evaluating the entire expression against the context
2. Extracting `_oracleCall` → invokes the oracle
3. Extracting `_emit` → queues emitted events
4. Remaining keys (`finalStatus`, `winner`, `finalBoard`) → become new state

## Gas Metering

All JSON Logic evaluation in OttoChain is gas-metered. Each operation (comparisons, variable lookups, arithmetic) costs gas. Complex expressions with many operators consume more gas. The gas system prevents infinite loops and bounds computation cost.

Gas is configured at two levels:
- **JLVM Gas** — Per-expression evaluation costs
- **Fiber Gas** — Per-orchestration operation costs (triggers, spawns, oracle calls)

## Further Reading

- [OttoChain Introduction](../introduction.md) — Architecture and concepts overview
- [Architecture Reference](../reference/architecture.md) — Deployment and system design
- [JSON Logic Specification](https://jsonlogic.com) — Full operator reference
- Example state machines in `modules/shared-data/src/test/resources/`
