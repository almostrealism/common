#!/usr/bin/env bash
#
# Register (or replace) a LaunchDaemon in the system domain from a plist
# rendered by the account the daemon will run as. This is the one step of a
# native-agent install that needs root, and it is the same step for the agent
# (install.sh renders its plist and prints this command) and for the runner
# that redeploys it (tools/ci/macos/README.md).
#
# Trust boundary. The plist comes from a service account's home, and that
# account executes code it did not write (it runs coding-agent jobs), so
# nothing it can write may be trusted to decide what root does:
#
#   - This script refuses to run unless the file it was invoked from is owned
#     by root or by the administrator invoking sudo, and is not writable by
#     anyone else. Run it from a checkout you own — never from a copy under
#     the service account's home, and never from a checkout that account owns.
#   - The plist is copied to a root-owned temporary file before anything
#     reads it, so what is validated is what gets installed.
#   - The daemon must run as the account that owns the plist, with that
#     account's primary group: UserName is required and must name the owner,
#     GroupName if present must be the owner's primary group, and the owner
#     must not be root. A plist can therefore only register a service running
#     as whoever wrote it — which is what a user could already do with a
#     LaunchAgent, minus the login-session requirement.
#   - Only the keys a plain service needs are accepted (see ALLOWED_KEYS).
#     launchd opens the StandardOutPath/StandardErrorPath files as the
#     service user, so those are not a root write path and are allowed.
#
# What it does, in order:
#   1. Checks its own provenance (above), lints the plist and reads its Label;
#      the file under /Library/LaunchDaemons is named after the label.
#   2. If a service with that label is already registered, boots it out and
#      waits for launchd to stop listing it. bootout returns before the
#      service is gone, and a bootstrap that lands while the old process is
#      still exiting would briefly run two of them — for the agent, two
#      processes with the same node identity on the controller. If the old
#      service is still listed after the wait, this script fails rather than
#      install beside it.
#   3. Installs the plist as root:wheel, mode 644, and bootstraps it.
#   4. Prints the service's state and pid.
#
# Usage:
#   sudo /path/to/your/checkout/flowtree/runtime/agent/macos/register-daemon.sh <rendered plist>
#
# Exit codes:
#   0 - the service is registered from the given definition
#   1 - not root, the script or plist failed a trust check, the plist is
#       missing or invalid, or the previous instance would not stop

set -euo pipefail

DAEMONS_DIR="/Library/LaunchDaemons"
STOP_TIMEOUT_SECONDS=30
ALLOWED_KEYS="Label UserName GroupName ProgramArguments EnvironmentVariables WorkingDirectory RunAtLoad KeepAlive ThrottleInterval StandardOutPath StandardErrorPath ProcessType Nice"

if [ "$#" -ne 1 ]; then
    echo "Usage: sudo $0 <rendered plist>" >&2
    exit 1
fi
SOURCE="$1"

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run as root: sudo $0 ${SOURCE}" >&2
    exit 1
fi
for cmd in plutil jq launchctl; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not on PATH." >&2
        exit 1
    fi
done

# ── Provenance of this script ──────────────────────────────────────
#
# A script root runs must not be writable by the account whose plist it is
# validating, or the validation is theirs to remove. Owner root or the
# administrator behind sudo; no group or world write bit.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SELF_OWNER_UID="$(stat -f '%u' "${SELF}")"
SELF_MODE="$(stat -f '%Lp' "${SELF}")"
ADMIN_UID="${SUDO_UID:-0}"
if [ "${SELF_OWNER_UID}" != "0" ] && [ "${SELF_OWNER_UID}" != "${ADMIN_UID}" ]; then
    echo "ERROR: ${SELF} is owned by uid ${SELF_OWNER_UID}, not root or the invoking administrator." >&2
    echo "  Root must not execute a file the service account can edit. Run this script" >&2
    echo "  from a checkout you own, not from ${SELF}." >&2
    exit 1
