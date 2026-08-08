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
#   --runners N   Run N runners (default: RUNNER_COUNT from .env, else 1).
#                 Scaling down stops the instances above N.
#   --allow-overcommit
#                 Start even when the combined memory allowance exceeds what the
#                 host can back. Only with a specific reason.
#   --no-build    Install/refresh the unit only; do not rebuild the image
#   --no-start    Install everything but leave the services stopped
#   -h, --help    Show this help
#
# Resource limits are PER RUNNER. Set RUNNER_MEMORY_MAX / RUNNER_CPU_QUOTA in
# .env and they are written as a systemd drop-in; with N runners the host must
# accommodate N times those values.
#
# This fleet is Quadlet-managed rather than Compose-managed. See the comment at
# the top of ar-ci-cl-runner@.container for why — briefly, `docker compose`
# cannot express GroupAdd=keep-groups, without which the container has no GPU.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UNIT_NAME="ar-ci-cl-runner"
UNIT_FILE="${UNIT_NAME}@.container"
IMAGE="ar-ci-cl-runner:latest"
QUADLET_DIR="${HOME}/.config/containers/systemd"
DROPIN_DIR="${HOME}/.config/systemd/user/${UNIT_NAME}@.service.d"
ENV_DIR="${HOME}/.config/ar-ci-cl"
ENV_TARGET="${ENV_DIR}/runner.env"

# Highest instance index this script will look for when stopping runners that
# are no longer wanted. Scaling down from any plausible count is covered.
MAX_INSTANCE=32

DO_BUILD=1
DO_START=1
RUNNERS=""
ALLOW_OVERCOMMIT=0

# Refuse to start a fleet whose combined memory allowance exceeds this share of
# host RAM. The GPU here is integrated, so its memory comes out of the same pool
# the kernel needs; overcommitting has already taken this machine down hard
# enough to need physical intervention.
MEM_BUDGET_PERCENT=85

usage() { sed -n '4,28p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --runners)  RUNNERS="$2"; shift 2 ;;
        --allow-overcommit) ALLOW_OVERCOMMIT=1; shift ;;
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

# Rootless podman and `systemctl --user` both need XDG_RUNTIME_DIR. It is set by
# pam_systemd when a login session is created, which `sudo -iu` does not always
# do — and the directory itself only exists while the user manager is running,
# which for a service account means lingering must be enabled.
if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    CANDIDATE="/run/user/$(id -u)"
    if [ -d "${CANDIDATE}" ]; then
        export XDG_RUNTIME_DIR="${CANDIDATE}"
        echo "note: XDG_RUNTIME_DIR was unset; using ${CANDIDATE}"
        echo
    else
        echo "ERROR: XDG_RUNTIME_DIR is not set and ${CANDIDATE} does not exist." >&2
        echo "  The user manager is not running for $(id -un). That is expected if" >&2
        echo "  lingering is disabled — re-enable it from an account with sudo:" >&2
        echo "    sudo loginctl enable-linger $(id -un)" >&2
        echo "  then start a fresh session with 'sudo -iu $(id -un)'." >&2
        exit 1
    fi
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

# ---------- Retire the pre-template unit ----------
# Before the template rename this fleet installed a single, non-templated
# ar-ci-cl-runner.container. Installing the template does not displace it, and
# the scale-down sweep below only considers @N instances — so a host set up
# before the rename quietly runs one extra runner that --runners can never
# account for. Retire it explicitly.
LEGACY_UNIT="${QUADLET_DIR}/${UNIT_NAME}.container"
if [ -f "${LEGACY_UNIT}" ] || \
        systemctl --user is-active --quiet "${UNIT_NAME}.service" 2>/dev/null; then
    echo "Retiring the pre-template ${UNIT_NAME}.service ..."
    systemctl --user stop "${UNIT_NAME}.service" 2>/dev/null || true
    rm -f "${LEGACY_UNIT}"
    echo "  removed ${LEGACY_UNIT}"
fi

# ---------- Resource limits ----------

