# Automated Market Maker (AMM) Proposal

## Overview

A decentralized exchange using constant product formula (x * y = k) with two fungible tokens and an AMM oracle for pool management. Demonstrates oracle-centric architecture with complex DeFi math and multi-oracle coordination.

## Architecture: Oracle-Centric Pattern

### Why Oracle-Centric for AMM?

**AMM Oracle** (Pool Logic Engine):
- ✅ Holds pool reserves (tokenA, tokenB amounts)
- ✅ Manages LP token supply and balances
- ✅ Performs swap calculations (constant product formula)
- ✅ Calculates liquidity math (mint/burn LP tokens)
- ✅ Atomic state updates for reserves
- ✅ Single source of truth for pool state

**State Machine** (Orchestrator):
- ✅ Coordinates multi-step operations
- ✅ Calls token oracles to move funds
- ✅ Calls AMM oracle for calculations
- ✅ Manages pool lifecycle
- ✅ Emits swap/liquidity events

**Token Oracles** (Existing Fungible):
- ✅ Two separate instances (TokenA, TokenB)
- ✅ Handle balances and transfers
- ✅ Already implemented and tested

## Components

### 1. Token Oracles (2 instances)

Use existing `e2e-test/resources/fungible.json`:
- **TokenA Oracle**: CID_A (e.g., WETH)
- **TokenB Oracle**: CID_B (e.g., USDC)
- Methods: `constructor`, `transfer`, `approve`, `transferFrom`

### 2. AMM Oracle (New)

**State Schema:**
```json
{
  "tokenA": "CID_A",
  "tokenB": "CID_B",
  "reserveA": 1000000,
  "reserveB": 2000000,
  "lpTokenSupply": 1414213,
  "lpBalances": {
    "DAG_ADDRESS_1": 500000,
    "DAG_ADDRESS_2": 914213
  },
  "feePercent": 30,
  "k": 2000000000000
}
```

**Methods:**

#### `initialize(tokenA, tokenB, initialA, initialB, provider)`
- Sets up pool with initial liquidity
- Mints initial LP tokens: `sqrt(initialA * initialB)`
- Sets k constant: `k = initialA * initialB`

#### `calculateSwap(amountIn, tokenIn)`
- Input: amount and which token (A or B)
- Applies 0.3% fee
- Calculates output using: `amountOut = (reserveOut * amountIn * 997) / (reserveIn * 1000 + amountIn * 997)`
- Returns: `{amountOut, newReserveIn, newReserveOut}`
- **Read-only** - doesn't update state

#### `executeSwap(amountIn, tokenIn, minAmountOut, trader)`
- Validates: `amountOut >= minAmountOut` (slippage protection)
- Updates reserves
- Returns: `{amountOut, priceImpact}`

#### `calculateAddLiquidity(amountA, amountB)`
- Calculates optimal amounts maintaining ratio
- Calculates LP tokens to mint: `lpMint = min(amountA * lpSupply / reserveA, amountB * lpSupply / reserveB)`
- Returns: `{amountAUsed, amountBUsed, lpMint}`
- **Read-only**

#### `executeAddLiquidity(amountA, amountB, minLpMint, provider)`
- Validates LP mint amount
- Updates reserves and LP supply
- Mints LP tokens to provider
- Returns: `{lpMint}`

#### `calculateRemoveLiquidity(lpAmount)`
- Calculates token amounts: `amountA = lpAmount * reserveA / lpSupply`
- Returns: `{amountA, amountB}`
- **Read-only**

#### `executeRemoveLiquidity(lpAmount, minAmountA, minAmountB, provider)`
- Burns LP tokens
- Updates reserves and LP supply
- Returns: `{amountA, amountB}`

#### `getPoolState()`
- Returns current reserves, LP supply, k
- **Read-only**

### 3. AMM State Machine

**States:**

```
┌─────────────┐
│ uninitialized│
└──────┬──────┘
       │ initialize_pool
       ▼
   ┌────────┐
   │ active │ (self-transitions for swaps/liquidity)
   └───┬────┘
       │ pause_pool
       ▼
   ┌────────┐
   │ paused │
   └───┬────┘
       │ resume_pool
       ▼
   (back to active)
```

