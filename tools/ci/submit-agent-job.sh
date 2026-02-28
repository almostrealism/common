#!/usr/bin/env bash
# ─── Submit a job to the FlowTree coding-agent controller ────────────
#
# Reads a prompt from a file and POSTs it to the FlowTree controller's
# submission endpoint. The workstream is resolved server-side from the
# target branch.
#
# Usage:
#   submit-agent-job.sh <prompt-file>
#
# Required environment variables:
#   BRANCH           - target branch for the agent to work on
#   BASE_BRANCH      - base branch for comparison
#
# Optional environment variables:
#   CONTROLLER_HOST   - FlowTree controller hostname (default: localhost)
#   CONTROLLER_PORT   - FlowTree controller port     (default: 7780)
#   MAX_TURNS         - agent turn budget             (omitted → workstream default)
#   MAX_BUDGET_USD    - agent dollar budget           (omitted → workstream default)
#   ENFORCE_CHANGES   - require code changes or retry (default: false)
#   DESCRIPTION       - short label for Slack notifications (e.g., "Resolve test failures")
#
# Exit codes:
#   0 - submission succeeded
#   1 - submission failed (unreachable host, non-200 response, etc.)
#
# Outputs (to stdout):
#   job_id=<id>   (on success)

set -euo pipefail

PROMPT_FILE="${1:-}"

if [ -z "$PROMPT_FILE" ]; then
    echo "Usage: $0 <prompt-file>" >&2
    exit 1
fi

for var in BRANCH BASE_BRANCH; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

CONTROLLER_HOST="${CONTROLLER_HOST:-localhost}"
CONTROLLER_PORT="${CONTROLLER_PORT:-7780}"

# ── Escalation Circuit Breaker (DECEPTION.md Countermeasure #6) ─────
# Check if any failing test class has been dispatched too many times.
# If so, block dispatch and require human intervention.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ESCALATION_TRACKER="${SCRIPT_DIR}/agent-protection/escalation-tracker.sh"
if [ -x "${ESCALATION_TRACKER}" ] && [ -n "${FAILING_TEST_CLASSES:-}" ]; then
    for TEST_CLASS in $FAILING_TEST_CLASSES; do
        if ! "${ESCALATION_TRACKER}" check "$BRANCH" "$TEST_CLASS"; then
            echo "::error::Circuit breaker tripped for ${TEST_CLASS} on ${BRANCH}. Manual intervention required."
            exit 0
        fi
        # Record this dispatch attempt
        "${ESCALATION_TRACKER}" record "$BRANCH" "$TEST_CLASS"
    done
fi

PROMPT=$(cat "$PROMPT_FILE")

ENDPOINT="http://${CONTROLLER_HOST}:${CONTROLLER_PORT}/api/submit"

# Build the JSON payload; maxTurns and maxBudgetUsd are omitted by default
# so the workstream's own defaults are used. Include them only when
# explicitly provided via environment variables.
PAYLOAD=$(jq -n \
    --arg prompt "$PROMPT" \
    --arg branch "$BRANCH" \
    --arg base "$BASE_BRANCH" \
    --argjson protect "${PROTECT_TEST_FILES:-false}" \
    --argjson enforce "${ENFORCE_CHANGES:-false}" \
    '{
        prompt: $prompt,
        targetBranch: $branch,
        baseBranch: $base,
        protectTestFiles: $protect,
        enforceChanges: $enforce
    }')

if [ -n "${DESCRIPTION:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg d "$DESCRIPTION" '. + {description: $d}')
fi

if [ -n "${MAX_TURNS:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson t "$MAX_TURNS" '. + {maxTurns: $t}')
fi

if [ -n "${MAX_BUDGET_USD:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson b "$MAX_BUDGET_USD" '. + {maxBudgetUsd: $b}')
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
    echo "::error::Agent job submission failed (HTTP $HTTP_CODE): $BODY"
    exit 1
fi

JOB_ID=$(echo "$BODY" | jq -r '.jobId // empty')
echo "job_id=$JOB_ID"
echo "Agent job submitted: $JOB_ID"
