#!/usr/bin/env bash
#
# Start the ar-manager MCP server in HTTP mode.
#
# Usage:
#   ./start.sh                    # defaults: mac-studio controller, port 8010
#   ./start.sh --port 9000        # custom port
#   ./start.sh --controller http://100.64.0.5:7780  # custom controller URL
#   ./start.sh --funnel           # also set up Tailscale Funnel
#
# Environment variables (override flags):
#   AR_CONTROLLER_URL       - FlowTree controller URL
#   AR_MANAGER_GITHUB_TOKEN - GitHub PAT for pipeline tools
#   AR_MANAGER_TOKEN_FILE   - Path to bearer token file
#   MCP_PORT                - Server port
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_PY="${SCRIPT_DIR}/server.py"

# Defaults
: "${AR_CONTROLLER_URL:=http://mac-studio:7780}"
: "${MCP_PORT:=8010}"
: "${AR_MANAGER_TOKEN_FILE:=${HOME}/.config/ar/manager-tokens.json}"
SETUP_FUNNEL=false

# Parse flags
while [ $# -gt 0 ]; do
    case "$1" in
        --port)
            MCP_PORT="$2"
            shift 2
            ;;
        --controller)
            AR_CONTROLLER_URL="$2"
            shift 2
            ;;
        --funnel)
            SETUP_FUNNEL=true
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [--port PORT] [--controller URL] [--funnel]"
            echo ""
            echo "Options:"
            echo "  --port PORT         MCP server port (default: 8010)"
            echo "  --controller URL    FlowTree controller URL (default: http://mac-studio:7780)"
            echo "  --funnel            Set up Tailscale Funnel to expose the server publicly"
            echo ""
            echo "Before first run:"
            echo "  1. pip install -r ${SCRIPT_DIR}/requirements.txt"
            echo "  2. ${SCRIPT_DIR}/generate-token.sh"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

export AR_CONTROLLER_URL
export MCP_PORT
export MCP_TRANSPORT=http
export AR_MANAGER_TOKEN_FILE

# Forward GitHub token if set
if [ -n "${AR_MANAGER_GITHUB_TOKEN:-}" ]; then
    export AR_MANAGER_GITHUB_TOKEN
elif [ -n "${GITHUB_TOKEN:-}" ]; then
    export GITHUB_TOKEN
fi

# Preflight checks
echo "ar-manager: Checking prerequisites..."

if ! python3 -c "import mcp" 2>/dev/null; then
    echo "ERROR: 'mcp' package not installed. Run:" >&2
    echo "  pip install -r ${SCRIPT_DIR}/requirements.txt" >&2
    exit 1
fi

if [ ! -f "$AR_MANAGER_TOKEN_FILE" ]; then
    echo "WARNING: No token file at ${AR_MANAGER_TOKEN_FILE}" >&2
    echo "  Server will run WITHOUT authentication." >&2
    echo "  Generate a token: ${SCRIPT_DIR}/generate-token.sh" >&2
    echo ""
fi

# Verify controller is reachable
echo "ar-manager: Checking controller at ${AR_CONTROLLER_URL}..."
if curl -sf --connect-timeout 3 "${AR_CONTROLLER_URL}/api/health" > /dev/null 2>&1; then
    echo "ar-manager: Controller is healthy"
else
    echo "WARNING: Controller at ${AR_CONTROLLER_URL} is not responding." >&2
    echo "  The server will start but tools will fail until the controller is reachable." >&2
fi

# Set up Tailscale Funnel if requested
if [ "$SETUP_FUNNEL" = true ]; then
    echo "ar-manager: Setting up Tailscale Funnel on port ${MCP_PORT}..."
    if command -v tailscale >/dev/null 2>&1; then
        tailscale funnel --bg "${MCP_PORT}"
        FUNNEL_URL=$(tailscale funnel --bg "${MCP_PORT}" 2>&1 | grep -oE 'https://[^ ]+' || true)
        if [ -n "$FUNNEL_URL" ]; then
            echo "ar-manager: Funnel active at ${FUNNEL_URL}"
        else
            # Derive the URL from tailscale status
            TS_NAME=$(tailscale status --self --json 2>/dev/null | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['Self']['DNSName'].rstrip('.'))" 2>/dev/null || echo "unknown")
            echo "ar-manager: Funnel configured. Likely URL: https://${TS_NAME}/"
        fi
    else
        echo "ERROR: tailscale CLI not found. Install Tailscale or skip --funnel." >&2
        exit 1
    fi
fi

echo ""
echo "ar-manager: Starting on port ${MCP_PORT}"
echo "  Controller:  ${AR_CONTROLLER_URL}"
echo "  Token file:  ${AR_MANAGER_TOKEN_FILE}"
echo "  GitHub PAT:  $([ -n "${AR_MANAGER_GITHUB_TOKEN:-}${GITHUB_TOKEN:-}" ] && echo '<set>' || echo '<not set>')"
echo ""

exec python3 "$SERVER_PY"