fi
if [ $(( 8#${SELF_MODE} & 8#022 )) -ne 0 ]; then
    echo "ERROR: ${SELF} is group- or world-writable (mode ${SELF_MODE})." >&2
    exit 1
fi

# ── A private copy of the plist ────────────────────────────────────
#
# Validated and installed from the same bytes: the source stays under the
# owner's control and could change between a check and the install.

if [ ! -f "${SOURCE}" ] || [ -L "${SOURCE}" ]; then
    echo "ERROR: ${SOURCE} is not a regular file." >&2
    exit 1
fi
OWNER_UID="$(stat -f '%u' "${SOURCE}")"
OWNER_NAME="$(id -un "${OWNER_UID}")"
OWNER_GROUP="$(id -gn "${OWNER_UID}")"
if [ "${OWNER_UID}" = "0" ]; then
    echo "ERROR: ${SOURCE} is owned by root; this script registers services for service accounts." >&2
    exit 1
fi

STAGED="$(mktemp -t register-daemon)"
trap 'rm -f "${STAGED}"' EXIT
cp "${SOURCE}" "${STAGED}"
chmod 600 "${STAGED}"
plutil -lint -s "${STAGED}"

# ── Content checks ─────────────────────────────────────────────────

plist_keys() {
    plutil -convert json -o - "${STAGED}" | jq -r 'keys[]'
}
plist_string() {
    /usr/libexec/PlistBuddy -c "Print :$1" "${STAGED}" 2>/dev/null || true
}

for key in $(plist_keys); do
    case " ${ALLOWED_KEYS} " in
        *" ${key} "*) ;;
        *)
            echo "ERROR: ${SOURCE} sets '${key}', which this script does not accept." >&2
            echo "  Accepted keys: ${ALLOWED_KEYS}" >&2
            exit 1
            ;;
    esac
done

LABEL="$(plist_string Label)"
if [ -z "${LABEL}" ]; then
    echo "ERROR: ${SOURCE} has no Label." >&2
    exit 1
fi
case "${LABEL}" in
    *[!A-Za-z0-9._-]*)
        echo "ERROR: Label '${LABEL}' contains characters other than letters, digits, '.', '_' and '-'." >&2
        exit 1
        ;;
esac

USER_NAME="$(plist_string UserName)"
if [ "${USER_NAME}" != "${OWNER_NAME}" ]; then
    echo "ERROR: ${SOURCE} is owned by ${OWNER_NAME} but its UserName is '${USER_NAME:-<missing>}'." >&2
    echo "  A daemon registered from an account's plist runs as that account, nothing else." >&2
    exit 1
fi
GROUP_NAME="$(plist_string GroupName)"
if [ -n "${GROUP_NAME}" ] && [ "${GROUP_NAME}" != "${OWNER_GROUP}" ]; then
    echo "ERROR: ${SOURCE} sets GroupName '${GROUP_NAME}'; ${OWNER_NAME}'s primary group is ${OWNER_GROUP}." >&2
    exit 1
fi

SERVICE="system/${LABEL}"
TARGET="${DAEMONS_DIR}/${LABEL}.plist"

# ── Replace or register ────────────────────────────────────────────

registered() {
    launchctl print "${SERVICE}" >/dev/null 2>&1
}

if registered; then
    echo "Stopping the registered ${SERVICE}..."
    launchctl bootout "${SERVICE}"
    for _ in $(seq 1 "${STOP_TIMEOUT_SECONDS}"); do
        if ! registered; then
            break
        fi
        sleep 1
    done
    if registered; then
        echo "ERROR: ${SERVICE} is still listed ${STOP_TIMEOUT_SECONDS}s after bootout." >&2
        echo "  Not installing beside it. Inspect with: launchctl print ${SERVICE}" >&2
        exit 1
    fi
fi

install -o root -g wheel -m 644 "${STAGED}" "${TARGET}"
echo "Installed ${TARGET} (runs as ${USER_NAME})"
launchctl bootstrap system "${TARGET}"
echo "Registered ${SERVICE}"
launchctl print "${SERVICE}" | awk '/^[[:space:]]*(state|pid) = /{print "  " $0}'
