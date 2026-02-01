# Tic-Tac-Toe Game

Complete tic-tac-toe implementation using OttoChain script oracle + state machine, demonstrating the **oracle-centric architecture** pattern.

## Table of Contents

1. [Overview](#overview)
2. [Key Features Demonstrated](#key-features-demonstrated)
3. [Architecture](#architecture)
4. [Script Oracle Design](#script-oracle-design)
5. [State Machine Design](#state-machine-design)
6. [Test Location](#test-location)
7. [Cell Numbering](#cell-numbering)

---

## Overview

### Workflow Sequence

![Tic-Tac-Toe Sequence Diagram](../../diagrams/tictactoe-sequence.png)

This example demonstrates the **oracle-centric architecture** where:
- **Script Oracle** = Game engine (holds board, enforces rules, detects wins)
- **State Machine** = Lifecycle orchestrator (setup → playing → finished/cancelled)

The game provides a simple but complete example of:
- Stateful script oracles maintaining game state
- State machines calling oracle methods via `_oracleCall`
- Guards checking oracle state for transitions
- Self-transitions for ongoing gameplay
- Multiple final states (finished vs cancelled)

### Why Oracle Holds State?

- ✅ **Deterministic rules**: Oracle enforces valid moves, win detection
- ✅ **Single source of truth**: Board state in one place
- ✅ **Atomic operations**: makeMove updates board + checks winner in one call
- ✅ **Simple state machine**: Just lifecycle, no game logic

---

## Key Features Demonstrated

| Feature | Description |
|---------|-------------|
| **Script Oracle Pattern** | Oracle holds all game state (board, players, history) |
| **Oracle Method Dispatch** | Single script with 6 methods: initialize, makeMove, checkWinner, getBoard, resetGame, cancelGame |
| **Validation Logic** | Oracle validates moves (turn, cell bounds, occupied check) |
| **Win Detection** | Deterministic check of all 8 winning patterns |
| **Self-Transitions** | State machine stays in `playing` during moves |
| **Multiple Guards** | Same event type (make_move) transitions to different states based on oracle status |
| **Reset Support** | Clear board without recreating oracle/machine |
| **Structured Outputs** | Emit `game_completed` output on win/draw |

---

## Architecture

```
┌──────────────────────────┐
│   State Machine          │
│   (Lifecycle)            │
│                          │
│  setup → playing →       │
│          ↓   ↑           │
│      finished/cancelled  │
└──────────┬───────────────┘
           │ _oracleCall
           ▼
┌──────────────────────────┐
│   Script Oracle          │
│   (Game Engine)          │
│                          │
│  • Board [9 cells]       │
│  • Turn (X/O)            │
│  • Win detection         │
│  • Move validation       │
└──────────────────────────┘
```

### State Machine States

```
    ┌───────┐
    │ setup │ (initial state)
    └───┬───┘
        │ start_game
        ▼
   ┌─────────┐  make_move (self-transition)
   │ playing │ ◄──────────┐
   └─┬─┬─┬───┘            │
     │ │ │ └──────────────┘
     │ │ │
     │ │ │ make_move (win/draw)
     │ │ ├────────────► ┌──────────┐
     │ │ │               │ finished │ (final)
     │ │ │               └──────────┘
     │ │ │
     │ │ └─ reset_board (stays in playing)
     │ │
     │ └─ cancel_game
     │    └────────────► ┌───────────┐
     │                    │ cancelled │ (final)
     └────────────────────┘
```

---

## Script Oracle Design

### Oracle State Structure

```json
{
  "board": [null, null, "X", null, "O", null, null, null, null],
  "currentTurn": "X",
  "moveCount": 3,
  "status": "InProgress",
  "playerX": "DAG7L1...",
  "playerO": "DAG3X2...",
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "moveHistory": [
    {"player": "X", "cell": 2, "moveNum": 1},
    {"player": "O", "cell": 4, "moveNum": 2},
    {"player": "X", "cell": 0, "moveNum": 3}
  ],
  "winner": null,
  "cancelledBy": null,
  "cancelReason": null
}
```

### Oracle Methods

#### 1. `initialize(playerX, playerO, gameId)`
Sets up new game with two players.

**Input:**
```json
{
  "playerX": "DAG7L1...",
  "playerO": "DAG3X2...",
  "gameId": "550e8400-..."
}
```

**Output:**
- Creates fresh board (all nulls)
- Sets status to "InProgress"
- Sets currentTurn to "X"

#### 2. `makeMove(player, cell)`
Validates and applies move, checks for win/draw.

**Validations:**
- Game status must be "InProgress"
- Correct player's turn
- Cell in bounds [0-8]
- Cell not occupied

**Logic:**
1. Update `board[cell] = player`
2. Increment `moveCount`
3. Append to `moveHistory`
4. Check for win condition (8 winning patterns)
5. Check for draw condition (moveCount === 9)
6. Toggle `currentTurn` if game continues

**Error Response (validation failure):**
```json
{
  "_result": {
    "valid": false,
    "error": "Cell already occupied"
  }
}
```
Note: Returns `_result` only (no `_state`), so oracle state unchanged.

#### 3. `checkWinner()`
Returns current game status and winner (read-only).

#### 4. `getBoard()`
Returns current board, turn, and move count (read-only).

#### 5. `resetGame()`
Clears board for new round, keeps same players.

**Logic:**
- Clear board to all nulls
- Reset moveCount to 0
- Reset status to "InProgress"
- Reset currentTurn to "X"
- Clear moveHistory and winner
- **Keep**: playerX, playerO, gameId

#### 6. `cancelGame(requestedBy, reason)`
Marks game as cancelled, preserves state for auditing.

### Win Detection Algorithm

A player wins if they occupy any of these 8 winning patterns:

```
Rows:        Columns:     Diagonals:
[0, 1, 2]    [0, 3, 6]    [0, 4, 8]
[3, 4, 5]    [1, 4, 7]    [2, 4, 6]
[6, 7, 8]    [2, 5, 8]
```

**Implementation Note**: The win detection checks the board state AFTER the current move is applied by using conditional logic: "if this is the cell being played, use the player's mark; otherwise use the current board value".

---

## State Machine Design

### Design Philosophy

**Lifecycle Orchestrator**: The state machine manages setup → playing → finished/cancelled transitions, while the oracle enforces game rules.

**Why State Machine is Minimal:**
- ✅ Oracle holds game state (board, moves, winner)
- ✅ State machine only tracks lifecycle phase
- ✅ Guards check oracle state for transitions
- ✅ Effects invoke oracle methods via `_oracleCall`

### State Definitions

#### `setup`
- Initial state, waiting for players to be assigned
- **Next States**: `playing`, `cancelled`

#### `playing`
- Game is active, moves being made
- References oracle for querying game state
- **Next States**: `playing` (self), `finished`, `cancelled`
- **Special**: Self-transitions on both `make_move` and `reset_board`

#### `finished`
- Game ended (won or draw)
- **isFinal**: `true`
- Contains winner and final board snapshot

#### `cancelled`
- Game cancelled by user
- **isFinal**: `true`
- Contains cancellation reason and who cancelled

### Key Transitions

#### 1. `setup → playing` on `start_game`

**Event Payload:**
```json
{
  "eventType": {"value": "start_game"},
  "payload": {
    "playerX": "DAG7L1...",
    "playerO": "DAG3X2...",
    "gameId": "550e8400-..."
  }
}
```

**Effect:** Calls oracle `initialize` method with player info.

#### 2. `playing → playing` on `make_move` (game continues)

**Event Payload:**
```json
{
  "eventType": {"value": "make_move"},
  "payload": {
    "player": "X",
    "cell": 4
  }
}
```

**Guard:** Oracle status is "InProgress"

**Effect:** Calls oracle `makeMove` method, stays in `playing` state.

#### 3. `playing → finished` on `make_move` (win/draw)

**Same Event Type** as transition #2, but different guard!

**Guard:** Oracle status is "Won" OR "Draw"

**Effect:**
- Captures final status, winner, and board from oracle state
- Emits structured output:
```json
{
  "_outputs": [{
    "outputType": "game_completed",
    "data": {
      "gameId": "550e8400-...",
      "winner": "X",
      "status": "Won"
    }
  }]
}
```

#### 4. `playing → playing` on `reset_board`

**Guard:** Oracle status is "Won" OR "Draw"

**Effect:**
- Increments state machine's `roundCount`
- Calls oracle `resetGame` method
- Stays in `playing` state for new round

#### 5. `playing → cancelled` on `cancel_game`

**Effect:** Calls oracle `cancelGame` method with reason.

### Dependencies

Every transition that reads oracle state must include the oracle CID in its `dependencies` array:

```json
{
  "from": {"value": "playing"},
  "to": {"value": "playing"},
  "eventType": {"value": "make_move"},
  "dependencies": ["11111111-1111-1111-1111-111111111111"]
}
```

This ensures the DeterministicEventProcessor loads oracle state before evaluating guards/effects.

---

## Test Location

The tic-tac-toe example is fully implemented and tested in the Scala test suite:

**Test Suite:**
- `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/examples/TicTacToeGameSuite.scala`

**Test Resources:**
- Oracle definition: `modules/shared-data/src/test/resources/tictactoe/oracle-definition.json`
- State machine definition: `modules/shared-data/src/test/resources/tictactoe/state-machine-definition.json`

### Test Scenarios

1. **Complete game flow - X wins**: Play 5 moves, X wins with top row [0,1,2]
2. **Draw scenario**: Play all 9 moves resulting in draw
3. **Invalid move rejected**: Attempt to play occupied cell, verify rejection
4. **Reset and play another round**: Complete game, reset board, play again

The test suite demonstrates:
- Creating oracle and state machine
- Starting game and making moves
- Oracle state updates and win detection
- Invalid move handling (event fails, state unchanged)
- Multi-round gameplay with reset

---

## Cell Numbering

Board cells are indexed 0-8 in row-major order:

```
0 | 1 | 2
-----------
3 | 4 | 5
-----------
6 | 7 | 8
```

---

## Future Enhancements

Possible extensions to explore:

- **Stat tracking**: Add `getStats()` method for wins/losses/draws across rounds
- **Undo move**: Add `undoLastMove()` method using moveHistory
- **Timed moves**: Add turn time limits with auto-forfeit
- **Tournament mode**: Chain multiple games with bracket progression
- **AI opponent**: Oracle method for computer player move selection

---

## See Also

- [State Machine Examples README](./README.md) - Overview of all examples
- [Fuel Logistics](./fuel-logistics.md) - Multi-machine coordination example
- [Clinical Trial](./clinical-trial.md) - Complex multi-party workflows
- [Test Resources](../../modules/shared-data/src/test/resources/tictactoe/) - Actual JSON definitions