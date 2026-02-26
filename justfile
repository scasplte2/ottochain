# OttoChain Justfile
# Local development, testing, and Docker validation

set shell := ["bash", "-uc"]

# Default: show available recipes
default:
    @just --list

# ============ Build ============

# Build all JARs (ML0, CL1, DL1)
build:
    source ~/.sdkman/bin/sdkman-init.sh && sbt "currencyL0/assembly" "currencyL1/assembly" "dataL1/assembly"

# Build Docker image locally
build-image tag="local":
    docker build -t ghcr.io/scasplte2/ottochain-metagraph:{{tag}} .

# Build Docker image with no cache
build-image-fresh tag="local":
    docker build --no-cache -t ghcr.io/scasplte2/ottochain-metagraph:{{tag}} .

# ============ Test ============

# Run unit tests
test:
    source ~/.sdkman/bin/sdkman-init.sh && sbt test

# Run specific test suite
test-only suite:
    source ~/.sdkman/bin/sdkman-init.sh && sbt "testOnly *{{suite}}*"

# Validate Docker image - check all layer CLIs respond correctly
test-image tag="local":
    #!/usr/bin/env bash
    set -e
    IMAGE="ghcr.io/scasplte2/ottochain-metagraph:{{tag}}"
    echo "🔍 Testing Docker image: $IMAGE"
    
    echo ""
    echo "=== Checking JAR files exist ==="
    docker run --rm --entrypoint '' $IMAGE ls -la /ottochain/jars/
    
    echo ""
    echo "=== GL0 (Global L0) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/gl0.jar --help | head -20
    
    echo ""
    echo "=== GL1 (Global L1) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/gl1.jar --help | head -20
    
    echo ""
    echo "=== ML0 (Metagraph L0) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/ml0.jar --help | head -20
    
    echo ""
    echo "=== CL1 (Currency L1) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/cl1.jar --help | head -20
    
    echo ""
    echo "=== DL1 (Data L1) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/dl1.jar --help | head -20
    
    echo ""
    echo "=== Checking run-validator flags (ML0) ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/ml0.jar run-validator --help 2>&1 | grep -E "^\s+--" | head -20
    
    echo ""
    echo "✅ All layer CLIs responding correctly"

# Validate entrypoint works for each layer
test-entrypoint tag="local":
    #!/usr/bin/env bash
    set -e
    IMAGE="ghcr.io/scasplte2/ottochain-metagraph:{{tag}}"
    echo "🔍 Testing entrypoint for each layer..."
    
    for layer in gl0 gl1 ml0 cl1 dl1; do
        echo ""
        echo "=== Testing LAYER=$layer ==="
        # Should fail with missing keystore but show it's trying to start
        docker run --rm -e LAYER=$layer -e CL_PASSWORD=test $IMAGE 2>&1 | head -5 || true
    done
    
    echo ""
    echo "✅ Entrypoint responds for all layers"

# ============ Local Stack ============

# First-time local setup (generates keys, genesis, .env)
local-setup tag="local":
    IMAGE=ghcr.io/scasplte2/ottochain-metagraph:{{tag}} ./scripts/local-setup.sh

# Start local development stack (requires docker-compose.local.yml)
up:
    docker compose -f docker-compose.local.yml up -d
    @echo "Waiting for services to start..."
    @sleep 10
    just health

# Stop local stack
down:
    docker compose -f docker-compose.local.yml down

# Stop and remove all data
down-clean:
    docker compose -f docker-compose.local.yml down -v
    rm -rf .local/data/*

# View logs
logs service="":
    docker compose -f docker-compose.local.yml logs -f {{service}}

# Health check all services
health:
    #!/usr/bin/env bash
    set -e
    echo "🏥 Health Check"
    
    check_health() {
        local name=$1
        local port=$2
        local status=$(curl -sf http://localhost:$port/node/info 2>/dev/null | jq -r '.state' || echo "DOWN")
        printf "  %-10s :%d  %s\n" "$name" "$port" "$status"
    }
    
    check_health "GL0" 9000
    check_health "GL1" 9100
    check_health "ML0" 9200
    check_health "CL1" 9300
    check_health "DL1" 9400

# ============ Utilities ============

# Generate a new keystore
keygen output="key.p12" alias="alias" password="password":
    docker run --rm -v $(pwd):/out --entrypoint '' \
        ghcr.io/scasplte2/ottochain-metagraph:local \
        java -jar /ottochain/jars/cl1.jar generate-key \
        --output /out/{{output}} --alias {{alias}} --password {{password}}

# Show wallet address from keystore
show-address keystore="key.p12" alias="alias" password="password":
    docker run --rm -v $(pwd):/keys --entrypoint '' \
        -e CL_KEYSTORE=/keys/{{keystore}} \
        -e CL_KEYALIAS={{alias}} \
        -e CL_PASSWORD={{password}} \
        ghcr.io/scasplte2/ottochain-metagraph:local \
        java -jar /ottochain/jars/ml0.jar show-address

# Clean build artifacts
clean:
    source ~/.sdkman/bin/sdkman-init.sh && sbt clean
    rm -rf target/
    rm -rf modules/*/target/

