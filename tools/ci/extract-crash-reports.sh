#!/usr/bin/env bash
# ─── Extract crash reports from Maven test output ──────────────────
#
# Parses Maven log files for JVM fork crash indicators (exit codes,
# crashed test class names), collects .dumpstream files from
# surefire-reports directories and hs_err_pid*.log fatal error logs,
# and prints a bounded excerpt of each collected file to stdout so the
# cause of a native crash (signal, problematic frame, crashing thread,
# first native frames, glibc message) is readable in the job log
# itself, without downloading the crash-reports artifact.
#
# Files are searched for below the current directory. hs_err logs a
# fatal error message names ("An error report file ... is saved as:
# <path>") are collected too when that path exists.
#
# Usage:
#   extract-crash-reports.sh <log-file> [log-file ...]
#
# Environment:
#   CRASH_REPORT_DIR        - output directory (default: unique temp directory)
#   CRASH_LOG_MAX_FILES     - files excerpted into the job log (default 5)
#   CRASH_LOG_MAX_LINES     - lines kept from an excerpt's leading section: a
#                             dumpstream's cause lines, or an hs_err summary
#                             header (default 40). An hs_err's Current-thread and
#                             siginfo lines (one each) and its native frames are
#                             bounded separately, the frames by
#                             CRASH_LOG_NATIVE_FRAMES, so an hs_err excerpt can
#                             run to a few lines beyond the sum of these bounds.
#   CRASH_LOG_NATIVE_FRAMES - native frames printed per hs_err log (default 15)
#   CRASH_LOG_LINE_WIDTH    - characters kept per printed line (default 300)
#
# Output:
#   stdout                                 - bounded crash excerpts
#   $CRASH_REPORT_DIR/crash-summary.txt   - consolidated crash info
#   $CRASH_REPORT_DIR/dumpstreams/         - collected .dumpstream files
#   $CRASH_REPORT_DIR/hs_err/              - collected hs_err_pid*.log files
#
# Exit codes:
#   0 - always succeeds (missing logs are skipped)

set -euo pipefail

# Output directory can be overridden via CRASH_REPORT_DIR env var.
# Uses RUNNER_TEMP (GitHub Actions) or mktemp to avoid /tmp collisions.
if [ -z "${CRASH_REPORT_DIR:-}" ]; then
    if [ -n "${RUNNER_TEMP:-}" ]; then
        OUTPUT_DIR="${RUNNER_TEMP}/crash-reports"
    else
        OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/crash-reports-XXXXXX")"
    fi
else
    OUTPUT_DIR="${CRASH_REPORT_DIR}"
fi
SUMMARY_FILE="${OUTPUT_DIR}/crash-summary.txt"
DUMPSTREAM_DIR="${OUTPUT_DIR}/dumpstreams"
HS_ERR_DIR="${OUTPUT_DIR}/hs_err"

MAX_FILES="${CRASH_LOG_MAX_FILES:-5}"
MAX_LINES="${CRASH_LOG_MAX_LINES:-40}"
NATIVE_FRAMES="${CRASH_LOG_NATIVE_FRAMES:-15}"
LINE_WIDTH="${CRASH_LOG_LINE_WIDTH:-300}"

# Lines of a dumpstream that carry a native crash's cause: the JVM's fatal
# error banner, the signal, the problematic frame, frame lines and the
# C library's own abort messages.
DUMPSTREAM_CAUSE_PATTERN='(fatal error has been detected|SIGSEGV|SIGBUS|SIGABRT|SIGILL|SIGFPE|EXCEPTION_|Problematic frame|^#  *[CVjJv] |[^A-Za-z][CVjJ]  \[|Current thread|error report file|hs_err_pid|free\(\)|malloc|double free|corrupted|Aborted|core dumped|OutOfMemoryError|Internal Error|assert\(|Process Exit Code|Crashed tests)'

mkdir -p "$OUTPUT_DIR" "$DUMPSTREAM_DIR" "$HS_ERR_DIR"

