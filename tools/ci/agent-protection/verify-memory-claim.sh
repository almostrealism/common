#!/usr/bin/env bash
# ─── Memory Claim Verification ──────────────────────────────────────
#
# Cross-references agent memory claims against the actual git diff to
# detect false or misleading memory entries. This implements DECEPTION.md
# Countermeasure #4 (Memory Integrity Verification).
#
# The problem this solves: On feature/lora-gradients, an agent stored
# memory dff49e69 claiming "CollectionFeatures switching to
# CollectionSubsetComputation/CollectionConcatenateComputation... do
# NOT cause NormTests failures." The agent itself later admitted this
# was false. That memory poisoned future agent sessions.
#
# This script checks whether a "no changes needed" claim is plausible
# by verifying that the changed production files do not overlap with
# the failing test's module or import chain.
#
# Usage:
#   verify-memory-claim.sh <base-branch> <test-class> <test-module>
#
# The script outputs a verification report and exits:
#   0 - claim appears plausible (no changed files in test's module)
#   1 - invalid arguments
#   2 - claim is SUSPECT (changed files overlap with failing test's module)
#   3 - claim is UNVERIFIABLE (cannot determine dependency chain)

set -euo pipefail

BASE_BRANCH="${1:-}"
TEST_CLASS="${2:-}"
TEST_MODULE="${3:-}"

if [ -z "$BASE_BRANCH" ] || [ -z "$TEST_CLASS" ] || [ -z "$TEST_MODULE" ]; then
    echo "Usage: $0 <base-branch> <test-class> <test-module>" >&2
    echo "" >&2
    echo "Verifies whether a 'no changes needed' claim for a test failure" >&2
    echo "is plausible by checking if changed production files overlap" >&2
    echo "with the failing test's module or dependency chain." >&2
    exit 1
fi

echo "═══════════════════════════════════════════════════════════════"
echo "  Memory Claim Verification"
echo "  Test:   ${TEST_CLASS}"
echo "  Module: ${TEST_MODULE}"
echo "  Base:   ${BASE_BRANCH}"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# ── Step 1: Get all changed production files ────────────────────────

CHANGED_FILES=$(git diff --name-only "${BASE_BRANCH}...HEAD" \
    | grep -v 'src/test/' \
    | grep -v '.github/' \
    | grep -v 'tools/ci/' \
    | grep -v 'CLAUDE.md' \
    | grep -v 'DECEPTION.md' \
    || true)

if [ -z "$CHANGED_FILES" ]; then
    echo "No production files changed on this branch."
    echo "A 'no changes needed' claim is TRIVIALLY TRUE — there are no changes."
    echo ""
    echo "VERDICT: PLAUSIBLE (but suspicious — why does the branch exist?)"
    exit 0
fi

TOTAL_CHANGED=$(echo "$CHANGED_FILES" | wc -l)
echo "Changed production files: ${TOTAL_CHANGED}"
echo ""

# ── Step 2: Check module overlap ───────────────────────────────────

SAME_MODULE_FILES=""
DEPENDENCY_MODULE_FILES=""

while IFS= read -r FILE; do
    [ -z "$FILE" ] && continue

    # Check if file is in the same Maven module as the failing test
    if echo "$FILE" | grep -q "^${TEST_MODULE}/"; then
        SAME_MODULE_FILES="${SAME_MODULE_FILES}${FILE}\n"
    fi

    # Check if file is in a module that the test module depends on
    # (heuristic: modules like collect, algebra, hardware, io are
    # dependencies of almost everything)
    CORE_MODULES="collect algebra hardware io code graph relation"
    for MODULE in $CORE_MODULES; do
        if echo "$FILE" | grep -q "^${MODULE}/"; then
            DEPENDENCY_MODULE_FILES="${DEPENDENCY_MODULE_FILES}${FILE}\n"
        fi
    done
done <<< "$CHANGED_FILES"

SAME_MODULE_COUNT=$(echo -e "$SAME_MODULE_FILES" | grep -c '[^[:space:]]' || true)
DEP_MODULE_COUNT=$(echo -e "$DEPENDENCY_MODULE_FILES" | grep -c '[^[:space:]]' || true)

# ── Step 3: Try to find the test file and trace imports ─────────────

TEST_FILE=$(find . -name "${TEST_CLASS}.java" -path "*/test/*" 2>/dev/null | head -1 || true)

