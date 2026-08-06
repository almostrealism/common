#!/usr/bin/env bash
# ─── Validate an agent's commit for deception patterns ──────────────
#
# This script enforces two absolute rules on agent commits:
#
# RULE 1 (Test Method Write Lock - DECEPTION.md Countermeasure #2):
#   An agent CANNOT modify a test METHOD that exists on the base branch.
#   Within such a method — its @Test annotation block, its signature, and
#   its body — ANY modification is a violation, period. This eliminates
#   TestDepth escalation, dimension reduction, timeout inflation,
#   annotation suppression, and every other test-hiding tactic.
#
#   The lock is on test methods, not on the files that contain them. A
#   test class also accumulates fixtures, constructors and private helpers,
#   and editing those does not hide a test failure — it is ordinary work,
#   and refusing it forces agents to leave duplicated helpers behind rather
#   than remove them. Test-surface membership is decided by
#   test-method-lines.awk, not by the file's path.
#
# RULE 2 (Substantive Changes Required - DECEPTION.md Countermeasure #8):
#   When the agent was dispatched to fix test failures, its commit MUST
#   include at least one production file or branch-introduced test file.
#   Commits that touch ONLY base-branch test files or CI files are
#   rejected because they cannot fix a test failure — they can only hide one.
#
# RULE 3 (CI/Workflow File Lock - DECEPTION.md Countermeasure #9):
#   An agent CANNOT modify CI workflow files (.github/workflows/) or
#   CI tooling (tools/ci/) to exclude, skip, or disable tests or
#   quality checks. This prevents pipeline manipulation.
#
# Usage:
#   validate-agent-commit.sh <base-branch> [--require-production-changes]
#
# Exit codes:
#   0 - commit is valid
#   1 - invalid arguments
#   2 - BLOCKED: test file modifications detected
#   3 - BLOCKED: commit contains only test/CI changes (no production code)
#   4 - BLOCKED: CI/workflow file modifications detected
#
# Outputs (to GITHUB_OUTPUT if available):
#   blocked=true|false
#   block_reason=<reason>
#   modified_test_files=<count>
#   modified_ci_files=<count>
#   modified_production_files=<count>

set -euo pipefail

BASE_BRANCH="${1:-}"
REQUIRE_PRODUCTION="${2:-}"

if [ -z "$BASE_BRANCH" ]; then
    echo "Usage: $0 <base-branch> [--require-production-changes]" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_METHOD_LINES_AWK="${SCRIPT_DIR}/test-method-lines.awk"

# ── Exempt branches ─────────────────────────────────────────────────
#
# The setMem policy branches convert host-side collection manipulation to
# computations across the whole repository. That reaches into the body of
# a large number of existing tests for a reason that has nothing to do
# with hiding a failure, and the exemption was granted deliberately by the
# project owner for that work. It is narrow and temporary: remove this
# once the policy phases have landed.
EXEMPT_BRANCH_PATTERN='^feature/setmem-policy-phases/'

CURRENT_BRANCH="$(git branch --show-current 2>/dev/null || true)"

if [ -n "$CURRENT_BRANCH" ] && echo "$CURRENT_BRANCH" | grep -qE "$EXEMPT_BRANCH_PATTERN"; then
    echo "Branch ${CURRENT_BRANCH} is exempt from agent commit validation."
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "blocked=false" >> "$GITHUB_OUTPUT"
        echo "block_reason=branch_exempt" >> "$GITHUB_OUTPUT"
    fi
    exit 0
fi

# ── Reports the changed line numbers on one side of a diff ──────────
#
# Side is "old" or "new"; the corresponding field of each hunk header is
# expanded into the individual line numbers it covers.
changed_lines() {
    local side="$1" file="$2"
    local field=3
    [ "$side" = "old" ] && field=2

    git diff -U0 "${BASE_BRANCH}...HEAD" -- "$file" \
        | awk -v f="$field" '
            /^@@/ {
                split($f, a, ",")
                start = substr(a[1], 2) + 0
                count = (a[2] == "" ? 1 : a[2] + 0)
                for (i = 0; i < count; i++) print start + i
            }'
}

