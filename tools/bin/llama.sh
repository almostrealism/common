#!/usr/bin/env bash
# Launches the two local llama.cpp inference servers used by the AR stack.
#
#   :8083  Qwen2.5-Coder-32B   — backs ar-consultant (chatml template).
#   :8084  Qwen3-Coder-30B-A3B — backs the flowtree OpencodeRunner; reached
#                                from agent containers as `mac-studio:8084`.
#
# Both bind 0.0.0.0 so tailnet peers can reach them. Logs are written under
# ~/.llama-logs/ so a non-interactive launch (boot, tmux) still leaves a
# diagnosable trail. The script waits on both children so Ctrl-C terminates
# the whole group cleanly.
#
# If a port is already in use, the corresponding server is left running and
# this script skips relaunching it — safe to re-invoke.

set -euo pipefail

MODELS_DIR="${MODELS_DIR:-/Users/Shared/models}"
LOG_DIR="${HOME}/.llama-logs"
mkdir -p "${LOG_DIR}"

CONSULTANT_GGUF="${MODELS_DIR}/qwen2.5-coder-32b-instruct-q4_k_m.gguf"
OPENCODE_GGUF="${MODELS_DIR}/Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf"

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

if launch consultant 8083 "${CONSULTANT_GGUF}" \
    -ngl 99 -c 8192 --chat-template chatml; then
  pids+=("$!")
fi

if launch opencode 8084 "${OPENCODE_GGUF}" \
    -ngl 99 -c 131072 --jinja; then
  pids+=("$!")
fi

if [ "${#pids[@]}" -eq 0 ]; then
  echo "Nothing to do — both ports already serving or both models missing."
  exit 0
fi

# Forward Ctrl-C / TERM to all launched children so the whole group exits.
trap 'echo "stopping..."; kill -- "${pids[@]}" 2>/dev/null || true' INT TERM
wait
