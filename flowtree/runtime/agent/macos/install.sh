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
#   4. Renders the launchd plist into FLOWTREE_AGENT_HOME/conf and checks
#      that the copy an administrator loaded into the system domain
#      (/Library/LaunchDaemons) matches it. The service is a LaunchDaemon
#      that runs as this account, registered ONCE by root; this script never
#      needs launchd privileges and never has them. When the daemon is not
#      registered, or the registered definition is stale, the script fails
#      and prints the one command the administrator has to run —
#      register-daemon.sh, from a checkout the administrator owns, which
#      validates the plist before root acts on it (see that script's header
#      for the trust boundary).
#   5. Restarts the agent by signalling the running process. launchd's
#      KeepAlive starts it again on the new classpath, so the process is
#      owned by launchd from boot onward: it survives the end of the CI job
#      that installed it, a crash, and a reboot, with nobody logged in.
#   6. Waits for the new process to open its connection to the controller
#      and fails if it does not — a service that launchd reports as running
#      but that never connects is not a deployed agent.
#
# Why a LaunchDaemon and not a LaunchAgent in the account's own domain: a
# per-user domain (gui/<uid> or user/<uid>) exists only while the account has
# a login session, and on a host where the account is only ever reached with
# `su` from another user's terminal that domain refuses every bootstrap —
# launchd answers "Bootstrap failed: 5: Input/output error" to the account,
# to root, and to root via `launchctl asuser`. The system domain accepts a
# bootstrap from root regardless of session, is present from boot, and its
# services can be inspected (`launchctl print system/<label>`) and, since
# they run as this account, signalled by this account without root.
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
#   1 - a precondition failed, the build failed, the daemon is not
#       registered (or registered with a stale definition), or the agent
#       did not connect

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROJECT_ROOT="$(cd "${MODULE_DIR}/../.." && pwd)"

LABEL="com.almostrealism.flowtree-agent"
AGENT_HOME="${FLOWTREE_AGENT_HOME:-${HOME}/flowtree-agent}"
ENV_FILE="${FLOWTREE_AGENT_ENV:-${AGENT_HOME}/agent.env}"
# The definition this script renders, and the copy of it launchd actually
# holds. Only root can write the second; this script only compares them.
PLIST="${AGENT_HOME}/conf/${LABEL}.plist"
DAEMON_PLIST="/Library/LaunchDaemons/${LABEL}.plist"
SERVICE="system/${LABEL}"
# The per-user LaunchAgent earlier versions of this script installed. Still
# looked for, so a host that moves to the daemon does not end up with two
# agents carrying the same node identity.
LEGACY_PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
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

for cmd in java launchctl lsof; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not on PATH for $(id -un)." >&2
        exit 1
    fi
done
if [ "${BUILD}" = true ] && ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: mvn is not on PATH for $(id -un)." >&2
    exit 1
fi

daemon_registered() {
    launchctl print "${SERVICE}" >/dev/null 2>&1
}

echo "Installing native FlowTree agent"
echo "  Account:     $(id -un)"
echo "  Install dir: ${AGENT_HOME}"
echo "  Env file:    ${ENV_FILE}"
echo "  Controller:  ${ROOT_HOST}:${ROOT_PORT}"
echo "  Service:     ${SERVICE} ($(daemon_registered && echo registered || echo 'NOT registered — this run stages the files, then stops with the one-time registration steps'))"
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

# ── Build provenance ───────────────────────────────────────────────
#
# Written into the staging directory so it is swapped into place by the
# same mv as the JARs it describes. Written afterwards, it could survive a
# failed swap and name a build that is not the one installed.
#
# What it proves and what it does not: it records the commit this checkout
# was on and whether the checkout was clean when the JARs were built. It
# does NOT prove the JARs were compiled from that commit — only a
# reproducible build proves that, and this is not one. It is enough to
# catch the realistic failure, a deploy from a stale or dirty tree, and it
# must not be read as more than that.
#
# `built` records whether this run compiled anything. Under --no-build the
# JARs may predate the checked-out commit entirely, so a consumer that
# treats the SHA as describing the binary has to know to distrust it.
#
# Living in lib/ alongside the JARs is what buys the atomic swap, and it is
# inert there: run.sh passes `-cp "${AGENT_HOME}/lib/*"` unquoted to the JVM,
# whose classpath wildcard expands to .jar files only.
PROVENANCE_CLEAN=true
if [ -n "$(git -C "${PROJECT_ROOT}" status --porcelain 2>/dev/null)" ]; then
    PROVENANCE_CLEAN=false
fi
{
    echo "sha=$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "branch=$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
    echo "clean=${PROVENANCE_CLEAN}"
    echo "built=${BUILD}"
    echo "installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "installed_by=$(id -un)"
    echo "host=$(hostname)"
} > "${STAGING}/BUILD_PROVENANCE"

