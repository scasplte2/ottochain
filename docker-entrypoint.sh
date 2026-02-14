#!/bin/bash
set -e

# OttoChain Full Stack Entrypoint
# All 5 layers in one image for guaranteed compatibility.
# LAYER env var selects which to run: gl0, gl1, ml0, cl1, dl1
#
# Smart run mode detection by layer type:
#
# L0 layers (GL0, ML0):
#   - run-genesis: First-time genesis node (no ordinal data, genesis file provided)
#   - run-rollback: Restarting genesis node (ordinal data exists)
#   - run-validator: Joining an existing L0 cluster
#
# L1 layers (GL1, CL1, DL1):
#   - run-initial-validator: First validator to start L1 cluster (no ordinal data, IS_INITIAL=true)
#   - run-validator: Restarting OR joining an existing L1 cluster

case "${LAYER,,}" in
  gl0)
    JAR="/ottochain/jars/gl0.jar"
    PORT="${GL0_PORT:-9000}"
    P2P_PORT="${GL0_P2P_PORT:-9001}"
    CLI_PORT="${GL0_CLI_PORT:-9002}"
    DATA_DIR="/ottochain/data"
    LAYER_TYPE="l0"
    echo "Starting GL0 (Global L0) on port ${PORT}..."
    ;;
  gl1)
    JAR="/ottochain/jars/gl1.jar"
    PORT="${GL1_PORT:-9100}"
    P2P_PORT="${GL1_P2P_PORT:-9101}"
    CLI_PORT="${GL1_CLI_PORT:-9102}"
    DATA_DIR="/ottochain/data"
    LAYER_TYPE="l1"
    echo "Starting GL1 (Global L1 / DAG L1) on port ${PORT}..."
    ;;
  ml0)
    JAR="/ottochain/jars/ml0.jar"
    PORT="${ML0_PORT:-9200}"
    P2P_PORT="${ML0_P2P_PORT:-9201}"
    CLI_PORT="${ML0_CLI_PORT:-9202}"
    DATA_DIR="/ottochain/data"
    LAYER_TYPE="l0"
    echo "Starting ML0 (Metagraph L0) on port ${PORT}..."
    ;;
  cl1)
    JAR="/ottochain/jars/cl1.jar"
    PORT="${CL1_PORT:-9300}"
    P2P_PORT="${CL1_P2P_PORT:-9301}"
    CLI_PORT="${CL1_CLI_PORT:-9302}"
    DATA_DIR="/ottochain/data"
    LAYER_TYPE="l1"
    echo "Starting CL1 (Currency L1) on port ${PORT}..."
    ;;
  dl1)
    JAR="/ottochain/jars/dl1.jar"
    PORT="${DL1_PORT:-9400}"
    P2P_PORT="${DL1_P2P_PORT:-9401}"
    CLI_PORT="${DL1_CLI_PORT:-9402}"
    DATA_DIR="/ottochain/data"
    LAYER_TYPE="l1"
    echo "Starting DL1 (Data L1) on port ${PORT}..."
    ;;
  *)
    echo "Error: LAYER must be one of: gl0, gl1, ml0, cl1, dl1"
    echo "Got: ${LAYER:-<not set>}"
    exit 1
    ;;
esac

if [ ! -f "$JAR" ]; then
  echo "Error: JAR not found at $JAR"
  ls -la /ottochain/jars/
  exit 1
fi

# ============================================================
# Smart Run Mode Detection
# ============================================================
# Check for existing ordinal data (indicates restart vs fresh start)
# ============================================================

ORDINAL_DIR="${DATA_DIR}/snapshot/ordinal"
HAS_ORDINAL_DATA=false

if [ -d "${ORDINAL_DIR}" ] && [ "$(ls -A ${ORDINAL_DIR} 2>/dev/null)" ]; then
  HAS_ORDINAL_DATA=true
  ORDINAL_COUNT=$(ls -1 "${ORDINAL_DIR}" 2>/dev/null | wc -l)
  echo "Found existing ordinal data: ${ORDINAL_COUNT} ordinal(s) in ${ORDINAL_DIR}"
fi

# IS_INITIAL marks this as the first/genesis node for its layer
# Can be set explicitly, or inferred from RUN_MODE containing "genesis" or "initial"
IS_INITIAL="${IS_INITIAL:-false}"
if [ -n "${RUN_MODE}" ]; then
  case "${RUN_MODE}" in
    run-genesis|run-initial-validator)
      IS_INITIAL=true
      ;;
  esac
fi

