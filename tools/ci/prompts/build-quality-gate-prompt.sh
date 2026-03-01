#!/usr/bin/env bash
# ─── Build the quality-gate auto-resolve prompt for the FlowTree agent ──
#
# Reads a quality gate failure list and assembles a natural-language
# prompt for the coding agent. This is used as a fallback when no test
# failures exist but quality gates have failed.
#
# Usage:
#   build-quality-gate-prompt.sh <failures-file> <output-file>
#
# Required environment variables:
#   FAILURE_COUNT   - number of quality gate failures
#   BRANCH          - branch name where failures occurred
#   COMMIT_SHA      - commit SHA where failures occurred
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

FAILURES_FILE="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$FAILURES_FILE" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <failures-file> <output-file>" >&2
    exit 1
fi

for var in FAILURE_COUNT BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

FAILURE_LIST=$(cat "$FAILURES_FILE")

cat > "$OUTPUT_FILE" <<EOF
All tests are passing on branch "${BRANCH}" (commit ${COMMIT_SHA}), but ${FAILURE_COUNT} CI quality gate(s) failed:

${FAILURE_LIST}

These are code quality and style issues, not functional test failures. Please investigate and fix them. Run the indicated commands locally to see the specific violations, then make the minimal changes needed to satisfy each quality gate.
EOF
