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
# eligible test job or one job whose log shows an infrastructure failure (see
# below), and the run has had fewer than MAX_ATTEMPTS total attempts, this
# re-runs the failed jobs (GitHub re-runs the failed jobs and their dependents,
# producing a new attempt) and reports retried=true. Only a failure that
# survives MAX_ATTEMPTS attempts — or a failure with neither a flaky test job
# nor an infrastructure failure in it — reports retried=false, letting the
# caller proceed to submit whatever request the `auto-resolve` job staged.
#
# A retry is spent only on a failure that might not happen again. The
# `auto-resolve` job classifies a test run's failures on every attempt, and when
# one is a regression confirmed on two or more backends it stages its request on
# that attempt instead of waiting for the last one. A run whose current attempt
# staged an `auto-resolve-request` artifact is therefore not retried
# (retried=false), so the caller submits it now: retrying cannot change a
# failure the code makes on two accelerators, and each retry of a test run is
# hours of runner time.
#
# Retries are for the slow path only. The early remediation jobs in "Build and
# Test" submit from inside the pipeline, without waiting for this script:
# `auto-resolve-python` when python-tests or python-evals fails, and
# `auto-review` (build failure, code policy, quality gates, docs-only review,
# or the general review) on attempt 1 only. `auto-resolve` stages its request
# only on attempt MAX_ATTEMPTS or later, so the only thing a retry can lead to
# is that slow-path request. Retrying therefore requires that the pipeline got
# as far as the long-running test jobs (a failed flaky-eligible job, below) or
# that a job failed on infrastructure rather than on the commit, and is refused
# outright when python-tests or python-evals failed: that run's
# remediation has already been submitted by auto-resolve-python, and
# auto-resolve is skipped for it, so another attempt would only spend test
# capacity.
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
# Any other failed job — a `*-check` validator, `build`, `checkstyle`,
# `analysis` (and the "Test Checks" gate it then fails) — is
# deterministic unless its log shows an infrastructure failure: a dependency or
# plugin that could not be downloaded, a dropped or timed-out connection, a
# server error from a package or artifact host, a runner that shut down or lost
# contact, or a full disk. The signatures and the log fetch live in
# tools/ci/infra-failure.sh, shared with the pull-request auto-review path so
# the two classify a failure the same way. Such a failure says nothing about the
# commit, so it is retried like a flaky test, up to the same MAX_ATTEMPTS. It
# never reaches an agent: auto-resolve stages nothing for a run whose test jobs
# did not fail, so a run that is still failing this way on its last attempt is
# left for a person. Without this, one failed download in test-timeout-check
# skips every test lane, and the run is neither retried nor remediated.
#
# A log that cannot be read is neither infrastructure nor deterministic but
# unknown, and must not silently be called deterministic — the fetch failing
# (most often because the per-job log is not archived the instant the run
# completes) is what left those pipelines stuck. The fetch therefore retries
# before giving up and reports a persistent failure as a ::warning:: rather than
# discarding it; and when the log of a failed `*-check` validator, the `build`
# job or the `analysis` job still cannot be read, the run is retried while
# attempts remain rather than classified deterministic. (`analysis` merges the
# test lanes' coverage after they passed; no agent can be handed its failure,
# because auto-resolve stages nothing for a run whose test jobs did not fail.) The retry is bounded by MAX_ATTEMPTS and the final
# attempt proceeds exactly as a deterministic failure would, so nothing is
# masked: a genuine deterministic failure is still reported and handed on, at
# most MAX_ATTEMPTS-1 re-runs later.
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
#   ATTEMPT_STARTED_AT - ISO-8601 start of the attempt that completed (the
#                   workflow_run event's run_started_at). An auto-resolve request
#                   created at or after it was staged by this attempt. Unset, the
#                   staged-request check is skipped and the run is retried as
#                   though nothing was staged.
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

# INFRA_SIGNATURES and the log-reading helpers (ci_infra_fetch_job_log,
# ci_infra_job_matches) are shared with the auto-review path so the two cannot
# drift; see tools/ci/infra-failure.sh.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/ci/infra-failure.sh
. "$SCRIPT_DIR/infra-failure.sh"

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

# A request this attempt staged means the failure is already classified as one a
# retry cannot clear; see the header. Artifacts persist across attempts, so only
# one created during this attempt counts. ISO-8601 UTC timestamps compare
# correctly as strings. A failed query finds nothing and the run is retried,
# which is the behaviour before classification.
if [ -n "${ATTEMPT_STARTED_AT:-}" ]; then
    STAGED_AT=$(gh api "/repos/$REPO/actions/runs/$RUN_ID/artifacts?name=auto-resolve-request" \
        --jq '.artifacts[] | select(.expired | not) | .created_at' 2>/dev/null || true)
    while IFS= read -r created; do
        [ -z "$created" ] && continue
        if [[ "$created" > "$ATTEMPT_STARTED_AT" || "$created" == "$ATTEMPT_STARTED_AT" ]]; then
            echo "::notice::Run $RUN_ID attempt $RUN_ATTEMPT staged an auto-resolve request at $created — a confirmed failure, so it is submitted instead of retried"
            emit false
            exit 0
        fi
    done <<< "$STAGED_AT"
