#!/usr/bin/env bash
# ─── Reject change sets that only edit the base branch's tests ───────
#
# The tests on the base branch pass there; they are the suite the branch is
# measured against. A change set whose only content is edits to those
# tests — no production change, no new test, nothing else — cannot fix
# anything: it can only change what the suite says. Agents sent to fix a
# failing test have produced exactly that, and that is the one thing this
# check exists to reject (DECEPTION.md Countermeasure #8).
#
# What counts as an edit to the base branch's tests: a change to a test
# file that exists at the merge-base — Java under src/test/ or matching
# *Test*.java, Python matching test_*.py, *_test.py or tests/*.py — that
# adds no new test to it. Anything else in the change set (production code,
# a test file the branch introduced, a new test method or function, config,
# docs) makes it substantive, and the set passes.
#
# This is not the test-integrity rule. Whether an edit to an existing test
# weakens it is test-integrity-check's question (detect-test-hiding.sh,
# detect-python-test-hiding.sh), and it asks it of every branch. The CI file
# lock is check-ci-file-lock.sh, run by the `changes` job.
#
# Usage:
#   validate-agent-commit.sh <base-branch>
#
# Exit codes:
#   0 - the change set is substantive (or empty)
#   1 - invalid arguments, or the branch could not be diffed
#   3 - BLOCKED: the change set only edits base-branch tests
#
# Outputs (to GITHUB_OUTPUT if available):
#   blocked=true|false
#   block_reason=<reason>

set -euo pipefail

BASE_BRANCH="${1:-}"

if [ -z "$BASE_BRANCH" ]; then
    echo "Usage: $0 <base-branch>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_METHOD_LINES_AWK="${SCRIPT_DIR}/test-method-lines.awk"

output() {
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1" >> "$GITHUB_OUTPUT"
    fi
}

# Base-branch content is read from the merge-base, not the live tip of
# $BASE_BRANCH: the base keeps moving after a branch forks, and comparing
# against its tip would attribute the base branch's own later edits to the
# branch. No merge-base, no listing, or no diff means no evidence, which is
# a reason to stop rather than to pass.
if ! MERGE_BASE=$(git merge-base "$BASE_BRANCH" HEAD 2>&1); then
    echo "Cannot compute merge-base of ${BASE_BRANCH} and HEAD — the branch cannot be validated:" >&2
    echo "$MERGE_BASE" >&2
    output "blocked=true"
    output "block_reason=merge_base_unavailable"
    exit 1
fi

if ! BASE_FILES=$(git ls-tree -r --name-only "$MERGE_BASE" 2>&1); then
    echo "Cannot list files at merge-base ${MERGE_BASE} — the branch cannot be validated:" >&2
    echo "$BASE_FILES" >&2
    output "blocked=true"
    output "block_reason=merge_base_unavailable"
    exit 1
fi

# Renames are not detected, so renaming a test file reads as removing it and
# adding a new one — which is substantive, and test-integrity-check is what
# notices the removal.
if ! CHANGED=$(git diff --name-only --no-renames "${BASE_BRANCH}...HEAD" 2>&1); then
    echo "Cannot diff ${BASE_BRANCH}...HEAD — the branch cannot be validated:" >&2
    echo "$CHANGED" >&2
    output "blocked=true"
    output "block_reason=diff_unavailable"
    exit 1
fi

if [ -z "$CHANGED" ]; then
    echo "No files changed — nothing to validate."
    output "blocked=false"
    exit 0
fi

# Test names in one revision of a file, one line per test, sorted — Java
# from the shared test-method extractor, Python from its `def test_*` lines.
# Counting names rather than comparing whole methods is deliberate: an edited
# test is not a new one, and an added overload still is.
test_names() {
    local rev="$1" file="$2"
    case "$file" in
        *.py)
            git show "${rev}:${file}" 2>/dev/null \
                | sed -nE 's/^[[:space:]]*(async[[:space:]]+)?def[[:space:]]+(test_[A-Za-z0-9_]*).*/\2/p'
            ;;
        *)
            git show "${rev}:${file}" 2>/dev/null \
                | awk -f "$TEST_METHOD_LINES_AWK" -v mode=methods \
                | cut -f1
            ;;
    esac | LC_ALL=C sort
}

# Whether HEAD has a test the merge-base did not: `comm -13` over the
# name lists, which keep duplicates, so a second overload counts as new.
adds_a_test() {
    [ -n "$(LC_ALL=C comm -13 <(test_names "$MERGE_BASE" "$1") <(test_names HEAD "$1"))" ]
}

BASE_TEST_EDITS=""
SUBSTANTIVE=""

while IFS= read -r FILE; do
    [ -z "$FILE" ] && continue
    if printf '%s\n' "$FILE" | grep -qE '(src/test/|Test[^/]*\.java$)|(^|/)(test_[^/]*|[^/]*_test)\.py$|/tests/[^/]*\.py$' \
            && printf '%s\n' "$BASE_FILES" | grep -qxF "$FILE" \
            && ! adds_a_test "$FILE"; then
        BASE_TEST_EDITS="${BASE_TEST_EDITS}${FILE}\n"
    else
        SUBSTANTIVE="${SUBSTANTIVE}${FILE}\n"
    fi
done <<< "$CHANGED"

if [ -n "$SUBSTANTIVE" ]; then
    echo "The change set is substantive:"
    echo -e "$SUBSTANTIVE" | grep '[^[:space:]]' | sed 's/^/  - /'
    output "blocked=false"
    exit 0
fi

echo "::error::The change set only edits tests that exist on ${BASE_BRANCH}"
echo ""
echo "Every changed file is a test file from ${BASE_BRANCH}, and none of them gains a"
echo "new test. A change set like that cannot fix anything — it can only change what"
echo "the suite reports. Fix the code under test, or add the missing test."
echo ""
echo "Changed base-branch test files:"
echo -e "$BASE_TEST_EDITS" | grep '[^[:space:]]' | sed 's/^/  - /'
output "blocked=true"
output "block_reason=only_base_test_edits"
exit 3
