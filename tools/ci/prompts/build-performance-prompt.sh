#!/usr/bin/env bash
# ─── Build the performance prompt for the FlowTree agent ──────────────
#
# Reads the performance prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Like the defect hunt there is no review window: the slow paths worth
# improving are rarely the ones touched last week. The template assumes
# it is running where Metal is available — the workflow routes the job to
# a macOS node for that reason — and tells the agent to verify that
# before trusting a measurement.
#
# Usage:
#   build-performance-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the performance work
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
TEMPLATE="${SCRIPT_DIR}/performance.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

echo "Performance prompt written to ${OUTPUT_FILE} ($(wc -l < "$OUTPUT_FILE") lines)"
