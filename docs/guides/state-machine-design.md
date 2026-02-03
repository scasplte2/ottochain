# State Machine Design Guide

A practical guide to writing effective JSON-encoded state machine definitions and events for the Ottochain platform.

## Table of Contents
1. [Understanding the Context](#understanding-the-context)
2. [State Machine Definition Structure](#state-machine-definition-structure)
3. [Writing Effective Guards](#writing-effective-guards)
4. [Writing Effective Effects](#writing-effective-effects)
5. [Event Design](#event-design)
6. [Common Patterns](#common-patterns)
7. [Troubleshooting](#troubleshooting)

## Understanding the Context

When your guards and effects execute, they have access to a context object with the following structure:

```javascript
{
  "state": {...},              // Current state data (MapValue)
  "event": {...},              // Event payload (whatever you sent)
  "eventType": "submit",       // The event type as a string
  "machineId": "uuid",         // This fiber's UUID
  "currentStateId": "draft",   // Current state ID
  "sequenceNumber": 0,         // Current sequence number
  "proofs": [{                 // Signatures on the transaction
    "address": "DAG...",
    "id": "...",
    "signature": "..."
  }],
  "machines": {                // Dependent state machines (if any)
    "uuid": {
      "state": {...},
      "currentStateId": "...",
      "sequenceNumber": 0
    }
  },
  "parent": {...},            // Parent machine context (if any)
  "children": {...},          // Child machines (if any)
  "scripts": {...}      // Dependent oracles (if any)
}
```

### Critical Variables

- **`state`** - Use `{ "var": "state" }` to access current state data
- **`event`** - Use `{ "var": "event.fieldName" }` to access event payload fields
- **NOT** `data` or `payload` - These don't exist in the context!

## State Machine Definition Structure

### Basic Template

```json
{
  "states": {
    "state_id": {
      "id": { "value": "state_id" },
      "isFinal": false,
      "metadata": null
    }
  },
  "initialState": { "value": "initial_state_id" },
  "transitions": [
    {
      "from": { "value": "source_state" },
      "to": { "value": "target_state" },
      "eventType": { "value": "event_name" },
      "guard": { "json-logic-expression": "..." },
      "effect": { "json-logic-expression": "..." },
      "dependencies": []
    }
  ],
  "metadata": {
    "name": "MyStateMachine",
    "description": "What this state machine does"
  }
}
```

### State Design Tips

1. **Use clear, descriptive state names**
   - ✅ `"pending"`, `"approved"`, `"shipped"`
   - ❌ `"s1"`, `"state_a"`, `"temp"`

2. **Mark final states correctly**
   - Set `"isFinal": true` for terminal states
   - Final states prevent further transitions
   - Examples: `"completed"`, `"cancelled"`, `"rejected"`

3. **Keep state IDs consistent**
   - The `"id"` field should match the object key
   - Both should use the same naming convention

## Writing Effective Guards

Guards determine whether a transition can execute. They must return a boolean.

### Simple Guards

**Always allow:**
```json
{
  "guard": { "==": [1, 1] }
}
```

**Check event field:**
```json
{
  "guard": { ">=": [{ "var": "event.amount" }, 100] }
}
```

**Check state field:**
```json
{
  "guard": { "===": [{ "var": "state.status" }, "pending"] }
}
```

### Complex Guards

**Multiple conditions (AND):**
```json
{
  "guard": {
    "and": [
      { ">": [{ "var": "event.amount" }, 0] },
      { "<=": [{ "var": "event.amount" }, 10000] },
      { "===": [{ "var": "state.approved" }, false] }
    ]
  }
}
```

**Multiple conditions (OR):**
```json
{
  "guard": {
    "or": [
      { "===": [{ "var": "event.priority" }, "urgent"] },
      { ">": [{ "var": "event.amount" }, 5000] }
    ]
  }
}
```

**Check proof signatures:**
```json
{
  "guard": {
    "in": [
      { "var": "event.approver" },
      { "map": [
        { "var": "proofs" },
        { "var": "address" }
      ]}
    ]
  }
}
```

### Guard Best Practices

1. **Keep guards deterministic** - Same input should always produce same output
2. **Avoid complex calculations** - Guards should be quick checks
3. **Use meaningful comparisons** - Make intent clear
4. **Don't mutate state in guards** - That's what effects are for

## Writing Effective Effects

Effects modify the state when a transition executes. They must return a MapValue or ArrayValue.

### The Merge Operation

The most common pattern is merging event data into state:

```json
{
  "effect": {
    "merge": [
      { "var": "state" },           // Current state
      { "newField": "value" }       // New/updated fields
    ]
  }
}
```

### Common Effect Patterns

**1. Add fields from event to state:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      {
        "submittedAt": { "var": "event.timestamp" },
        "submittedBy": { "var": "event.userId" }
      }
    ]
  }
}
```

**2. Merge entire event payload:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      { "var": "event" }
    ]
  }
}
```

**3. Conditional field updates:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      {
        "status": { "var": "event.newStatus" },
        "priority": {
          "if": [
            { ">": [{ "var": "event.amount" }, 1000] },
            "high",
            "normal"
          ]
        }
      }
    ]
  }
}
```

**4. Update nested objects:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      {
        "metadata": {
          "merge": [
            { "var": "state.metadata" },
            { "lastModified": { "var": "event.timestamp" } }
          ]
        }
      }
    ]
  }
}
```

**5. Array operations:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      {
        "history": {
          "cat": [
            { "var": "state.history" },
            [{
              "action": { "var": "eventType" },
              "timestamp": { "var": "event.timestamp" }
            }]
          ]
        }
      }
    ]
  }
}
```

### Reserved Effect Keys

Effects can include special keys for side effects:

**Trigger events on other machines:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      { "processed": true }
    ],
    "_triggers": [
      {
        "targetMachineId": "uuid-of-target",
        "eventType": "notification",
        "payload": { "message": { "var": "state.message" } }
      }
    ]
  }
}
```

