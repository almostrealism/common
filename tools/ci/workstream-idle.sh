#!/usr/bin/env bash
# ─── Report whether a workstream has an in-flight agent job ─────────
#
# Asks the controller's in-flight view for a single workstream: the jobs it
# records as running (STARTED) and as pending (SUBMITTED but not yet started).
# A workstream with either is busy, and a caller about to submit another job to
# it — an implementation dispatched from a plan PR's ready_for_review, a manual
# Verify Completion, or project_verify_branch — should refuse rather than start
# a second one beside the first.
#
# The two views are the same ones the operator's job listings and the deploy
# drain read, so "in flight" here means exactly what it means there.
#
# Fails open: a controller that cannot be reached, or a view that does not
# parse, is reported as idle (inflight=false). The alternative — refusing on a
# transient controller blip — would block a legitimate approval, and the
# per-branch concurrency group plus the submission's own stale-job guard still
# stand behind this check.
#
# Required environment variables:
#   WORKSTREAM_ID    - the workstream to query
#
# Optional environment variables:
#   CONTROLLER_URL    - FlowTree controller base URL (takes precedence)
#   CONTROLLER_HOST   - controller hostname (default: localhost; used when
#                       CONTROLLER_URL is unset)
#   CONTROLLER_PORT   - controller port     (default: 7780)
#   CF_ACCESS_CLIENT_ID     - Cloudflare Access service token client ID
#   CF_ACCESS_CLIENT_SECRET - Cloudflare Access service token client secret
#
# Outputs (stdout, and $GITHUB_OUTPUT when set):
#   inflight=true|false
#   inflight_count=<active+pending>
#
# Exit code: always 0 — this is a gate that reports a verdict, not a failure.
set -euo pipefail

if [ -z "${WORKSTREAM_ID:-}" ]; then
    echo "ERROR: WORKSTREAM_ID is not set." >&2
    exit 1
fi

if [ -n "${CONTROLLER_URL:-}" ]; then
    CONTROLLER_BASE="${CONTROLLER_URL%/}"
else
    CONTROLLER_HOST="${CONTROLLER_HOST:-localhost}"
    CONTROLLER_PORT="${CONTROLLER_PORT:-7780}"
    CONTROLLER_BASE="http://${CONTROLLER_HOST}:${CONTROLLER_PORT}"
fi

# Seeded with a header so the array is never empty under `set -u` on bash 3.2.
# The time cap keeps a stalled controller from holding the gate open until the
# job's own timeout: a query that does not answer in time counts as idle, like
# any other unreachable controller.
CURL_ARGS=(-fsS --max-time 30 -H "Accept: application/json")
if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

# Counts the entries in a JSON array the controller returned, reporting 0 for
# anything that is unreachable or does not parse as an array.
count_jobs() {
    local url="$1" body
    if ! body=$(curl "${CURL_ARGS[@]}" "$url" 2>/dev/null); then
        echo 0
        return 0
    fi
    printf '%s' "$body" | python3 -c 'import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    data = []
print(len(data) if isinstance(data, list) else 0)'
}

ACTIVE=$(count_jobs "${CONTROLLER_BASE}/api/workstreams/${WORKSTREAM_ID}/jobs/active")
PENDING=$(count_jobs "${CONTROLLER_BASE}/api/workstreams/${WORKSTREAM_ID}/jobs/pending")

case "$ACTIVE" in ''|*[!0-9]*) ACTIVE=0 ;; esac
case "$PENDING" in ''|*[!0-9]*) PENDING=0 ;; esac

TOTAL=$((ACTIVE + PENDING))
if [ "$TOTAL" -gt 0 ]; then
    INFLIGHT=true
    echo "::notice::Workstream ${WORKSTREAM_ID} has ${ACTIVE} running and ${PENDING} pending job(s)"
else
    INFLIGHT=false
    echo "::notice::Workstream ${WORKSTREAM_ID} has no in-flight jobs"
fi

echo "inflight=${INFLIGHT}"
echo "inflight_count=${TOTAL}"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "inflight=${INFLIGHT}" >> "$GITHUB_OUTPUT"
    echo "inflight_count=${TOTAL}" >> "$GITHUB_OUTPUT"
fi
