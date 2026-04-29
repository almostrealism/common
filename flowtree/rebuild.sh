#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SECRETS_DIR="/Users/Shared/flowtree/secrets"
AGENT_ENV="flowtree/agent/.env"

# ── Usage ──────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage: flowtree/rebuild.sh [services...] [--agents]

  No args        Rebuild all controller-stack containers
  --agents       Also rebuild and restart the agent pool
  --agents-only  Rebuild only the agent pool (skip controller stack)
  service names  Rebuild specific controller-stack services

Examples:
  flowtree/rebuild.sh                        # controller stack only
  flowtree/rebuild.sh --agents               # controller stack + agents
  flowtree/rebuild.sh --agents-only          # agents only
  flowtree/rebuild.sh flowtree-controller    # just the controller service
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
elif [ ${#SERVICES[@]} -eq 0 ] || printf '%s\n' "${SERVICES[@]}" | grep -qwE "flowtree-controller|ar-manager"; then
  NEEDS_BUILD=true
fi

if [ "${NEEDS_BUILD}" = true ]; then
  echo "Building flowtree module..."
  # Use `install` (not `package`) so each upstream module's freshly built jar
  # lands in ~/.m2 before `dependency:copy-dependencies` pulls from there.
  # `package` leaves the jars in each module's target/ dir but does not install
  # them, so the dependency copy would pick up stale cached jars and the agent
  # image would run with outdated transitive code.
  mvn install -pl flowtree -am -DskipTests
  mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
fi

# ── Controller stack ───────────────────────────────────────────────

if [ "${AGENTS_ONLY}" = false ]; then
  if [ ${#SERVICES[@]} -eq 0 ]; then
    echo "Rebuilding all controller-stack containers..."
    docker compose -f flowtree/controller/docker-compose.yml up -d --build
  else
    echo "Rebuilding: ${SERVICES[*]}"
    docker compose -f flowtree/controller/docker-compose.yml up -d --build "${SERVICES[@]}"
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

# ── Funnel health check (non-fatal) ───────────────────────────────
#
# Verifies that Tailscale Funnel is configured and that the public edge
# completes a TLS handshake for the exposed ar-manager hostname. This is
# purely informational — failures never block the script exit code. Seen
# failure modes: Funnel config lost across daemon restart ("No serve
# config"), and TLS handshake dropping at the public edge when the local
# tailscaled is too outdated for the current edge protocol.

check_funnel() {
  # Prefer the Tailscale.app binary on macOS — a /usr/local/bin/tailscale
  # shim sometimes exists but crashes with a Swift fatal error outside the
  # app's sandbox. Fall back to PATH on other platforms.
  local ts_bin=""
  if [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
    ts_bin="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
  elif command -v tailscale >/dev/null 2>&1; then
    ts_bin="tailscale"
  else
    echo "Funnel check: SKIP — tailscale CLI not found"
    return 0
  fi

  local status_output
  status_output="$("${ts_bin}" funnel status 2>&1 || true)"

  if echo "${status_output}" | grep -q "No serve config"; then
    echo "Funnel check: DOWN — no serve config"
    echo "  Controller stack is not publicly reachable."
    echo "  To restore:  ${ts_bin} funnel --bg http://127.0.0.1:8010"
    return 0
  fi

  local host url
  url="$(echo "${status_output}" | grep -oE 'https://[^[:space:]/]+\.ts\.net' | head -1 || true)"
  if [ -z "${url}" ]; then
    echo "Funnel check: UNKNOWN — could not parse funnel status output"
    return 0
  fi
  host="${url#https://}"

  # Resolve via public DNS so we test the public edge, not MagicDNS.
  local public_ip=""
  if command -v dig >/dev/null 2>&1; then
    public_ip="$(dig +short +time=3 @1.1.1.1 "${host}" 2>/dev/null | head -1 || true)"
  fi

  local code
  if [ -n "${public_ip}" ]; then
    code="$(curl -sS -o /dev/null -w '%{http_code}' \
      --resolve "${host}:443:${public_ip}" \
      --connect-timeout 5 --max-time 10 \
      "${url}/" 2>/dev/null || echo "000")"
  else
    # Fall back to the default path (may use MagicDNS inside the tailnet).
    code="$(curl -sS -o /dev/null -w '%{http_code}' \
      --connect-timeout 5 --max-time 10 \
      "${url}/" 2>/dev/null || echo "000")"
  fi

  if [ "${code}" = "000" ]; then
    echo "Funnel check: DOWN — ${url} did not respond at the public edge"
    echo "  Consider: updating the Tailscale app, or toggling funnel off/on."
  else
    echo "Funnel check: UP — ${url} responded with HTTP ${code}"
  fi
}

check_funnel || true
