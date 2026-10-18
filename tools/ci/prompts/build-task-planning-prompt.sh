#!/usr/bin/env bash
# ─── Build the task-planning prompt for the FlowTree agent ────────────
#
# Reads the task-planning template and substitutes environment variables
# to produce a concrete prompt for the coding agent. The agent claims the
# next ready task of a tracker release and writes a plan for it; it does
# not implement the plan.
#
# Usage:
#   build-task-planning-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH           - branch name for the planning work
#   BASE_BRANCH      - base branch (master)
#   TRACKER_PROJECT  - tracker project whose release is planned (e.g. Common)
#   TRACKER_RELEASE  - full tracker release name, "<project> <version>"
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

for var in BRANCH BASE_BRANCH TRACKER_PROJECT TRACKER_RELEASE; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/task-planning.txt"

# shellcheck source=prompt-render.sh
source "${SCRIPT_DIR}/prompt-render.sh"

# Expand the template's @include lines and substitute its placeholders.
render_prompt "$TEMPLATE" BRANCH BASE_BRANCH TRACKER_PROJECT TRACKER_RELEASE > "$OUTPUT_FILE"
