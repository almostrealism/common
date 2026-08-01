#!/usr/bin/env bash
# PostToolUse — Write/Edit: scan newly written Java files for Producer pattern violations.
# Fires after every Write or Edit, checks computation-layer Java files only.
# Outputs warnings to stdout (visible in transcript) but does not block (exit 0).
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

# Only Java source files (not test files)
if [[ "$FILE_PATH" != *.java ]]; then
    exit 0
fi
if [[ "$FILE_PATH" == *Test.java || "$FILE_PATH" == *Tests.java ]]; then
    exit 0
fi

# Only paths where producer pattern is required
RELEVANT=false
for path in "engine/ml/src/main" "engine/audio/src/main" "studio/" "domain/graph/src/main" "compute/algebra/src/main" "compute/geometry/src/main"; do
    if [[ "$FILE_PATH" == *"$path"* ]]; then
        RELEVANT=true
        break
    fi
done

[[ "$RELEVANT" == "true" ]] || exit 0
[[ -f "$FILE_PATH" ]] || exit 0

VIOLATIONS=()

# .evaluate() in computation code
if grep -q '\.evaluate(' "$FILE_PATH" 2>/dev/null; then
    COUNT=$(grep -c '\.evaluate(' "$FILE_PATH" || true)
    LINES=$(grep -n '\.evaluate(' "$FILE_PATH" | head -5 | sed 's/^/      /')
    VIOLATIONS+=(".evaluate() in computation code (${COUNT} occurrence(s))\n${LINES}\n      Allowed only in: test methods, main(), pipeline step boundaries.")
fi

# .toDouble() in computation code
if grep -q '\.toDouble(' "$FILE_PATH" 2>/dev/null; then
    COUNT=$(grep -c '\.toDouble(' "$FILE_PATH" || true)
    LINES=$(grep -n '\.toDouble(' "$FILE_PATH" | head -5 | sed 's/^/      /')
    VIOLATIONS+=(".toDouble() in computation code (${COUNT} occurrence(s))\n${LINES}\n      Replace with CollectionProducer operations.")
fi

# .toFloat() in computation code
if grep -q '\.toFloat(' "$FILE_PATH" 2>/dev/null; then
    COUNT=$(grep -c '\.toFloat(' "$FILE_PATH" || true)
    VIOLATIONS+=(".toFloat() in computation code (${COUNT} occurrence(s)) — use Producer operations instead.")
fi

# for-loop + setMem combination (likely element-wise Java math)
if grep -qE 'for\s*\(' "$FILE_PATH" 2>/dev/null && grep -q '\.setMem(' "$FILE_PATH" 2>/dev/null; then
    LOOP_LINES=$(grep -n '\.setMem(' "$FILE_PATH" | head -3 | sed 's/^/      /')
    VIOLATIONS+=("for-loop + setMem() detected — check for element-wise Java math (not allowed)\n${LOOP_LINES}")
fi

# System.out / System.err
if grep -qE 'System\.(out|err)\.' "$FILE_PATH" 2>/dev/null; then
    COUNT=$(grep -cE 'System\.(out|err)\.' "$FILE_PATH")
    VIOLATIONS+=("System.out/err in source (${COUNT} occurrence(s)) — use ConsoleFeatures / Console.log() instead.")
fi

# new Model(...) multiple times (more than once = separate models where ONE is required)
MODEL_COUNT=$(grep -c 'new Model(' "$FILE_PATH" 2>/dev/null || true)
MODEL_COUNT=${MODEL_COUNT:-0}
if [[ "$MODEL_COUNT" -gt 1 ]]; then
    VIOLATIONS+=("new Model() called ${MODEL_COUNT} times — computation must use a SINGLE CompiledModel. See GRUDecoder pattern.")
fi

if [[ "${#VIOLATIONS[@]}" -gt 0 ]]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    printf "║  PRODUCER PATTERN VIOLATIONS: %-35s║\n" "$(basename "$FILE_PATH")"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    for v in "${VIOLATIONS[@]}"; do
        echo ""
        printf "  ✗ "
        echo -e "$v"
    done
    echo ""
    echo "  Fix: Replace Java-side math with CollectionProducer compositions."
    echo "  Reference: THE FUNDAMENTAL RULE section in CLAUDE.md."
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
fi

exit 0
