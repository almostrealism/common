#!/usr/bin/env bash
set -euo pipefail

# ─── Self-hosted GitHub Actions Runner Entrypoint ────────────────────────
#
# Required environment variables:
#   GITHUB_OWNER    - GitHub org or user  (e.g. "almostrealism")
#   GITHUB_REPO     - Repository name     (e.g. "common")
#   GITHUB_PAT      - Personal access token with repo + admin:org scope
#
# Optional:
#   RUNNER_NAME     - Override the runner name (default: hostname)
#   RUNNER_LABELS   - Comma-separated extra labels (always includes "self-hosted,linux,ar-ci")
#   RUNNER_GROUP    - Runner group (default: "Default")
#   RUNNER_WORKDIR  - Working directory for job execution (default: /home/runner/_work)

# ---------- Validation ----------
for var in GITHUB_OWNER GITHUB_REPO GITHUB_PAT; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set."
        exit 1
    fi
done

RUNNER_NAME="${RUNNER_NAME:-$(hostname)}"
RUNNER_LABELS="${RUNNER_LABELS:-}"
RUNNER_GROUP="${RUNNER_GROUP:-Default}"
RUNNER_WORKDIR="${RUNNER_WORKDIR:-/home/runner/_work}"

# Build label string: always include baseline labels
BASE_LABELS="self-hosted,linux"
if [ -n "${RUNNER_LABELS}" ]; then
    ALL_LABELS="${BASE_LABELS},${RUNNER_LABELS}"
else
    ALL_LABELS="${BASE_LABELS}"
fi

mkdir -p "${RUNNER_WORKDIR}"

# ---------- Obtain registration token ----------
echo "Requesting registration token for ${GITHUB_OWNER}/${GITHUB_REPO}..."
REG_TOKEN=$(curl -s -X POST \
    -H "Authorization: token ${GITHUB_PAT}" \
    -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/registration-token" \
    | jq -r '.token')

if [ "${REG_TOKEN}" = "null" ] || [ -z "${REG_TOKEN}" ]; then
    echo "ERROR: Failed to obtain registration token. Check GITHUB_PAT permissions."
    exit 1
fi

echo "Registration token obtained."

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
    echo "Caught signal, removing runner..."
    # Request a new token for removal (the registration token may have expired)
    REMOVE_TOKEN=$(curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/remove-token" \
        | jq -r '.token')
    ./config.sh remove --token "${REMOVE_TOKEN}" || true
}
trap cleanup SIGTERM SIGINT

# ---------- Run ----------
echo "Starting runner..."
./run.sh &
wait $!
