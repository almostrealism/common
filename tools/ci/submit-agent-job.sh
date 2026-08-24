#!/usr/bin/env bash
# ─── Submit a job to the FlowTree coding-agent controller ────────────
#
# Reads a prompt from a file and POSTs it to the FlowTree controller's
# submission endpoint. The workstream is resolved server-side from the
# repository and target branch; when no workstream is registered for that
# combination the controller creates one (see CREATE_WORKSTREAM below), so a
# project gets auto-resolve runs on a branch nobody registered by hand.
#
# Usage:
#   submit-agent-job.sh <prompt-file>
#
# Required environment variables:
#   BRANCH           - target branch for the agent to work on
#   BASE_BRANCH      - base branch for comparison
#
# Optional environment variables:
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
#   REPO_URL          - repository the branch belongs to. Defaults to
#                       "git@github.com:$GITHUB_REPOSITORY.git" when running in
#                       GitHub Actions, so no project needs to set it; set it
#                       explicitly for a host other than github.com. Sent as
#                       repoUrl: it disambiguates the workstream lookup, and a
#                       workstream created on demand is both identified by it
#                       and cloned from it — hence the SSH form, matching
#                       register-workstream.sh, so the agent clones with the
#                       same credentials it uses everywhere else. Lookup itself
#                       is insensitive to the URL form.
#   CREATE_WORKSTREAM - create a workstream when none is registered for this
#                       repository and branch (default: true). Set to false to
#                       have the controller reject such a submission instead.
#   MAX_TURNS         - agent turn budget             (omitted → workstream default)
#   MAX_BUDGET_USD    - agent dollar budget           (omitted → workstream default)
#   ENFORCE_CHANGES   - require code changes or retry (default: false)
#   AUTO_CREATE_PR    - auto-create a GitHub PR on success (default: false)
#   STARTED_AFTER     - epoch millis; skip if a newer job exists (default: unset)
#   DELAY_SECONDS     - delay execution by this many seconds (default: unset → run immediately)
#   DESCRIPTION       - short label for notifications (e.g., "Resolve test failures")
#   DEFAULT_PHASE_CONFIG - JSON object applied to every phase that has no
#                       dedicated entry, e.g. '{"model":"opus"}'. Omitted →
#                       the workstream/workspace default applies.
#   PHASE_CONFIGS     - JSON object mapping phase wire names to per-phase
#                       config, e.g. '{"primary":{"model":"opus"}}'. Each named
#                       phase overrides DEFAULT_PHASE_CONFIG field-by-field.
#                       Use this to pin one phase without disturbing the rest.
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

PROMPT=$(cat "$PROMPT_FILE")

# The repository is half of the (repository, branch) pair that identifies a
# workstream. GITHUB_REPOSITORY is always exported inside a workflow, so this
# default covers every caller running in GitHub Actions with no per-project
# setup at all.
if [ -z "${REPO_URL:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
    REPO_URL="git@github.com:${GITHUB_REPOSITORY}.git"
fi

CREATE_WORKSTREAM="${CREATE_WORKSTREAM:-true}"
case "$CREATE_WORKSTREAM" in
    true|false) ;;
    *)
        echo "ERROR: CREATE_WORKSTREAM must be true or false (got: $CREATE_WORKSTREAM)" >&2
        exit 1
        ;;
esac

ENDPOINT="${CONTROLLER_BASE}/api/submit"

# Build the JSON payload; maxTurns and maxBudgetUsd are omitted by default
# so the workstream's own defaults are used. Include them only when
# explicitly provided via environment variables.
PAYLOAD=$(jq -n \
    --arg prompt "$PROMPT" \
    --arg branch "$BRANCH" \
    --arg base "$BASE_BRANCH" \
    --argjson protect "${PROTECT_TEST_FILES:-false}" \
    --argjson enforce "${ENFORCE_CHANGES:-false}" \
    --argjson autopr "${AUTO_CREATE_PR:-false}" \
    --argjson automated true \
    --argjson createws "$CREATE_WORKSTREAM" \
    '{
        prompt: $prompt,
        targetBranch: $branch,
        baseBranch: $base,
        protectTestFiles: $protect,
        enforceChanges: $enforce,
        autoCreatePr: $autopr,
        automated: $automated,
        createWorkstreamIfMissing: $createws
    }')

if [ -n "${REPO_URL:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg r "$REPO_URL" '. + {repoUrl: $r}')
fi

if [ -n "${DESCRIPTION:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg d "$DESCRIPTION" '. + {description: $d}')
fi

if [ -n "${MAX_TURNS:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson t "$MAX_TURNS" '. + {maxTurns: $t}')
fi

if [ -n "${MAX_BUDGET_USD:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson b "$MAX_BUDGET_USD" '. + {maxBudgetUsd: $b}')
fi

# Phase configuration is passed through as parsed JSON rather than a string:
# the controller reads `defaultPhaseConfig` / `phaseConfigs` as objects. jq
# fails loudly on malformed input here, which is the right moment to find out
# — a job that silently ran on the wrong model would look like a successful
# run whose results are not what was asked for.
if [ -n "${DEFAULT_PHASE_CONFIG:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson c "$DEFAULT_PHASE_CONFIG" \
        '. + {defaultPhaseConfig: $c}')
fi

if [ -n "${PHASE_CONFIGS:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson c "$PHASE_CONFIGS" \
        '. + {phaseConfigs: $c}')
fi

if [ -n "${STARTED_AFTER:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --arg t "$STARTED_AFTER" '. + {startedAfter: $t}')
fi

if [ -n "${DELAY_SECONDS:-}" ]; then
    PAYLOAD=$(echo "$PAYLOAD" | jq --argjson d "$DELAY_SECONDS" '. + {delaySeconds: $d}')
fi

# Send Cloudflare Access service-token headers when provided so the request
# is authorized through the tunnel in front of the controller.
CURL_ARGS=(-s -w "\n%{http_code}" -X POST -H "Content-Type: application/json")

if [ -n "${CF_ACCESS_CLIENT_ID:-}" ] && [ -n "${CF_ACCESS_CLIENT_SECRET:-}" ]; then
    CURL_ARGS+=(-H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}")
    CURL_ARGS+=(-H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}")
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
    echo "::error::Agent job submission failed (HTTP $HTTP_CODE): $BODY"
    exit 1
fi

SKIPPED=$(echo "$BODY" | jq -r '.skipped // empty')
if [ "$SKIPPED" = "true" ]; then
    REASON=$(echo "$BODY" | jq -r '.reason // "unknown"')
    echo "::notice::Agent job skipped: $REASON"
    exit 0
fi

# Check if automated jobs are disabled
AUTOMATED_REJECTED=$(echo "$BODY" | jq -r 'if .automated == true and .ok == false then "true" else "false" end')
if [ "$AUTOMATED_REJECTED" = "true" ]; then
    echo "::notice::Automated job submission rejected — automated jobs are currently disabled on the controller"
    exit 0
fi

JOB_ID=$(echo "$BODY" | jq -r '.jobId // empty')
echo "job_id=$JOB_ID"
echo "Agent job submitted: $JOB_ID"

if [ "$(echo "$BODY" | jq -r '.workstreamCreated // empty')" = "true" ]; then
    WS_ID=$(echo "$BODY" | jq -r '.workstreamId // "unknown"')
    echo "::notice::Created workstream $WS_ID for ${REPO_URL:-this repository} on branch $BRANCH"
fi
