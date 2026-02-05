# OttoChain: Complete Deployment from Scratch

**Tested:** 2026-02-04
**Tessellation:** v4.0.0-rc.2
**OttoChain:** metakit 1.7.0-rc.4

## Prerequisites

- 2 Hetzner servers (or similar):
  - **Metagraph Node**: 4+ CPU, 8GB+ RAM (runs GL0, ML0, CL1×3, DL1×3)
  - **Services Node**: 2+ CPU, 4GB+ RAM (runs Bridge, Indexer, Gateway, Explorer, Postgres, Redis)
- Ubuntu 22.04 LTS
- Docker installed on both
- Ports open: 9000-9422 (metagraph), 3030-8080 (services)

## Part 1: Metagraph Node Setup

### 1.1 SSH to Metagraph Node

```bash
ssh root@<METAGRAPH_IP>
```

### 1.2 Install Dependencies

```bash
apt update && apt install -y docker.io curl jq
systemctl enable docker && systemctl start docker
```

### 1.3 Create Directory Structure

```bash
mkdir -p /opt/ottochain/{jars,keys1,keys2,keys3,keys4,data/{gl0,ml0,cl1,cl1-2,cl1-3,dl1,dl1-2,dl1-3}}
cd /opt/ottochain
```

### 1.4 Download Tessellation Tools

```bash
cd /opt/ottochain/jars
TESS_VERSION="v4.0.0-rc.2"

# Download keytool and wallet
curl -L -o cl-keytool.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${TESS_VERSION}/cl-keytool.jar"
curl -L -o cl-wallet.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${TESS_VERSION}/cl-wallet.jar"

# Download global-l0 (needed for standalone GL0)
curl -L -o global-l0.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${TESS_VERSION}/cl-dag-l1.jar"
```

### 1.5 Build OttoChain JARs (on dev machine)

```bash
# On your dev machine with sbt installed
cd /path/to/ottochain
source ~/.sdkman/bin/sdkman-init.sh  # Ensure Java 21

sbt "l0/assembly" "l1/assembly" "dataL1/assembly"

# Copy JARs to server
scp modules/l0/target/scala-2.13/*-assembly-*.jar root@<METAGRAPH_IP>:/opt/ottochain/jars/metagraph-l0.jar
scp modules/l1/target/scala-2.13/*-assembly-*.jar root@<METAGRAPH_IP>:/opt/ottochain/jars/currency-l1.jar
scp modules/data_l1/target/scala-2.13/*-assembly-*.jar root@<METAGRAPH_IP>:/opt/ottochain/jars/data-l1.jar
```

### 1.6 Generate Keys (4 unique keys)

```bash
cd /opt/ottochain

# Generate 4 keys with tessellation keytool
for i in 1 2 3 4; do
  cd /opt/ottochain/keys$i
  CL_KEYSTORE=key.p12 CL_KEYALIAS=alias CL_PASSWORD=password \
    java -jar /opt/ottochain/jars/cl-keytool.jar generate
  echo "Generated key $i"
done

# Get peer IDs for each key
for i in 1 2 3 4; do
  echo "Key $i peer ID:"
  cd /opt/ottochain/keys$i
  CL_KEYSTORE=key.p12 CL_KEYALIAS=alias CL_PASSWORD=password \
    java -jar /opt/ottochain/jars/cl-wallet.jar show-id
done
```

**Save the peer ID from keys1** - this is your primary node ID.

### 1.7 Set Environment Variables

```bash
# Edit these for your setup
export HOST="<METAGRAPH_IP>"
export GL0_PEER_ID="<peer-id-from-keys1>"
export TOKEN="DAG2NLnsdCvDovUT7nJxP4kPznqEramWozMhuKzN"  # Your metagraph token ID
```

### 1.8 Start GL0 (Global L0)

```bash
docker run -d --name gl0 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys1:/keys:ro \
  -v /opt/ottochain/data/gl0:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9000 \
  -e CL_P2P_HTTP_PORT=9001 \
  -e CL_CLI_HTTP_PORT=9002 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx2g -jar /jars/global-l0.jar run-initial-validator

# Wait for GL0 to be ready
sleep 15
curl -s http://localhost:9000/node/info | jq .state
# Should show "Ready"
```

### 1.9 Start ML0 (Metagraph L0)

**⚠️ CRITICAL: ML0 requires a two-step genesis process!**

#### Step 1: Create genesis.csv

```bash
# Create genesis.csv with initial token distribution
# Format: <address>,<amount> (amount in DAG units × 10^8)
cat > /opt/ottochain/genesis.csv << 'EOF'
DAG3yG9CRoYd4XF4PTBtLo95h8uiGNWYXXrASJGg,1000000000000000
EOF
```

#### Step 2: Create genesis.snapshot

