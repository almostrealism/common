#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

SECRETS_DIR="${SECRETS_DIR:-/Users/Shared/flowtree/secrets}"
# The agent pool's configuration file. It is gitignored, so a fresh checkout
# (a CI runner workspace, for instance) never contains it — an unattended
# caller must point FLOWTREE_AGENT_ENV at a copy that lives outside the
# working tree, conventionally under ${SECRETS_DIR}.
AGENT_ENV="${FLOWTREE_AGENT_ENV:-flowtree/runtime/agent/.env}"

# ── Usage ──────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage: flowtree/runtime/rebuild.sh [services...] [--agents] [--with-llm]

  No args        Rebuild all controller-stack containers
  --agents       Also rebuild and restart the agent pool
  --agents-only  Rebuild only the agent pool (skip controller stack)
  --cache        Allow Docker to reuse cached build layers (omit --no-cache).
                 Use this to sidestep transient apt/GPG signature errors from
                 ports.ubuntu.com when a previously cached layer is usable.
  --with-llm     Also start a local OpenAI-compatible LLM server (ollama)
                 and pull the default model. Used by the OpencodeRunner.
                 Override the model with LLM_MODEL (default: qwen3-coder:30b).
                 Override the host port with LLM_PORT (default: 11434).
  service names  Rebuild specific controller-stack services

Examples:
  flowtree/runtime/rebuild.sh                        # controller stack only
  flowtree/runtime/rebuild.sh --agents               # controller stack + agents
  flowtree/runtime/rebuild.sh --agents-only          # agents only
  flowtree/runtime/rebuild.sh --agents --with-llm    # full stack + agents + local LLM
  flowtree/runtime/rebuild.sh flowtree-controller    # just the controller service

Environment variables consulted by --agents / --agents-only:
  FLOWTREE_AGENT_ENV     path to the agent .env file
                         (default: flowtree/runtime/agent/.env). Set this when
                         running from a checkout that does not carry the file,
                         e.g. /Users/Shared/flowtree/secrets/agent.env.
  FLOWTREE_ROOT_HOST     controller hostname, when not supplied by that file
  CLAUDE_CODE_OAUTH_TOKEN
                         agent OAuth token, when not supplied by that file

Environment variables consulted by --with-llm:
  LLM_MODEL          ollama model identifier (default: qwen3-coder:30b)
  LLM_PORT           host port the ollama server listens on (default: 11434)
  OLLAMA_HOST_OVERRIDE   override the URL written into the agent .env
                         (default: http://host.docker.internal:${LLM_PORT}/v1)
EOF
  exit 0
}

# ── Parse flags ────────────────────────────────────────────────────

AGENTS=false
AGENTS_ONLY=false
WITH_LLM=false
USE_CACHE=false
SERVICES=()

for arg in "$@"; do
  case "${arg}" in
    --help|-h)      usage ;;
    --agents)       AGENTS=true ;;
    --agents-only)  AGENTS_ONLY=true ;;
    --with-llm|--llm) WITH_LLM=true ;;
    --cache)        USE_CACHE=true ;;
    *)              SERVICES+=("${arg}") ;;
  esac
done

# ── Agent configuration ────────────────────────────────────────────
#
# Loaded and validated BEFORE the Maven build so a pool that cannot be
# configured fails in seconds rather than after a full build.

