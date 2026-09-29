#!/usr/bin/env bash
# ─── Decide whether the open-PR backlog leaves room for a new plan ────
#
# Shared by the planning jobs in master-agent-dispatch.yaml. Every plan
# they open is a pull request someone has to review, so no new planning
# round starts while more than MAX_OPEN_PRS pull requests of any kind are
# already open against master.
#
# Usage:
#   open-pr-backlog.sh
#
# Required environment variables:
#   MAX_OPEN_PRS   - the most open pull requests at which a round may start
#   GITHUB_TOKEN   - token for gh
#
# Optional environment variables:
#   FORCE          - "true" starts a round whatever the backlog
#   BASE_BRANCH    - branch the counted pull requests target (default: master)
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   open_prs=<count>
#   max_open_prs=<limit>
#   needs_new_branch=true|false
#
# Exit codes:
#   0 - decision made (check needs_new_branch=)
#   1 - invalid arguments, or the pull requests could not be counted

set -euo pipefail

if [ -z "${MAX_OPEN_PRS:-}" ]; then
    echo "ERROR: MAX_OPEN_PRS is not set." >&2
    exit 1
fi

emit() {
    echo "$1"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1" >> "$GITHUB_OUTPUT"
    fi
}

OPEN_PR_COUNT=$(gh pr list --base "${BASE_BRANCH:-master}" --state open \
    --limit 1000 --json number --jq 'length')
emit "open_prs=$OPEN_PR_COUNT"
emit "max_open_prs=$MAX_OPEN_PRS"

if [ "${FORCE:-false}" = "true" ]; then
    emit "needs_new_branch=true"
    echo "::notice::Force mode — starting a planning round regardless of the backlog"
elif [ "$OPEN_PR_COUNT" -le "$MAX_OPEN_PRS" ]; then
    emit "needs_new_branch=true"
    echo "::notice::$OPEN_PR_COUNT open PRs (limit $MAX_OPEN_PRS) — room for a planning round"
else
    emit "needs_new_branch=false"
    echo "::notice::$OPEN_PR_COUNT open PRs exceeds the limit of $MAX_OPEN_PRS — not starting a planning round"
fi
