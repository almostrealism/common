#!/usr/bin/env bash
# ─── Register a workstream with the FlowTree controller ─────────────
#
# Creates a new workstream for a branch, optionally with a planning
# document and auto-created Slack channel.  The controller derives the
# channel name automatically from the branch name (last path component,
# prepended with "w-", sanitized for Slack).  Pass CHANNEL_NAME to
# override the auto-generated name.
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
#   CHANNEL_NAME      - explicit Slack channel name (controller auto-generates when absent)
#   PLAN_FILE         - path to the planning document (relative to repo root)
#   CONTROLLER_URL    - FlowTree controller base URL. Takes precedence over
#                       CONTROLLER_HOST/CONTROLLER_PORT when set. Use this to
#                       reach the controller through the public Cloudflare
#                       Access tunnel (e.g. https://flowtree.almostrealism.ai)
#                       from a cloud runner.
#   CONTROLLER_HOST   - FlowTree controller hostname (default: localhost).
#                       Used only when CONTROLLER_URL is unset; reaches the
#                       controller directly on the closed network.
#   CONTROLLER_PORT   - FlowTree controller port     (default: 7780)
#   CF_ACCESS_CLIENT_ID     - Cloudflare Access service token client ID;
#                             sent as CF-Access-Client-Id header when set
#   CF_ACCESS_CLIENT_SECRET - Cloudflare Access service token client secret;
#                             sent as CF-Access-Client-Secret header when set
#   REPO_URL          - repository clone URL. Sent as repoUrl: it identifies
#                       the workstream alongside the branch, and a workstream
#                       is cloned from it — hence the SSH form, matching
#                       submit-agent-job.sh.
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

# Resolve the controller endpoint. CONTROLLER_URL (the Cloudflare-tunnelled
# public URL) takes precedence; otherwise fall back to building the URL from
# CONTROLLER_HOST/CONTROLLER_PORT for callers that reach the controller
# directly on the closed network.
if [ -n "${CONTROLLER_URL:-}" ]; then
    CONTROLLER_BASE="${CONTROLLER_URL%/}"
else
    CONTROLLER_HOST="${CONTROLLER_HOST:-localhost}"
    CONTROLLER_PORT="${CONTROLLER_PORT:-7780}"
    CONTROLLER_BASE="http://${CONTROLLER_HOST}:${CONTROLLER_PORT}"
fi

ENDPOINT="${CONTROLLER_BASE}/api/workstreams"

# Common curl arguments for every controller request. Seeded with the
# content type rather than declared empty: under `set -u`, bash 3.2 (the
# system bash on the macOS runners) treats the expansion of an empty array
# as an unbound variable and aborts.
#
# Cloudflare Access service-token headers are appended when provided, so the
# request is authorized through the tunnel in front of the controller.
CURL_ARGS=(-s -w "\n%{http_code}" -X POST -H "Content-Type: application/json")

if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

PAYLOAD=$(jq -n \
    --arg branch "$BRANCH" \
    --arg base "$BASE_BRANCH" \
    '{
        defaultBranch: $branch,
        baseBranch: $base
    }')

# Use an explicit channel name when provided; otherwise the controller
# auto-generates one from the branch name.
if [ -n "${CHANNEL_NAME:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg channel "$CHANNEL_NAME" '. + {channelName: $channel}')
fi

if [ -n "${PLAN_FILE:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg plan "$PLAN_FILE" '. + {planningDocument: $plan}')
fi

if [ -n "${REPO_URL:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg url "$REPO_URL" '. + {repoUrl: $url}')
fi

RESPONSE=$(curl "${CURL_ARGS[@]}" -d "$PAYLOAD" "$ENDPOINT") || CURL_EXIT=$?

if [ "${CURL_EXIT:-0}" -ne 0 ]; then
    echo "::error::curl failed (exit code $CURL_EXIT) — controller may be unreachable at ${CONTROLLER_BASE}"
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

    UPDATE_RESPONSE=$(curl "${CURL_ARGS[@]}" \
        -d "$UPDATE_PAYLOAD" "$UPDATE_ENDPOINT") || UPDATE_EXIT=$?

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
