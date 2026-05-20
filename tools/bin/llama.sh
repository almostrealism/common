#!/usr/bin/env bash
# Launches the local llama.cpp inference server(s) used by the AR stack.
#
# Default mode (one server):
#   :8084  Qwen3-Coder-Next (unsloth UD-Q6_K_XL, ~73 GB split GGUF, MoE
#          coder model) — serves BOTH the flowtree OpencodeRunner (which
#          calls it as `mac-studio:8084`) AND ar-consultant (the
#          AR_CONSULTANT_LLAMACPP_URL in the agent .env points at the same
#          endpoint). One model, one server, two callers.
#
# Defaults are tuned for the M1 Ultra (~103 GB Metal working-set budget):
#   weights      ~73 GB  (UD-Q6_K_XL — near-fp16 quality)
#   KV cache     ~6 GB   (128K ctx at q8_0 KV)
#   total        ~79 GB  → ~24 GB headroom for other Metal users
#
# UD-Q4_K_XL (~50 GB) remains downloaded as a faster, lower-quality fallback —
# override OPENCODE_GGUF to switch. Both ship as Coder-tuned MoE with 3B
# active params, so even Q6 is fast enough for interactive use.
#
# Optional split-model mode (two servers):
#   Set CONSULTANT_GGUF=<path-to-smaller-model.gguf> and the script will
#   ALSO launch a dedicated consultant server on :8083 with that model.
#   Useful when memory headroom is tight or when you want a faster, smaller
#   model for short consultant queries while keeping Coder-Next for opencode.
#   (Operators planning to host the consultant on a different machine
#   entirely should leave CONSULTANT_GGUF unset here and configure that
#   peer's llama.sh to bind 0.0.0.0:8083 with the smaller model.)
#
# Both servers bind 0.0.0.0 so tailnet peers can reach them. Logs are
# written under ~/.llama-logs/ so a non-interactive launch (boot, tmux)
# still leaves a diagnosable trail. The script waits on its children so
# Ctrl-C terminates them cleanly.
#
# If a port is already in use, the corresponding server is left running and
# this script skips relaunching it — safe to re-invoke.
#
# Health watchdog: while servers we launched are running, a background loop
# polls each one's /health endpoint. After WATCHDOG_FAILS consecutive failures
# (default 3 at WATCHDOG_INTERVAL=15s = 45s) OR an unexpected server exit, the
# script kills its children and exits non-zero so a process supervisor
# (launchd, systemd, tmux loop, etc.) restarts the whole thing. Clean stops
# via SIGINT/SIGTERM exit 0 and do NOT trip the watchdog.

set -euo pipefail

MODELS_DIR="${MODELS_DIR:-/Users/Shared/models}"
LOG_DIR="${HOME}/.llama-logs"
mkdir -p "${LOG_DIR}"

WATCHDOG_INTERVAL="${WATCHDOG_INTERVAL:-15}"
WATCHDOG_FAILS="${WATCHDOG_FAILS:-3}"
WATCHDOG_TIMEOUT="${WATCHDOG_TIMEOUT:-5}"

# UD-Q6_K_XL is a 3-shard split GGUF; llama.cpp auto-discovers shards 2/3
# when handed shard 1. UD-Q4_K_XL (~50 GB single-file) is the lighter
# fallback and stays on disk. Override OPENCODE_GGUF to switch quants.
OPENCODE_GGUF="${OPENCODE_GGUF:-${MODELS_DIR}/Qwen3-Coder-Next-GGUF/UD-Q6_K_XL/Qwen3-Coder-Next-UD-Q6_K_XL-00001-of-00003.gguf}"

# Optional second model for a dedicated consultant server on :8083.
# Leave empty (the default) for one-server mode. Set to e.g.
#   CONSULTANT_GGUF=$MODELS_DIR/qwen2.5-coder-32b-instruct-q4_k_m.gguf
# to enable a small consultant server alongside Coder-Next.
CONSULTANT_GGUF="${CONSULTANT_GGUF:-}"

# Context windows. The opencode server's KV cache scales with this; with KV
# quantization (see below) 128K stays well inside the Metal budget.
#   OPENCODE_CTX    default 131072 (128K — Qwen3-Coder-Next is native 256K,
#                                   so we can run higher when the workload
#                                   wants it)
#   CONSULTANT_CTX  default 8192   (consultant queries are small)
OPENCODE_CTX="${OPENCODE_CTX:-131072}"
CONSULTANT_CTX="${CONSULTANT_CTX:-8192}"

# Long-context tuning. Defaults turn on flash attention and q8_0 KV cache,
# which together halve KV memory at negligible quality cost and are required
# for the 128K default ctx to fit alongside Q6 weights.
#   OPENCODE_FA       default 1   (flash attention; required for KV quant)
#   OPENCODE_KV_TYPE  default q8_0 ("f16" disables KV quant; "q4_0" goes
#                                   smaller still if you push past 128K)
OPENCODE_FA="${OPENCODE_FA:-1}"
OPENCODE_KV_TYPE="${OPENCODE_KV_TYPE:-q8_0}"

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

