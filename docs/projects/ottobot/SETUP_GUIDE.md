# OttoBot Agent-Bridge Setup Guide

This guide walks through setting up the complete OttoBot system from scratch.

## Prerequisites

### Required Software

- **Scala 2.13** and **sbt 1.9+**
- **Node.js 18+** and **npm** (for e2e-test terminal)
- **Java 11+** (for Scala/sbt)
- **Ollama** (for local LLM inference)
- **Docker** (optional, for Ollama)

### Ottochain

You need a running Ottochain metagraph with:
- Data L1 node (default: `http://localhost:9000`)
- L0 node (default: `http://localhost:9100`)

**If you don't have Ottochain running**, see your existing deployment docs.

---

## Installation Steps

### 1. Install Ollama

#### Option A: Native Install

**macOS/Linux**:
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

**Windows**: Download from https://ollama.com/download

#### Option B: Docker

```bash
docker pull ollama/ollama
docker run -d -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
```

Verify installation:
```bash
ollama --version
```

### 2. Pull LLM Model

Download llama3.2 (or compatible):

```bash
ollama pull llama3.2
```

Test inference:
```bash
ollama run llama3.2 "Hello, can you play tic-tac-toe?"
```

### 3. Install Open WebUI

#### Option A: Docker (Recommended)

```bash
docker run -d -p 3000:8080 \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -v open-webui:/app/backend/data \
  --name open-webui \
  ghcr.io/open-webui/open-webui:main
```

Access at: http://localhost:3000

#### Option B: pip Install

```bash
pip install open-webui
open-webui serve
```

**Note**: Update Ollama URL in Open WebUI settings to `http://localhost:11434`.

### 4. Set Up Ottochain

Ensure your metagraph is running:

```bash
# Check Data L1
curl http://localhost:9000/cluster/info

# Check L0
curl http://localhost:9100/cluster/info
```

If not running, start your local cluster (refer to your Ottochain deployment guide).

### 5. Install E2E Test Dependencies

```bash
cd e2e-test
npm install
```

Verify terminal works:
```bash
node terminal.js --help
```

---

## Testing Oracle & State Machine

Before building the agent-bridge, test the tic-tac-toe definitions manually.

### 1. Create Oracle

```bash
cd e2e-test

node terminal.js or create \
  --script examples/tictactoe/script-definition.json
```

**Expected Output**:
```
✅ Oracle created successfully!
Oracle CID: 550e8400-e29b-41d4-a716-446655440000
```

**Save the oracle CID** - you'll need it for the state machine.

### 2. Update Initial Data

Edit `examples/tictactoe/initial-data.json`:

```json
{
  "gameId": null,
  "oracleCid": "550e8400-e29b-41d4-a716-446655440000",  // <-- Your oracle CID
  "roundCount": 0,
  "createdAt": null,
  "playerX": null,
  "playerO": null
}
```

### 3. Create State Machine

```bash
node terminal.js sm create \
  --definition examples/tictactoe/state-machine-definition.json \
  --initialData examples/tictactoe/initial-data.json
```

**Expected Output**:
```
✅ State machine created successfully!
Machine CID: 7c9e6679-7425-40de-944b-e07fc1f90ae7
Current state: setup
```

**Save the machine CID**.

### 4. Start Game

Edit `examples/tictactoe/event-start.json` with your wallet addresses:

```json
{
  "eventType": {"value": "start_game"},
  "payload": {
    "playerX": "YOUR_WALLET_X_ADDRESS",
    "playerO": "YOUR_WALLET_O_ADDRESS",
    "gameId": "550e8400-e29b-41d4-a716-446655440001",
    "timestamp": "2025-10-23T00:00:00Z"
  },
  "idempotencyKey": null
}
```

Submit event:
```bash
node terminal.js sm process-event \
  --address 7c9e6679-7425-40de-944b-e07fc1f90ae7 \
  --event examples/tictactoe/event-start.json \
  --expectedState playing
```

**Expected Output**:
```
✅ Event processed successfully!
Machine transitioned: setup → playing
Oracle initialized with fresh board
```

### 5. Make Moves

Player X move:
```bash
node terminal.js sm process-event \
  --address 7c9e6679-7425-40de-944b-e07fc1f90ae7 \
  --event examples/tictactoe/event-move-x.json \
  --expectedState playing
```

