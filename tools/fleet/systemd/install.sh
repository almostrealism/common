#!/bin/bash
#
# Install the fleet metrics collector on a Linux runner host, end to end,
# as a systemd service. One command for a new host:
#
#   sudo tools/fleet/systemd/install.sh --store-from michael@mac-studio
#
# The Linux counterpart of tools/fleet/launchd/install.sh, with the
# differences a Linux runner host imposes:
#
#   * The runners there are containers (Docker Compose on the CPU fleet,
#     rootless podman on the ROCm fleet) but their processes are ordinary
#     host processes, so the collector's process-tree attribution works
#     unchanged from the host. Nothing runs inside a container.
#   * The collector gets its own system account (FLEET_USER, default
#     `fleet`, created here if missing, no login shell) because the store
#     credential must not be readable by the account a CI job runs as.
#   * The code the service runs is a root-owned snapshot of tools/fleet
#     under FLEET_HOME/app, not a checkout, so neither the service account
#     nor the runner account can change it. Re-run this script to update it.
#
# Run with sudo from a checkout. It:
#
#   1. Checks the prerequisites (python3 with venv; systemd).
#   2. Creates the service account and FLEET_HOME (root-owned, mode 711 —
#      traversable but not writable by the service account; its logs and
#      venv subdirectories are separately owned by that account).
#   3. Snapshots tools/fleet into FLEET_HOME/app and creates the private
#      interpreter (a venv with psycopg) as the service account.
#   4. Puts the store credential in place: --store-url-file copies a file
#      you already have; --store-from copies FLEET_HOME/store-url from a host
#      that has it, over the tailnet, as the user who invoked sudo.
#   5. Proves the path before anything is daemonised: one sample into the
#      central store, as the service account, read back with the CLI.
#   6. Renders the unit, enables and starts it, and shows its state.
#
# Options:
#   --store-from USER@HOST   scp the credential from that host's ~/fleet/store-url
#   --store-url-file FILE    copy the credential from a local file instead
#   --host LABEL             host label in every sample (default: hostname -s, lower-cased)
#   --disk-path PATH         filesystem to report disk usage for — the runners'
#                            work volume, e.g. the Docker data root (default: /)
#   --user NAME              service account (default: fleet)
#   --no-start               install everything, render the unit, but do not
#                            enable or start it
#
# Exit codes: 0 on success; 1 when a prerequisite, the credential, the test
# sample, or the service start fails.

set -euo pipefail

STORE_FROM=""
STORE_URL_SRC=""
FLEET_USER="fleet"
FLEET_HOST=""
DISK_PATH="/"
START=true
while [ "$#" -gt 0 ]; do
    case "$1" in
        --store-from)     STORE_FROM="${2:?--store-from needs USER@HOST}"; shift ;;
        --store-from=*)   STORE_FROM="${1#*=}" ;;
        --store-url-file) STORE_URL_SRC="${2:?--store-url-file needs a path}"; shift ;;
        --host)           FLEET_HOST="${2:?--host needs a label}"; shift ;;
        --disk-path)      DISK_PATH="${2:?--disk-path needs a path}"; shift ;;
        --user)           FLEET_USER="${2:?--user needs a name}"; shift ;;
        --no-start)       START=false ;;
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

if [ "${FLEET_USER}" = "root" ]; then
    echo "ERROR: --user must not be root; the service must run as a dedicated, unprivileged account." >&2
    exit 1