rm -rf "${AGENT_HOME}/lib.previous"
if [ -d "${AGENT_HOME}/lib" ]; then
    mv "${AGENT_HOME}/lib" "${AGENT_HOME}/lib.previous"
fi
mv "${STAGING}" "${AGENT_HOME}/lib"

cp "${MODULE_DIR}/conf/agent.properties" "${AGENT_HOME}/conf/agent.properties"
cp "${SCRIPT_DIR}/run.sh" "${AGENT_HOME}/bin/run.sh"
chmod +x "${AGENT_HOME}/bin/run.sh"

echo "Installed $(ls "${AGENT_HOME}"/lib/*.jar 2>/dev/null | wc -l | tr -d ' ') JARs to ${AGENT_HOME}/lib"


# ── Service definition ─────────────────────────────────────────────
#
# The plist is re-rendered on every install so a changed AGENT_HOME, env
# file path or account is visible, but launchd holds its own copy under
# /Library/LaunchDaemons and only root can replace that. So the rendered
# file is compared with the registered one, and a difference stops the
# install: a redeploy must not report success against a definition that no
# longer describes what was installed.

# The values land inside XML text, by way of a sed replacement, and are
# configurable paths: each must be escaped for both. XML first (& < >), then
# the characters sed reads in a replacement (\ & and the | delimiter).
plist_value() {
    printf '%s' "$1" \
        | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
        | sed -e 's/[\\&|]/\\&/g'
}

sed -e "s|@AGENT_HOME@|$(plist_value "${AGENT_HOME}")|g" \
    -e "s|@ENV_FILE@|$(plist_value "${ENV_FILE}")|g" \
    -e "s|@AGENT_USER@|$(plist_value "$(id -un)")|g" \
    -e "s|@AGENT_GROUP@|$(plist_value "$(id -gn)")|g" \
    -e "s|@AGENT_USER_HOME@|$(plist_value "${HOME}")|g" \
    "${SCRIPT_DIR}/${LABEL}.plist" > "${PLIST}"
# A definition launchd would reject is caught here, not when root loads it.
plutil -lint -s "${PLIST}"

# Printed whenever the administrator has to act. Root may bootstrap into the
# system domain from any session, which is the whole reason the service
# lives there. register-daemon.sh does the whole sequence — validates the
# plist (it must run the service as the account that wrote it, and nothing
# else), and when a definition is being replaced waits for the old service to
# be gone before loading the new one, so two agents never run at once.
#
# The script is deliberately NOT staged under AGENT_HOME and the command
# does not point at this checkout: both are writable by this account, and
# this account runs code it did not write. Root must execute the script from
# a checkout the administrator owns; register-daemon.sh refuses otherwise.
registration_steps() {
    echo "  As an administrator, from a checkout of this repository that YOU own" >&2
    echo "  (not this one, and nothing under ${AGENT_HOME} — root must not run files $(id -un) can edit):" >&2
    # The plist path comes from FLOWTREE_AGENT_HOME, which this script does
    # not control; the administrator will paste this line into a root shell,
    # so it is emitted shell-quoted rather than as raw text.
    printf '    sudo <your checkout>/flowtree/runtime/agent/macos/register-daemon.sh %q %q\n' "${LABEL}" "${PLIST}" >&2
    echo "  Then run this script again (or re-run the deploy workflow)." >&2
}

# Retire the per-user LaunchAgent from earlier installs, in whichever domain
# it landed. The daemon must not be started beside it: two processes with
# the same node identity would both connect to the controller. bootout
# returns before the service is gone, and on a host whose per-user domain
# refuses a bootstrap it may refuse the bootout too, so the install goes on
# only once launchd no longer lists the service, and fails otherwise —
# removing the plist alone does not stop a loaded service.
UID_NUM="$(id -u)"
for legacy_domain in "gui/${UID_NUM}" "user/${UID_NUM}"; do
    legacy_service="${legacy_domain}/${LABEL}"
    if ! launchctl print "${legacy_service}" >/dev/null 2>&1; then
        continue
    fi
    echo "Stopping the per-user agent from an earlier install (${legacy_service})..."
    launchctl bootout "${legacy_service}" || true
    for _ in $(seq 1 30); do
        if ! launchctl print "${legacy_service}" >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
    if launchctl print "${legacy_service}" >/dev/null 2>&1; then
        echo "ERROR: ${legacy_service} is still loaded after bootout; the daemon cannot be started beside it." >&2
        echo "  Stop it by hand, then run this script again:" >&2
        echo "    launchctl bootout ${legacy_service}        # as $(id -un)" >&2
        echo "    sudo launchctl bootout ${legacy_service}   # if launchd refuses the first" >&2
        exit 1
    fi
