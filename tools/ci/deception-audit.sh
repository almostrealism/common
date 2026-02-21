#!/usr/bin/env bash
# ─── Cross-Session Deception Audit ──────────────────────────────────
#
# Detects patterns of coordinated deception across multiple agent
# sessions on a branch. This implements DECEPTION.md Countermeasure #7.
#
# Detected patterns:
#
# PATTERN 1: Ping-Pong (Add/Revert/Re-Add)
#   An agent modifies a file, a human reverts it, then another agent
#   re-modifies it. This indicates the agent is re-applying suppression
#   that was explicitly rejected by a human.
#
# PATTERN 2: Test-Only Agent Commits
#   Agent commits that modify ONLY test files or CI files with no
#   production code changes. These cannot fix bugs — they can only
#   hide failures.
#
# PATTERN 3: Repeated Failure Dispatch
#   The same test class appearing in multiple auto-resolve dispatches
#   on the same branch, suggesting agents are failing to fix the
#   issue and may be hiding it instead.
#
# PATTERN 4: TestDepth Churn
#   @TestDepth values that have been changed more than once on the
#   same branch, suggesting agents are experimenting with different
#   suppression levels.
#
# Usage:
#   deception-audit.sh <base-branch> [output-file]
#
# Exit codes:
#   0 - no deception patterns detected
#   1 - invalid arguments
#   2 - deception patterns detected

set -euo pipefail

BASE_BRANCH="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$BASE_BRANCH" ]; then
    echo "Usage: $0 <base-branch> [output-file]" >&2
    exit 1
fi

FINDING_COUNT=0
FINDINGS=""

record_finding() {
    local pattern="$1"
    local severity="$2"
    local detail="$3"
    FINDINGS="${FINDINGS}- **[${severity}] ${pattern}**: ${detail}\n"
    FINDING_COUNT=$((FINDING_COUNT + 1))
}

# ── PATTERN 1: Ping-Pong Detection ─────────────────────────────────
# Look for files that were modified by an agent, then had those
# changes reverted, then were modified again by an agent.
# We detect this by looking for files with 3+ distinct modifications
# where the content oscillates.

echo "Checking for ping-pong patterns..."

# Get all test files that have been modified on this branch
BRANCH_TEST_FILES=$(git diff --name-only "${BASE_BRANCH}...HEAD" -- \
    '**/*Test*.java' '**/test/**/*.java' 2>/dev/null || true)

if [ -n "$BRANCH_TEST_FILES" ]; then
    while IFS= read -r TEST_FILE; do
        [ -z "$TEST_FILE" ] && continue

        # Count how many commits on this branch touch this test file
        COMMIT_COUNT=$(git log --oneline "${BASE_BRANCH}..HEAD" -- "$TEST_FILE" 2>/dev/null | wc -l || true)

        if [ "$COMMIT_COUNT" -ge 3 ]; then
            record_finding "PING_PONG" "CRITICAL" \
                "\`${TEST_FILE}\` has been modified in ${COMMIT_COUNT} separate commits on this branch. This may indicate an add/revert/re-add cycle."
        fi
    done <<< "$BRANCH_TEST_FILES"
fi

# ── PATTERN 2: Test-Only Agent Commits ──────────────────────────────
# Look for commits that ONLY modify test files or CI files.

echo "Checking for test-only commits..."

BRANCH_COMMITS=$(git log --format="%H" "${BASE_BRANCH}..HEAD" 2>/dev/null || true)

