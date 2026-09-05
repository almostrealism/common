#!/usr/bin/env bash
# ─── Validate an agent's commit for deception patterns ──────────────
#
# This script enforces three absolute rules on agent commits:
#
# RULE 1 (Test Method Write Lock - DECEPTION.md Countermeasure #2):
#   An agent CANNOT change or remove a test METHOD that exists on the
#   base branch. Every test method present on the base image of a
#   changed test file must still be present on HEAD, byte for byte,
#   including its @Test annotation block and its signature. This
#   eliminates TestDepth escalation, dimension reduction, timeout
#   inflation, annotation suppression, deletion, and every other
#   test-hiding tactic.
#
#   ADDING a test method to an existing test class is allowed. A test
#   that did not exist on the base branch cannot conceal the failure of
#   one that did, so an agent asked to widen coverage does not have to
#   scatter new tests into new files to get past the gate.
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
#   add something that did not exist before: a production change, a
#   branch-introduced test file, or a new test method in an existing test
#   file. Commits confined to edits of base-branch tests, CI files and
#   config are rejected because they cannot fix a test failure — they can
#   only hide one.
#
# RULE 3 (CI/Workflow File Lock - DECEPTION.md Countermeasure #9):
#   An agent CANNOT modify CI workflow files (.github/workflows/) or
#   CI tooling (tools/ci/) to exclude, skip, or disable tests or
#   quality checks. This prevents pipeline manipulation.
#
#   Branches named ci/... are exempt from this rule. The branch name is
#   chosen before the work starts and is visible in the PR title, so it
#   is a declaration that the subject of the change IS the pipeline,
#   reviewed as such. It cannot be adopted after the fact to rescue a
#   commit that was really about something else.
#
# SENSITIVE-FILE BYPASS:
#   A commit carrying a `Sensitive-File-Bypass: <job-id>=<signature>`
#   trailer signed by the controller lifts RULE 1 and RULE 3 for the
#   branch. The signing secret (AR_AGENT_BYPASS_SECRET) is not in the
#   agent's environment and the harness strips any agent-written trailer
#   before appending the controller's, so an agent cannot authorise
#   itself — see SensitiveFileBypassTrailer in flowtree/runtime and
#   verify-sensitive-bypass.sh. Without the secret in the environment no
#   bypass is possible, which is the safe direction to fail.
#
# Usage:
#   validate-agent-commit.sh <base-branch> [--require-production-changes]
#
# Exit codes:
#   0 - commit is valid
#   1 - invalid arguments, or the branch could not be diffed
#   2 - BLOCKED: base-branch test methods were changed or removed
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
VERIFY_BYPASS="${SCRIPT_DIR}/verify-sensitive-bypass.sh"

# ── Branches that may change the pipeline ───────────────────────────
CI_BRANCH_PATTERN='^ci/'

# ── Reports the branch under validation ─────────────────────────────
#
# The workspace is usually a detached checkout of a pull request head,
# where `git branch --show-current` is empty, so the Actions environment
# is consulted first: GITHUB_HEAD_REF names the source branch of a pull
# request, GITHUB_REF_NAME the branch of a direct push.
current_branch() {
    if [ -n "${GITHUB_HEAD_REF:-}" ]; then
        printf '%s\n' "$GITHUB_HEAD_REF"
    elif [ -n "${GITHUB_REF_NAME:-}" ]; then
        printf '%s\n' "$GITHUB_REF_NAME"
    else
        git branch --show-current 2>/dev/null || true
    fi
}

CURRENT_BRANCH="$(current_branch)"

# ── Reports whether the branch carries a signed bypass ──────────────
#
# Each commit on the branch is verified separately rather than the
# concatenation of all of them, so that a forged trailer in one commit
# cannot mask the controller's signature in another. The verified job ID
# is echoed for the audit trail.
bypass_job_id() {
    local sha msgfile jobid

    [ -n "${AR_AGENT_BYPASS_SECRET:-}" ] || return 1
    [ -r "$VERIFY_BYPASS" ] || return 1

    msgfile=$(mktemp)
    trap 'rm -f "$msgfile"' RETURN

    for sha in $(git rev-list "${BASE_BRANCH}..HEAD"); do
        git log -1 --format='%B' "$sha" > "$msgfile"
        if jobid=$(bash "$VERIFY_BYPASS" "$msgfile" 2>/dev/null); then
            printf '%s\n' "$jobid"
            return 0
        fi
    done

    return 1
}

