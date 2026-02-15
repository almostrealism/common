#!/usr/bin/env bash
set -euo pipefail

# ─── macOS Self-Hosted GitHub Actions Runner Setup ─────────────────────
#
# One-time setup script that installs the GitHub Actions runner agent
# on a macOS machine. After running this, use run.sh to start the runner.
#
# Prerequisites:
#   - macOS (Intel or Apple Silicon)
#   - JDK 17 installed and on PATH
#   - Maven installed and on PATH
#   - curl, jq available
#
# Usage:
#   chmod +x setup.sh
#   ./setup.sh [--runner-dir /path/to/runner]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="${1:-${HOME}/actions-runner}"

# ---------- Verify prerequisites ----------

echo "Checking prerequisites..."

check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "ERROR: $1 is not installed or not on PATH."
        echo "  $2"
        exit 1
    fi
}

check_command java "Install JDK 17: brew install --cask temurin@17"
check_command mvn  "Install Maven: brew install maven"
check_command curl "Install curl: brew install curl"
check_command jq   "Install jq: brew install jq"

# Verify JDK version
JAVA_VERSION=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [ "${JAVA_VERSION}" -lt 17 ]; then
    echo "ERROR: JDK 17+ required, found JDK ${JAVA_VERSION}."
    echo "  Install JDK 17: brew install --cask temurin@17"
    exit 1
fi
echo "  JDK ${JAVA_VERSION} - OK"
echo "  Maven $(mvn --version | head -1 | awk '{print $3}') - OK"

# ---------- Detect architecture ----------

ARCH=$(uname -m)
case "${ARCH}" in
    x86_64)  RUNNER_ARCH="x64" ;;
    arm64)   RUNNER_ARCH="arm64" ;;
    *)
        echo "ERROR: Unsupported architecture: ${ARCH}"
        exit 1
        ;;
esac
echo "  Architecture: ${ARCH} (runner arch: ${RUNNER_ARCH})"

# ---------- Get latest runner version ----------

echo ""
echo "Fetching latest runner release..."
RUNNER_VERSION=$(curl -s https://api.github.com/repos/actions/runner/releases/latest \
    | jq -r '.tag_name' | sed 's/^v//')

if [ -z "${RUNNER_VERSION}" ] || [ "${RUNNER_VERSION}" = "null" ]; then
    echo "ERROR: Could not determine latest runner version."
    echo "  Check network connectivity and GitHub API access."
    exit 1
fi
echo "  Runner version: ${RUNNER_VERSION}"

# ---------- Download and install runner ----------

echo ""
echo "Installing runner to ${RUNNER_DIR}..."

mkdir -p "${RUNNER_DIR}"

RUNNER_TAR="actions-runner-osx-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"
RUNNER_URL="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${RUNNER_TAR}"

echo "  Downloading ${RUNNER_URL}..."
curl -fsSL -o "${RUNNER_DIR}/${RUNNER_TAR}" "${RUNNER_URL}"

echo "  Extracting..."
tar xzf "${RUNNER_DIR}/${RUNNER_TAR}" -C "${RUNNER_DIR}"
rm "${RUNNER_DIR}/${RUNNER_TAR}"

# ---------- Copy configuration files ----------

if [ ! -f "${SCRIPT_DIR}/.env" ]; then
    cp "${SCRIPT_DIR}/.env.example" "${SCRIPT_DIR}/.env"
    echo ""
    echo "Created .env from template. Edit it before running:"
    echo "  ${SCRIPT_DIR}/.env"
fi

# ---------- Create AR libs directory ----------

mkdir -p /tmp/ar_libs

# ---------- Done ----------

echo ""
echo "Setup complete."
echo ""
echo "Next steps:"
echo "  1. Edit ${SCRIPT_DIR}/.env with your GitHub PAT and repo details"
echo "  2. Start the runner: ${SCRIPT_DIR}/run.sh"
echo ""
echo "Runner installed at: ${RUNNER_DIR}"
