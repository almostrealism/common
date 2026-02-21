#!/usr/bin/env bash
# ─── Detect test-hiding modifications ─────────────────────────────────
#
# Compares the current branch against a base branch and flags any
# modifications to existing test files that look like they are hiding
# failures rather than fixing production code.
#
# Usage:
#   detect-test-hiding.sh <base-branch> [output-file]
#
# The script examines git diff output for test files that existed on the
# base branch and flags suspicious patterns:
#   - Added @Ignore / @Disabled annotations
#   - Deleted or commented-out assertions and test methods
#   - Weakened assertions (assertEquals -> assertTrue, etc.)
#   - Added try/catch blocks that swallow exceptions
#   - Changed expected values in assertions
#   - Added skipLongTests guards
#   - Added @TestDepth annotations
#
# Exit codes:
#   0 - no test-hiding detected (or only new test files modified)
#   1 - invalid arguments
#   2 - test-hiding patterns detected
#
# Outputs (to GITHUB_OUTPUT if available):
#   violation_count=<N>
#   has_violations=true|false

set -euo pipefail

BASE_BRANCH="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$BASE_BRANCH" ]; then
    echo "Usage: $0 <base-branch> [output-file]" >&2
    exit 1
fi

VIOLATION_COUNT=0
VIOLATIONS=""

# Helper to record a violation
record_violation() {
    local file="$1"
    local pattern="$2"
    local detail="$3"
    VIOLATIONS="${VIOLATIONS}- **${file}**: ${pattern} — ${detail}\n"
    VIOLATION_COUNT=$((VIOLATION_COUNT + 1))
}

# Get list of modified (not added) test files.
# We only care about files that existed on the base branch. New files (A status)
# are excluded because modifying your own new tests is acceptable.
MODIFIED_TEST_FILES=$(git diff --name-status "${BASE_BRANCH}...HEAD" -- \
    '**/*Test*.java' '**/test/**/*.java' \
    | grep -E '^M\s' \
    | awk '{print $2}' \
    || true)

if [ -z "$MODIFIED_TEST_FILES" ]; then
    echo "No existing test files were modified — nothing to check."
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "violation_count=0" >> "$GITHUB_OUTPUT"
        echo "has_violations=false" >> "$GITHUB_OUTPUT"
    fi
    exit 0
fi

FILE_COUNT=$(echo "$MODIFIED_TEST_FILES" | wc -l)
echo "Checking ${FILE_COUNT} modified test file(s) for test-hiding patterns..."