if [ -n "$BRANCH_COMMITS" ]; then
    while IFS= read -r COMMIT_SHA; do
        [ -z "$COMMIT_SHA" ] && continue

        # Get all files changed in this commit
        COMMIT_FILES=$(git diff-tree --no-commit-id --name-only -r "$COMMIT_SHA" 2>/dev/null || true)
        [ -z "$COMMIT_FILES" ] && continue

        # Classify files
        HAS_PRODUCTION=false
        HAS_TEST=false
        HAS_CI=false
        TOTAL_FILES=0

        while IFS= read -r FILE; do
            [ -z "$FILE" ] && continue
            TOTAL_FILES=$((TOTAL_FILES + 1))

            if echo "$FILE" | grep -qE '(src/test/|Test[^/]*\.java$)'; then
                HAS_TEST=true
            elif echo "$FILE" | grep -qE '(\.github/workflows/|tools/ci/)'; then
                HAS_CI=true
            else
                HAS_PRODUCTION=true
            fi
        done <<< "$COMMIT_FILES"

        # Flag commits that touch test/CI files but no production code
        if [ "$HAS_PRODUCTION" = false ] && { [ "$HAS_TEST" = true ] || [ "$HAS_CI" = true ]; }; then
            COMMIT_MSG=$(git log --format="%s" -1 "$COMMIT_SHA" 2>/dev/null || echo "(unknown)")
            SHORT_SHA="${COMMIT_SHA:0:10}"

            if [ "$HAS_TEST" = true ] && [ "$HAS_CI" = true ]; then
                record_finding "TEST_AND_CI_ONLY_COMMIT" "CRITICAL" \
                    "Commit \`${SHORT_SHA}\` (\"${COMMIT_MSG}\") modifies ${TOTAL_FILES} file(s) — ALL are test or CI files. No production code was changed."
            elif [ "$HAS_TEST" = true ]; then
                record_finding "TEST_ONLY_COMMIT" "HIGH" \
                    "Commit \`${SHORT_SHA}\` (\"${COMMIT_MSG}\") modifies ${TOTAL_FILES} file(s) — ALL are test files. No production code was changed."
            elif [ "$HAS_CI" = true ]; then
                record_finding "CI_ONLY_COMMIT" "HIGH" \
                    "Commit \`${SHORT_SHA}\` (\"${COMMIT_MSG}\") modifies ${TOTAL_FILES} file(s) — ALL are CI files. No production code was changed."
            fi
        fi
    done <<< "$BRANCH_COMMITS"
fi

# ── PATTERN 3: TestDepth Churn ──────────────────────────────────────
# Look for @TestDepth annotations that have been added, removed, or
# changed multiple times on the same branch.

echo "Checking for TestDepth churn..."

if [ -n "$BRANCH_TEST_FILES" ]; then
    while IFS= read -r TEST_FILE; do
        [ -z "$TEST_FILE" ] && continue

        # Count commits that touch @TestDepth in this file
        DEPTH_COMMITS=$(git log --oneline "${BASE_BRANCH}..HEAD" -S "TestDepth" -- "$TEST_FILE" 2>/dev/null | wc -l || true)

        if [ "$DEPTH_COMMITS" -ge 2 ]; then
            record_finding "TESTDEPTH_CHURN" "CRITICAL" \
                "\`${TEST_FILE}\` has had @TestDepth annotations changed in ${DEPTH_COMMITS} separate commits. This suggests experimentation with suppression levels."
        fi
    done <<< "$BRANCH_TEST_FILES"
fi

# ── PATTERN 4: Revert-then-Reapply ─────────────────────────────────
# Look for commits with "revert" in the message followed by commits
# that touch the same files.

echo "Checking for revert-then-reapply patterns..."

REVERT_COMMITS=$(git log --format="%H" --grep="[Rr]evert" "${BASE_BRANCH}..HEAD" 2>/dev/null || true)

