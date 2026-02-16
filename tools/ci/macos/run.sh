#!/usr/bin/env bash
set -euo pipefail

# ─── macOS Self-Hosted GitHub Actions Runner ───────────────────────────
#
# Starts a GitHub Actions runner in ephemeral mode on macOS. After each
# job completes, the runner re-registers and picks up the next job.
# This mimics the Docker Compose restart behavior used on Linux.
#
# The runner registers with labels: self-hosted, macos, ar-ci
#
# Prerequisites:
#   - Run setup.sh first to install the runner agent
#   - Configure .env with GITHUB_PAT, GITHUB_OWNER, GITHUB_REPO
#
# Usage:
#   ./run.sh [--runner-dir /path/to/runner]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="${1:-${HOME}/actions-runner}"

# ---------- Load configuration ----------

ENV_FILE="${SCRIPT_DIR}/.env"
if [ ! -f "${ENV_FILE}" ]; then
    echo "ERROR: ${ENV_FILE} not found."
    echo "  Copy .env.example to .env and fill in the values."
    exit 1
fi

# shellcheck source=/dev/null
source "${ENV_FILE}"

# ---------- Validate ----------

for var in GITHUB_PAT GITHUB_OWNER GITHUB_REPO; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set in ${ENV_FILE}."
        exit 1
    fi
done

if [ ! -f "${RUNNER_DIR}/config.sh" ]; then
    echo "ERROR: Runner not found at ${RUNNER_DIR}."
    echo "  Run setup.sh first."
    exit 1
fi

RUNNER_NAME="${RUNNER_NAME:-$(hostname)-macos}"
RUNNER_GROUP="${RUNNER_GROUP:-Default}"
RUNNER_WORKDIR="${RUNNER_WORKDIR:-${RUNNER_DIR}/_work}"

mkdir -p "${RUNNER_WORKDIR}"

# ---------- Set AR environment variables ----------

export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
mkdir -p "${AR_HARDWARE_LIBS}"

# ---------- Graceful shutdown ----------

RUNNING=true

cleanup() {
    echo ""
    echo "Caught signal, stopping runner..."
    RUNNING=false

    REMOVE_TOKEN=$(curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/remove-token" \
        | jq -r '.token')

    cd "${RUNNER_DIR}"
    ./config.sh remove --token "${REMOVE_TOKEN}" 2>/dev/null || true
    echo "Runner removed. Exiting."
    exit 0
}
trap cleanup SIGTERM SIGINT

# ---------- Runner loop ----------

echo "Starting macOS runner loop for ${GITHUB_OWNER}/${GITHUB_REPO}"
echo "  Runner name:  ${RUNNER_NAME}"
echo "  Runner dir:   ${RUNNER_DIR}"
echo "  Work dir:     ${RUNNER_WORKDIR}"
echo "  Labels:       self-hosted, macos, ar-ci"
echo ""

cd "${RUNNER_DIR}"

while ${RUNNING}; do
    # Request registration token
    echo "Requesting registration token..."
    REG_TOKEN=$(curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/registration-token" \
        | jq -r '.token')

    if [ "${REG_TOKEN}" = "null" ] || [ -z "${REG_TOKEN}" ]; then
        echo "ERROR: Failed to obtain registration token. Retrying in 30s..."
        sleep 30
        continue
    fi

    # Configure in ephemeral mode
    echo "Configuring runner (ephemeral)..."
    ./config.sh \
        --url "https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}" \
        --token "${REG_TOKEN}" \
        --name "${RUNNER_NAME}" \
        --labels "self-hosted,macos,ar-ci" \
        --runnergroup "${RUNNER_GROUP}" \
        --work "${RUNNER_WORKDIR}" \
        --replace \
        --unattended \
        --ephemeral

    # Run one job
    echo "Waiting for job..."
    ./run.sh || true

    echo ""
    echo "Job completed. Re-registering for next job..."
    echo ""

    # Brief pause before re-registering
    sleep 2
done
