#!/usr/bin/env bash
# ─── Build the task-planning prompt for the FlowTree agent ────────────
#
# Reads the task-planning template and substitutes environment variables
# to produce a concrete prompt for the coding agent. CI has already claimed
# the task (tools/ci/tracker-claim.sh) and linked it to the round's
# workstream; the prompt names it, and the agent writes a plan for it. It
# does not implement the plan.
#
# Usage:
#   build-task-planning-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH           - branch name for the planning work
#   BASE_BRANCH      - base branch (master)
#   TRACKER_PROJECT  - tracker project whose release is planned (e.g. Common)
#   TRACKER_RELEASE  - full tracker release name, "<project> <version>"
#   TASK_ID          - id of the claimed tracker task
#   TASK_TITLE       - its title
#   TASK_DESCRIPTION - its description; may span several lines
#
# TASK_TITLE and TASK_DESCRIPTION are free text written by a person. They
# are substituted as they are, after every other value, so text in them
# that looks like a placeholder cannot pull another value into the prompt
# (only the title could, and only the description).
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

for var in BRANCH BASE_BRANCH TRACKER_PROJECT TRACKER_RELEASE TASK_ID TASK_TITLE TASK_DESCRIPTION; do
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
render_prompt "$TEMPLATE" BRANCH BASE_BRANCH TRACKER_PROJECT TRACKER_RELEASE TASK_ID \
    TASK_TITLE TASK_DESCRIPTION > "$OUTPUT_FILE"
