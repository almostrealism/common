#!/usr/bin/env bash
#
# Build, install and (re)start a native FlowTree agent on macOS.
#
# The Docker agent pool (../docker-compose.yml, driven by rebuild.sh) runs on
# Linux containers and therefore has no Metal. This is the third kind of
# agent: a plain JVM under launchd, running as the account this script is
# invoked by, with the host's GPU available to every job it executes. The
# deploy workflow runs it on a runner registered as that account, so a
# rebuild of the agent pool replaces this agent as well as the containers.
#
# What it does, in order:
#   1. Refuses to start without the env file the service will need — the
#      same "fail in seconds, not after a build" rule rebuild.sh follows.
#   2. Builds the flowtree JARs (mvn install + dependency:copy-dependencies,
#      the same two commands rebuild.sh runs for the container images).
#   3. Stages them into a fresh lib directory under FLOWTREE_AGENT_HOME and
#      swaps it in. The running agent keeps the old JARs open until it is
#      restarted; they are never overwritten underneath it.
#   4. Renders the launchd plist into ~/Library/LaunchAgents and (re)loads
#      the service, so launchd owns the process from here on: it survives
#      the end of the CI job that installed it, and is restarted on exit.
#   5. Waits for the agent to open its connection to the controller and
#      fails if it does not — a service that launchd reports as running but
#      that never connects is not a deployed agent.
#
# Usage:
#   flowtree/runtime/agent/macos/install.sh [--no-build]
#
#   --no-build   Skip the Maven build and install whatever is in
#                flowtree/runtime/target already. For iterating on the
#                service definition; the deploy workflow never passes it.
#
# Environment:
#   FLOWTREE_AGENT_HOME   install directory (default: ${HOME}/flowtree-agent).
#                         Holds lib/, conf/, bin/, logs/ and the workspace.
#   FLOWTREE_AGENT_ENV    env file the service loads
#                         (default: ${FLOWTREE_AGENT_HOME}/agent.env).
#                         Must exist and define FLOWTREE_ROOT_HOST and a
#                         credential; see agent.env.example.
#   CONNECT_TIMEOUT_SECONDS
#                         how long to wait for the controller connection
#                         (default: 180). The agent's first attempt is ~30s
#                         after start, so anything under 60 is too short.
#
# Exit codes:
#   0 - agent installed, running, and connected to the controller
#   1 - a precondition failed, the build failed, or the agent did not connect

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROJECT_ROOT="$(cd "${MODULE_DIR}/../.." && pwd)"

LABEL="com.almostrealism.flowtree-agent"
AGENT_HOME="${FLOWTREE_AGENT_HOME:-${HOME}/flowtree-agent}"
ENV_FILE="${FLOWTREE_AGENT_ENV:-${AGENT_HOME}/agent.env}"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
CONNECT_TIMEOUT_SECONDS="${CONNECT_TIMEOUT_SECONDS:-180}"

BUILD=true
for arg in "$@"; do
    case "${arg}" in
        --no-build) BUILD=false ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument '${arg}'" >&2
            exit 1
            ;;
    esac
done

if [ "$(uname -s)" != "Darwin" ]; then
    echo "ERROR: this installer manages a launchd service and only runs on macOS." >&2
    exit 1
fi

# ── Preconditions ──────────────────────────────────────────────────
#
# Checked before the build so a misconfigured host fails immediately. The
# env file is the operator's, kept outside the checkout because it holds a
# credential; this script never creates or edits it.

if [ ! -f "${ENV_FILE}" ]; then
    echo "ERROR: agent env file not found at ${ENV_FILE}" >&2
    echo "  Create it from ${SCRIPT_DIR}/agent.env.example, or point" >&2
    echo "  FLOWTREE_AGENT_ENV at an existing one." >&2
    exit 1
fi

# Read only the keys the checks below need. The file is sourced in a
# subshell so nothing in it leaks into this script's environment — the
# service gets it from launchd via FLOWTREE_AGENT_ENV, not from here.
read_env_key() {
    ( set +u; set -a; . "${ENV_FILE}"; set +a; printf '%s' "${!1:-}" )
}

ROOT_HOST="$(read_env_key FLOWTREE_ROOT_HOST)"
ROOT_PORT="$(read_env_key FLOWTREE_ROOT_PORT)"
ROOT_PORT="${ROOT_PORT:-7766}"

if [ -z "${ROOT_HOST}" ]; then
    echo "ERROR: FLOWTREE_ROOT_HOST is not set in ${ENV_FILE}." >&2
    exit 1
fi
if [ -z "$(read_env_key CLAUDE_CODE_OAUTH_TOKEN)" ] && [ -z "$(read_env_key ANTHROPIC_API_KEY)" ]; then
    echo "ERROR: neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY is set in ${ENV_FILE}." >&2
    echo "  Generate a token with: claude setup-token" >&2
    exit 1
fi

for cmd in java mvn launchctl lsof; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not on PATH for $(id -un)." >&2
        exit 1
    fi
done

echo "Installing native FlowTree agent"
echo "  Account:     $(id -un)"
echo "  Install dir: ${AGENT_HOME}"
echo "  Env file:    ${ENV_FILE}"
echo "  Controller:  ${ROOT_HOST}:${ROOT_PORT}"
echo ""

# ── Build ──────────────────────────────────────────────────────────
#
# Identical to the build rebuild.sh performs for the container images, and
# for the same reasons: `install` so upstream modules land in ~/.m2 before
# copy-dependencies reads them, and an absolute outputDirectory because the
# plugin resolves a relative one against the module, not the reactor.

