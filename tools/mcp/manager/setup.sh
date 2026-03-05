#!/usr/bin/env bash
#
# Set up the ar-manager MCP server with Docker Compose and Tailscale Funnel.
#
# This script:
#   1. Creates the manager config directory
#   2. Generates a bearer token (if none exists)
#   3. Builds and starts the ar-manager container
#   4. Sets up Tailscale Funnel to expose it publicly
#
# Usage:
#   ./setup.sh                    # full setup
#   ./setup.sh --no-funnel        # skip Tailscale Funnel
#   ./setup.sh --token-only       # just generate a token and exit
#
# Prerequisites:
#   - Docker and docker compose
#   - Tailscale (for --funnel, which is the default)
#   - The controller and ar-memory containers should already be running
#     or will start as dependencies
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/tools/docker-compose.yml"
CONFIG_DIR="/Users/Shared/flowtree/manager"
TOKEN_FILE="${CONFIG_DIR}/manager-tokens.json"
MANAGER_PORT="${AR_MANAGER_PORT:-8010}"

SETUP_FUNNEL=true
TOKEN_ONLY=false

while [ $# -gt 0 ]; do
    case "$1" in
        --no-funnel)   SETUP_FUNNEL=false; shift ;;
        --token-only)  TOKEN_ONLY=true; shift ;;
        --help|-h)
            echo "Usage: $0 [--no-funnel] [--token-only]"
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── 1. Create config directory ────────────────────────────────────────

echo "==> Creating config directory: ${CONFIG_DIR}"
sudo mkdir -p "${CONFIG_DIR}"
sudo chown "$(whoami)" "${CONFIG_DIR}"
chmod 700 "${CONFIG_DIR}"

# ── 2. Generate bearer token ─────────────────────────────────────────

if [ -f "${TOKEN_FILE}" ]; then
    COUNT=$(python3 -c "import json; print(len(json.load(open('${TOKEN_FILE}')).get('tokens',[])))" 2>/dev/null || echo 0)
    echo "==> Token file exists with ${COUNT} token(s): ${TOKEN_FILE}"
    echo "    To add another: AR_MANAGER_TOKEN_FILE=${TOKEN_FILE} ${SCRIPT_DIR}/generate-token.sh \"label\""
else
    echo "==> Generating bearer token..."
    AR_MANAGER_TOKEN_FILE="${TOKEN_FILE}" "${SCRIPT_DIR}/generate-token.sh" "Claude mobile"
fi

if [ "$TOKEN_ONLY" = true ]; then
    echo "==> Done (--token-only)."
    exit 0
fi

# ── 3. Build and start the container ─────────────────────────────────

echo ""
echo "==> Building and starting ar-manager..."

# Pass through env vars docker-compose needs
export AR_MANAGER_PORT="${MANAGER_PORT}"

docker compose -f "${COMPOSE_FILE}" build ar-manager
docker compose -f "${COMPOSE_FILE}" up -d ar-manager

echo "==> Waiting for ar-manager to be healthy..."
for i in $(seq 1 15); do
    if curl -sf --connect-timeout 2 "http://localhost:${MANAGER_PORT}/mcp" > /dev/null 2>&1; then
        echo "==> ar-manager is up on port ${MANAGER_PORT}"
        break
    fi
    if [ "$i" -eq 15 ]; then
        echo "WARNING: ar-manager did not respond within 15s. Check logs:"
        echo "  docker compose -f ${COMPOSE_FILE} logs ar-manager"
    fi
    sleep 1
done

# ── 4. Set up Tailscale Funnel ───────────────────────────────────────

if [ "$SETUP_FUNNEL" = true ]; then
    echo ""
    echo "==> Setting up Tailscale Funnel on port ${MANAGER_PORT}..."

    if ! command -v tailscale >/dev/null 2>&1; then
        echo "ERROR: tailscale CLI not found. Install Tailscale or use --no-funnel." >&2
        exit 1
    fi

    # Enable funnel (idempotent — safe to re-run)
    tailscale funnel --bg "${MANAGER_PORT}"

    # Derive the public URL
    TS_DNS=$(tailscale status --self --json 2>/dev/null \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['Self']['DNSName'].rstrip('.'))" \
        2>/dev/null || echo "")

    echo ""
    if [ -n "$TS_DNS" ]; then
        echo "==> Funnel active. Public MCP endpoint:"
        echo ""
        echo "    https://${TS_DNS}/"
        echo ""
        echo "    Configure this URL in Claude mobile as a remote MCP server."
        echo "    Use the bearer token printed above for authentication."
    else
        echo "==> Funnel configured, but could not determine the public URL."
        echo "    Run 'tailscale funnel status' to see the URL."
    fi
else
    echo ""
    echo "==> Skipping Tailscale Funnel (--no-funnel)."
    echo "    ar-manager is available locally at http://localhost:${MANAGER_PORT}"
fi

echo ""
echo "==> Setup complete."
echo ""
echo "Useful commands:"
echo "  Logs:           docker compose -f ${COMPOSE_FILE} logs -f ar-manager"
echo "  Restart:        docker compose -f ${COMPOSE_FILE} restart ar-manager"
echo "  Add token:      AR_MANAGER_TOKEN_FILE=${TOKEN_FILE} ${SCRIPT_DIR}/generate-token.sh \"label\""
echo "  Funnel status:  tailscale funnel status"