# Auto-detect run mode if not explicitly set
if [ -z "${RUN_MODE}" ]; then
  if [ "${LAYER_TYPE}" = "l0" ]; then
    # GL0 or ML0: genesis / rollback / validator
    if [ "${HAS_ORDINAL_DATA}" = "true" ]; then
      if [ "${IS_INITIAL}" = "true" ]; then
        RUN_MODE="run-rollback"
        echo "Auto-detected RUN_MODE=run-rollback (L0 initial node restart, ordinal data exists)"
      else
        RUN_MODE="run-validator"
        echo "Auto-detected RUN_MODE=run-validator (L0 validator restart)"
      fi
    elif [ "${IS_INITIAL}" = "true" ]; then
      RUN_MODE="run-genesis"
      echo "Auto-detected RUN_MODE=run-genesis (L0 initial node, no ordinal data)"
    else
      RUN_MODE="run-validator"
      echo "Auto-detected RUN_MODE=run-validator (L0 joining cluster)"
    fi
  else
    # GL1, CL1, DL1: initial-validator / validator
    if [ "${HAS_ORDINAL_DATA}" = "true" ]; then
      RUN_MODE="run-validator"
      echo "Auto-detected RUN_MODE=run-validator (L1 restart, ordinal data exists)"
    elif [ "${IS_INITIAL}" = "true" ]; then
      RUN_MODE="run-initial-validator"
      echo "Auto-detected RUN_MODE=run-initial-validator (L1 initial node, no ordinal data)"
    else
      RUN_MODE="run-validator"
      echo "Auto-detected RUN_MODE=run-validator (L1 joining cluster)"
    fi
  fi
else
  echo "Using explicit RUN_MODE=${RUN_MODE}"
  
  # Safety warnings for potential issues
  if [ "${RUN_MODE}" = "run-genesis" ] && [ "${HAS_ORDINAL_DATA}" = "true" ]; then
    echo "⚠️  WARNING: run-genesis requested but ordinal data exists!"
    echo "   This will likely fail with 'Ordinal already exists' error."
    echo "   Consider using run-rollback or wiping ${ORDINAL_DIR}"
  fi
  if [ "${RUN_MODE}" = "run-initial-validator" ] && [ "${HAS_ORDINAL_DATA}" = "true" ]; then
    echo "⚠️  WARNING: run-initial-validator requested but ordinal data exists!"
    echo "   This may cause issues. Consider using run-validator for restarts."
  fi
fi

echo "Final: LAYER=${LAYER}, LAYER_TYPE=${LAYER_TYPE}, RUN_MODE=${RUN_MODE}, IS_INITIAL=${IS_INITIAL}"

# Build command line args
ARGS=""

# Verify keystore and password are set (passed via env vars to JAR)
if [ -f "${CL_KEYSTORE}" ]; then
  if [ -z "${CL_PASSWORD}" ]; then
    echo "Error: CL_PASSWORD is required when using a keystore"
    exit 1
  fi
  # Note: CL_KEYSTORE, CL_KEYALIAS, CL_PASSWORD are read directly
  # by tessellation from env vars (not CLI flags as of v4.x)
  echo "Using keystore: ${CL_KEYSTORE}"
fi

# Add environment
ARGS="${ARGS} --env ${CL_APP_ENV:-testnet}"

# Add ports
ARGS="${ARGS} --public-port ${PORT}"
ARGS="${ARGS} --p2p-port ${P2P_PORT}"
ARGS="${ARGS} --cli-port ${CLI_PORT}"

# Add external IP if provided
if [ -n "${CL_EXTERNAL_IP}" ]; then
  ARGS="${ARGS} --ip ${CL_EXTERNAL_IP}"
fi

# Add collateral (default 0 for metagraph)
ARGS="${ARGS} --collateral ${CL_COLLATERAL:-0}"

# Layer-specific peer configuration
case "${LAYER,,}" in
  gl1)
    # GL1 needs GL0 peer info
    if [ -n "${CL_L0_PEER_ID}" ]; then
      ARGS="${ARGS} --l0-peer-id ${CL_L0_PEER_ID}"
      ARGS="${ARGS} --l0-peer-host ${CL_L0_PEER_HOST:-localhost}"
      ARGS="${ARGS} --l0-peer-port ${CL_L0_PEER_PORT:-9000}"
    fi
    ;;
  ml0|cl1|dl1)
    # Metagraph layers need GL0 peer info
    if [ -n "${CL_GLOBAL_L0_PEER_ID}" ]; then
      ARGS="${ARGS} --global-l0-peer-id ${CL_GLOBAL_L0_PEER_ID}"
      ARGS="${ARGS} --global-l0-peer-host ${CL_GLOBAL_L0_PEER_HOST:-localhost}"
      ARGS="${ARGS} --global-l0-peer-port ${CL_GLOBAL_L0_PEER_PORT:-9000}"
    fi
    
    # CL1 and DL1 also need ML0 peer info
    if [ "${LAYER,,}" = "cl1" ] || [ "${LAYER,,}" = "dl1" ]; then
      if [ -n "${CL_L0_PEER_ID}" ]; then
        ARGS="${ARGS} --l0-peer-id ${CL_L0_PEER_ID}"
        ARGS="${ARGS} --l0-peer-host ${CL_L0_PEER_HOST:-localhost}"
        ARGS="${ARGS} --l0-peer-port ${CL_L0_PEER_PORT:-9200}"
      fi
    fi
    
    # Token ID for metagraph layers
    if [ -n "${CL_TOKEN_ID}" ]; then
      ARGS="${ARGS} --l0-token-identifier ${CL_TOKEN_ID}"
    fi
    ;;
