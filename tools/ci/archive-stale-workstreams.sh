#!/usr/bin/env bash
# ─── Archive the workstreams left behind by earlier QA runs ───────────
#
# Every run of a recurring QA job registers a workstream for its branch.
# Nothing ever retired them, so they accumulated: at the time this script
# was written, 26 of the 54 registered workstreams were abandoned
# documentation-QA rounds.
#
# This runs immediately before a new round is registered, and only once
# the cadence gate has established that no PR from a previous round is
# still open. That ordering is what makes archiving safe: an open PR
# means the round is still live and the gate stops the run before it
# reaches here, so anything this script finds has already been dealt
# with — merged, closed, or abandoned.
#
# KEEP_BRANCH is excluded so a caller that registers first and archives
# second cannot archive the round it just created. Archiving is
# reversible (POST .../unarchive), which is why this errs toward
# archiving rather than leaving the backlog to grow.
#
# Usage:
#   archive-stale-workstreams.sh
#
# Required environment variables:
#   BRANCH_PREFIX     - archive workstreams whose defaultBranch starts with this
#
# Optional environment variables:
#   KEEP_BRANCH       - never archive the workstream for this branch
#   CONTROLLER_URL    - FlowTree controller base URL (takes precedence)
#   CONTROLLER_HOST   - controller hostname (default: localhost)
#   CONTROLLER_PORT   - controller port     (default: 7780)
#   REPO_URL          - only archive workstreams on this repository
#   DRY_RUN           - "true" reports what it would archive and stops
#
# Exit codes:
#   0 - finished (individual archive failures are warnings, not errors)
#   1 - invalid arguments or the workstream list could not be read

set -euo pipefail

if [ -z "${BRANCH_PREFIX:-}" ]; then
    echo "ERROR: BRANCH_PREFIX is not set." >&2
    exit 1
fi

if [ -n "${CONTROLLER_URL:-}" ]; then
    CONTROLLER_BASE="${CONTROLLER_URL%/}"
else
    CONTROLLER_BASE="http://${CONTROLLER_HOST:-localhost}:${CONTROLLER_PORT:-7780}"
fi

CURL_ARGS=(-sS -w "\n%{http_code}")
if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
fi

RESPONSE=$(curl "${CURL_ARGS[@]}" "${CONTROLLER_BASE}/api/workstreams") || {
    echo "::error::Could not reach the controller at ${CONTROLLER_BASE}"
    exit 1
}

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" != "200" ]; then
    echo "::error::Listing workstreams failed (HTTP $HTTP_CODE): $BODY"
    exit 1
fi

STALE=$(echo "$BODY" | jq -r \
    --arg p "$BRANCH_PREFIX" \
    --arg keep "${KEEP_BRANCH:-}" \
    --arg repo "${REPO_URL:-}" \
    '[ .[]
       | select(.defaultBranch != null)
       | select(.defaultBranch | startswith($p))
       | select(.defaultBranch != $keep)
       | select($repo == "" or .repoUrl == $repo)
     ] | .[] | "\(.workstreamId)\t\(.defaultBranch)"')

if [ -z "$STALE" ]; then
    echo "::notice::No stale ${BRANCH_PREFIX}* workstreams to archive"
    exit 0
fi

COUNT=$(echo "$STALE" | wc -l | tr -d ' ')
echo "::notice::Found ${COUNT} stale ${BRANCH_PREFIX}* workstream(s) to archive"

if [ "${DRY_RUN:-false}" = "true" ]; then
    echo "Dry run — would archive:"
    echo "$STALE" | while IFS=$'\t' read -r id branch; do
        echo "  $id  $branch"
    done
    exit 0
fi

ARCHIVED=0
FAILED=0

while IFS=$'\t' read -r ID BRANCH; do
    [ -z "$ID" ] && continue
    ARCHIVE_RESPONSE=$(curl "${CURL_ARGS[@]}" \
        -X POST \
        -H "Content-Type: application/json" \
        -d '{"archiveSlackChannel": true}' \
        "${CONTROLLER_BASE}/api/workstreams/${ID}/archive") || {
            echo "::warning::Archive request failed for ${BRANCH} (${ID})"
            FAILED=$((FAILED + 1))
            continue
        }

    ARCHIVE_CODE=$(echo "$ARCHIVE_RESPONSE" | tail -1)
    if [ "$ARCHIVE_CODE" = "200" ]; then
        echo "  archived ${BRANCH} (${ID})"
        ARCHIVED=$((ARCHIVED + 1))
    else
        ARCHIVE_BODY=$(echo "$ARCHIVE_RESPONSE" | sed '$d')
        echo "::warning::Archive failed for ${BRANCH} (${ID}): HTTP ${ARCHIVE_CODE} ${ARCHIVE_BODY}"
        FAILED=$((FAILED + 1))
    fi
done <<< "$STALE"

echo "::notice::Archived ${ARCHIVED} workstream(s); ${FAILED} failure(s)"

# A failure to archive is not a reason to fail the run: the new round is
# more valuable than the cleanup, and the next run retries the leftovers.
exit 0
