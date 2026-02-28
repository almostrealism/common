#!/usr/bin/env bash
set -euo pipefail

# ─── Self-hosted GitHub Actions Runner Entrypoint ────────────────────────
#
# Required environment variables:
#   GITHUB_OWNER    - GitHub org or user  (e.g. "almostrealism")
#   GITHUB_REPO     - Repository name     (e.g. "common")
#   GITHUB_PAT      - Personal access token with repo + admin:org scope
#   RUNNER_PREFIX   - Name prefix for this machine (e.g. "mac-studio")
#
# Optional:
#   RUNNER_NAME     - Explicit name (skips auto-indexing)
#   RUNNER_LABELS   - Comma-separated extra labels (always includes "self-hosted,linux")
#   RUNNER_GROUP    - Runner group (default: "Default")
#   RUNNER_WORKDIR  - Working directory for job execution (default: /home/runner/_work)

# ---------- Validation ----------
for var in GITHUB_OWNER GITHUB_REPO GITHUB_PAT; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set."
        exit 1
    fi
done

REPO_API="https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}"
RUNNER_PREFIX="${RUNNER_PREFIX:-ar-runner}"
RUNNER_LABELS="${RUNNER_LABELS:-}"
RUNNER_GROUP="${RUNNER_GROUP:-Default}"
RUNNER_WORKDIR="${RUNNER_WORKDIR:-/home/runner/_work}"

BASE_LABELS="self-hosted,linux"
if [ -n "${RUNNER_LABELS}" ]; then
    ALL_LABELS="${BASE_LABELS},${RUNNER_LABELS}"
else
    ALL_LABELS="${BASE_LABELS}"
fi

mkdir -p "${RUNNER_WORKDIR}"

# ---------- Token helpers ----------
get_reg_token() {
    curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "${REPO_API}/actions/runners/registration-token" \
        | jq -r '.token'
}

get_remove_token() {
    curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "${REPO_API}/actions/runners/remove-token" \
        | jq -r '.token'
}

# ---------- Clean up local config from previous run ----------
if [ -f .runner ]; then
    echo "Removing leftover local .runner config..."
    REMOVE_TOKEN=$(get_remove_token)
    ./config.sh remove --token "${REMOVE_TOKEN}" 2>/dev/null || true
    rm -f .runner .credentials .credentials_rsaparams 2>/dev/null || true
fi

# ---------- Claim a sequential name ----------
# If RUNNER_NAME is explicitly set, use it. Otherwise find the lowest
# available <prefix>-N by querying the GitHub runners API.
if [ -z "${RUNNER_NAME:-}" ]; then
    # Small random delay to reduce races when many containers start together
    sleep $(( RANDOM % 3 ))

    echo "Finding available runner slot for prefix '${RUNNER_PREFIX}'..."
    REGISTERED=$(curl -s \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "${REPO_API}/actions/runners?per_page=100" \
        | jq -r --arg pfx "${RUNNER_PREFIX}-" \
            '[.runners[] | select(.name | startswith($pfx)) | select(.status == "online") | .name] | sort | .[]')

    # Find the first unused index starting from 1
    for i in $(seq 1 100); do
        CANDIDATE="${RUNNER_PREFIX}-${i}"
        if ! echo "${REGISTERED}" | grep -qx "${CANDIDATE}"; then
            RUNNER_NAME="${CANDIDATE}"
            break
        fi
    done

    if [ -z "${RUNNER_NAME:-}" ]; then
        echo "ERROR: Could not find an available runner slot (tried 1-100)."
        exit 1
    fi

    # If a stale (offline) runner exists with this name, remove it
    STALE_ID=$(curl -s \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "${REPO_API}/actions/runners?per_page=100" \
        | jq -r --arg name "${RUNNER_NAME}" \
            '.runners[] | select(.name == $name) | .id // empty')

    if [ -n "${STALE_ID}" ]; then
        echo "Removing stale runner '${RUNNER_NAME}' (id=${STALE_ID})..."
        curl -s -X DELETE \
            -H "Authorization: token ${GITHUB_PAT}" \
            -H "Accept: application/vnd.github.v3+json" \
            "${REPO_API}/actions/runners/${STALE_ID}" || true
    fi
fi

echo "Claiming runner name: ${RUNNER_NAME}"

# ---------- Obtain registration token ----------
REG_TOKEN=$(get_reg_token)

if [ "${REG_TOKEN}" = "null" ] || [ -z "${REG_TOKEN}" ]; then
    echo "ERROR: Failed to obtain registration token. Check GITHUB_PAT permissions."
    exit 1
fi

# ---------- Configure the runner ----------
echo "Configuring runner '${RUNNER_NAME}' with labels [${ALL_LABELS}]..."
./config.sh \
    --url "https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}" \
    --token "${REG_TOKEN}" \
    --name "${RUNNER_NAME}" \
    --labels "${ALL_LABELS}" \
    --runnergroup "${RUNNER_GROUP}" \
    --work "${RUNNER_WORKDIR}" \
    --replace \
    --unattended \
    --ephemeral

# ---------- Graceful shutdown ----------
cleanup() {
    echo "Caught signal, removing runner '${RUNNER_NAME}'..."
    REMOVE_TOKEN=$(get_remove_token)
    ./config.sh remove --token "${REMOVE_TOKEN}" || true
}
trap cleanup SIGTERM SIGINT

# ---------- Run ----------
echo "Starting runner '${RUNNER_NAME}'..."
./run.sh &
wait $!
