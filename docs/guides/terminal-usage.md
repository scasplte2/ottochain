# Ottochain Terminal

Interactive CLI for testing Ottochain state machines and scripts. This terminal provides a comprehensive testing framework with transaction generation, signing, sending, and validation.

## Table of Contents

- [Overview](#overview)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Commands](#commands)
  - [State Machine Commands](#state-machine-commands)
  - [Oracle Commands](#oracle-commands)
  - [Helper Commands](#helper-commands)
- [Global Options](#global-options)
- [Examples](#examples)
- [Resource Files](#resource-files)
- [CI Integration](#ci-integration)

## Overview

The Ottochain Terminal provides a command-line interface for:

- **State Machine Management**: Create, process events, and archive state machine fibers
- **Script Management**: Create and invoke scripts
- **Full Testing Cycle**: Fetch initial state, send transactions, and validate final state
- **CI/CD Ready**: Subcommand-based architecture suitable for automated testing

## Installation

```bash
cd e2e-test
npm install
```

## Quick Start

### List Available Resources

```bash
npm run terminal:list
```

### Create a State Machine Fiber

```bash
npm run terminal -- state-machine create \
  --definition resources/state-machines/simple-order.json \
  --initialData resources/state-machines/initial-data-order.json
```

### Process an Event

```bash
npm run terminal -- state-machine process-event \
  --address <CID-from-previous-step> \
  --event resources/state-machines/events/confirm-order.json \
  --expectedState confirmed
```

### Create an Oracle

```bash
npm run terminal -- oracle create \
  --oracle resources/oracles/calculator-oracle.json
```

### Invoke an Oracle

```bash
npm run terminal -- oracle invoke \
  --address <CID-from-create-step> \
  --method add \
  --args resources/oracles/invoke-args-add.json \
  --expectedResult '15'
```

## Commands

### State Machine Commands

#### `state-machine create` (alias: `sm create`)

Create a new state machine fiber.

**Required Options:**
- `--definition <path>`: Path to state machine definition JSON file
- `--initialData <path>`: Path to initial data JSON file

**Optional:**
- `--parentFiberId <uuid>`: Parent fiber ID for hierarchical state machines

**Example:**
```bash
node terminal.js state-machine create \
  --definition resources/state-machines/approval-workflow.json \
  --initialData resources/state-machines/initial-data-approval.json
```

#### `state-machine process-event` (alias: `sm event`)

Process an event on an existing state machine fiber.

**Required Options:**
- `--event <path>`: Path to event JSON file

**Optional:**
- `--expectedState <state>`: Expected resulting state for validation

**Example:**
```bash
node terminal.js sm event \
  --address 7065df56-2eba-49bd-8d55-69c3ead89e05 \
  --event resources/state-machines/events/ship-order.json \
  --expectedState shipped
```

#### `state-machine archive` (alias: `sm archive`)

Archive a state machine fiber.

**Example:**
```bash
node terminal.js sm archive \
  --address 7065df56-2eba-49bd-8d55-69c3ead89e05
```

### Oracle Commands

#### `oracle create` (alias: `or create`)

Create a new script.

**Required Options:**
- `--oracle <path>`: Path to oracle definition JSON file

**Example:**
```bash
node terminal.js oracle create \
  --oracle resources/oracles/counter-oracle.json
```

#### `oracle invoke` (alias: `or invoke`)

Invoke a method on an existing script.

**Required Options:**
- `--method <name>`: Method name to invoke

**Optional:**
- `--args <path>`: Path to arguments JSON file
- `--expectedResult <json>`: Expected result for validation

**Example:**
```bash
node terminal.js or invoke \
  --address abc123... \
  --method increment \
  --expectedResult '{"count":1}'
```

### Helper Commands

#### `list`

List available example resources.

**Optional:**
- `--type <type>`: Filter by type: `state-machines`, `oracles`, or `events`

**Examples:**
```bash
node terminal.js list
node terminal.js list --type oracles
```

## Global Options

These options apply to all commands:

### Resource Options
- `--address <uuid>`: Use a specific UUID for the resource (default: random UUID)
- `--wallets <list>`: Comma-separated signers: `alice,bob,charlie,diane,james` (default: `alice`)

### Environment Options
- `--target <env>`: Target environment: `local`, `remote`, or `ci` (default: `local`)

### Operation Mode Options
- `--mode <mode>`: Operation mode (default: `send+validate`)
  - `send`: Only send transaction (fire-and-forget)
  - `validate`: Only validate existing state (no transaction sent)
  - `send+validate`: Full cycle - send and validate

### Validation Options
- `--waitTime <N>`: Seconds to wait after sending before validation (default: `5`)
- `--retryDelay <N>`: Seconds between validation retries (default: `3`)
- `--maxRetries <N>`: Maximum validation retry attempts (default: `5`)

## Examples

### Full Workflow: Order Fulfillment

#### 1. Create Order Fiber
```bash
CID=$(uuidgen)
node terminal.js sm create \
  --address $CID \
  --definition resources/state-machines/simple-order.json \
  --initialData resources/state-machines/initial-data-order.json
```

#### 2. Confirm Order
```bash
node terminal.js sm event \
  --address $CID \
  --event resources/state-machines/events/confirm-order.json \
  --expectedState confirmed
```

#### 3. Ship Order
```bash
node terminal.js sm event \
  --address $CID \
  --event resources/state-machines/events/ship-order.json \
  --expectedState shipped
```

### Multi-Signer Transaction

```bash
node terminal.js oracle create \
  --oracle resources/oracles/calculator-oracle.json \
  --wallets alice,bob,charlie
```

### Fire-and-Forget Mode (CI)

```bash
node terminal.js sm create \
  --definition resources/state-machines/simple-order.json \
  --initialData resources/state-machines/initial-data-order.json \
  --mode send \
  --target ci
```

### Validation Only (Post-Deployment Check)

```bash
node terminal.js oracle invoke \
  --address abc123... \
  --method add \
  --args resources/oracles/invoke-args-add.json \
  --mode validate
```

## Resource Files

### State Machine Definition Structure

```json
{
  "states": {
    "state_id": {
      "id": { "value": "state_id" },
      "isFinal": false,
      "metadata": null
    }
  },
  "initialState": { "value": "state_id" },
  "transitions": [
    {
      "from": { "value": "state_a" },
      "to": { "value": "state_b" },
      "eventType": { "value": "event_name" },
      "guard": { "==": [1, 1] },
      "effect": { "merge": [{ "var": "data" }, { "var": "payload" }] },
      "dependencies": []
    }
  ],
  "metadata": {
    "name": "MachineName",
    "description": "Description"
  }
}
```

### Event Structure

```json
{
  "eventType": { "value": "event_name" },
  "payload": {
    "field1": "value1",
    "field2": "value2"
  },
  "idempotencyKey": null
}
```

### Oracle Definition Structure

```json
{
  "scriptProgram": {
    "if": [
      { "==": [{ "var": "method" }, "methodName"] },
      { "return": "expression" },
      { "error": "Unknown method" }
    ]
  },
  "initialState": {
    "field": "value"
  },
  "accessControl": {
    "Public": {}
  }
}
```

## CI Integration

The terminal is designed for CI/CD pipelines:

### GitHub Actions Example

```yaml
- name: Test State Machine Creation
  run: |
    cd e2e-test
    npm run terminal -- sm create \
      --definition resources/state-machines/simple-order.json \
      --initialData resources/state-machines/initial-data-order.json \
      --target ci \
      --mode send+validate \
      --maxRetries 10
```

### Exit Codes

- `0`: Success
- `1`: Failure (transaction or validation error)

## Troubleshooting

### Common Issues

**Transaction fails to send:**
- Check that the metagraph is running (use `--target local/remote/ci` appropriately)
- Verify wallet configuration in environment

**Validation times out:**
- Increase `--maxRetries` and `--retryDelay`
- Check metagraph health

**Resource file not found:**
- Use absolute paths or paths relative to `e2e-test/` directory
- Run `npm run terminal:list` to see available resources

### Debug Mode

For verbose output, examine the console logs which show:
- Client request JSON (signed transaction)
- Node responses
- Validation attempts and state comparisons

## Development

### Adding New Resources

1. **State Machines**: Add to `resources/state-machines/`
2. **Events**: Add to `resources/state-machines/events/`
3. **Oracles**: Add to `resources/oracles/`

### Extending Operations

To add new operations:

1. Create generator/validator in `lib/state-machine/` or `lib/oracle/`
2. Add command to `terminal.js`
3. Update this README

## Architecture

```
terminal.js (main CLI)
├── lib/state-machine/
│   ├── createFiber.js (generator + validator)
│   ├── processEvent.js (generator + validator)
│   └── archiveFiber.js (generator + validator)
├── lib/oracle/
│   ├── createOracle.js (generator + validator)
│   └── invokeOracle.js (generator + validator)
├── lib/ (shared utilities)
│   ├── generateProof.js
│   ├── generateWallet.js
│   ├── sendDataTransaction.js
│   └── metagraphEnv.js
└── resources/
    ├── state-machines/
    ├── oracles/
    └── state-machines/events/
```

## License

ISC