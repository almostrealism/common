#!/usr/bin/env bash
# ─── Submit a staged remediation request to the FlowTree controller ──
#
# Reads a request directory written by stage-submit-request.sh (an
# agent-prompt.txt plus a submit.env of KEY=value lines) and submits it via
# submit-agent-job.sh.
#
# The request directory arrives as an artifact produced by a job that ran the
# pull request's own code, so its contents are untrusted. submit.env is
# therefore NOT sourced or appended to $GITHUB_ENV, and it does not get to
# decide anything that matters about the submission:
#
#   - Where the job goes is the caller's: BRANCH and BASE_BRANCH must already
#     be set from the triggering event, and submit.env's values for them are
#     ignored. REPO_URL and CREATE_WORKSTREAM are not accepted at all;
#     submit-agent-job.sh derives them from the trusted environment.
#   - PROTECT_TEST_FILES, ENFORCE_CHANGES and STARTED_AFTER set by the
#     caller win. Otherwise the request may only turn PROTECT_TEST_FILES and
#     ENFORCE_CHANGES on ("true"; "false" is their default anyway), never
#     off, and STARTED_AFTER must be all digits (epoch millis) — a malformed
#     value would otherwise break the submission's JSON.
#   - DESCRIPTION is exported as a literal value.
#
# Anything else (PATH, BASH_ENV, LD_PRELOAD, credentials, ...) is ignored with
# a warning, so a crafted request cannot change how this script or
# submit-agent-job.sh runs while the controller credentials are in the
# environment. Callers must run this script from a trusted checkout (the
# default branch), never from the pull request's tree.
#
# Usage:
#   submit-staged-request.sh <request-dir>
#
# Required environment:
#   BRANCH, BASE_BRANCH - the branch the request is for, from the event
#
# Optional environment (from the caller, never overridden by the request):
#   PROTECT_TEST_FILES, ENFORCE_CHANGES, STARTED_AFTER, REQUIRED_LABELS,
#   OBJECT_ORIENTED_REVIEW_ENABLED, REMEDIATION_PASS
#   (REQUIRED_LABELS, OBJECT_ORIENTED_REVIEW_ENABLED and REMEDIATION_PASS pass
#   straight through to submit-agent-job.sh; a request that names any of them is
#   ignored like any other unexpected key. The remediation submitters set
#   OBJECT_ORIENTED_REVIEW_ENABLED=false so the object-oriented design review
#   does not ride along on the follow-up jobs CI runs in a loop, and
#   REMEDIATION_PASS=true so the controller counts the pass against the per-PR
#   cap and records its commit as the loop's own, so the cap resets on a commit
#   no pass produced.)
#
# Environment:
#   Everything submit-agent-job.sh reads for reaching the controller
#   (CONTROLLER_URL, CF_ACCESS_CLIENT_ID, CF_ACCESS_CLIENT_SECRET, ...).
#   Without CF_ACCESS_CLIENT_SECRET (a fork's pull request gets no secrets)
#   nothing is submitted.
#
#   REMEDIATION_KIND - a label for the remediation route this submission is for
#                      (e.g. "auto-resolve-python"), used only in the rejection
#                      alert below. Falls back to GITHUB_JOB, then "remediation".
#
# ─── Making a rejected submission visible ──────────────────────────────
#
# When the controller rejects the submission (a non-2xx response, or a 2xx that
# created no job), submit-agent-job.sh prints the reason and exits non-zero —
# which on its own leaves only a red submit job with nothing pointing at the
# branch it was for. Before propagating that failure, this script posts a short
# message to the branch's workstream (POST /api/workstreams/{id}/messages, the
# same endpoint ar-manager's send_message uses) naming the remediation kind, the
# branch and workflow run, the HTTP status, and the controller's error text, and
# writes the same summary to $GITHUB_STEP_SUMMARY. The alert is best-effort: if
# it cannot be delivered (no workstream for the branch, an unreachable
# controller) it logs a warning and the original submission failure still fails
# the job. The alert path never turns a rejection into success.
#
# Exit codes:
#   0 - submitted, or nothing to do (no request, or no credentials)
#   1 - the submission failed (the rejection is alerted first)

set -euo pipefail

REQUEST_DIR="${1:?usage: submit-staged-request.sh <request-dir>}"
: "${BRANCH:?BRANCH must be set by the caller from the triggering event}"
: "${BASE_BRANCH:?BASE_BRANCH must be set by the caller from the triggering event}"