load_agent_env() {
  if [ -f "${AGENT_ENV}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${AGENT_ENV}"
    set +a
  fi
}

# Prompting is only possible with a terminal attached. An unattended caller
# (the deploy workflow) has none, and a `read` against a closed stdin would
# otherwise fail with no indication of what is actually missing. Name the key
# and the file that should have supplied it instead.
require_agent_config() {
  echo "ERROR: $1 is required to rebuild the agent pool, and no terminal is"
  echo "  attached to prompt for it. Supply it in ${AGENT_ENV}, or export it"
  echo "  before invoking this script. $2"
  exit 1
}

if [ "${AGENTS}" = true ] || [ "${AGENTS_ONLY}" = true ]; then
  load_agent_env
  if [ ! -t 0 ]; then
    if [ -z "${FLOWTREE_ROOT_HOST:-}" ]; then
      require_agent_config FLOWTREE_ROOT_HOST \
        "Set FLOWTREE_AGENT_ENV to an agent .env kept outside the working tree."
    fi
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
      require_agent_config CLAUDE_CODE_OAUTH_TOKEN \
        "Generate one with: claude setup-token"
    fi
  fi
fi

# When --cache is set, drop --no-cache so Docker reuses existing build
# layers. This sidesteps transient "GPG error ... is not signed" failures
# from ports.ubuntu.com that occur when apt-get runs in a fresh layer.
if [ "${USE_CACHE}" = true ]; then
  NO_CACHE_FLAG=""
else
  NO_CACHE_FLAG="--no-cache"
fi

# ── Shared secret ──────────────────────────────────────────────────

if [ ! -f "$SECRETS_DIR/shared-secret" ]; then
  echo "Generating ar-manager shared secret..."
  mkdir -p "$SECRETS_DIR"
  openssl rand -base64 32 > "$SECRETS_DIR/shared-secret"
  chmod 600 "$SECRETS_DIR/shared-secret"
  echo "Shared secret written to $SECRETS_DIR/shared-secret"
fi

# ── Fleet monitoring: credentials, data directories, bind address ──
#
# fleet-db and fleet-grafana (see docker-compose.yml) take their passwords
# from files, never from an environment variable with an empty default, so
# the files have to exist before compose can start them. Generated once,
# like the shared secret above. The Postgres password is alphanumeric so it
# can be pasted into a URL without escaping.
#
# Skipped entirely on --agents-only: that mode never starts the controller
# stack (see the `AGENTS_ONLY` guard below), so a host with no tailnet
# address configured must still be able to rebuild the agent pool alone
# without this block's hard stop on a missing FLEET_BIND_ADDR.
#
# Also skipped when specific, non-fleet services were named: `rebuild.sh
# flowtree-controller` never touches fleet-db/fleet-grafana, so a host with
# no tailnet address must still be able to rebuild that unrelated service
# without this block's hard stop, and compose must not interpolate the
# fleet port variables for an invocation that never selects those services.

FLEET_SERVICES_SELECTED=false
if [ ${#SERVICES[@]} -eq 0 ] || printf '%s\n' "${SERVICES[@]}" | grep -qwE "fleet-db|fleet-grafana"; then
  FLEET_SERVICES_SELECTED=true
fi

FLEET_DB_DATA_DIR="${FLEET_DB_DATA_DIR:-/Users/Shared/flowtree/fleet-db}"
FLEET_GRAFANA_DATA_DIR="${FLEET_GRAFANA_DATA_DIR:-/Users/Shared/flowtree/grafana}"

# docker compose interpolates the whole file on EVERY invocation from the
# project directory — `exec`, `logs`, `ps`, the deploy workflow's own
# post-deploy checks — not only the one that starts fleet-db/fleet-grafana,
# and each of those needs the mandatory ${FLEET_BIND_ADDR:?} resolved. An
# exported variable lives only in this script's process, so the address is
# also written to the compose project's `.env` file (gitignored), which
# compose reads for interpolation automatically. The `:?` in the compose
# file still fails closed on a host where this script has never run.
COMPOSE_ENV_FILE="flowtree/runtime/controller/.env"

persist_fleet_bind_addr() {
  local tmp="${COMPOSE_ENV_FILE}.tmp"
  { [ -f "${COMPOSE_ENV_FILE}" ] && grep -v '^FLEET_BIND_ADDR=' "${COMPOSE_ENV_FILE}"; \
    echo "FLEET_BIND_ADDR=$1"; } > "${tmp}" || true
  mv "${tmp}" "${COMPOSE_ENV_FILE}"
}

persisted_fleet_bind_addr() {
  [ -f "${COMPOSE_ENV_FILE}" ] && sed -n 's/^FLEET_BIND_ADDR=//p' "${COMPOSE_ENV_FILE}" | tail -1
}

if [ "${AGENTS_ONLY}" = false ] && [ "${FLEET_SERVICES_SELECTED}" = true ]; then
  for secret_pair in "fleet-db-password:${FLEET_DB_DATA_DIR}" "grafana-admin-password:${FLEET_GRAFANA_DATA_DIR}"; do
    secret="${secret_pair%%:*}"
    data_dir="${secret_pair#*:}"
    # `-s`, not `-f`: a zero-byte file (e.g. from an interrupted write) must
    # be treated exactly like a missing one, not like a present-but-blank
    # password. Postgres's POSTGRES_PASSWORD_FILE rejects an empty file
    # outright, so falling through to the chmod-only path below would leave
    # the service unable to start instead of regenerating or failing closed.
    if [ ! -s "$SECRETS_DIR/$secret" ]; then
      # A non-empty data directory with no matching secret file means the
      # service already initialized with a password we no longer have:
      # Postgres and Grafana both bake the credential into their state at
      # first start, so minting a new one here would not match it and would
      # turn the next rebuild into an authentication outage. Fail closed
      # instead of silently generating a value that cannot possibly work.
      if [ -d "$data_dir" ] && [ -n "$(ls -A "$data_dir" 2>/dev/null)" ]; then
        echo "ERROR: $SECRETS_DIR/$secret is missing, but $data_dir already" >&2
        echo "  contains initialized state. Generating a new password here would not" >&2
        echo "  match the credential already baked into that service and would break" >&2
        echo "  authentication on the next start. Restore the original secret file, or" >&2
        echo "  rotate the credential explicitly (change it inside the running service" >&2
        echo "  first, then write the matching value to $SECRETS_DIR/$secret)." >&2
        exit 1
      fi
      echo "Generating $secret..."
      mkdir -p "$SECRETS_DIR"
      openssl rand -hex 24 > "$SECRETS_DIR/$secret"
      echo "Written to $SECRETS_DIR/$secret"
    fi
    # Enforced unconditionally, not only on the create branch above: a
    # secret left over from before this mode was enforced, or loosened by
    # some other process, must not silently stay group/world-readable.
    chmod 600 "$SECRETS_DIR/$secret"
  done
  mkdir -p "${FLEET_DB_DATA_DIR}" "${FLEET_GRAFANA_DATA_DIR}"

  # The fleet services publish only on the tailnet address. Detect it unless
  # the operator set FLEET_BIND_ADDR (127.0.0.1 is the value for a machine
  # without Tailscale, where nothing else should reach them anyway). No
  # address at all is a hard stop: the compose file refuses to start the
  # stack without one, and silently binding to every interface is exactly
  # what the fleet design forbids.
  if [ -z "${FLEET_BIND_ADDR:-}" ]; then
    if command -v tailscale >/dev/null 2>&1; then
      FLEET_BIND_ADDR="$(tailscale ip -4 2>/dev/null | head -1 || true)"
    fi
    if [ -z "${FLEET_BIND_ADDR:-}" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
      FLEET_BIND_ADDR="$(/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4 2>/dev/null | head -1 || true)"
    fi
    if [ -z "${FLEET_BIND_ADDR:-}" ]; then
      # Tailscale's CGNAT range, 100.64.0.0/10, is the only 100.x address a
      # host normally carries.
      FLEET_BIND_ADDR="$(ifconfig 2>/dev/null | awk '/inet 100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\./{print $2; exit}' || true)"
    fi
  fi
  if [ -z "${FLEET_BIND_ADDR:-}" ]; then
    echo "ERROR: no tailnet address found for the fleet services." >&2
    echo "  Set FLEET_BIND_ADDR to this host's Tailscale IPv4 address (or to 127.0.0.1 on a host" >&2
    echo "  without Tailscale) and run again. The fleet-db and fleet-grafana ports are never" >&2
    echo "  published on every interface." >&2
    exit 1
  fi
  # An operator override can undo the guarantee the detection above exists
  # to provide; reject a wildcard address explicitly rather than letting a
  # typo (or a deliberate but mistaken value) publish these ports everywhere.
  case "${FLEET_BIND_ADDR}" in
    0.0.0.0|::|"::0"|"[::]")
      echo "ERROR: FLEET_BIND_ADDR=${FLEET_BIND_ADDR} is a wildcard address." >&2
      echo "  fleet-db and fleet-grafana must bind to a specific address (this host's" >&2
      echo "  tailnet address, or 127.0.0.1 on a host without Tailscale), never to every" >&2
      echo "  interface. Set FLEET_BIND_ADDR to a non-wildcard value." >&2
      exit 1
      ;;
  esac
  export FLEET_BIND_ADDR
  persist_fleet_bind_addr "${FLEET_BIND_ADDR}"
  echo "Fleet services will bind to ${FLEET_BIND_ADDR}"
elif [ "${AGENTS_ONLY}" = false ]; then
  # docker compose interpolates every service definition in the file before
  # selecting which ones to build/start, even for a single named non-fleet
  # service, so the fleet ports' mandatory ${FLEET_BIND_ADDR:?} still needs a
  # value here even though fleet-db/fleet-grafana are not being touched.
  # The address a full rebuild persisted is the right one to reuse; only a
  # host that never ran a full rebuild falls back to loopback, and that value
  # is never used to publish anything in this branch.
  export FLEET_BIND_ADDR="${FLEET_BIND_ADDR:-$(persisted_fleet_bind_addr)}"
  export FLEET_BIND_ADDR="${FLEET_BIND_ADDR:-127.0.0.1}"
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
  mvn install -pl flowtree/runtime -am -DskipTests
  # `dependency:copy-dependencies` with `-pl <module>` resolves
  # -DoutputDirectory relative to the module's basedir, not the reactor cwd —
  # passing `flowtree/runtime/target/dependency` would create
  # `flowtree/runtime/flowtree/runtime/target/dependency/` and the
  # controller/agent Dockerfile COPY would fail with "no such file or
  # directory". Use an absolute path to pin it to the expected location.
  mvn dependency:copy-dependencies -pl flowtree/runtime \
      -DoutputDirectory="$(pwd)/flowtree/runtime/target/dependency"
fi

# ── Controller stack ───────────────────────────────────────────────
#
# `build --no-cache` followed by `up --force-recreate` is the only
# reliable way to get a fresh deploy. Plain `up -d --build` reuses
# cached COPY layers if Docker decides the source mtimes are
# unchanged, which can silently ship a stale JAR or stale bundled
# tool source. We hit exactly that bug shipping the ar-secrets
# Python source — the rebuild "succeeded" but the running image was
# the previous one. Always-fresh defaults are worth the few extra
# seconds of cold build per deploy.

if [ "${AGENTS_ONLY}" = false ]; then
  if [ ${#SERVICES[@]} -eq 0 ]; then
    echo "Rebuilding all controller-stack containers (cache=${USE_CACHE})..."
    docker compose -f flowtree/runtime/controller/docker-compose.yml build ${NO_CACHE_FLAG} --pull
    docker compose -f flowtree/runtime/controller/docker-compose.yml up -d --force-recreate
  else
    echo "Rebuilding (cache=${USE_CACHE}): ${SERVICES[*]}"
    docker compose -f flowtree/runtime/controller/docker-compose.yml build ${NO_CACHE_FLAG} --pull "${SERVICES[@]}"
    docker compose -f flowtree/runtime/controller/docker-compose.yml up -d --force-recreate "${SERVICES[@]}"
  fi
fi

# ── Local LLM server (opt-in via --with-llm) ───────────────────────
#
# Provisions an ollama daemon on the host and pulls a coding-capable
# model. The agent containers reach it via host.docker.internal on
# macOS / Windows Docker Desktop; on Linux you need to expose the host
# differently (e.g. add `--add-host=host.docker.internal:host-gateway`
# to the agent compose file or set OLLAMA_HOST_OVERRIDE to your LAN IP).
#
# All state lives under $HOME/.ollama (managed by ollama itself); this
# script never writes to system-owned paths.

LLM_MODEL="${LLM_MODEL:-qwen3-coder:30b}"
LLM_PORT="${LLM_PORT:-11434}"

if [ "${WITH_LLM}" = true ]; then
  echo "── LLM server (--with-llm) ─────────────────────────────────"

  if ! command -v ollama >/dev/null 2>&1; then
    echo "ERROR: ollama is not installed or not on PATH."
    echo "  Install from https://ollama.ai or pass --with-llm only on a"
    echo "  machine that already has it. For llama.cpp or other"
    echo "  OpenAI-compatible servers, omit --with-llm and set"
    echo "  OPENCODE_PROVIDER_URL directly in flowtree/runtime/agent/.env."
    exit 1
  fi

  # Probe whether something is already listening on the requested port.
  # Tolerant: curl exits non-zero if no listener, which is what we want.
  if curl -sS --max-time 2 "http://127.0.0.1:${LLM_PORT}/api/tags" >/dev/null 2>&1; then
    echo "ollama already responding on :${LLM_PORT} — leaving it running"
  else
    echo "Starting ollama serve on :${LLM_PORT}..."
    mkdir -p "${HOME}/.ollama"
    OLLAMA_HOST="127.0.0.1:${LLM_PORT}" nohup ollama serve \
        >"${HOME}/.ollama/server.log" 2>&1 &
    # Wait for the server to become reachable. Short polls; ollama
    # comes up in well under a second on a warm install.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      if curl -sS --max-time 2 "http://127.0.0.1:${LLM_PORT}/api/tags" \
          >/dev/null 2>&1; then
        break
      fi
      sleep 1
    done
    if ! curl -sS --max-time 2 "http://127.0.0.1:${LLM_PORT}/api/tags" \
        >/dev/null 2>&1; then
      echo "ERROR: ollama did not become reachable on :${LLM_PORT} within 10s."
      echo "  Check ${HOME}/.ollama/server.log for details."
      exit 1
    fi
    echo "ollama is up on :${LLM_PORT}"
  fi

  # Detect whether the requested model is already present. `ollama list`
  # prints tab-separated NAME ... lines; grep tolerates the colon form.
  if OLLAMA_HOST="127.0.0.1:${LLM_PORT}" ollama list 2>/dev/null \
      | awk 'NR>1 {print $1}' \
      | grep -Fxq "${LLM_MODEL}"; then
    echo "Model already present: ${LLM_MODEL}"
  else
    echo "Pulling model: ${LLM_MODEL} (this can take several minutes for first run)..."
    OLLAMA_HOST="127.0.0.1:${LLM_PORT}" ollama pull "${LLM_MODEL}"
  fi

  # Compute the URL that the *agent containers* should use to reach the
  # host's ollama. On Docker Desktop (macOS / Windows) host.docker.internal
  # is the well-known alias; on Linux it requires the
  # --add-host=host.docker.internal:host-gateway compose option, so we
  # let operators override the URL explicitly via OLLAMA_HOST_OVERRIDE.
  OLLAMA_AGENT_URL="${OLLAMA_HOST_OVERRIDE:-http://host.docker.internal:${LLM_PORT}/v1}"
  echo "Agent containers will use OPENCODE_PROVIDER_URL=${OLLAMA_AGENT_URL}"

  # Persist OPENCODE_* values into the agent .env so subsequent runs
  # without --with-llm still point at the same endpoint, and so the
  # `. .env` load in the agent block below sees the freshly chosen
  # URL even though the env block runs after this one. Existing keys
  # are replaced in place; other operator-set values are preserved.
  mkdir -p "$(dirname "${AGENT_ENV}")"
  touch "${AGENT_ENV}"
  set_env_var() {
    local key="$1" value="$2"
    if grep -q "^${key}=" "${AGENT_ENV}"; then
      # macOS sed needs -i ''; Linux sed needs -i without arg. Use a
      # rewrite-via-temp-file approach that works on both.
      local tmp
      tmp="$(mktemp)"
      awk -v k="${key}" -v v="${value}" '
        BEGIN { kp = k "=" }
        index($0, kp) == 1 { print kp v; next }
        { print }
      ' "${AGENT_ENV}" > "${tmp}"
      mv "${tmp}" "${AGENT_ENV}"
    else
      printf '%s=%s\n' "${key}" "${value}" >> "${AGENT_ENV}"
    fi
  }
  set_env_var OPENCODE_PROVIDER_URL "${OLLAMA_AGENT_URL}"
  set_env_var OPENCODE_API_KEY ""
  # We intentionally do NOT write OPENCODE_DEFAULT_MODEL. The OpencodeRunner
  # falls back to the literal alias "default", which works with llama.cpp
  # (it ignores the model field on the wire) but NOT with ollama, which
  # dispatches by name. Operators using the ollama path provisioned here
  # must add the matching key to ${AGENT_ENV} themselves, e.g.
  #     OPENCODE_DEFAULT_MODEL=${LLM_MODEL}
  # See flowtree/docs/operations/OPENCODE.md for context.
  echo ""
  echo "NOTE: ollama dispatches by model name, but OPENCODE_DEFAULT_MODEL was"
  echo "      NOT written automatically. Add the following line to ${AGENT_ENV}"
  echo "      so jobs without an explicit model field reach this ollama pull:"
  echo "          OPENCODE_DEFAULT_MODEL=${LLM_MODEL}"
fi

# ── Agent pool ─────────────────────────────────────────────────────

if [ "${AGENTS}" = true ] || [ "${AGENTS_ONLY}" = true ]; then
  # Reload the env file: --with-llm may have written OPENCODE_* keys into it
  # since the pre-build load.
  load_agent_env

  if [ -z "${FLOWTREE_ROOT_HOST:-}" ]; then
    if [ ! -t 0 ]; then
      require_agent_config FLOWTREE_ROOT_HOST \
        "Set FLOWTREE_AGENT_ENV to an agent .env kept outside the working tree."
    fi
    read -rp "Controller host: " FLOWTREE_ROOT_HOST
    if [ -z "${FLOWTREE_ROOT_HOST}" ]; then
      echo "ERROR: Controller host is required."
      exit 1
    fi
  else
    echo "Using controller host: ${FLOWTREE_ROOT_HOST}"
  fi

  if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
    if [ ! -t 0 ]; then
      require_agent_config CLAUDE_CODE_OAUTH_TOKEN \
        "Generate one with: claude setup-token"
    fi
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

  # Pre-create the per-agent transcript subdirectories on the host so the
  # bind mounts resolve. Each agent gets a DISTINCT subdir (never shared)
  # — see the volume policy in flowtree/runtime/agent/docker-compose.yml.
  AGENT_COMPOSE="flowtree/runtime/agent/docker-compose.yml"
  TRANSCRIPT_DIR_HOST="${TRANSCRIPT_DIR_HOST:-/Users/Shared/flowtree/agent-transcripts}"
  for sub in $(grep -oE '/agent-[A-Za-z0-9_-]+:/agent-transcripts:rw' "${AGENT_COMPOSE}" | cut -d: -f1); do
    mkdir -p "${TRANSCRIPT_DIR_HOST}${sub}"
  done

  echo "Rebuilding agent pool (cache=${USE_CACHE}); transcripts at ${TRANSCRIPT_DIR_HOST}/<agent>/..."
  docker compose -f "${AGENT_COMPOSE}" build ${NO_CACHE_FLAG} --pull
  # --remove-orphans clears containers from services no longer in the compose
  # file — notably the flowtree-agent-1/-2 containers left by the old `agent`
  # replica service, whose names the explicit agent-N services now reuse.
  docker compose -f "${AGENT_COMPOSE}" up -d --force-recreate --remove-orphans
fi

echo "Done."

# ── Public endpoint health check (non-fatal) ──────────────────────
#
# Verifies that the ar-manager MCP server is reachable over the PUBLIC
# internet at its Cloudflare Tunnel hostname. Purely informational —
# failures never block the script exit code.
#
# We deliberately resolve via a public DNS resolver (1.1.1.1) instead of
# the system resolver. An earlier outage went undiagnosed for hours because
# the old Tailscale Funnel ts.net name resolved to a private 100.x tailnet
# IP via MagicDNS on this host — so a local curl looked "UP" while the name
# was NXDOMAIN (and unreachable) for every external client (ChatGPT,
# claude.ai). Checking public DNS is the only way to catch that class of
# failure from the server box itself.
#
# A 401 is the HEALTHY response: the unauthenticated probe is correctly
# rejected by the bearer/OAuth layer, which proves the whole path —
# public DNS -> Cloudflare edge -> cloudflared tunnel -> ar-manager — works.

PUBLIC_HOST="${AR_MANAGER_PUBLIC_HOST:-mcp.almostrealism.ai}"

check_public_endpoint() {
  local host="${PUBLIC_HOST}"
  local url="https://${host}"

  # Require a PUBLIC DNS answer (not the system/MagicDNS resolver).
  local public_ip=""
  if command -v dig >/dev/null 2>&1; then
    public_ip="$(dig +short +time=3 @1.1.1.1 "${host}" A 2>/dev/null \
      | grep -E '^[0-9]' | head -1 || true)"
  else
    echo "Public endpoint check: SKIP — dig not found (cannot verify public DNS)"
    return 0
  fi

  if [ -z "${public_ip}" ]; then
    echo "Public endpoint check: DOWN — ${host} has no public DNS record (@1.1.1.1)"
    echo "  External clients (ChatGPT, claude.ai) cannot reach it."
    echo "  Check the Cloudflare DNS record and that the tunnel is running:"
    echo "    cloudflared tunnel info ar-manager"
    echo "    launchctl print system/com.cloudflare.cloudflared | grep -iE 'state|last exit'"
    return 0
  fi

  # Test the public edge, pinned to the publicly-resolved IP.
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' \
    --resolve "${host}:443:${public_ip}" \
    --connect-timeout 5 --max-time 10 \
    "${url}/" 2>/dev/null || echo "000")"

  if [ "${code}" = "000" ]; then
    echo "Public endpoint check: DOWN — ${url} (${public_ip}) did not respond"
    echo "  Public DNS resolves but the edge/tunnel is not serving. Check:"
    echo "    cloudflared tunnel info ar-manager   (expect an active connector)"
  elif [ "${code}" = "401" ]; then
    echo "Public endpoint check: UP — ${url} responded HTTP 401 (auth required, as expected)"
  else
    echo "Public endpoint check: UP — ${url} responded HTTP ${code}"
  fi
}

check_public_endpoint || true
