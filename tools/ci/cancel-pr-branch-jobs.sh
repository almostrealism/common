#!/usr/bin/env bash
# ─── Cancel a closed pull request's in-flight FlowTree jobs ──────────────
#
# A pull request merging or closing settles the work on its head branch. A
# FlowTree agent job still running or waiting on that branch is now working a
# question that has already been answered: a primary "implement the plan" job
# has held a scarce worker for hours after its own PR merged, reclaimed only
# once the stall watchdog fired.
#
# This tells the controller the pull request reached a terminal state so it can
# cancel the jobs still in flight on the branch. The controller cancels only
# jobs it accepted before the event — a follow-up the owner starts on the same
# branch afterwards is left alone — and names the pull request event as the
# cancellation reason. Both a merge and a close-without-merge are reported; the
# controller does not distinguish them for this purpose.
#
# This is a controller API call driven entirely by event metadata — it checks
# out and runs no pull request code — which is why the workflow that invokes it
# can safely run under `pull_request_target`.
#
# The call fails soft: a controller that cannot be reached leaves the jobs
# running (the stall watchdog remains the backstop) rather than failing the
# workflow. So does a controller that rejects the event (`ok:false`, e.g. a
# branch that resolves to more than one workstream); its error is surfaced as
# a workflow warning rather than reported as zero cancellations.
#
# Usage: cancel-pr-branch-jobs.sh
#
# Required environment:
#   CONTROLLER_URL  - FlowTree controller base URL
#   HEAD_BRANCH     - the closed pull request's head branch
#   REPO_URL        - the head repository's clone URL; the controller cancels
#                     only a workstream that names this same repository, so a
#                     fork's pull request never reaches another repository's
#                     workstream that happens to share the branch name. When
#                     it is empty (the head repository was deleted) the script
#                     warns and exits successfully without calling the controller
#
# Optional environment:
#   PR_NUMBER               - the pull request number, for the cancellation reason
#   MERGED                  - "true" if the pull request was merged (default false)
#   EVENT_TIME              - ISO-8601 instant of the close; jobs submitted at or
#                             after it are left untouched (default: controller's now)
#   CF_ACCESS_CLIENT_ID     - Cloudflare Access service token client ID
#   CF_ACCESS_CLIENT_SECRET - Cloudflare Access service token client secret
#   CURL_CONNECT_TIMEOUT    - seconds to wait for the connection (default 10)
#   CURL_MAX_TIME           - seconds to bound the whole request  (default 30)

set -euo pipefail

: "${CONTROLLER_URL:?CONTROLLER_URL is required}"
: "${HEAD_BRANCH:?HEAD_BRANCH is required}"
# A pull request whose head repository was deleted (usually a fork) arrives with
# no clone URL. The controller would reject the event anyway, so this is
# reported and skipped rather than failing the workflow.
if [ -z "${REPO_URL:-}" ]; then
    echo "::warning::No head repository for ${HEAD_BRANCH} (deleted?); not asking the controller to cancel in-flight jobs"
    exit 0
fi

if [ "${MERGED:-false}" = "true" ]; then
    MERGED_JSON=true
else
    MERGED_JSON=false
fi

BODY=$(jq -nc \
    --arg headBranch "$HEAD_BRANCH" \
    --arg repoUrl "$REPO_URL" \
    --arg prNumber "${PR_NUMBER:-}" \
    --arg eventTime "${EVENT_TIME:-}" \
    --argjson merged "$MERGED_JSON" \
    '{headBranch: $headBranch, repoUrl: $repoUrl, prNumber: $prNumber, merged: $merged, eventTime: $eventTime}')

CURL_ARGS=(-sS -f \
    --connect-timeout "${CURL_CONNECT_TIMEOUT:-10}" \
    --max-time "${CURL_MAX_TIME:-30}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json")
if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

RESP=$(curl "${CURL_ARGS[@]}" -X POST --data "$BODY" \
    "${CONTROLLER_URL%/}/api/pr-events") || {
        echo "::warning::Could not reach the controller to cancel in-flight jobs for ${HEAD_BRANCH}; any are left running"
        exit 0
    }

if [ "$(echo "$RESP" | jq -r '.ok' 2>/dev/null || echo false)" != "true" ]; then
    ERROR=$(echo "$RESP" | jq -r '.error // "unrecognised response"' 2>/dev/null || echo "unrecognised response")
    echo "::warning::Controller did not cancel in-flight jobs for ${HEAD_BRANCH}: ${ERROR}"
    exit 0
fi

COUNT=$(echo "$RESP" | jq -r '.count // 0' 2>/dev/null || echo 0)
echo "::notice::Reported PR #${PR_NUMBER:-?} close on ${HEAD_BRANCH}; controller cancelled ${COUNT} in-flight job(s)"
