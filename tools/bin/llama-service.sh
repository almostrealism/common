#!/usr/bin/env bash
# TODO(review): this file appears unrelated to the exfiltration guard hook
# feature this branch/PR is about (see docs/plans/EXFILTRATION_GUARD_HOOK.md).
# Confirm whether it belongs in this PR or should move to its own change.
# On/off switch for the llama.cpp launchd service.
#
# tools/bin/llama.sh is only the LAUNCHER. In normal operation it is supervised
# by a launchd LaunchAgent (com.almostrealism.llama-server, installed from
# tools/launchd/com.almostrealism.llama-server.plist) with KeepAlive, so the
# :8084 Qwen3-Coder-Next server — shared by opencode/flowtree and the
# ar-consultant/ar-manager rephrasing layer — comes up at login and is
# restarted if it crashes.
#
# Because of that supervision, `kill`-ing the llama-server process does NOT
# stop it: llama.sh's watchdog sees the child die, exits non-zero, and launchd
# respawns the whole thing. The only clean stop is at the launchd level, which
# is what this wraps.
#
#   llama-service.sh stop      # clean stop, no auto-restart (frees ~70 GB Metal)
#   llama-service.sh start     # load the agent and start serving
#   llama-service.sh restart   # reload/restart the agent
#   llama-service.sh status    # is the agent loaded, and is :8084 serving?
#   llama-service.sh logs      # tail the launchd + server logs
#
# Run it on the account that OWNS the agent — the user whose
# ~/Library/LaunchAgents holds the plist (launchctl domains are per-user).
set -euo pipefail

LABEL="com.almostrealism.llama-server"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
DOMAIN="gui/$(id -u)"
PORT="${LLAMA_PORT:-8084}"
LOG_DIR="${HOME}/.llama-logs"

loaded() { launchctl print "${DOMAIN}/${LABEL}" >/dev/null 2>&1; }
serving() { lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1; }

case "${1:-status}" in
  stop)
    echo "stopping ${LABEL} (clean stop; KeepAlive will not revive it)..."
    if loaded; then
      launchctl bootout "${DOMAIN}/${LABEL}"
    else
      echo "  agent already not loaded"
    fi
    for _ in $(seq 1 10); do serving || break; sleep 1; done
    serving && echo "  WARNING: :${PORT} still serving" || echo "  :${PORT} free"
    ;;
  start)
    [ -f "${PLIST}" ] || { echo "plist not found: ${PLIST}" >&2; exit 1; }
    echo "starting ${LABEL}..."
    loaded || launchctl bootstrap "${DOMAIN}" "${PLIST}"
    launchctl kickstart "${DOMAIN}/${LABEL}"
    echo "  started (model load can take a minute or two; check: $0 status)"
    ;;
  restart)
    [ -f "${PLIST}" ] || { echo "plist not found: ${PLIST}" >&2; exit 1; }
    loaded || launchctl bootstrap "${DOMAIN}" "${PLIST}"
    launchctl kickstart -k "${DOMAIN}/${LABEL}"
    echo "restarted ${LABEL}"
    ;;
  status)
    loaded && echo "agent:  LOADED" || echo "agent:  not loaded"
    serving && echo "port ${PORT}: serving" || echo "port ${PORT}: not serving"
    ;;
  logs)
    tail -n 40 -f "${LOG_DIR}/launchd.err.log" "${LOG_DIR}/launchd.out.log" \
      "${LOG_DIR}/opencode.log"
    ;;
  *)
    echo "usage: $0 {stop|start|restart|status|logs}" >&2
    exit 2
    ;;
esac
