#!/usr/bin/env bash
# ─── Parse Surefire XML reports for test failures ────────────────────
#
# Scans a directory of Surefire XML reports and extracts a list of
# failing test methods in ClassName#methodName format.
#
# Usage:
#   parse-surefire-failures.sh <reports-dir> <output-file>
#
# Exit codes:
#   0 - completed successfully (check output for failure count)
#   1 - invalid arguments
#
# Outputs (to stdout, one per line):
#   failure_count=<N>
#   has_failures=true|false
#
# The output file will contain one "ClassName#methodName" per line.

set -euo pipefail

REPORTS_DIR="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$REPORTS_DIR" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <reports-dir> <output-file>" >&2
    exit 1
fi

FAILURE_COUNT=0
> "$OUTPUT_FILE"

for xml in $(find "$REPORTS_DIR" -name "TEST-*.xml" 2>/dev/null); do
    # Extract test class name from the testsuite element
    CLASS=$(sed -n 's/.*testsuite[^>]*name="\([^"]*\)".*/\1/p' "$xml" | head -1)

    # Find test methods that have <failure> or <error> children.
    # Uses awk to handle cases where <system-out> or other elements
    # sit between <testcase> and <failure>/<error> tags.
    # Note: uses POSIX awk (no gawk extensions) for portability.
    while IFS= read -r line; do
        if [ -n "$line" ]; then
            echo "- ${line}" >> "$OUTPUT_FILE"
            FAILURE_COUNT=$((FAILURE_COUNT + 1))
        fi
    done < <(awk -v suite_class="$CLASS" '
        /<testcase / {
            cname = ""; tname = "";
            s = $0;
            if (index(s, "classname=\"") > 0) {
                sub(/.*classname="/, "", s);
                sub(/".*/, "", s);
                cname = s;
            }
            s = $0;
            if (index(s, " name=\"") > 0) {
                sub(/.* name="/, "", s);
                sub(/".*/, "", s);
                tname = s;
            }
        }
        /<failure|<error/ {
            if (tname != "") {
                cls = (cname != "" ? cname : suite_class);
                print cls "#" tname;
            }
            tname = "";
        }
    ' "$xml")
done

echo "failure_count=$FAILURE_COUNT"
if [ "$FAILURE_COUNT" -gt 0 ]; then
    echo "has_failures=true"
else
    echo "has_failures=false"
fi