**State Data:**
```json
{
  "ammOracleCid": "CID_AMM",
  "tokenAOracleCid": "CID_A",
  "tokenBOracleCid": "CID_B",
  "totalSwaps": 0,
  "totalVolume": 0,
  "pausedReason": null
}
```

## Key Transitions

### 1. `uninitialized → active` on `initialize_pool`

**Event Payload:**
```json
{
  "eventType": "initialize_pool",
  "payload": {
    "initialAmountA": 1000000,
    "initialAmountB": 2000000,
    "provider": "DAG_ADDRESS_1"
  }
}
```

**Effect:**
```json
{
  "_oracleCall": {
    "cid": "CID_AMM",
    "method": "initialize",
    "args": {
      "tokenA": {"var": "state.tokenAOracleCid"},
      "tokenB": {"var": "state.tokenBOracleCid"},
      "initialA": {"var": "event.initialAmountA"},
      "initialB": {"var": "event.initialAmountB"},
      "provider": {"var": "event.provider"}
    }
  },
  "_outputs": [{
    "outputType": "pool_initialized",
    "data": {
      "lpMinted": {"var": "oracleResult.lpMinted"},
      "initialK": {"var": "oracleResult.k"}
    }
  }]
}
```

**Note:** In real implementation, would need token transfers first. See "Coordination Challenge" below.

### 2. `active → active` on `swap` (self-transition)

**Event Payload:**
```json
{
  "eventType": "swap",
  "payload": {
    "trader": "DAG_ADDRESS_2",
    "tokenIn": "A",
    "amountIn": 1000,
    "minAmountOut": 1950,
    "idempotencyKey": "swap-123"
  }
}
```

**Guard:**
```json
{
  "and": [
    {"===": [{"var": "scripts.CID_AMM.state.status"}, "active"]},
    {"in": [{"var": "event.tokenIn"}, ["A", "B"]]},
    {">": [{"var": "event.amountIn"}, 0]}
  ]
}
```

**Effect Steps:**
1. Call AMM oracle `calculateSwap` to get expected output
2. Transfer `amountIn` from trader to pool (via token oracle)
3. Call AMM oracle `executeSwap` to update reserves
4. Transfer `amountOut` from pool to trader (via token oracle)
5. Emit swap event

**Multi-Step Challenge:** State machine effects are single expressions. Solution approaches:
- **Option A**: AMM oracle handles token transfers internally (requires oracle→oracle calls)
- **Option B**: Use structured output to trigger follow-up transitions
- **Option C**: State machine transition sequences (swap_initiated → swap_completed)

### 3. `active → active` on `add_liquidity`

**Event Payload:**
```json
{
  "eventType": "add_liquidity",
  "payload": {
    "provider": "DAG_ADDRESS_3",
    "amountA": 10000,
    "amountB": 20000,
    "minLpMint": 13500,
    "idempotencyKey": "liq-456"
  }
}
```

**Effect Flow:**
1. Calculate optimal amounts (AMM oracle)
2. Transfer tokens from provider to pool
3. Execute add liquidity (AMM oracle)
4. Mint LP tokens to provider
5. Emit liquidity_added event

### 4. `active → active` on `remove_liquidity`

**Event Payload:**
```json
{
  "eventType": "remove_liquidity",
  "payload": {
    "provider": "DAG_ADDRESS_1",
    "lpAmount": 5000,
    "minAmountA": 3500,
    "minAmountB": 7000,
    "idempotencyKey": "rem-789"
  }
}
```

**Effect Flow:**
1. Calculate token amounts (AMM oracle)
2. Burn LP tokens from provider
3. Execute remove liquidity (AMM oracle)
4. Transfer tokens from pool to provider
5. Emit liquidity_removed event

## Division of Responsibilities

### AMM Oracle (Pool Engine)

**State:**
- ✅ Pool reserves (reserveA, reserveB)
- ✅ LP token supply and balances
- ✅ Constant product k
- ✅ Fee configuration