The `create-genesis` command creates the `genesis.snapshot` file that ML0 needs.
This must be done BEFORE starting ML0 with `run-genesis`.

```bash
# Run create-genesis to generate genesis.snapshot
docker run --rm \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys1:/keys:ro \
  -v /opt/ottochain/genesis.csv:/genesis.csv:ro \
  -v /opt/ottochain/data/ml0:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9200 \
  -e CL_P2P_HTTP_PORT=9201 \
  -e CL_CLI_HTTP_PORT=9202 \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  eclipse-temurin:21-jre-jammy \
  java -jar /jars/metagraph-l0.jar create-genesis /data/genesis.csv

# Verify genesis files were created
ls -la /opt/ottochain/data/ml0/genesis.*
# Should show: genesis.snapshot, genesis.address

# Save the metagraph ID (this is your new TOKEN)
export TOKEN=$(cat /opt/ottochain/data/ml0/genesis.address)
echo "Metagraph ID: $TOKEN"
```

#### Step 3: Start ML0 with run-genesis

```bash
docker run -d --name ml0 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys1:/keys:ro \
  -v /opt/ottochain/data/ml0:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9200 \
  -e CL_P2P_HTTP_PORT=9201 \
  -e CL_CLI_HTTP_PORT=9202 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx2g -jar /jars/metagraph-l0.jar run-genesis \
    --global-l0-peer-id $GL0_PEER_ID \
    --global-l0-peer-host $HOST \
    --global-l0-peer-port 9000 \
    /data/genesis.snapshot

# Wait and verify
sleep 20
curl -s http://localhost:9200/node/info | jq .state
# Should show: "Ready"
```

**Note:** After genesis, subsequent restarts use `run-validator` (not `run-genesis`).

### 1.10 Start CL1 (Currency L1 - 3 nodes)

```bash
# Node 1 (initial validator with keys1)
docker run -d --name cl1 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys1:/keys:ro \
  -v /opt/ottochain/data/cl1:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9300 \
  -e CL_P2P_HTTP_PORT=9301 \
  -e CL_CLI_HTTP_PORT=9302 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx1g -jar /jars/currency-l1.jar run-initial-validator

sleep 10

# Node 2 (validator with keys2)
docker run -d --name cl1-1 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys2:/keys:ro \
  -v /opt/ottochain/data/cl1-2:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9310 \
  -e CL_P2P_HTTP_PORT=9311 \
  -e CL_CLI_HTTP_PORT=9312 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx1g -jar /jars/currency-l1.jar run-validator

# Node 3 (validator with keys3)
docker run -d --name cl1-2 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys3:/keys:ro \
  -v /opt/ottochain/data/cl1-3:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9320 \
  -e CL_P2P_HTTP_PORT=9321 \
  -e CL_CLI_HTTP_PORT=9322 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx1g -jar /jars/currency-l1.jar run-validator

# Wait for nodes to start
sleep 10

# Join nodes 2 and 3 to the cluster (via their CLI ports)
curl -X POST "http://localhost:9312/cluster/join" \
  -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'

curl -X POST "http://localhost:9322/cluster/join" \
  -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'

# Verify cluster
sleep 5
echo "CL1 cluster size:"
curl -s http://localhost:9300/cluster/info | jq length
```

### 1.11 Start DL1 (Data L1 - 3 nodes)

