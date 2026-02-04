#!/bin/bash
# Generate TypeScript types from protobuf definitions
# Run with: npm run generate:proto

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(dirname "$SCRIPT_DIR")"
PROTO_DIR="$SDK_DIR/proto"
OUT_DIR="$SDK_DIR/src/generated"

# Ensure output directory exists
mkdir -p "$OUT_DIR/ottochain/v1"

echo "Generating TypeScript from proto files..."
cd "$SDK_DIR"

./node_modules/.bin/protoc \
  --plugin=./node_modules/.bin/protoc-gen-ts_proto \
  --ts_proto_out="$OUT_DIR" \
  --ts_proto_opt=esModuleInterop=true \
  --ts_proto_opt=outputEncodeMethods=false \
  --ts_proto_opt=outputJsonMethods=true \
  --ts_proto_opt=outputClientImpl=false \
  --ts_proto_opt=snakeToCamel=true \
  --ts_proto_opt=useOptionals=messages \
  --ts_proto_opt=oneof=unions \
  --proto_path="$PROTO_DIR" \
  "$PROTO_DIR/ottochain/v1/common.proto" \
  "$PROTO_DIR/ottochain/v1/fiber.proto" \
  "$PROTO_DIR/ottochain/v1/records.proto" \
  "$PROTO_DIR/ottochain/v1/messages.proto"

echo "✓ Generated TypeScript types in src/generated/"
echo "  Files:"
ls "$OUT_DIR/ottochain/v1/"
