#!/usr/bin/env bash
set -euo pipefail

# ─── CUDA preflight for the ar-ci-cuda runner ───────────────────────────
#
# Runs before the shared runner entrypoint (../docker/entrypoint.sh) and
# refuses to register the runner unless the container can actually use the
# GPU. A runner that registered without a GPU would pick up the CUDA lane,
# fail to create a CUDA context, and report results that measure nothing.
#
# Checks, in order:
#   1. the NVIDIA runtime injected a GPU (nvidia-smi lists one)
#   2. the host CUDA toolkit is mounted and provides NVRTC
#   3. a staged sample library is readable by the runner user (an unstaged
#      one only warns, since test-cuda does not need it)
#
# On success it execs the shared entrypoint, so signal handling and
# deregistration are exactly as on the CPU fleet.
#
# The paths default to the compose file's mounts; the PREFLIGHT_* overrides
# exist so tools/tests/test_cuda_preflight.py can run the script on any host.

CUDA_ROOT="${PREFLIGHT_CUDA_ROOT:-/usr/local/cuda}"
SAMPLES_ROOT="${PREFLIGHT_SAMPLES_ROOT:-/opt/ar-samples}"
RUNNER_ENTRYPOINT="${PREFLIGHT_RUNNER_ENTRYPOINT:-/home/runner/entrypoint.sh}"
FAIL_PAUSE_SECONDS="${PREFLIGHT_FAIL_PAUSE_SECONDS:-60}"

fail() {
    echo "CUDA PREFLIGHT FAILED: $1" >&2
    shift
    for line in "$@"; do echo "  $line" >&2; done
    echo "Not registering this runner." >&2
    # Pause before exiting so `restart: unless-stopped` does not spin.
    sleep "${FAIL_PAUSE_SECONDS}"
    exit 1
}

if ! command -v nvidia-smi >/dev/null 2>&1; then
    fail "nvidia-smi is not available inside the container." \
        "The NVIDIA runtime did not inject the driver utilities. Check that the" \
        "compose service reserves the nvidia device and that" \
        "NVIDIA_DRIVER_CAPABILITIES includes 'utility'."
fi

if ! GPUS=$(nvidia-smi -L 2>&1) || [ -z "${GPUS}" ]; then
    fail "nvidia-smi could not list a GPU." "${GPUS:-no output}" \
        "On the host, 'docker run --rm --gpus all ubuntu nvidia-smi -L' should" \
        "list the GPU; if it does not, the NVIDIA Container Toolkit is the problem."
fi
echo "GPU: ${GPUS}"

# NVRTC must be present AND readable by the runner user. Listing the pathname
# only needs the directory's r-x bits, but the CUDA JNI bridge dlopen()s the
# library file itself; a toolkit whose libnvrtc.so is not readable by the
# non-root runner would otherwise pass preflight and then fail to load NVRTC
# in every job. Select the first candidate that is actually readable.
NVRTC=""
NVRTC_FOUND=0
for candidate in "${CUDA_ROOT}"/lib64/libnvrtc.so.*; do
    [ -e "${candidate}" ] || continue
    NVRTC_FOUND=1
    if [ -r "${candidate}" ]; then
        NVRTC="${candidate}"
        break
    fi
done
if [ -z "${NVRTC}" ]; then
    if [ "${NVRTC_FOUND}" -eq 1 ]; then
        fail "NVRTC under ${CUDA_ROOT}/lib64 is not readable by uid $(id -u) (groups: $(id -G))." \
            "The host CUDA toolkit is bind-mounted at /usr/local/cuda, but its" \
            "libnvrtc.so is not readable by the runner user, so the CUDA JNI bridge" \
            "cannot dlopen it. Make the toolkit readable by the runner on the host."
    else
        fail "NVRTC was not found under ${CUDA_ROOT}/lib64." \
            "The host CUDA toolkit is bind-mounted at /usr/local/cuda; check" \
            "CUDA_HOME_HOST in .env points at a toolkit that contains NVRTC."
    fi
fi
echo "NVRTC: ${NVRTC}"

# A library that is not staged yet is expected while the lane is
# informational: only the media suites need it, and they say so themselves.
# A library that is staged but unreadable is a fleet misconfiguration, and
# would fail every media job for a reason unrelated to the code under test.
if [ -r "${SAMPLES_ROOT}/pattern-factory.json" ] \
        && [ -r "${SAMPLES_ROOT}/Samples" ] && [ -x "${SAMPLES_ROOT}/Samples" ]; then
    echo "Sample library: ${SAMPLES_ROOT}"
elif [ -r "${SAMPLES_ROOT}" ] && [ -x "${SAMPLES_ROOT}" ] \
        && [ ! -e "${SAMPLES_ROOT}/pattern-factory.json" ] && [ ! -e "${SAMPLES_ROOT}/Samples" ]; then
    echo "WARNING: no sample library is staged at ${SAMPLES_ROOT}; the media suites will fail." >&2
else
    fail "The sample library at ${SAMPLES_ROOT} is not readable by uid $(id -u) (groups: $(id -G))." \
        "Both Samples/ and pattern-factory.json must be readable. sync-music-samples.sh" \
        "makes the tree readable by its --group only; AR_CI_SAMPLES_GID in .env must be" \
        "that group's numeric id, or empty so that fleet.sh reads it from the directory."
fi

exec "${RUNNER_ENTRYPOINT}" "$@"
