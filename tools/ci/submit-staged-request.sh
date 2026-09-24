#!/usr/bin/env bash
# ─── Submit a staged remediation request to the FlowTree controller ──
#
# Reads a request directory written by stage-submit-request.sh (an
# agent-prompt.txt plus a submit.env of KEY=value lines) and submits it via
# submit-agent-job.sh.
#
# The request directory arrives as an artifact produced by a job that ran the
# pull request's own code, so its contents are untrusted. submit.env is
# therefore NOT sourced or appended to $GITHUB_ENV: only the keys
# stage-submit-request.sh is documented to write are exported, each as a
# literal value. Anything else (PATH, BASH_ENV, LD_PRELOAD, credentials, ...)
# is ignored with a warning, so a crafted request cannot change how this
# script or submit-agent-job.sh runs while the controller credentials are in
# the environment. Callers must run this script from a trusted checkout (the
# default branch), never from the pull request's tree.
#
# Usage:
#   submit-staged-request.sh <request-dir>
#
# Environment:
#   Everything submit-agent-job.sh reads for reaching the controller
#   (CONTROLLER_URL, CF_ACCESS_CLIENT_ID, CF_ACCESS_CLIENT_SECRET, ...).
#
# Exit codes:
#   0 - submitted, or no request was staged (nothing to do)
#   1 - the submission failed

set -euo pipefail

REQUEST_DIR="${1:?usage: submit-staged-request.sh <request-dir>}"

if [ ! -f "$REQUEST_DIR/submit.env" ] || [ ! -f "$REQUEST_DIR/agent-prompt.txt" ]; then
    echo "::notice::No staged request in ${REQUEST_DIR} — nothing to submit"
    exit 0
fi

while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$line" ] && continue
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
        BRANCH|BASE_BRANCH|STARTED_AFTER|DESCRIPTION|PROTECT_TEST_FILES|ENFORCE_CHANGES|REPO_URL|CREATE_WORKSTREAM)
            export "$key=$value"
            ;;
        *)
            echo "::warning::Ignoring unexpected key in the staged submit.env: ${key}"
            ;;
    esac
done < "$REQUEST_DIR/submit.env"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "${SCRIPT_DIR}/submit-agent-job.sh" "$REQUEST_DIR/agent-prompt.txt"