**Logic:**
- ✅ Swap math (constant product formula)
- ✅ Liquidity calculations (add/remove)
- ✅ LP token minting/burning
- ✅ Price impact calculations
- ✅ Slippage validation

**Why Oracle:**
- Complex mathematical operations
- Atomic reserve updates
- LP token ledger management
- Deterministic calculations

### State Machine (Orchestrator)

**State:**
- ✅ Pool lifecycle status (uninitialized → active → paused)
- ✅ Oracle CID references
- ✅ Aggregate metrics (total swaps, volume)
- ✅ Pause reason

**Logic:**
- ✅ Multi-step coordination
- ✅ Guard conditions (pool active, valid inputs)
- ✅ Structured output emission
- ✅ Lifecycle management
- ✅ Access control (who can pause)

**Why State Machine:**
- Lifecycle orchestration
- Event sequencing
- Business rules (pause conditions)
- External system integration

## Coordination Challenge: Token Transfers

**Problem:** Swaps require multiple oracle calls in sequence:
1. Transfer tokenIn from user to pool (TokenA oracle)
2. Calculate and update reserves (AMM oracle)
3. Transfer tokenOut from pool to user (TokenB oracle)

**Current Limitation:** State machine effects are single expressions with `_oracleCall`.

**Proposed Solutions:**

### Option 1: Oracle-to-Oracle Calls (Cleanest)

AMM oracle methods accept token transfer instructions and internally trigger token oracle calls:

```json
{
  "method": "executeSwap",
  "args": {
    "amountIn": 1000,
    "tokenIn": "A",
    "trader": "DAG_ADDRESS",
    "tokenOracleA": "CID_A",
    "tokenOracleB": "CID_B"
  }
}
```

AMM oracle's `_result` includes:
```json
{
  "_oracleCalls": [
    {"cid": "CID_A", "method": "transferFrom", "args": {...}},
    {"cid": "CID_B", "method": "transfer", "args": {...}}
  ]
}
```

**Implementation:** Requires oracle execution to support chained oracle calls.

### Option 2: Multi-State Transitions (More Complex)

Break swap into multiple states:

```
active → swap_pending → swap_validated → swap_completed → active
```

Each transition handles one oracle call. Uses structured outputs to trigger next transition.

**Drawback:** More complex, more gas, more states.

### Option 3: Pre-Approval Pattern (Simplest for Testing)

Users pre-approve AMM pool address on token oracles. AMM oracle uses `transferFrom` directly.

**State Machine Effect:**
```json
{
  "_oracleCall": {
    "cid": "CID_AMM",
    "method": "executeSwapWithTransfers",
    "args": {
      "trader": {"var": "event.trader"},
      "amountIn": {"var": "event.amountIn"},
      "tokenIn": {"var": "event.tokenIn"},
      "minAmountOut": {"var": "event.minAmountOut"}
    }
  }
}
```

AMM oracle internally:
1. Calls `tokenIn.transferFrom(trader, pool, amountIn)`
2. Updates reserves
3. Calls `tokenOut.transfer(pool, trader, amountOut)`

**For Unit Test:** Use Option 3 initially. Can evolve to Option 1 if oracle-to-oracle becomes available.

## Example Flow: Complete Swap

**Setup:**
1. Alice approves AMM pool on TokenA oracle
2. Pool initialized with reserves: 1000 TokenA, 2000 TokenB

**Swap:**
1. Alice triggers `swap` event: 10 TokenA → ? TokenB
2. State machine guard checks: pool active, valid amounts
3. State machine calls AMM oracle `executeSwapWithTransfers`:
   - AMM calls TokenA oracle: `transferFrom(Alice, Pool, 10)`
   - AMM calculates: amountOut = (2000 * 10 * 997) / (1000 * 1000 + 10 * 997) ≈ 19.74 TokenB
   - AMM updates reserves: reserveA = 1010, reserveB = 1980.26
   - AMM calls TokenB oracle: `transfer(Pool, Alice, 19.74)`
   - AMM returns: `{amountOut: 19.74, priceImpact: 0.13%}`
4. State machine emits `swap_completed` output
5. State machine increments totalSwaps counter

## Test Implementation Plan