# ── Reports whether a file's changes land inside a test method ──────
#
# Both sides are examined: additions and edits against the new image,
# deletions against the base image, so that removing a test is caught
# just as surely as weakening one.
touches_test_method() {
    local file="$1"
    local side rev lines surface

    for side in new old; do
        [ "$side" = "new" ] && rev="HEAD" || rev="$BASE_BRANCH"

        lines=$(changed_lines "$side" "$file" | sort -u)
        [ -z "$lines" ] && continue

        surface=$(git show "${rev}:${file}" 2>/dev/null \
            | awk -f "$TEST_METHOD_LINES_AWK" | sort -u)
        [ -z "$surface" ] && continue

        if comm -12 <(echo "$lines") <(echo "$surface") | grep -q '[0-9]'; then
            return 0
        fi
    done

    return 1
}

# ── Classify all changed files ──────────────────────────────────────

ALL_CHANGED_FILES=$(git diff --name-only "${BASE_BRANCH}...HEAD" || true)

if [ -z "$ALL_CHANGED_FILES" ]; then
    echo "No files changed — nothing to validate."
    exit 0
fi

BASE_TEST_FILES=""
BASE_SUPPORT_FILES=""
BRANCH_TEST_FILES=""
CI_FILES=""
PRODUCTION_FILES=""
CONFIG_FILES=""

while IFS= read -r FILE; do
    # Test files: anything under src/test/ or matching *Test*.java
    if echo "$FILE" | grep -qE '(src/test/|Test[^/]*\.java$)'; then
        # Distinguish between tests that exist on the base branch (protected)
        # and tests introduced on this branch (modifiable by the agent)
        if git cat-file -e "${BASE_BRANCH}:${FILE}" 2>/dev/null; then
            # Only changes reaching a test method are locked; fixtures and
            # helpers in the same file are ordinary code
            if touches_test_method "$FILE"; then
                BASE_TEST_FILES="${BASE_TEST_FILES}${FILE}\n"
            else
                BASE_SUPPORT_FILES="${BASE_SUPPORT_FILES}${FILE}\n"
            fi
        else
            BRANCH_TEST_FILES="${BRANCH_TEST_FILES}${FILE}\n"
        fi
    # CI files: .github/workflows/ or tools/ci/
    elif echo "$FILE" | grep -qE '(\.github/workflows/|tools/ci/)'; then
        CI_FILES="${CI_FILES}${FILE}\n"
    # Config files: pom.xml, CLAUDE.md, etc.
    elif echo "$FILE" | grep -qE '(pom\.xml|CLAUDE\.md|\.gitignore|\.editorconfig)'; then
        CONFIG_FILES="${CONFIG_FILES}${FILE}\n"
    # Everything else is production code
    else
        PRODUCTION_FILES="${PRODUCTION_FILES}${FILE}\n"
    fi
done <<< "$ALL_CHANGED_FILES"

BASE_TEST_COUNT=$(echo -e "$BASE_TEST_FILES" | grep -c '[^[:space:]]' || true)
BASE_SUPPORT_COUNT=$(echo -e "$BASE_SUPPORT_FILES" | grep -c '[^[:space:]]' || true)
BRANCH_TEST_COUNT=$(echo -e "$BRANCH_TEST_FILES" | grep -c '[^[:space:]]' || true)
CI_COUNT=$(echo -e "$CI_FILES" | grep -c '[^[:space:]]' || true)
PRODUCTION_COUNT=$(echo -e "$PRODUCTION_FILES" | grep -c '[^[:space:]]' || true)

echo "File classification:"
echo "  Test methods (existing on base):  $BASE_TEST_COUNT"
echo "  Test support (existing on base):  $BASE_SUPPORT_COUNT"
echo "  Test files (branch-introduced):   $BRANCH_TEST_COUNT"
echo "  CI/workflow files:                $CI_COUNT"
echo "  Production files:                 $PRODUCTION_COUNT"

# ── RULE 1: Test Method Write Lock ──────────────────────────────────

