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
#
# On success it execs the shared entrypoint, so signal handling and
# deregistration are exactly as on the CPU fleet.

fail() {
    echo "CUDA PREFLIGHT FAILED: $1" >&2
    shift
    for line in "$@"; do echo "  $line" >&2; done
    echo "Not registering this runner." >&2
    # Pause before exiting so `restart: unless-stopped` does not spin.
    sleep 60
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

NVRTC=$(ls /usr/local/cuda/lib64/libnvrtc.so.* 2>/dev/null | head -1 || true)
if [ -z "${NVRTC}" ]; then
    fail "NVRTC was not found under /usr/local/cuda/lib64." \
        "The host CUDA toolkit is bind-mounted at /usr/local/cuda; check" \
        "CUDA_HOME_HOST in .env points at a toolkit that contains NVRTC."
fi
echo "NVRTC: ${NVRTC}"

exec /home/runner/entrypoint.sh "$@"
