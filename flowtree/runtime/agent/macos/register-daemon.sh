#!/usr/bin/env bash
#
# Register (or replace) a LaunchDaemon in the system domain from a rendered
# plist. This is the one step of a native-agent install that needs root, and
# it is the same step for the agent (install.sh renders its plist and prints
# this command) and for the runner that redeploys it (tools/ci/macos/README.md).
#
# What it does, in order:
#   1. Lints the plist and reads its Label; the file under
#      /Library/LaunchDaemons is named after the label, as launchd expects.
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
#   sudo register-daemon.sh <rendered plist>
#
# Exit codes:
#   0 - the service is registered from the given definition
#   1 - not root, the plist is missing or invalid, or the previous instance
#       would not stop

set -euo pipefail

DAEMONS_DIR="/Library/LaunchDaemons"
STOP_TIMEOUT_SECONDS=30

if [ "$#" -ne 1 ]; then
    echo "Usage: sudo $0 <rendered plist>" >&2
    exit 1
fi
SOURCE="$1"

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run as root: sudo $0 ${SOURCE}" >&2
    exit 1
fi
if [ ! -f "${SOURCE}" ]; then
    echo "ERROR: ${SOURCE} does not exist." >&2
    exit 1
fi
plutil -lint -s "${SOURCE}"

LABEL="$(/usr/libexec/PlistBuddy -c 'Print :Label' "${SOURCE}")"
if [ -z "${LABEL}" ]; then
    echo "ERROR: ${SOURCE} has no Label." >&2
    exit 1
fi
SERVICE="system/${LABEL}"
TARGET="${DAEMONS_DIR}/${LABEL}.plist"

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

install -o root -g wheel -m 644 "${SOURCE}" "${TARGET}"
echo "Installed ${TARGET}"
launchctl bootstrap system "${TARGET}"
echo "Registered ${SERVICE}"
launchctl print "${SERVICE}" | awk '/^[[:space:]]*(state|pid) = /{print "  " $0}'