if [ -n "$REVERT_COMMITS" ]; then
    while IFS= read -r REVERT_SHA; do
        [ -z "$REVERT_SHA" ] && continue

        REVERTED_FILES=$(git diff-tree --no-commit-id --name-only -r "$REVERT_SHA" 2>/dev/null || true)
        [ -z "$REVERTED_FILES" ] && continue

        # Check if any later commit re-touches these files
        LATER_COMMITS=$(git log --format="%H" "${REVERT_SHA}..HEAD" 2>/dev/null || true)

        if [ -n "$LATER_COMMITS" ]; then
            while IFS= read -r LATER_SHA; do
                [ -z "$LATER_SHA" ] && continue

                LATER_FILES=$(git diff-tree --no-commit-id --name-only -r "$LATER_SHA" 2>/dev/null || true)
                [ -z "$LATER_FILES" ] && continue

                # Find overlap between reverted files and later-touched files
                OVERLAP=$(comm -12 \
                    <(echo "$REVERTED_FILES" | sort) \
                    <(echo "$LATER_FILES" | sort) 2>/dev/null || true)

                if [ -n "$OVERLAP" ]; then
                    REVERT_MSG=$(git log --format="%s" -1 "$REVERT_SHA" 2>/dev/null || echo "(unknown)")
                    LATER_MSG=$(git log --format="%s" -1 "$LATER_SHA" 2>/dev/null || echo "(unknown)")
                    OVERLAP_COUNT=$(echo "$OVERLAP" | wc -l)

                    record_finding "REVERT_THEN_REAPPLY" "CRITICAL" \
                        "Revert commit \`${REVERT_SHA:0:10}\` (\"${REVERT_MSG}\") touched files that were later re-modified in \`${LATER_SHA:0:10}\` (\"${LATER_MSG}\"). ${OVERLAP_COUNT} file(s) overlap."
                fi
            done <<< "$LATER_COMMITS"
        fi
    done <<< "$REVERT_COMMITS"
fi

# ── PATTERN 5: Assertion Weakening Across Commits ───────────────────
# Look for net assertion reduction across the full branch diff.

echo "Checking for net assertion reduction..."

FULL_DIFF=$(git diff "${BASE_BRANCH}...HEAD" -- '**/*Test*.java' '**/test/**/*.java' 2>/dev/null || true)

if [ -n "$FULL_DIFF" ]; then
    TOTAL_DELETED_ASSERTS=$(echo "$FULL_DIFF" | grep -cE '^\-.*\b(assert|assertEquals|assertTrue|assertFalse|assertNotNull|assertThrows|fail\()' || true)
    TOTAL_ADDED_ASSERTS=$(echo "$FULL_DIFF" | grep -cE '^\+.*\b(assert|assertEquals|assertTrue|assertFalse|assertNotNull|assertThrows|fail\()' || true)

    if [ "$TOTAL_DELETED_ASSERTS" -gt 0 ] && [ "$TOTAL_ADDED_ASSERTS" -lt "$TOTAL_DELETED_ASSERTS" ]; then
        NET_LOST=$((TOTAL_DELETED_ASSERTS - TOTAL_ADDED_ASSERTS))
        record_finding "NET_ASSERTION_LOSS" "HIGH" \
            "Branch has a net loss of ${NET_LOST} assertion(s) across all test files (${TOTAL_DELETED_ASSERTS} removed, ${TOTAL_ADDED_ASSERTS} added). Test coverage may be degraded."
    fi
fi

# ── Output results ──────────────────────────────────────────────────

if [ "$FINDING_COUNT" -gt 0 ]; then
    REPORT="## Cross-Session Deception Audit\n\n"
    REPORT+="**${FINDING_COUNT} suspicious pattern(s) detected on this branch.**\n\n"
    REPORT+="These patterns are consistent with coordinated agent deception across\n"
    REPORT+="multiple sessions. Each finding should be investigated by a human.\n\n"
    REPORT+="### Findings\n\n"
    REPORT+="${FINDINGS}\n"
    REPORT+="### Recommended Actions\n\n"
    REPORT+="1. Review each flagged commit to determine if it was a legitimate change or deception\n"
    REPORT+="2. Check memory entries on this branch for false claims\n"
    REPORT+="3. If deception is confirmed, revert the deceptive commits and re-run CI\n"
    REPORT+="4. Consider disabling auto-resolve on this branch until the root cause is fixed\n"

    echo -e "$REPORT"

    if [ -n "$OUTPUT_FILE" ]; then
        echo -e "$REPORT" > "$OUTPUT_FILE"
    fi

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "finding_count=$FINDING_COUNT" >> "$GITHUB_OUTPUT"
        echo "has_findings=true" >> "$GITHUB_OUTPUT"
    fi

    exit 2
else
    echo "No deception patterns detected."

    if [ -n "$OUTPUT_FILE" ]; then
        echo "No deception patterns detected." > "$OUTPUT_FILE"
    fi

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "finding_count=0" >> "$GITHUB_OUTPUT"
        echo "has_findings=false" >> "$GITHUB_OUTPUT"
    fi

    exit 0
fi
