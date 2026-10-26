#!/bin/bash
#
# Install the fleet metrics collector on this Mac, end to end, and register it
# as a LaunchDaemon. One command for a new host:
#
#   tools/fleet/launchd/install.sh --store-from michael@mac-studio
#
# Run as the account the collector will run as (never root; never the account
# the runners run as — the store credential must not be readable by a CI
# job). It:
#
#   1. Checks the prerequisites (python3 with venv, the register helper).
#   2. Renders the LaunchDaemon plists and creates the private interpreter
#      (tools/fleet/launchd/render.sh).
#   3. Puts the store credential in place: copies FLEET_HOME/store-url from
#      --store-from over the tailnet unless the file already exists.
#   4. Proves the path before anything is daemonised: one sample into the
#      central store, read back with the CLI. A store or credential problem
#      fails here, in the foreground, not in a KeepAlive loop.
#   5. Registers the collector daemon through
#      flowtree/runtime/agent/macos/register-daemon.sh (this is the one sudo
#      step; you are prompted for your password) and shows its state.
#
# The poller is not installed unless --with-poller is given: it runs once
# per fleet, on the store host, and needs the GitHub token file there.
#
# Re-running is safe and is how an existing install is updated: the venv and
# store-url are kept, the plists are re-rendered, and a registered daemon is
# replaced (register-daemon.sh waits for the old one to stop).
#
# Options:
#   --store-from USER@HOST   copy the store credential from that host's
#                            FLEET_HOME/store-url with scp (a tailnet name
#                            such as michael@mac-studio); not needed when
#                            FLEET_HOME/store-url already exists here
#   --with-poller            also register the GitHub poller (store host only)
#   --no-register            do everything except the sudo registration step;
#                            prints the commands instead
#
# Environment: the same variables render.sh reads (FLEET_HOME, FLEET_HOST,
# FLEET_DISK_PATH, FLEET_REPO, FLEET_TOKEN_FILE, FLEET_PYTHON).

set -euo pipefail

STORE_FROM=""
WITH_POLLER=false
REGISTER=true
while [ "$#" -gt 0 ]; do
    case "$1" in
        --store-from)
            if [ "$#" -lt 2 ]; then
                echo "ERROR: --store-from needs a USER@HOST value" >&2
                exit 1
            fi
            STORE_FROM="$2"
            shift
            ;;
        --store-from=*) STORE_FROM="${1#*=}" ;;
        --with-poller) WITH_POLLER=true ;;
        --no-register) REGISTER=false ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument '$1'" >&2
            exit 1
            ;;
    esac
    shift
done

if [ "$(id -u)" -eq 0 ]; then
    echo "ERROR: run this as the account the collector will run as, not as root." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
REGISTER_SCRIPT="${CHECKOUT}/flowtree/runtime/agent/macos/register-daemon.sh"
FLEET_HOME="${FLEET_HOME:-${HOME}/fleet}"
FLEET_HOST="${FLEET_HOST:-$(scutil --get LocalHostName 2>/dev/null | tr '[:upper:]' '[:lower:]' || hostname -s)}"
export FLEET_HOME FLEET_HOST

# ── 1. Prerequisites ───────────────────────────────────────────────

if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import venv' 2>/dev/null; then
    echo "ERROR: python3 with the venv module is required (brew install python)." >&2
    exit 1
fi
if [ ! -x "${REGISTER_SCRIPT}" ]; then
    echo "ERROR: ${REGISTER_SCRIPT} is missing; is this a complete checkout?" >&2
    exit 1
fi
RUNNER_ACCOUNT="$(ps -axo user=,comm= 2>/dev/null | awk '$2 ~ /Runner\.Listener$/ {print $1; exit}' || true)"
if [ -n "${RUNNER_ACCOUNT}" ] && [ "${RUNNER_ACCOUNT}" = "$(id -un)" ]; then
    echo "ERROR: a GitHub Actions runner on this host runs as $(id -un), the account you are installing as." >&2
    echo "  The collector's store credential must not be readable by CI jobs; install as a different account." >&2
    exit 1
fi

echo "Installing the fleet collector as $(id -un) on ${FLEET_HOST}"
echo "  Checkout:   ${CHECKOUT}"
echo "  Fleet home: ${FLEET_HOME}"
echo ""

# ── 2. Render (also creates the venv) ──────────────────────────────

"${SCRIPT_DIR}/render.sh" > /dev/null
PYTHON="${FLEET_PYTHON:-${FLEET_HOME}/venv/bin/python3}"

# ── 3. Store credential ────────────────────────────────────────────

STORE_URL_FILE="${FLEET_HOME}/store-url"
if [ ! -s "${STORE_URL_FILE}" ]; then
    if [ -z "${STORE_FROM}" ]; then
        echo "ERROR: ${STORE_URL_FILE} does not exist and no --store-from host was given." >&2
        echo "  Either pass --store-from USER@HOST (a host that already has it, e.g. michael@mac-studio)" >&2
        echo "  or create it yourself: postgresql://fleet:<password>@<store tailnet address>:5432/fleet, mode 600." >&2
        exit 1
    fi
    echo "Copying the store credential from ${STORE_FROM}..."
    scp -q "${STORE_FROM}:fleet/store-url" "${STORE_URL_FILE}"
fi
chmod 600 "${STORE_URL_FILE}"

# ── 4. Prove the path ──────────────────────────────────────────────

echo "Taking one sample into the central store..."
PYTHONPATH="${CHECKOUT}" "${PYTHON}" -m tools.fleet.collector \
    --host "${FLEET_HOST}" --log-dir "${FLEET_HOME}/logs" --agent-domain-target system \
    --disk-path "${FLEET_DISK_PATH:-/}" --store-url-file "${STORE_URL_FILE}" --once
echo "Reading it back:"
STATUS="$(PYTHONPATH="${CHECKOUT}" "${PYTHON}" -m tools.fleet.cli --db-url-file "${STORE_URL_FILE}" status --host "${FLEET_HOST}")"
printf '%s\n' "${STATUS}" | sed 's/^/  /'
if ! printf '%s\n' "${STATUS}" | grep -q "^${FLEET_HOST} "; then
    echo "ERROR: the sample did not come back from the store; not registering the daemon." >&2
    exit 1
fi
echo ""

# ── 5. Register ────────────────────────────────────────────────────

LABELS="com.almostrealism.fleet-collector"
if [ "${WITH_POLLER}" = true ]; then
    LABELS="${LABELS} com.almostrealism.fleet-poller"
fi

if [ "${REGISTER}" != true ]; then
    echo "Not registering (--no-register). As an administrator:"
    for label in ${LABELS}; do
        printf '  sudo %q %q %q\n' "${REGISTER_SCRIPT}" "${label}" "${FLEET_HOME}/launchd/${label}.plist"
    done
    exit 0
fi

for label in ${LABELS}; do
    echo "Registering ${label} (sudo)..."
    sudo "${REGISTER_SCRIPT}" "${label}" "${FLEET_HOME}/launchd/${label}.plist"
done
echo ""
echo "Done. Logs: ${FLEET_HOME}/logs/collector.log"
if [ "${WITH_POLLER}" = true ]; then
    echo "      ${FLEET_HOME}/logs/poller.log"
fi
