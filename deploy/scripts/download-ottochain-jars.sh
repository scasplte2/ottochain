#!/bin/bash
# Download OttoChain JAR artifacts from GitHub Releases

set -euo pipefail

# Source configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Configuration
OTTOCHAIN_REPO_URL="https://api.github.com/repos/scasplte2/ottochain/releases"
OUTPUT_DIR="${SCRIPT_DIR}/../jars"
OTTOCHAIN_JARS=(
    "metagraph-l0"
    "currency-l1" 
    "data-l1"
)

#######################
# Functions
#######################

show_help() {
    cat << EOF
Download OttoChain JAR artifacts from GitHub Releases

USAGE:
    $0 [OPTIONS] [VERSION]

OPTIONS:
    -h, --help          Show this help message
    -v, --version VER   Download specific version (e.g., 0.7.4)
    -l, --latest        Download latest release (default)
    -o, --output DIR    Output directory (default: $OUTPUT_DIR)
    --list              List available releases
    --verify            Verify downloaded JARs

EXAMPLES:
    $0                  # Download latest release
    $0 -v 0.7.4         # Download specific version
    $0 --list           # Show available versions
    $0 --verify         # Download and verify checksums

NOTE:
    This replaces the manual 'sbt assembly + scp' process.
    JARs are automatically built and published by GitHub Actions.
EOF
}

list_releases() {
    print_status "Fetching available OttoChain releases..."
    
    local releases
    releases=$(curl -s "$OTTOCHAIN_REPO_URL" 2>/dev/null)
    
    if [[ $? -ne 0 ]] || [[ -z "$releases" ]]; then
        print_error "Failed to fetch releases from GitHub API"
        return 1
    fi
    
    echo "Available OttoChain releases:"
    echo "$releases" | jq -r '.[] | "  \(.tag_name) - \(.published_at) \(if .prerelease then "(prerelease)" else "" end)"' | head -10
    
    return 0
}

get_release_info() {
    local version="$1"
    
    if [[ "$version" == "latest" ]]; then
        curl -s "$OTTOCHAIN_REPO_URL/latest" 2>/dev/null
    else
        curl -s "$OTTOCHAIN_REPO_URL/tags/v$version" 2>/dev/null
    fi
}

download_ottochain_jar() {
    local jar_name="$1"
    local version="$2"
    local output_dir="$3"
    
    local release_info
    release_info=$(get_release_info "$version")
    
    if [[ $? -ne 0 ]] || [[ -z "$release_info" ]]; then
        print_error "Failed to fetch release information for version $version"
        return 1
    fi
    
    local tag_name
    tag_name=$(echo "$release_info" | jq -r '.tag_name')
    
    if [[ -z "$tag_name" ]] || [[ "$tag_name" == "null" ]]; then
        print_error "Version $version not found"
        return 1
    fi
    
    local actual_version
    actual_version=$(echo "$tag_name" | sed 's/^v//')
    
    local jar_filename="${jar_name}-${actual_version}.jar"
    local download_url
    download_url=$(echo "$release_info" | jq -r ".assets[] | select(.name == \"$jar_filename\") | .browser_download_url")
    
    if [[ -z "$download_url" ]] || [[ "$download_url" == "null" ]]; then
        print_error "$jar_filename not found in release $tag_name"
        print_status "Available assets:"
        echo "$release_info" | jq -r '.assets[].name' | sed 's/^/  /'
        return 1
    fi
    
    local output_file="$output_dir/${jar_name}.jar"
    
    # Check if already downloaded
    if [[ -f "$output_file" ]]; then
        local existing_size
        existing_size=$(stat -c%s "$output_file" 2>/dev/null || echo "0")
        local remote_size
        remote_size=$(echo "$release_info" | jq -r ".assets[] | select(.name == \"$jar_filename\") | .size")
        
        if [[ "$existing_size" == "$remote_size" ]]; then
            print_status "$jar_name.jar already up-to-date (v$actual_version)"
            return 0
        fi
    fi
    
    print_status "Downloading $jar_name.jar v$actual_version..."
    
    # Download with progress and resume support
    if ! curl -L -C - --progress-bar -o "$output_file" "$download_url"; then
        print_error "Failed to download $jar_filename"
        [[ -f "$output_file" ]] && rm "$output_file"
        return 1
    fi
    
    # Verify file size
    local downloaded_size
    downloaded_size=$(stat -c%s "$output_file" 2>/dev/null || echo "0")
    local expected_size
    expected_size=$(echo "$release_info" | jq -r ".assets[] | select(.name == \"$jar_filename\") | .size")
    
    if [[ "$downloaded_size" != "$expected_size" ]]; then
        print_error "Size mismatch for $jar_filename:"
        print_error "  Expected: $expected_size bytes"
        print_error "  Downloaded: $downloaded_size bytes"
        rm "$output_file"
        return 1
    fi
    
    print_success "$jar_name.jar downloaded successfully ($(numfmt --to=iec "$downloaded_size"))"
    return 0
}