if [ ! -f "$REQUEST_DIR/submit.env" ] || [ ! -f "$REQUEST_DIR/agent-prompt.txt" ]; then
    echo "::notice::No staged request in ${REQUEST_DIR} — nothing to submit"
    exit 0
fi

if [ -z "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    echo "::notice::No controller credentials in this run (a fork's pull request gets no secrets) — the staged request is not submitted"
    exit 0
fi

CALLER_PROTECT_TEST_FILES="${PROTECT_TEST_FILES:-}"
CALLER_ENFORCE_CHANGES="${ENFORCE_CHANGES:-}"
CALLER_STARTED_AFTER="${STARTED_AFTER:-}"

# A boolean the caller's value decides, and the request may only turn on.
# $1 names the variable, $2 is the caller's value, $3 the request's.
request_may_enable() {
    if [ -n "$2" ]; then
        return
    elif [ "$3" = "true" ]; then
        export "$1=true"
    elif [ "$3" != "false" ]; then
        echo "::warning::Ignoring $1=$3 from the staged request; it is not a boolean"
    fi
}

while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$line" ] && continue
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
        DESCRIPTION)
            export "$key=$value"
            ;;
        ENFORCE_CHANGES)
            request_may_enable ENFORCE_CHANGES "$CALLER_ENFORCE_CHANGES" "$value"
            ;;
        PROTECT_TEST_FILES)
            request_may_enable PROTECT_TEST_FILES "$CALLER_PROTECT_TEST_FILES" "$value"
            ;;
        STARTED_AFTER)
            if [ -n "$CALLER_STARTED_AFTER" ]; then
                :
            elif [[ "$value" =~ ^[0-9]+$ ]]; then
                export STARTED_AFTER="$value"
            else
                echo "::warning::Ignoring STARTED_AFTER=${value} from the staged request; it is not epoch milliseconds"
            fi
            ;;
        TRIGGER_COMMIT)
            # Used only for the controller's cap-reset comparison; accept a git
            # SHA (hex) and ignore anything else rather than forward it.
            if [[ "$value" =~ ^[0-9a-fA-F]+$ ]]; then
                export TRIGGER_COMMIT="$value"
            else
                echo "::warning::Ignoring TRIGGER_COMMIT=${value} from the staged request; not a commit SHA"
            fi
            ;;
        BRANCH|BASE_BRANCH)
            if [ "$value" != "${!key}" ]; then
                echo "::warning::The staged request names ${key}=${value}, but this run is for ${!key}; submitting for ${!key}"
            fi
            ;;
        *)
            echo "::warning::Ignoring unexpected key in the staged submit.env: ${key}"
            ;;
    esac
done < "$REQUEST_DIR/submit.env"

# Resolve the controller endpoint the same way submit-agent-job.sh does, so the
# rejection alert reaches the same controller the submission did.
if [ -n "${CONTROLLER_URL:-}" ]; then
    CONTROLLER_BASE="${CONTROLLER_URL%/}"
else
    CONTROLLER_BASE="http://${CONTROLLER_HOST:-localhost}:${CONTROLLER_PORT:-7780}"
fi

