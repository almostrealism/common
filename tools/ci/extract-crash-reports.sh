#!/usr/bin/env bash
# ─── Extract crash reports from Maven test output ──────────────────
#
# Parses Maven log files for JVM fork crash indicators (exit codes,
# crashed test class names) and collects .dumpstream files from
# surefire-reports directories.
#
# Usage:
#   extract-crash-reports.sh /tmp/mvn-utils-test.log /tmp/mvn-ml-test.log ...
#
# Output:
#   /tmp/crash-reports/crash-summary.txt   - consolidated crash info
#   /tmp/crash-reports/dumpstreams/         - collected .dumpstream files
#
# Exit codes:
#   0 - always succeeds (missing logs are skipped)

set -euo pipefail

OUTPUT_DIR="/tmp/crash-reports"
SUMMARY_FILE="${OUTPUT_DIR}/crash-summary.txt"
DUMPSTREAM_DIR="${OUTPUT_DIR}/dumpstreams"

mkdir -p "$OUTPUT_DIR" "$DUMPSTREAM_DIR"

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
        module_dir=$(echo "$dumpfile" | grep -oP '^\./\K[^/]+' || basename "$(dirname "$(dirname "$(dirname "$dumpfile")")")")
        dest_name="${module_dir}-$(basename "$dumpfile")"
        cp "$dumpfile" "${DUMPSTREAM_DIR}/${dest_name}"
        dumpstream_count=$((dumpstream_count + 1))
    fi
done < <(find . -path "*/surefire-reports/*.dumpstream" -type f 2>/dev/null || true)

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
