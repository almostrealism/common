#!/bin/bash
#
# Moonbeam Weight Preparation Script
#
# Downloads the Moonbeam 309M checkpoint from HuggingFace and converts
# it to the protobuf format used by the AR framework's StateDictionary.
#
# Run this ONCE before using run_moonbeam_inference.sh.
#
# What it does:
#   1. Downloads moonbeam_309M.pt (~619MB) from HuggingFace
#   2. Generates Python protobuf stubs from the repo's .proto files
#   3. Runs extract_moonbeam_weights.py to convert to protobuf
#
# Usage:
#   ./engine/ml/scripts/prep_moonbeam_weights.sh
#   ./engine/ml/scripts/prep_moonbeam_weights.sh --checkpoint /path/to/moonbeam_309M.pt  # skip download
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ML_SCRIPTS="$SCRIPT_DIR"

CHECKPOINT_URL="https://huggingface.co/guozixunnicolas/moonbeam-midi-foundation-model/resolve/main/moonbeam_309M.pt"
DOWNLOAD_DIR="/workspace/project/moonbeam-weights"
CHECKPOINT_PATH="$DOWNLOAD_DIR/moonbeam_309M.pt"
OUTPUT_DIR="/workspace/project/moonbeam-weights-protobuf"

# Parse arguments
SKIP_DOWNLOAD=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --checkpoint)
            CHECKPOINT_PATH="$2"
            SKIP_DOWNLOAD=true
            shift 2 ;;
        --output)
            OUTPUT_DIR="$2"
            shift 2 ;;
        -h|--help)
            head -16 "$0" | tail -14
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

echo "================================================="
echo "  Moonbeam Weight Preparation"
echo "================================================="
echo ""

# ---- Step 1: Download checkpoint ----

if [[ "$SKIP_DOWNLOAD" == true ]]; then
    echo "[1/3] Using provided checkpoint: $CHECKPOINT_PATH"
else
    echo "[1/3] Downloading Moonbeam 309M checkpoint (~619MB)..."

    # Check if we already have the real file (not an LFS pointer)
    if [[ -f "$CHECKPOINT_PATH" ]]; then
        FILE_SIZE=$(stat -c%s "$CHECKPOINT_PATH" 2>/dev/null || stat -f%z "$CHECKPOINT_PATH" 2>/dev/null || echo "0")
        if [[ "$FILE_SIZE" -gt 1000 ]]; then
            echo "  Checkpoint already exists ($FILE_SIZE bytes), skipping download."
        else
            echo "  Existing file is an LFS pointer ($FILE_SIZE bytes), downloading real weights..."
            rm -f "$CHECKPOINT_PATH"
        fi
    fi

    if [[ ! -f "$CHECKPOINT_PATH" ]] || [[ $(stat -c%s "$CHECKPOINT_PATH" 2>/dev/null || echo "0") -lt 1000 ]]; then
        mkdir -p "$DOWNLOAD_DIR"

        echo "  Downloading from HuggingFace..."
        echo "  URL: $CHECKPOINT_URL"
        echo ""

        curl -L --progress-bar \
            -o "$CHECKPOINT_PATH" \
            "$CHECKPOINT_URL"

        FILE_SIZE=$(stat -c%s "$CHECKPOINT_PATH" 2>/dev/null || stat -f%z "$CHECKPOINT_PATH" 2>/dev/null)
        echo ""
        echo "  Downloaded: $CHECKPOINT_PATH ($FILE_SIZE bytes)"
    fi
fi

# Validate checkpoint
if [[ ! -f "$CHECKPOINT_PATH" ]]; then
    echo "ERROR: Checkpoint file not found: $CHECKPOINT_PATH"
    exit 1
fi

FILE_SIZE=$(stat -c%s "$CHECKPOINT_PATH" 2>/dev/null || stat -f%z "$CHECKPOINT_PATH" 2>/dev/null || echo "0")
if [[ "$FILE_SIZE" -lt 1000 ]]; then
    echo "ERROR: Checkpoint file is too small ($FILE_SIZE bytes) -- likely an LFS pointer."
    echo "Delete it and re-run, or download manually."
    exit 1
fi

echo "[1/3] Checkpoint ready: $CHECKPOINT_PATH ($FILE_SIZE bytes)"
echo ""

# ---- Step 2: Generate protobuf Python stubs ----

echo "[2/3] Generating protobuf Python stubs..."

# Check if already generated
if [[ -f "$ML_SCRIPTS/collections_pb2.py" ]]; then
    echo "  collections_pb2.py already exists, skipping."
else
    "$ML_SCRIPTS/generate_protobuf_python.sh"
fi

echo "[2/3] Protobuf stubs ready."
echo ""

# ---- Step 3: Extract weights ----

if [[ -d "$OUTPUT_DIR" ]] && [[ $(ls "$OUTPUT_DIR" 2>/dev/null | wc -l) -gt 5 ]]; then
    echo "[3/3] Protobuf weights directory already exists with files: $OUTPUT_DIR"
    echo "  Delete it and re-run if you want to re-extract."
    echo ""
else
    echo "[3/3] Extracting weights to protobuf format..."
    echo "  Input:  $CHECKPOINT_PATH"
    echo "  Output: $OUTPUT_DIR"
    echo ""

    python3 "$ML_SCRIPTS/extract_moonbeam_weights.py" \
        "$CHECKPOINT_PATH" \
        "$OUTPUT_DIR"
fi

echo ""
echo "================================================="
echo "  Weight preparation complete!"
echo "  Weights directory: $OUTPUT_DIR"
echo ""
echo "  Next: run inference with:"
echo "    ./engine/ml/scripts/run_moonbeam_inference.sh --tokens 50"
echo "================================================="