fi
if ! [[ "${FLEET_USER}" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]; then
    echo "ERROR: --user must be a valid system account name (e.g. 'fleet')." >&2
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run with sudo: sudo $0 ..." >&2
    exit 1
fi
if [ "$(uname -s)" != "Linux" ]; then
    echo "ERROR: this installer is for Linux hosts; use tools/fleet/launchd/install.sh on macOS." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
FLEET_HOME="/var/lib/${FLEET_USER}"
APP_DIR="${FLEET_HOME}/app"
PYTHON="${FLEET_HOME}/venv/bin/python3"
STORE_URL_FILE="${FLEET_HOME}/store-url"
UNIT_FILE="/etc/systemd/system/fleet-collector.service"
FLEET_HOST="${FLEET_HOST:-$(hostname -s | tr '[:upper:]' '[:lower:]')}"

# ── 1. Prerequisites ───────────────────────────────────────────────

if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import venv, ensurepip' 2>/dev/null; then
    echo "ERROR: python3 with venv is required (Debian/Ubuntu: apt install python3-venv)." >&2
    exit 1
fi
if ! command -v systemctl >/dev/null 2>&1; then
    echo "ERROR: systemd is required." >&2
    exit 1
fi
if [ -z "${STORE_FROM}" ] && [ -z "${STORE_URL_SRC}" ] && [ ! -s "${STORE_URL_FILE}" ]; then
    echo "ERROR: no store credential: pass --store-from USER@HOST or --store-url-file FILE" >&2
    echo "  (the file holds postgresql://fleet:<password>@<store tailnet address>:5432/fleet)." >&2
    exit 1
fi
RUNNER_ACCOUNT="$(ps -eo user=,comm= 2>/dev/null | awk '$2 == "Runner.Listener" {print $1; exit}' || true)"
if [ -n "${RUNNER_ACCOUNT}" ] && [ "${RUNNER_ACCOUNT}" = "${FLEET_USER}" ]; then
    echo "ERROR: a GitHub Actions runner on this host runs as ${FLEET_USER}; the collector must not." >&2
    exit 1
fi

echo "Installing the fleet collector as ${FLEET_USER} on ${FLEET_HOST}"
echo "  Checkout:   ${CHECKOUT}"
echo "  Fleet home: ${FLEET_HOME}"
echo "  Disk path:  ${DISK_PATH}"
echo ""

# ── 2. Service account and home ────────────────────────────────────

if ! id "${FLEET_USER}" >/dev/null 2>&1; then
    echo "Creating system account ${FLEET_USER}..."
    useradd --system --home-dir "${FLEET_HOME}" --create-home --shell /usr/sbin/nologin \
        --comment "Runner fleet metrics collector" "${FLEET_USER}"
fi
# FLEET_HOME itself stays root-owned and not writable by FLEET_USER, so the
# service account can traverse into it (needed to reach APP_DIR, its own
# logs/venv subdirectories, and the store credential) but cannot remove or
# rename anything directly under it — in particular it cannot replace
# APP_DIR, which is what makes that snapshot tamper-proof against a
# compromised service account. The subdirectories the service account must
# write to are owned by it individually.
chown root:root "${FLEET_HOME}"
chmod 711 "${FLEET_HOME}"
mkdir -p "${FLEET_HOME}/logs" "${FLEET_HOME}/venv"
chown "${FLEET_USER}:${FLEET_USER}" "${FLEET_HOME}/logs" "${FLEET_HOME}/venv"
chmod 700 "${FLEET_HOME}/logs" "${FLEET_HOME}/venv"

# ── 3. Code snapshot and interpreter ───────────────────────────────

# Root-owned; FLEET_HOME's own permissions (above) are what stop the service
# account from replacing this directory. World-readable so the service
# account (or anyone else who can traverse into FLEET_HOME) can still import
# it. A fresh copy each run, so an update is a re-run.
rm -rf "${APP_DIR}"
mkdir -p "${APP_DIR}/tools/fleet"
cp "${CHECKOUT}"/tools/fleet/*.py "${APP_DIR}/tools/fleet/"
touch "${APP_DIR}/tools/__init__.py"
cp "${CHECKOUT}/tools/fleet/README.md" "${APP_DIR}/tools/fleet/README.md"
chmod -R a+rX "${APP_DIR}"
chmod 755 "${APP_DIR}"

if [ ! -x "${PYTHON}" ]; then
    echo "Creating ${FLEET_HOME}/venv..."
    runuser -u "${FLEET_USER}" -- python3 -m venv "${FLEET_HOME}/venv"
    runuser -u "${FLEET_USER}" -- "${FLEET_HOME}/venv/bin/pip" install --quiet --upgrade pip
fi
if ! runuser -u "${FLEET_USER}" -- "${PYTHON}" -c 'import psycopg' 2>/dev/null; then
    echo "Installing psycopg into the venv..."
    runuser -u "${FLEET_USER}" -- "${FLEET_HOME}/venv/bin/pip" install --quiet 'psycopg[binary]'
fi

# ── 4. Store credential ────────────────────────────────────────────

if [ -n "${STORE_URL_SRC}" ]; then
    install -o "${FLEET_USER}" -g "${FLEET_USER}" -m 600 "${STORE_URL_SRC}" "${STORE_URL_FILE}"
elif [ -n "${STORE_FROM}" ] && [ ! -s "${STORE_URL_FILE}" ]; then
    # scp as the human behind sudo, whose keys and known_hosts reach the
    # other host; root's do not.
    INVOKER="${SUDO_USER:-}"
    if [ -z "${INVOKER}" ]; then
        echo "ERROR: --store-from needs to run under sudo from a user account (SUDO_USER is unset)." >&2
        exit 1
    fi
    TMP="$(mktemp)"
    chown "${INVOKER}" "${TMP}"
    echo "Copying the store credential from ${STORE_FROM} as ${INVOKER}..."
    runuser -u "${INVOKER}" -- scp -q "${STORE_FROM}:fleet/store-url" "${TMP}"
    install -o "${FLEET_USER}" -g "${FLEET_USER}" -m 600 "${TMP}" "${STORE_URL_FILE}"
    rm -f "${TMP}"
fi
chown "${FLEET_USER}:${FLEET_USER}" "${STORE_URL_FILE}"
chmod 600 "${STORE_URL_FILE}"

# ── 5. Prove the path ──────────────────────────────────────────────

echo "Taking one sample into the central store as ${FLEET_USER}..."
runuser -u "${FLEET_USER}" -- env PYTHONPATH="${APP_DIR}" "${PYTHON}" -m tools.fleet.collector \
    --host "${FLEET_HOST}" --log-dir "${FLEET_HOME}/logs" --disk-path "${DISK_PATH}" \
    --store-url-file "${STORE_URL_FILE}" --once
echo "Reading it back:"
STATUS="$(runuser -u "${FLEET_USER}" -- env PYTHONPATH="${APP_DIR}" "${PYTHON}" -m tools.fleet.cli \
    --db-url-file "${STORE_URL_FILE}" status --host "${FLEET_HOST}")"
printf '%s\n' "${STATUS}" | sed 's/^/  /'
if ! printf '%s\n' "${STATUS}" | grep -q "^${FLEET_HOST} "; then
    echo "ERROR: the sample did not come back from the store; not starting the service." >&2
    exit 1
fi
echo ""

# ── 6. Unit ────────────────────────────────────────────────────────

# Values land in a unit file, where backslash and a leading space would be
# read as escapes; the placeholders are single tokens, so escape only what
# sed's replacement reads.
unit_value() {
    printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'
}
sed -e "s|@FLEET_USER@|$(unit_value "${FLEET_USER}")|g" \
    -e "s|@FLEET_HOME@|$(unit_value "${FLEET_HOME}")|g" \
    -e "s|@APP_DIR@|$(unit_value "${APP_DIR}")|g" \
    -e "s|@PYTHON@|$(unit_value "${PYTHON}")|g" \
    -e "s|@HOST@|$(unit_value "${FLEET_HOST}")|g" \
    -e "s|@DISK_PATH@|$(unit_value "${DISK_PATH}")|g" \
    "${SCRIPT_DIR}/fleet-collector.service" > "${UNIT_FILE}"
chmod 644 "${UNIT_FILE}"
systemctl daemon-reload

if [ "${START}" != true ]; then
    echo "Installed ${UNIT_FILE}; not started (--no-start). Start with:"
    echo "  sudo systemctl enable --now fleet-collector"
    exit 0
fi
systemctl enable --now fleet-collector
# A re-run must restart onto the new snapshot; enable --now alone leaves a
# running service on the old one.
systemctl restart fleet-collector
sleep 2
systemctl --no-pager --lines=5 status fleet-collector || true
echo ""
echo "Done. Logs: journalctl -u fleet-collector -f ; JSONL under ${FLEET_HOME}/logs"
