# Agent-Bridge API Reference

This document describes the HTTP API exposed by the agent-bridge microservice.

## Base URL

```
http://localhost:8080
```

## Authentication

All endpoints are currently unauthenticated (demo mode). Production deployments should add authentication.

---

## Endpoints

### Health Check

#### `GET /health`

Health check endpoint for monitoring.

**Request:**
```http
GET /health HTTP/1.1
Host: localhost:8080
```

**Response:**
```json
{
  "status": "healthy",
  "uptime": 3600,
  "wallets": {
    "playerX": "available",
    "playerO": "available"
  },
  "ottochain": {
    "connected": true,
    "lastPing": "2025-10-23T10:30:00Z"
  }
}
```

**Status Codes:**
- `200 OK` - Service healthy
- `503 Service Unavailable` - Service degraded

---

### Wallet Information

#### `GET /wallets`

Returns wallet addresses managed by the agent-bridge.

**Request:**
```http
GET /wallets HTTP/1.1
Host: localhost:8080
```

**Response:**
```json
{
  "playerX": {
    "address": "DAG88MPZSPzWqEcCTkrs6hPjcdePPMyKhxUnPQU5",
    "alias": "Player X"
  },
  "playerO": {
    "address": "DAG7Fqp72nH6FoVPCdFxm2QuBnVpFAv38kc9HPUr",
    "alias": "Player O"
  }
}
```

**Status Codes:**
- `200 OK` - Wallets available
- `500 Internal Server Error` - Wallet loading failed

---

### LLM Proxy

#### `POST /generate`

Forwards chat requests to Ollama for LLM inference. This is a transparent proxy.

**Request:**
```http
POST /generate HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "prompt": "Let's play tic-tac-toe!",
  "system": "You are OttoBot...",
  "model": "llama3.2",
  "stream": false
}
```

**Response:**
```json
{
  "text": "🦦 Awesome! Let me set up a new game on Ottochain...",
  "tool_calls": [
    {
      "tool": "ttt_create_game",
      "arguments": {}
    }
  ]
}
```

**Status Codes:**
- `200 OK` - Response generated
- `502 Bad Gateway` - Ollama unreachable
- `500 Internal Server Error` - Generation failed

**Note**: Response format depends on Ollama's API. This endpoint is essentially a passthrough.

---

### Tool Execution

#### `POST /otto/tool`

Execute a tool on behalf of OttoBot. This is the main endpoint for LLM function calling.

**Request:**
```http
POST /otto/tool HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "tool": "ttt_create_game",
  "arguments": {}
}
```

**Response (Success):**
```json
{
  "success": true,
  "result": {
    "oracleId": "550e8400-e29b-41d4-a716-446655440000",
    "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "status": "created",
    "playerXAddress": "DAG88MPZSPzWqEcCTkrs6hPjcdePPMyKhxUnPQU5",
    "playerOAddress": "DAG7Fqp72nH6FoVPCdFxm2QuBnVpFAv38kc9HPUr"
  }
}
```

**Response (Error):**
```json
{
  "success": false,
  "error": "Tool not found",
  "code": "UNKNOWN_TOOL",
  "availableTools": [
    "ttt_create_game",
    "ttt_start_game",
    "ttt_make_move",
    "ttt_get_state",
    "ttt_reset_board",
    "ttt_cancel_game"
  ]
}
```

**Status Codes:**
- `200 OK` - Tool executed (check `success` field for outcome)
- `400 Bad Request` - Invalid request format
- `404 Not Found` - Tool not found
- `500 Internal Server Error` - Tool execution failed

**Supported Tools**: See [TOOL_CATALOG.md](TOOL_CATALOG.md) for full specifications.

---

### Game State Query

#### `GET /otto/games/:machineId/state`

Convenience endpoint to query current game state without calling a tool.

**Request:**
```http
GET /otto/games/7c9e6679-7425-40de-944b-e07fc1f90ae7/state HTTP/1.1
Host: localhost:8080
```

**Response:**
```json
{
  "machineId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "oracleId": "550e8400-e29b-41d4-a716-446655440000",
  "machineState": "playing",
  "board": [null, null, "X", null, "O", null, null, null, null],
  "turn": "X",
  "status": "InProgress",
  "moveCount": 2,
  "roundCount": 0,
  "moveHistory": [
    {"player": "X", "cell": 2, "moveNum": 1},
    {"player": "O", "cell": 4, "moveNum": 2}
  ]
}
```

**Status Codes:**
- `200 OK` - State retrieved
- `404 Not Found` - Game not found
- `502 Bad Gateway` - Ottochain unreachable

---

## Tool Specifications

### `ttt_create_game`

**Arguments:** `{}`

**Returns:**
```json
{
  "oracleId": "uuid",
  "machineId": "uuid",
  "status": "created",
  "playerXAddress": "DAG...",
  "playerOAddress": "DAG..."
}
```

---

### `ttt_start_game`

**Arguments:**
```json
{
  "machineId": "7c9e6679-..."
}
```

**Returns:**
```json
{
  "status": "started",
  "board": [null, null, null, null, null, null, null, null, null],
  "turn": "X",
  "gameId": "uuid",
  "machineState": "playing"
}
```

---

### `ttt_make_move`

**Arguments:**
```json
{
  "machineId": "7c9e6679-...",
  "player": "X",
  "cell": 4
}
```

**Returns (game continues):**
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