# The unit's MemoryMax/CPUQuota are per instance, and scaling multiplies them.
# A drop-in keeps the committed unit free of host-specific tuning, so changing
# the runner count and the limits is a single edit to .env.
env_value() { sed -n "s/^$1=//p" "${SCRIPT_DIR}/.env" | tail -1; }

MEMORY_MAX=$(env_value RUNNER_MEMORY_MAX)
CPU_QUOTA=$(env_value RUNNER_CPU_QUOTA)

if [ -n "${MEMORY_MAX}" ] || [ -n "${CPU_QUOTA}" ]; then
    mkdir -p "${DROPIN_DIR}"
    {
        echo "# Generated by install-runner.sh from .env — do not edit by hand."
        echo "[Service]"
        [ -n "${MEMORY_MAX}" ] && echo "MemoryMax=${MEMORY_MAX}"
        [ -n "${CPU_QUOTA}" ]  && echo "CPUQuota=${CPU_QUOTA}"
    } > "${DROPIN_DIR}/limits.conf"
    echo "Installed ${DROPIN_DIR}/limits.conf (per instance:${MEMORY_MAX:+ ${MEMORY_MAX}}${CPU_QUOTA:+ ${CPU_QUOTA}})"
else
    rm -f "${DROPIN_DIR}/limits.conf" 2>/dev/null || true
fi
echo

# ---------- Activate ----------

[ -z "${RUNNERS}" ] && RUNNERS=$(env_value RUNNER_COUNT)
[ -z "${RUNNERS}" ] && RUNNERS=1

case "${RUNNERS}" in
    ''|*[!0-9]*|0) echo "ERROR: runner count must be a positive integer (got '${RUNNERS}')." >&2; exit 2 ;;
esac

if [ "${RUNNERS}" -gt "${MAX_INSTANCE}" ]; then
    echo "ERROR: refusing to start ${RUNNERS} runners; ${MAX_INSTANCE} is the cap." >&2
    exit 2
fi

# ---------- Memory headroom ----------
# MemoryMax is a per-cgroup ceiling, so it only protects the host while the SUM
# across instances leaves the host something to run on. Three runners at the
# unit's 64G default once put 192G of allowance on a 125G machine, which hung it
# hard enough to need physical intervention: the GPU is integrated, its memory
# comes from the same pool, and ROCm allocations are pinned, so the kernel has
# nothing to reclaim and stalls rather than OOM-killing its way out.

# Converts a systemd byte value (64G, 8192M, 1048576K, or plain bytes) to KiB.
# Echoes 0 for anything unparseable, which disables the check rather than
# guessing at a limit.
to_kib() {
    local value="$1" number unit
    number=$(printf '%s' "${value}" | sed 's/[^0-9].*$//')
    unit=$(printf '%s' "${value}" | sed 's/^[0-9]*//' | tr '[:lower:]' '[:upper:]')
    [ -z "${number}" ] && { echo 0; return; }
    case "${unit}" in
        T|TI|TB) echo $((number * 1024 * 1024 * 1024)) ;;
        G|GI|GB) echo $((number * 1024 * 1024)) ;;
        M|MI|MB) echo $((number * 1024)) ;;
        K|KI|KB) echo $((number)) ;;
        "")      echo $((number / 1024)) ;;
        *)       echo 0 ;;
    esac
}

PER_INSTANCE_MEM="${MEMORY_MAX}"
if [ -z "${PER_INSTANCE_MEM}" ]; then
    PER_INSTANCE_MEM=$(sed -n 's/^MemoryMax=//p' "${SCRIPT_DIR}/${UNIT_FILE}" | tail -1)
fi

PER_KIB=$(to_kib "${PER_INSTANCE_MEM}")
HOST_KIB=$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null || echo 0)

