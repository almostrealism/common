#!/usr/bin/env bash
set -euo pipefail

# ─── Start, stop, and inspect the OpenCL CI runner fleet ─────────────
#
# A thin front end over the systemd user units, for the routine case: the host
# is already set up and installed, and the machine is wanted back for
# interactive GPU work.
#
#   ./fleet.sh start          # start the configured number of runners
#   ./fleet.sh start 3        # start three (also the way to scale)
#   ./fleet.sh stop           # stop all of them, deregistering cleanly
#   ./fleet.sh stop --if-idle # stop only if no job is currently running
#   ./fleet.sh status         # units, in-flight jobs, and host memory
#   ./fleet.sh logs [-f]      # journal for every instance
#   ./fleet.sh restart        # stop then start
#
# It can be invoked from an admin account: anything other than the service
# account re-executes itself under it with `sudo -iu`, using the service
# account's own checkout. Set AR_CI_USER to override the account name and
# AR_CI_FLEET_SH to override the path re-executed there.
#
# start delegates to install-runner.sh --no-build rather than calling systemctl
# directly. That keeps one implementation of the things that must happen before
# a runner comes up — refreshing runner.env, the resource-limit drop-in,
# retiring the pre-template unit, stopping instances above the requested count,
# and above all the memory-headroom check, which exists because overcommitting
# this host's unified GPU memory has hung it hard enough to need physical
# intervention. Use install-runner.sh directly when the image needs rebuilding.
#
# For host setup see setup-host.sh; for everything else see README.md.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_USER="${AR_CI_USER:-ar-ci}"
UNIT_NAME="ar-ci-cl-runner"
CONTAINER_PREFIX="ar-ci-cl-runner-"

# Matches install-runner.sh: the highest instance index either script will look
# at when sweeping for runners that should not be there.
MAX_INSTANCE=32

usage() { sed -n '4,30p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
    -h|--help|help|"") usage; exit 0 ;;
esac

# ---------- Run as the service account ----------

# The fleet belongs to the service account's systemd user instance, so every
# operation has to happen there. `sudo -iu` rather than `sudo -u`: the login
# session is what gives XDG_RUNTIME_DIR, without which neither `systemctl
# --user` nor rootless podman can find anything.
if [ "$(id -un)" != "${SERVICE_USER}" ]; then
    SERVICE_HOME=$(getent passwd "${SERVICE_USER}" | cut -d: -f6)
    if [ -z "${SERVICE_HOME}" ]; then
        echo "ERROR: no account named '${SERVICE_USER}' on this host." >&2
        echo "  Run setup-host.sh first, or set AR_CI_USER if it is named" >&2
        echo "  something else here." >&2
        exit 1
    fi

    # The admin account's checkout is generally unreadable by the service
    # account, so re-exec the copy in the service account's own tree.
    SERVICE_SCRIPT="${AR_CI_FLEET_SH:-${SERVICE_HOME}/common/tools/ci/rocm/fleet.sh}"
    if ! sudo -u "${SERVICE_USER}" test -x "${SERVICE_SCRIPT}"; then
        echo "ERROR: ${SERVICE_SCRIPT} is not executable by ${SERVICE_USER}." >&2
        echo "  Point AR_CI_FLEET_SH at the copy in that account's checkout," >&2
        echo "  or run this from a shell opened with 'sudo -iu ${SERVICE_USER}'." >&2
        exit 1
    fi

    exec sudo -iu "${SERVICE_USER}" -- "${SERVICE_SCRIPT}" "$@"
fi

# Lingering keeps /run/user/<uid> alive for the service account, but a
# non-login invocation does not always export the variable that names it.
if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    CANDIDATE="/run/user/$(id -u)"
    if [ -d "${CANDIDATE}" ]; then
        export XDG_RUNTIME_DIR="${CANDIDATE}"
    else
        echo "ERROR: XDG_RUNTIME_DIR is not set and ${CANDIDATE} does not exist." >&2
        echo "  The user manager is not running for $(id -un), which means" >&2
        echo "  lingering is off. From an account with sudo:" >&2
        echo "    sudo loginctl enable-linger $(id -un)" >&2
        exit 1
    fi
fi

# ---------- Helpers ----------

# Instance indices whose unit is currently active, one per line.
active_instances() {
    local i
    for i in $(seq 1 "${MAX_INSTANCE}"); do
        if systemctl --user is-active --quiet "${UNIT_NAME}@${i}.service" 2>/dev/null; then
            echo "${i}"
        fi
    done
}

