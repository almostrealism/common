#!/usr/bin/env bash
# ─── Build the coverage-qa prompt for the FlowTree agent ──────────────
#
# Reads the coverage-qa prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Usage:
#   build-coverage-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH             - branch name for the coverage work
#   BASE_BRANCH        - base branch (master)
#   TARGET             - the selected Java package or Python directory
#   TARGET_LANGUAGE    - "java" or "python"
#   CURRENT_COVERAGE   - line coverage percentage before this round
#   COVERAGE_THRESHOLD - line coverage percentage this round targets
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

OUTPUT_FILE="${1:-}"

if [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <output-file>" >&2
    exit 1
fi

for var in BRANCH BASE_BRANCH TARGET TARGET_LANGUAGE CURRENT_COVERAGE COVERAGE_THRESHOLD; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/coverage.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    -e "s|\${TARGET}|${TARGET}|g" \
    -e "s|\${TARGET_LANGUAGE}|${TARGET_LANGUAGE}|g" \
    -e "s|\${CURRENT_COVERAGE}|${CURRENT_COVERAGE}|g" \
    -e "s|\${COVERAGE_THRESHOLD}|${COVERAGE_THRESHOLD}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

echo "Coverage prompt written to ${OUTPUT_FILE} ($(wc -l < "$OUTPUT_FILE") lines)"
