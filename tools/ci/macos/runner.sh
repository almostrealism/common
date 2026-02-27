#!/usr/bin/env bash
set -euo pipefail

# ─── macOS Self-Hosted GitHub Actions Runner ─────────────────────────
#
# Single script that installs (if needed) and runs a GitHub Actions
# runner in ephemeral mode. If the runner dies or its registration is
# deleted server-side, the script removes the local config and
# re-registers automatically.
#
# Prerequisites:
#   - macOS (Intel or Apple Silicon)
#   - JDK 17+ installed and on PATH
#   - Maven installed and on PATH
#   - curl, jq available
#
# Usage:
#   chmod +x runner.sh
#   ./runner.sh [/path/to/runner-dir]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="${1:-${HOME}/actions-runner}"

# ---------- Load configuration ----------

ENV_FILE="${SCRIPT_DIR}/.env"
if [ ! -f "${ENV_FILE}" ]; then
    if [ -f "${SCRIPT_DIR}/.env.example" ]; then
        cp "${SCRIPT_DIR}/.env.example" "${SCRIPT_DIR}/.env"
        echo "Created .env from template. Edit it before running:"
        echo "  ${ENV_FILE}"
        exit 1
    fi
    echo "ERROR: ${ENV_FILE} not found."
    echo "  Copy .env.example to .env and fill in the values."
    exit 1
fi

# shellcheck source=/dev/null
source "${ENV_FILE}"

for var in GITHUB_PAT GITHUB_OWNER GITHUB_REPO; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set in ${ENV_FILE}."
        exit 1
    fi
done

# ---------- Verify prerequisites ----------

MISSING=""
for cmd in java mvn curl jq; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        MISSING="${MISSING} ${cmd}"
    fi
done

if [ -n "${MISSING}" ]; then
    echo "ERROR: Missing required commands:${MISSING}"
    echo "  Install with Homebrew:"
    echo "    brew install jq maven"
    echo "    brew install --cask temurin@17"
    exit 1
fi

JAVA_VERSION_RAW="$(java -version 2>&1 | head -1)"
JAVA_MAJOR="$(echo "${JAVA_VERSION_RAW}" | sed -n 's/.*"\([0-9][0-9]*\).*/\1/p')"

if [ -z "${JAVA_MAJOR}" ] || [ "${JAVA_MAJOR}" -lt 17 ]; then
    echo "ERROR: JDK 17+ required. Found: ${JAVA_VERSION_RAW}"
    exit 1
fi

# ---------- Install runner if missing ----------

if [ ! -f "${RUNNER_DIR}/config.sh" ]; then
    echo "Runner not found at ${RUNNER_DIR}. Installing..."

    ARCH="$(uname -m)"
    if [ "${ARCH}" = "arm64" ] && ! /usr/bin/pgrep -q oahd; then
        echo "Installing Rosetta 2..."
        softwareupdate --install-rosetta --agree-to-license
    fi

    case "${ARCH}" in
        x86_64)  RUNNER_ARCH="x64" ;;
        arm64)   RUNNER_ARCH="arm64" ;;
        *)
            echo "ERROR: Unsupported architecture: ${ARCH}"
            exit 1
            ;;
    esac

    API_RESPONSE="$(curl -sS https://api.github.com/repos/actions/runner/releases/latest)"
    RUNNER_VERSION="$(echo "${API_RESPONSE}" | jq -r '.tag_name // empty' | sed 's/^v//')"

    if [ -z "${RUNNER_VERSION}" ]; then
        echo "ERROR: Could not determine latest runner version."
        exit 1
    fi

    mkdir -p "${RUNNER_DIR}"
    RUNNER_TAR="actions-runner-osx-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"
    RUNNER_URL="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${RUNNER_TAR}"

    echo "Downloading runner ${RUNNER_VERSION} (${RUNNER_ARCH})..."
    curl -fSL -o "${RUNNER_DIR}/${RUNNER_TAR}" "${RUNNER_URL}"
    tar xzf "${RUNNER_DIR}/${RUNNER_TAR}" -C "${RUNNER_DIR}"
    rm "${RUNNER_DIR}/${RUNNER_TAR}"

    echo "Runner installed at ${RUNNER_DIR}"
fi

# ---------- Runner configuration ----------

RUNNER_NAME="${RUNNER_NAME:-$(hostname)-macos}"
RUNNER_GROUP="${RUNNER_GROUP:-Default}"
RUNNER_WORKDIR="${RUNNER_WORKDIR:-${RUNNER_DIR}/_work}"

mkdir -p "${RUNNER_WORKDIR}"

# ---------- Set AR environment variables ----------

export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
mkdir -p "${AR_HARDWARE_LIBS}"

# ---------- Helper: remove runner config ----------

remove_runner() {
    if [ ! -f "${RUNNER_DIR}/.runner" ]; then
        return 0
    fi

    echo "Removing existing runner configuration..."

    REMOVE_TOKEN=$(curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/remove-token" \
        | jq -r '.token // empty')

    if [ -n "${REMOVE_TOKEN}" ]; then
        cd "${RUNNER_DIR}"
        ./config.sh remove --token "${REMOVE_TOKEN}" 2>/dev/null || true
    else
        echo "WARNING: Could not obtain remove token. Cleaning config files manually."
        rm -f "${RUNNER_DIR}/.runner" "${RUNNER_DIR}/.credentials" "${RUNNER_DIR}/.credentials_rsaparams"
    fi
}

# ---------- Helper: configure runner ----------

configure_runner() {
    echo "Requesting registration token..."
    REG_TOKEN=$(curl -s -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/registration-token" \
        | jq -r '.token // empty')

    if [ -z "${REG_TOKEN}" ]; then
        echo "ERROR: Failed to obtain registration token."
        return 1
    fi

    echo "Configuring runner (ephemeral)..."
    cd "${RUNNER_DIR}"
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
}

# ---------- Graceful shutdown ----------

RUNNING=true

cleanup() {
    echo ""
    echo "Caught signal, stopping runner..."
    RUNNING=false
    remove_runner
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
if [ -n "${RUNNER_CPU_LIMIT:-}" ]; then
    echo "  CPU limit:    ${RUNNER_CPU_LIMIT} CPUs"
fi
echo ""

while ${RUNNING}; do
    # Always remove old config before registering to avoid
    # "Cannot configure the runner because it is already configured"
    remove_runner

    if ! configure_runner; then
        echo "Registration failed. Retrying in 30s..."
        sleep 30
        continue
    fi

    # Run one job
    echo "Waiting for job..."
    cd "${RUNNER_DIR}"
    if [ -n "${RUNNER_CPU_LIMIT:-}" ]; then
        LIMIT_PCT=$(( RUNNER_CPU_LIMIT * 100 ))
        echo "Throttling job to ${RUNNER_CPU_LIMIT} CPUs (${LIMIT_PCT}%)"
        "${SCRIPT_DIR}/cpu-watcher.sh" $$ "${LIMIT_PCT}" &
        WATCHER_PID=$!
        ./run.sh || true
        kill "${WATCHER_PID}" 2>/dev/null || true
        wait "${WATCHER_PID}" 2>/dev/null || true
    else
        ./run.sh || true
    fi

    echo ""
    echo "Job completed. Re-registering for next job..."
    echo ""

    sleep 2
done