esac

# Genesis file handling for run-genesis mode (L0 layers only)
GENESIS_ARG=""
if [ "${RUN_MODE}" = "run-genesis" ]; then
  # Find genesis file (CSV for GL0, snapshot for ML0)
  if [ -n "${GENESIS_FILE}" ] && [ -f "${GENESIS_FILE}" ]; then
    GENESIS_PATH="${GENESIS_FILE}"
  elif [ "${LAYER,,}" = "gl0" ] && [ -f "/ottochain/genesis/genesis.csv" ]; then
    GENESIS_PATH="/ottochain/genesis/genesis.csv"
  elif [ "${LAYER,,}" = "gl0" ] && [ -f "/ottochain/genesis/gl0-genesis.csv" ]; then
    GENESIS_PATH="/ottochain/genesis/gl0-genesis.csv"
  elif [ "${LAYER,,}" = "ml0" ] && [ -f "/ottochain/data/genesis.snapshot" ]; then
    GENESIS_PATH="/ottochain/data/genesis.snapshot"
  elif [ "${LAYER,,}" = "ml0" ] && [ -f "/ottochain/genesis/genesis.snapshot" ]; then
    GENESIS_PATH="/ottochain/genesis/genesis.snapshot"
  fi
  
  if [ -n "${GENESIS_PATH}" ]; then
    echo "Using genesis file: ${GENESIS_PATH}"
    GENESIS_ARG="${GENESIS_PATH}"
  else
    echo "Error: run-genesis requires a genesis file but none found"
    echo "Expected locations:"
    echo "  GL0: /ottochain/genesis/genesis.csv or /ottochain/genesis/gl0-genesis.csv"
    echo "  ML0: /ottochain/data/genesis.snapshot or /ottochain/genesis/genesis.snapshot"
    echo "  Or set GENESIS_FILE env var"
    exit 1
  fi
fi

# Rollback hash handling for run-rollback mode (L0 layers only)
# run-rollback requires the hash of the snapshot to roll back to
ROLLBACK_ARG=""
if [ "${RUN_MODE}" = "run-rollback" ]; then
  HASH_DIR="${DATA_DIR}/snapshot/hash"
  
  # Allow explicit override via ROLLBACK_HASH env var
  if [ -n "${ROLLBACK_HASH}" ]; then
    ROLLBACK_ARG="${ROLLBACK_HASH}"
    echo "Using explicit rollback hash: ${ROLLBACK_ARG}"
  elif [ -d "${HASH_DIR}" ]; then
    # Find the latest snapshot hash by looking at the most recent file in hash directory
    # Structure: hash/<prefix>/<prefix2>/<full_hash>
    # The filename IS the hash
    LATEST_HASH_FILE=$(find "${HASH_DIR}" -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
    
    if [ -n "${LATEST_HASH_FILE}" ]; then
      ROLLBACK_ARG=$(basename "${LATEST_HASH_FILE}")
      echo "Auto-detected rollback hash from latest snapshot: ${ROLLBACK_ARG}"
    fi
  fi
  
  if [ -z "${ROLLBACK_ARG}" ]; then
    echo "Error: run-rollback requires a snapshot hash but none found"
    echo "Expected: snapshots in ${HASH_DIR}/<prefix>/<prefix2>/<hash>"
    echo "Or set ROLLBACK_HASH env var explicitly"
    exit 1
  fi
fi

# Add any extra args passed to container
ARGS="${ARGS} $@"

# Build final command
# Positional arguments go after options:
#   run-genesis <genesis_file>
#   run-rollback <rollback_hash>
if [ "${RUN_MODE}" = "run-genesis" ] && [ -n "${GENESIS_ARG}" ]; then
  CMD="java ${JAVA_OPTS} -jar \"${JAR}\" ${RUN_MODE} ${ARGS} \"${GENESIS_ARG}\""
elif [ "${RUN_MODE}" = "run-rollback" ] && [ -n "${ROLLBACK_ARG}" ]; then
  CMD="java ${JAVA_OPTS} -jar \"${JAR}\" ${RUN_MODE} ${ARGS} \"${ROLLBACK_ARG}\""
else
  CMD="java ${JAVA_OPTS} -jar \"${JAR}\" ${RUN_MODE} ${ARGS}"
fi

echo "Running: $CMD"
eval exec $CMD
