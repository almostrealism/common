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
#   - PROTECT_TEST_FILES is accepted only as "true" (its default), so a
#     request cannot switch test-file protection off.
#   - DESCRIPTION, STARTED_AFTER and ENFORCE_CHANGES are exported as literal
#     values.
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

while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$line" ] && continue
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
        DESCRIPTION|STARTED_AFTER|ENFORCE_CHANGES)
            export "$key=$value"
            ;;
        PROTECT_TEST_FILES)
            if [ "$value" != "true" ]; then
                echo "::warning::Ignoring PROTECT_TEST_FILES=${value} from the staged request; test files stay protected"
            fi
            export PROTECT_TEST_FILES=true
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