for FILE in $MODIFIED_TEST_FILES; do
    # Verify the file actually existed on the base branch (belt-and-suspenders)
    if ! git cat-file -e "${BASE_BRANCH}:${FILE}" 2>/dev/null; then
        continue
    fi

    # Get only the additions and deletions for this file
    DIFF=$(git diff "${BASE_BRANCH}...HEAD" -- "$FILE")

    # ── Pattern 1: Added @Ignore or @Disabled annotations ──
    ADDED_IGNORE=$(echo "$DIFF" | grep -cE '^\+.*@(Ignore|Disabled)' || true)
    if [ "$ADDED_IGNORE" -gt 0 ]; then
        record_violation "$FILE" "ADDED_SKIP_ANNOTATION" \
            "Added ${ADDED_IGNORE} @Ignore/@Disabled annotation(s) to an existing test file"
    fi

    # ── Pattern 2: Deleted assertion lines ──
    DELETED_ASSERTS=$(echo "$DIFF" | grep -cE '^\-.*\b(assert|Assert\.|assertEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|fail\()' || true)
    ADDED_ASSERTS=$(echo "$DIFF" | grep -cE '^\+.*\b(assert|Assert\.|assertEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|fail\()' || true)
    if [ "$DELETED_ASSERTS" -gt 0 ] && [ "$ADDED_ASSERTS" -lt "$DELETED_ASSERTS" ]; then
        NET_REMOVED=$((DELETED_ASSERTS - ADDED_ASSERTS))
        record_violation "$FILE" "NET_ASSERTIONS_REMOVED" \
            "Net ${NET_REMOVED} assertion(s) removed (${DELETED_ASSERTS} deleted, ${ADDED_ASSERTS} added)"
    fi

    # ── Pattern 3: Deleted @Test methods ──
    DELETED_TEST_METHODS=$(echo "$DIFF" | grep -cE '^\-.*@Test' || true)
    ADDED_TEST_METHODS=$(echo "$DIFF" | grep -cE '^\+.*@Test' || true)
    if [ "$DELETED_TEST_METHODS" -gt 0 ] && [ "$ADDED_TEST_METHODS" -lt "$DELETED_TEST_METHODS" ]; then
        NET_REMOVED=$((DELETED_TEST_METHODS - ADDED_TEST_METHODS))
        record_violation "$FILE" "NET_TEST_METHODS_REMOVED" \
            "Net ${NET_REMOVED} @Test method(s) removed"
    fi

    # ── Pattern 4: Added try/catch that swallows exceptions in tests ──
    # Look for added try/catch blocks where the catch is empty or just has a comment
    ADDED_CATCH=$(echo "$DIFF" | grep -cE '^\+.*catch\s*\(' || true)
    if [ "$ADDED_CATCH" -gt 0 ]; then
        # Check if the catch blocks swallow (no rethrow or fail())
        CATCH_WITH_RETHROW=$(echo "$DIFF" | grep -A3 '^\+.*catch\s*(' | grep -cE '^\+.*(throw|fail\(|Assert\.fail)' || true)
        SWALLOWED=$((ADDED_CATCH - CATCH_WITH_RETHROW))
        if [ "$SWALLOWED" -gt 0 ]; then
            record_violation "$FILE" "EXCEPTION_SWALLOWING" \
                "Added ${SWALLOWED} catch block(s) that may swallow exceptions without rethrowing or failing"
        fi
    fi

    # ── Pattern 5: Added skipLongTests guard ──
    ADDED_SKIP=$(echo "$DIFF" | grep -cE '^\+.*skipLongTests' || true)
    if [ "$ADDED_SKIP" -gt 0 ]; then
        record_violation "$FILE" "ADDED_SKIP_GUARD" \
            "Added skipLongTests guard to an existing test"
    fi

    # ── Pattern 6: Added @TestDepth annotation ──
    ADDED_DEPTH=$(echo "$DIFF" | grep -cE '^\+.*@TestDepth' || true)
    if [ "$ADDED_DEPTH" -gt 0 ]; then
        record_violation "$FILE" "ADDED_TEST_DEPTH" \
            "Added @TestDepth annotation(s) to push existing test(s) out of default run"
    fi

    # ── Pattern 7: Commented out test code ──
    ADDED_COMMENTED=$(echo "$DIFF" | grep -cE '^\+\s*//' | head -20 || true)
    DELETED_CODE=$(echo "$DIFF" | grep -cE '^\-\s*[^/]' || true)
    # Only flag if significant code was deleted AND comments were added in roughly the same amount
    if [ "$DELETED_CODE" -gt 5 ] && [ "$ADDED_COMMENTED" -gt "$((DELETED_CODE / 2))" ]; then
        record_violation "$FILE" "CODE_COMMENTED_OUT" \
            "Significant code deleted (${DELETED_CODE} lines) with many comment lines added (${ADDED_COMMENTED}) — possible commenting-out"
    fi

    # ── Pattern 8: @TestDepth value INCREASED (not just added) ──
    # Catches the specific tactic of changing @TestDepth(2) to @TestDepth(10)
    REMOVED_DEPTH_LINES=$(echo "$DIFF" | grep -oP '^\-.*@TestDepth\(\K[0-9]+' || true)
    ADDED_DEPTH_LINES=$(echo "$DIFF" | grep -oP '^\+.*@TestDepth\(\K[0-9]+' || true)
    if [ -n "$REMOVED_DEPTH_LINES" ] && [ -n "$ADDED_DEPTH_LINES" ]; then
        OLD_DEPTH=$(echo "$REMOVED_DEPTH_LINES" | head -1)
        NEW_DEPTH=$(echo "$ADDED_DEPTH_LINES" | head -1)
        if [ "$NEW_DEPTH" -gt "$OLD_DEPTH" ]; then
            record_violation "$FILE" "TEST_DEPTH_ESCALATED" \
                "@TestDepth increased from ${OLD_DEPTH} to ${NEW_DEPTH} — hides test from CI runs at lower depth"
        fi
    fi

    # ── Pattern 9: Timeout value INCREASED by more than 2x ──
    # Catches inflating timeouts to prevent timeout-based failure detection
    REMOVED_TIMEOUTS=$(echo "$DIFF" | grep -oP '^\-.*timeout\s*=\s*\K[0-9]+(\s*\*\s*[0-9]+)*' || true)
    ADDED_TIMEOUTS=$(echo "$DIFF" | grep -oP '^\+.*timeout\s*=\s*\K[0-9]+(\s*\*\s*[0-9]+)*' || true)
    if [ -n "$REMOVED_TIMEOUTS" ] && [ -n "$ADDED_TIMEOUTS" ]; then
        # Evaluate expressions like "5 * 60000" to get actual values
        for OLD_TIMEOUT_EXPR in $REMOVED_TIMEOUTS; do
            OLD_TIMEOUT=$((OLD_TIMEOUT_EXPR)) 2>/dev/null || OLD_TIMEOUT=0
            if [ "$OLD_TIMEOUT" -gt 0 ]; then
                for NEW_TIMEOUT_EXPR in $ADDED_TIMEOUTS; do
                    NEW_TIMEOUT=$((NEW_TIMEOUT_EXPR)) 2>/dev/null || NEW_TIMEOUT=0
                    if [ "$NEW_TIMEOUT" -gt "$((OLD_TIMEOUT * 2))" ]; then
                        record_violation "$FILE" "TIMEOUT_INFLATED" \
                            "Timeout increased by more than 2x (${OLD_TIMEOUT} -> ${NEW_TIMEOUT}) — may hide performance regressions"
                        break 2
                    fi
                done
            fi
        done
    fi

    # ── Pattern 10: Numeric constants DECREASED in test methods ──
    # Catches reducing dimensions/sizes/iterations to make tests trivially pass
    # Look for integer variable assignments where the value decreased
    REMOVED_ASSIGNMENTS=$(echo "$DIFF" | grep -oP '^\-\s*int\s+\w+\s*=\s*\K[0-9]+' || true)
    ADDED_ASSIGNMENTS=$(echo "$DIFF" | grep -oP '^\+\s*int\s+\w+\s*=\s*\K[0-9]+' || true)
    if [ -n "$REMOVED_ASSIGNMENTS" ] && [ -n "$ADDED_ASSIGNMENTS" ]; then
        DECREASED_COUNT=0
        while IFS= read -r OLD_VAL; do
            while IFS= read -r NEW_VAL; do
                if [ "$NEW_VAL" -lt "$((OLD_VAL / 2))" ] && [ "$OLD_VAL" -gt 4 ]; then
                    DECREASED_COUNT=$((DECREASED_COUNT + 1))
                fi
            done <<< "$ADDED_ASSIGNMENTS"
        done <<< "$REMOVED_ASSIGNMENTS"
        if [ "$DECREASED_COUNT" -gt 0 ]; then
            record_violation "$FILE" "DIMENSIONS_REDUCED" \
                "${DECREASED_COUNT} numeric constant(s) reduced by more than half — may trivialize test coverage"
        fi
    fi