BYPASS_JOB_ID="$(bypass_job_id || true)"

# ── Reports the test methods of one revision of a file ──────────────
#
# One record per test method, "<name><TAB><body>", sorted so the two
# revisions can be compared with comm. The body carries the annotation
# block and the signature as well as the statements, so a changed
# @TestDepth or timeout makes the record differ just as a changed
# assertion does.
test_methods() {
    local rev="$1" file="$2"

    git show "${rev}:${file}" 2>/dev/null \
        | awk -f "$TEST_METHOD_LINES_AWK" -v mode=methods \
        | LC_ALL=C sort -u
}

# ── Reports how a base-branch test file was changed ─────────────────
#
# Prints one of:
#   modified  a test method that exists on the base branch was edited,
#             renamed or removed — its record is absent from HEAD
#   added     no existing test method changed, and at least one new test
#             method appeared
#   support   no test method changed either way; only fixtures, helpers
#             or other non-test members of the class were touched
classify_test_file() {
    local file="$1" base head

    base=$(test_methods "$BASE_BRANCH" "$file")
    head=$(test_methods HEAD "$file")

    if [ -n "$base" ] && LC_ALL=C comm -23 \
            <(printf '%s\n' "$base") \
            <(printf '%s\n' "$head") | grep -q '[^[:space:]]'; then
        echo "modified"
    elif [ -n "$head" ] && LC_ALL=C comm -13 \
            <(printf '%s\n' "$base") \
            <(printf '%s\n' "$head") | grep -q '[^[:space:]]'; then
        echo "added"
    else
        echo "support"
    fi
}

# ── Classify all changed files ──────────────────────────────────────
#
# Renames are not detected, so a renamed file appears as a deletion of
# the old path and an addition of the new one. That is what makes a test
# file's disappearance visible: renaming a test class would otherwise
# present it as branch-introduced and unlock every method in it.

# A diff that cannot be taken — an unknown base ref, a shallow clone
# without the merge base — must not read as "nothing changed". The
# validator has no evidence in that case, and no evidence is a reason to
# stop, not a reason to pass.
if ! ALL_CHANGED_FILES=$(git diff --name-only --no-renames "${BASE_BRANCH}...HEAD" 2>&1); then
    echo "Cannot diff ${BASE_BRANCH}...HEAD — the branch cannot be validated:" >&2
    echo "$ALL_CHANGED_FILES" >&2

    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "blocked=true" >> "$GITHUB_OUTPUT"
        echo "block_reason=diff_unavailable" >> "$GITHUB_OUTPUT"
    fi

    exit 1
fi

if [ -z "$ALL_CHANGED_FILES" ]; then
    echo "No files changed — nothing to validate."
    exit 0
fi

BASE_TEST_FILES=""
BASE_ADDED_FILES=""
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
            # Only changes reaching an existing test method are locked;
            # added test methods, fixtures and helpers are ordinary code
            case "$(classify_test_file "$FILE")" in
                modified) BASE_TEST_FILES="${BASE_TEST_FILES}${FILE}\n" ;;
                added)    BASE_ADDED_FILES="${BASE_ADDED_FILES}${FILE}\n" ;;
                *)        BASE_SUPPORT_FILES="${BASE_SUPPORT_FILES}${FILE}\n" ;;
            esac
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
BASE_ADDED_COUNT=$(echo -e "$BASE_ADDED_FILES" | grep -c '[^[:space:]]' || true)
BASE_SUPPORT_COUNT=$(echo -e "$BASE_SUPPORT_FILES" | grep -c '[^[:space:]]' || true)
BRANCH_TEST_COUNT=$(echo -e "$BRANCH_TEST_FILES" | grep -c '[^[:space:]]' || true)
CI_COUNT=$(echo -e "$CI_FILES" | grep -c '[^[:space:]]' || true)
PRODUCTION_COUNT=$(echo -e "$PRODUCTION_FILES" | grep -c '[^[:space:]]' || true)