# True when the container for an instance is executing a job. The container is
# up whenever the runner is registered and waiting, so its presence says
# nothing; Runner.Worker is the process the agent forks per job, and it is the
# only reliable distinction between idle and busy from outside.
instance_busy() {
    podman top "${CONTAINER_PREFIX}$1" args 2>/dev/null | grep -q 'Runner\.Worker'
}

busy_instances() {
    local i
    for i in $(active_instances); do
        instance_busy "${i}" && echo "${i}"
    done
    return 0
}

# ---------- Commands ----------

cmd_start() {
    local count="${1:-}"
    local args=(--no-build)
    [ -n "${count}" ] && args+=(--runners "${count}")

    if [ ! -x "${SCRIPT_DIR}/install-runner.sh" ]; then
        echo "ERROR: ${SCRIPT_DIR}/install-runner.sh not found." >&2
        exit 1
    fi

    exec "${SCRIPT_DIR}/install-runner.sh" "${args[@]}"
}

cmd_stop() {
    local if_idle=0
    [ "${1:-}" = "--if-idle" ] && if_idle=1

    local instances busy
    instances=$(active_instances)
    if [ -z "${instances}" ]; then
        echo "No runners are active."
        return 0
    fi

    busy=$(busy_instances)
    if [ -n "${busy}" ]; then
        if [ "${if_idle}" = 1 ]; then
            echo "Not stopping: instance(s)$(echo " ${busy}" | tr '\n' ' ')are running a job."
            return 1
        fi
        echo "NOTE: instance(s)$(echo " ${busy}" | tr '\n' ' ')are running a job; it will be"
        echo "  cancelled. Runners are ephemeral, so waiting for the current job to"
        echo "  finish and stopping then costs nothing:  ./fleet.sh stop --if-idle"
        echo
    fi

    # The entrypoint traps SIGTERM and deregisters from GitHub before exiting
    # (TimeoutStopSec=90 in the unit), so a stop leaves no stale offline runner
    # behind. That is also why this can take a moment per instance.
    local i
    for i in ${instances}; do
        echo "Stopping ${UNIT_NAME}@${i}.service ..."
        systemctl --user stop "${UNIT_NAME}@${i}.service" || true
    done

    echo
    if [ -n "$(active_instances)" ]; then
        echo "WARNING: some instances are still active:"
        cmd_status
    else
        echo "All runners stopped. The GPU and its memory are free."
    fi
}

cmd_status() {
    local instances busy state
    instances=$(active_instances)

    if [ -z "${instances}" ]; then
        echo "No runners are active."
    else
        local i
        for i in ${instances}; do
            state="idle"
            instance_busy "${i}" && state="running a job"
            echo "  ${UNIT_NAME}@${i}.service: active (${state})"
        done
    fi

    # Failed instances do not appear above, and are the case worth surfacing:
    # runners restart after every job, so a restart loop is normal and a failed
    # unit is not.
    local failed
    failed=$(systemctl --user list-units "${UNIT_NAME}@*" --state=failed --no-legend 2>/dev/null || true)
    if [ -n "${failed}" ]; then
        echo
        echo "Failed units:"
        echo "${failed}" | sed 's/^/  /'
        echo "  journalctl --user -u '${UNIT_NAME}@*' -n 50 --no-pager"
    fi

    # The GPU here is integrated, so what is left for interactive work is host
    # memory. It is the number the operator actually wants.
    if [ -r /proc/meminfo ]; then
        echo
        awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2}
             END{printf "Host memory: %.0fG available of %.0fG\n", a/1048576, t/1048576}' \
            /proc/meminfo
    fi
}

cmd_logs() {
    exec journalctl --user -u "${UNIT_NAME}@*" -n 50 "$@"
}

# ---------- Dispatch ----------

COMMAND="${1:-}"
[ $# -gt 0 ] && shift

case "${COMMAND}" in
    start)   cmd_start "$@" ;;
    stop)    cmd_stop "$@" ;;
    restart) cmd_stop; echo; cmd_start "$@" ;;
    status)  cmd_status ;;
    logs)    cmd_logs "$@" ;;
    -h|--help|help|"") usage ;;
    *) echo "Unknown command: ${COMMAND}" >&2; echo >&2; usage >&2; exit 2 ;;
esac