# Clean Docker images
clean-docker:
    docker rmi ghcr.io/scasplte2/ottochain-metagraph:local 2>/dev/null || true
    docker image prune -f

# ============ CI Validation ============

# Full pre-push validation (what CI should do)
validate tag="local":
    @echo "🚀 Running full validation..."
    just build-image {{tag}}
    just test-image {{tag}}
    just test-entrypoint {{tag}}
    @echo ""
    @echo "✅ All validations passed - safe to push"

# Quick smoke test on existing image
smoke tag="local":
    just test-image {{tag}}

# ============ E2E Tests ============
# Requires tessellation repo at ~/repos/tessellation

TESSELLATION_DIR := env_var_or_default("TESSELLATION_DIR", env_var("HOME") + "/repos/tessellation")

# Resolve tessellation version from metakit dependency on Maven Central
resolve-tess-version:
    #!/usr/bin/env bash
    METAKIT_VER=$(grep 'val metakit =' project/Dependencies.scala | head -1 | grep -o '"[^"]*"' | tr -d '"')
    TESS_VER=$(curl -sf "https://repo1.maven.org/maven2/io/constellationnetwork/metakit_2.13/${METAKIT_VER}/metakit_2.13-${METAKIT_VER}.pom" \
      | grep -A1 'tessellation-sdk' | grep '<version>' | grep -o '[0-9][^<]*')
    echo "ottochain → metakit ${METAKIT_VER} → tessellation ${TESS_VER}"

# Run full E2E test suite (start cluster, test, stop)
# Uses --hypergraph-release to download pre-built tessellation JARs instead of building from source.
# metakit + tessellation-sdk resolve from Maven Central — no publishLocal needed.
e2e:
    #!/usr/bin/env bash
    set -e
    echo "🚀 Running E2E test suite..."
    echo ""
    
    # Source SDKMAN and export to subprocesses
    source ~/.sdkman/bin/sdkman-init.sh
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    export PATH="$HOME/.sdkman/candidates/sbt/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
    
    # Check tessellation exists
    if [ ! -d "{{ TESSELLATION_DIR }}" ]; then
        echo "❌ Tessellation not found at {{ TESSELLATION_DIR }}"
        echo "   Clone it with: git clone https://github.com/Constellation-Labs/tessellation.git {{ TESSELLATION_DIR }}"
        exit 1
    fi
    
    # Resolve tessellation version from metakit dependency
    METAKIT_VER=$(grep 'val metakit =' project/Dependencies.scala | head -1 | grep -o '"[^"]*"' | tr -d '"')
    TESS_VER=$(curl -sf "https://repo1.maven.org/maven2/io/constellationnetwork/metakit_2.13/${METAKIT_VER}/metakit_2.13-${METAKIT_VER}.pom" \
      | grep -A1 'tessellation-sdk' | grep '<version>' | grep -o '[0-9][^<]*')
    echo "Dependency chain: ottochain → metakit ${METAKIT_VER} → tessellation ${TESS_VER}"
    
    OTTOCHAIN_DIR="{{ justfile_directory() }}"
    
    # Start cluster with pre-built hypergraph release
    echo ""
    echo "🔧 Starting E2E cluster (hypergraph release v${TESS_VER})..."
    cd "{{ TESSELLATION_DIR }}"
    just up --hypergraph-release="v${TESS_VER}" --metagraph="$OTTOCHAIN_DIR" --dl1 --data
    
    cd "$OTTOCHAIN_DIR"
    
    # Wait for cluster
    just _e2e-wait
    
    # Run tests
    echo ""
    echo "🧪 Running E2E tests..."
    cd e2e-test
    npm install --silent
    npm test
    
    echo ""
    echo "✅ E2E tests complete!"
    
    # Cleanup
    cd "{{ TESSELLATION_DIR }}"
    just down

