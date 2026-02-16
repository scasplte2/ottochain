#!/bin/bash

# 03-deploy-jars.sh
# Deploy built JARs to all metagraph nodes
#
# This script:
# 1. Locates built JAR files from sbt assembly
# 2. Deploys JARs to all nodes via SCP
# 3. Verifies deployment using SHA256 hash comparison
# 4. Reports deployment status for each node

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Parse arguments
FROM_GITHUB=false
GITHUB_VERSION="latest"

while [[ $# -gt 0 ]]; do
  case $1 in
    --help|-h)
      cat << EOF
Deploy OttoChain metagraph JARs to all nodes

USAGE:
    ./deploy/scripts/03-deploy-jars.sh [OPTIONS]

OPTIONS:
    -h, --help           Show this help message
    --from-github        Download JARs from GitHub Releases (recommended)
    --version VER        Specific version to download from GitHub (default: latest)

METHODS:
    Default:             Use locally built JARs (requires sbt assembly)
    --from-github:       Download pre-built JARs from GitHub Releases

EXAMPLES:
    ./deploy/scripts/03-deploy-jars.sh                    # Use local JARs
    ./deploy/scripts/03-deploy-jars.sh --from-github     # Download latest
    ./deploy/scripts/03-deploy-jars.sh --from-github --version 0.7.4

DESCRIPTION:
    Deploy built metagraph JARs to all nodes. The --from-github option
    eliminates the need for manual 'sbt assembly' builds by downloading
    pre-built JARs from GitHub Releases.
EOF
      exit 0
      ;;
    --from-github)
      FROM_GITHUB=true
      shift
      ;;
    --version)
      GITHUB_VERSION="$2"
      shift 2
      ;;
    *)
      print_error "Unknown option: $1"
      print_error "Use --help for usage information"
      exit 1
      ;;
  esac
done

print_title "Ottochain Metagraph - Deploy JARs"

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd scp
require_cmd ssh

# Validate SSH key
if ! check_ssh_key; then
  exit 1
fi

# Change to project root
cd "$PROJECT_ROOT"

# Handle GitHub download if requested
if [[ "$FROM_GITHUB" == "true" ]]; then
  print_title "Downloading JARs from GitHub Releases"
  
  if [[ ! -f "$SCRIPT_DIR/download-ottochain-jars.sh" ]]; then
    print_error "GitHub download script not found: $SCRIPT_DIR/download-ottochain-jars.sh"
    exit 1
  fi
  
  # Download JARs from GitHub
  if [[ "$GITHUB_VERSION" == "latest" ]]; then
    print_status "Downloading latest release JARs..."
    if ! "$SCRIPT_DIR/download-ottochain-jars.sh" --verify; then
      print_error "Failed to download JARs from GitHub Releases"
      exit 1
    fi
  else
    print_status "Downloading JARs for version $GITHUB_VERSION..."
    if ! "$SCRIPT_DIR/download-ottochain-jars.sh" --version "$GITHUB_VERSION" --verify; then
      print_error "Failed to download JARs for version $GITHUB_VERSION"
      exit 1
    fi
  fi
  
  print_success "GitHub JARs downloaded successfully"
  echo ""
fi

# Locate JARs (either built or downloaded)
if [[ "$FROM_GITHUB" == "true" ]]; then
  print_title "Locating Downloaded JARs"
else
  print_title "Locating Built JARs"
fi

declare -A JAR_PATHS
declare -A JAR_HASHES
declare -A TARGET_NAMES

LOCATE_SUCCESS=true

