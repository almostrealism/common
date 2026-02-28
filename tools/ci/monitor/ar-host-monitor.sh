#!/usr/bin/env bash
set -euo pipefail

# ─── AR Host Resource Monitor ───────────────────────────────────────────
#
# Lightweight background daemon that samples host-level CPU and memory
# usage, writing JSONL logs for retroactive correlation with CI timeouts.
#
# Prerequisites:
#   - jq available on PATH
#   - POSIX ps and uptime (macOS or Linux)
#
# Usage:
#   chmod +x ar-host-monitor.sh
#   ./ar-host-monitor.sh          # foreground
#   nohup ./ar-host-monitor.sh &  # background

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- Load configuration ----------

ENV_FILE="${SCRIPT_DIR}/.env"
if [ -f "${ENV_FILE}" ]; then
    # shellcheck source=/dev/null
    source "${ENV_FILE}"
fi

MONITOR_INTERVAL="${MONITOR_INTERVAL:-10}"
MONITOR_LOG_DIR="${MONITOR_LOG_DIR:-${SCRIPT_DIR}/logs}"
MONITOR_CPU_THRESHOLD="${MONITOR_CPU_THRESHOLD:-5.0}"
MONITOR_MEM_THRESHOLD="${MONITOR_MEM_THRESHOLD:-2.0}"
MONITOR_RETENTION_DAYS="${MONITOR_RETENTION_DAYS:-14}"
HOSTNAME_LABEL="${HOSTNAME_LABEL:-$(hostname -s)}"

# ---------- Verify prerequisites ----------

if ! command -v jq > /dev/null 2>&1; then
    echo "ERROR: jq is required but not found on PATH."
    echo "  Install with: brew install jq  (macOS)  or  apt-get install jq  (Linux)"
    exit 1
fi

# ---------- PID file ----------

PID_FILE="${SCRIPT_DIR}/.ar-host-monitor.pid"

if [ -f "${PID_FILE}" ]; then
    OLD_PID="$(cat "${PID_FILE}")"
    if kill -0 "${OLD_PID}" 2>/dev/null; then
        echo "ERROR: Monitor already running (PID ${OLD_PID})."
        echo "  Stop it first or remove ${PID_FILE} if stale."
        exit 1
    fi
    rm -f "${PID_FILE}"
fi

echo $$ > "${PID_FILE}"

# ---------- Ensure log directory ----------

mkdir -p "${MONITOR_LOG_DIR}"

# ---------- Graceful shutdown ----------

RUNNING=true

cleanup() {
    echo ""
    echo "Caught signal, stopping monitor..."
    RUNNING=false
    rm -f "${PID_FILE}"
    echo "Monitor stopped."
    exit 0
}
trap cleanup SIGTERM SIGINT

# ---------- Helpers ----------

# Parse load averages from uptime output (works on both macOS and Linux)
parse_load() {
    uptime | sed 's/.*load average[s]*: *//' | tr -d ','
}

# Clean up log files older than retention period
cleanup_old_logs() {
    find "${MONITOR_LOG_DIR}" -name "*.jsonl" -mtime "+${MONITOR_RETENTION_DAYS}" -delete 2>/dev/null || true
}

# ---------- Startup banner ----------

echo "AR Host Resource Monitor"
echo "  Host:       ${HOSTNAME_LABEL}"
echo "  Interval:   ${MONITOR_INTERVAL}s"
echo "  Log dir:    ${MONITOR_LOG_DIR}"
echo "  CPU threshold: ${MONITOR_CPU_THRESHOLD}%"
echo "  MEM threshold: ${MONITOR_MEM_THRESHOLD}%"
echo "  Retention:  ${MONITOR_RETENTION_DAYS} days"
echo "  PID:        $$"
echo ""

# ---------- Sampling loop ----------

LAST_LOG_DATE=""

while ${RUNNING}; do
    CURRENT_DATE="$(date -u +%Y-%m-%d)"
    LOG_FILE="${MONITOR_LOG_DIR}/${CURRENT_DATE}.jsonl"

    # Clean up old logs on day rollover
    if [ "${CURRENT_DATE}" != "${LAST_LOG_DATE}" ]; then
        cleanup_old_logs
        LAST_LOG_DATE="${CURRENT_DATE}"
    fi

    TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    # Collect load averages
    LOAD_RAW="$(parse_load)"
    LOAD_JSON="$(echo "${LOAD_RAW}" | awk '{printf "[%s,%s,%s]", $1, $2, $3}')"

    # Collect process data: filter by CPU or MEM threshold
    # ps -eo outputs: PID %CPU %MEM RSS COMMAND
    # Skip header, filter by thresholds, build JSON array
    PROCS_JSON="$(ps -eo pid,pcpu,pmem,rss,comm 2>/dev/null \
        | tail -n +2 \
        | awk -v cpu_thresh="${MONITOR_CPU_THRESHOLD}" -v mem_thresh="${MONITOR_MEM_THRESHOLD}" '
            $2+0 >= cpu_thresh+0 || $3+0 >= mem_thresh+0 {
                # Extract just the command basename
                cmd = $5
                n = split(cmd, parts, "/")
                cmd = parts[n]
                # RSS is in KB, convert to MB
                rss_mb = int($4 / 1024)
                printf "%s\t%s\t%s\t%s\t%s\n", $1, $2, $3, rss_mb, cmd
            }' \
        | jq -Rn '[inputs | split("\t") | {pid: (.[0] | tonumber), cpu: (.[1] | tonumber), mem: (.[2] | tonumber), rss_mb: (.[3] | tonumber), cmd: .[4]}]'
    )"

    # Build and write the JSONL record
    jq -cn \
        --arg ts "${TIMESTAMP}" \
        --arg host "${HOSTNAME_LABEL}" \
        --argjson load "${LOAD_JSON}" \
        --argjson procs "${PROCS_JSON}" \
        '{ts: $ts, host: $host, load: $load, procs: $procs}' \
        >> "${LOG_FILE}"

    sleep "${MONITOR_INTERVAL}" &
    wait $! 2>/dev/null || true
done
