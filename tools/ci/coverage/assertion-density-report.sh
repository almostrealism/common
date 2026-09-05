#!/usr/bin/env bash
# ─── Assertion-density report for new test methods ─────────────────────
#
# Report-only; NEVER fails the build. Counts assertion-shaped statements
# per test method introduced on this branch — either in a branch-new test
# file, or newly added to a test class that existed on the base branch —
# and reports the ratio of assertions to new methods, flagging any method
# with zero assertions.
#
# This is the coverage-qa pipeline's defense against its primary gaming
# risk: a test that calls a method to touch a line but asserts nothing
# raises line coverage without pinning any behavior. It is deliberately a
# signal for the human reviewer of a coverage-qa PR, not a merge gate — a
# low ratio or a zero-assertion method can be a legitimate
# parameterized-fixture helper as easily as a hollow test, and only a
# human can tell which.
#
# Usage:
#   assertion-density-report.sh <base-branch> [output-file]
#
# Exit codes:
#   0 - always. A report that could not be computed (bad base ref) still
#       exits 0 and says so in its output — this script informs, and an
#       inability to inform is not a reason to fail a pipeline stage.
#
# Outputs (to GITHUB_OUTPUT if available):
#   total_new_methods=<N>
#   total_assertions=<N>
#   zero_assertion_methods=<N>

set -uo pipefail

BASE_BRANCH="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$BASE_BRANCH" ]; then
    echo "Usage: $0 <base-branch> [output-file]" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_METHOD_LINES_AWK="${SCRIPT_DIR}/../agent-protection/test-method-lines.awk"

ASSERTION_RE='\b(assertEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|assertArrayEquals|assertSame|assertNotSame|Assert\.|fail\()'

# Counts assertion-shaped lines in a \001-joined method body (the format
# test-method-lines.awk emits in "methods" mode).
count_assertions() {
    printf '%s' "$1" | tr '\001' '\n' | grep -cE "$ASSERTION_RE" || true
}

# One record per test method, "<name><TAB><body>", for one revision of a file.
test_methods() {
    local rev="$1" file="$2"
    git show "${rev}:${file}" 2>/dev/null \
        | awk -f "$TEST_METHOD_LINES_AWK" -v mode=methods \
        | LC_ALL=C sort -u
}

if ! CHANGED_TEST_FILES=$(git diff --name-only --no-renames "${BASE_BRANCH}...HEAD" -- \
        '**/*Test*.java' '**/test/**/*.java' 2>&1); then
    echo "## Assertion Density Report"
    echo ""
    echo "Could not diff ${BASE_BRANCH}...HEAD — no report produced."
    [ -n "$OUTPUT_FILE" ] && printf 'Could not diff %s...HEAD — no report produced.\n' "$BASE_BRANCH" > "$OUTPUT_FILE"
    exit 0
fi

TOTAL_NEW_METHODS=0
TOTAL_ASSERTIONS=0
ZERO_ASSERTION_METHODS=""

while IFS= read -r FILE; do
    [ -z "$FILE" ] && continue
    [ -f "$FILE" ] || continue   # skip files deleted on this branch

    if git cat-file -e "${BASE_BRANCH}:${FILE}" 2>/dev/null; then
        BASE_RECORDS=$(test_methods "$BASE_BRANCH" "$FILE")
        HEAD_RECORDS=$(test_methods HEAD "$FILE")
        NEW_RECORDS=$(LC_ALL=C comm -13 \
            <(printf '%s\n' "$BASE_RECORDS") <(printf '%s\n' "$HEAD_RECORDS") || true)
    else
        NEW_RECORDS=$(test_methods HEAD "$FILE")
    fi

    [ -z "$NEW_RECORDS" ] && continue

    while IFS=$'\t' read -r NAME BODY; do
        [ -z "$NAME" ] && continue
        TOTAL_NEW_METHODS=$((TOTAL_NEW_METHODS + 1))
        COUNT=$(count_assertions "$BODY")
        TOTAL_ASSERTIONS=$((TOTAL_ASSERTIONS + COUNT))
        if [ "$COUNT" -eq 0 ]; then
            ZERO_ASSERTION_METHODS="${ZERO_ASSERTION_METHODS}- ${FILE}::${NAME}\n"
        fi
    done <<< "$NEW_RECORDS"
done <<< "$CHANGED_TEST_FILES"

ZERO_COUNT=$(echo -e "$ZERO_ASSERTION_METHODS" | grep -c '[^[:space:]]' || true)

REPORT="## Assertion Density Report\n\n"
if [ "$TOTAL_NEW_METHODS" -eq 0 ]; then
    REPORT+="No new test methods on this branch.\n"
else
    RATIO=$(awk -v a="$TOTAL_ASSERTIONS" -v m="$TOTAL_NEW_METHODS" 'BEGIN { printf "%.2f", a / m }')
    REPORT+="**${TOTAL_NEW_METHODS} new test method(s), ${TOTAL_ASSERTIONS} assertion(s) — ${RATIO} assertions/method.**\n\n"
    if [ "$ZERO_COUNT" -gt 0 ]; then
        REPORT+="**${ZERO_COUNT} new method(s) with zero assertions** — worth a second look; a test\n"
        REPORT+="with no assertion proves only that a line ran, never that behavior is correct:\n\n"
        REPORT+="${ZERO_ASSERTION_METHODS}\n"
    else
        REPORT+="No new test method has zero assertions.\n"
    fi
fi

echo -e "$REPORT"

if [ -n "$OUTPUT_FILE" ]; then
    echo -e "$REPORT" > "$OUTPUT_FILE"
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "total_new_methods=$TOTAL_NEW_METHODS" >> "$GITHUB_OUTPUT"
    echo "total_assertions=$TOTAL_ASSERTIONS" >> "$GITHUB_OUTPUT"
    echo "zero_assertion_methods=$ZERO_COUNT" >> "$GITHUB_OUTPUT"
fi

exit 0
