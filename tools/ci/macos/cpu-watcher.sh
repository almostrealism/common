#!/usr/bin/env bash
set -euo pipefail

# ─── CPU Watcher ─────────────────────────────────────────────────────
#
# Background helper that monitors for java child processes of a given
# parent PID and throttles each using SIGSTOP/SIGCONT duty cycling.
#
# No external dependencies (cpulimit is not used). The watcher reads
# CPU usage from ps(1) and applies an adaptive duty cycle: when a
# java process exceeds the limit, it is stopped for a fraction of
# each 1-second period proportional to the overage, then resumed.
# SIGSTOP/SIGCONT affects the entire process including all native
# threads (JNI, OpenCL, Metal).
#
# Usage:
#   cpu-watcher.sh <parent-pid> <cpu-limit-percent>
#
# Arguments:
#   parent-pid         PID whose descendants to monitor
#   cpu-limit-percent  CPU percentage cap (e.g. 400 for 4 cores)
#
# The watcher exits when the parent PID is no longer running.
# Compatible with macOS bash 3.2 (no associative arrays).

PARENT_PID="${1:?Usage: cpu-watcher.sh <parent-pid> <cpu-limit-percent>}"
LIMIT_PCT="${2:?Usage: cpu-watcher.sh <parent-pid> <cpu-limit-percent>}"

# Parallel arrays: JAVA_PIDS[i] is throttled by subshell THROTTLE_PIDS[i]
JAVA_PIDS=()
THROTTLE_PIDS=()

cleanup() {
    # Kill throttle subshells
    for tpid in "${THROTTLE_PIDS[@]}"; do
        kill "$tpid" 2>/dev/null || true
    done
    # Resume all tracked java processes (never leave stopped)
    for jpid in "${JAVA_PIDS[@]}"; do
        kill -CONT "$jpid" 2>/dev/null || true
    done
    exit 0
}
trap cleanup SIGTERM SIGINT EXIT

# Throttle a single java PID using SIGSTOP/SIGCONT duty cycling.
# Runs in a background subshell — one per java process.
throttle() {
    local pid=$1
    local limit=$2

    # Safety net: if this subshell is killed, resume the target
    trap 'kill -CONT '"$pid"' 2>/dev/null || true; exit 0' SIGTERM SIGINT EXIT

    while kill -0 "$pid" 2>/dev/null; do
        # ps -o %cpu= gives a decaying average of recent CPU usage.
        # On macOS this can exceed 100% for multi-threaded processes
        # (e.g. 1600% = 16 cores saturated).
        local cpu
        cpu=$(ps -o %cpu= -p "$pid" 2>/dev/null | tr -d ' ') || break
        local cpu_int=${cpu%%.*}
        if [ -z "$cpu_int" ] || [ "$cpu_int" = "0" ]; then
            sleep 1
            continue
        fi

        if [ "$cpu_int" -gt "$limit" ]; then
            # Duty cycle over a 1-second period:
            #   run_ms  = limit / cpu_int * 1000
            #   stop_ms = 1000 - run_ms
            # Example: 1600% CPU with 400% limit → run 250ms, stop 750ms
            local run_ms=$(( limit * 1000 / cpu_int ))
            local stop_ms=$(( 1000 - run_ms ))
            [ "$run_ms" -lt 50 ] && run_ms=50
            [ "$stop_ms" -lt 50 ] && stop_ms=50

            local stop_s run_s
            printf -v stop_s "%d.%03d" $((stop_ms / 1000)) $((stop_ms % 1000))
            printf -v run_s "%d.%03d" $((run_ms / 1000)) $((run_ms % 1000))

            kill -STOP "$pid" 2>/dev/null || break
            sleep "$stop_s"
            kill -CONT "$pid" 2>/dev/null || break
            sleep "$run_s"
        else
            sleep 1
        fi
    done

    kill -CONT "$pid" 2>/dev/null || true
}

# Check if a java PID already has a live throttle subshell.
is_tracked() {
    local target_pid="$1"
    local i=0
    while [ $i -lt ${#JAVA_PIDS[@]} ]; do
        if [ "${JAVA_PIDS[$i]}" = "$target_pid" ]; then
            if kill -0 "${THROTTLE_PIDS[$i]}" 2>/dev/null; then
                return 0
            fi
            # Throttle subshell died — remove stale entry
            unset "JAVA_PIDS[$i]"
            unset "THROTTLE_PIDS[$i]"
            JAVA_PIDS=("${JAVA_PIDS[@]}")
            THROTTLE_PIDS=("${THROTTLE_PIDS[@]}")
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
        is_tracked "$java_pid" && continue
        kill -0 "$java_pid" 2>/dev/null || continue

        # Demote to lowest scheduling priority so the process yields to others
        renice 20 "$java_pid" 2>/dev/null || true

        throttle "$java_pid" "$LIMIT_PCT" &
        JAVA_PIDS+=("$java_pid")
        THROTTLE_PIDS+=("$!")
        echo "cpu-watcher: throttling java PID ${java_pid} to ${LIMIT_PCT}%"
    done < <(pgrep -g "$(ps -o pgid= -p "${PARENT_PID}" | tr -d ' ')" -x java 2>/dev/null || true)

    sleep 2
done