download_all_jars() {
    local version="$1"
    local output_dir="$2"
    local verify_checksums="$3"
    
    mkdir -p "$output_dir"
    
    local release_info
    release_info=$(get_release_info "$version")
    
    if [[ $? -ne 0 ]] || [[ -z "$release_info" ]]; then
        print_error "Failed to fetch release information for version $version"
        return 1
    fi
    
    local tag_name
    tag_name=$(echo "$release_info" | jq -r '.tag_name')
    local actual_version
    actual_version=$(echo "$tag_name" | sed 's/^v//')
    
    print_title "Downloading OttoChain JAR artifacts v$actual_version"
    
    # Download each JAR
    local failed_count=0
    for jar in "${OTTOCHAIN_JARS[@]}"; do
        if ! download_ottochain_jar "$jar" "$version" "$output_dir"; then
            ((failed_count++))
        fi
    done
    
    if [[ $failed_count -gt 0 ]]; then
        print_error "$failed_count JAR(s) failed to download"
        return 1
    fi
    
    # Verify checksums if requested
    if [[ "$verify_checksums" == "true" ]]; then
        print_status "Verifying JAR integrity..."
        
        # GitHub doesn't provide checksums, so we verify file sizes match release assets
        for jar in "${OTTOCHAIN_JARS[@]}"; do
            local jar_filename="${jar}-${actual_version}.jar"
            local output_file="$output_dir/${jar}.jar"
            local expected_size
            expected_size=$(echo "$release_info" | jq -r ".assets[] | select(.name == \"$jar_filename\") | .size")
            local actual_size
            actual_size=$(stat -c%s "$output_file" 2>/dev/null || echo "0")
            
            if [[ "$expected_size" != "$actual_size" ]]; then
                print_error "Verification failed for $jar.jar"
                return 1
            fi
        done
        
        print_success "All JARs verified successfully"
    fi
    
    print_success "Downloaded ${#OTTOCHAIN_JARS[@]} JAR artifacts to $output_dir"
    print_status "Files:"
    for jar in "${OTTOCHAIN_JARS[@]}"; do
        local output_file="$output_dir/${jar}.jar"
        local size
        size=$(stat -c%s "$output_file" 2>/dev/null | numfmt --to=iec)
        print_status "  ${jar}.jar ($size)"
    done
    
    return 0
}

#######################
# Main
#######################

main() {
    local version="latest"
    local output_dir="$OUTPUT_DIR"
    local list_only=false
    local verify_checksums=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            -h|--help)
                show_help
                exit 0
                ;;
            -v|--version)
                version="$2"
                shift 2
                ;;
            -l|--latest)
                version="latest"
                shift
                ;;
            -o|--output)
                output_dir="$2"
                shift 2
                ;;
            --list)
                list_only=true
                shift
                ;;
            --verify)
                verify_checksums=true
                shift
                ;;
            *)
                # Treat positional argument as version
                if [[ ! "$1" =~ ^- ]] && [[ -z "${version_from_positional:-}" ]]; then
                    version="$1"
                    version_from_positional=true
                    shift
                else
                    print_error "Unknown option: $1"
                    show_help >&2
                    exit 1
                fi
                ;;
        esac
    done
    
    # Validate requirements
    require_cmd curl
    require_cmd jq
    
    if [[ "$list_only" == "true" ]]; then
        list_releases
        exit $?
    fi
    
    # Download JARs
    if ! download_all_jars "$version" "$output_dir" "$verify_checksums"; then
        print_error "Failed to download OttoChain JARs"
        exit 1
    fi
    
    # Show next steps
    print_title "Next Steps"
    print_status "1. Copy JARs to deployment nodes:"
    for node_ip in "${NODE_IPS[@]}"; do
        print_status "   scp $output_dir/*.jar $SSH_USER@$node_ip:/opt/ottochain/jars/"
    done
    print_status ""
    print_status "2. Or use deploy script with --from-github flag (if available)"
    print_status ""
    print_success "JAR download complete! No more manual 'sbt assembly' needed."
}

# Run main function
main "$@"