#!/usr/bin/env bash
# ─── Cancel a merged pull request's still-running pipelines ──────────────
#
# Merging a pull request is a decision that its checks are good enough. The
# pipeline still running against the pre-merge head is answering a question
# that has already been settled, and the merge commit's own pipeline on the
# base branch re-answers it against the code that was actually merged. On
# constrained CI capacity that dangling pipeline is pure cost: it occupies
# runners that a branch someone is waiting on could be using.
#
# This cancels the queued and in-progress runs of the main pipeline for the
# merged pull request's head branch.
#
# Scope: only runs of WORKFLOW, only those triggered by the `pull_request`
# event, only those whose head branch and head repository match the merged
# pull request. In this repository the main pipeline's `push` trigger is
# restricted to the base branch, so a feature branch's only runs are the ones
# the pull request itself launched — the branch filter is therefore already
# limited to pipelines that exist because of this pull request. Matching the
# head repository as well keeps a fork's branch from matching a same-named
# branch in the base repository.
#
# The merge commit's pipeline is never affected: it is a `push` event on the
# base branch, which the event filter excludes even when a fast-forward or
# rebase merge leaves it pointing at the same commit.
#
# Cancelling does not dispatch an agent. "Auto-Resolve Submit" runs on the
# pipeline's completion, but its retry gate requires conclusion == 'failure'
# and its submit job excludes conclusion == 'cancelled'.
#
# Usage: cancel-merged-pr-runs.sh
#
# Required environment:
#   GH_TOKEN     - token with actions:write on the repository
#   REPO         - owner/name of the repository the runs belong to
#   PR_NUMBER    - the merged pull request's number (for logging)
#   HEAD_BRANCH  - the merged pull request's head branch
#   HEAD_REPO    - owner/name of the repository holding that branch, which
#                  differs from REPO for a pull request opened from a fork
#
# Optional environment:
#   WORKFLOW     - workflow file to cancel runs of (default analysis.yaml)

set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${REPO:?REPO is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${HEAD_BRANCH:?HEAD_BRANCH is required}"
: "${HEAD_REPO:?HEAD_REPO is required}"
WORKFLOW="${WORKFLOW:-analysis.yaml}"

CANCELLED=0
FAILED_QUERIES=0

# A run that is queued has not yet taken a runner but would; a run that is
# waiting is held at an environment approval gate. Both are cancelled along
# with the ones already executing.
#
# A listing that fails (auth, rate limit, API outage) is reported rather than
# read as an empty result: "no runs to cancel" and "could not find out" lead to
# the same inaction but want different responses from whoever reads the log.
for status in in_progress queued waiting; do
    if ! RUN_IDS=$(gh api --paginate \
        "/repos/$REPO/actions/workflows/$WORKFLOW/runs?event=pull_request&branch=$HEAD_BRANCH&status=$status" \
        --jq ".workflow_runs[] | select(.head_repository.full_name == \"$HEAD_REPO\") | .id" 2>/dev/null); then
        echo "::warning::Could not list $status runs of $WORKFLOW for $HEAD_REPO@$HEAD_BRANCH; any are left running"
        FAILED_QUERIES=$((FAILED_QUERIES + 1))
        continue
    fi

    for id in $RUN_IDS; do
        # A run that reached a terminal state between the query and here
        # returns 409; that is the outcome this wanted, not a failure.
        if gh api -X POST "/repos/$REPO/actions/runs/$id/cancel" >/dev/null 2>&1; then
            echo "::notice::Cancelled $status run $id for merged PR #$PR_NUMBER ($HEAD_REPO@$HEAD_BRANCH)"
            CANCELLED=$((CANCELLED + 1))
        else
            echo "::notice::Run $id could not be cancelled — it has most likely already finished"
        fi
    done
done

if [ "$CANCELLED" -eq 0 ] && [ "$FAILED_QUERIES" -gt 0 ]; then
    echo "::warning::Cancelled nothing for merged PR #$PR_NUMBER: $FAILED_QUERIES of 3 run listings failed"
elif [ "$CANCELLED" -eq 0 ]; then
    echo "::notice::No active $WORKFLOW runs for merged PR #$PR_NUMBER ($HEAD_REPO@$HEAD_BRANCH)"
else
    echo "::notice::Cancelled $CANCELLED run(s) for merged PR #$PR_NUMBER"
    if [ "$FAILED_QUERIES" -gt 0 ]; then
        echo "::warning::$FAILED_QUERIES of 3 run listings failed; some runs may still be active"
    fi
fi
