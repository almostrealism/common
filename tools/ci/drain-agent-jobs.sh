#!/usr/bin/env bash
# Wait until the FlowTree controller reports no in-flight agent jobs.
#
# Restarting ar-manager drops the MCP connection of every running coding-agent
# job, losing whatever that job had not yet committed. The deploy workflow
# closes job intake, calls this to wait for the running set to empty, and
# fails rather than forcing: a deploy that silently kills active work is worse
# than a deploy that does not happen.
#
# Environment:
#   CONTROLLER_URL          controller base URL (default http://localhost:7780)
#   DRAIN_TIMEOUT_SECONDS   give up after this long (default 1800)
#   DRAIN_POLL_SECONDS      seconds between polls (default 30)
#
# Exit 0 when drained, 1 on timeout.
set -euo pipefail

CONTROLLER_URL="${CONTROLLER_URL:-http://localhost:7780}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-1800}"
DRAIN_POLL_SECONDS="${DRAIN_POLL_SECONDS:-30}"

# Statuses that mean "this job still holds an ar-manager connection".
# An unrecognised status is NOT counted as running: the failure mode to avoid
# is blocking every deploy forever on a status nobody remembers adding.
#
# The controller being unreachable counts as zero. The workflow already skips
# the drain when the controller is down before it starts; this covers it going
# away mid-wait, where there is no longer a connection left to protect.
count_running() {
    local body
    if ! body=$(curl -fsS "${CONTROLLER_URL}/api/jobs?limit=100" 2>/dev/null); then
        echo 0
        return 0
    fi

    # python3 reports 0 for any payload it cannot make sense of, so this
    # emits exactly one integer on every path.
    printf '%s' "${body}" | python3 -c 'import json, sys
ACTIVE = {"running", "in_progress", "started", "pending", "queued"}
try:
    jobs = json.load(sys.stdin)
except Exception:
    jobs = []
if not isinstance(jobs, list):
    jobs = []
print(sum(1 for j in jobs
          if isinstance(j, dict)
          and str(j.get("status", "")).lower() in ACTIVE))'
}

deadline=$(( $(date +%s) + DRAIN_TIMEOUT_SECONDS ))

while :; do
    running=$(count_running)
    # Belt and braces: never let a malformed count abort the deploy with a
    # bash "integer expression expected" instead of a real verdict.
    case "${running}" in
        ''|*[!0-9]*) running=0 ;;
    esac

    if [ "${running}" -eq 0 ]; then
        echo "::notice::No in-flight agent jobs — safe to restart the stack"
        exit 0
    fi

    now=$(date +%s)
    if [ "${now}" -ge "${deadline}" ]; then
        echo "::error::${running} agent job(s) still running after ${DRAIN_TIMEOUT_SECONDS}s." \
             "Not deploying. Re-run the workflow with skip_drain=true to interrupt them deliberately."
        exit 1
    fi

    echo "Waiting for ${running} in-flight job(s); $(( deadline - now ))s left before giving up"
    sleep "${DRAIN_POLL_SECONDS}"
done
