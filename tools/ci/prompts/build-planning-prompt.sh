#!/usr/bin/env bash
# ─── Build the project-planning prompt for the FlowTree agent ─────────
#
# Reads the project-planning prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# Usage:
#   build-planning-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the planning work
#   BASE_BRANCH     - base branch (master)
#
# Optional environment variables:
#   PLAN_DOCUMENT   - path to a plan document already committed on the branch.
#                     When set, the caller has stated the task rather than
#                     asking for one to be found, and the prompt is extended to
#                     say so. Left unset, the prompt is unchanged.
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
TEMPLATE="${SCRIPT_DIR}/project-planning.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# Substitute environment variables in the template.
sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

# A seeded plan inverts the job: the task is already stated, so the agent
# refines it rather than surveying the project for something to do. This is
# appended rather than held in the template because it applies to a minority of
# runs, and a template placeholder that is usually empty reads as an oversight.
if [ -n "${PLAN_DOCUMENT:-}" ]; then
    cat >> "$OUTPUT_FILE" <<EOF

## A Plan Document Already Exists

\`${PLAN_DOCUMENT}\` was committed to this branch before you started. It states
the task you are being asked to plan; it was written by the person who
requested this run.

Work from it instead of surveying the project for a new task, and do NOT create
a second plan document. Refine that file in place until it satisfies the
Deliverables section above, then update \`docs/plans/MANAGER_LOG.md\` as
described there. Exactly one new plan document must exist on this branch when
you are done.
EOF
fi