```bash
# Node 1 (initial validator with keys1)
docker run -d --name dl1 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys1:/keys:ro \
  -v /opt/ottochain/data/dl1:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9400 \
  -e CL_P2P_HTTP_PORT=9401 \
  -e CL_CLI_HTTP_PORT=9402 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx1g -jar /jars/data-l1.jar run-initial-validator

sleep 10

# Node 2 (validator with keys2)
docker run -d --name dl1-1 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys2:/keys:ro \
  -v /opt/ottochain/data/dl1-2:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9410 \
  -e CL_P2P_HTTP_PORT=9411 \
  -e CL_CLI_HTTP_PORT=9412 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx1g -jar /jars/data-l1.jar run-validator

# Node 3 (validator with keys3)
docker run -d --name dl1-2 --restart unless-stopped \
  --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys3:/keys:ro \
  -v /opt/ottochain/data/dl1-3:/data \
  -w /data \
  -e CL_KEYSTORE=/keys/key.p12 \
  -e CL_KEYALIAS=alias \
  -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_L0_PEER_HTTP_HOST=$HOST \
  -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9420 \
  -e CL_P2P_HTTP_PORT=9421 \
  -e CL_CLI_HTTP_PORT=9422 \
  -e CL_APP_ENV=dev \
  -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy \
  java -Xmx1g -jar /jars/data-l1.jar run-validator

# Wait for nodes to start
sleep 30

# Check DL1-0 is ready
curl -s http://localhost:9400/node/info | jq .state
# Should show: "Ready"

# DL1-1 and DL1-2 will be in "ReadyToJoin" state
# They need to join the cluster via CLI port (localhost-only)

# ⚠️ CRITICAL: CLI port is bound to 127.0.0.1, so join must be called from inside container
# Get DL1-0's peer ID first
DL1_PEER_ID=$(curl -s http://localhost:9400/node/info | jq -r .id)

# Join DL1-1 to cluster (via docker exec)
docker exec dl1-1 bash -c "apt-get update -qq && apt-get install -y -qq curl > /dev/null 2>&1 && \
  curl -s -X POST 'http://127.0.0.1:9412/cluster/join' \
    -H 'Content-Type: application/json' \
    -d '{\"id\": \"$DL1_PEER_ID\", \"ip\": \"$HOST\", \"p2pPort\": 9401}'"

# Join DL1-2 to cluster (via docker exec)
docker exec dl1-2 bash -c "apt-get update -qq && apt-get install -y -qq curl > /dev/null 2>&1 && \
  curl -s -X POST 'http://127.0.0.1:9422/cluster/join' \
    -H 'Content-Type: application/json' \
    -d '{\"id\": \"$DL1_PEER_ID\", \"ip\": \"$HOST\", \"p2pPort\": 9401}'"

# Verify cluster
sleep 10
echo "DL1 states:"
for port in 9400 9410 9420; do
  echo "  $port: $(curl -s http://localhost:$port/node/info | jq -r .state)"
done
echo "DL1 cluster size:"
curl -s http://localhost:9400/cluster/info | jq length
# Should show: 3
```

**Note:** The CLI join endpoint format is `{id, ip, p2pPort}` (simpler than P2P join).
The P2P `/cluster/join` endpoint expects a different, more complex `JoinRequest` format.

### 1.12 Verify Metagraph Stack

```bash
echo "=== Stack Status ==="
echo "GL0: $(curl -s http://localhost:9000/node/info | jq -r .state)"
echo "ML0: $(curl -s http://localhost:9200/node/info | jq -r .state)"
echo "CL1 cluster: $(curl -s http://localhost:9300/cluster/info | jq length) nodes"
echo "DL1 cluster: $(curl -s http://localhost:9400/cluster/info | jq length) nodes"
```

---

## Part 2: Services Node Setup

### 2.1 SSH to Services Node

```bash
ssh root@<SERVICES_IP>
```

### 2.2 Install Dependencies

```bash
apt update && apt install -y docker.io docker-compose-plugin nginx curl jq git nodejs npm
systemctl enable docker nginx
systemctl start docker nginx
```

### 2.3 Clone and Build Services

```bash
cd /opt
git clone https://github.com/ottobot-ai/ottochain-services.git
cd ottochain-services

# Install dependencies and build
npm install -g pnpm
pnpm install
pnpm build
```

### 2.4 Create Docker Compose

```bash
cat > /opt/ottochain-services/docker-compose.yml << 'EOF'
version: '3.8'

services:
  postgres:
    image: postgres:15
    restart: unless-stopped
    environment:
      - POSTGRES_USER=ottochain
      - POSTGRES_PASSWORD=ottochain
      - POSTGRES_DB=ottochain
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ottochain"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5

  bridge:
    build:
      context: .
      dockerfile: Dockerfile
    restart: unless-stopped
    environment:
      - PORT=3030
      - METAGRAPH_ML0_URL=http://<METAGRAPH_IP>:9200
      - METAGRAPH_DL1_URL=http://<METAGRAPH_IP>:9400
      - DATABASE_URL=postgresql://ottochain:ottochain@postgres:5432/ottochain
      - REDIS_URL=redis://redis:6379
    command: ["node", "packages/bridge/dist/index.js"]
    ports:
      - "3030:3030"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  indexer:
    build:
      context: .
      dockerfile: Dockerfile
    restart: unless-stopped
    environment:
      - PORT=3031
      - METAGRAPH_ML0_URL=http://<METAGRAPH_IP>:9200
      - METAGRAPH_DL1_URL=http://<METAGRAPH_IP>:9400
      - DATABASE_URL=postgresql://ottochain:ottochain@postgres:5432/ottochain
      - REDIS_URL=redis://redis:6379
    command: ["node", "packages/indexer/dist/index.js"]
    ports:
      - "3031:3031"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  gateway:
    build:
      context: .
      dockerfile: Dockerfile
    restart: unless-stopped
    environment:
      - GATEWAY_PORT=4000
      - DATABASE_URL=postgresql://ottochain:ottochain@postgres:5432/ottochain
      - REDIS_URL=redis://redis:6379
    command: ["node", "packages/gateway/dist/index.js"]
    ports:
      - "4000:4000"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

volumes:
  postgres_data:
EOF

# Replace <METAGRAPH_IP> with actual IP
sed -i 's/<METAGRAPH_IP>/5.78.90.207/g' docker-compose.yml
```

