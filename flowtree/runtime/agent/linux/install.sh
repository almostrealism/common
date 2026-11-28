#!/usr/bin/env bash
#
# Build, install and (re)start a native FlowTree host Server on Linux.
#
# The Linux counterpart of ../macos/install.sh: a plain JVM under systemd,
# running as the account this script is invoked by, that owns every Node of
# the host — native Nodes that run jobs in-process, and (with the Docker
# Node kind) Nodes that launch a container per job — from one typed
# agent.properties. Where the old layout ran one Server inside each agent
# container, this host runs one Server, and containers are a kind of Node.
#
# What it does, in order:
#   1. Refuses to start without the env file the service will need, without
#      Maven 3.9 or later (jobs isolate their Maven installs with a split
#      local repository, which older Maven silently ignores), and — when the
#      node list configures a Docker Node — without a Docker daemon this
#      account can reach. Fail in seconds, not after a build.
#   2. Builds the flowtree JARs (mvn install + dependency:copy-dependencies,
#      the same two commands rebuild.sh runs for the container images).
#   3. Stages them into a fresh lib directory under FLOWTREE_AGENT_HOME and
#      swaps it in. The running agent keeps the old JARs open until it is
#      restarted; they are never overwritten underneath it. The module's
#      agent.properties is installed with the operator's typed node list
#      (nodes.properties, see nodes.properties.example) appended.
#   4. Renders the systemd unit into FLOWTREE_AGENT_HOME/conf and checks that
#      the unit systemd has loaded is byte-for-byte that file, that systemd
#      is not holding an older definition in memory, and that the unit is
#      enabled at boot. The unit is a system unit that runs as this account,
#      registered ONCE by root; this script never needs root and never has
#      it. When the unit is not registered, or the registered definition is
#      stale, the script fails and prints the one command the administrator
#      has to run — register-unit.sh, from a checkout the administrator
#      owns, which validates the unit before root acts on it (see that
#      script's header for the trust boundary).
#   5. Restarts the agent by signalling the running process. Restart=always
#      starts it again on the new classpath, so the process is owned by
#      systemd from boot onward: it survives the end of the CI job that
#      installed it, a crash, and a reboot, with nobody logged in.
#   6. Waits for the new process to open its connection to the controller
#      and fails if it does not — a service that systemd reports as active
#      but that never connects is not a deployed agent.
#
# Usage:
#   flowtree/runtime/agent/linux/install.sh [--no-build]
#
#   --no-build   Skip the Maven build and install whatever is in
#                flowtree/runtime/target already. For iterating on the
#                service definition.
#
# Environment:
#   FLOWTREE_AGENT_HOME   install directory (default: ${HOME}/flowtree-agent).
#                         Holds lib/, conf/, bin/, logs/ and the workspace.
#   FLOWTREE_AGENT_ENV    env file the service loads
#                         (default: ${FLOWTREE_AGENT_HOME}/agent.env).
#                         Must exist and define FLOWTREE_ROOT_HOST and a
#                         credential; see agent.env.example.
#   FLOWTREE_AGENT_NODES  typed node list appended to agent.properties
#                         (default: ${FLOWTREE_AGENT_HOME}/nodes.properties;
#                         optional — without it the Server builds the
#                         legacy group of native Nodes).
#   CONNECT_TIMEOUT_SECONDS
#                         how long to wait for the controller connection
#                         (default: 180). The agent's first attempt is ~30s
#                         after start, so anything under 60 is too short.
#
# Exit codes:
#   0 - agent installed, running, and connected to the controller
#   1 - a precondition failed, the build failed, the unit is not registered
#       (or registered with a stale definition), or the agent did not
#       connect

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROJECT_ROOT="$(cd "${MODULE_DIR}/../.." && pwd)"

UNIT_NAME="almostrealism-flowtree-agent"
UNIT_FILE="${UNIT_NAME}.service"
AGENT_HOME="${FLOWTREE_AGENT_HOME:-${HOME}/flowtree-agent}"
ENV_FILE="${FLOWTREE_AGENT_ENV:-${AGENT_HOME}/agent.env}"
NODES_FILE="${FLOWTREE_AGENT_NODES:-${AGENT_HOME}/nodes.properties}"
# The definition this script renders. The copy systemd actually loaded is
# found by asking systemd for it (FragmentPath); only root can write it, and
# this script only compares the two.
UNIT="${AGENT_HOME}/conf/${UNIT_FILE}"
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

