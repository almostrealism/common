#!/usr/bin/env bash
set -euo pipefail

# ─── Build the image and install the runner as a systemd user service ────
#
# Run as the SERVICE ACCOUNT (not root, not your admin user), from this
# directory, after setup-host.sh has prepared the host:
#
#   sudo -iu ar-ci
#   cd ~/common/tools/ci/rocm
#   ./install-runner.sh
#
# Idempotent — re-run it to pick up a changed Dockerfile, entrypoint, or .env.
#
# Options:
#   --no-build    Install/refresh the unit only; do not rebuild the image
#   --no-start    Install everything but leave the service stopped
#   -h, --help    Show this help
#
# This fleet is Quadlet-managed rather than Compose-managed. See the comment at
# the top of ar-ci-cl-runner.container for why — briefly, `docker compose`
# cannot express GroupAdd=keep-groups, without which the container has no GPU.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UNIT_NAME="ar-ci-cl-runner"
UNIT_FILE="${UNIT_NAME}.container"
SERVICE="${UNIT_NAME}.service"
IMAGE="ar-ci-cl-runner:latest"
QUADLET_DIR="${HOME}/.config/containers/systemd"
ENV_DIR="${HOME}/.config/ar-ci-cl"
ENV_TARGET="${ENV_DIR}/runner.env"

DO_BUILD=1
DO_START=1

usage() { sed -n '4,22p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) DO_BUILD=0; shift ;;
        --no-start) DO_START=0; shift ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# ---------- Preflight ----------

if [ "$(id -u)" -eq 0 ]; then
    echo "ERROR: do not run this as root." >&2
    echo "  The runner is rootless and must be installed under the service" >&2
    echo "  account's own systemd user instance:  sudo -iu ar-ci" >&2
    exit 1
fi

if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    echo "ERROR: XDG_RUNTIME_DIR is not set." >&2
    echo "  Rootless podman and 'systemctl --user' both need it. If you got here" >&2
    echo "  via 'runuser' or a non-login sudo, use 'sudo -iu $(id -un)' instead," >&2
    echo "  or export XDG_RUNTIME_DIR=/run/user/$(id -u)." >&2
    exit 1
fi

for cmd in podman systemctl; do
    if ! command -v "${cmd}" > /dev/null 2>&1; then
        echo "ERROR: ${cmd} is not available." >&2
        exit 1
    fi
done

if [ ! -f "${SCRIPT_DIR}/.env" ]; then
    echo "ERROR: ${SCRIPT_DIR}/.env not found."
    echo "  cp .env.example .env  and fill in GITHUB_PAT."
    exit 1
fi

if grep -q '^GITHUB_PAT=ghp_your_token_here' "${SCRIPT_DIR}/.env"; then
    echo "ERROR: .env still holds the placeholder GITHUB_PAT."
    exit 1
fi

# ---------- Build ----------

if [ "${DO_BUILD}" = 1 ]; then
    echo "Building ${IMAGE} ..."
    # ROCM_OPENCL_LIB is a build argument because the ICD is written into the
    # image; changing it requires a rebuild, not just a restart.
    BUILD_ARGS=()
    ROCM_OPENCL_LIB=$(sed -n 's/^ROCM_OPENCL_LIB=//p' "${SCRIPT_DIR}/.env" | tail -1)
    if [ -n "${ROCM_OPENCL_LIB}" ]; then
        BUILD_ARGS+=(--build-arg "ROCM_OPENCL_LIB=${ROCM_OPENCL_LIB}")
        echo "  ICD will name: ${ROCM_OPENCL_LIB}"
    fi
    podman build -t "${IMAGE}" "${BUILD_ARGS[@]}" "${SCRIPT_DIR}"
    echo
fi

# ---------- Install configuration ----------

# The env file holds a token, so it is installed outside the checkout with
# restrictive permissions rather than read from the working tree.
mkdir -p "${ENV_DIR}"
install -m 600 "${SCRIPT_DIR}/.env" "${ENV_TARGET}"
echo "Installed ${ENV_TARGET} (mode 600)"

mkdir -p "${QUADLET_DIR}"
install -m 644 "${SCRIPT_DIR}/${UNIT_FILE}" "${QUADLET_DIR}/${UNIT_FILE}"
echo "Installed ${QUADLET_DIR}/${UNIT_FILE}"
echo

# ---------- Activate ----------

systemctl --user daemon-reload

if [ "${DO_START}" = 0 ]; then
    echo "Installed but not started (--no-start)."
    echo "  systemctl --user start ${SERVICE}"
    exit 0
fi

echo "Starting ${SERVICE} ..."
# Quadlet-generated services are transient: they are enabled by the unit's
# [Install] section at generation time, so restart is the way to apply changes.
systemctl --user restart "${SERVICE}"

# Give the entrypoint time to run its OpenCL preflight and register.
for _ in $(seq 1 20); do
    if systemctl --user is-active --quiet "${SERVICE}"; then break; fi
    sleep 1
done

echo
if systemctl --user is-active --quiet "${SERVICE}"; then
    echo "${SERVICE} is running."
else
    echo "WARNING: ${SERVICE} is not active. The entrypoint refuses to register a"
    echo "  runner that cannot see the GPU, so check its output:"
fi

cat <<EOF

  journalctl --user -u ${SERVICE} -n 50 --no-pager
  journalctl --user -u ${SERVICE} -f

The first lines of the log are the OpenCL preflight. It reports the platforms it
can see, and refuses to register if it sees none — a runner without a GPU would
otherwise run the CL suite with no CL backend and report it green.

If this is a new registration, confirm the runner group grants the repository
access in Org Settings -> Actions -> Runner groups. Without that grant, jobs
queue forever against a runner that reports healthy.
EOF
