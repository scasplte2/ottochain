# Ottochain E2E Test Examples

This directory contains example-based test resources for the Ottochain e2e test suite. Each example is self-contained with all related files grouped together in a dedicated directory.

## Directory Structure

Each example directory contains:
- `definition.json` - The state machine or script definition
- `initial-data.json` - Initial data for state machines
- `event-*.json` - Event files for state machine transitions
- `args-*.json` - Argument files for script method invocations
- `example.json` - Metadata describing the example and its test flows

## Available Examples

### State Machine Examples

#### approval-workflow
A simple approval workflow state machine with draft, submitted, approved, and rejected states.

**Files:**
- `definition.json` - State machine definition
- `initial-data.json` - Sample approval request
- `event-submit.json` - Submit event (draft → submitted)
- `event-approve.json` - Approve event (submitted → approved)
- `event-reject.json` - Reject event (submitted → rejected)

**Usage:**
```bash
# Create a state machine
node terminal.js sm create --definition examples/approval-workflow/definition.json --initialData examples/approval-workflow/initial-data.json

# Process submit event (use the CID from previous command)
node terminal.js sm process-event --address <CID> --event examples/approval-workflow/event-submit.json --expectedState submitted

# Process approve event
node terminal.js sm process-event --address <CID> --event examples/approval-workflow/event-approve.json --expectedState approved
```

#### simple-order
A basic order fulfillment state machine from pending to delivered.

**Files:**
- `definition.json` - State machine definition
- `initial-data.json` - Sample order
- `event-confirm.json` - Confirm event (pending → confirmed)
- `event-ship.json` - Ship event (confirmed → shipped)
- `event-deliver.json` - Deliver event (shipped → delivered)
- `event-cancel.json` - Cancel event (pending → cancelled)

**Usage:**
```bash
# Create and process full order flow
node terminal.js sm create --definition examples/simple-order/definition.json --initialData examples/simple-order/initial-data.json
node terminal.js sm process-event --address <CID> --event examples/simple-order/event-confirm.json --expectedState confirmed
node terminal.js sm process-event --address <CID> --event examples/simple-order/event-ship.json --expectedState shipped
node terminal.js sm process-event --address <CID> --event examples/simple-order/event-deliver.json --expectedState delivered
```

### Script Examples

#### counter-script
A stateful script that maintains a counter with increment, decrement, and reset operations.

**Files:**
- `definition.json` - Script definition

**Usage:**
```bash
# Create script
node terminal.js or create --script examples/counter-script/definition.json

# Invoke methods (use the CID from previous command)
node terminal.js or invoke --address <CID> --method increment
node terminal.js or invoke --address <CID> --method increment
node terminal.js or invoke --address <CID> --method decrement
node terminal.js or invoke --address <CID> --method reset
```

#### calculator-script
A stateless script for basic arithmetic operations.

**Files:**
- `definition.json` - Script definition
- `args-add.json` - Arguments for add method
- `args-subtract.json` - Arguments for subtract method
- `args-multiply.json` - Arguments for multiply method
- `args-divide.json` - Arguments for divide method

**Usage:**
```bash
# Create script
node terminal.js or create --script examples/calculator-script/definition.json

# Invoke methods with arguments (use the CID from previous command)
node terminal.js or invoke --address <CID> --method add --args examples/calculator-script/args-add.json
node terminal.js or invoke --address <CID> --method subtract --args examples/calculator-script/args-subtract.json
node terminal.js or invoke --address <CID> --method multiply --args examples/calculator-script/args-multiply.json
node terminal.js or invoke --address <CID> --method divide --args examples/calculator-script/args-divide.json
```

### Combined Examples (Script + State Machine)

#### tictactoe
A complete tic-tac-toe implementation demonstrating the **script-centric architecture** where:
- **Script** = Game engine (holds board state, enforces rules, detects wins)
- **State Machine** = Lifecycle orchestrator (setup → playing → finished)

**Files:**
- `script-definition.json` - Game engine script with methods: initialize, makeMove, checkWinner, getBoard, resetGame, cancelGame
- `sm-definition.json` - Lifecycle state machine with states: setup, playing, finished, cancelled
- `sm-initial-data.json` - Initial data template (scriptCid injected at runtime)
- `event-start-game.json` - Start game event template
- `event-move.json` - Make move event template
- `event-reset.json` - Reset board event

**Usage:**
```bash
# Run autonomous simulation (creates script + state machine automatically)
node terminal.js simulate tictactoe --games 1

# Or run the predefined test flow
node terminal.js run --example tictactoe

# Query the results
node terminal.js query scripts --scriptId <SCRIPT_CID>
node terminal.js query state-machines --fiberId <FIBER_ID>
```

## Running Test Flows

The `run` command executes predefined test flows from examples:

```bash
# List available flows for an example
node terminal.js run --example simple-order --list

# Run the first test flow
node terminal.js run --example simple-order

# Run a specific flow by name
node terminal.js run --example simple-order --flow "Cancellation"

# Run with custom environment
node terminal.js run --example simple-order --target remote
```

Test flows automate multi-step operations like:
1. Creating scripts and state machines
2. Processing events in sequence
3. Validating state after each step
4. Tracking CIDs between steps automatically

## Interactive Mode

Use interactive mode for guided selection of examples:

```bash
node terminal.js interactive
```

The interactive mode will:
1. Show you available examples by category
2. Let you select which example to use
3. Automatically use the correct files from the example directory

## Listing Examples

```bash
# List all examples
node terminal.js list

# List only state machine examples
node terminal.js list --type state-machines

# List only script examples
node terminal.js list --type scripts

# Get detailed info about a specific example
node terminal.js list --example approval-workflow
```

## Creating New Examples

To create a new example:

1. Create a directory: `examples/my-example/`
2. Add the required files based on the type:
   - State machines: `definition.json`, `initial-data.json`, `event-*.json`
   - Scripts: `definition.json`, `args-*.json` (if methods take arguments)
3. Create an `example.json` manifest:

```json
{
  "name": "My Example",
  "description": "Description of what this example demonstrates",
  "type": "state-machine",  // or "script"
  "definition": "definition.json",
  "initialData": "initial-data.json",  // for state machines
  "events": [  // for state machines
    {
      "name": "event-name",
      "description": "What this event does",
      "file": "event-name.json",
      "from": "source-state",
      "to": "target-state"
    }
  ],
  "methods": [  // for scripts
    {
      "name": "method-name",
      "description": "What this method does",
      "args": "args-method.json"  // or null
    }
  ],
  "testFlows": [
    {
      "name": "Flow name",
      "description": "Description of the test flow",
      "steps": [
        {"action": "create", "definition": "definition.json", "initialData": "initial-data.json"},
        {"action": "processEvent", "event": "event-name.json", "expectedState": "target-state"}
      ]
    }
  ]
}
```

## Key Benefits

This example-based structure provides:

1. **Clarity** - All related files are grouped together
2. **Discoverability** - Easy to see what examples exist and what they contain
3. **Documentation** - `example.json` serves as self-documenting metadata
4. **Reusability** - Examples can be easily copied and modified
5. **Testing** - `testFlows` define expected behavior for automated testing