# OttoBot Tool Catalog

This document defines all tools available to OttoBot for playing tic-tac-toe on Ottochain.

## Tool Overview

OttoBot has access to 6 tools for managing tic-tac-toe games:

| Tool | Purpose | Ottochain Operations |
|------|---------|---------------------|
| `ttt_create_game` | Set up new game on-chain | Create oracle + state machine |
| `ttt_start_game` | Initialize game with players | Send `start_game` event |
| `ttt_make_move` | Submit a move for X or O | Send `make_move` event |
| `ttt_get_state` | Query current game state | Read oracle + machine state |
| `ttt_reset_board` | Clear board for new round | Send `reset_board` event |
| `ttt_cancel_game` | Cancel the game | Send `cancel_game` event |

---

## Tool Specifications

### 1. `ttt_create_game`

**Purpose**: Create a new tic-tac-toe game on Ottochain (oracle + state machine).

**Input**:
```json
{}
```

**Process**:
1. Generate UUIDs for oracle and state machine
2. Build `CreateScript` message with tic-tac-toe logic
3. Build `CreateStateMachineFiber` message linked to oracle
4. Sign both messages with agent wallet
5. Submit both to Ottochain
6. Poll for confirmation

**Output**:
```json
{
  "oracleId": "550e8400-e29b-41d4-a716-446655440000",
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "status": "created",
  "playerXAddress": "DAG88MPZSPzWqEcCTkrs6hPjcdePPMyKhxUnPQU5",
  "playerOAddress": "DAG7Fqp72nH6FoVPCdFxm2QuBnVpFAv38kc9HPUr"
}
```

**Usage by OttoBot**:
```
User: "Let's play tic-tac-toe!"

OttoBot: "Setting up a new game on Ottochain..."
[Calls ttt_create_game]
OttoBot: "Game created! Oracle ID: 550e8400..., Machine ID: 7c9e6679..."
```

**Error Handling**:
- Ottochain unreachable: Return error, suggest retry
- Insufficient gas: Return error with details
- Signature failure: Return error, check wallet

---

### 2. `ttt_start_game`

**Purpose**: Initialize the board and set players (transition setup → playing).

