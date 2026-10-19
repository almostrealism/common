#!/usr/bin/env bash
# ─── Claim the next task of a tracker release for a workstream ──────────
#
# The task-planning job claims the task its round will plan before it
# submits the agent: the agent is told which task is its own instead of
# claiming one. CI cannot reach the tracker, so it asks the FlowTree
# controller, which answers POST /api/tracker/claim by taking the
# highest-priority task of the release that is ready, unclaimed and not
# waiting on unfinished work, and linking it to the workstream. That link
# is also what lets the round's agent edit the task.
#
# The claim fails closed: when the controller cannot be reached, refuses the
# claim, or answers with something that is not a task, nothing is claimed
# and the round submits no agent. A task can also be gone by the time this
# runs — another round took the last one after tracker-claimable.sh counted
# it — which is reported as nothing claimed, not as an error.
#
# The task itself (title and description are free text written by a person)
# never goes into $GITHUB_OUTPUT. It is written as JSON to TASK_FILE and
# only the file's path and the task id are emitted.
#
# Usage:
#   tracker-claim.sh
#
# Required environment variables:
#   TRACKER_PROJECT  - tracker project name (e.g. Common)
#   TRACKER_RELEASE  - full tracker release name, "<project> <version>"
#   WORKSTREAM_ID    - the workstream the claimed task is linked to
#   CONTROLLER_URL   - FlowTree controller base URL
#
# Optional environment variables:
#   TASK_FILE               - where the claimed task's JSON is written
#                             (default: $RUNNER_TEMP/tracker-claimed-task.json,
#                             or under $TMPDIR outside Actions)
#   CF_ACCESS_CLIENT_ID     - Cloudflare Access service token client ID
#   CF_ACCESS_CLIENT_SECRET - Cloudflare Access service token client secret
#   CURL_CONNECT_TIMEOUT    - seconds to wait for the connection (default 10)
#   CURL_MAX_TIME           - seconds to bound the whole request  (default 30)
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   claimed=true|false
#   task_id=<id>         (empty when nothing was claimed)
#   task_file=<path>     (empty when nothing was claimed)
#
# Exit codes:
#   0 - decision made (check claimed=)
#   1 - invalid arguments

set -euo pipefail

for var in TRACKER_PROJECT TRACKER_RELEASE WORKSTREAM_ID CONTROLLER_URL; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

TASK_FILE="${TASK_FILE:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/tracker-claimed-task.json}"

emit() {
    echo "claimed=$1"
    echo "task_id=$2"
    echo "task_file=$3"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        {
            echo "claimed=$1"
            echo "task_id=$2"
            echo "task_file=$3"
        } >> "$GITHUB_OUTPUT"
    fi
}

# A stale file from an earlier claim on the same runner must never be
# mistaken for this one's task.
rm -f "$TASK_FILE"

PAYLOAD=$(jq -cn \
    --arg project "$TRACKER_PROJECT" \
    --arg release "$TRACKER_RELEASE" \
    --arg workstream "$WORKSTREAM_ID" \
    '{project: $project, release: $release, workstreamId: $workstream}')

# Seeded rather than declared empty: under `set -u`, bash 3.2 treats the
# expansion of an empty array as an unbound variable. The timeouts bound a
# controller that accepts the connection but stops responding, as in
# tracker-claimable.sh. -w appends the status code so a refusal can be
# reported with the controller's own explanation.
CURL_ARGS=(-sS -X POST -w "\n%{http_code}" \
    --connect-timeout "${CURL_CONNECT_TIMEOUT:-10}" \
    --max-time "${CURL_MAX_TIME:-30}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json")
if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

RESPONSE=$(curl "${CURL_ARGS[@]}" -d "$PAYLOAD" \
    "${CONTROLLER_URL%/}/api/tracker/claim") || {
        echo "::warning::Could not reach the controller to claim a task — not starting a round."
        emit false "" ""
        exit 0
    }

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
# The body is quoted in warnings on one line, so a newline in it cannot
# start a workflow command of its own.
BODY_LINE=$(printf '%s' "$BODY" | tr '\r\n' '  ')

if [ "$HTTP_CODE" != "200" ]; then
    echo "::warning::The controller refused the claim (HTTP ${HTTP_CODE}): ${BODY_LINE} — not starting a round."
    emit false "" ""
    exit 0
fi

# "none" when the release had nothing left to claim, "invalid" for anything
# that is not a well-formed answer. A task id is emitted as an output, so it
# must be a plain token: a newline in it would forge further outputs.
VERDICT=$(echo "$BODY" | jq -r '
    if .ok != true then "invalid"
    elif .task == null then "none"
    elif (.task | type) != "object" then "invalid"
    elif (.task.id | type) == "number" then "task"
    elif (.task.id | type) == "string" and (.task.id | test("^[A-Za-z0-9._:-]+$")) then "task"
    else "invalid" end' 2>/dev/null || echo invalid)

case "$VERDICT" in
    task)
        echo "$BODY" | jq '.task' > "$TASK_FILE"
        TASK_ID=$(jq -r '.id | tostring' "$TASK_FILE")
        echo "::notice::Claimed task ${TASK_ID} of ${TRACKER_RELEASE} for workstream ${WORKSTREAM_ID}."
        emit true "$TASK_ID" "$TASK_FILE"
        ;;
    none)
        echo "::notice::No task left to claim in ${TRACKER_RELEASE} — another round took it. Nothing to plan."
        emit false "" ""
        ;;
    *)
        echo "::warning::Unexpected answer from /api/tracker/claim: ${BODY_LINE} — not starting a round."
        emit false "" ""
        ;;
esac
