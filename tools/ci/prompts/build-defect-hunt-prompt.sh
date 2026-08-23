#!/usr/bin/env bash
# ─── Build the defect-hunt prompt for the FlowTree agent ──────────────
#
# Reads the defect-hunt prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Unlike the documentation-QA prompt there is no review window: the
# defect hunt is deliberately unscoped in time, because the defects that
# survive longest are in the code nobody has looked at recently.
#
# Usage:
#   build-defect-hunt-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the defect-hunt work
#   BASE_BRANCH     - base branch (master)
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

for var in BRANCH BASE_BRANCH; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/defect-hunt.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

echo "Defect-hunt prompt written to ${OUTPUT_FILE} ($(wc -l < "$OUTPUT_FILE") lines)"
