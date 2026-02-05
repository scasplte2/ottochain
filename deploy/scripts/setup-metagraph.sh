#!/bin/bash
# OttoChain Metagraph Node Setup Script
# Run this on your metagraph server

set -e

# Configuration - EDIT THESE
HOST="${HOST:-$(curl -s ifconfig.me)}"
TOKEN="${TOKEN:-DAG2NLnsdCvDovUT7nJxP4kPznqEramWozMhuKzN}"
TESS_VERSION="${TESS_VERSION:-v4.0.0-rc.2}"
BASE_DIR="${BASE_DIR:-/opt/ottochain}"

echo "========================================"
echo "OttoChain Metagraph Setup"
echo "========================================"
echo "Host: $HOST"
echo "Token: $TOKEN"
echo "Tessellation: $TESS_VERSION"
echo "Base dir: $BASE_DIR"
echo "========================================"

# Create directories
echo "[1/8] Creating directories..."
mkdir -p $BASE_DIR/{jars,keys1,keys2,keys3,keys4}
mkdir -p $BASE_DIR/data/{gl0,ml0,cl1,cl1-2,cl1-3,dl1,dl1-2,dl1-3}

# Download tessellation tools
echo "[2/8] Downloading tessellation tools..."
cd $BASE_DIR/jars
if [ ! -f cl-keytool.jar ]; then
  curl -L -o cl-keytool.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${TESS_VERSION}/cl-keytool.jar"
fi
if [ ! -f cl-wallet.jar ]; then
  curl -L -o cl-wallet.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${TESS_VERSION}/cl-wallet.jar"
fi

# Check for OttoChain JARs
echo "[3/8] Checking for OttoChain JARs..."
for jar in metagraph-l0.jar currency-l1.jar data-l1.jar; do
  if [ ! -f $BASE_DIR/jars/$jar ]; then
    echo "ERROR: Missing $jar - please copy OttoChain JARs to $BASE_DIR/jars/"
    exit 1
  fi
done
echo "All JARs present."

# Generate keys
echo "[4/8] Generating keys..."
for i in 1 2 3 4; do
  if [ ! -f $BASE_DIR/keys$i/key.p12 ]; then
    cd $BASE_DIR/keys$i
    CL_KEYSTORE=key.p12 CL_KEYALIAS=alias CL_PASSWORD=password \
      java -jar $BASE_DIR/jars/cl-keytool.jar generate
    echo "Generated key $i"
  else
    echo "Key $i already exists"
  fi
done

# Get peer ID from key1
cd $BASE_DIR/keys1
GL0_PEER_ID=$(CL_KEYSTORE=key.p12 CL_KEYALIAS=alias CL_PASSWORD=password \
  java -jar $BASE_DIR/jars/cl-wallet.jar show-id 2>/dev/null)
echo "Primary peer ID: $GL0_PEER_ID"

# Stop existing containers
echo "[5/8] Stopping existing containers..."
docker stop gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2 2>/dev/null || true
docker rm gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2 2>/dev/null || true

# Start GL0
echo "[6/8] Starting GL0..."
docker run -d --name gl0 --restart unless-stopped \
  --network host \
  -v $BASE_DIR/jars:/jars:ro \
  -v $BASE_DIR/keys1:/keys:ro \
  -v $BASE_DIR/data/gl0:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9000 -e CL_P2P_HTTP_PORT=9001 -e CL_CLI_HTTP_PORT=9002 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx2g -jar /jars/metagraph-l0.jar run-initial-validator

echo "Waiting for GL0..."
sleep 15
curl -s http://localhost:9000/node/info | jq .state

# Start ML0
echo "[7/8] Starting ML0..."
docker run -d --name ml0 --restart unless-stopped \
  --network host \
  -v $BASE_DIR/jars:/jars:ro \
  -v $BASE_DIR/keys1:/keys:ro \
  -v $BASE_DIR/data/ml0:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9200 -e CL_P2P_HTTP_PORT=9201 -e CL_CLI_HTTP_PORT=9202 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx2g -jar /jars/metagraph-l0.jar run-initial-validator

echo "Waiting for ML0..."
sleep 15
curl -s http://localhost:9200/node/info | jq .state

