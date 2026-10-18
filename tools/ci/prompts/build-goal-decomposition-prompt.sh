#!/usr/bin/env bash
# ─── Build the goal-decomposition prompt for the FlowTree agent ───────
#
# Reads the goal-decomposition template and substitutes environment
# variables to produce a concrete prompt for the release steward. The agent
# reads a repository's goals and the plan for its next release, and creates
# or updates the tracker tasks that release needs; it changes no files.
#
# Usage:
#   build-goal-decomposition-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH               - the steward's branch, at the commit being reconciled
#   BASE_BRANCH          - the repository's default branch
#   REPOSITORY           - the repository being reconciled, "<owner>/<name>"
#   TRACKER_PROJECT      - tracker project of that repository
#   TRACKER_RELEASE      - its next release, "<project> <version>"
#   GOALS_DOCUMENT       - path of the long-term goals document
#   PLAN_DIRECTORY       - path of the release plan directory for the version
#   RELEASE_PLAN         - path of the release plan, <PLAN_DIRECTORY>/PLAN.md
#   FRAMEWORK_PROJECT    - tracker project of the framework
#   FRAMEWORK_RELEASE    - framework release the repository builds against
#   FRAMEWORK_REPOSITORY - the framework repository, "<owner>/<name>"
#   FRAMEWORK_BRANCH     - framework branch the agent reads
#   SOURCE_PREFIX        - source prefix of this steward's tasks,
#                          "goals:<owner>/<name>/"
#   MAX_NEW_TASKS        - most new tasks one run may create
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

for var in BRANCH BASE_BRANCH REPOSITORY TRACKER_PROJECT TRACKER_RELEASE GOALS_DOCUMENT PLAN_DIRECTORY RELEASE_PLAN FRAMEWORK_PROJECT FRAMEWORK_RELEASE FRAMEWORK_REPOSITORY FRAMEWORK_BRANCH SOURCE_PREFIX MAX_NEW_TASKS; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/goal-decomposition.txt"

# shellcheck source=prompt-render.sh
source "${SCRIPT_DIR}/prompt-render.sh"

# Substitute the template's placeholders.
render_prompt "$TEMPLATE" BRANCH BASE_BRANCH REPOSITORY TRACKER_PROJECT TRACKER_RELEASE \
    GOALS_DOCUMENT PLAN_DIRECTORY RELEASE_PLAN FRAMEWORK_PROJECT FRAMEWORK_RELEASE \
    FRAMEWORK_REPOSITORY FRAMEWORK_BRANCH SOURCE_PREFIX MAX_NEW_TASKS > "$OUTPUT_FILE"