server_pids=()
server_ports=()
server_labels=()

# Primary server — Qwen3-Coder-Next on :8084, shared by opencode and consultant.
opencode_args=(-ngl 99 -c "${OPENCODE_CTX}" --jinja)
if [ "${OPENCODE_FA}" = "1" ]; then
  opencode_args+=(-fa on)
fi
if [ "${OPENCODE_KV_TYPE}" != "f16" ]; then
  opencode_args+=(--cache-type-k "${OPENCODE_KV_TYPE}" --cache-type-v "${OPENCODE_KV_TYPE}")
fi
if launch opencode 8084 "${OPENCODE_GGUF}" "${opencode_args[@]}"; then
  server_pids+=("$!")
  server_ports+=("8084")
  server_labels+=("opencode")
fi

# Optional dedicated consultant on :8083. Only fires when CONSULTANT_GGUF is
# explicitly set and the file exists; default unset → single-server mode.
if [ -n "${CONSULTANT_GGUF}" ]; then
  if launch consultant 8083 "${CONSULTANT_GGUF}" \
      -ngl 99 -c "${CONSULTANT_CTX}" --jinja; then
    server_pids+=("$!")
    server_ports+=("8083")
    server_labels+=("consultant")
  fi
else
  echo "consultant: SKIP — CONSULTANT_GGUF unset; consultant queries will share :8084"
fi

if [ "${#server_pids[@]}" -eq 0 ]; then
  echo "Nothing to do — ports already serving or models missing."
  exit 0
fi

# Background watchdog. Only watches servers we launched in this invocation.
# Polls each server's /health endpoint and exits non-zero on:
#   - any server PID exiting unexpectedly
#   - $WATCHDOG_FAILS consecutive /health failures across the pool
# llama-server returns HTTP 503 while loading the model (which for the
# default 73GB Q6 split can take a minute or two on cold mmap). 503 is
# explicitly NOT counted as a failure — only connection refused / timeout
# and unexpected status codes are.
#
# The supervisor (launchd/systemd) sees the non-zero exit and restarts us.

# Returns: 0 on healthy or loading, 1 on real failure.
# Prints a one-word status to stdout.
check_health() {
  local url="$1"
  local code
  code=$(curl -sS --max-time "${WATCHDOG_TIMEOUT}" -o /dev/null \
      -w '%{http_code}' "${url}" 2>/dev/null || true)
  case "${code}" in
    200)      echo "ok";          return 0 ;;
    503)      echo "loading";     return 0 ;;
    "" | 000) echo "unreachable"; return 1 ;;
    *)        echo "http=${code}"; return 1 ;;
  esac
}

watchdog() {
  local consecutive_failures=0
  while sleep "${WATCHDOG_INTERVAL}"; do
    local i pid label port status
    for i in "${!server_pids[@]}"; do
      pid="${server_pids[$i]}"
      label="${server_labels[$i]}"
      if ! kill -0 "${pid}" 2>/dev/null; then
        echo "watchdog: ${label} (pid ${pid}) exited unexpectedly" >&2
        return 1
      fi
    done

    local any_failure=0
    for i in "${!server_ports[@]}"; do
      port="${server_ports[$i]}"
      label="${server_labels[$i]}"
      if ! status=$(check_health "http://127.0.0.1:${port}/health"); then
        echo "watchdog: ${label} :${port}/health ${status}" >&2
        any_failure=1
      fi
    done

    if [ "${any_failure}" -eq 1 ]; then
      consecutive_failures=$((consecutive_failures + 1))
      echo "watchdog: consecutive_failures=${consecutive_failures}/${WATCHDOG_FAILS}" >&2
      if [ "${consecutive_failures}" -ge "${WATCHDOG_FAILS}" ]; then
        echo "watchdog: unhealthy — tearing down" >&2
        return 1
      fi
    else
      if [ "${consecutive_failures}" -gt 0 ]; then
        echo "watchdog: /health recovered" >&2
      fi
      consecutive_failures=0
    fi
  done
}

watchdog &
watchdog_pid=$!

teardown() {
  local exit_code="${1:-0}"
  echo "stopping..."
  kill "${watchdog_pid}" 2>/dev/null || true
  kill -- "${server_pids[@]}" 2>/dev/null || true
  # Give children a moment to flush, then SIGKILL stragglers.
  sleep 2
  kill -KILL -- "${server_pids[@]}" 2>/dev/null || true
  exit "${exit_code}"
}

# Forward Ctrl-C / TERM to all launched children so the whole group exits cleanly.
trap 'teardown 0' INT TERM

# Wait for the watchdog. It only exits on detected unhealth, or when killed by
# the trap above (in which case `teardown 0` has already called exit).
set +e
wait "${watchdog_pid}"
watchdog_exit=$?
set -e
echo "watchdog exited with code ${watchdog_exit}" >&2
trap - INT TERM
teardown 1
