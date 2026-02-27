#!/usr/bin/env bash
set -euo pipefail

# ─── CPU Watcher ─────────────────────────────────────────────────────
#
# Background helper that monitors for java child processes of a given
# parent PID and applies cpulimit directly to each by PID.
#
# cpulimit --include-children fails for deep process hierarchies
# (cpulimit → run.sh → Runner.Worker → bash → mvn → java) because it
# discovers children by periodic process tree scanning and the forked
# JVM escapes monitoring. In contrast, cpulimit -p <pid> on a single
# known PID is reliable — it sends SIGSTOP/SIGCONT to the whole
# process, throttling all threads including native ones (JNI, OpenCL,
# Metal).
#
# Usage:
#   cpu-watcher.sh <parent-pid> <cpu-limit-percent>
#
# Arguments:
#   parent-pid         PID whose descendants to monitor
#   cpu-limit-percent  CPU percentage cap passed to cpulimit -l
#
# The watcher exits when the parent PID is no longer running.

PARENT_PID="${1:?Usage: cpu-watcher.sh <parent-pid> <cpu-limit-percent>}"
LIMIT_PCT="${2:?Usage: cpu-watcher.sh <parent-pid> <cpu-limit-percent>}"
POLL_INTERVAL=2

# Track java PIDs that already have a cpulimit attached
declare -A TRACKED_PIDS

# Clean up cpulimit children on exit
cleanup() {
    for pid in "${!TRACKED_PIDS[@]}"; do
        cpulimit_pid="${TRACKED_PIDS[$pid]}"
        if kill -0 "$cpulimit_pid" 2>/dev/null; then
            kill "$cpulimit_pid" 2>/dev/null || true
        fi
    done
    exit 0
}
trap cleanup SIGTERM SIGINT EXIT

while true; do
    # Exit if the parent process is gone
    if ! kill -0 "${PARENT_PID}" 2>/dev/null; then
        break
    fi

    # Find java processes descended from the parent PID.
    # pgrep -P is not recursive, so use ps + awk to find all java
    # commands whose PPID chain leads back to PARENT_PID.
    # A simpler approach: find all java PIDs, then check if PARENT_PID
    # is an ancestor via the process group or by walking the tree.
    while IFS= read -r java_pid; do
        [ -z "$java_pid" ] && continue

        # Skip if already tracked and cpulimit is still running
        if [[ -n "${TRACKED_PIDS[$java_pid]+x}" ]]; then
            if kill -0 "${TRACKED_PIDS[$java_pid]}" 2>/dev/null; then
                continue
            fi
            # cpulimit died, re-attach if java is still alive
            unset "TRACKED_PIDS[$java_pid]"
        fi

        # Verify the java process is still alive before attaching
        if ! kill -0 "$java_pid" 2>/dev/null; then
            continue
        fi

        cpulimit -p "$java_pid" -l "$LIMIT_PCT" -z &>/dev/null &
        TRACKED_PIDS[$java_pid]=$!
        echo "cpu-watcher: attached cpulimit (${LIMIT_PCT}%) to java PID ${java_pid}"
    done < <(pgrep -g "$(ps -o pgid= -p "${PARENT_PID}" | tr -d ' ')" -x java 2>/dev/null || true)

    sleep "${POLL_INTERVAL}"
done
