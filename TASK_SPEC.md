# Task: Token Escrow State Machine

## Overview
Create a simple Token Escrow state machine example that demonstrates:
- Multi-party coordination (depositor, beneficiary)
- Time-based guards (refund after timeout)
- Condition-based guards (release requires beneficiary confirmation)

## 1. Unit Test

Create: `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/TokenEscrowSuite.scala`

### State Machine Definition
```
States:
- pending (initial) - waiting for deposit
- funded - tokens deposited, waiting for release or refund
- released (final) - beneficiary received tokens
- refunded (final) - depositor reclaimed tokens

Transitions:
- pending → funded: "fund" event (depositor deposits amount)
- funded → released: "release" event (beneficiary confirms receipt)
- funded → refunded: "refund" event (depositor reclaims after timeout)
```

### Test Cases
1. Happy path: fund → release
2. Refund path: fund → refund (after timeout)
3. Guard failure: release without beneficiary signature should fail
4. Guard failure: refund before timeout should fail

### Pattern to Follow
Look at `InteractionContractSuite.scala` or `CalculatorOracleSuite.scala` for the test structure using:
- `weaver.SimpleIOSuite`
- `TestFixture.resource`
- `Combiner.make[IO]()`
- JSON state machine definitions with guards and effects

## 2. E2E Example

Create directory: `e2e-test/examples/token-escrow/`

### Files to Create

**example.json**:
```json
{
  "name": "Token Escrow",
  "description": "Simple escrow with deposit, release, and refund flows",
  "type": "state-machine",
  "definition": "definition.json",
  "initialData": "initial-data.json",
  "events": [
    { "name": "fund", "file": "event-fund.json", "from": "pending", "to": "funded" },
    { "name": "release", "file": "event-release.json", "from": "funded", "to": "released" },
    { "name": "refund", "file": "event-refund.json", "from": "funded", "to": "refunded" }
  ],
  "testFlows": [
    {
      "name": "Happy path - release",
      "steps": [
        { "action": "create", "definition": "definition.json", "initialData": "initial-data.json" },
        { "action": "processEvent", "event": "event-fund.json", "expectedState": "funded" },
        { "action": "processEvent", "event": "event-release.json", "expectedState": "released" }
      ]
    },
    {
      "name": "Refund path",
      "steps": [
        { "action": "create", "definition": "definition.json", "initialData": "initial-data.json" },
        { "action": "processEvent", "event": "event-fund.json", "expectedState": "funded" },
        { "action": "processEvent", "event": "event-refund.json", "expectedState": "refunded" }
      ]
    }
  ]
}
```

**definition.json**: JSON Logic state machine (see approval-workflow/definition.json pattern)

**initial-data.json**: Initial state data
```json
{
  "depositor": "alice",
  "beneficiary": "bob",
  "amount": 0,
  "status": "pending"
}
```

**event-fund.json**, **event-release.json**, **event-refund.json**: Event payloads

## Acceptance Criteria

1. Unit tests pass: `sbt sharedData/test`
2. E2E tests pass: `npm test` from e2e-test directory
3. Files follow existing patterns in the codebase

## Reference Files
- `e2e-test/examples/approval-workflow/` - simple e2e example
- `modules/shared-data/src/test/scala/.../CalculatorOracleSuite.scala` - simple unit test
- `e2e-test/examples/README.md` - e2e example documentation
