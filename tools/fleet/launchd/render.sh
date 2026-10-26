#!/bin/bash
#
# Render the fleet collector and poller LaunchDaemon templates for this host
# and stage everything the two services need under FLEET_HOME, as the
# account they will run as. Registering the rendered plists is the one root
# step, done afterwards with flowtree/runtime/agent/macos/register-daemon.sh
# from a checkout the administrator owns (the same helper, and the same
# trust checks, as the native agent's daemon).
#
# Run as the fleet account (never as root):
#   tools/fleet/launchd/render.sh
#
# Environment (all optional):
#   FLEET_HOME    where the services keep logs and read credentials
#                 (default: $HOME/fleet). Must hold, mode 600 and owned by
#                 this account, a `store-url` file with the Postgres URL —
#                 postgresql://fleet:<password>@<tailnet address>:5432/fleet
#                 — before the services are started.
#   FLEET_PYTHON  interpreter to run the services with (default:
#                 $FLEET_HOME/venv/bin/python3, created here if absent, with
#                 psycopg and PyYAML installed into it).
#   FLEET_HOST    host label recorded in every sample (default: the
#                 LocalHostName, lower-cased — NOT platform.node(), which
#                 on a Tailscale host can return the FQDN plus junk).
#   FLEET_REPO    repository the poller reads (default: almostrealism/common).
#   FLEET_RUNNERS_ORG
#                 organization whose runners the poller inventories besides
#                 the repository's (default: the repository's owner) — a
#                 runner registered org-wide is not in the repository's list.
#   FLEET_TOKEN_FILE
#                 the read-only GitHub token file (default:
#                 /Users/Shared/flowtree/secrets/fleet-github-token).
#   FLEET_DISK_PATH
#                 filesystem the collector reports disk usage for
#                 (default: /).
#
# What this script deliberately does NOT do: write any credential. The
# store URL and the token are the operator's, created by hand at mode 600.

set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then
    echo "ERROR: run this as the account the services will run as, not as root." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
FLEET_HOME="${FLEET_HOME:-${HOME}/fleet}"
FLEET_PYTHON="${FLEET_PYTHON:-${FLEET_HOME}/venv/bin/python3}"
FLEET_HOST="${FLEET_HOST:-$(scutil --get LocalHostName 2>/dev/null | tr '[:upper:]' '[:lower:]' || hostname -s)}"
FLEET_REPO="${FLEET_REPO:-almostrealism/common}"
FLEET_RUNNERS_ORG="${FLEET_RUNNERS_ORG:-${FLEET_REPO%%/*}}"
FLEET_TOKEN_FILE="${FLEET_TOKEN_FILE:-/Users/Shared/flowtree/secrets/fleet-github-token}"
FLEET_DISK_PATH="${FLEET_DISK_PATH:-/}"

mkdir -p "${FLEET_HOME}/logs" "${FLEET_HOME}/launchd"

# A private interpreter with the one non-stdlib dependency, so nothing the
# services need is installed into (or picked up from) a shared Python.
if [ ! -x "${FLEET_PYTHON}" ]; then
    if [ "${FLEET_PYTHON}" != "${FLEET_HOME}/venv/bin/python3" ]; then
        echo "ERROR: FLEET_PYTHON=${FLEET_PYTHON} is not executable." >&2
        exit 1
    fi
    echo "Creating ${FLEET_HOME}/venv..."
    python3 -m venv "${FLEET_HOME}/venv"
    "${FLEET_HOME}/venv/bin/pip" install --quiet --upgrade pip
    "${FLEET_HOME}/venv/bin/pip" install --quiet 'psycopg[binary]' pyyaml
fi
if ! "${FLEET_PYTHON}" -c 'import psycopg' 2>/dev/null; then
    echo "ERROR: ${FLEET_PYTHON} cannot import psycopg; install it with: ${FLEET_PYTHON} -m pip install 'psycopg[binary]'" >&2
    exit 1
fi
# The poller reads each run's workflow file to tell runner wait from time
# blocked behind an upstream job; without PyYAML it still runs, but every
# queue-wait panel stays empty. A venv this script created gets it here so
# an install that predates the dependency is brought up to date.
if ! "${FLEET_PYTHON}" -c 'import yaml' 2>/dev/null; then
    if [ "${FLEET_PYTHON}" = "${FLEET_HOME}/venv/bin/python3" ]; then
        echo "Installing PyYAML into ${FLEET_HOME}/venv..."
        "${FLEET_HOME}/venv/bin/pip" install --quiet pyyaml
    else
        echo "WARNING: ${FLEET_PYTHON} cannot import yaml; the poller will not resolve job dependencies. Install it with: ${FLEET_PYTHON} -m pip install pyyaml" >&2
    fi
fi

# The values land inside XML through sed, so escape for both.
plist_value() {
    printf '%s' "$1" \
        | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
        | sed -e 's/[\\&|]/\\&/g'
}

for label in com.almostrealism.fleet-collector com.almostrealism.fleet-poller; do
    sed -e "s|@FLEET_USER@|$(plist_value "$(id -un)")|g" \
        -e "s|@FLEET_USER_HOME@|$(plist_value "${HOME}")|g" \
        -e "s|@FLEET_HOME@|$(plist_value "${FLEET_HOME}")|g" \
        -e "s|@PYTHON@|$(plist_value "${FLEET_PYTHON}")|g" \
        -e "s|@CHECKOUT@|$(plist_value "${CHECKOUT}")|g" \
        -e "s|@HOST@|$(plist_value "${FLEET_HOST}")|g" \
        -e "s|@REPO@|$(plist_value "${FLEET_REPO}")|g" \
        -e "s|@RUNNERS_ORG@|$(plist_value "${FLEET_RUNNERS_ORG}")|g" \
        -e "s|@TOKEN_FILE@|$(plist_value "${FLEET_TOKEN_FILE}")|g" \
        -e "s|@DISK_PATH@|$(plist_value "${FLEET_DISK_PATH}")|g" \
        "${SCRIPT_DIR}/${label}.plist" > "${FLEET_HOME}/launchd/${label}.plist"
    plutil -lint -s "${FLEET_HOME}/launchd/${label}.plist"
done

echo "Rendered for $(id -un) on ${FLEET_HOST}:"
echo "  ${FLEET_HOME}/launchd/com.almostrealism.fleet-collector.plist"
echo "  ${FLEET_HOME}/launchd/com.almostrealism.fleet-poller.plist"
echo ""
if [ ! -f "${FLEET_HOME}/store-url" ]; then
    echo "Before registering, create the store URL file (mode 600):"
    echo "  printf 'postgresql://fleet:%s@<tailnet address>:5432/fleet\\n' \"\$(cat /Users/Shared/flowtree/secrets/fleet-db-password)\" > ${FLEET_HOME}/store-url"
    echo "  chmod 600 ${FLEET_HOME}/store-url"
fi
if [ ! -f "${FLEET_TOKEN_FILE}" ]; then
    echo "The poller also needs the GitHub token at ${FLEET_TOKEN_FILE} (mode 600, owned by $(id -un))."
fi
echo "Then, as an administrator, from a checkout you own:"
for label in com.almostrealism.fleet-collector com.almostrealism.fleet-poller; do
    printf '  sudo <your checkout>/flowtree/runtime/agent/macos/register-daemon.sh %q %q\n' "${label}" "${FLEET_HOME}/launchd/${label}.plist"
done