# Truncate each line of stdin to LINE_WIDTH characters and keep at most
# the number of lines given as the first argument. Callers end the
# pipeline with || true: head closing early sends SIGPIPE upstream,
# which pipefail would otherwise turn into a failure of the script.
bounded() {
    cut -c "1-${LINE_WIDTH}" | head -n "$1"
}

# Print the decisive part of an hs_err_pid*.log: the summary header
# (signal, problematic frame, JRE version), the siginfo and crashing
# thread lines, and the first native frames of the crashing stack.
excerpt_hs_err() {
    local file="$1"
    awk '/^-----/ { exit } { print }' "$file" | bounded "$MAX_LINES" || true
    grep -m1 -E '^Current thread' "$file" | bounded 1 || true
    grep -m1 -E '^siginfo:' "$file" | bounded 1 || true
    if grep -q -E '^Native frames:' "$file"; then
        echo "Native frames (first ${NATIVE_FRAMES}):"
        awk '/^Native frames:/ { inside = 1; next } inside && /^$/ { exit } inside { print }' "$file" \
            | bounded "$NATIVE_FRAMES" || true
    fi
}

# Print the lines of a dumpstream that carry the crash's cause; a
# dumpstream with no such line is printed from its head instead.
excerpt_dumpstream() {
    local file="$1"
    if grep -q -E "$DUMPSTREAM_CAUSE_PATTERN" "$file"; then
        grep -E "$DUMPSTREAM_CAUSE_PATTERN" "$file" | bounded "$MAX_LINES" || true
    else
        bounded "$MAX_LINES" < "$file" || true
    fi
}

