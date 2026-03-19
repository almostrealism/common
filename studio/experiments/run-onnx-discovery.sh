#!/bin/bash
#
# Run ONNX Prototype Discovery Test
#
# This script runs PrototypeDiscovery with ONNX-based feature computation.
# Run it twice - the second run should be faster because features are
# already persisted in the protobuf store.
#
# Usage:
#   ./run-onnx-discovery.sh [samples_dir] [store_dir] [models_dir]
#
# Defaults:
#   samples_dir: ~/Music/Samples
#   store_dir:   /tmp/onnx-discovery-store
#   models_dir:  ~/Documents/AlmostRealism/models
#

set -e

# Configuration
SAMPLES_DIR="${1:-$HOME/Music/Samples}"
STORE_DIR="${2:-/tmp/onnx-discovery-store}"
MODELS_DIR="${3:-$HOME/Documents/AlmostRealism/models}"
MAX_PROTOTYPES="${MAX_PROTOTYPES:-10}"

# Memory configuration
export AR_HARDWARE_MEMORY_SCALE="${AR_HARDWARE_MEMORY_SCALE:-7}"

# Verify paths
echo "=== ONNX Prototype Discovery ==="
echo "Samples:  $SAMPLES_DIR"
echo "Store:    $STORE_DIR"
echo "Models:   $MODELS_DIR"
echo "Max prototypes: $MAX_PROTOTYPES"
echo ""

if [[ ! -d "$SAMPLES_DIR" ]]; then
    echo "ERROR: Samples directory not found: $SAMPLES_DIR"
    exit 1
fi

if [[ ! -f "$MODELS_DIR/encoder.onnx" ]]; then
    echo "ERROR: encoder.onnx not found in: $MODELS_DIR"
    exit 1
fi

if [[ ! -f "$MODELS_DIR/decoder.onnx" ]]; then
    echo "ERROR: decoder.onnx not found in: $MODELS_DIR"
    exit 1
fi

# Check if store exists (determines if this is first or second run)
if [[ -d "$STORE_DIR" ]]; then
    ENTRY_COUNT=$(ls -1 "$STORE_DIR"/*.bin 2>/dev/null | wc -l || echo 0)
    echo "Store exists with ~$ENTRY_COUNT batch files (second run should be faster)"
else
    echo "Store does not exist (first run - will compute all features)"
fi
echo ""

# Change to common directory (parent of studio/experiments)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/../.."

# Build if needed
echo "Building experiments module..."
mvn install -pl studio/experiments -am -DskipTests -Dcheckstyle.skip=true -q || {
    echo "Build failed, trying without -q for details..."
    mvn install -pl studio/experiments -am -DskipTests -Dcheckstyle.skip=true
    exit 1
}

# Run the test
echo ""
echo "Running ONNX prototype discovery..."
echo "Start time: $(date)"
echo ""

mvn test -pl studio/experiments \
    -Dtest=OnnxPrototypeDiscoveryTest \
    -Dar.test.samples="$SAMPLES_DIR" \
    -Dar.test.store="$STORE_DIR" \
    -Dar.test.models="$MODELS_DIR" \
    -Dar.test.maxPrototypes="$MAX_PROTOTYPES" \
    -DfailIfNoTests=false \
    -Djacoco.skip=true

echo ""
echo "End time: $(date)"
echo ""
echo "To run again and verify persistence speedup:"
echo "  $0 $SAMPLES_DIR $STORE_DIR $MODELS_DIR"
