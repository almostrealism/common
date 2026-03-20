#!/usr/bin/env bash
# ─── Register a workstream with the FlowTree controller ─────────────
#
# Creates a new workstream for a branch, optionally with a planning
# document and auto-created Slack channel. The channel name is derived
# from the branch name by replacing slashes with hyphens.
#
# Idempotent: if a workstream already exists for the branch, and
# PLAN_FILE is set, the existing workstream is updated with the
# planning document via the /api/workstreams/{id}/update endpoint.
#
# Usage:
#   register-workstream.sh
#
# Required environment variables:
#   BRANCH           - target branch for the workstream
#   BASE_BRANCH      - base branch (e.g., master)
#
# Optional environment variables:
#   PLAN_FILE         - path to the planning document (relative to repo root)
#   CONTROLLER_HOST   - FlowTree controller hostname (default: localhost)
#   CONTROLLER_PORT   - FlowTree controller port     (default: 7780)
#   REPO_URL          - repository clone URL
#
# Exit codes:
#   0 - registration succeeded
#   1 - registration failed
#
# Outputs (to stdout):
#   workstream_id=<id>   (on success)

set -euo pipefail

for var in BRANCH BASE_BRANCH; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

CONTROLLER_HOST="${CONTROLLER_HOST:-localhost}"
CONTROLLER_PORT="${CONTROLLER_PORT:-7780}"

ENDPOINT="http://${CONTROLLER_HOST}:${CONTROLLER_PORT}/api/workstreams"

# Derive channel name from branch. If the branch contains a slash, use only
# the part after the first slash to keep names short:
#   feature/xyz            -> w-xyz
#   project/plan-20260308  -> w-plan-20260308
#   some-branch            -> w-some-branch
# Slack channel names: lowercase, max 80 chars, no spaces or periods
BRANCH_SUFFIX="${BRANCH#*/}"
if [ "$BRANCH_SUFFIX" = "$BRANCH" ]; then
    BRANCH_SUFFIX="$BRANCH"
fi
CHANNEL_NAME=$(echo "$BRANCH_SUFFIX" | sed 's|/|-|g' | tr '[:upper:]' '[:lower:]' | tr -d ' .')
CHANNEL_NAME="w-${CHANNEL_NAME#w-}"
# Enforce Slack 80-char channel name limit
CHANNEL_NAME="${CHANNEL_NAME:0:80}"

PAYLOAD=$(jq -n \
    --arg branch "$BRANCH" \
    --arg base "$BASE_BRANCH" \
    --arg channel "$CHANNEL_NAME" \
    '{
        defaultBranch: $branch,
        baseBranch: $base,
        channelName: $channel
    }')

if [ -n "${PLAN_FILE:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg plan "$PLAN_FILE" '. + {planningDocument: $plan}')
fi

if [ -n "${REPO_URL:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg url "$REPO_URL" '. + {repoUrl: $url}')
fi

RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" \
    "$ENDPOINT") || CURL_EXIT=$?

if [ "${CURL_EXIT:-0}" -ne 0 ]; then
    echo "::error::curl failed (exit code $CURL_EXIT) — controller may be unreachable at ${CONTROLLER_HOST}:${CONTROLLER_PORT}"
    exit 1
fi

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "Response ($HTTP_CODE): $BODY"

if [ "$HTTP_CODE" != "200" ]; then
    echo "::error::Workstream registration failed (HTTP $HTTP_CODE): $BODY"
    exit 1
fi

WORKSTREAM_ID=$(echo "$BODY" | jq -r '.workstreamId // empty')
EXISTING=$(echo "$BODY" | jq -r '.existing // false')

# If the workstream already existed and we have a plan file, update it
# so the planning document gets attached to the existing workstream.
if [ "$EXISTING" = "true" ] && [ -n "${PLAN_FILE:-}" ] && [ -n "$WORKSTREAM_ID" ]; then
    echo "Workstream already exists ($WORKSTREAM_ID) — updating with planning document"
    UPDATE_ENDPOINT="${ENDPOINT}/${WORKSTREAM_ID}/update"
    UPDATE_PAYLOAD=$(jq -n --arg plan "$PLAN_FILE" '{planningDocument: $plan}')

    UPDATE_RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$UPDATE_PAYLOAD" \
        "$UPDATE_ENDPOINT") || UPDATE_EXIT=$?

    if [ "${UPDATE_EXIT:-0}" -ne 0 ]; then
        echo "::warning::Failed to update workstream with planning document (curl exit $UPDATE_EXIT)"
    else
        UPDATE_CODE=$(echo "$UPDATE_RESPONSE" | tail -1)
        UPDATE_BODY=$(echo "$UPDATE_RESPONSE" | sed '$d')
        if [ "$UPDATE_CODE" != "200" ]; then
            echo "::warning::Failed to update workstream with planning document (HTTP $UPDATE_CODE): $UPDATE_BODY"
        else
            echo "Updated workstream $WORKSTREAM_ID with planning document: $PLAN_FILE"
        fi
    fi
fi

echo "workstream_id=$WORKSTREAM_ID"
echo "Workstream registered: $WORKSTREAM_ID"
