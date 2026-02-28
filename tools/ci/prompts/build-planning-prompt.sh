#!/usr/bin/env bash
# ─── Build the project-planning prompt for the FlowTree agent ─────────
#
# Reads the project-planning prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Usage:
#   build-planning-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the planning work
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
TEMPLATE="${SCRIPT_DIR}/project-planning.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# Substitute environment variables in the template.
sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"
