#!/usr/bin/env bash
# ─── Build the PDSL migration prompt for the FlowTree agent ───────────
#
# Reads the PDSL migration prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Like the defect hunt and the consolidation round there is no review
# window: the structure worth moving out of Java is the structure that
# was written before the language existed, not what changed last week.
#
# Usage:
#   build-pdsl-migration-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the migration work
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
TEMPLATE="${SCRIPT_DIR}/pdsl-migration.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

echo "PDSL migration prompt written to ${OUTPUT_FILE} ($(wc -l < "$OUTPUT_FILE") lines)"
