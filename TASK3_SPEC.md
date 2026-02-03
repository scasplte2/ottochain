# Task: Create Voting Contract Example (Multi-file)

## Goal
Create a NEW voting state machine example with unit test AND e2e files.

## Files to Create
1. `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/VotingSuite.scala`
2. `e2e-test/examples/voting/example.json`
3. `e2e-test/examples/voting/definition.json`
4. `e2e-test/examples/voting/initial-data.json`
5. `e2e-test/examples/voting/event-vote.json`
6. `e2e-test/examples/voting/event-close.json`

## State Machine Design
- **States**: `open` (initial), `closed` (final)
- **Transitions**:
  - `vote`: open → open (accumulate votes)
  - `close`: open → closed (finalize)
- **State data**: `{ "votes": { "optionA": 0, "optionB": 0 }, "closed": false }`

## Unit Test Requirements
Copy the EXACT patterns from TokenEscrowSuite.scala:
```scala
package xyz.kd5ujc.shared_data.examples

// Same imports as TokenEscrowSuite
import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._
import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture
import io.circe.parser._
import weaver.SimpleIOSuite

// API patterns:
// - Updates.CreateStateMachine(fiberId, machineDef, initialData)
// - Updates.TransitionStateMachine(fiberId, eventName, payload, ordinal)
// - decode[StateMachineDefinition](json)
// - MapValue(Map("key" -> IntValue(0)))
// - StrValue("string"), IntValue(123)
// - state.calculated.stateMachines.get(fiberId).collect { case r: Records.StateMachineFiberRecord => r }
// - StateId("statename") for comparisons
// - FiberOrdinal.MinValue, FiberOrdinal.unsafeApply(1L)
```

## E2E File Patterns
Look at `e2e-test/examples/token-escrow/` for exact JSON structure.

## Test Command
```bash
source ~/.sdkman/bin/sdkman-init.sh && sbt "sharedData/testOnly *Voting*"
```

## Acceptance
- Unit tests pass
- E2E files exist and are valid JSON
