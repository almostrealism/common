#!/usr/bin/env bash
# ─── Build the documentation-QA prompt for the FlowTree agent ─────────
#
# Reads the documentation-QA prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Usage:
#   build-doc-qa-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the QA work
#   BASE_BRANCH     - base branch (master)
#   REVIEW_SINCE    - git --since expression bounding the review window
#                     (e.g. "1 week ago")
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

for var in BRANCH BASE_BRANCH REVIEW_SINCE; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/doc-qa.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# Substitute environment variables in the template.
sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    -e "s|\${REVIEW_SINCE}|${REVIEW_SINCE}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"
