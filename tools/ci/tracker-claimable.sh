#!/usr/bin/env bash
# ─── Ask whether a tracker release has a task an agent could claim ─────
#
# The task-planning job starts a round only when the release this
# repository is building has a task that is ready, unclaimed and not
# waiting on unfinished work. CI cannot reach the tracker, so it asks the
# FlowTree controller, which answers GET /api/tracker/claimable with the
# tracker's own count.
#
# The answer fails closed: when the controller cannot be reached or does
# not answer with a count, the job does not start. Guessing "there is work"
# would create a branch, a workstream and an agent job with nothing to do.
#
# Usage:
#   tracker-claimable.sh
#
# Required environment variables:
#   TRACKER_PROJECT  - tracker project name (e.g. Common)
#   TRACKER_RELEASE  - full tracker release name, "<project> <version>"
#   CONTROLLER_URL   - FlowTree controller base URL
#
# Optional environment variables:
#   CF_ACCESS_CLIENT_ID     - Cloudflare Access service token client ID
#   CF_ACCESS_CLIENT_SECRET - Cloudflare Access service token client secret
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   claimable=<count>
#   run=true|false
#
# Exit codes:
#   0 - decision made (check run=)
#   1 - invalid arguments

set -euo pipefail

for var in TRACKER_PROJECT TRACKER_RELEASE CONTROLLER_URL; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

emit() {
    echo "claimable=$1"
    echo "run=$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "claimable=$1" >> "$GITHUB_OUTPUT"
        echo "run=$2" >> "$GITHUB_OUTPUT"
    fi
}

# Seeded rather than declared empty: under `set -u`, bash 3.2 treats the
# expansion of an empty array as an unbound variable.
CURL_ARGS=(-sS -f -G -H "Accept: application/json")
if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

BODY=$(curl "${CURL_ARGS[@]}" \
    --data-urlencode "project=${TRACKER_PROJECT}" \
    --data-urlencode "release=${TRACKER_RELEASE}" \
    "${CONTROLLER_URL%/}/api/tracker/claimable") || {
        echo "::warning::Could not ask the controller for claimable tasks — not starting a round."
        emit 0 false
        exit 0
    }

COUNT=$(echo "$BODY" | jq -r 'if .ok == true and (.count | type) == "number" then .count else "invalid" end' 2>/dev/null || echo invalid)
if [ "$COUNT" = "invalid" ]; then
    echo "::warning::Unexpected answer from /api/tracker/claimable: ${BODY} — not starting a round."
    emit 0 false
    exit 0
fi

if [ "$COUNT" -gt 0 ]; then
    echo "::notice::${COUNT} claimable task(s) in ${TRACKER_RELEASE} — starting a planning round."
    emit "$COUNT" true
else
    echo "::notice::No claimable task in ${TRACKER_RELEASE} — nothing to plan."
    emit 0 false
fi