# Start E2E cluster (for interactive development)
# Uses --hypergraph-release to skip building tessellation from source.
e2e-up:
    #!/usr/bin/env bash
    set -e
    
    # Source SDKMAN and export to subprocesses
    source ~/.sdkman/bin/sdkman-init.sh
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    export PATH="$HOME/.sdkman/candidates/sbt/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
    
    if [ ! -d "{{ TESSELLATION_DIR }}" ]; then
        echo "❌ Tessellation not found at {{ TESSELLATION_DIR }}"
        exit 1
    fi
    
    # Resolve tessellation version from metakit dependency
    METAKIT_VER=$(grep 'val metakit =' project/Dependencies.scala | head -1 | grep -o '"[^"]*"' | tr -d '"')
    TESS_VER=$(curl -sf "https://repo1.maven.org/maven2/io/constellationnetwork/metakit_2.13/${METAKIT_VER}/metakit_2.13-${METAKIT_VER}.pom" \
      | grep -A1 'tessellation-sdk' | grep '<version>' | grep -o '[0-9][^<]*')
    echo "Dependency chain: ottochain → metakit ${METAKIT_VER} → tessellation ${TESS_VER}"
    
    OTTOCHAIN_DIR="{{ justfile_directory() }}"
    cd "{{ TESSELLATION_DIR }}"
    
    just up --hypergraph-release="v${TESS_VER}" --metagraph="$OTTOCHAIN_DIR" --dl1 --data
    
    cd "$OTTOCHAIN_DIR"
    just _e2e-wait
    just e2e-health

# Stop E2E cluster
e2e-down:
    cd "{{ TESSELLATION_DIR }}" && just down

# Health check E2E cluster
e2e-health:
    #!/usr/bin/env bash
    echo "🏥 E2E Cluster Health"
    echo ""
    
    check_health() {
        local name=$1
        local port=$2
        local info=$(curl -sf http://localhost:$port/node/info 2>/dev/null)
        if [ -n "$info" ]; then
            local state=$(echo "$info" | jq -r '.state')
            printf "  %-10s :%d  %s\n" "$name" "$port" "$state"
        else
            printf "  %-10s :%d  DOWN\n" "$name" "$port"
        fi
    }
    
    check_cluster() {
        local name=$1
        local port=$2
        local size=$(curl -sf http://localhost:$port/cluster/info 2>/dev/null | jq 'length' || echo "0")
        printf "  %-10s cluster size: %s\n" "$name" "$size"
    }
    
    echo "Nodes:"
    check_health "GL0" 9000
    check_health "ML0" 9200
    check_health "DL1-0" 9400
    check_health "DL1-1" 9410
    check_health "DL1-2" 9420
    
    echo ""
    echo "Clusters:"
    check_cluster "GL0" 9000
    check_cluster "ML0" 9200
    check_cluster "DL1" 9400

# Run E2E tests against already-running cluster
e2e-test:
    cd e2e-test && npm install --silent && npm test

# Interactive E2E terminal
e2e-terminal:
    cd e2e-test && npm install --silent && npm run terminal

# Internal: wait for E2E cluster to be healthy
_e2e-wait:
    #!/usr/bin/env bash
    echo "⏳ Waiting for cluster to be ready..."
    
    wait_for_node() {
        local name=$1 port=$2 max_wait=${3:-120}
        local iterations=$((max_wait / 2))
        for i in $(seq 1 $iterations); do
            if curl -sf "http://localhost:${port}/node/info" 2>/dev/null | grep -q '"state":"Ready"'; then
                echo "  ✓ $name (port $port): Ready"
                return 0
            fi
            sleep 2
        done
        echo "  ✗ $name (port $port): TIMEOUT after ${max_wait}s"
        return 1
    }
    
    echo ""
    echo "Genesis nodes (120s timeout):"
    wait_for_node "GL0" 9000 120
    wait_for_node "ML0" 9200 120
    wait_for_node "DL1-0" 9400 120
    
    echo ""
    echo "DL1 validators (240s timeout):"
    wait_for_node "DL1-1" 9410 240
    wait_for_node "DL1-2" 9420 240
    
    echo ""
    echo "Verifying cluster sizes..."
    sleep 5
    
    DL1_SIZE=$(curl -s http://localhost:9400/cluster/info 2>/dev/null | jq 'length' || echo "0")
    if [ "$DL1_SIZE" -lt 3 ]; then
        echo "⚠️  DL1 cluster size is $DL1_SIZE (expected 3)"
    else
        echo "✅ DL1 cluster size: $DL1_SIZE"
    fi
