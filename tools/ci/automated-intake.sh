#!/usr/bin/env bash
# ─── Ask whether the controller is accepting automated jobs ────────────
#
# Every round in master-agent-dispatch.yaml ends by submitting an automated
# agent job, but it creates a branch and registers a workstream first. When
# a person has closed the controller's intake (POST
# /api/config/accept-automated-jobs {"accept": false} — done by hand, and by
# the deploy workflow while it drains), the submission is refused and the
# round is left behind half-built: a pushed branch, a registered workstream
# and no agent. The orphaned branch then reads as a round in progress, so
# the cadence gate holds off the next round for PR_GRACE_HOURS.
#
# This asks first, before anything is created. The answer fails closed: a
# controller that cannot be reached, or that does not answer with a
# boolean, would refuse the submission anyway, so no round starts.
#
# The answer is a snapshot, not a reservation: intake closed after this
# check and before the round submits still orphans that round. Closing that
# window needs a controller-side reservation; this check covers the common
# case of a round starting while intake is already closed.
#
# Usage:
#   automated-intake.sh
#
# Required environment variables:
#   CONTROLLER_URL   - FlowTree controller base URL
#
# Optional environment variables:
#   CF_ACCESS_CLIENT_ID     - Cloudflare Access service token client ID
#   CF_ACCESS_CLIENT_SECRET - Cloudflare Access service token client secret
#   CURL_CONNECT_TIMEOUT    - seconds to wait for the connection (default 10)
#   CURL_MAX_TIME           - seconds to bound the whole request  (default 30)
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   open=true|false
#
# Exit codes:
#   0 - decision made (check open=)
#   1 - invalid arguments

set -euo pipefail

if [ -z "${CONTROLLER_URL:-}" ]; then
    echo "ERROR: CONTROLLER_URL is not set." >&2
    exit 1
fi

emit() {
    echo "open=$1"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "open=$1" >> "$GITHUB_OUTPUT"
    fi
}

# Seeded rather than declared empty: under `set -u`, bash 3.2 treats the
# expansion of an empty array as an unbound variable. The timeouts keep a
# controller that accepts the connection but never answers from holding the
# job's concurrency slot.
CURL_ARGS=(-sS -f \
    --connect-timeout "${CURL_CONNECT_TIMEOUT:-10}" \
    --max-time "${CURL_MAX_TIME:-30}" \
    -H "Accept: application/json")
if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

BODY=$(curl "${CURL_ARGS[@]}" \
    "${CONTROLLER_URL%/}/api/config/accept-automated-jobs") || {
        echo "::warning::Could not ask the controller whether it accepts automated jobs — not starting a round."
        emit false
        exit 0
    }

ACCEPT=$(echo "$BODY" | jq -r 'if (.acceptAutomatedJobs | type) == "boolean" then .acceptAutomatedJobs else "invalid" end' 2>/dev/null || echo invalid)
case "$ACCEPT" in
    true)
        emit true
        ;;
    false)
        echo "::notice::The controller is not accepting automated jobs — not starting a round."
        emit false
        ;;
    *)
        echo "::warning::Unexpected answer from /api/config/accept-automated-jobs: ${BODY} — not starting a round."
        emit false
        ;;
esac
