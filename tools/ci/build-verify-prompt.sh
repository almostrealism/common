#!/usr/bin/env bash
# ─── Build the verify-completion prompt for the FlowTree agent ────────
#
# Substitutes branch/commit metadata into the verify-completion prompt
# template and writes the final prompt.
#
# NOTE: The planning document content is NOT embedded in this prompt.
# The FlowTree controller's InstructionPromptBuilder automatically
# injects the workstream's planningDocument path into the agent's
# system instructions, so the agent already knows where to find it.
#
# Usage:
#   build-verify-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name under verification
#   BASE_BRANCH     - base branch for comparison
#   COMMIT_SHA      - commit SHA under verification
#
# Arguments:
#   output-file     - where to write the final prompt
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

for var in BRANCH BASE_BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/prompts/verify-completion.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# Substitute scalar environment variables in the template.
sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    -e "s|\${COMMIT_SHA}|${COMMIT_SHA}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"
