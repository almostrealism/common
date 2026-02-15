#!/usr/bin/env bash

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
#   ./setup.sh [/path/to/runner-dir]

# Print every command before running it, and exit on error with a message
set -x
trap 'echo ""; echo "FAILED at line $LINENO (exit code $?)"; exit 1' ERR

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="${1:-${HOME}/actions-runner}"

# ---------- Verify prerequisites ----------

echo "Checking prerequisites..."

MISSING=""
for cmd in java mvn curl jq; do
    if command -v "$cmd" > /dev/null 2>&1; then
        echo "  $cmd: $(command -v "$cmd")"
    else
        echo "  $cmd: NOT FOUND"
        MISSING="${MISSING} ${cmd}"
    fi
done

if [ -n "${MISSING}" ]; then
    echo ""
    echo "ERROR: Missing required commands:${MISSING}"
    echo "  Install with Homebrew:"
    echo "    brew install jq maven"
    echo "    brew install --cask temurin@17"
    exit 1
fi

# Verify JDK version
JAVA_VERSION_RAW="$(java -version 2>&1 | head -1)"
echo "  Java version string: ${JAVA_VERSION_RAW}"
JAVA_MAJOR="$(echo "${JAVA_VERSION_RAW}" | sed -n 's/.*"\([0-9][0-9]*\).*/\1/p')"

if [ -z "${JAVA_MAJOR}" ]; then
    echo "ERROR: Could not parse Java version from: ${JAVA_VERSION_RAW}"
    exit 1
fi

if [ "${JAVA_MAJOR}" -lt 17 ]; then
    echo "ERROR: JDK 17+ required, found JDK ${JAVA_MAJOR}."
    echo "  Install JDK 17: brew install --cask temurin@17"
    exit 1
fi
echo "  JDK ${JAVA_MAJOR} - OK"

# ---------- Detect architecture ----------

ARCH="$(uname -m)"
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
API_RESPONSE="$(curl -sS https://api.github.com/repos/actions/runner/releases/latest)"
RUNNER_VERSION="$(echo "${API_RESPONSE}" | jq -r '.tag_name // empty' | sed 's/^v//')"

if [ -z "${RUNNER_VERSION}" ]; then
    echo "ERROR: Could not determine latest runner version."
    echo "  API response: ${API_RESPONSE}"
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
curl -fSL -o "${RUNNER_DIR}/${RUNNER_TAR}" "${RUNNER_URL}"

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

set +x
echo ""
echo "============================================"
echo "  Setup complete."
echo "============================================"
echo ""
echo "Runner installed at: ${RUNNER_DIR}"
echo ""
echo "Next steps:"
echo "  1. Edit ${SCRIPT_DIR}/.env with your GitHub PAT and repo details"
echo "  2. Start the runner: ${SCRIPT_DIR}/run.sh"
