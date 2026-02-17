#!/usr/bin/env bash
#
# Start the FlowTree Slack Bot Controller.
#
# Connects to Slack via Socket Mode and dispatches coding prompts
# to FlowTree agents as ClaudeCodeJob instances. Agents connect
# inbound to this controller on the FlowTree port.
#
# Required environment variables (or use --tokens <file>):
#   SLACK_BOT_TOKEN   - Slack bot token (xoxb-...)
#   SLACK_APP_TOKEN   - Slack app-level token for Socket Mode (xapp-...)
#
# Optional environment variables:
#   SLACK_CHANNEL_ID        - Default channel to monitor
#   SLACK_CHANNEL_NAME      - Human-readable channel name
#   FLOWTREE_PORT           - FlowTree listening port (default: 7766)
#   GIT_DEFAULT_BRANCH      - Default git branch for commits
#
# Usage:
#   ./start-slack-controller.sh [options]
#
# Options:
#   --tokens, -t <file>       JSON file with botToken/appToken
#   --config, -c <file>       YAML configuration file
#   --channel <id>            Slack channel ID to monitor
#   --channel-name <name>     Human-readable channel name
#   --branch <name>           Default git branch for commits
#   --api-port <port>         Port for the HTTP API endpoint (default: 7780)
#   --flowtree-port <port>    Port for the FlowTree server (default: 7766)
#   --help, -h                Show this help
#

set -euo pipefail

# Resolve project root (parent of bin/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"

MAIN_CLASS="io.flowtree.slack.FlowTreeController"

# Show help if requested (before potentially building)
for arg in "$@"; do
    if [[ "$arg" == "--help" || "$arg" == "-h" ]]; then
        echo "FlowTree Controller"
        echo ""
        echo "Usage: $(basename "$0") [options]"
        echo ""
        echo "Agents connect TO this controller on the FlowTree port."
        echo "Set FLOWTREE_ROOT_HOST and FLOWTREE_ROOT_PORT on each agent."
        echo ""
        echo "Options:"
        echo "  --tokens, -t <file>       JSON file with botToken/appToken"
        echo "  --config, -c <file>       YAML configuration file"
        echo "  --channel <id>            Slack channel ID to monitor"
        echo "  --channel-name <name>     Human-readable channel name"
        echo "  --branch <name>           Default git branch for commits"
        echo "  --api-port <port>         Port for the HTTP API endpoint (default: 7780)"
        echo "  --flowtree-port <port>    Port for the FlowTree server (default: 7766)"
        echo "  --help, -h                Show this help"
        echo ""
        echo "Environment variables:"
        echo "  SLACK_BOT_TOKEN        Slack bot token (xoxb-...)"
        echo "  SLACK_APP_TOKEN        Slack app-level token for Socket Mode (xapp-...)"
        echo "  SLACK_CHANNEL_ID       Default channel to monitor"
        echo "  FLOWTREE_PORT          FlowTree listening port (default: 7766)"
        echo "  GIT_DEFAULT_BRANCH     Default git branch for commits"
        echo ""
        echo "Token resolution (first match wins):"
        echo "  1. --tokens <file>           Explicit token file"
        echo "  2. ./slack-tokens.json       Convention file in working directory"
        echo "  3. SLACK_BOT_TOKEN / SLACK_APP_TOKEN environment variables"
        exit 0
    fi
done

# Build if target directory is missing (first-run convenience)
if [ ! -d "${MODULE_DIR}/target" ]; then
    echo "Target directory not found. Building flowtree module..."
    mvn -f "${PROJECT_ROOT}/pom.xml" install -DskipTests -pl flowtree -am
fi

# Run the controller
exec mvn -f "${PROJECT_ROOT}/pom.xml" exec:java \
    -pl flowtree \
    -Dexec.mainClass="${MAIN_CLASS}" \
    -Dexec.args="$*"