### Phase 1: Basic Setup
1. Create two token oracle instances (TokenA, TokenB)
2. Initialize with balances for Alice, Bob, Pool
3. Create AMM oracle with `initialize` method
4. Create state machine with `initialize_pool` transition
5. Test: Initialize pool successfully

### Phase 2: Swap Logic
1. Implement AMM oracle `calculateSwap` method
2. Implement AMM oracle `executeSwapWithTransfers` method
3. Add state machine `swap` transition
4. Test: Swap TokenA → TokenB with correct amounts
5. Test: Price impact calculation
6. Test: Slippage protection (minAmountOut)
7. Test: Invalid swap rejected (insufficient liquidity)

### Phase 3: Liquidity
1. Implement AMM oracle `calculateAddLiquidity` method
2. Implement AMM oracle `executeAddLiquidity` method
3. Add state machine `add_liquidity` transition
4. Test: Add liquidity and receive LP tokens
5. Test: Maintain k constant after liquidity add
6. Implement `calculateRemoveLiquidity` and `executeRemoveLiquidity`
7. Test: Remove liquidity and burn LP tokens

### Phase 4: Advanced Features
1. Add state machine pause/resume transitions
2. Test: Swaps rejected when paused
3. Add fee accumulation tracking
4. Test: Multiple swaps in sequence
5. Test: Large swap (high price impact)

## Key Features Demonstrated

| Feature | Description |
|---------|-------------|
| **Multi-Oracle Coordination** | 3 oracles working together (2 tokens + AMM) |
| **Oracle-Centric DeFi Logic** | Complex math in oracle, simple orchestration in SM |
| **Constant Product Formula** | x * y = k automated market maker |
| **LP Token Management** | Mint/burn mechanics in oracle state |
| **Self-Transitions** | Multiple swaps in `active` state |
| **Slippage Protection** | Guards validate minAmountOut |
| **Price Impact Calculation** | Oracle computes % change in reserves |
| **Structured Outputs** | Swap/liquidity events with details |
| **Read-Only Methods** | Calculate before execute pattern |

## Math Reference

### Constant Product Formula

**Base Formula:** `x * y = k` (constant)

**Swap Calculation (with 0.3% fee):**
```
amountInWithFee = amountIn * 997
newReserveIn = reserveIn + amountInWithFee / 1000
newReserveOut = k / newReserveIn
amountOut = reserveOut - newReserveOut
```

**Add Liquidity:**
```
ratioA = amountA / reserveA
ratioB = amountB / reserveB
ratio = min(ratioA, ratioB)
lpMint = lpSupply * ratio
```

**Remove Liquidity:**
```
shareOfPool = lpAmount / lpSupply
amountA = reserveA * shareOfPool
amountB = reserveB * shareOfPool
```

**Price:**
```
price = reserveB / reserveA
priceImpact = abs(newPrice - oldPrice) / oldPrice * 100
```

## JSON Logic Considerations

**Supported:**
- ✅ Arithmetic: `+`, `-`, `*`, `/`
- ✅ Comparisons: `>`, `>=`, `<`, `<=`, `===`
- ✅ Conditionals: `if`
- ✅ Variable access: `{"var": "path.to.value"}`
- ✅ Merge operations for state updates
- ✅ **FloatValue**: Decimal arithmetic available in JLVM
- ✅ **PowOp**: `{"pow": [x, 0.5]}` for square root calculations
- ✅ **Approve/TransferFrom**: Standard ERC-20 pattern for token coordination

**Initial Liquidity LP Token Calculation:**
```json
{
  "lpMint": {
    "pow": [
      {"*": [{"var": "args.initialA"}, {"var": "args.initialB"}]},
      0.5
    ]
  }
}
```

This gives us `sqrt(initialA * initialB)` directly!

**No Implementation Blockers:** All necessary operations are available. AMM math can be implemented directly in JSON Logic without workarounds or approximations.

## Next Steps

1. Review this proposal
2. Decide on oracle-to-oracle call strategy
3. Implement AMM oracle JSON Logic
4. Create test suite in Scala
5. Add to examples documentation

Would you like me to proceed with implementing the AMM oracle JSON or create the test suite scaffolding?