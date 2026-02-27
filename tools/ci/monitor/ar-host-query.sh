#!/usr/bin/env bash
set -euo pipefail

# ─── AR Host Resource Query ─────────────────────────────────────────────
#
# Query tool for JSONL logs produced by ar-host-monitor.sh.
# Filters samples by time range, process name, load average, and host.
#
# Prerequisites:
#   - jq available on PATH
#
# Usage:
#   ./ar-host-query.sh --last 15m
#   ./ar-host-query.sh --at "2026-02-26T14:30:00"
#   ./ar-host-query.sh --from "2026-02-26T14:00:00" --to "2026-02-26T15:00:00"
#   ./ar-host-query.sh --last 1h --proc java
#   ./ar-host-query.sh --last 30m --min-load 8.0
#   ./ar-host-query.sh --last 5m --raw

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- Load configuration ----------

ENV_FILE="${SCRIPT_DIR}/.env"
if [ -f "${ENV_FILE}" ]; then
    # shellcheck source=/dev/null
    source "${ENV_FILE}"
fi

MONITOR_LOG_DIR="${MONITOR_LOG_DIR:-${SCRIPT_DIR}/logs}"

# ---------- Verify prerequisites ----------

if ! command -v jq > /dev/null 2>&1; then
    echo "ERROR: jq is required but not found on PATH."
    exit 1
fi

# ---------- Parse arguments ----------

MODE=""
AT_TIME=""
FROM_TIME=""
TO_TIME=""
LAST_DURATION=""
PROC_FILTER=""
MIN_LOAD=""
HOST_FILTER=""
RAW_OUTPUT=false

usage() {
    cat <<'EOF'
Usage: ar-host-query.sh [OPTIONS]

Time selection (pick one):
  --at TIMESTAMP       Find samples closest to a timestamp
  --from TS --to TS    Show samples in a time range
  --last DURATION      Show last N minutes/hours (e.g., 15m, 1h)

Filters:
  --proc NAME          Filter by process command name (substring match)
  --min-load VALUE     Only show samples where load[0] >= VALUE
  --host NAME          Filter by hostname label

Output:
  --raw                Output raw JSONL instead of formatted table

Examples:
  ar-host-query.sh --last 15m
  ar-host-query.sh --at "2026-02-26T14:30:00"
  ar-host-query.sh --last 1h --proc java --min-load 4.0
  ar-host-query.sh --from "2026-02-26T14:00:00" --to "2026-02-26T15:00:00" --raw
EOF
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --at)
            MODE="at"
            AT_TIME="$2"
            shift 2
            ;;
        --from)
            FROM_TIME="$2"
            MODE="range"
            shift 2
            ;;
        --to)
            TO_TIME="$2"
            shift 2
            ;;
        --last)
            MODE="last"
            LAST_DURATION="$2"
            shift 2
            ;;
        --proc)
            PROC_FILTER="$2"
            shift 2
            ;;
        --min-load)
            MIN_LOAD="$2"
            shift 2
            ;;
        --host)
            HOST_FILTER="$2"
            shift 2
            ;;
        --raw)
            RAW_OUTPUT=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "ERROR: Unknown option: $1"
            usage
            ;;
    esac
done

if [ -z "${MODE}" ]; then
    echo "ERROR: Must specify --at, --from/--to, or --last"
    echo ""
    usage
fi

# ---------- Helpers ----------

# Convert a duration string (15m, 1h, 2d) to seconds
duration_to_seconds() {
    local dur="$1"
    local num="${dur%[mhds]}"
    local unit="${dur: -1}"
    case "${unit}" in
        m) echo $(( num * 60 )) ;;
        h) echo $(( num * 3600 )) ;;
        d) echo $(( num * 86400 )) ;;
        s) echo "${num}" ;;
        *) echo $(( dur * 60 )) ;;  # Default to minutes if no unit
    esac
}

# Cross-platform date-to-epoch conversion
# Accepts ISO 8601 timestamps (with or without trailing Z)
date_to_epoch() {
    local ts="$1"
    # Normalize: ensure trailing Z for UTC
    ts="${ts%Z}"
    if date -j -f "%Y-%m-%dT%H:%M:%S" "${ts}" "+%s" 2>/dev/null; then
        return
    fi
    # Linux fallback
    date -d "${ts}Z" "+%s" 2>/dev/null || date -d "${ts}" "+%s"
}