Player O move:
```bash
node terminal.js sm process-event \
  --address 7c9e6679-7425-40de-944b-e07fc1f90ae7 \
  --event examples/tictactoe/event-move-o.json \
  --expectedState playing
```

Continue alternating until win or draw.

### 6. Check Oracle State

```bash
node terminal.js or invoke \
  --address 550e8400-e29b-41d4-a716-446655440000 \
  --method getBoard
```

**Expected Output**:
```json
{
  "board": [null, null, "X", null, "O", null, null, null, null],
  "currentTurn": "X",
  "moveCount": 2
}
```

### 7. Test Reset

After game finishes:

```bash
node terminal.js sm process-event \
  --address 7c9e6679-7425-40de-944b-e07fc1f90ae7 \
  --event examples/tictactoe/event-reset.json \
  --expectedState playing
```

Should clear the board while staying in `playing` state.

### 8. Test Cancel

```bash
node terminal.js sm process-event \
  --address 7c9e6679-7425-40de-944b-e07fc1f90ae7 \
  --event examples/tictactoe/event-cancel.json \
  --expectedState cancelled
```

State machine should transition to `cancelled`.

---

## Agent-Bridge Setup (When Implemented)

### 1. Generate Wallets

```bash
cd modules/agent-bridge
sbt "runMain xyz.kd5ujc.agent_bridge.GenerateWallets"
```

This creates:
- `src/main/resources/wallets/playerX.p12`
- `src/main/resources/wallets/playerO.p12`

**Backup these files securely!**

### 2. Configure Agent-Bridge

Edit `src/main/resources/application.conf`:

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

Set wallet password:
```bash
export WALLET_PASSWORD="your-secure-password"
```

### 3. Build Agent-Bridge

```bash
cd modules/agent-bridge
sbt compile
```

### 4. Run Agent-Bridge

```bash
sbt run
```

**Expected Output**:
```
[INFO] Agent-bridge starting on http://0.0.0.0:8080
[INFO] Loaded wallets: playerX (DAG88MP...), playerO (DAG7Fqp...)
[INFO] Connected to Ottochain Data L1: http://localhost:9000
[INFO] Connected to Ollama: http://localhost:11434
[INFO] Server started successfully
```

### 5. Test Health Endpoint

```bash
curl http://localhost:8080/health
```

**Expected**:
```json
{
  "status": "healthy",
  "uptime": 10,
  "wallets": {
    "playerX": "available",
    "playerO": "available"
  },
  "ottochain": {
    "connected": true
  }
}
```

### 6. Test Tool Endpoint

```bash
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool": "ttt_create_game", "arguments": {}}'
```

**Expected**:
```json
{
  "success": true,
  "result": {
    "oracleId": "...",
    "machineId": "...",
    "status": "created"
  }
}
```

---

## Open WebUI Configuration

### 1. Access Open WebUI

Open http://localhost:3000 in browser.

### 2. Create Account

First time: create an admin account.

### 3. Configure Model

- Go to **Settings** → **Models**
- Select `llama3.2` as default
- Enable **Function Calling** (if available)

### 4. Add OttoBot Persona

- Go to **Settings** → **Prompts** or **Personas**
- Create new persona named "OttoBot"
- Copy contents from `/docs/OTTO_PERSONA.md` into system prompt
- Save

### 5. Add Tool Definitions

If Open WebUI supports custom tools:
- Go to **Settings** → **Functions** or **Tools**
- Add tool definitions from `/docs/TOOL_CATALOG.md`
- Configure endpoint: `http://localhost:8080/otto/tool`

**Note**: If Open WebUI doesn't support external tool calling, you'll need to configure Ollama's native function calling or use a wrapper.

### 6. Test Chat

Start a conversation:

```
User: "Let's play tic-tac-toe!"
```

OttoBot should respond and attempt to call `ttt_create_game`.

---

## Troubleshooting

### Ollama Not Responding

**Check if running**:
```bash
curl http://localhost:11434/api/tags
```

**Restart**:
```bash
ollama serve
```

### Ottochain Connection Failed

**Check nodes are running**:
```bash
curl http://localhost:9000/cluster/info
curl http://localhost:9100/cluster/info
```

**Check firewall**: Ensure ports 9000, 9100 are accessible.

### Wallet Loading Failed

**Check file paths**:
```bash
ls -la modules/agent-bridge/src/main/resources/wallets/
```