**Call oracles:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      { "calculationRequested": true }
    ],
    "_oracleCall": {
      "cid": "oracle-uuid",
      "method": "calculate",
      "args": { "value": { "var": "state.amount" } }
    }
  }
}
```

**Spawn child machines:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      { "spawned": true }
    ],
    "_spawn": [
      {
        "childId": "child-uuid",
        "definition": { "...": "..." },
        "initialData": { "parentId": { "var": "machineId" } }
      }
    ]
  }
}
```

**Structured outputs:**
```json
{
  "effect": {
    "merge": [
      { "var": "state" },
      { "completed": true }
    ],
    "_outputs": [
      {
        "outputType": "webhook",
        "data": { "status": "complete" },
        "destination": "https://api.example.com/callback"
      }
    ]
  }
}
```

### Effect Best Practices

1. **Always include current state** - Start with `{ "var": "state" }`
2. **Use merge for updates** - Preserves existing fields
3. **Validate before effects** - Use guards to check conditions
4. **Keep effects focused** - Each effect should have a clear purpose
5. **Don't access undefined fields** - Use guards to ensure fields exist
6. **Avoid circular triggers** - Be careful with `_triggers` to prevent loops

## Event Design

### Event Structure

```json
{
  "eventType": { "value": "event_name" },
  "payload": {
    "field1": "value1",
    "field2": 42
  },
  "idempotencyKey": null
}
```

### Event Best Practices

1. **Use descriptive event names**
   - ✅ `"submit"`, `"approve"`, `"ship_order"`
   - ❌ `"event1"`, `"do_thing"`, `"update"`

2. **Keep payloads flat when possible**
   ```json
   // ✅ Good
   {
     "timestamp": "2025-01-01T00:00:00Z",
     "userId": "user123",
     "amount": 100
   }

   // ❌ Harder to access
   {
     "metadata": {
       "timestamp": "2025-01-01T00:00:00Z",
       "user": { "id": "user123" }
     },
     "data": { "amount": 100 }
   }
   ```

3. **Include context data**
   - Timestamps
   - User identifiers
   - Reference IDs
   - Any data needed for effects or guards

4. **Use consistent field names**
   - If multiple events use timestamps, call them all `"timestamp"`
   - Consistency makes guards and effects reusable

5. **Validate event structure**
   - Use guards to check required fields exist
   - Validate ranges and types in guards

## Common Patterns

### Approval Workflow

```json
{
  "states": {
    "draft": { "id": { "value": "draft" }, "isFinal": false },
    "submitted": { "id": { "value": "submitted" }, "isFinal": false },
    "approved": { "id": { "value": "approved" }, "isFinal": true },
    "rejected": { "id": { "value": "rejected" }, "isFinal": true }
  },
  "initialState": { "value": "draft" },
  "transitions": [
    {
      "from": { "value": "draft" },
      "to": { "value": "submitted" },
      "eventType": { "value": "submit" },
      "guard": { "==": [1, 1] },
      "effect": {
        "merge": [
          { "var": "state" },
          { "submittedAt": { "var": "event.timestamp" } }
        ]
      }
    },
    {
      "from": { "value": "submitted" },
      "to": { "value": "approved" },
      "eventType": { "value": "approve" },
      "guard": { "in": [{ "var": "event.approver" }, ["manager@example.com", "admin@example.com"]] },
      "effect": {
        "merge": [
          { "var": "state" },
          { "approvedBy": { "var": "event.approver" } }
        ]
      }
    }
  ]
}
```

### State Machine with Dependencies

```json
{
  "transitions": [
    {
      "from": { "value": "pending" },
      "to": { "value": "approved" },
      "eventType": { "value": "check_dependencies" },
      "guard": {
        "and": [
          { "===": [{ "var": "machines.dep1.currentStateId" }, "completed"] },
          { "===": [{ "var": "machines.dep2.currentStateId" }, "completed"] }
        ]
      },
      "effect": {
        "merge": [
          { "var": "state" },
          {
            "approved": true,
            "dep1Data": { "var": "machines.dep1.state.result" },
            "dep2Data": { "var": "machines.dep2.state.result" }
          }
        ]
      },
      "dependencies": ["uuid-of-dep1", "uuid-of-dep2"]
    }
  ]
}
```