### 2.5 Create Dockerfile

```bash
cat > /opt/ottochain-services/Dockerfile << 'EOF'
FROM node:20-slim
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
COPY packages ./packages
RUN npm install -g pnpm && pnpm install --frozen-lockfile
RUN pnpm build
EOF
```

### 2.6 Start Services

```bash
cd /opt/ottochain-services
docker compose up -d

# Wait for services
sleep 10

# Run database migrations
docker exec ottochain-services-indexer-1 npx prisma db push --accept-data-loss
```

### 2.7 Build and Deploy Explorer

```bash
cd /opt
git clone https://github.com/ottobot-ai/ottochain-explorer.git
cd ottochain-explorer

# Build
pnpm install
pnpm build

# Deploy to nginx
cp -r dist /opt/ottochain-explorer/dist
```

### 2.8 Configure Nginx

```bash
cat > /etc/nginx/conf.d/explorer.conf << 'EOF'
server {
    listen 8080;
    root /opt/ottochain-explorer/dist;
    index index.html;

    # GraphQL proxy to gateway
    location /graphql {
        proxy_pass http://127.0.0.1:4000/graphql;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # REST API proxy to indexer
    location /api/ {
        proxy_pass http://127.0.0.1:3031/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
    }

    # WebSocket proxy
    location /ws {
        proxy_pass http://127.0.0.1:4000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
EOF

# Remove default site if exists
rm -f /etc/nginx/sites-enabled/default

# Test and reload
nginx -t && nginx -s reload
```

### 2.9 Verify Services

```bash
echo "=== Services Status ==="
echo "Bridge: $(curl -s http://localhost:3030/health | jq -r .status)"
echo "Indexer: $(curl -s http://localhost:3031/health | jq -r .status)"
echo "Gateway: $(curl -s http://localhost:4000/health | jq -r .status)"
echo "Explorer: http://<SERVICES_IP>:8080"
```

---

## Part 3: Trigger Initial Index

```bash
# Get current ordinal from ML0
ORDINAL=$(curl -s http://<METAGRAPH_IP>:9200/data-application/v1/checkpoint | jq -r '.ordinal')

# Trigger indexer webhook
curl -X POST http://localhost:3031/webhook/snapshot \
  -H "Content-Type: application/json" \
  -d '{"ordinal": '$ORDINAL', "hash": "initial", "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"}'

# Check indexer status
curl -s http://localhost:3031/status | jq .
```

---

## Part 4: Generate Traffic (Optional)

```bash
cd /opt/ottochain-services

# Run traffic generator
BRIDGE_URL=http://localhost:3030 ITERATIONS=50 \
  pnpm exec tsx scripts/demo/metagraph-activity.ts
```

---

## Verification Checklist

- [ ] GL0 state is "Ready"
- [ ] ML0 state is "Ready"
- [ ] CL1 cluster has 3 nodes
- [ ] DL1 cluster has 3 nodes
- [ ] Bridge health returns "ok"
- [ ] Indexer health returns "ok"
- [ ] Gateway health returns "ok"
- [ ] Explorer loads at http://<SERVICES_IP>:8080
- [ ] GraphQL query returns data
- [ ] Traffic generator creates agents/contracts

---

## Troubleshooting

### Nodes stuck in "Initial" state
- Check they can reach the L0 peer (GL0 or ML0)
- Verify environment variables are correct
- Check docker logs: `docker logs <container>`

### L1 cluster not forming
- Ensure each node has a DIFFERENT key
- Verify join command uses correct peer ID and p2p port
- Wait longer - joining can take 10-30 seconds

### Indexer shows empty data
- Trigger webhook manually with current ordinal
- Check `METAGRAPH_ML0_URL` env var is correct
- Verify database migrations ran

### Bridge fails to submit
- Check `METAGRAPH_DL1_URL` env var is correct
- Verify DL1 is accessible from services node
- Check bridge logs for specific error

---

## Quick Reference

| Layer | Port (Public) | Port (P2P) | Port (CLI) |
|-------|---------------|------------|------------|
| GL0   | 9000          | 9001       | 9002       |
| ML0   | 9200          | 9201       | 9202       |
| CL1-1 | 9300          | 9301       | 9302       |
| CL1-2 | 9310          | 9311       | 9312       |
| CL1-3 | 9320          | 9321       | 9322       |
| DL1-1 | 9400          | 9401       | 9402       |
| DL1-2 | 9410          | 9411       | 9412       |
| DL1-3 | 9420          | 9421       | 9422       |

| Service  | Port |
|----------|------|
| Bridge   | 3030 |
| Indexer  | 3031 |
| Gateway  | 4000 |
| Explorer | 8080 |
| Postgres | 5432 |
| Redis    | 6379 |
