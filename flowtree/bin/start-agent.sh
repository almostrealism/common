#!/usr/bin/env bash
#
# Start a FlowTree Agent node.
#
# The agent listens for ClaudeCodeJob submissions from a SlackBotController
# or ClaudeCodeClient and executes them via Claude Code.
#
# Environment variables (set automatically if not present):
#   AR_HARDWARE_LIBS    - Directory for native libraries (default: /tmp/ar_libs/)
#   AR_HARDWARE_DRIVER  - Hardware backend (default: native)
#
# Usage:
#   ./start-agent.sh [args...]
#

set -euo pipefail

# Resolve project root (parent of bin/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"

MAIN_CLASS="io.flowtree.Agent"

# Set AR hardware defaults if not already set
export AR_HARDWARE_LIBS="${AR_HARDWARE_LIBS:-/tmp/ar_libs/}"
export AR_HARDWARE_DRIVER="${AR_HARDWARE_DRIVER:-native}"

# Build if target directory is missing (first-run convenience)
if [ ! -d "${MODULE_DIR}/target" ]; then
    echo "Target directory not found. Building flowtree module..."
    mvn -f "${PROJECT_ROOT}/pom.xml" install -DskipTests -pl flowtree -am
fi

# Run the agent
exec mvn -f "${PROJECT_ROOT}/pom.xml" exec:java \
    -pl flowtree \
    -Dexec.mainClass="${MAIN_CLASS}" \
    -Dexec.args="$*"
