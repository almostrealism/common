#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SECRETS_DIR="/Users/Shared/flowtree/secrets"
AGENT_ENV="flowtree/agent/.env"

# ── Usage ──────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage: tools/rebuild.sh [services...] [--agents]

  No args        Rebuild all controller-stack containers
  --agents       Also rebuild and restart the agent pool
  --agents-only  Rebuild only the agent pool (skip controller stack)
  service names  Rebuild specific controller-stack services

Examples:
  tools/rebuild.sh                  # controller stack only
  tools/rebuild.sh --agents         # controller stack + agents
  tools/rebuild.sh --agents-only    # agents only
  tools/rebuild.sh controller       # just the controller service
EOF
  exit 0
}

# ── Parse flags ────────────────────────────────────────────────────

AGENTS=false
AGENTS_ONLY=false
SERVICES=()

for arg in "$@"; do
  case "${arg}" in
    --help|-h)      usage ;;
    --agents)       AGENTS=true ;;
    --agents-only)  AGENTS_ONLY=true ;;
    *)              SERVICES+=("${arg}") ;;
  esac
done

# ── Shared secret ──────────────────────────────────────────────────

if [ ! -f "$SECRETS_DIR/shared-secret" ]; then
  echo "Generating ar-manager shared secret..."
  mkdir -p "$SECRETS_DIR"
  openssl rand -base64 32 > "$SECRETS_DIR/shared-secret"
  chmod 600 "$SECRETS_DIR/shared-secret"
  echo "Shared secret written to $SECRETS_DIR/shared-secret"
fi

# ── Maven build (needed by both controller and agent images) ───────

NEEDS_BUILD=false

if [ "${AGENTS_ONLY}" = true ] || [ "${AGENTS}" = true ]; then
  NEEDS_BUILD=true
elif [ ${#SERVICES[@]} -eq 0 ] || printf '%s\n' "${SERVICES[@]}" | grep -qwE "controller|ar-manager"; then
  NEEDS_BUILD=true
fi

if [ "${NEEDS_BUILD}" = true ]; then
  echo "Building flowtree module..."
  mvn package -pl flowtree -am -DskipTests
  mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
fi

# ── Controller stack ───────────────────────────────────────────────

if [ "${AGENTS_ONLY}" = false ]; then
  if [ ${#SERVICES[@]} -eq 0 ]; then
    echo "Rebuilding all controller-stack containers..."
    docker compose -f tools/docker-compose.yml up -d --build
  else
    echo "Rebuilding: ${SERVICES[*]}"
    docker compose -f tools/docker-compose.yml up -d --build "${SERVICES[@]}"
  fi
fi

# ── Agent pool ─────────────────────────────────────────────────────

if [ "${AGENTS}" = true ] || [ "${AGENTS_ONLY}" = true ]; then
  # Load existing .env if present
  if [ -f "${AGENT_ENV}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${AGENT_ENV}"
    set +a
  fi

  # Validate required config — skip prompts if already set
  if [ -z "${FLOWTREE_ROOT_HOST:-}" ]; then
    read -rp "Controller host: " FLOWTREE_ROOT_HOST
    if [ -z "${FLOWTREE_ROOT_HOST}" ]; then
      echo "ERROR: Controller host is required."
      exit 1
    fi
  else
    echo "Using controller host: ${FLOWTREE_ROOT_HOST}"
  fi

  if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
    echo ""
    echo "A Claude Code OAuth token is required."
    echo "Generate one by running:  claude setup-token"
    echo ""
    read -rp "Claude Code OAuth token: " CLAUDE_CODE_OAUTH_TOKEN
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN}" ]; then
      echo "ERROR: Claude Code OAuth token is required."
      exit 1
    fi
  else
    echo "Using existing OAuth token"
  fi

  export FLOWTREE_ROOT_HOST CLAUDE_CODE_OAUTH_TOKEN

  AGENT_COUNT="${AGENT_COUNT:-2}"
  echo "Rebuilding ${AGENT_COUNT} agent container(s)..."
  docker compose -f flowtree/agent/docker-compose.yml up -d --build
fi

echo "Done."
