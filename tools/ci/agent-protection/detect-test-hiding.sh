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
#   - Added @Ignore / @Disabled annotations on existing methods
#   - Deleted or commented-out assertions and test methods
#   - Weakened assertions (assertEquals -> assertTrue, etc.)
#   - Added try/catch blocks in existing methods that swallow exceptions
#   - Changed expected values in assertions
#   - Added skipLongTests guards to existing methods
#   - Added @TestDepth annotations to existing methods
#
# Method-scoping: patterns that flag *added* annotations or guards (1, 4, 5, 6)
# associate each suspicious + line with the method that owns it in HEAD, and
# only fire when that method also existed on the base branch. Method spans
# are computed by counting { and } from each declaration through its matching
# closing brace; "owns" ranges then extend each method to include the
# annotations/javadoc/blank lines that immediately precede its declaration.
# This prevents false positives when a branch adds brand-new test methods
# that legitimately carry @TestDepth, skipLongTests, etc.
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

# ── Method-scoping helpers ──────────────────────────────────────────────
#
# These distinguish "modified an existing test" from "added a brand-new test
# method." Patterns that fire on additions alone (added @Ignore, added
# @TestDepth, added skipLongTests, added catch blocks) are filtered through
# OWNS_RANGES so that lines inside brand-new test methods — and lines
# between methods that lead into a new method — are not attributed to a
# previous existing method.

