#!/usr/bin/env bash
# ─── Re-run flaky main-pipeline test jobs before auto-resolving ──────────
#
# The "Build and Test" pipeline's test-execution jobs (the matrix `test`
# job and the `test-*` jobs) are occasionally flaky: they fail spuriously
# and pass on re-run. Dispatching a FlowTree coding agent on the first red
# pipeline therefore wastes agent capacity chasing failures that are not
# real. This script gives a red pipeline additional attempts before it is
# handed to auto-resolve.
#
# When the completed run failed, its failed set contains at least one flaky-
# eligible test job, and the run has had fewer than MAX_ATTEMPTS total
# attempts, this re-runs the failed jobs (GitHub re-runs the failed jobs and
# their dependents, producing a new attempt) and reports retried=true. Only a
# failure that survives MAX_ATTEMPTS attempts — or a failure with no flaky
# test job in it (a deterministic failure such as build/checkstyle/policy) —
# reports retried=false, letting the caller proceed to submit an agent job.
#
# A run whose head commit is no longer the tip of its branch is superseded: a
# newer commit has been pushed and its own pipeline is authoritative. Retrying
# such a run spends test capacity on a commit that will not be merged, and
# handing it to an agent produces a fix against stale code, so a superseded run
# is neither retried nor submitted (superseded=true).
#
# Because a re-run is a new attempt on the SAME run, it re-enters the workflow's
# concurrency group. The superseded guard is what keeps that safe: without it, a
# retry of an older commit could start after — and therefore cancel — the
# in-progress pipeline for the current head commit.
#
# A flaky-eligible job is a test-execution job: name `test`, a matrix entry
# `test (N)`, or a `test-*` job (test-flowtree, test-media, test-mac, test-cl,
# test-media-mac, test-media-cl, …). The deterministic `*-check` validators
# (test-timeout-check, test-integrity-check) are explicitly NOT flaky-eligible.
#
# This must run from a workflow_run workflow (after "Build and Test"
# completes): rerun-failed-jobs can only act on a completed run.
#
# Usage: rerun-flaky-tests.sh
#
# Required environment:
#   GH_TOKEN      - token with actions:write on the repository
#   REPO          - owner/name
#   RUN_ID        - the completed "Build and Test" run id
#   RUN_ATTEMPT   - that run's attempt number (1-based)
#   HEAD_SHA      - the commit the completed run was for
#   HEAD_BRANCH   - the branch that commit was pushed to
#   HEAD_REPO     - owner/name of the repository holding that branch (differs
#                   from REPO for a pull request opened from a fork)
#
# Optional environment:
#   MAX_ATTEMPTS  - retry only while RUN_ATTEMPT < MAX_ATTEMPTS (default 3)
#
# Output (appended to $GITHUB_OUTPUT, or stdout when unset):
#   retried=true      - a re-run was requested; do NOT submit an agent job
#   retried=false     - no re-run
#   superseded=true   - the run's commit is no longer the branch tip; the
#                       caller should neither retry nor submit
#   superseded=false  - the run's commit is still current (or could not be
#                       resolved, in which case the guard is skipped)

set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${REPO:?REPO is required}"
: "${RUN_ID:?RUN_ID is required}"
: "${RUN_ATTEMPT:?RUN_ATTEMPT is required}"
: "${HEAD_SHA:?HEAD_SHA is required}"
: "${HEAD_BRANCH:?HEAD_BRANCH is required}"
: "${HEAD_REPO:?HEAD_REPO is required}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-3}"

# emit <retried> [superseded]
emit() {
    {
        echo "retried=$1"
        echo "superseded=${2:-false}"
    } >> "${GITHUB_OUTPUT:-/dev/stdout}"
}

# The tip of the branch the run was for. An empty result (deleted branch,
# transient API failure) skips the guard rather than suppressing a legitimate
# retry: the guard exists to avoid wasted work, not to gate correctness.
CURRENT_SHA=$(gh api "/repos/$HEAD_REPO/commits/$HEAD_BRANCH" --jq '.sha' 2>/dev/null || true)

if [ -z "$CURRENT_SHA" ]; then
    echo "::notice::Could not resolve the tip of $HEAD_REPO@$HEAD_BRANCH — skipping the superseded check"
elif [ "$CURRENT_SHA" != "$HEAD_SHA" ]; then
    echo "::notice::Run $RUN_ID is for $HEAD_SHA but $HEAD_REPO@$HEAD_BRANCH is now at $CURRENT_SHA — superseded, so it is neither retried nor submitted"
    emit false true
    exit 0
fi

if [ "$RUN_ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "::notice::Run $RUN_ID has reached attempt $RUN_ATTEMPT (max $MAX_ATTEMPTS) — treating the failure as genuine and proceeding to auto-resolve"
    emit false
    exit 0
fi

# Failed jobs for the attempt that just completed. Cancelled/skipped jobs are
# not "failures" here — only a job that ran and failed counts.
FAILED_JOBS=$(gh api --paginate \
    "/repos/$REPO/actions/runs/$RUN_ID/attempts/$RUN_ATTEMPT/jobs" \
    --jq '.jobs[] | select(.conclusion == "failure") | .name')

if [ -z "$FAILED_JOBS" ]; then
    echo "::notice::No failed jobs found for run $RUN_ID attempt $RUN_ATTEMPT — proceeding to auto-resolve"
    emit false
    exit 0
fi

# Is any failed job a flaky-eligible test-execution job?
FLAKY_FOUND=false
while IFS= read -r name; do
    [ -z "$name" ] && continue
    case "$name" in
        # Deterministic validators — never flaky, never retried.
        *-check|*-check\ *)
            echo "::notice::Failed validation job (deterministic): $name"
            ;;
        # test, test (N), test-flowtree, test-media, test-mac, test-cl, …
        test|test\ *|test-*)
            FLAKY_FOUND=true
            echo "::notice::Failed flaky-eligible test job: $name"
            ;;
        *)
            echo "::notice::Failed non-test job (deterministic): $name"
            ;;
    esac
done <<< "$FAILED_JOBS"

if [ "$FLAKY_FOUND" != "true" ]; then
    echo "::notice::No flaky-eligible test job in the failed set — deterministic failure, proceeding to auto-resolve"
    emit false
    exit 0
fi

echo "::notice::Re-running failed jobs for run $RUN_ID (attempt $RUN_ATTEMPT of $MAX_ATTEMPTS) — a flaky test failure may not survive a re-run"
gh api -X POST "/repos/$REPO/actions/runs/$RUN_ID/rerun-failed-jobs"
emit true
