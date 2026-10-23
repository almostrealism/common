#!/usr/bin/env bash
# ─── Build the flaky-test-hunt prompt for the FlowTree agent ──────────
#
# Reads the flaky-hunt prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# This is the pilot nondeterministic-test hunt (RETRO-20261022 F6): it
# repeatedly runs a bounded, named slice of one module's tests on a single
# fixed commit, looking for a test whose pass/fail outcome varies, and either
# fixes the source of the nondeterminism in production or test-helper code or
# files a follow-up task. Like the defect hunt it carries no review window:
# the slice to examine is chosen by the agent, not scoped to recent changes.
#
# Usage:
#   build-flaky-hunt-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the flaky-hunt work
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
TEMPLATE="${SCRIPT_DIR}/flaky-hunt.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

echo "Flaky-hunt prompt written to ${OUTPUT_FILE} ($(wc -l < "$OUTPUT_FILE") lines)"