# Lists method spans for a Java file as TAB-separated
# "START_LINE END_LINE METHOD_NAME". START_LINE is the declaration line;
# END_LINE is the line of the matching closing brace, found by counting {
# and } (with naive treatment of strings/comments — adequate for typical
# test files). Abstract / interface declarations terminated by ';' before
# any '{' are skipped.
list_method_spans() {
    awk '
        function reset() { in_method = 0; depth = 0; seen_open = 0; method_name = "" }
        BEGIN { reset() }
        /^[[:space:]]*(public|private|protected)[[:space:]].*\(/ {
            decl = $0
            sub(/\(.*$/, "", decl)
            n = split(decl, parts, /[[:space:]]+/)
            name = parts[n]
            if (name != "") {
                # Abandon any previous unfinished method (likely abstract).
                method_start = NR
                method_name = name
                in_method = 1
                depth = 0
                seen_open = 0
            }
        }
        in_method {
            line = $0
            L = length(line)
            for (i = 1; i <= L; i++) {
                c = substr(line, i, 1)
                if (c == "{") {
                    depth++
                    seen_open = 1
                } else if (c == "}") {
                    if (seen_open) {
                        depth--
                        if (depth == 0) {
                            print method_start "\t" NR "\t" method_name
                            reset()
                            break
                        }
                    }
                } else if (c == ";" && !seen_open) {
                    reset()
                    break
                }
            }
        }
    ' "$1"
}

# Converts method spans to "owns" ranges. Each method owns the lines from
# (previous method end + 1) through its own end, so annotations, javadoc,
# and blank lines preceding a declaration are attributed to the method
# they introduce. Output: TAB-separated "OWNS_START OWNS_END METHOD_NAME".
compute_owns_ranges() {
    awk -F'\t' '
        BEGIN { prev_end = 0 }
        {
            owns_start = prev_end + 1
            owns_end = $2
            print owns_start "\t" owns_end "\t" $3
            prev_end = $2
        }
    ' <<< "$1"
}

# Finds the method that owns a given line number, or empty if the line
# falls outside any methods range (imports, class header, file footer).
find_owning_method() {
    local owns_map="$1"
    local line_no="$2"
    [ -z "$owns_map" ] && return 0
    awk -F'\t' -v t="$line_no" '
        $1 <= t && $2 >= t { print $3; exit }
    ' <<< "$owns_map"
}

# Extracts (LINENO\tCONTENT) for each + line in a unified diff, where
# LINENO is the line number in the new file. Skips diff/index/+++/---
# header lines.
extract_added_lines_with_lineno() {
    awk '
        /^@@/ {
            if (match($0, /\+[0-9]+/)) {
                lineno = substr($0, RSTART + 1, RLENGTH - 1) + 0
            }
            in_hunk = 1
            next
        }
        /^\+\+\+/ || /^---/ || /^diff/ || /^index/ { in_hunk = 0; next }
        !in_hunk { next }
        /^\+/ {
            if (lineno != "") print lineno "\t" substr($0, 2)
            lineno++
            next
        }
        /^-/ { next }
        { lineno++ }
    ' <<< "$1"
}

# Filters added lines (LINENO\tCONTENT) to only those whose owning method
# existed on the base branch. Lines outside any method (imports, class
# header, gaps before the first method) are dropped — they are not test
# behaviour and should be caught by other rules if they matter.
filter_added_in_existing_methods() {
    local added="$1"
    local base_names="$2"
    local owns_map="$3"
    local ln content method

    [ -z "$added" ] && return 0
    [ -z "$base_names" ] && return 0

    while IFS=$'\t' read -r ln content; do
        [ -z "$ln" ] && continue
        method=$(find_owning_method "$owns_map" "$ln")
        [ -z "$method" ] && continue
        if grep -qxF "$method" <<< "$base_names"; then
            printf '%s\t%s\n' "$ln" "$content"
        fi
    done <<< "$added"
}

# ── File-level checks ───────────────────────────────────────────────────

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

    # Pre-compute method spans and added-line metadata once per file. The
    # method-scoped patterns below filter additions through OWNS_RANGES so
    # that lines inside brand-new test methods (or between methods, leading
    # into a new one) are not attributed to a previous existing method.
    BASE_FILE_TMP=$(mktemp)
    git show "${BASE_BRANCH}:${FILE}" > "$BASE_FILE_TMP" 2>/dev/null || true
    BASE_METHOD_NAMES=$(list_method_spans "$BASE_FILE_TMP" | cut -f3 | sort -u)
    HEAD_SPANS=$(list_method_spans "$FILE")
    OWNS_RANGES=$(compute_owns_ranges "$HEAD_SPANS")
    ADDED_LINES=$(extract_added_lines_with_lineno "$DIFF")
    ADDED_IN_EXISTING=$(filter_added_in_existing_methods \
        "$ADDED_LINES" "$BASE_METHOD_NAMES" "$OWNS_RANGES")
    rm -f "$BASE_FILE_TMP"

    # ── Pattern 1: Added @Ignore or @Disabled to EXISTING methods ──
    ADDED_IGNORE=$(echo "$ADDED_IN_EXISTING" | grep -cE '@(Ignore|Disabled)' || true)
    if [ "$ADDED_IGNORE" -gt 0 ]; then
        record_violation "$FILE" "ADDED_SKIP_ANNOTATION" \
            "Added ${ADDED_IGNORE} @Ignore/@Disabled annotation(s) to test method(s) that existed on the base branch"
    fi

    # ── Pattern 2: Deleted assertion lines (net) ──
    DELETED_ASSERTS=$(echo "$DIFF" | grep -cE '^\-.*\b(assert|Assert\.|assertEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|fail\()' || true)
    ADDED_ASSERTS=$(echo "$DIFF" | grep -cE '^\+.*\b(assert|Assert\.|assertEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|fail\()' || true)
    if [ "$DELETED_ASSERTS" -gt 0 ] && [ "$ADDED_ASSERTS" -lt "$DELETED_ASSERTS" ]; then
        NET_REMOVED=$((DELETED_ASSERTS - ADDED_ASSERTS))
        record_violation "$FILE" "NET_ASSERTIONS_REMOVED" \
            "Net ${NET_REMOVED} assertion(s) removed (${DELETED_ASSERTS} deleted, ${ADDED_ASSERTS} added)"
    fi

    # ── Pattern 3: Deleted @Test methods (net) ──
    DELETED_TEST_METHODS=$(echo "$DIFF" | grep -cE '^\-.*@Test' || true)
    ADDED_TEST_METHODS=$(echo "$DIFF" | grep -cE '^\+.*@Test' || true)
    if [ "$DELETED_TEST_METHODS" -gt 0 ] && [ "$ADDED_TEST_METHODS" -lt "$DELETED_TEST_METHODS" ]; then
        NET_REMOVED=$((DELETED_TEST_METHODS - ADDED_TEST_METHODS))
        record_violation "$FILE" "NET_TEST_METHODS_REMOVED" \
            "Net ${NET_REMOVED} @Test method(s) removed"
    fi

    # ── Pattern 4: Added catch in EXISTING methods that swallows exceptions ──
    # For each added catch line in an existing method, peek at the next 3
    # lines of the HEAD file to see whether the catch body rethrows or fails.
    SWALLOWED=0
    while IFS=$'\t' read -r ADDED_LN ADDED_CONTENT; do
        [ -z "$ADDED_LN" ] && continue
        if echo "$ADDED_CONTENT" | grep -qE 'catch[[:space:]]*\('; then
            NEXT_LINES=$(sed -n "$((ADDED_LN+1)),$((ADDED_LN+3))p" "$FILE" 2>/dev/null || true)
            if ! echo "$NEXT_LINES" | grep -qE '(throw|fail\(|Assert\.fail)'; then
                SWALLOWED=$((SWALLOWED + 1))
            fi
        fi
    done <<< "$ADDED_IN_EXISTING"
    if [ "$SWALLOWED" -gt 0 ]; then
        record_violation "$FILE" "EXCEPTION_SWALLOWING" \
            "Added ${SWALLOWED} catch block(s) in existing method(s) that may swallow exceptions without rethrowing or failing"
    fi

    # ── Pattern 5: Added skipLongTests guard to EXISTING methods ──
    ADDED_SKIP=$(echo "$ADDED_IN_EXISTING" | grep -cE 'skipLongTests' || true)
    if [ "$ADDED_SKIP" -gt 0 ]; then
        record_violation "$FILE" "ADDED_SKIP_GUARD" \
            "Added skipLongTests guard to test method(s) that existed on the base branch"
    fi

    # ── Pattern 6: Added @TestDepth to EXISTING methods ──
    ADDED_DEPTH=$(echo "$ADDED_IN_EXISTING" | grep -cE '@TestDepth' || true)
    if [ "$ADDED_DEPTH" -gt 0 ]; then
        record_violation "$FILE" "ADDED_TEST_DEPTH" \
            "Added @TestDepth annotation(s) to test method(s) that existed on the base branch"
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
    # Catches the specific tactic of changing @TestDepth(2) to @TestDepth(10).
    # Requires a removed @TestDepth in the diff, so purely additive branches
    # never trigger this — even when new methods have a high @TestDepth.
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
    # Catches reducing dimensions/sizes/iterations to make tests trivially pass.
    # Checks BOTH variable assignments (int x = N) AND method-call arguments
    # and constructor parameters where numeric literals decreased.
    #
    # This is the "Numeric Constant Freeze" from DECEPTION.md Countermeasure #3.
    # Evidence: AggressiveFineTuningTest dimensions reduced 8x (64->8, 32->4),
    # RotaryEmbeddingGradientTests dimensions reduced (1,8,32,64) -> (1,4,8,16).

    # Check variable assignments: int varName = NUMBER
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
                "${DECREASED_COUNT} numeric constant(s) in variable assignments reduced by more than half — may trivialize test coverage"
        fi
    fi

    # ── Pattern 11: Numeric literals in method calls DECREASED ──
    # Catches dimension reductions in method-call arguments like
    # someMethod(1, 8, 32, 64) -> someMethod(1, 4, 8, 16)
    # Extract all numeric literals > 4 from removed and added lines
    REMOVED_LITERALS=$(echo "$DIFF" | grep -E '^\-' | grep -oP '\b[0-9]+\b' | sort -rn | head -50 || true)
    ADDED_LITERALS=$(echo "$DIFF" | grep -E '^\+' | grep -oP '\b[0-9]+\b' | sort -rn | head -50 || true)
    if [ -n "$REMOVED_LITERALS" ] && [ -n "$ADDED_LITERALS" ]; then
        # Count how many large numbers disappeared and were replaced by smaller ones
        LARGE_REMOVED=0
        LARGE_ADDED=0
        while IFS= read -r VAL; do
            [ -z "$VAL" ] && continue
            if [ "$VAL" -ge 16 ]; then
                LARGE_REMOVED=$((LARGE_REMOVED + 1))
            fi
        done <<< "$REMOVED_LITERALS"
        while IFS= read -r VAL; do
            [ -z "$VAL" ] && continue
            if [ "$VAL" -ge 16 ]; then
                LARGE_ADDED=$((LARGE_ADDED + 1))
            fi
        done <<< "$ADDED_LITERALS"
        # If significantly more large numbers were removed than added, flag it
        if [ "$LARGE_REMOVED" -gt 2 ] && [ "$LARGE_ADDED" -lt "$((LARGE_REMOVED / 2))" ]; then
            NET_LOST=$((LARGE_REMOVED - LARGE_ADDED))
            record_violation "$FILE" "NUMERIC_LITERALS_SHRUNK" \
                "${NET_LOST} large numeric literal(s) (>=16) removed without replacement — may indicate dimension reduction in method arguments"
        fi
    fi

    # ── Pattern 12: Tolerance/epsilon values INCREASED ──
    # Catches weakening of floating-point comparisons by increasing
    # tolerance thresholds (e.g., 0.001 -> 0.1)
    REMOVED_TOLERANCES=$(echo "$DIFF" | grep -oP '^\-.*(?:tolerance|epsilon|delta|EPSILON|TOLERANCE)\s*[=,]\s*\K[0-9]*\.?[0-9]+' || true)
    ADDED_TOLERANCES=$(echo "$DIFF" | grep -oP '^\+.*(?:tolerance|epsilon|delta|EPSILON|TOLERANCE)\s*[=,]\s*\K[0-9]*\.?[0-9]+' || true)
    if [ -n "$REMOVED_TOLERANCES" ] && [ -n "$ADDED_TOLERANCES" ]; then
        OLD_TOL=$(echo "$REMOVED_TOLERANCES" | head -1)
        NEW_TOL=$(echo "$ADDED_TOLERANCES" | head -1)
        # Use awk for float comparison
        TOLERANCE_INCREASED=$(awk "BEGIN { print ($NEW_TOL > $OLD_TOL * 2) ? 1 : 0 }" 2>/dev/null || echo "0")
        if [ "$TOLERANCE_INCREASED" = "1" ]; then
            record_violation "$FILE" "TOLERANCE_WEAKENED" \
                "Tolerance/epsilon increased from ${OLD_TOL} to ${NEW_TOL} — weakens assertion precision"
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