**Check password**:
```bash
echo $WALLET_PASSWORD
```

### Tool Calls Not Working

**Check agent-bridge logs**:
```bash
tail -f modules/agent-bridge/logs/application.log
```

**Test tool directly**:
```bash
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool": "ttt_get_state", "arguments": {"machineId": "test"}}'
```

### Open WebUI Can't Reach Agent-Bridge

**From Docker**: Use `http://host.docker.internal:8080` instead of `localhost`.

**From Native**: Use `http://localhost:8080`.

---

## Development Workflow

### 1. Start All Services

```bash
# Terminal 1: Ottochain
cd /path/to/ottochain
./start-cluster.sh

# Terminal 2: Ollama
ollama serve

# Terminal 3: Agent-Bridge
cd modules/agent-bridge
sbt run

# Terminal 4: Open WebUI
docker start open-webui
```

### 2. Make Changes

Edit Scala code in `modules/agent-bridge/src/`.

### 3. Hot Reload

Use sbt's hot reload:
```bash
sbt ~reStart
```

Changes will auto-recompile and restart the server.

### 4. Run Tests

```bash
sbt "agent-bridge/test"
```

---

## Production Deployment

### Environment Variables

```bash
export WALLET_PASSWORD="secure-password"
export OTTOCHAIN_DATA_L1_URL="https://prod-l1.example.com"
export OTTOCHAIN_L0_URL="https://prod-l0.example.com"
export OLLAMA_URL="http://ollama-service:11434"
export HTTP_PORT=8080
```

### Docker Deployment (Future)

```dockerfile
FROM hseeberger/scala-sbt:11.0.12_1.5.5_2.13.6 as builder
WORKDIR /app
COPY .. .
RUN sbt "agent-bridge/assembly"

FROM openjdk:11-jre-slim
COPY --from=builder /app/modules/agent-bridge/target/scala-2.13/agent-bridge.jar /app/
ENTRYPOINT ["java", "-jar", "/app/agent-bridge.jar"]
```

### Security Considerations

- **Wallet security**: Use hardware wallets or HSM in production
- **API authentication**: Add JWT or API key auth
- **Rate limiting**: Implement per-IP rate limits
- **HTTPS**: Use TLS for all endpoints
- **Firewall**: Restrict access to Ottochain nodes

---

## Monitoring

### Logs

**Agent-bridge logs**:
```bash
tail -f modules/agent-bridge/logs/application.log
```

**Structured JSON logs**:
```json
{"timestamp":"2025-10-23T10:30:00Z","level":"INFO","message":"Tool executed","tool":"ttt_make_move","duration_ms":2341}
```

### Metrics (Future)

Prometheus endpoint:
```bash
curl http://localhost:8080/metrics
```

Grafana dashboard for:
- Tool call rates
- Success/error rates
- Ottochain latency
- Wallet balances

---

## Useful Commands

### Check Agent-Bridge Status

```bash
curl http://localhost:8080/health | jq
```

### List Wallet Addresses

```bash
curl http://localhost:8080/wallets | jq
```

### Query Game State

```bash
curl http://localhost:8080/otto/games/<MACHINE_ID>/state | jq
```

### Test Full Game Flow

```bash
# Create game
GAME=$(curl -s -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d '{"tool":"ttt_create_game","arguments":{}}' | jq -r '.result.machineId')

echo "Game ID: $GAME"

# Start game
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d "{\"tool\":\"ttt_start_game\",\"arguments\":{\"machineId\":\"$GAME\"}}"

# Make move
curl -X POST http://localhost:8080/otto/tool \
  -H "Content-Type: application/json" \
  -d "{\"tool\":\"ttt_make_move\",\"arguments\":{\"machineId\":\"$GAME\",\"player\":\"X\",\"cell\":4}}"
```

---

## Next Steps

1. ✅ Test oracle and state machine definitions manually
2. ⏭️ Implement agent-bridge Scala module
3. ⏭️ Integrate with Open WebUI
4. ⏭️ End-to-end test with OttoBot

---

**See also**:
- [API_REFERENCE.md](API_REFERENCE.md) - HTTP API documentation
- [TOOL_CATALOG.md](TOOL_CATALOG.md) - Tool specifications
- [ARCHITECTURE.md](ARCHITECTURE.md) - System design
- [OTTO_PERSONA.md](OTTO_PERSONA.md) - LLM configuration