# Current epoch in UTC
now_epoch() {
    date -u "+%s"
}

# ---------- Determine time bounds ----------

case "${MODE}" in
    at)
        # Find samples within 30 seconds of the target
        TARGET_EPOCH="$(date_to_epoch "${AT_TIME}")"
        EPOCH_FROM=$(( TARGET_EPOCH - 30 ))
        EPOCH_TO=$(( TARGET_EPOCH + 30 ))
        ;;
    range)
        if [ -z "${FROM_TIME}" ] || [ -z "${TO_TIME}" ]; then
            echo "ERROR: --from and --to must both be specified"
            exit 1
        fi
        EPOCH_FROM="$(date_to_epoch "${FROM_TIME}")"
        EPOCH_TO="$(date_to_epoch "${TO_TIME}")"
        ;;
    last)
        DURATION_SECS="$(duration_to_seconds "${LAST_DURATION}")"
        EPOCH_TO="$(now_epoch)"
        EPOCH_FROM=$(( EPOCH_TO - DURATION_SECS ))
        ;;
esac

# ---------- Determine which log files to read ----------

if [ ! -d "${MONITOR_LOG_DIR}" ]; then
    echo "ERROR: Log directory not found: ${MONITOR_LOG_DIR}"
    exit 1
fi

# Collect all .jsonl files that might contain relevant data
LOG_FILES=()
for f in "${MONITOR_LOG_DIR}"/*.jsonl; do
    [ -f "$f" ] || continue
    LOG_FILES+=("$f")
done

if [ ${#LOG_FILES[@]} -eq 0 ]; then
    echo "No log files found in ${MONITOR_LOG_DIR}"
    exit 0
fi

# ---------- Build jq filter ----------

JQ_FILTER='.'

# Time filter: parse timestamp and compare epoch
JQ_TIME_FILTER="((.ts | sub(\"Z$\"; \"\") | strptime(\"%Y-%m-%dT%H:%M:%S\") | mktime) as \$e | \$e >= ${EPOCH_FROM} and \$e <= ${EPOCH_TO})"
JQ_FILTER="${JQ_TIME_FILTER}"

# Host filter
if [ -n "${HOST_FILTER}" ]; then
    JQ_FILTER="${JQ_FILTER} and (.host == \"${HOST_FILTER}\")"
fi

# Min load filter
if [ -n "${MIN_LOAD}" ]; then
    JQ_FILTER="${JQ_FILTER} and (.load[0] >= ${MIN_LOAD})"
fi

# Process name filter (applied to procs array)
PROC_TRANSFORM=""
if [ -n "${PROC_FILTER}" ]; then
    PROC_TRANSFORM="| .procs |= [.[] | select(.cmd | ascii_downcase | contains(\"$(echo "${PROC_FILTER}" | tr '[:upper:]' '[:lower:]')\"))]"
fi

# ---------- Query and output ----------

COMBINED_FILTER="select(${JQ_FILTER}) ${PROC_TRANSFORM}"

if ${RAW_OUTPUT}; then
    cat "${LOG_FILES[@]}" | jq -c "${COMBINED_FILTER}"
else
    cat "${LOG_FILES[@]}" | jq -c "${COMBINED_FILTER}" | while IFS= read -r line; do
        TS="$(echo "${line}" | jq -r '.ts')"
        HOST="$(echo "${line}" | jq -r '.host')"
        LOAD="$(echo "${line}" | jq -r '.load | map(tostring) | join(" ")')"
        PROC_COUNT="$(echo "${line}" | jq '.procs | length')"

        echo "=== ${HOST} at ${TS} (load: ${LOAD}) ==="

        if [ "${PROC_COUNT}" -eq 0 ]; then
            echo "  (no processes above threshold)"
        else
            printf "  %-8s %6s %6s %8s  %s\n" "PID" "CPU%" "MEM%" "RSS_MB" "CMD"
            echo "${line}" | jq -r '.procs[] | "  \(.pid | tostring | (" " * (8 - length)) + .)\((.cpu | tostring) | (" " * (6 - length)) + .)\((.mem | tostring) | (" " * (6 - length)) + .)\((.rss_mb | tostring) | (" " * (8 - length)) + .)  \(.cmd)"'
        fi
        echo ""
    done
fi