if [ "$(uname -s)" != "Linux" ]; then
    echo "ERROR: this installer manages a systemd service and only runs on Linux." >&2
    exit 1
fi
# The Server runs jobs that execute code nobody reviewed; it must run as an
# ordinary account, and register-unit.sh refuses a unit owned by root.
if [ "$(id -u)" -eq 0 ]; then
    echo "ERROR: run this as the account the agent should run as, not as root." >&2
    exit 1
fi

# ── Preconditions ──────────────────────────────────────────────────
#
# The env file checks, the Maven probe, the build and the stage/swap are
# shared with the macOS installer; see ../host/install-lib.sh.

# shellcheck source=../host/install-lib.sh
. "${SCRIPT_DIR}/../host/install-lib.sh"

# Every value rendered into the unit is a path or an account name. systemd
# splits ExecStart= on whitespace, expands `%` specifiers and reads quotes
# and backslashes, so instead of escaping for all of that the values are
# held to a set of characters that means the same thing to systemd, to sed
# and to the shell.
unit_safe() {
    local name="$1" value="$2"
    case "${value}" in
        ''|*[!A-Za-z0-9._/@+-]*)
            echo "ERROR: ${name} '${value}' may contain only letters, digits and . _ / @ + -" >&2
            echo "  (it is written into the systemd unit, where spaces, % and quotes change its meaning)." >&2
            exit 1
            ;;
    esac
}
unit_safe FLOWTREE_AGENT_HOME "${AGENT_HOME}"
unit_safe FLOWTREE_AGENT_ENV "${ENV_FILE}"
unit_safe HOME "${HOME}"
unit_safe "account name" "$(id -un)"
unit_safe "group name" "$(id -gn)"
for path_value in "${AGENT_HOME}" "${ENV_FILE}"; do
    case "${path_value}" in
        /*) ;;
        *)
            echo "ERROR: '${path_value}' is not an absolute path; systemd requires one." >&2
            exit 1
            ;;
    esac
done

require_agent_env

for cmd in java systemctl ss mvn; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not on PATH for $(id -un)." >&2
        exit 1
    fi
done

# A precondition here, not the warning the macOS installer prints: a Linux
# host Server runs several Nodes side by side, so jobs that share one
# unsplit local repository would read each other's installed artifacts.
probe_mvn_split_repository
if [ "${MVN_SPLIT_SUPPORTED}" = false ]; then
    echo "ERROR: mvn on PATH is older than 3.9 (or unreadable)." >&2
    echo "  Jobs on this host isolate their Maven installs with a split local" >&2
    echo "  repository, which older Maven silently ignores. Install Maven 3.9 or" >&2
    echo "  later for $(id -un), or put it first on PATH (FLOWTREE_AGENT_PATH)." >&2
    exit 1
fi

# Each node entry is a line `nodes.<i>.kind=<kind>`. A Docker Node is useful
# only if this account can drive the daemon; finding that out at the first
# job would leave a Node that accepts work it can never run.
if [ -f "${NODES_FILE}" ] \
        && grep -Eq '^[[:space:]]*nodes\.[0-9]+\.kind[[:space:]]*=[[:space:]]*docker[[:space:]]*$' "${NODES_FILE}"; then
    if ! command -v docker >/dev/null 2>&1; then
        echo "ERROR: ${NODES_FILE} configures a docker Node, but docker is not on PATH for $(id -un)." >&2
        exit 1
    fi
    if ! docker info >/dev/null 2>&1; then
        echo "ERROR: ${NODES_FILE} configures a docker Node, but 'docker info' fails for $(id -un)." >&2
        echo "  The account must be able to reach the Docker daemon (e.g. be in the docker group)." >&2
        exit 1
    fi
fi

unit_property() {
    systemctl show -p "$1" --value "${UNIT_FILE}" 2>/dev/null || true
}
unit_registered() {
    [ "$(unit_property LoadState)" = "loaded" ]
}

NODE_LIST_STATE="none — legacy native Nodes"
if [ -f "${NODES_FILE}" ]; then
    NODE_LIST_STATE="${NODES_FILE}"
fi
SERVICE_STATE="NOT registered — this run stages the files, then stops with the one-time registration steps"
if unit_registered; then
    SERVICE_STATE="registered"
fi
echo "Installing native FlowTree host Server"
echo "  Account:     $(id -un)"
echo "  Install dir: ${AGENT_HOME}"
echo "  Env file:    ${ENV_FILE}"
echo "  Node list:   ${NODE_LIST_STATE}"
echo "  Controller:  ${ROOT_HOST}:${ROOT_PORT}"
echo "  Service:     ${UNIT_FILE} (${SERVICE_STATE})"
echo ""

# ── Build ──────────────────────────────────────────────────────────

build_runtime

# ── Stage and swap ─────────────────────────────────────────────────

stage_runtime

# The properties are assembled beside their destination and moved over it,
# so a Server restarting at this moment reads either the old file or the
# new one, never half of it.
{
    cat "${MODULE_DIR}/conf/agent.properties"
    if [ -f "${NODES_FILE}" ]; then
        echo ""
        echo "# Typed node list, appended by install.sh from ${NODES_FILE}"
        cat "${NODES_FILE}"
    fi
} > "${AGENT_HOME}/conf/agent.properties.new"
mv "${AGENT_HOME}/conf/agent.properties.new" "${AGENT_HOME}/conf/agent.properties"
cp "${SCRIPT_DIR}/run.sh" "${AGENT_HOME}/bin/run.sh"
chmod +x "${AGENT_HOME}/bin/run.sh"

INSTALLED_JARS=("${AGENT_HOME}"/lib/*.jar)
echo "Installed ${#INSTALLED_JARS[@]} JARs to ${AGENT_HOME}/lib"

# ── Service definition ─────────────────────────────────────────────
#
# The unit is re-rendered on every install so a changed AGENT_HOME, env
# file path or account is visible, but systemd loads its own copy from
# /etc/systemd/system and only root can replace that. So the rendered file
# is compared with the one systemd loaded, and a difference stops the
# install: a redeploy must not report success against a definition that no
# longer describes what was installed. The values were held to unit_safe's
# character set above, which contains nothing sed reads in a replacement.

sed -e "s|@AGENT_HOME@|${AGENT_HOME}|g" \
    -e "s|@ENV_FILE@|${ENV_FILE}|g" \
    -e "s|@AGENT_USER@|$(id -un)|g" \
    -e "s|@AGENT_GROUP@|$(id -gn)|g" \
    -e "s|@AGENT_USER_HOME@|${HOME}|g" \
    "${SCRIPT_DIR}/flowtree-agent.service" > "${UNIT}"

# Printed whenever the administrator has to act. register-unit.sh validates
# the unit (it must run the service as the account that wrote it, and
# nothing else), and when a definition is being replaced stops the old
# service before starting the new one, so two agents never run at once.
#
# The script is deliberately NOT staged under AGENT_HOME and the command
# does not point at this checkout: both are writable by this account, and
# this account runs code it did not write. Root must execute the script from
# a checkout the administrator owns; register-unit.sh refuses otherwise.
registration_steps() {
    echo "  As an administrator, from a checkout of this repository that YOU own" >&2
    echo "  (not this one, and nothing under ${AGENT_HOME} — root must not run files $(id -un) can edit):" >&2
    printf '    sudo <your checkout>/flowtree/runtime/agent/linux/register-unit.sh %q %q\n' "${UNIT_NAME}" "${UNIT}" >&2
    echo "  Then run this script again." >&2
}

if ! unit_registered; then
    echo "ERROR: ${UNIT_FILE} is not registered with systemd." >&2
    echo "  The JARs, run.sh and the service definition are staged under ${AGENT_HOME};" >&2
    echo "  registering the unit is a one-time step this account cannot perform." >&2
    registration_steps
    exit 1
fi

REGISTERED_UNIT="$(unit_property FragmentPath)"
if [ -z "${REGISTERED_UNIT}" ] || ! cmp -s "${UNIT}" "${REGISTERED_UNIT}"; then
    echo "ERROR: the registered service definition ${REGISTERED_UNIT:-<none>} differs from ${UNIT}." >&2
    echo "  The install directory, env file or account changed since the unit was" >&2
    echo "  registered, and systemd is still running the old definition." >&2
    registration_steps
    exit 1
fi

# The file can match while systemd still runs the definition it read before
# the file was replaced; until a daemon-reload it is the old one that counts.
if [ "$(unit_property NeedDaemonReload)" = "yes" ]; then
    echo "ERROR: ${REGISTERED_UNIT} changed on disk since systemd loaded it." >&2
    registration_steps
    exit 1
fi

if [ "$(unit_property UnitFileState)" != "enabled" ]; then
    echo "ERROR: ${UNIT_FILE} is registered but not enabled, so it would not start at boot." >&2
    registration_steps
    exit 1
fi

# ── Restart ────────────────────────────────────────────────────────
#
# The unit runs as this account, so its process can be signalled without
# root, and Restart=always brings it back on the classpath just installed.
# A service that is registered but has no process right now (between
# restarts after a crash, say) comes back on its own; nothing needs to be
# sent to it.
#
# A pid alone does not identify a process: once the JVM exits, the number
# can be handed to its replacement or to anything else this account runs,
# and a signal sent to the number would then land on the wrong process.
# `systemctl kill` needs privileges this account does not have, so the
# process is identified by pid AND start time, and nothing is signalled
# unless both still match.

service_pid() {
    local pid
    pid="$(unit_property MainPID)"
    if [ -n "${pid}" ] && [ "${pid}" != "0" ]; then
        printf '%s' "${pid}"
    fi
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
    echo "Restarting the agent (${UNIT_FILE}, pid ${OLD_PID}, started ${OLD_START})..."
    # Identity is re-checked immediately before each signal. A process that
    # exited on its own since it was found is the state the signal was meant
    # to produce, so it is not a failure. A process that is still there and
    # cannot be signalled is: the service is not running as this account.
    if old_process_alive && ! kill -TERM "${OLD_PID}" 2>/dev/null && old_process_alive; then
        echo "ERROR: pid ${OLD_PID} is running but cannot be signalled by $(id -un)." >&2
        echo "  ${UNIT_FILE} is not running as this account; check User= in ${REGISTERED_UNIT}." >&2
        exit 1
    fi
    # Wait for the old process to be gone so the new one does not race it
    # for the controller connection.
    for _ in $(seq 1 30); do
        if ! old_process_alive; then
            break
        fi
        sleep 1
    done
    if old_process_alive; then
        echo "  pid ${OLD_PID} ignored SIGTERM for 30s; sending SIGKILL."
        if old_process_alive; then
            kill -KILL "${OLD_PID}" 2>/dev/null || true
        fi
    fi
elif [ -n "${OLD_PID}" ]; then
    echo "The agent's process (pid ${OLD_PID}) exited while this install ran; systemd will start it..."
    OLD_PID=""
else
    echo "Starting the agent (${UNIT_FILE} is registered but has no process; systemd will start it)..."
fi

# ── Verify ─────────────────────────────────────────────────────────
#
# There is no controller endpoint that lists connected agents, so the
# check is made from this side: the service must have a live process that
# is not the one just stopped — by identity, not pid, since the number can
# be reused — and that process must hold an established TCP connection to
# the controller port. `ss -p` names the owning process of sockets that
# belong to this account without root. The Server's reconnect loop makes
# its first attempt about thirty seconds after start, hence the generous
# default timeout.

# True when the service's current process is not the one that was stopped.
new_process() {
    [ "$1" != "${OLD_PID}" ] || [ "$(process_start "$1")" != "${OLD_START}" ]
}
connected_to_controller() {
    ss -Htnp state established "( dport = :${ROOT_PORT} )" 2>/dev/null | grep -q "pid=$1,"
}

echo "Waiting up to ${CONNECT_TIMEOUT_SECONDS}s for the agent to connect to ${ROOT_HOST}:${ROOT_PORT}..."
DEADLINE=$(( $(date +%s) + CONNECT_TIMEOUT_SECONDS ))
while [ "$(date +%s)" -lt "${DEADLINE}" ]; do
    PID="$(service_pid)"
    if [ -n "${PID}" ] && new_process "${PID}" && connected_to_controller "${PID}"; then
        echo "Agent is running (pid ${PID}) and connected to the controller."
        echo "  Logs: ${AGENT_HOME}/logs/agent.log"
        exit 0
    fi
    sleep 5
done

echo "ERROR: the agent did not connect to ${ROOT_HOST}:${ROOT_PORT} within ${CONNECT_TIMEOUT_SECONDS}s." >&2
echo "  Service: $(systemctl show -p ActiveState -p SubState -p ExecMainStatus "${UNIT_FILE}" 2>/dev/null | tr '\n' ';')" >&2
echo "  Last log lines from ${AGENT_HOME}/logs/agent.log:" >&2
tail -n 40 "${AGENT_HOME}/logs/agent.log" 2>/dev/null >&2 || true
exit 1