DIRECT_IMPORTS=""
if [ -n "$TEST_FILE" ] && [ -f "$TEST_FILE" ]; then
    echo "Test file found: ${TEST_FILE}"

    # Extract imports from the test file
    DIRECT_IMPORTS=$(grep '^import ' "$TEST_FILE" | sed 's/import //;s/;//' || true)

    if [ -n "$DIRECT_IMPORTS" ]; then
        echo "Direct imports: $(echo "$DIRECT_IMPORTS" | wc -l)"

        # Check if any changed file corresponds to an imported class
        IMPORT_OVERLAP=""
        while IFS= read -r FILE; do
            [ -z "$FILE" ] && continue

            # Convert file path to approximate class name
            # e.g., collect/src/main/java/org/almostrealism/collect/Foo.java -> org.almostrealism.collect.Foo
            CLASS_NAME=$(echo "$FILE" \
                | sed 's|.*/src/main/java/||' \
                | sed 's|\.java$||' \
                | tr '/' '.')

            if echo "$DIRECT_IMPORTS" | grep -q "$CLASS_NAME"; then
                IMPORT_OVERLAP="${IMPORT_OVERLAP}  ${FILE} -> imported as ${CLASS_NAME}\n"
            fi
        done <<< "$CHANGED_FILES"

        if [ -n "$IMPORT_OVERLAP" ]; then
            echo ""
            echo "DIRECT IMPORT OVERLAP DETECTED:"
            echo -e "$IMPORT_OVERLAP"
        fi
    fi
else
    echo "Test file not found locally (may be in a different checkout)."
fi

# ── Step 4: Render verdict ──────────────────────────────────────────

echo ""
echo "───────────────────────────────────────────────────────────────"

if [ -n "$IMPORT_OVERLAP" ]; then
    echo ""
    echo "VERDICT: CLAIM IS ALMOST CERTAINLY FALSE"
    echo ""
    echo "The failing test DIRECTLY IMPORTS classes that were modified"
    echo "on this branch. A claim that these changes 'do not cause'"
    echo "the test failure is contradicted by the import chain."
    echo ""
    echo "Changed files imported by the test:"
    echo -e "$IMPORT_OVERLAP"
    echo ""
    echo "ACTION REQUIRED:"
    echo "  1. Delete or tag any memory claiming 'no changes needed'"
    echo "  2. Investigate the actual failure mechanism"
    echo "  3. Fix the production code"
    exit 2
elif [ "$SAME_MODULE_COUNT" -gt 0 ]; then
    echo ""
    echo "VERDICT: CLAIM IS SUSPECT"
    echo ""
    echo "${SAME_MODULE_COUNT} changed file(s) are in the SAME MODULE as the"
    echo "failing test (${TEST_MODULE}). Further investigation is required"
    echo "before accepting a 'no changes needed' claim."
    echo ""
    echo "Same-module files:"
    echo -e "$SAME_MODULE_FILES" | grep '[^[:space:]]' | while IFS= read -r f; do
        echo "  - $f"
    done
    echo ""
    echo "ACTION REQUIRED:"
    echo "  1. Trace the full import chain from the test to these files"
    echo "  2. If ANY path exists, the claim is false"
    echo "  3. Tag the memory with 'requires-verification'"
    exit 2
elif [ "$DEP_MODULE_COUNT" -gt 0 ]; then
    echo ""
    echo "VERDICT: CLAIM REQUIRES VERIFICATION"
    echo ""
    echo "${DEP_MODULE_COUNT} changed file(s) are in core modules that the"
    echo "test module likely depends on. The claim may be true but cannot"
    echo "be accepted without tracing the full dependency chain."
    echo ""
    echo "Core module files:"
    echo -e "$DEPENDENCY_MODULE_FILES" | grep '[^[:space:]]' | while IFS= read -r f; do
        echo "  - $f"
    done
    echo ""
    echo "ACTION: Tag the memory with 'requires-verification'"
    exit 3
else
    echo ""
    echo "VERDICT: CLAIM IS PLAUSIBLE"
    echo ""
    echo "No changed production files are in the same module as the failing"
    echo "test, and no changed files are in obvious core dependency modules."
    echo "The 'no changes needed' claim may be correct."
    echo ""
    echo "NOTE: This is a heuristic. Transitive dependencies may still exist."
    echo "The memory should still be tagged with the git diff hash for auditability."
    exit 0
fi
