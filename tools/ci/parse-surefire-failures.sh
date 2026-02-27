#!/usr/bin/env bash
# ─── Parse Surefire XML reports for test failures ────────────────────
#
# Scans a directory of Surefire XML reports and extracts failing test
# methods along with their exception type, message, and a truncated
# stack trace.
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
# The output file contains one block per failure, formatted as:
#
#   - ClassName#methodName
#     Exception: <type>: <message>
#     Stack trace (truncated):
#       at com.example.Foo.bar(Foo.java:42)
#       at com.example.Baz.run(Baz.java:10)
#       ...
#
# Stack traces are truncated to 8 lines to keep prompts manageable.

set -euo pipefail

REPORTS_DIR="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$REPORTS_DIR" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <reports-dir> <output-file>" >&2
    exit 1
fi

MAX_STACKTRACE_LINES=8
FAILURE_COUNT=0
> "$OUTPUT_FILE"

for xml in $(find "$REPORTS_DIR" -name "TEST-*.xml" 2>/dev/null); do
    # Extract test class name from the testsuite element
    CLASS=$(sed -n 's/.*testsuite[^>]*name="\([^"]*\)".*/\1/p' "$xml" | head -1)

    # Parse testcase elements with failure/error children.
    # Outputs tab-separated: testclass \t methodname \t type \t message \t stacktrace
    # Stack trace has newlines replaced with ␤ (U+2424) for single-line transport.
    while IFS=$'\t' read -r tc_class tc_method fail_type fail_msg fail_trace; do
        if [ -z "$tc_method" ]; then
            continue
        fi

        cls="${tc_class:-$CLASS}"
        echo "- ${cls}#${tc_method}" >> "$OUTPUT_FILE"

        # Exception line: "type: message" (decode XML entities)
        if [ -n "$fail_type" ] || [ -n "$fail_msg" ]; then
            exc_line=""
            if [ -n "$fail_type" ] && [ -n "$fail_msg" ]; then
                exc_line="${fail_type}: ${fail_msg}"
            elif [ -n "$fail_type" ]; then
                exc_line="${fail_type}"
            else
                exc_line="${fail_msg}"
            fi
            exc_line=$(echo "$exc_line" | sed 's/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g; s/&quot;/"/g; s/&apos;/'"'"'/g')
            echo "  Exception: ${exc_line}" >> "$OUTPUT_FILE"
        fi

        # Stack trace (truncated)
        if [ -n "$fail_trace" ]; then
            # Convert ␤ back to newlines, take first N lines
            trace_lines=$(echo "$fail_trace" | tr '␤' '\n' | head -n "$MAX_STACKTRACE_LINES")
            line_count=$(echo "$fail_trace" | tr '␤' '\n' | wc -l | tr -d ' ')
            echo "  Stack trace (truncated):" >> "$OUTPUT_FILE"
            while IFS= read -r tline; do
                # Only include lines that look like stack frames or the exception line
                trimmed=$(echo "$tline" | sed 's/^[[:space:]]*//; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g; s/&quot;/"/g')
                if [ -n "$trimmed" ]; then
                    echo "    ${trimmed}" >> "$OUTPUT_FILE"
                fi
            done <<< "$trace_lines"
            if [ "$line_count" -gt "$MAX_STACKTRACE_LINES" ]; then
                echo "    ... ($((line_count - MAX_STACKTRACE_LINES)) more lines)" >> "$OUTPUT_FILE"
            fi
        fi

        echo "" >> "$OUTPUT_FILE"
        FAILURE_COUNT=$((FAILURE_COUNT + 1))
    done < <(awk -v suite_class="$CLASS" -v max_trace="$MAX_STACKTRACE_LINES" '
        BEGIN { OFS = "\t" }

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
            in_failure = 0;
            fail_type = "";
            fail_msg = "";
            fail_trace = "";
        }

        /<failure|<error/ {
            in_failure = 1;
            # Extract type attribute
            s = $0;
            if (index(s, "type=\"") > 0) {
                sub(/.*type="/, "", s);
                sub(/".*/, "", s);
                fail_type = s;
            }
            # Extract message attribute
            s = $0;
            if (index(s, "message=\"") > 0) {
                sub(/.*message="/, "", s);
                sub(/".*/, "", s);
                fail_msg = s;
            }
            # Check if this is a self-closing tag
            if (index($0, "/>") > 0) {
                if (tname != "") {
                    cls = (cname != "" ? cname : suite_class);
                    print cls, tname, fail_type, fail_msg, "";
                }
                in_failure = 0;
                tname = "";
                next;
            }
            # Check for inline content after the opening tag
            s = $0;
            sub(/.*>/, "", s);
            if (s != "" && s !~ /^[[:space:]]*$/) {
                fail_trace = s;
            }
            next;
        }

        in_failure && (/<\/failure>/ || /<\/error>/) {
            if (tname != "") {
                cls = (cname != "" ? cname : suite_class);
                # Replace newlines in trace with ␤ for single-line output
                gsub(/\n/, "␤", fail_trace);
                print cls, tname, fail_type, fail_msg, fail_trace;
            }
            in_failure = 0;
            tname = "";
            next;
        }

        in_failure {
            # Accumulate stack trace lines, using ␤ as line separator
            if (fail_trace != "") {
                fail_trace = fail_trace "␤" $0;
            } else {
                fail_trace = $0;
            }
        }
    ' "$xml")
done

echo "failure_count=$FAILURE_COUNT"
if [ "$FAILURE_COUNT" -gt 0 ]; then
    echo "has_failures=true"
else
    echo "has_failures=false"
fi
