#!/usr/bin/env bash
# ─── Detect test-hiding modifications to Python tests ───────────────
#
# The Python counterpart of detect-test-hiding.sh, run as its own step of
# the test-integrity-check job. For every Python test file that exists at
# the merge-base and was changed on this branch, two properties must hold:
#
#   - every `def test_*` present at the merge-base is still present, which
#     is what deleting a test and renaming it away have in common;
#   - the file's assertion count does not fall, which is what gutting a
#     test body has in common with removing one.
#
# Editing the inside of a Python test is otherwise allowed. Java's detector
# can key on annotations and braces to scope its patterns to a method;
# an indentation-delimited Python body cannot be bounded as reliably, so
# this checks the two properties that survive that imprecision rather than
# claiming a finer check it cannot honestly make.
#
# A Python test file is test_*.py, *_test.py, or any module under a tests/
# directory. Files introduced on this branch are not checked.
#
# Usage:
#   detect-python-test-hiding.sh <base-branch> [output-file]
#
# Exit codes:
#   0 - no Python test weakened
#   1 - invalid arguments, or the branch could not be checked
#   2 - a base-branch Python test was removed or lost assertions
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

output() {
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1" >> "$GITHUB_OUTPUT"
    fi
}

# Base-branch content is read from the merge-base, not the moving tip of
# $BASE_BRANCH, so master's own later edits are never attributed to the
# branch. No merge-base means nothing could be checked, which is not a pass.
if ! MERGE_BASE=$(git merge-base "$BASE_BRANCH" HEAD 2>&1); then
    echo "Cannot compute merge-base of ${BASE_BRANCH} and HEAD — the branch cannot be checked:" >&2
    echo "$MERGE_BASE" >&2
    exit 1
fi

if ! CHANGED=$(git diff --name-only --no-renames "${MERGE_BASE}" HEAD 2>&1); then
    echo "Cannot diff ${MERGE_BASE}..HEAD — the branch cannot be checked:" >&2
    echo "$CHANGED" >&2
    exit 1
fi

if ! BASE_FILES=$(git ls-tree -r --name-only "$MERGE_BASE" 2>&1); then
    echo "Cannot list files at merge-base ${MERGE_BASE} — the branch cannot be checked:" >&2
    echo "$BASE_FILES" >&2
    exit 1
fi

# Names of the test functions in one revision of a file, sorted.
test_names() {
    git show "${1}:${2}" 2>/dev/null \
        | sed -nE 's/^[[:space:]]*(async[[:space:]]+)?def[[:space:]]+(test_[A-Za-z0-9_]*).*/\2/p' \
        | LC_ALL=C sort -u
}

# Assertions in one revision of a file: bare `assert` and the unittest and
# pytest forms, since this repository's Python tests use all three.
assertions() {
    git show "${1}:${2}" 2>/dev/null \
        | grep -cE '(^|[^A-Za-z0-9_.])(assert|self\.assert[A-Za-z_]+|self\.fail|pytest\.raises|assertRaises)\b' \
        || true
}

REPORT=""
VIOLATIONS=0

while IFS= read -r FILE; do
    [ -z "$FILE" ] && continue
    printf '%s\n' "$FILE" | grep -qE '(^|/)(test_[^/]*|[^/]*_test)\.py$|/tests/[^/]*\.py$' || continue
    printf '%s\n' "$BASE_FILES" | grep -qxF "$FILE" || continue

    removed=$(LC_ALL=C comm -23 <(test_names "$MERGE_BASE" "$FILE") <(test_names HEAD "$FILE") || true)
    if [ -n "$removed" ]; then
        VIOLATIONS=$((VIOLATIONS + 1))
        REPORT="${REPORT}- ${FILE}: test function(s) removed or renamed: $(printf '%s' "$removed" | tr '\n' ' ')\n"
    fi

    base_count=$(assertions "$MERGE_BASE" "$FILE")
    head_count=$(assertions HEAD "$FILE")
    if [ "$head_count" -lt "$base_count" ]; then
        VIOLATIONS=$((VIOLATIONS + 1))
        REPORT="${REPORT}- ${FILE}: assertions fell from ${base_count} to ${head_count}\n"
    fi
done <<< "$CHANGED"

output "violation_count=$VIOLATIONS"

if [ "$VIOLATIONS" -eq 0 ]; then
    echo "No base-branch Python test was removed or lost assertions."
    output "has_violations=false"
    exit 0
fi

{
    echo "## Python test integrity"
    echo ""
    echo "Base-branch Python tests were removed or lost assertions:"
    echo ""
    echo -e "$REPORT"
    echo "Restore them and fix the code under test instead."
} | tee ${OUTPUT_FILE:+"$OUTPUT_FILE"}
output "has_violations=true"
exit 2
