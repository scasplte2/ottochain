# Task: Escrow with Oracle Price Feed

## Goal
Create a test that combines a STATE MACHINE (escrow contract) with a SCRIPT ORACLE (price feed).
The escrow releases only when the oracle confirms the price condition is met.

## Architecture
1. **PriceFeedOracle** (ScriptOracle): Accepts price updates, stores current price
2. **ConditionalEscrow** (StateMachine): Releases funds when price >= threshold

## State Machine: ConditionalEscrow
- States: `pending`, `funded`, `released`, `cancelled`
- Transitions:
  - `fund`: pending → funded (store amount, threshold)
  - `checkPrice`: funded → released (guard checks oracle price >= threshold)
  - `cancel`: funded → cancelled

## Script Oracle: PriceFeed  
- Methods: `updatePrice(price)` - stores new price
- State: `{ "price": 0 }`

## Test Flow
1. Create price oracle
2. Create escrow with threshold=100
3. Fund escrow
4. Update oracle price to 50 (below threshold)
5. Try checkPrice → should fail (guard not satisfied)
6. Update oracle price to 150
7. checkPrice → should succeed, escrow released

## Reference Files (COPY EXACTLY)
- `TokenEscrowSuite.scala` for state machine patterns
- `CalculatorOracleSuite.scala` for script oracle patterns

## Key APIs
```scala
// State Machine
Updates.CreateStateMachine(fiberId, machineDef, initialData)
Updates.TransitionStateMachine(fiberId, eventName, payload, ordinal)
state.calculated.stateMachines.get(fiberId)

// Script Oracle  
Updates.CreateScriptOracle(fiberId, script, initialState, accessControl)
Updates.InvokeScriptOracle(fiberId, method, args, ordinal)
state.calculated.scriptOracles.get(fiberId)

// Types
MapValue, IntValue, StrValue (NOT StringValue!)
FiberOrdinal.MinValue, FiberOrdinal.unsafeApply(1L)
StateId("statename")
```

## Output File
`modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/OracleEscrowSuite.scala`

## Test Command
```bash
source ~/.sdkman/bin/sdkman-init.sh && sbt "sharedData/testOnly *OracleEscrow*"
```

## CONSTRAINTS
- DO NOT create any new model files
- DO NOT modify existing files
- Create ONLY the test file
