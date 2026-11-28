#!/bin/bash
#
# Register (or replace) a systemd system unit from a unit file rendered by
# the account the service will run as. This is the one step of a native
# Linux agent install that needs root; install.sh renders the unit and
# prints this command. The Linux counterpart of ../macos/register-daemon.sh.
#
# Trust boundary. The unit comes from a service account's home, and that
# account executes code it did not write (it runs coding-agent jobs), so
# nothing it can write may be trusted to decide what root does:
#
#   - This script refuses to run unless the file it was invoked from, and
#     every directory on the path to it, is owned by root or by the
#     administrator invoking sudo, is not group- or world-writable, carries
#     no ACL, and is not a symlink; a trusted file inside a directory the
#     service account can write to can be swapped before sudo opens it. Run
#     it from a checkout you own, under directories only you and root can
#     write — never from a copy under the service account's home, never from
#     a checkout that account owns, and not from /tmp.
#   - It runs with the base-system PATH only, and the shebang names
#     /bin/bash outright, so nothing another account could put on the
#     administrator's PATH is consulted.
#   - The administrator names the unit on the command line; the file is
#     installed under that name and no other, and the name must start with
#     almostrealism-, so no system service can be replaced.
#   - The unit is copied into a directory only root can reach before
#     anything reads it, so what is validated is what gets installed.
#   - The unit speaks for its owner and for nobody else: the file and every
#     directory above it must be writable by root and the owner alone, under
#     the same checks as this script's own path.
#   - The service must run as the account that owns the file, with that
#     account's primary group: exactly one User= naming the owner is
#     required, Group= if present must be the owner's primary group, and the
#     owner must not be root.
#   - Only the directives a plain service needs are accepted (see
#     ALLOWED_*). Everything systemd would do as root on the unit's behalf
#     is outside that list: EnvironmentFile= (read by the manager),
#     StandardOutput=file: (opened by the manager), ExecStartPre= and the
#     ExecStart= prefixes such as `+` and `!` that skip the User= switch,
#     capabilities, and every other directive. Line continuations are
#     refused so that what is checked is what systemd parses.
#
# What it does, in order:
#   1. Checks its own provenance, copies the unit somewhere only root can
#      write, and checks its content.
#   2. If the unit is already running, stops it — `systemctl stop` returns
#      once the old process is gone, so two agents with the same node
#      identity never run at once.
#   3. Installs the unit as root:root, mode 644, under /etc/systemd/system,
#      reloads systemd, enables the unit at boot and starts it.
#   4. Prints the service's state and pid.
#
# Usage:
#   sudo /path/to/your/checkout/flowtree/runtime/agent/linux/register-unit.sh <name> <rendered unit>
#
#   <name>  the unit being registered, without .service, e.g.
#           almostrealism-flowtree-agent
#
# Exit codes:
#   0 - the unit is registered from the given definition and started
#   1 - not root, the script or unit failed a trust check, the unit is
#       missing or carries a directive this script does not accept, or a
#       drop-in directory would change what systemd loads

set -euo pipefail

export PATH="/usr/bin:/bin:/usr/sbin:/sbin"

UNIT_DIR="/etc/systemd/system"
# Root's home: root-owned and closed to everyone else on every mainstream
# distribution. The checks below hold it to that rather than assume it.
STAGING_ROOT="/root"
NAME_PREFIX="almostrealism-"
ALLOWED_UNIT="Description Documentation Wants After StartLimitIntervalSec"
ALLOWED_SERVICE="Type User Group Environment WorkingDirectory ExecStart Restart RestartSec NoNewPrivileges"
ALLOWED_INSTALL="WantedBy"

if [ "$#" -ne 2 ]; then
    echo "Usage: sudo $0 <name> <rendered unit>" >&2
    exit 1
fi
NAME="$1"
SOURCE="$2"

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run as root: sudo $0 ${NAME} ${SOURCE}" >&2
    exit 1
fi
case "${NAME}" in
    "${NAME_PREFIX}"*) ;;
    *)
        echo "ERROR: unit '${NAME}' does not start with ${NAME_PREFIX}; this script registers only this project's services." >&2
        exit 1
        ;;
esac
case "${NAME}" in
    *[!A-Za-z0-9._-]*|*.service)
        echo "ERROR: unit '${NAME}' must be a bare name of letters, digits, '.', '_' and '-' (without .service)." >&2
        exit 1
        ;;
esac
for cmd in systemctl stat install mktemp; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not available." >&2
        exit 1
    fi
