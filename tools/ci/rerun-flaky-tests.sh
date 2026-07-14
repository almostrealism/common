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
#
# Optional environment:
#   MAX_ATTEMPTS  - retry only while RUN_ATTEMPT < MAX_ATTEMPTS (default 5)
#
# Output (appended to $GITHUB_OUTPUT, or stdout when unset):
#   retried=true   - a re-run was requested; do NOT submit an agent job
#   retried=false  - no re-run; the caller should proceed to submit

set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${REPO:?REPO is required}"
: "${RUN_ID:?RUN_ID is required}"
: "${RUN_ATTEMPT:?RUN_ATTEMPT is required}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"

emit() {
    echo "retried=$1" >> "${GITHUB_OUTPUT:-/dev/stdout}"
}

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