### Parent-Child Communication

```json
{
  "transitions": [
    {
      "from": { "value": "waiting" },
      "to": { "value": "processing" },
      "eventType": { "value": "parent_ready" },
      "guard": { "===": [{ "var": "parent.currentStateId" }, "ready"] },
      "effect": {
        "merge": [
          { "var": "state" },
          {
            "parentData": { "var": "parent.state" },
            "startedAt": { "var": "event.timestamp" }
          }
        ]
      }
    }
  ]
}
```

## Troubleshooting

### Common Errors and Solutions

**1. "Invalid effect format: NullValue"**
- **Cause:** Using `{ "var": "data" }` instead of `{ "var": "state" }`
- **Solution:** Always use `{ "var": "state" }` to access current state

**2. "Guard returned non-boolean value"**
- **Cause:** Guard expression doesn't return true/false
- **Solution:** Use comparison operators (`==`, `>`, `in`, etc.) that return booleans

**3. "No transition found"**
- **Cause:** No transition matches current state + event type
- **Solution:** Check state machine is in expected state, verify event type matches exactly

**4. "No guard matched"**
- **Cause:** All guards for this transition returned false
- **Solution:** Review guard conditions, check event payload has required fields

**5. "Effect must return MapValue or ArrayValue"**
- **Cause:** Effect doesn't return a valid object or array
- **Solution:** Ensure effect uses `merge` or returns a map/array structure

**6. Undefined field access**
- **Cause:** Accessing `event.field` or `state.field` that doesn't exist
- **Solution:** Add guard to check field exists: `{ "!!": [{ "var": "event.field" }] }`

**7. "Cannot process event on fiber: fiber is Archived"**
- **Cause:** Sending an event to a state machine that has been archived
- **Solution:** Check fiber status before sending events. Archived fibers cannot process events.

**8. "Trigger target not found"**
- **Cause:** A `_triggers` directive references a fiber UUID that doesn't exist
- **Solution:** Verify the target fiber exists and the UUID is correct. This error aborts the entire transaction.

**9. "Spawn validation failed"**
- **Cause:** Invalid spawn directive (bad UUID format, duplicate child IDs, invalid owner addresses)
- **Solution:** Check spawn expressions return valid UUIDs and addresses. Use unique child IDs per spawn batch.

### Debugging Tips

1. **Test guards in isolation** - Verify they return booleans
2. **Test effects separately** - Ensure they produce valid output
3. **Use simple guards first** - Start with `{ "==": [1, 1] }` then add logic
4. **Check the context** - Remember: `state` and `event`, not `data` and `payload`
5. **Validate JSON** - Use a JSON validator to catch syntax errors
6. **Start simple** - Build complexity gradually
7. **Review logs** - Error messages often indicate which expression failed

### Testing Checklist

Before deploying a state machine:

- [ ] All states have correct `isFinal` values
- [ ] Initial state exists in states map
- [ ] All transitions reference valid states
- [ ] Guards return booleans
- [ ] Effects return MapValue (after merge)
- [ ] Effects use `{ "var": "state" }` not `{ "var": "data" }`
- [ ] Event payloads use `{ "var": "event.field" }` not `{ "var": "payload.field" }`
- [ ] No circular trigger loops
- [ ] Dependencies are specified when accessing other machines
- [ ] Reserved keys (starting with `_`) only used for side effects
- [ ] Trigger targets exist and are valid UUIDs
- [ ] Spawn child IDs are unique within each effect
- [ ] Owner addresses in spawns are valid DAG addresses
- [ ] No events sent to archived fibers

## JSON Logic Reference

Common operations for guards and effects:

### Comparison
- `{ "==": [a, b] }` - Equal (loose)
- `{ "===": [a, b] }` - Equal (strict)
- `{ "!=": [a, b] }` - Not equal
- `{ ">": [a, b] }`, `{ ">=": [a, b] }` - Greater than
- `{ "<": [a, b] }`, `{ "<=": [a, b] }` - Less than

### Logic
- `{ "and": [a, b, ...] }` - All true
- `{ "or": [a, b, ...] }` - Any true
- `{ "!": a }` - Not
- `{ "!!": a }` - Truthy check

### Arrays
- `{ "in": [item, array] }` - Item in array
- `{ "map": [array, expr] }` - Transform array
- `{ "filter": [array, expr] }` - Filter array
- `{ "cat": [array1, array2] }` - Concatenate

### Conditionals
- `{ "if": [condition, then, else] }` - Ternary

### Data
- `{ "var": "path.to.field" }` - Access variable
- `{ "merge": [obj1, obj2] }` - Merge objects

## Additional Resources

- [JSON Logic Playground](https://jsonlogic.com/play.html) - Test expressions
- `examples/` directory - Working examples
- `examples/README.md` - Usage documentation