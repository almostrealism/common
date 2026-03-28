#!/usr/bin/env bash
#
# Start a pool of FlowTree agent containers.
#
# This script:
#   1. Builds the flowtree module (if needed)
#   2. Copies dependency JARs for Docker packaging
#   3. Prompts for controller host and Claude Code auth token
#   4. Launches the agent pool via docker compose
#
# Usage:
#   ./start.sh              # interactive prompts
#   ./start.sh --rebuild    # force Maven rebuild before starting
#   ./start.sh --stop       # stop running agents
#   ./start.sh --status     # show agent container status

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"

# ── Helpers ─────────────────────────────────────────────────────────

log()  { echo "==> $*"; }
warn() { echo "WARNING: $*" >&2; }

load_env() {
    if [ -f "${ENV_FILE}" ]; then
        # Source .env but don't override existing exports
        set -a
        # shellcheck disable=SC1090
        . "${ENV_FILE}"
        set +a
    fi
}

save_env() {
    local key="$1" value="$2"

    if [ -f "${ENV_FILE}" ]; then
        # Update existing key or append
        if grep -q "^${key}=" "${ENV_FILE}" 2>/dev/null; then
            # Use a temp file for portable sed -i
            local tmp="${ENV_FILE}.tmp"
            sed "s|^${key}=.*|${key}=${value}|" "${ENV_FILE}" > "${tmp}"
            mv "${tmp}" "${ENV_FILE}"
        else
            echo "${key}=${value}" >> "${ENV_FILE}"
        fi
    else
        # Create .env from example, then set the value
        if [ -f "${SCRIPT_DIR}/.env.example" ]; then
            cp "${SCRIPT_DIR}/.env.example" "${ENV_FILE}"
            local tmp="${ENV_FILE}.tmp"
            sed "s|^${key}=.*|${key}=${value}|" "${ENV_FILE}" > "${tmp}"
            mv "${tmp}" "${ENV_FILE}"
        else
            echo "${key}=${value}" > "${ENV_FILE}"
        fi
    fi
}

# ── Command handlers ────────────────────────────────────────────────

do_stop() {
    log "Stopping agents..."
    docker compose -f "${COMPOSE_FILE}" down
    exit 0
}

do_status() {
    docker compose -f "${COMPOSE_FILE}" ps
    exit 0
}

# ── Parse flags ─────────────────────────────────────────────────────

FORCE_REBUILD=false

for arg in "$@"; do
    case "${arg}" in
        --stop)    do_stop ;;
        --status)  do_status ;;
        --rebuild) FORCE_REBUILD=true ;;
        *)         echo "Unknown option: ${arg}"; exit 1 ;;
    esac
done

# ── Step 1: Build ───────────────────────────────────────────────────

FLOWTREE_JAR="${MODULE_DIR}/target/ar-flowtree-"*.jar
DEPS_DIR="${MODULE_DIR}/target/dependency"

if [ "${FORCE_REBUILD}" = true ] || ! ls ${FLOWTREE_JAR} >/dev/null 2>&1; then
    log "Building flowtree module..."
    mvn -f "${PROJECT_ROOT}/pom.xml" package -pl flowtree -am -DskipTests -q
fi

if [ "${FORCE_REBUILD}" = true ] || [ ! -d "${DEPS_DIR}" ] || [ -z "$(ls -A "${DEPS_DIR}" 2>/dev/null)" ]; then
    log "Copying dependency JARs..."
    mvn -f "${PROJECT_ROOT}/pom.xml" dependency:copy-dependencies \
        -pl flowtree -DoutputDirectory=target/dependency -q
fi

# ── Step 2: Load existing config ────────────────────────────────────

load_env

# ── Step 3: Prompt for controller host ──────────────────────────────

CURRENT_HOST="${FLOWTREE_ROOT_HOST:-}"
if [ -n "${CURRENT_HOST}" ]; then
    read -rp "Controller host [${CURRENT_HOST}]: " INPUT_HOST
else
    read -rp "Controller host: " INPUT_HOST
fi

if [ -n "${INPUT_HOST}" ]; then
    export FLOWTREE_ROOT_HOST="${INPUT_HOST}"
    save_env "FLOWTREE_ROOT_HOST" "${INPUT_HOST}"
elif [ -z "${CURRENT_HOST}" ]; then
    echo "ERROR: Controller host is required."
    exit 1
fi

# ── Step 4: Prompt for auth token ───────────────────────────────────

CURRENT_TOKEN="${CLAUDE_CODE_OAUTH_TOKEN:-}"
if [ -n "${CURRENT_TOKEN}" ]; then
    # Mask the token for display
    MASKED="${CURRENT_TOKEN:0:8}...${CURRENT_TOKEN: -4}"
    read -rp "Claude Code OAuth token [${MASKED}]: " INPUT_TOKEN
else
    echo ""
    echo "A Claude Code OAuth token is required."
    echo "Generate one by running:  claude setup-token"
    echo ""
    read -rp "Claude Code OAuth token: " INPUT_TOKEN
fi

if [ -n "${INPUT_TOKEN}" ]; then
    export CLAUDE_CODE_OAUTH_TOKEN="${INPUT_TOKEN}"
    save_env "CLAUDE_CODE_OAUTH_TOKEN" "${INPUT_TOKEN}"
elif [ -z "${CURRENT_TOKEN}" ]; then
    echo "ERROR: Claude Code OAuth token is required."
    echo "  Run 'claude setup-token' to generate one."
    exit 1
fi

# ── Step 5: Launch ──────────────────────────────────────────────────

AGENT_COUNT="${AGENT_COUNT:-2}"
log "Starting ${AGENT_COUNT} agent container(s)..."

docker compose -f "${COMPOSE_FILE}" up -d --build

log "Agents started. Use './start.sh --status' to check or './start.sh --stop' to shut down."
