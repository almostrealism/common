#!/usr/bin/env bash
# Launches the local llama.cpp inference server(s) used by the AR stack.
#
# Default mode (one server):
#   :8084  Qwen3-235B-A22B-Instruct-2507 (unsloth UD-Q2_K_XL, ~83 GB, MoE
#          with 22B active) — serves BOTH the flowtree OpencodeRunner
#          (which calls it as `mac-studio:8084`) AND ar-consultant (the
#          AR_CONSULTANT_LLAMACPP_URL in the agent .env points at the same
#          endpoint). One model, one server, two callers.
#
# We use UD-Q2_K_XL (~83 GB) rather than UD-Q3_K_XL (~97 GB) because the
# Q3 weights leave too little headroom for the KV cache on M1 Ultra's
# ~103 GB Metal working-set budget — Q3 reliably OOMs at warmup. Q2 keeps
# enough room for a 65K context plus competing Metal-using workloads
# (window server, browser GPU compositor, Docker).
#
# Optional split-model mode (two servers):
#   Set CONSULTANT_GGUF=<path-to-smaller-model.gguf> and the script will
#   ALSO launch a dedicated consultant server on :8083 with that model.
#   Useful when memory headroom is tight or when you want a faster, smaller
#   model for short consultant queries while keeping the 235B for opencode.
#   (Operators planning to host the consultant on a different machine
#   entirely should leave CONSULTANT_GGUF unset here and configure that
#   peer's llama.sh to bind 0.0.0.0:8083 with the smaller model.)
#
# Memory footprint note. The Metal recommendedMaxWorkingSetSize on M1 Ultra
# is ~103 GB; the 235B with a 65K context fits inside that budget but only
# just. Drop OPENCODE_CTX, free other workloads, or move the consultant to
# another machine if Metal starts paging.
#
# Both servers bind 0.0.0.0 so tailnet peers can reach them. Logs are
# written under ~/.llama-logs/ so a non-interactive launch (boot, tmux)
# still leaves a diagnosable trail. The script waits on its children so
# Ctrl-C terminates them cleanly.
#
# If a port is already in use, the corresponding server is left running and
# this script skips relaunching it — safe to re-invoke.

set -euo pipefail

MODELS_DIR="${MODELS_DIR:-/Users/Shared/models}"
LOG_DIR="${HOME}/.llama-logs"
mkdir -p "${LOG_DIR}"

# llama.cpp auto-discovers the remaining shards of a split GGUF when given
# part 00001-of-N. The full set lives next to this file on disk.
# Override OPENCODE_GGUF to switch quants (e.g. Q3_K_XL if you've freed
# enough Metal memory and prefer the higher-quality weights).
OPENCODE_GGUF="${OPENCODE_GGUF:-${MODELS_DIR}/Qwen3-235B-A22B-Instruct-2507-UD-Q2_K_XL/Qwen3-235B-A22B-Instruct-2507-UD-Q2_K_XL-00001-of-00002.gguf}"

# Optional second model for a dedicated consultant server on :8083.
# Leave empty (the default) for one-server mode. Set to e.g.
#   CONSULTANT_GGUF=$MODELS_DIR/qwen2.5-coder-32b-instruct-q4_k_m.gguf
# to enable a small consultant server alongside the 235B.
CONSULTANT_GGUF="${CONSULTANT_GGUF:-}"

# Context windows. The opencode server's KV cache scales with this; the
# 65K default consumed ~12 GB on Qwen3-235B Q3 in earlier probes.
#   OPENCODE_CTX    default 65536 (dedup audits accumulated ~63K tokens in
#                                  the prior Qwen3-Coder-30B session)
#   CONSULTANT_CTX  default 8192  (consultant queries are small)
OPENCODE_CTX="${OPENCODE_CTX:-65536}"
CONSULTANT_CTX="${CONSULTANT_CTX:-8192}"

port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

launch() {
  local label="$1" port="$2" gguf="$3"
  shift 3
  if [ ! -f "${gguf}" ]; then
    echo "${label}: SKIP — model file missing: ${gguf}"
    return 1
  fi
  if port_in_use "${port}"; then
    echo "${label}: SKIP — port ${port} already in use"
    return 1
  fi
  local log="${LOG_DIR}/${label}.log"
  echo "${label}: starting on :${port} (log: ${log})"
  llama-server -m "${gguf}" --host 0.0.0.0 --port "${port}" "$@" \
      >"${log}" 2>&1 &
  echo "${label}: pid=$!"
  return 0
}

pids=()

# Primary server — Qwen3-235B on :8084, shared by opencode and consultant.
if launch opencode 8084 "${OPENCODE_GGUF}" \
    -ngl 99 -c "${OPENCODE_CTX}" --jinja; then
  pids+=("$!")
fi

# Optional dedicated consultant on :8083. Only fires when CONSULTANT_GGUF is
# explicitly set and the file exists; default unset → single-server mode.
if [ -n "${CONSULTANT_GGUF}" ]; then
  if launch consultant 8083 "${CONSULTANT_GGUF}" \
      -ngl 99 -c "${CONSULTANT_CTX}" --jinja; then
    pids+=("$!")
  fi
else
  echo "consultant: SKIP — CONSULTANT_GGUF unset; consultant queries will share :8084"
fi

if [ "${#pids[@]}" -eq 0 ]; then
  echo "Nothing to do — ports already serving or models missing."
  exit 0
fi

# Forward Ctrl-C / TERM to all launched children so the whole group exits.
trap 'echo "stopping..."; kill -- "${pids[@]}" 2>/dev/null || true' INT TERM
wait
