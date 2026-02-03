# Task: Add "Expired Escrow" Test Case

## Goal
Add a third test to TokenEscrowSuite that tests expired escrow (time-based guard).

## What to do
1. Add an "expire" transition to the state machine definition (funded → expired)
2. Add a test case "Expiration path: fund then expire after timeout"
3. The guard should check that enough time has passed (simulate with a flag)

## Reference Pattern
Look at the existing tests in the same file for the exact API:
- `Updates.CreateStateMachine(fiberId, machineDef, initialData)` 
- `Updates.TransitionStateMachine(fiberId, eventName, payload, ordinal)`
- `state.calculated.stateMachines.get(fiberId).collect { case r: Records.StateMachineFiberRecord => r }`
- Use `StrValue`, `IntValue`, `MapValue` (NOT StringValue)
- Use `StateId("statename")` for state comparisons

## Test Command
```bash
source ~/.sdkman/bin/sdkman-init.sh && sbt "sharedData/testOnly *TokenEscrow*"
```

## Acceptance
All 3 TokenEscrow tests pass (including the new one).