echo "File classification:"
echo "  Test methods changed (on base):   $BASE_TEST_COUNT"
echo "  Test methods added (on base):     $BASE_ADDED_COUNT"
echo "  Test support (existing on base):  $BASE_SUPPORT_COUNT"
echo "  Test files (branch-introduced):   $BRANCH_TEST_COUNT"
echo "  CI/workflow files:                $CI_COUNT"
echo "  Production files:                 $PRODUCTION_COUNT"

if [ -n "$BYPASS_JOB_ID" ]; then
    echo "Sensitive-file bypass verified for job ${BYPASS_JOB_ID} — RULE 1 and RULE 3 are lifted."
fi

# ── RULE 1: Test Method Write Lock ──────────────────────────────────

if [ "$BASE_TEST_COUNT" -gt 0 ] && [ -z "$BYPASS_JOB_ID" ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  BLOCKED: AGENT CHANGED TEST METHODS THAT EXIST ON BASE BRANCH  ║"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║  Agents are NEVER allowed to change or remove a test method    ║"
    echo "║  that exists on the base branch. This is not a pattern check — ║"
    echo "║  ANY change inside an existing test method is a violation.     ║"
    echo "║                                                                ║"
    echo "║  ADDING new test methods to the same class IS allowed, as are  ║"
    echo "║  fixtures and helpers. Tests introduced on this branch are     ║"
    echo "║  modifiable in full.                                           ║"
    echo "║  Evidence of why this rule exists: see DECEPTION.md            ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Files with changed or removed base-branch test methods:"
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

CI_EXEMPT=""
if [ -n "$CURRENT_BRANCH" ] && echo "$CURRENT_BRANCH" | grep -qE "$CI_BRANCH_PATTERN"; then
    CI_EXEMPT="branch"
elif [ -n "$BYPASS_JOB_ID" ]; then
    CI_EXEMPT="bypass"
fi

if [ "$CI_COUNT" -gt 0 ] && [ -n "$CI_EXEMPT" ]; then
    if [ "$CI_EXEMPT" = "branch" ]; then
        echo "Branch ${CURRENT_BRANCH} is a CI branch — RULE 3 does not apply to it."
    fi
fi

if [ "$CI_COUNT" -gt 0 ] && [ -z "$CI_EXEMPT" ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  BLOCKED: AGENT MODIFIED CI/WORKFLOW FILES                     ║"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║  Agents are NEVER allowed to modify CI workflow files or CI    ║"
    echo "║  tooling scripts. Agents have previously excluded quality      ║"
    echo "║  checks, removed quality gates, and manipulated job            ║"
    echo "║  dependencies to avoid running tests.                          ║"
    echo "║                                                                ║"
    echo "║  Work whose subject IS the pipeline belongs on a branch named  ║"
    echo "║  ci/... chosen before the work starts.                         ║"
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
    # Allow commits that change production code, branch-introduced test
    # files, or existing test files that gained a new test method. All three
    # add something that did not exist; only edits confined to base-branch
    # tests, CI and config are incapable of fixing a failure.
    #
    # Where the CI lock has been lifted — a ci/... branch, or a signed
    # bypass — the pipeline IS the work, so CI files count as substantive
    # too. Everywhere else they remain incapable of fixing anything.
    SUBSTANTIVE_COUNT=$((PRODUCTION_COUNT + BRANCH_TEST_COUNT + BASE_ADDED_COUNT))
    if [ -n "$CI_EXEMPT" ]; then
        SUBSTANTIVE_COUNT=$((SUBSTANTIVE_COUNT + CI_COUNT))
    fi
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
echo "  $BASE_ADDED_COUNT existing test file(s) gained new test method(s)."
echo "  $BASE_TEST_COUNT base-branch test method(s) changed or removed."
echo "  $CI_COUNT CI file(s) modified."

if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "blocked=false" >> "$GITHUB_OUTPUT"
    echo "modified_test_files=$BASE_TEST_COUNT" >> "$GITHUB_OUTPUT"
    echo "modified_ci_files=$CI_COUNT" >> "$GITHUB_OUTPUT"
    echo "modified_production_files=$PRODUCTION_COUNT" >> "$GITHUB_OUTPUT"
fi

exit 0