fi

# Every job of the attempt that just completed, as "<conclusion>\t<name>\t<id>".
JOBS=$(gh api --paginate \
    "/repos/$REPO/actions/runs/$RUN_ID/attempts/$RUN_ATTEMPT/jobs" \
    --jq '.jobs[] | "\(.conclusion)\t\(.name)\t\(.id)"')

# A python-tests or python-evals failure is handled by auto-resolve-python:
# see the header.
for python_job in python-tests python-evals; do
    CONCLUSION=$(printf '%s\n' "$JOBS" | awk -F '\t' -v job="$python_job" '$2 == job { print $1 }')
    if [ "$CONCLUSION" = "failure" ] || [ "$CONCLUSION" = "timed_out" ]; then
        echo "::notice::${python_job} failed in run $RUN_ID attempt $RUN_ATTEMPT — auto-resolve-python already submitted a remediation, so the run is not retried"
        emit false
        exit 0
    fi
done

# Failed jobs for the attempt. Cancelled/skipped jobs are not "failures"
# here — only a job that ran and failed (or hit its timeout) counts.
FAILED_JOBS=$(printf '%s\n' "$JOBS" | awk -F '\t' '$1 == "failure" || $1 == "timed_out" { print $2 "\t" $3 }')

if [ -z "$FAILED_JOBS" ]; then
    echo "::notice::No failed jobs found for run $RUN_ID attempt $RUN_ATTEMPT — proceeding to auto-resolve"
    emit false
    exit 0
fi

# Is any failed job a flaky-eligible test-execution job, or did one fail on
# infrastructure? A deterministic failure anywhere does not stop the retry: the
# re-run repeats it, and the last attempt hands it on.
FLAKY_FOUND=false
INFRA_FOUND=false
while IFS=$'\t' read -r name id; do
    [ -z "$name" ] && continue
    case "$name" in
        # test, test (N), test-flowtree, test-media, test-mac, test-cl, …
        # (a *-check validator is named test-…-check, so it is matched first).
        *-check|*-check\ *) kind=check ;;
        test|test\ *|test-*) kind=test ;;
        *) kind=other ;;
    esac
    if [ "$kind" = "test" ]; then
        FLAKY_FOUND=true
        echo "::notice::Failed flaky-eligible test job: $name"
        continue
    fi

    # A non-test job is deterministic unless its log shows an infrastructure
    # failure. rc: 0 = infrastructure, 1 = readable and deterministic, 2 = the
    # log could not be read (ci_infra_job_matches already warned).
    rc=0
    ci_infra_job_matches "$id" || rc=$?
    case "$rc" in
        0)
            INFRA_FOUND=true
            echo "::notice::Failed job with an infrastructure failure in its log: $name"
            ;;
        2)
            # An unreadable log must not silently classify a validation or build
            # job as deterministic: that is exactly the case that left PR
            # pipelines stuck (one failed plugin download in test-timeout-check
            # skips every test lane, so the slow path never classifies the run).
            # Retry such a job while attempts remain — bounded by MAX_ATTEMPTS,
            # and the final attempt proceeds to auto-resolve exactly as a
            # deterministic failure would. `analysis` is included because its
            # failure reaches no agent either way. Any other job stays
            # deterministic.
            if [ "$kind" = "check" ] || [ "$name" = "build" ] || [ "$name" = "analysis" ]; then
                INFRA_FOUND=true
                echo "::warning::Could not read the log of failed ${kind} job '$name' — retrying while attempts remain rather than treating it as deterministic"
            else
                echo "::notice::Failed non-test job with an unreadable log (deterministic): $name"
            fi
            ;;
        *)
            if [ "$kind" = "check" ]; then
                echo "::notice::Failed validation job (deterministic): $name"
            else
                echo "::notice::Failed non-test job (deterministic): $name"
            fi
            ;;
    esac
done <<< "$FAILED_JOBS"

if [ "$FLAKY_FOUND" != "true" ] && [ "$INFRA_FOUND" != "true" ]; then
    echo "::notice::No flaky-eligible test job and no infrastructure failure in the failed set — deterministic failure, proceeding to auto-resolve"
    emit false
    exit 0
fi

echo "::notice::Re-running failed jobs for run $RUN_ID (attempt $RUN_ATTEMPT of $MAX_ATTEMPTS) — a flaky test or infrastructure failure may not survive a re-run"
gh api -X POST "/repos/$REPO/actions/runs/$RUN_ID/rerun-failed-jobs"
emit true
