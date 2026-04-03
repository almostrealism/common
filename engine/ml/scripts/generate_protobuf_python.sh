#!/bin/bash
#
# Generate Python protobuf stubs from the .proto source files in this repo.
#
# This script generates collections_pb2.py (and any other proto stubs)
# into the same directory as the weight extraction scripts, so they can
# import them directly without depending on external repos.
#
# Requirements:
#   pip install grpcio-tools
#
# Usage:
#   ./engine/ml/scripts/generate_protobuf_python.sh
#
# This is an OPTIONAL step -- only needed when running the Python weight
# extraction scripts. The standard Maven build does NOT require Python.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PROTO_DIR="$REPO_ROOT/engine/ml/src/main/proto"
OUTPUT_DIR="$SCRIPT_DIR"

if ! python3 -c "from grpc_tools import protoc" 2>/dev/null; then
    echo "Error: grpcio-tools not found. Install with:"
    echo "  pip install grpcio-tools"
    exit 1
fi

echo "Generating Python protobuf stubs..."
echo "  Proto source: $PROTO_DIR"
echo "  Output:       $OUTPUT_DIR"

python3 -c "
from grpc_tools import protoc
import sys

result = protoc.main([
    'grpc_tools.protoc',
    '-I${PROTO_DIR}',
    '--python_out=${OUTPUT_DIR}',
    '${PROTO_DIR}/collections.proto'
])

if result != 0:
    print('protoc failed with exit code', result, file=sys.stderr)
    sys.exit(result)
"

echo "Generated:"
ls -la "$OUTPUT_DIR"/collections_pb2.py

echo "Done. Weight extraction scripts can now import collections_pb2 locally."