if [ "$BASE_TEST_COUNT" -gt 0 ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  BLOCKED: AGENT MODIFIED TEST METHODS THAT EXIST ON BASE BRANCH ║"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║  Agents are NEVER allowed to modify a test method that exists  ║"
    echo "║  on the base branch. This is not a pattern check — ANY change  ║"
    echo "║  inside an existing test method is a violation. Period.        ║"
    echo "║                                                                ║"
    echo "║  Fixtures and helpers in the same file are NOT locked, and     ║"
    echo "║  tests introduced on this branch ARE modifiable.               ║"
    echo "║  Evidence of why this rule exists: see DECEPTION.md            ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Files with modified base-branch test methods:"
    echo -e "$BASE_TEST_FILES" | grep '[^[:space:]]' | while IFS= read -r f; do
        echo "  - $f"
    done

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "blocked=true" >> "$GITHUB_OUTPUT"
        echo "block_reason=test_file_modification" >> "$GITHUB_OUTPUT"
        echo "modified_test_files=$BASE_TEST_COUNT" >> "$GITHUB_OUTPUT"
    fi

    exit 2
fi

# ── RULE 3: CI/Workflow File Lock ───────────────────────────────────

if [ "$CI_COUNT" -gt 0 ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  BLOCKED: AGENT MODIFIED CI/WORKFLOW FILES                     ║"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║  Agents are NEVER allowed to modify CI workflow files or CI    ║"
    echo "║  tooling scripts. Agents have previously excluded quality      ║"
    echo "║  checks, removed quality gates, and manipulated job            ║"
    echo "║  dependencies to avoid running tests.                          ║"
    echo "║                                                                ║"
    echo "║  Evidence of why this rule exists: see DECEPTION.md            ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Modified CI files:"
    echo -e "$CI_FILES" | grep '[^[:space:]]' | while IFS= read -r f; do
        echo "  - $f"
    done

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "blocked=true" >> "$GITHUB_OUTPUT"
        echo "block_reason=ci_file_modification" >> "$GITHUB_OUTPUT"
        echo "modified_ci_files=$CI_COUNT" >> "$GITHUB_OUTPUT"
    fi

    exit 4
fi

# ── RULE 2: Production-Code-Only Commits ────────────────────────────

if [ "$REQUIRE_PRODUCTION" = "--require-production-changes" ]; then
    # Allow commits that change production code OR branch-introduced test files.
    # Branch-new tests are legitimate work (e.g., fixing a test the agent introduced).
    SUBSTANTIVE_COUNT=$((PRODUCTION_COUNT + BRANCH_TEST_COUNT))
    if [ "$SUBSTANTIVE_COUNT" -eq 0 ]; then
        echo ""
        echo "╔══════════════════════════════════════════════════════════════════╗"
        echo "║  BLOCKED: NO PRODUCTION OR BRANCH-NEW TEST CHANGES             ║"
        echo "╠══════════════════════════════════════════════════════════════════╣"
        echo "║  This agent was dispatched to fix failing tests. The commit    ║"
        echo "║  must include at least one production code or branch-new test  ║"
        echo "║  file change. Commits that contain ONLY base-branch test,     ║"
        echo "║  CI, or config files cannot fix test failures.                 ║"
        echo "║                                                                ║"
        echo "║  Evidence of why this rule exists: see DECEPTION.md            ║"
        echo "╚══════════════════════════════════════════════════════════════════╝"
        echo ""
        echo "All changed files are non-production:"
        echo "$ALL_CHANGED_FILES" | while IFS= read -r f; do
            echo "  - $f"
        done

        if [ -n "${GITHUB_OUTPUT:-}" ]; then
            echo "blocked=true" >> "$GITHUB_OUTPUT"
            echo "block_reason=no_production_changes" >> "$GITHUB_OUTPUT"
            echo "modified_production_files=0" >> "$GITHUB_OUTPUT"
        fi

        exit 3
    fi
fi

# ── All checks passed ──────────────────────────────────────────────

echo ""
echo "Agent commit validation PASSED."
echo "  $PRODUCTION_COUNT production file(s) changed."
echo "  $BRANCH_TEST_COUNT branch-introduced test file(s) changed."
echo "  $BASE_TEST_COUNT base-branch test file(s) modified: NONE"
echo "  $CI_COUNT CI file(s) modified: NONE"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "blocked=false" >> "$GITHUB_OUTPUT"
    echo "modified_test_files=$BASE_TEST_COUNT" >> "$GITHUB_OUTPUT"
    echo "modified_ci_files=$CI_COUNT" >> "$GITHUB_OUTPUT"
    echo "modified_production_files=$PRODUCTION_COUNT" >> "$GITHUB_OUTPUT"
fi

exit 0