if [ "${PER_KIB}" -gt 0 ] && [ "${HOST_KIB}" -gt 0 ]; then
    TOTAL_KIB=$((PER_KIB * RUNNERS))
    BUDGET_KIB=$((HOST_KIB * MEM_BUDGET_PERCENT / 100))
    TOTAL_G=$((TOTAL_KIB / 1024 / 1024))
    HOST_G=$((HOST_KIB / 1024 / 1024))
    BUDGET_G=$((BUDGET_KIB / 1024 / 1024))

    if [ "${TOTAL_KIB}" -gt "${BUDGET_KIB}" ]; then
        echo "ERROR: ${RUNNERS} runners at ${PER_INSTANCE_MEM} each is ${TOTAL_G}G of memory" >&2
        echo "  allowance, against ${HOST_G}G of host RAM (budget ${BUDGET_G}G at" >&2
        echo "  ${MEM_BUDGET_PERCENT}%). Refusing to start." >&2
        echo "" >&2
        echo "  Lower RUNNER_MEMORY_MAX in .env, or run fewer runners. Remember the" >&2
        echo "  GPU is integrated: its memory comes out of this same pool, and so" >&2
        echo "  does AR_HARDWARE_MEMORY_SCALE per test JVM." >&2
        echo "" >&2
        echo "  --allow-overcommit proceeds anyway, if you have a reason." >&2
        [ "${ALLOW_OVERCOMMIT}" = 1 ] || exit 2
        echo "  --allow-overcommit given; continuing." >&2
        echo "" >&2
    else
        echo "Memory: ${RUNNERS} x ${PER_INSTANCE_MEM} = ${TOTAL_G}G of ${HOST_G}G host RAM (budget ${BUDGET_G}G)"
    fi
fi

systemctl --user daemon-reload

# Stop instances above the requested count before starting any, so scaling down
# does not leave orphans holding a GitHub registration.
for i in $(seq $((RUNNERS + 1)) "${MAX_INSTANCE}"); do
    if systemctl --user is-active --quiet "${UNIT_NAME}@${i}.service" 2>/dev/null; then
        echo "Stopping ${UNIT_NAME}@${i}.service (beyond the requested ${RUNNERS}) ..."
        systemctl --user stop "${UNIT_NAME}@${i}.service" || true
    fi
done

if [ "${DO_START}" = 0 ]; then
    echo "Installed but not started (--no-start)."
    echo "  systemctl --user start ${UNIT_NAME}@1.service"
    exit 0
fi

echo "Starting ${RUNNERS} runner(s) ..."
FAILED=""
for i in $(seq 1 "${RUNNERS}"); do
    # Quadlet-generated services are transient: they are enabled by the unit's
    # [Install] section at generation time, so restart is how changes are applied.
    systemctl --user restart "${UNIT_NAME}@${i}.service" || FAILED="${FAILED} ${i}"
done

# Give the entrypoints time to run their OpenCL preflight and register.
for _ in $(seq 1 30); do
    ACTIVE=0
    for i in $(seq 1 "${RUNNERS}"); do
        systemctl --user is-active --quiet "${UNIT_NAME}@${i}.service" && ACTIVE=$((ACTIVE + 1))
    done
    [ "${ACTIVE}" -eq "${RUNNERS}" ] && break
    sleep 1
done

echo
for i in $(seq 1 "${RUNNERS}"); do
    if systemctl --user is-active --quiet "${UNIT_NAME}@${i}.service"; then
        echo "  ${UNIT_NAME}@${i}.service: running"
    else
        echo "  ${UNIT_NAME}@${i}.service: NOT ACTIVE"
        FAILED="${FAILED} ${i}"
    fi
done

if [ -n "${FAILED}" ]; then
    echo
    echo "WARNING: one or more runners did not start. The entrypoint refuses to"
    echo "  register a runner that cannot see the GPU, so check its output."
fi

cat <<EOF

  journalctl --user -u '${UNIT_NAME}@*' -n 50 --no-pager
  journalctl --user -u '${UNIT_NAME}@*' -f
  systemctl --user list-units '${UNIT_NAME}@*'

The first lines of each log are the OpenCL preflight. It reports the platforms it
can see, and refuses to register if it sees none — a runner without a GPU would
otherwise run the CL suite with no CL backend and report it green.

If this is a new registration, confirm the runner group grants the repository
access in Org Settings -> Actions -> Runner groups. Without that grant, jobs
queue forever against a runner that reports healthy.
EOF
