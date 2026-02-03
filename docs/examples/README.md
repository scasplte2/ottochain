# State Machine Examples

This directory contains comprehensive real-world examples demonstrating the capabilities of the OttoChain state machine framework. Each example showcases different patterns, complexities, and use cases for building JSON-encoded deterministic state machines.

## Table of Contents

1. [Overview](#overview)
2. [Example Comparison Matrix](#example-comparison-matrix)
3. [Examples](#examples)
   - [Fuel Logistics with GPS Tracking](#fuel-logistics-with-gps-tracking)
   - [Clinical Trial Management](#clinical-trial-management)
   - [Real Estate Lifecycle](#real-estate-lifecycle)
   - [Riverdale Economy](#riverdale-economy)
   - [Tic-Tac-Toe Game](#tic-tac-toe-game)
4. [Key Patterns Demonstrated](#key-patterns-demonstrated)
5. [Getting Started](#getting-started)

---

## Overview

These examples demonstrate that OttoChain's state machine infrastructure can model complex, real-world workflows entirely through JSON configuration without custom state machine code. Each example is fully implemented, tested, and demonstrates specific advanced features.

### What You'll Find

- **Complete workflows** from simple games to complex multi-party systems
- **JSON-encoded machines** showing zero-code deployment patterns
- **Advanced features** like cross-machine triggers, parent-child spawning, and broadcast coordination
- **Real-world patterns** applicable to logistics, healthcare, finance, and governance
- **Test implementations** demonstrating verification approaches

---

## Example Comparison Matrix

| Feature | Tic-Tac-Toe | Fuel Logistics | Clinical Trial | Real Estate | Riverdale Economy |
|---------|-------------|---------------|----------------|-------------|------------------|
| **State Machines** | 1 | 4 | 6 | 8 | 6 types (17 instances) |
| **Total States** | 4 | 23 | 47 | 58 | 37 (across types) |
| **Participants** | 2 | 5 | 6 | 8 | 13 active participants |
| **Complexity** | ⭐ Simple | ⭐⭐ Moderate | ⭐⭐⭐ Complex | ⭐⭐⭐ Complex | ⭐⭐⭐⭐ Very Complex |
| **Script Pattern** | ✅ Yes (game state) | ❌ No | ❌ No | ❌ No | ❌ No |
| **Cross-Machine Triggers** | ❌ No | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes (15+ types) |
| **Multiple Guards** | ❌ No | ❌ No | ✅ Yes (5 outcomes) | ✅ Yes (3 outcomes) | ✅ Yes |
| **Bi-directional Transitions** | ❌ No | ❌ No | ✅ Yes | ✅ Yes | ❌ No |
| **Broadcast Triggers** | ❌ No | ❌ No | ❌ No | ❌ No | ✅ Yes (tax, rates, stress tests) |
| **Parent-Child Spawning** | ❌ No | ❌ No | ❌ No | ❌ No | ✅ Yes (auctions) |
| **Self-Transitions** | ✅ Yes (playing) | ❌ No | ❌ No | ✅ Yes (3 types) | ✅ Yes (multiple) |
| **Structured Outputs** | ✅ Yes (game_completed) | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes |
| **Use Case** | Game/Tutorial | Supply Chain | Healthcare | Real Estate/Finance | Economic Ecosystem |

---

## Examples

### Fuel Logistics with GPS Tracking

**Complexity**: ⭐⭐ Moderate
**State Machines**: 4
**Participants**: 5

A supply chain workflow demonstrating fuel delivery contracts with real-time GPS tracking, quality inspections, and supplier approval processes.

#### Key Features

- GPS location tracking with geofencing violations
- Quality inspection workflows with pass/fail gates
- Supplier approval with vetting process
- Contract lifecycle from draft through completion
- Cross-machine triggers for shipment tracking

#### Workflow Overview

```
Supplier Approval → Contract Creation → GPS Tracking →
Quality Inspection → Delivery → Payment
```

**[View Detailed Documentation →](./fuel-logistics.md)**

---

### Clinical Trial Management

**Complexity**: ⭐⭐⭐ Complex
**State Machines**: 6
**Participants**: 6

A Phase III clinical trial workflow demonstrating multi-party coordination, adverse event handling, and regulatory compliance with multiple guards on single events.

#### Key Features

- **Multiple guards on same event** (5 outcomes from lab results)
- **Bi-directional transitions** (pause/resume workflows)
- Cross-machine dependencies (4 machines checked for contingencies)
- Adverse event monitoring with escalation
- Regulatory oversight and approval
- Insurance claims processing

#### Workflow Overview

```
Recruitment → Enrollment → Active Trial → Lab Processing →
Adverse Event Monitoring → Regulatory Review → Completion
```

**[View Detailed Documentation →](./clinical-trial.md)**

---

### Real Estate Lifecycle

**Complexity**: ⭐⭐⭐ Complex
**State Machines**: 8
**Participants**: 8

A complete real estate transaction from listing through purchase, mortgage servicing, potential default, foreclosure, and REO (bank-owned property).

#### Key Features

- **Multiple self-transitions on same state** (payment/servicing/sale)
- **Bi-directional recovery paths** (delinquency cure)
- Complex multi-machine guards (4 contingencies)
- Conditional earnest money disbursement
- Mortgage servicing with secondary market transfers
- Foreclosure and REO workflows
- Long-term asset lifecycle management

#### Workflow Overview

```
Listing → Offer → Contract → Contingencies → Closing → Ownership
                                                         ↓
                                                    Mortgage
                                                         ↓
                                    (If default) → Foreclosure → REO → Re-list
```

**[View Detailed Documentation →](./real-estate.md)**

---

### Riverdale Economy

**Complexity**: ⭐⭐⭐⭐ Very Complex
**State Machine Types**: 6
**Machine Instances**: 17 (+ dynamic children)
**Participants**: 13

A complete economic ecosystem simulation with manufacturing, retail, banking, consumers, monetary policy, and governance demonstrating the most advanced features.

#### Key Features

- **17 machine instances** across 6 types
- **Broadcast triggers** (tax collection to 11 machines, rate policy to 3 banks)
- **Parent-child spawning** (dynamic auction machines)
- **System-wide coordination** (stress testing, emergency lending)
- Complex dependency graphs
- Economic cascades (loan defaults affecting entire system)
- Computed payloads (interest rate calculations)
- Multi-party marketplaces

#### Economic Flows

```
Raw Materials → Manufacturer → Retailer → Consumer
                                          ↓
                                      Bank Loans
                                          ↓
                                   Federal Reserve (Monetary Policy)
                                          ↓
                                      Governance (Tax Collection)
```

**[View Detailed Documentation →](./riverdale-economy.md)**

---

### Tic-Tac-Toe Game

**Complexity**: ⭐ Simple
**State Machines**: 1
**Participants**: 2
**Total States**: 4

A two-player game demonstrating the **oracle-centric architecture** pattern where the script acts as the game engine and the state machine orchestrates the game lifecycle.

#### Architecture: Oracle-Centric Pattern

**Script** (Game Engine):
- Holds complete game state (board, moves, players)
- Enforces game rules and move validation
- Detects win/draw conditions automatically
- Maintains move history
- 6 methods: initialize, makeMove, checkWinner, getBoard, resetGame, cancelGame

**State Machine** (Lifecycle Orchestrator):
- Manages game phases: setup → playing → finished/cancelled
- Calls oracle methods via `_oracleCall`
- Guards check oracle state for transitions
- Self-transitions during gameplay
- Minimal state - delegates game logic to oracle

This separation demonstrates a key architectural pattern: **oracle holds state and logic, state machine orchestrates lifecycle**.

#### State Machine States

4 states managing game lifecycle:
- `setup` - Initial state, waiting for players
- `playing` - Game active (self-transitions during moves)
- `finished` - Game completed (win/draw) - final state
- `cancelled` - Game cancelled - final state

#### Key Features

- **Oracle-Centric Pattern** - Oracle is the single source of truth for game state
- **Oracle Method Dispatch** - Single script with 6 methods handling all game logic
- **Validation in Oracle** - Move validation happens in oracle, not state machine
- **Self-Transitions** - State machine stays in `playing` during move sequences
- **Multiple Guards** - Same event (`make_move`) transitions to different states based on oracle status
- **Win Detection** - Oracle checks all 8 winning patterns deterministically
- **Reset Support** - Clear board for new round without recreating machines
- **Structured Outputs** - Emit `game_completed` output on finish

#### Workflow

```
setup → playing (self-transitions) → finished/cancelled
         │  ↑
         │  └── make_move events ───────┘
         │
         └──→ _oracleCall(makeMove)
              - Oracle validates move
              - Updates board state
              - Checks win/draw
              - Returns result
```

#### Why Start Here?

- **Foundational pattern** - Oracle-centric architecture scales to complex logic
- **Familiar domain** - Everyone understands tic-tac-toe rules
- **Clean separation** - State vs. lifecycle management clearly delineated
- **Core concepts** - Oracle calls, guards, self-transitions, validation patterns
- **Complete example** - Shows oracle creation, invocation, and state updates
- **Well tested** - Full test suite demonstrating all scenarios

**[View Detailed Documentation →](./tictactoe.md)**

---

## Key Patterns Demonstrated

### Cross-Machine Triggers

**What**: One machine's transition triggers an event on another machine.

**Examples**:
- Fuel: GPS tracker triggers contract shipment events
- Clinical: Visit completion triggers lab sample processing
- Real Estate: Property closing triggers mortgage activation
- Riverdale: Supply chain (retailer → manufacturer → retailer)

**Use Cases**: Coordinating multi-party workflows, supply chains, financial transactions

---

### Multiple Guards on Same Event

**What**: Single event can transition to multiple different states based on complex conditions.

**Examples**:
- Clinical Trial: Lab `complete` event → 5 possible outcomes (passed, questionable, failed, critical, retest_required) based on quality, contamination, and biomarker levels
- Real Estate: Inspection `approve` event → 3 outcomes (passed, passed_with_repairs, failed) based on issue count and repair agreement

**Use Cases**: Complex decision trees, quality assessment, risk evaluation

---

### Bi-directional Transitions

**What**: Ability to transition back and forth between states for pause/resume or error recovery.

**Examples**:
- Clinical Trial: Patient `active` ⇄ `paused`, `adverse_event` → `active` (if resolved)
- Real Estate: Mortgage `current` ⇄ `delinquent_30` ⇄ `delinquent_60` (cure with payment)

**Use Cases**: Pause/resume workflows, error recovery, delinquency management

---

### Broadcast Triggers

**What**: One machine triggers events on multiple machines simultaneously.

**Examples**:
- Riverdale: Governance tax collection triggers `pay_taxes` on 11 machines (4 manufacturers, 4 retailers, 3 consumers)
- Riverdale: Federal Reserve rate adjustment triggers `rate_adjustment` on 3 banks
- Riverdale: Stress test initiation triggers `fed_directive` on all banks

**Use Cases**: System-wide policy changes, regulatory coordination, mass notifications

---

### Parent-Child Spawning

**What**: Dynamic creation of child state machines with independent lifecycles.

**Examples**:
- Riverdale: Consumer listing item spawns Auction child machine
- Child completes lifecycle: `listed` → `bid_received` → `sold`
- Child completion triggers parent's `sale_completed` event

**Use Cases**: Dynamic marketplaces, temporary workflows, auction systems, job scheduling

---

### Self-Transitions (Same State, Different Effects)

**What**: Events that don't change state but modify internal data.

**Examples**:
- Real Estate: Mortgage `current` → `current` with three different events:
  - `make_payment`: Updates balance, increments paymentCount
  - `transfer_servicing`: Changes servicer, preserves balance
  - `sell_loan`: Changes owner, records sale price
- Riverdale: Manufacturer states allowing `pay_taxes` without state change

**Use Cases**: Continuous operations, payment processing, servicing transfers

---

### Structured Outputs

**What**: Formatted output generation for external systems (webhooks, emails, reports).

**Examples**:
- Fuel: Geofencing alerts, shipment notifications
- Clinical Trial: Regulatory reports, enrollment webhooks
- Real Estate: Legal notices (foreclosure), payment disbursements
- Riverdale: Credit reports, audit findings, regulatory notices

**Use Cases**: External system integration, notifications, compliance reporting

---

## Getting Started

### Choose Your Starting Point

1. **New to State Machines?** → Start with [Tic-Tac-Toe](./tictactoe.md)
2. **Supply Chain Use Cases?** → Check out [Fuel Logistics](./fuel-logistics.md)
3. **Healthcare/Compliance?** → Explore [Clinical Trial](./clinical-trial.md)
4. **Financial Services?** → Study [Real Estate](./real-estate.md)
5. **Complex Ecosystems?** → Dive into [Riverdale Economy](./riverdale-economy.md)

### Common Learning Path

```
Tic-Tac-Toe (basics)
    ↓
Fuel Logistics (cross-machine triggers, basic workflows)
    ↓
Clinical Trial (multiple guards, bi-directional transitions)
    ↓
Real Estate (self-transitions, complex guards, long-term lifecycle)
    ↓
Riverdale Economy (broadcast triggers, parent-child spawning, full ecosystem)
```

### Test Locations

All examples have comprehensive test implementations:

- **Fuel Logistics**: `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/FuelLogisticsStateMachineSuite.scala`
- **Clinical Trial**: `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/ClinicalTrialStateMachineSuite.scala`
- **Real Estate**: `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/RealEstateStateMachineSuite.scala`
- **Riverdale Economy**: `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/RiverdaleEconomyStateMachineSuite.scala`
- **Tic-Tac-Toe**: `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/TicTacToeGameSuite.scala`

### Key Takeaways

1. **Zero-Code Deployment**: All machines are JSON-encoded, no custom code required
2. **Composability**: Mix and match patterns across different domains
3. **Versioning**: Workflow rules can evolve without code changes
4. **Auditability**: Complete machine logic visible in JSON definitions
5. **Portability**: Definitions transferable across environments
6. **Validation**: Schema-validated at startup

---

## Contributing

When adding new examples:

1. Follow the established documentation structure
2. Include state diagrams (both `.dot` source and rendered `.png`)
3. Provide comprehensive test implementations
4. Document key features and patterns demonstrated
5. Add to comparison matrices in this README
6. Include workflow examples and sequence diagrams

For questions or suggestions, please open an issue or submit a pull request.

---

## Additional Resources

- **[State Machine Design Guide](../guides/state-machine-design.md)** - Best practices for designing state machines
- **[API Reference](../reference/api-reference.md)** - HTTP API documentation
- **[Architecture Overview](../reference/architecture.md)** - System architecture
- **[Deployment Guide](../guides/deployment.md)** - Deploying state machine workflows

---

*All examples are fully implemented, tested, and production-ready. The JSON-encoded approach demonstrates that complex, real-world workflows can be modeled without writing custom state machine code.*