# Start CL1 cluster (3 nodes)
echo "[8/8] Starting CL1 and DL1 clusters..."

# CL1 Node 1
docker run -d --name cl1 --restart unless-stopped --network host \
  -v $BASE_DIR/jars:/jars:ro -v $BASE_DIR/keys1:/keys:ro \
  -v $BASE_DIR/data/cl1:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9300 -e CL_P2P_HTTP_PORT=9301 -e CL_CLI_HTTP_PORT=9302 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/currency-l1.jar run-initial-validator

sleep 8

# CL1 Node 2
docker run -d --name cl1-1 --restart unless-stopped --network host \
  -v $BASE_DIR/jars:/jars:ro -v $BASE_DIR/keys2:/keys:ro \
  -v $BASE_DIR/data/cl1-2:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9310 -e CL_P2P_HTTP_PORT=9311 -e CL_CLI_HTTP_PORT=9312 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/currency-l1.jar run-validator

# CL1 Node 3
docker run -d --name cl1-2 --restart unless-stopped --network host \
  -v $BASE_DIR/jars:/jars:ro -v $BASE_DIR/keys3:/keys:ro \
  -v $BASE_DIR/data/cl1-3:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9320 -e CL_P2P_HTTP_PORT=9321 -e CL_CLI_HTTP_PORT=9322 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/currency-l1.jar run-validator

sleep 8

# Join CL1 cluster
curl -s -X POST "http://localhost:9312/cluster/join" -H "Content-Type: application/json" \
  -d "{\"id\": \"$GL0_PEER_ID\", \"ip\": \"$HOST\", \"p2pPort\": 9301}"
curl -s -X POST "http://localhost:9322/cluster/join" -H "Content-Type: application/json" \
  -d "{\"id\": \"$GL0_PEER_ID\", \"ip\": \"$HOST\", \"p2pPort\": 9301}"

# DL1 Node 1
docker run -d --name dl1 --restart unless-stopped --network host \
  -v $BASE_DIR/jars:/jars:ro -v $BASE_DIR/keys1:/keys:ro \
  -v $BASE_DIR/data/dl1:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9400 -e CL_P2P_HTTP_PORT=9401 -e CL_CLI_HTTP_PORT=9402 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/data-l1.jar run-initial-validator

sleep 8

# DL1 Node 2
docker run -d --name dl1-1 --restart unless-stopped --network host \
  -v $BASE_DIR/jars:/jars:ro -v $BASE_DIR/keys2:/keys:ro \
  -v $BASE_DIR/data/dl1-2:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9410 -e CL_P2P_HTTP_PORT=9411 -e CL_CLI_HTTP_PORT=9412 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/data-l1.jar run-validator

# DL1 Node 3
docker run -d --name dl1-2 --restart unless-stopped --network host \
  -v $BASE_DIR/jars:/jars:ro -v $BASE_DIR/keys3:/keys:ro \
  -v $BASE_DIR/data/dl1-3:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9420 -e CL_P2P_HTTP_PORT=9421 -e CL_CLI_HTTP_PORT=9422 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/data-l1.jar run-validator

sleep 8

# Join DL1 cluster
curl -s -X POST "http://localhost:9412/cluster/join" -H "Content-Type: application/json" \
  -d "{\"id\": \"$GL0_PEER_ID\", \"ip\": \"$HOST\", \"p2pPort\": 9401}"
curl -s -X POST "http://localhost:9422/cluster/join" -H "Content-Type: application/json" \
  -d "{\"id\": \"$GL0_PEER_ID\", \"ip\": \"$HOST\", \"p2pPort\": 9401}"

sleep 5

echo ""
echo "========================================"
echo "Setup Complete!"
echo "========================================"
echo "GL0: $(curl -s http://localhost:9000/node/info | jq -r .state)"
echo "ML0: $(curl -s http://localhost:9200/node/info | jq -r .state)"
echo "CL1 cluster: $(curl -s http://localhost:9300/cluster/info | jq length) nodes"
echo "DL1 cluster: $(curl -s http://localhost:9400/cluster/info | jq length) nodes"
echo "========================================"
echo "Peer ID: $GL0_PEER_ID"
echo "Token: $TOKEN"
echo "========================================"
