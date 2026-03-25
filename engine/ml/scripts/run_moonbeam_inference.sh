#!/bin/bash
#
# Moonbeam MIDI Inference Runner
#
# Builds the project (if needed) and runs MIDI generation using the
# Moonbeam 309M model. Weights must be prepared first with:
#   ./engine/ml/scripts/prep_moonbeam_weights.sh
#
# Usage:
#   ./engine/ml/scripts/run_moonbeam_inference.sh [options]
#
# Options:
#   --weights <dir>    Protobuf weights directory (default: /workspace/project/moonbeam-weights-protobuf)
#   --output <file>    Output MIDI file (default: ./moonbeam_output.mid)
#   --tokens <n>       Max tokens to generate (default: 50)
#   --input <file>     Optional MIDI prompt file for conditional generation
#   --temp <value>     Sampling temperature (default: 0.8, 0=greedy)
#   --top-p <value>    Nucleus sampling threshold (default: 0.95)
#   --seed <value>     Random seed for reproducibility
#   --config <name>    Model config: 'default' or '309M' (default: 309M)
#   --skip-build       Skip Maven build (use if you know code hasn't changed)
#   --memory <scale>   AR_HARDWARE_MEMORY_SCALE (default: 6 = ~16GB)
#
# Examples:
#   # Unconditional generation (50 notes, greedy)
#   ./engine/ml/scripts/run_moonbeam_inference.sh --tokens 50 --temp 0
#
#   # Creative generation with sampling
#   ./engine/ml/scripts/run_moonbeam_inference.sh --tokens 100 --temp 0.8 --seed 42
#
#   # Continue from a MIDI prompt
#   ./engine/ml/scripts/run_moonbeam_inference.sh --input prompt.mid --tokens 50
#

set -euo pipefail

# Defaults
WEIGHTS_DIR="/workspace/project/moonbeam-weights-protobuf"
OUTPUT_FILE="./moonbeam_output.mid"
MAX_TOKENS=50
TEMPERATURE=0.8
TOP_P=0.95
SEED=""
INPUT_FILE=""
CONFIG="309M"
SKIP_BUILD=false
MEMORY_SCALE=6

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --weights) WEIGHTS_DIR="$2"; shift 2 ;;
        --output) OUTPUT_FILE="$2"; shift 2 ;;
        --tokens) MAX_TOKENS="$2"; shift 2 ;;
        --input) INPUT_FILE="$2"; shift 2 ;;
        --temp) TEMPERATURE="$2"; shift 2 ;;
        --top-p) TOP_P="$2"; shift 2 ;;
        --seed) SEED="$2"; shift 2 ;;
        --config) CONFIG="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --memory) MEMORY_SCALE="$2"; shift 2 ;;
        -h|--help)
            head -38 "$0" | tail -36
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

echo "================================================="
echo "  Moonbeam MIDI Inference"
echo "================================================="
echo ""

# ---- Validate weights ----

if [[ ! -d "$WEIGHTS_DIR" ]]; then
    echo "ERROR: Weights directory not found: $WEIGHTS_DIR"
    echo ""
    echo "Run weight preparation first:"
    echo "  ./engine/ml/scripts/prep_moonbeam_weights.sh"
    exit 1
fi

WEIGHT_COUNT=$(ls "$WEIGHTS_DIR" 2>/dev/null | wc -l)
if [[ "$WEIGHT_COUNT" -lt 3 ]]; then
    echo "ERROR: Weights directory looks incomplete ($WEIGHT_COUNT files): $WEIGHTS_DIR"
    echo "Re-run: ./scripts/prep_moonbeam_weights.sh"
    exit 1
fi

echo "Weights: $WEIGHTS_DIR ($WEIGHT_COUNT files)"

# ---- Build if needed ----

ML_CLASSES="$PROJECT_DIR/engine/ml/target/classes/org/almostrealism/ml/midi/MoonbeamMidi.class"
ML_TEST_CLASSES="$PROJECT_DIR/engine/ml/target/test-classes/org/almostrealism/ml/midi/test/MoonbeamInferenceRunner.class"

if [[ "$SKIP_BUILD" == false ]]; then
    if [[ ! -f "$ML_CLASSES" ]] || [[ ! -f "$ML_TEST_CLASSES" ]]; then
        echo ""
        echo "Building project (first run may take a few minutes)..."
        cd "$PROJECT_DIR"
        mvn install -DskipTests -q 2>&1 | tail -5
        mvn test-compile -pl engine/ml -q 2>&1 | tail -5
        echo "Build complete."
    else
        # Check if source files are newer than compiled classes
        NEWEST_SRC=$(find "$PROJECT_DIR/engine/ml/src" -name "*.java" -newer "$ML_CLASSES" 2>/dev/null | head -1)
        if [[ -n "$NEWEST_SRC" ]]; then
            echo ""
            echo "Source changes detected, rebuilding..."
            cd "$PROJECT_DIR"
            mvn install -DskipTests -q 2>&1 | tail -5
            mvn test-compile -pl engine/ml -q 2>&1 | tail -5
            echo "Build complete."
        else
            echo "Build: up to date (use --skip-build to skip this check)"
        fi
    fi
fi

# ---- Resolve classpath ----

CLASSPATH_CACHE="$PROJECT_DIR/engine/ml/target/.moonbeam-classpath"

if [[ -f "$CLASSPATH_CACHE" ]] && [[ "$CLASSPATH_CACHE" -nt "$PROJECT_DIR/engine/ml/pom.xml" ]]; then
    DEP_CLASSPATH=$(cat "$CLASSPATH_CACHE")
else
    echo "Resolving classpath (cached for subsequent runs)..."
    cd "$PROJECT_DIR"
    DEP_CLASSPATH=$(mvn -pl engine/ml dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)
    echo "$DEP_CLASSPATH" > "$CLASSPATH_CACHE"
fi

CLASSPATH="$PROJECT_DIR/engine/ml/target/test-classes:$PROJECT_DIR/engine/ml/target/classes:$DEP_CLASSPATH"

# ---- Build java arguments ----

JAVA_ARGS=(
    "$WEIGHTS_DIR"
    "$OUTPUT_FILE"
    "$MAX_TOKENS"
    "--config" "$CONFIG"
    "--temp" "$TEMPERATURE"
    "--top-p" "$TOP_P"
)

if [[ -n "$INPUT_FILE" ]]; then
    JAVA_ARGS+=("--input" "$INPUT_FILE")
fi

if [[ -n "$SEED" ]]; then
    JAVA_ARGS+=("--seed" "$SEED")
fi

# ---- Run inference ----

echo ""
echo "Config:      $CONFIG"
echo "Max tokens:  $MAX_TOKENS"
echo "Temperature: $TEMPERATURE"
echo "Top-p:       $TOP_P"
echo "Output:      $OUTPUT_FILE"
echo "Memory:      scale=$MEMORY_SCALE"
if [[ -n "$INPUT_FILE" ]]; then
    echo "Prompt:      $INPUT_FILE"
fi
echo ""
echo "Press Ctrl+C to stop at any time."
echo "-------------------------------------------------"
echo ""

export AR_HARDWARE_MEMORY_SCALE="$MEMORY_SCALE"

java -Xmx8g \
    -cp "$CLASSPATH" \
    org.almostrealism.ml.midi.test.MoonbeamInferenceRunner \
    "${JAVA_ARGS[@]}"

echo ""
echo "Output MIDI: $OUTPUT_FILE"