done

# ── Path checks ────────────────────────────────────────────────────

ADMIN_UID="${SUDO_UID:-0}"

# True when the path carries an extended ACL: GNU ls marks it with a `+`
# after the mode. Any ACL is refused rather than interpreted, since an
# entry can grant write access the mode bits do not show.
has_acl() {
    # The mode string is the one thing read from ls, and it never contains
    # a file name, so the usual reasons not to parse ls do not apply.
    # shellcheck disable=SC2012
    [ "$(ls -ld "$1" | cut -c11)" = "+" ]
}

# Fails unless the path can be changed only by root and the given uid —
# owned by one of the two, not writable by anyone else, no ACL, not a
# symlink; the reason is printed.
writable_only_by() {
    local uid="$1" path="$2" owner mode
    if [ -L "${path}" ]; then
        echo "${path} is a symlink" >&2
        return 1
    fi
    owner="$(stat -c '%u' "${path}")"
    mode="$(stat -c '%a' "${path}")"
    if [ "${owner}" != "0" ] && [ "${owner}" != "${uid}" ]; then
        echo "${path} is owned by uid ${owner}, not root or uid ${uid}" >&2
        return 1
    fi
    if [ $(( 8#${mode} & 8#022 )) -ne 0 ]; then
        echo "${path} is group- or world-writable (mode ${mode})" >&2
        return 1
    fi
    if has_acl "${path}"; then
        echo "${path} has an ACL" >&2
        return 1
    fi
}

trusted_path() {
    writable_only_by "${ADMIN_UID}" "$1"
}

# Stricter: root's and nobody else's — for the directory the unit is staged
# in, where even read access would let someone learn a name to race.
root_only_path() {
    local path="$1" mode
    if [ -L "${path}" ] || [ "$(stat -c '%u' "${path}")" != "0" ]; then
        echo "${path} is a symlink or not owned by root" >&2
        return 1
    fi
    mode="$(stat -c '%a' "${path}")"
    if [ $(( 8#${mode} & 8#077 )) -ne 0 ] || has_acl "${path}"; then
        echo "${path} is reachable by others (mode ${mode}, or an ACL)" >&2
        return 1
    fi
}

# Runs a check on every prefix of an absolute path, / first.
check_path_components() {
    local path="$1" prefix="" component
    shift
    local -a components
    IFS='/' read -r -a components <<< "${path#/}"
    "$@" / || return 1
    for component in "${components[@]}"; do
        prefix="${prefix}/${component}"
        "$@" "${prefix}" || return 1
    done
}

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -L)/$(basename "${BASH_SOURCE[0]}")"
if ! check_path_components "${SELF}" trusted_path; then
    echo "ERROR: refusing to run from ${SELF}." >&2
    echo "  Root must not execute a file the service account can edit or replace, and" >&2
    echo "  every directory on the way to it counts. Run this script from a checkout" >&2
    echo "  you own, under directories only you and root can write." >&2
    exit 1
fi

if ! writable_only_by 0 / || ! writable_only_by 0 "${STAGING_ROOT}"; then
    echo "ERROR: ${STAGING_ROOT} is not a root-owned, root-only-writable directory; refusing to stage there." >&2
    exit 1
fi

if [ ! -f "${SOURCE}" ] || [ -L "${SOURCE}" ]; then
    echo "ERROR: ${SOURCE} is not a regular file." >&2
    exit 1
fi
OWNER_UID="$(stat -c '%u' "${SOURCE}")"
OWNER_NAME="$(id -un "${OWNER_UID}")"
OWNER_GROUP="$(id -gn "${OWNER_UID}")"
if [ "${OWNER_UID}" = "0" ]; then
    echo "ERROR: ${SOURCE} is owned by root; this script registers services for service accounts." >&2
    exit 1
fi

SOURCE_ABS="$(cd "$(dirname "${SOURCE}")" && pwd -L)/$(basename "${SOURCE}")"
if ! check_path_components "${SOURCE_ABS}" writable_only_by "${OWNER_UID}"; then
    echo "ERROR: ${SOURCE} can be changed by an account other than root and ${OWNER_NAME}; refusing to register it." >&2
    echo "  Every directory from / down to the unit must be owned by root or ${OWNER_NAME}, have no" >&2
    echo "  group or world write bit and no ACL, and not be a symlink." >&2
    exit 1
fi

# ── A private copy of the unit ─────────────────────────────────────

STAGING_DIR="$(mktemp -d "${STAGING_ROOT}/register-unit.XXXXXX")"
trap 'rm -rf "${STAGING_DIR}"' EXIT
chmod 700 "${STAGING_DIR}"
if ! root_only_path "${STAGING_DIR}"; then
    echo "ERROR: the staging directory could not be made root-only; refusing to continue." >&2
    exit 1
fi
STAGED="${STAGING_DIR}/${NAME}.service"
cp "${SOURCE}" "${STAGED}"
chmod 600 "${STAGED}"

# ── Content checks ─────────────────────────────────────────────────

refuse() {
    echo "ERROR: ${SOURCE}: $1" >&2
    exit 1
}

SECTION=""
USER_COUNT=0
GROUP_VALUE=""
while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
        ''|'#'*|';'*) continue ;;
        *\\) refuse "line continuations are not accepted: ${line}" ;;
        '[Unit]'|'[Service]'|'[Install]') SECTION="${line}"; continue ;;
        '['*) refuse "section ${line} is not accepted" ;;
        *=*) ;;
        *) refuse "not a directive: ${line}" ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "${SECTION}" in
        '[Unit]') allowed="${ALLOWED_UNIT}" ;;
        '[Service]') allowed="${ALLOWED_SERVICE}" ;;
        '[Install]') allowed="${ALLOWED_INSTALL}" ;;
        *) refuse "directive outside a section: ${line}" ;;
    esac
    case " ${allowed} " in
        *" ${key} "*) ;;
        *) refuse "${SECTION} sets '${key}', which this script does not accept. Accepted: ${allowed}" ;;
    esac
    case "${key}" in
        User)
            USER_COUNT=$(( USER_COUNT + 1 ))
            if [ "${value}" != "${OWNER_NAME}" ]; then
                refuse "the file is owned by ${OWNER_NAME} but User= is '${value}'; a unit registered from an account's file runs as that account, nothing else."
            fi
            ;;
        Group) GROUP_VALUE="${value}" ;;
        Type)
            [ "${value}" = "simple" ] || refuse "Type=${value} is not accepted; only Type=simple."
            ;;
        ExecStart)
            case "${value}" in
                /*) ;;
                *) refuse "ExecStart= must be an absolute command with no prefix (got '${value}')." ;;
            esac
            ;;
        WantedBy)
            [ "${value}" = "multi-user.target" ] || refuse "WantedBy=${value} is not accepted; only multi-user.target."
            ;;
        Wants|After)
            [ "${value}" = "network-online.target" ] || refuse "${key}=${value} is not accepted; only network-online.target. Wants= has systemd start the named unit as root."
            ;;
    esac
done < "${STAGED}"

if [ "${USER_COUNT}" -ne 1 ]; then
    refuse "exactly one User= line is required (found ${USER_COUNT})."
fi
if [ -n "${GROUP_VALUE}" ] && [ "${GROUP_VALUE}" != "${OWNER_GROUP}" ]; then
    refuse "Group=${GROUP_VALUE}; ${OWNER_NAME}'s primary group is ${OWNER_GROUP}."
fi

# A drop-in would change the service after this script validated it, and
# install.sh's comparison would never see it.
for dropin in "${UNIT_DIR}/${NAME}.service.d" "/run/systemd/system/${NAME}.service.d"; do
    if [ -e "${dropin}" ]; then
        echo "ERROR: ${dropin} exists; a drop-in would change what systemd runs. Remove it first." >&2
        exit 1
    fi
done

# ── Replace or register ────────────────────────────────────────────

UNIT_FILE="${NAME}.service"
TARGET="${UNIT_DIR}/${UNIT_FILE}"

if [ "$(systemctl show -p ActiveState --value "${UNIT_FILE}" 2>/dev/null || true)" != "inactive" ] \
        && [ "$(systemctl show -p LoadState --value "${UNIT_FILE}" 2>/dev/null || true)" = "loaded" ]; then
    echo "Stopping the running ${UNIT_FILE}..."
    systemctl stop "${UNIT_FILE}"
fi

install -o root -g root -m 644 "${STAGED}" "${TARGET}"
echo "Installed ${TARGET} (runs as ${OWNER_NAME})"
systemctl daemon-reload
systemctl enable "${UNIT_FILE}"
systemctl restart "${UNIT_FILE}"
echo "Registered ${UNIT_FILE}"
systemctl show -p ActiveState -p SubState -p MainPID "${UNIT_FILE}" | sed 's/^/  /'