**Returns (game ends):**
```json
{
  "valid": true,
  "board": ["X", "O", "X", "O", "X", null, "O", null, "X"],
  "status": "Won",
  "winner": "X",
  "machineState": "finished"
}
```

**Returns (error):**
```json
{
  "valid": false,
  "error": "Cell already occupied",
  "board": [...],
  "turn": "X"
}
```

---

### `ttt_get_state`

**Arguments:**
```json
{
  "machineId": "7c9e6679-..."
}
```

**Returns:**
```json
{
  "board": [...],
  "turn": "X",
  "status": "InProgress",
  "moveCount": 2,
  "machineState": "playing",
  "roundCount": 0,
  "moveHistory": [...]
}
```

---

### `ttt_reset_board`

**Arguments:**
```json
{
  "machineId": "7c9e6679-..."
}
```

**Returns:**
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

---

### `ttt_cancel_game`

**Arguments:**
```json
{
  "machineId": "7c9e6679-...",
  "reason": "user requested"
}
```

**Returns:**
```json
{
  "status": "cancelled",
  "machineState": "cancelled",
  "cancelledBy": "DAG88MP...",
  "reason": "user requested",
  "message": "Game cancelled"
}
```

---

## Error Responses

All errors follow this format:

```json
{
  "success": false,
  "error": "Human-readable error message",
  "code": "ERROR_CODE",
  "details": {
    "field": "value"
  }
}
```

### Common Error Codes

| Code | Description |
|------|-------------|
| `UNKNOWN_TOOL` | Tool name not recognized |
| `INVALID_ARGUMENTS` | Tool arguments invalid or missing |
| `OTTOCHAIN_ERROR` | Error from Ottochain (network, validation, etc.) |
| `WALLET_ERROR` | Wallet signing or loading failed |
| `GAME_NOT_FOUND` | State machine or oracle not found |
| `INVALID_MOVE` | Move validation failed (wrong turn, occupied cell, etc.) |
| `INTERNAL_ERROR` | Unexpected server error |

---

## Rate Limiting

**Current**: No rate limiting (demo mode)

**Production**: Consider:
- 10 requests/second per IP for `/otto/tool`
- 100 requests/second for `/generate` (or delegate to Ollama's limits)
- No limit on `/health` and `/wallets`

---

## CORS

**Current**: CORS disabled (local development)

**Production**: Enable CORS for Open WebUI origin:
```
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET, POST
Access-Control-Allow-Headers: Content-Type
```

---

## WebSocket Support

**Future Enhancement**: Real-time game updates via WebSocket

```
ws://localhost:8080/otto/games/:machineId/watch
```

Would emit events:
- `move_made` - When a move is applied
- `game_ended` - When game finishes
- `board_reset` - When board is reset

---

## Monitoring & Observability

### Structured Logging

All requests logged in JSON format:

```json
{
  "timestamp": "2025-10-23T10:30:00.123Z",
  "level": "INFO",
  "endpoint": "/otto/tool",
  "tool": "ttt_make_move",
  "duration_ms": 2341,
  "success": true,
  "trace_id": "abc-123-..."
}
```

### Metrics (Prometheus Format)

Future endpoints:
- `GET /metrics` - Prometheus metrics
  - `tool_calls_total{tool="ttt_make_move",status="success"}`
  - `tool_duration_seconds{tool="ttt_make_move"}`
  - `ottochain_requests_total{endpoint="/data",status="200"}`

---

## Configuration

Agent-bridge reads configuration from `application.conf`:

```hocon
agent-bridge {
  http {
    host = "0.0.0.0"
    port = 8080
  }

  ollama {
    url = "http://localhost:11434"
    model = "llama3.2"
  }

  ottochain {
    data-l1-url = "http://localhost:9000"
    l0-url = "http://localhost:9100"
    timeout = 30s
  }

  wallets {
    player-x-path = "src/main/resources/wallets/playerX.p12"
    player-o-path = "src/main/resources/wallets/playerO.p12"
    password = ${?WALLET_PASSWORD}
  }
}
```

Override via environment variables:
```bash
WALLET_PASSWORD=secret \
OTTOCHAIN_DATA_L1_URL=http://remote:9000 \
sbt "agent-bridge/run"
```

---

## Example: Full Game via API

### 1. Create Game

```bash
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool": "ttt_create_game", "arguments": {}}'
```

Response:
```json
{"success": true, "result": {"machineId": "abc-123", "oracleId": "def-456"}}
```

### 2. Start Game

```bash
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool": "ttt_start_game", "arguments": {"machineId": "abc-123"}}'
```

### 3. Make Moves

```bash
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool": "ttt_make_move", "arguments": {"machineId": "abc-123", "player": "X", "cell": 4}}'
```

### 4. Check State

```bash
curl http://localhost:8080/otto/games/abc-123/state
```

### 5. Reset Board

```bash
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool": "ttt_reset_board", "arguments": {"machineId": "abc-123"}}'
```

---

## Development

### Running Locally

```bash
cd modules/agent-bridge
sbt run
```

### Running Tests

```bash
sbt "agent-bridge/test"
```

### Hot Reload

Use `sbt ~reStart` for automatic recompilation on file changes.

---

**See also**:
- [TOOL_CATALOG.md](TOOL_CATALOG.md) - Detailed tool specifications
- [SETUP_GUIDE.md](SETUP_GUIDE.md) - Installation instructions
- [ARCHITECTURE.md](ARCHITECTURE.md) - System design