# Print excerpts of every file in a directory, at most MAX_FILES of them
# across calls. The first argument names the excerpt function.
PRINTED_FILES=0
print_excerpts() {
    local kind="$1" dir="$2" f lines
    for f in "$dir"/*; do
        [ -f "$f" ] || continue
        if [ "$PRINTED_FILES" -ge "$MAX_FILES" ]; then
            echo "... further crash files omitted from the log; see the crash-reports artifact"
            return
        fi
        PRINTED_FILES=$((PRINTED_FILES + 1))
        lines=$(wc -l < "$f" | tr -d ' ')
        echo "──── ${kind}: $(basename "$f") (${lines} lines) ────"
        "excerpt_${kind}" "$f"
        echo ""
    done
}

# Start the summary file
cat > "$SUMMARY_FILE" <<'HEADER'
# JVM Crash Report
# Extracted from Maven test console output and surefire dumpstreams.
HEADER

FOUND_CRASHES="false"

# ── Parse each Maven log file for crash indicators ─────────────────
for logfile in "$@"; do
    if [ ! -f "$logfile" ]; then
        echo "# WARN: Log file not found: ${logfile}" >> "$SUMMARY_FILE"
        continue
    fi

    module_name=$(basename "$logfile" .log | sed 's/^mvn-//' | sed 's/-test$//')

    # Look for surefire fork crash lines:
    #   "Error occurred in starting fork" / "ExecutionException ... Forked VM crashed"
    #   "The forked VM terminated without properly saying goodbye"
    #   "Process Exit Code: <N>"
    #   "Crashed tests: <class>"
    crash_lines=$(grep -E \
        '(Error occurred in starting fork|Forked VM|forked VM terminated|Process Exit Code:|Crashed tests:|ExecutionException.*fork|There was an error in the forked process|The forked VM terminated without properly saying goodbye)' \
        "$logfile" 2>/dev/null || true)

    if [ -n "$crash_lines" ]; then
        FOUND_CRASHES="true"
        {
            echo ""
            echo "## Module: ${module_name}"
            echo "## Log file: ${logfile}"
            echo ""
            echo "$crash_lines"
            echo ""
        } >> "$SUMMARY_FILE"
    fi

    # Also extract any OOM or signal-related lines
    oom_lines=$(grep -E \
        '(OutOfMemoryError|Cannot allocate memory|signal [0-9]+|SIGABRT|SIGSEGV|SIGKILL|exit code 134|exit code 137|exit code 139)' \
        "$logfile" 2>/dev/null || true)

    if [ -n "$oom_lines" ]; then
        FOUND_CRASHES="true"
        {
            echo "### OOM/Signal indicators (${module_name}):"
            echo "$oom_lines"
            echo ""
        } >> "$SUMMARY_FILE"
    fi
done

# ── Collect .dumpstream files from surefire-reports ────────────────
dumpstream_count=0
while IFS= read -r dumpfile; do
    if [ -f "$dumpfile" ]; then
        # Preserve module context in the filename
        module_dir=$(echo "$dumpfile" | grep -oP '^\./\K[^/]+' 2>/dev/null || basename "$(dirname "$(dirname "$(dirname "$dumpfile")")")")
        dest_name="${module_dir}-$(basename "$dumpfile")"
        cp "$dumpfile" "${DUMPSTREAM_DIR}/${dest_name}"
        dumpstream_count=$((dumpstream_count + 1))
    fi
done < <(find . -path "*/surefire-reports/*.dumpstream" -type f 2>/dev/null || true)

# ── Collect hs_err_pid*.log fatal error logs ───────────────────────
# A forked JVM writes its hs_err log to its working directory unless
# -XX:ErrorFile says otherwise; the fatal error banner names the path.
hs_err_count=0
collect_hs_err() {
    local file="$1" dest
    [ -f "$file" ] || return 0
    dest="${HS_ERR_DIR}/$(basename "$file")"
    [ -e "$dest" ] && return 0
    cp "$file" "$dest"
    hs_err_count=$((hs_err_count + 1))
}
# No OUTPUT_DIR exclusion: find emits ./-relative paths an absolute OUTPUT_DIR
# cannot match, and collect_hs_err already dedupes by destination basename, so a
# file this run copied into HS_ERR_DIR is skipped if the search re-reaches it.
while IFS= read -r errfile; do
    collect_hs_err "$errfile"
done < <(find . -name 'hs_err_pid*.log' -type f 2>/dev/null || true)
while IFS= read -r errfile; do
    collect_hs_err "$errfile"
done < <(cat "$@" "${DUMPSTREAM_DIR}"/* 2>/dev/null \
    | grep -oE '/[^ '"'"'"]*hs_err_pid[0-9]+\.log' | sort -u || true)

if [ "$hs_err_count" -gt 0 ]; then
    FOUND_CRASHES="true"
    {
        echo ""
        echo "## hs_err files collected: ${hs_err_count}"
        for f in "${HS_ERR_DIR}"/*; do
            if [ -f "$f" ]; then
                echo ""
                echo "### $(basename "$f")"
                excerpt_hs_err "$f"
            fi
        done
    } >> "$SUMMARY_FILE"
fi

# ── Print bounded excerpts into the job log ────────────────────────
if [ "$hs_err_count" -gt 0 ] || [ "$dumpstream_count" -gt 0 ]; then
    echo "════ Native crash excerpts (full files in the crash-reports artifact) ════"
    print_excerpts hs_err "$HS_ERR_DIR"
    print_excerpts dumpstream "$DUMPSTREAM_DIR"
fi

if [ "$dumpstream_count" -gt 0 ]; then
    FOUND_CRASHES="true"
    {
        echo ""
        echo "## Dumpstream files collected: ${dumpstream_count}"
        for f in "${DUMPSTREAM_DIR}"/*; do
            if [ -f "$f" ]; then
                echo ""
                echo "### $(basename "$f")"
                # Include first 100 lines of each dumpstream (they can be large)
                head -n 100 "$f"
                line_count=$(wc -l < "$f")
                if [ "$line_count" -gt 100 ]; then
                    echo "... (truncated, ${line_count} total lines)"
                fi
            fi
        done
    } >> "$SUMMARY_FILE"
fi

if [ "$FOUND_CRASHES" = "false" ]; then
    echo "" >> "$SUMMARY_FILE"
    echo "# No crash indicators found in the provided log files." >> "$SUMMARY_FILE"
    # Clean up empty output dir so upload-artifact ignores it
    rm -rf "$OUTPUT_DIR"
fi