done

# ── Output results ──
if [ "$VIOLATION_COUNT" -gt 0 ]; then
    REPORT="## Test Integrity Violations Detected\n\n"
    REPORT+="**${VIOLATION_COUNT} suspicious modification(s) found in existing test files.**\n\n"
    REPORT+="These patterns suggest tests are being modified to hide failures rather than\n"
    REPORT+="fixing the production code that the tests exercise. Tests that existed on\n"
    REPORT+="the base branch (\`${BASE_BRANCH}\`) are the specification — if this branch\n"
    REPORT+="breaks them, the production code must be fixed.\n\n"
    REPORT+="### Violations\n\n"
    REPORT+="${VIOLATIONS}\n"
    REPORT+="### What to do\n\n"
    REPORT+="Revert the test modifications and fix the production code instead. If you\n"
    REPORT+="believe the test itself is genuinely wrong, explain your reasoning in a PR\n"
    REPORT+="comment for human review.\n"

    echo -e "$REPORT"

    if [ -n "$OUTPUT_FILE" ]; then
        echo -e "$REPORT" > "$OUTPUT_FILE"
    fi

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "violation_count=$VIOLATION_COUNT" >> "$GITHUB_OUTPUT"
        echo "has_violations=true" >> "$GITHUB_OUTPUT"
    fi

    exit 2
else
    echo "No test-hiding patterns detected in modified test files."

    if [ -n "$OUTPUT_FILE" ]; then
        echo "No test-hiding patterns detected." > "$OUTPUT_FILE"
    fi

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "violation_count=0" >> "$GITHUB_OUTPUT"
        echo "has_violations=false" >> "$GITHUB_OUTPUT"
    fi

    exit 0
fi