done
if [ -f "${LEGACY_PLIST}" ]; then
    rm -f "${LEGACY_PLIST}"
    echo "Removed the per-user LaunchAgent definition ${LEGACY_PLIST}"
fi

if ! daemon_registered; then
    echo "ERROR: ${SERVICE} is not registered with launchd." >&2
    echo "  The JARs, run.sh and the service definition are staged under ${AGENT_HOME};" >&2
    echo "  registering the daemon is a one-time step this account cannot perform." >&2
    registration_steps
    exit 1
fi

if ! cmp -s "${PLIST}" "${DAEMON_PLIST}"; then
    echo "ERROR: the registered service definition ${DAEMON_PLIST} differs from ${PLIST}." >&2
    echo "  The install directory, env file or account changed since the daemon was" >&2
    echo "  registered, and launchd is still running the old definition." >&2
    registration_steps
    exit 1
fi

# ── Restart ────────────────────────────────────────────────────────
#
# The daemon runs as this account, so its process can be signalled without
# root, and KeepAlive brings it back on the classpath just installed. A
# service that is registered but has no process right now (in launchd's
# throttle back-off after a crash, say) comes back on its own; nothing
# needs to be sent to it.
#
# A pid alone does not identify a process: once the JVM exits, the number
# can be handed to its replacement or to anything else this account runs,
# and a signal sent to the number would then land on the wrong process.
# launchd will not signal a system-domain service for a non-root owner
# (`launchctl kill` answers "Not privileged"), so the process is identified
# by pid AND start time, and nothing is signalled unless both still match.

service_pid() {
    launchctl print "${SERVICE}" 2>/dev/null | awk '/^[[:space:]]*pid = /{print $3; exit}'
}
process_start() {
    ps -o lstart= -p "$1" 2>/dev/null | sed 's/[[:space:]]*$//'
}

OLD_PID="$(service_pid)"
OLD_START=""
if [ -n "${OLD_PID}" ]; then
    OLD_START="$(process_start "${OLD_PID}")"
fi
# True while the process that was found at the start is still the one
# holding that pid.
old_process_alive() {
    [ -n "${OLD_START}" ] && [ "$(process_start "${OLD_PID}")" = "${OLD_START}" ]
}

if [ -n "${OLD_PID}" ] && old_process_alive; then
    echo "Restarting the agent (${SERVICE}, pid ${OLD_PID}, started ${OLD_START})..."
    # Identity is re-checked immediately before each signal. The process may
    # have exited on its own since it was found (and KeepAlive may already be
    # starting its replacement); that is the state the signal was meant to
    # produce, so it is not a failure. A process that is still there and
    # cannot be signalled is: it means the service is not running as this
    # account.
    if old_process_alive && ! kill -TERM "${OLD_PID}" 2>/dev/null && old_process_alive; then
        echo "ERROR: pid ${OLD_PID} is running but cannot be signalled by $(id -un)." >&2
        echo "  ${SERVICE} is not running as this account; check UserName in ${DAEMON_PLIST}." >&2
        exit 1
    fi
    # KeepAlive respawns only after the process is gone; wait for that so the
    # new process does not race the old one for the controller connection.
    for _ in $(seq 1 30); do
        if ! old_process_alive; then
            break
        fi
        sleep 1
    done
    if old_process_alive; then
        echo "  pid ${OLD_PID} ignored SIGTERM for 30s; sending SIGKILL."
        old_process_alive && kill -KILL "${OLD_PID}" 2>/dev/null || true
    fi
elif [ -n "${OLD_PID}" ]; then
    echo "The agent's process (pid ${OLD_PID}) exited while this install ran; launchd will start it..."
    OLD_PID=""
else
    echo "Starting the agent (${SERVICE} is registered but has no process; launchd will start it)..."
fi

# ── Verify ─────────────────────────────────────────────────────────
#
# There is no controller endpoint that lists connected agents, so the
# check is made from this side: the service must have a live process that
# is not the one just stopped — by identity, not pid, since the number can
# be reused — and that process must hold an established TCP connection to
# the controller port. The Server's reconnect loop makes its first attempt
# about thirty seconds after start, hence the generous default timeout.

# True when the service's current process is not the one that was stopped.
new_process() {
    [ "$1" != "${OLD_PID}" ] || [ "$(process_start "$1")" != "${OLD_START}" ]
}

echo "Waiting up to ${CONNECT_TIMEOUT_SECONDS}s for the agent to connect to ${ROOT_HOST}:${ROOT_PORT}..."
DEADLINE=$(( $(date +%s) + CONNECT_TIMEOUT_SECONDS ))
while [ "$(date +%s)" -lt "${DEADLINE}" ]; do
    PID="$(service_pid)"
    if [ -n "${PID}" ] && new_process "${PID}" \
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