for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

  print_status "Locating $target_dir JAR..."

  if [[ "$FROM_GITHUB" == "true" ]]; then
    # Use downloaded JAR from GitHub
    jar_file="$SCRIPT_DIR/../jars/$target_jar_name"
    
    if [[ ! -f "$jar_file" ]]; then
      print_error "Downloaded JAR not found: $jar_file"
      print_status "GitHub JAR download may have failed"
      LOCATE_SUCCESS=false
      continue
    fi
  else
    # Find locally built JAR file
    jar_file=$(find "$PROJECT_ROOT/modules"/*/target/scala-2.13/ -name "${jar_pattern}-*.jar" -type f 2>/dev/null | head -1)

    if [[ -z "$jar_file" ]] || [[ ! -f "$jar_file" ]]; then
      print_error "JAR file not found for $target_dir (pattern: ${jar_pattern}-*.jar)"
      print_status "Run ./deploy/scripts/02-build-jars.sh first to build the JARs"
      print_status "Or use --from-github to download pre-built JARs"
      LOCATE_SUCCESS=false
      continue
    fi
  fi

  # Calculate hash
  jar_hash=$(calculate_hash "$jar_file")

  if [[ $? -ne 0 ]] || [[ -z "$jar_hash" ]]; then
    print_error "Failed to calculate hash for $jar_file"
    LOCATE_SUCCESS=false
    continue
  fi

  # Store information
  JAR_PATHS["$target_dir"]="$jar_file"
  JAR_HASHES["$target_dir"]="$jar_hash"
  TARGET_NAMES["$target_dir"]="$target_jar_name"

  print_success "  Found: $(basename "$jar_file")"
  print_status "  Hash: $jar_hash"
done

if [[ "$LOCATE_SUCCESS" != "true" ]]; then
  print_error "Failed to locate all required JAR files"
  exit 1
fi

# Deploy to each node
print_title "Deploying JARs to Nodes"

deploy_to_node() {
  local node_ip="$1"
  local node_num="$2"

  print_status "Deploying to Node $node_num ($node_ip)..."

  # Test SSH connectivity
  if ! test_ssh_connectivity "$node_ip" "$node_num" >/dev/null 2>&1; then
    print_error "Cannot connect to Node $node_num"
    return 1
  fi

  # Deploy each module's JAR
  for module_spec in "${MODULES[@]}"; do
    IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

    local jar_file="${JAR_PATHS[$target_dir]}"
    local jar_hash="${JAR_HASHES[$target_dir]}"
    local remote_dir="$REMOTE_CODE_DIR/$target_dir"
    local remote_jar="$remote_dir/$target_jar_name"

    print_status "  Deploying $target_jar_name to $target_dir..."

    # Ensure remote directory exists
    if ! ssh_exec "$node_ip" "mkdir -p $remote_dir" >/dev/null 2>&1; then
      print_error "Failed to create directory $remote_dir on Node $node_num"
      return 1
    fi

    # Deploy JAR
    if ! scp_to_node "$jar_file" "$node_ip" "$remote_jar"; then
      print_error "Failed to copy $target_jar_name to Node $node_num"
      return 1
    fi

    # Verify deployment
    print_status "  Verifying $target_jar_name..."
    if ! verify_remote_hash "$node_ip" "$remote_jar" "$jar_hash"; then
      print_error "Hash verification failed for $target_jar_name on Node $node_num"
      return 1
    fi

    print_success "  ✓ $target_jar_name deployed and verified"
  done

  print_success "Node $node_num deployment completed successfully"
  return 0
}

# Deploy to all nodes
DEPLOYMENT_SUCCESS=true

for i in "${!NODE_IPS[@]}"; do
  if ! deploy_to_node "${NODE_IPS[$i]}" "$((i+1))"; then
    DEPLOYMENT_SUCCESS=false
    print_error "Deployment failed on Node $((i+1))"
  fi
  echo ""
done

if [[ "$DEPLOYMENT_SUCCESS" != "true" ]]; then
  print_error "Deployment failed for one or more nodes"
  exit 1
fi

# Deployment summary
print_title "Deployment Summary"

print_success "All JARs deployed successfully to all nodes!"
print_status ""

if [[ "$FROM_GITHUB" == "true" ]]; then
  print_status "✅ Source: GitHub Releases (v$GITHUB_VERSION) - No manual builds needed!"
else
  print_status "📦 Source: Local builds (sbt assembly)"
fi

print_status ""
print_status "Deployed modules:"

for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"
  print_status "  $sbt_module → ~/$REMOTE_CODE_DIR/$target_dir/$target_jar_name"
done

print_status ""
print_status "Deployment locations:"
for i in "${!NODE_IPS[@]}"; do
  print_status "  Node $((i+1)) (${NODE_IPS[$i]}): ~/$REMOTE_CODE_DIR/"
done

print_status ""
print_status "💡 Pro tip: Use --from-github to skip manual builds in the future"
print_status ""
print_status "Next steps:"
print_status "  Create genesis: ./deploy/scripts/04-create-genesis.sh"