**Input**:
```json
{
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

**Process**:
1. Get playerX and playerO addresses from wallet manager
2. Generate game UUID
3. Build `ProcessFiberEvent` with `start_game` event:
   ```json
   {
     "playerX": "DAG88MP...",
     "playerO": "DAG7Fqp...",
     "gameId": "abc-123-...",
     "timestamp": "2025-10-23T..."
   }
   ```
4. Sign with playerX wallet
5. Submit to Ottochain
6. Poll for result
7. Check oracle state to verify initialization

**Output**:
```json
{
  "status": "started",
  "board": [null, null, null, null, null, null, null, null, null],
  "turn": "X",
  "gameId": "abc-123-...",
  "machineState": "playing"
}
```

**Usage by OttoBot**:
```
OttoBot: "Initializing the board with X and O..."
[Calls ttt_start_game]
OttoBot: "Game started! Fresh board, X goes first!"
[Displays empty board]
```

**Error Handling**:
- State machine not in `setup`: Return error
- Oracle call failed: Return error with oracle response
- Invalid player addresses: Return error

---

### 3. `ttt_make_move`

**Purpose**: Submit a move for player X or O on-chain.

**Input**:
```json
{
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "player": "X",
  "cell": 4
}
```

**Process**:
1. Determine wallet (playerX or playerO) based on `player`
2. **Pre-flight validation** (local):
   - Check cell in range [0, 8]
   - Optionally: query current board and verify cell not occupied
3. Build `ProcessFiberEvent` with `make_move` event:
   ```json
   {
     "player": "X",
     "cell": 4,
     "timestamp": "2025-10-23T...",
     "idempotencyKey": "uuid-..."
   }
   ```
4. Sign with appropriate wallet (X or O)
5. Submit to Ottochain
6. Poll for result
7. Query oracle state for updated board
8. Check if game ended (Won/Draw)

**Output** (game continues):
```json
{
  "valid": true,
  "board": [null, null, null, null, "X", null, null, null, null],
  "turn": "O",
  "status": "InProgress",
  "moveCount": 1,
  "machineState": "playing"
}
```

**Output** (game ends):
```json
{
  "valid": true,
  "board": ["X", "O", "X", "O", "X", null, "O", null, "X"],
  "status": "Won",
  "winner": "X",
  "machineState": "finished"
}
```

**Output** (error):
```json
{
  "valid": false,
  "error": "Cell already occupied",
  "board": [...],
  "turn": "X"
}
```

**Usage by OttoBot**:
```
OttoBot: "As X, I'll take the center!"
[Calls ttt_make_move({machineId: "...", player: "X", cell: 4})]
OttoBot: "Move applied! Board updated, now O's turn."
[Displays board]
```

**Error Handling**:
- Pre-flight validation failed: Return error without submitting
- Oracle validation failed: Return oracle error message
- Wrong turn: Return error, show whose turn it is
- Cell occupied: Return error, suggest alternate cell

---

### 4. `ttt_get_state`

**Purpose**: Query current game state from Ottochain.

**Input**:
```json
{
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

**Process**:
1. Query state machine fiber by CID
2. Extract `oracleCid` from state machine state
3. Query oracle fiber by CID
4. Parse oracle state (board, turn, status, etc.)
5. Return combined state

**Output**:
```json
{
  "board": [null, null, "X", null, "O", null, null, null, null],
  "turn": "X",
  "status": "InProgress",
  "moveCount": 2,
  "machineState": "playing",
  "roundCount": 0,
  "moveHistory": [
    {"player": "X", "cell": 2, "moveNum": 1},
    {"player": "O", "cell": 4, "moveNum": 2}
  ]
}
```

**Usage by OttoBot**:
```
OttoBot: "Let me check the current board state..."
[Calls ttt_get_state]
OttoBot: "Board has 2 moves so far, X's turn next."
[Displays board]
```

**Error Handling**:
- State machine not found: Return error
- Oracle not found: Return error
- Network error: Return error, suggest retry

---

### 5. `ttt_reset_board`

**Purpose**: Reset the board for a new round, keeping same players.

**Input**:
```json
{
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

**Process**:
1. Query oracle state to verify game is Won or Draw
2. Build `ProcessFiberEvent` with `reset_board` event:
   ```json
   {
     "timestamp": "2025-10-23T...",
     "idempotencyKey": "uuid-..."
   }
   ```
3. Sign with playerX wallet (or either player)
4. Submit to Ottochain
5. Verify oracle state reset (board all nulls, status InProgress)

**Output**:
```json
{
  "status": "reset",
  "board": [null, null, null, null, null, null, null, null, null],
  "turn": "X",
  "roundCount": 1,
  "machineState": "playing",
  "message": "Board reset for new round"
}
```

**Usage by OttoBot**:
```
User: "Let's go again!"

OttoBot: "Resetting the board for round 2..."
[Calls ttt_reset_board]
OttoBot: "Fresh board! X starts again."
[Displays empty board]
```

**Error Handling**:
- Game not finished: Return error "Game still in progress"
- Oracle reset failed: Return error with details
- State machine error: Return error

---

### 6. `ttt_cancel_game`

**Purpose**: Cancel the current game session.

**Input**:
```json
{
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "reason": "user requested"
}
```

**Process**:
1. Get requester address (use playerX wallet)
2. Build `ProcessFiberEvent` with `cancel_game` event:
   ```json
   {
     "requestedBy": "DAG88MP...",
     "reason": "user requested",
     "timestamp": "2025-10-23T...",
     "idempotencyKey": "uuid-..."
   }
   ```
3. Sign with requester wallet
4. Submit to Ottochain
5. Verify state machine transitioned to `cancelled`

**Output**:
```json
{
  "status": "cancelled",
  "machineState": "cancelled",
  "cancelledBy": "DAG88MP...",
  "reason": "user requested",
  "message": "Game cancelled"
}
```

**Usage by OttoBot**:
```
User: "Let's stop"

OttoBot: "Cancelling the game..."
[Calls ttt_cancel_game]
OttoBot: "Game cancelled. Come back anytime! 🦦"
```

**Error Handling**:
- Already cancelled: Return current status
- Already finished: Return error "Game already completed"
- Network error: Return error, suggest retry

---

## Tool Call Format

When OttoBot calls a tool, the agent-bridge receives:

```http
POST /otto/tool
Content-Type: application/json

{
  "tool": "ttt_make_move",
  "arguments": {
    "machineId": "7c9e6679-...",
    "player": "X",
    "cell": 4
  }
}
```

Response:

```json
{
  "valid": true,
  "board": [...],
  "turn": "O",
  "status": "InProgress"
}
```

## Common Workflows

### Full Game Flow

1. `ttt_create_game` → Get machineId
2. `ttt_start_game` → Initialize board
3. Loop:
   - `ttt_make_move` (X)
   - `ttt_make_move` (O)
   - Until status = Won or Draw
4. Optionally: `ttt_reset_board` or `ttt_cancel_game`

### Query-Only Flow

1. `ttt_get_state` → Check current board
2. Display board to user
3. No writes needed

### Multi-Round Flow

1. `ttt_create_game` + `ttt_start_game`
2. Play game to completion
3. `ttt_reset_board`
4. Play again (repeat step 2-3)

---

## Error Handling Strategy

All tools return structured errors:

```json
{
  "error": "Cell already occupied",
  "code": "INVALID_MOVE",
  "details": {
    "cell": 4,
    "currentOccupant": "O",
    "turn": "X"
  }
}
```

OttoBot translates errors to natural language:

```
Tool Error: "Cell already occupied"

OttoBot: "Oops! Cell 4 is already taken by O. Let me try cell 6 instead..."
[Retries with different cell]
```

## Performance Notes

- **`ttt_make_move`**: ~2-5 seconds (Ottochain tx + confirmation)
- **`ttt_get_state`**: ~500ms (read-only query)
- **`ttt_create_game`**: ~5-10 seconds (create 2 fibers)
- **`ttt_reset_board`**: ~2-5 seconds (oracle call + state update)

OttoBot can call `ttt_get_state` frequently without impacting chain.

---

## Future Tool Extensions

### `ttt_simulate_move` (Local Pre-flight)

Validate move locally before submitting:

```json
{
  "tool": "ttt_simulate_move",
  "arguments": {
    "board": [...],
    "player": "X",
    "cell": 4
  }
}
```

Returns `{valid: true/false, reason?: "..."}` without touching chain.

### `ttt_get_stats` (Multi-Round Stats)

If oracle supports stats tracking:

```json
{
  "tool": "ttt_get_stats",
  "arguments": {
    "machineId": "..."
  }
}
```

Returns:
```json
{
  "wins": {"X": 2, "O": 1},
  "draws": 1,
  "totalGames": 4
}
```

---

**See also**:
- `OTTO_PERSONA.md` - OttoBot behavior and tool usage guidelines
- `API_REFERENCE.md` - Agent-bridge HTTP API specifications
- `SCRIPT_DESIGN.md` - Script method specifications
- `STATE_MACHINE_DESIGN.md` - State machine transitions
