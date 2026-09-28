#!/usr/bin/env bash
# ─── Build the docs-review prompt for the FlowTree agent ────────────
#
# Reads a prompt template file and substitutes environment variables
# to produce a concrete prompt for the coding agent.
#
# auto-review sends this for a branch whose every change is under docs/,
# which is usually a plan awaiting approval. It reviews and improves the
# documents and never implements the plan; that is started by hand, with
# the Verify Completion workflow, once a person has approved it.
#
# Usage:
#   build-docs-review-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name under review
#   BASE_BRANCH     - base branch for comparison
#   COMMIT_SHA      - commit SHA under review
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
TEMPLATE="${SCRIPT_DIR}/docs-review.txt"

# shellcheck source=prompt-render.sh
source "${SCRIPT_DIR}/prompt-render.sh"

# Expand the template's @include lines and substitute its placeholders.
render_prompt "$TEMPLATE" BRANCH BASE_BRANCH COMMIT_SHA > "$OUTPUT_FILE"