if [ "${BUILD}" = true ]; then
    echo "Building flowtree module..."
    (
        cd "${PROJECT_ROOT}"
        mvn install -pl flowtree/runtime -am -DskipTests
        mvn dependency:copy-dependencies -pl flowtree/runtime \
            -DoutputDirectory="${PROJECT_ROOT}/flowtree/runtime/target/dependency"
    )
fi

RUNTIME_JAR="$(ls "${MODULE_DIR}"/target/ar-flowtree-runtime-*.jar 2>/dev/null | grep -v -E -- '-(sources|javadoc|tests)\.jar$' | head -1 || true)"
if [ -z "${RUNTIME_JAR}" ] || [ ! -d "${MODULE_DIR}/target/dependency" ]; then
    echo "ERROR: no built runtime JAR under ${MODULE_DIR}/target — run without --no-build." >&2
    exit 1
fi

# ── Stage and swap ─────────────────────────────────────────────────
#
# A new lib directory is assembled in full and moved into place in one
# step, so the service never sees a half-copied classpath, and the JARs a
# running JVM has open are moved aside rather than truncated under it.

mkdir -p "${AGENT_HOME}/bin" "${AGENT_HOME}/conf" "${AGENT_HOME}/logs"

STAGING="${AGENT_HOME}/lib.staging"
rm -rf "${STAGING}"
mkdir -p "${STAGING}"
cp "${RUNTIME_JAR}" "${STAGING}/"
cp "${MODULE_DIR}"/target/dependency/*.jar "${STAGING}/"

rm -rf "${AGENT_HOME}/lib.previous"
if [ -d "${AGENT_HOME}/lib" ]; then
    mv "${AGENT_HOME}/lib" "${AGENT_HOME}/lib.previous"
fi
mv "${STAGING}" "${AGENT_HOME}/lib"

cp "${MODULE_DIR}/conf/agent.properties" "${AGENT_HOME}/conf/agent.properties"
cp "${SCRIPT_DIR}/run.sh" "${AGENT_HOME}/bin/run.sh"
chmod +x "${AGENT_HOME}/bin/run.sh"

echo "Installed $(ls "${AGENT_HOME}/lib" | wc -l | tr -d ' ') JARs to ${AGENT_HOME}/lib"

# ── launchd service ────────────────────────────────────────────────
#
# The plist is re-rendered on every install so a changed AGENT_HOME or env
# file path takes effect, and the service is unloaded and loaded again
# rather than kickstarted, because kickstart re-runs the plist launchd
# already holds, not the one on disk.
#
# The gui domain is the one a LaunchAgent normally lives in, but it exists
# only while the account has a window-server session. A runner started over
# SSH (or as a LaunchDaemon) has none, and there the per-user domain is the
# one that accepts a bootstrap.

mkdir -p "$(dirname "${PLIST}")"
sed -e "s|@AGENT_HOME@|${AGENT_HOME}|g" \
    -e "s|@ENV_FILE@|${ENV_FILE}|g" \
    "${SCRIPT_DIR}/${LABEL}.plist" > "${PLIST}"

UID_NUM="$(id -u)"
if launchctl print "gui/${UID_NUM}" >/dev/null 2>&1; then
    DOMAIN="gui/${UID_NUM}"
else
    DOMAIN="user/${UID_NUM}"
fi
SERVICE="${DOMAIN}/${LABEL}"

if launchctl print "${SERVICE}" >/dev/null 2>&1; then
    echo "Stopping the running agent (${SERVICE})..."
    launchctl bootout "${SERVICE}" || true
    # bootout returns before the process is gone; wait for it so the new
    # service does not race the old one for the controller connection.
    for _ in $(seq 1 30); do
        if ! launchctl print "${SERVICE}" >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
fi

echo "Starting the agent (${SERVICE})..."
launchctl bootstrap "${DOMAIN}" "${PLIST}"

# ── Verify ─────────────────────────────────────────────────────────
#
# There is no controller endpoint that lists connected agents, so the
# check is made from this side: the service must have a live process, and
# that process must hold an established TCP connection to the controller
# port. The Server's reconnect loop makes its first attempt about thirty
# seconds after start, hence the generous default timeout.

service_pid() {
    launchctl print "${SERVICE}" 2>/dev/null | awk '/^[[:space:]]*pid = /{print $3; exit}'
}

echo "Waiting up to ${CONNECT_TIMEOUT_SECONDS}s for the agent to connect to ${ROOT_HOST}:${ROOT_PORT}..."
DEADLINE=$(( $(date +%s) + CONNECT_TIMEOUT_SECONDS ))
while [ "$(date +%s)" -lt "${DEADLINE}" ]; do
    PID="$(service_pid)"
    if [ -n "${PID}" ] \
       && lsof -nP -a -p "${PID}" -iTCP -sTCP:ESTABLISHED 2>/dev/null | grep -q ":${ROOT_PORT}"; then
        echo "Agent is running (pid ${PID}) and connected to the controller."
        echo "  Logs: ${AGENT_HOME}/logs/agent.log"
        exit 0
    fi
    sleep 5
done

echo "ERROR: the agent did not connect to ${ROOT_HOST}:${ROOT_PORT} within ${CONNECT_TIMEOUT_SECONDS}s." >&2
echo "  Service: $(launchctl print "${SERVICE}" 2>/dev/null | awk '/state = |pid = |last exit/' | tr -s ' ' | tr '\n' ';')" >&2
echo "  Last log lines from ${AGENT_HOME}/logs/agent.log:" >&2
tail -n 40 "${AGENT_HOME}/logs/agent.log" 2>/dev/null >&2 || true
exit 1
