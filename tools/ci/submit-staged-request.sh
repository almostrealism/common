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
#   PROTECT_TEST_FILES, ENFORCE_CHANGES, STARTED_AFTER
#
# Environment:
#   Everything submit-agent-job.sh reads for reaching the controller
#   (CONTROLLER_URL, CF_ACCESS_CLIENT_ID, CF_ACCESS_CLIENT_SECRET, ...).
#   Without CF_ACCESS_CLIENT_SECRET (a fork's pull request gets no secrets)
#   nothing is submitted.
#
# Exit codes:
#   0 - submitted, or nothing to do (no request, or no credentials)
#   1 - the submission failed

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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "${SCRIPT_DIR}/submit-agent-job.sh" "$REQUEST_DIR/agent-prompt.txt"
