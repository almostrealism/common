#!/usr/bin/env bash
# ─── Build the verify-completion prompt for the FlowTree agent ────────
#
# Reads the plan file from the repository, injects its contents into
# the verify-completion prompt template, and writes the final prompt.
#
# Usage:
#   build-verify-prompt.sh <plan-file-path> <output-file>
#
# Required environment variables:
#   BRANCH          - branch name under verification
#   BASE_BRANCH     - base branch for comparison
#   COMMIT_SHA      - commit SHA under verification
#
# Arguments:
#   plan-file-path  - path to the plan file (relative to repo root)
#   output-file     - where to write the final prompt
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments, missing env vars, or plan file not found

set -euo pipefail

PLAN_FILE="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$PLAN_FILE" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <plan-file-path> <output-file>" >&2
    exit 1
fi

for var in BRANCH BASE_BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

if [ ! -f "$PLAN_FILE" ]; then
    echo "ERROR: Plan file not found at ${PLAN_FILE}" >&2
    exit 1
fi

PLAN_CONTENT=$(cat "$PLAN_FILE")
export PLAN_FILE PLAN_CONTENT

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/prompts/verify-completion.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# Substitute environment variables in the template.
# Use awk to handle multi-line PLAN_CONTENT safely.
awk -v plan="$PLAN_CONTENT" \
    -v branch="$BRANCH" \
    -v base="$BASE_BRANCH" \
    -v sha="$COMMIT_SHA" \
    -v planfile="$PLAN_FILE" \
    '{
        gsub(/\$\{PLAN_CONTENT\}/, plan)
        gsub(/\$\{BRANCH\}/, branch)
        gsub(/\$\{BASE_BRANCH\}/, base)
        gsub(/\$\{COMMIT_SHA\}/, sha)
        gsub(/\$\{PLAN_FILE\}/, planfile)
        print
    }' "$TEMPLATE" > "$OUTPUT_FILE"