# Post a short message to the branch's workstream naming why its remediation
# submission was rejected. Best-effort: every failure here is a warning and the
# caller's original submission failure is what fails the job. $1 is the HTTP
# status (or a short phrase when there was no response), $2 the error text.
post_rejection_alert() {
    status="$1"
    error="$2"

    kind="${REMEDIATION_KIND:-${GITHUB_JOB:-remediation}}"

    if [ -n "${GITHUB_RUN_ID:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
        run_ref="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
    else
        run_ref="(run unknown)"
    fi

    message=$(printf 'Remediation submission rejected: %s\nbranch %s, run %s\nHTTP %s: %s' \
        "$kind" "$BRANCH" "$run_ref" "$status" "$error")

    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        printf '%s\n' "$message" >> "$GITHUB_STEP_SUMMARY"
    fi

    # The repository is the other half of the (repository, branch) pair that
    # identifies the workstream; derive it the way submit-agent-job.sh does.
    repo="${REPO_URL:-}"
    if [ -z "$repo" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
        repo="git@github.com:${GITHUB_REPOSITORY}.git"
    fi

    alert_curl_args=(-sS -w "\n%{http_code}")
    if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
        alert_curl_args+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
        alert_curl_args+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
    fi

    list_response=$(curl "${alert_curl_args[@]}" "${CONTROLLER_BASE}/api/workstreams") || {
        echo "::warning::Could not reach the controller to post the rejection alert for ${BRANCH}"
        return 1
    }
    list_code=$(printf '%s' "$list_response" | tail -1)
    list_body=$(printf '%s' "$list_response" | sed '$d')
    if [ "$list_code" != "200" ]; then
        echo "::warning::Could not list workstreams to post the rejection alert for ${BRANCH} (HTTP ${list_code})"
        return 1
    fi

    # Prefer the workstream on this repository; fall back to the branch alone
    # when the repository URL form does not match (still better than no alert).
    ws_id=$(printf '%s' "$list_body" | jq -r --arg b "$BRANCH" --arg r "$repo" \
        '[ .[] | select(.defaultBranch == $b) | select($r == "" or .repoUrl == $r) ] | .[0].workstreamId // empty' 2>/dev/null || true)
    if [ -z "$ws_id" ]; then
        ws_id=$(printf '%s' "$list_body" | jq -r --arg b "$BRANCH" \
            '[ .[] | select(.defaultBranch == $b) ] | .[0].workstreamId // empty' 2>/dev/null || true)
    fi
    if [ -z "$ws_id" ]; then
        echo "::warning::No workstream registered for ${BRANCH}; cannot post the rejection alert"
        return 1
    fi

    payload=$(jq -n --arg t "$message" '{text: $t}')
    post_response=$(curl "${alert_curl_args[@]}" -X POST \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "${CONTROLLER_BASE}/api/workstreams/${ws_id}/messages") || {
        echo "::warning::Could not post the rejection alert to workstream ${ws_id}"
        return 1
    }
    post_code=$(printf '%s' "$post_response" | tail -1)
    if [ "$post_code" != "200" ]; then
        echo "::warning::Posting the rejection alert to workstream ${ws_id} failed (HTTP ${post_code})"
        return 1
    fi
    echo "::notice::Posted a rejection alert to workstream ${ws_id} for ${BRANCH}"
    return 0
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Run the submission, streaming its output live while capturing it, so a
# rejection can be read back and alerted. (This is why the submission is no
# longer exec'd: the script has to regain control to post the alert.)
SUBMIT_LOG="$(mktemp)"
trap 'rm -f "$SUBMIT_LOG"' EXIT

set +e
"${SCRIPT_DIR}/submit-agent-job.sh" "$REQUEST_DIR/agent-prompt.txt" 2>&1 | tee "$SUBMIT_LOG"
SUBMIT_STATUS=${PIPESTATUS[0]}
set -e

if [ "$SUBMIT_STATUS" -eq 0 ]; then
    exit 0
fi

# The submission failed. Read the HTTP status and the controller's error text
# back out of submit-agent-job.sh's output. It prints "Response (<code>): <body>"
# for every response it received; a curl failure (unreachable controller) prints
# no such line, only an ::error:: line.
RESPONSE_LINE=$(grep -E '^Response \([0-9]+\): ' "$SUBMIT_LOG" | tail -1 || true)
if [ -n "$RESPONSE_LINE" ]; then
    HTTP_STATUS=$(printf '%s' "$RESPONSE_LINE" | sed -E 's/^Response \(([0-9]+)\): .*/\1/')
    RESPONSE_BODY=$(printf '%s' "$RESPONSE_LINE" | sed -E 's/^Response \([0-9]+\): //')
    ERROR_TEXT=$(printf '%s' "$RESPONSE_BODY" | jq -r '.error // empty' 2>/dev/null || true)
    if [ -z "$ERROR_TEXT" ]; then
        ERROR_TEXT="$RESPONSE_BODY"
    fi
else
    HTTP_STATUS="no response"
    ERROR_TEXT=$(grep -E '^::error::' "$SUBMIT_LOG" | tail -1 | sed -E 's/^::error:://' || true)
fi

# Trim surrounding whitespace and keep the alert short.
ERROR_TEXT=$(printf '%s' "$ERROR_TEXT" | tr -d '\r' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')
ERROR_TEXT="${ERROR_TEXT:0:400}"
[ -z "$ERROR_TEXT" ] && ERROR_TEXT="(no error text)"

post_rejection_alert "$HTTP_STATUS" "$ERROR_TEXT" || \
    echo "::warning::The rejection alert could not be delivered; the submission failure stands"

exit "$SUBMIT_STATUS"
