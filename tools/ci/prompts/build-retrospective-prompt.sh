#!/usr/bin/env bash
# ─── Build the QA retrospective prompt for the FlowTree agent ─────────
#
# Reads the retrospective prompt template and substitutes environment
# variables to produce a concrete prompt for the coding agent.
#
# The retrospective is the one QA round that studies the other rounds. It
# surveys recent automated work in a single repository and proposes, with
# evidence, how the agent harness, the existing QA jobs, and the set of QA
# jobs itself should improve. It carries no pr-feedback.txt fragment: like
# every round dispatched from master it starts on a fresh branch with no
# pull request, so the review-feedback policy (which the auto-review and
# auto-resolve builders patch in) does not apply to it.
#
# Usage:
#   build-retrospective-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name for the retrospective work
#   BASE_BRANCH     - base branch (master)
#   REPO_URL        - the only repository the survey may cover
#   REVIEW_SINCE    - git --since expression bounding the survey window
#                     (e.g. "14 days ago")
#   QA_PREFIXES     - space-separated branch prefixes the survey covers
#                     (every QA job's BRANCH_PREFIX plus ci/qa-)
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

for var in BRANCH BASE_BRANCH REPO_URL REVIEW_SINCE QA_PREFIXES; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/retrospective.txt"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: Template not found at ${TEMPLATE}" >&2
    exit 1
fi

# Substitute environment variables in the template.
sed -e "s|\${BRANCH}|${BRANCH}|g" \
    -e "s|\${BASE_BRANCH}|${BASE_BRANCH}|g" \
    -e "s|\${REPO_URL}|${REPO_URL}|g" \
    -e "s|\${REVIEW_SINCE}|${REVIEW_SINCE}|g" \
    -e "s|\${QA_PREFIXES}|${QA_PREFIXES}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"
