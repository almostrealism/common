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
# Compatible with macOS bash 3.2 (no associative arrays).

PARENT_PID="${1:?Usage: cpu-watcher.sh <parent-pid> <cpu-limit-percent>}"
LIMIT_PCT="${2:?Usage: cpu-watcher.sh <parent-pid> <cpu-limit-percent>}"
POLL_INTERVAL=2

# Track java PIDs and their cpulimit PIDs as parallel lists.
# JAVA_PIDS[i] is throttled by LIMIT_PIDS[i].
JAVA_PIDS=()
LIMIT_PIDS=()

# Clean up cpulimit children on exit
cleanup() {
    for cpulimit_pid in "${LIMIT_PIDS[@]}"; do
        if kill -0 "$cpulimit_pid" 2>/dev/null; then
            kill "$cpulimit_pid" 2>/dev/null || true
        fi
    done
    exit 0
}
trap cleanup SIGTERM SIGINT EXIT

# Check if a java PID is already tracked with a live cpulimit.
# Returns 0 (true) if tracked and cpulimit is alive, 1 otherwise.
is_tracked() {
    local target_pid="$1"
    local i=0
    while [ $i -lt ${#JAVA_PIDS[@]} ]; do
        if [ "${JAVA_PIDS[$i]}" = "$target_pid" ]; then
            if kill -0 "${LIMIT_PIDS[$i]}" 2>/dev/null; then
                return 0
            fi
            # cpulimit died — remove stale entry
            unset "JAVA_PIDS[$i]"
            unset "LIMIT_PIDS[$i]"
            JAVA_PIDS=("${JAVA_PIDS[@]}")
            LIMIT_PIDS=("${LIMIT_PIDS[@]}")
            return 1
        fi
        i=$((i + 1))
    done
    return 1
}

while true; do
    # Exit if the parent process is gone
    if ! kill -0 "${PARENT_PID}" 2>/dev/null; then
        break
    fi

    # Find java processes in the same process group as the parent
    while IFS= read -r java_pid; do
        [ -z "$java_pid" ] && continue

        # Skip if already tracked with a live cpulimit
        if is_tracked "$java_pid"; then
            continue
        fi

        # Verify the java process is still alive before attaching
        if ! kill -0 "$java_pid" 2>/dev/null; then
            continue
        fi

        cpulimit -p "$java_pid" -l "$LIMIT_PCT" -z &>/dev/null &
        JAVA_PIDS+=("$java_pid")
        LIMIT_PIDS+=("$!")
        echo "cpu-watcher: attached cpulimit (${LIMIT_PCT}%) to java PID ${java_pid}"
    done < <(pgrep -g "$(ps -o pgid= -p "${PARENT_PID}" | tr -d ' ')" -x java 2>/dev/null || true)

    sleep "${POLL_INTERVAL}"
done
