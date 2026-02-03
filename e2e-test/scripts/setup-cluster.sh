#!/bin/bash
set -euo pipefail

# Setup local tessellation cluster for E2E testing
# Usage: ./setup-cluster.sh [--skip-build]

TESSELLATION_VERSION="${TESSELLATION_VERSION:-4.0.0-rc.2}"
TESSELLATION_REPO="${TESSELLATION_REPO:-https://github.com/Constellation-Labs/tessellation.git}"
TESSELLATION_DIR="${TESSELLATION_DIR:-./tessellation}"
METAGRAPH_PATH="${METAGRAPH_PATH:-$(cd "$(dirname "$0")/../.." && pwd)}"
SKIP_BUILD="${1:-}"

echo "=== OttoChain E2E Cluster Setup ==="
echo "Tessellation version: v${TESSELLATION_VERSION}"
echo "Metagraph path: ${METAGRAPH_PATH}"
echo ""

# Clone tessellation if not present
if [ ! -d "$TESSELLATION_DIR" ]; then
    echo "[1/6] Cloning tessellation..."
    git clone --depth 1 --branch "v${TESSELLATION_VERSION}" "$TESSELLATION_REPO" "$TESSELLATION_DIR"
else
    echo "[1/6] Tessellation directory exists, checking out v${TESSELLATION_VERSION}..."
    cd "$TESSELLATION_DIR"
    git fetch --depth 1 origin "v${TESSELLATION_VERSION}"
    git checkout "v${TESSELLATION_VERSION}"
    cd ..
fi

# Apply compile fix for v4.0.0-rc.2
echo "[2/6] Applying compile fix..."
PATCH_FILE="$(dirname "$0")/../patches/v4.0.0-rc.2-compile-fix.patch"
if [ -f "$PATCH_FILE" ]; then
    cd "$TESSELLATION_DIR"
    if git apply --check "$PATCH_FILE" 2>/dev/null; then
        git apply "$PATCH_FILE"
        echo "  Applied compile fix patch"
    else
        echo "  Patch already applied or not needed"
    fi
    cd ..
else
    echo "  Warning: Patch file not found at $PATCH_FILE"
fi

echo "[3/6] Skipping compose-metagraph.yaml fix (not needed - env vars flow through node-key-env-setup.sh)"

# Build tessellation and metagraph
if [ "$SKIP_BUILD" != "--skip-build" ]; then
    echo "[4/6] Building tessellation JARs (this takes ~5 min)..."
    cd "$TESSELLATION_DIR"
    export RELEASE_TAG="v${TESSELLATION_VERSION}"
    sbt -batch "dagL0/assembly; dagL1/assembly; keytool/assembly; wallet/assembly"
    cd ..
    
    echo "[5/6] Building metagraph..."
    cd "$METAGRAPH_PATH"
    sbt -batch "sharedData/assembly"
    cd -
else
    echo "[4/6] Skipping tessellation build (--skip-build)"
    echo "[5/6] Skipping metagraph build (--skip-build)"
fi

# Start cluster
echo "[6/6] Starting cluster..."
cd "$TESSELLATION_DIR"
export RELEASE_TAG="v${TESSELLATION_VERSION}"
export TESSELLATION_VERSION="${TESSELLATION_VERSION}"

# Use compose-runner directly (just may not be available in CI)
docker/compose-runner.sh --up --metagraph="$METAGRAPH_PATH" --dl1 --data --skip-assembly

echo ""
echo "=== Waiting for cluster health ==="

wait_for_node() {
    local name=$1
    local port=$2
    local max_attempts=60
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if curl -sf "http://localhost:${port}/node/info" | grep -q '"state":"Ready"'; then
            echo "  $name (port $port): Ready"
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    echo "  $name (port $port): TIMEOUT"
    return 1
}

echo "Waiting for nodes to become Ready..."
wait_for_node "GL0" 9000
wait_for_node "ML0" 9200
wait_for_node "DL1-0" 9400
wait_for_node "DL1-1" 9410
wait_for_node "DL1-2" 9420

echo ""
echo "=== Cluster is ready! ==="
echo "Run E2E tests with: cd e2e-test && npm test"
