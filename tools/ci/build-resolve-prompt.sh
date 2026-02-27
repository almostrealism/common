#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for the FlowTree agent ───────────
#
# Reads a failure list file and assembles a natural-language prompt
# for the coding agent.
#
# Usage:
#   build-resolve-prompt.sh <failures-file> <output-file>
#
# Required environment variables:
#   FAILURE_COUNT   - number of failures (for the prompt text)
#   BRANCH          - branch name where failures occurred
#   COMMIT_SHA      - commit SHA where failures occurred
#   RUN_URL         - URL to the GitHub Actions run
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

FAILURES_FILE="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$FAILURES_FILE" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <failures-file> <output-file>" >&2
    exit 1
fi

for var in FAILURE_COUNT BRANCH COMMIT_SHA RUN_URL; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

FAILURE_LIST=$(cat "$FAILURES_FILE")

cat > "$OUTPUT_FILE" <<'PROMPT_HEADER'
## YOU BROKE THESE TESTS. YOU MUST FIX THEM.

**This is not a request. This is not optional. The tests listed below PASS on master
and FAIL on your branch. The changes on your branch caused these failures. Your ONLY
job is to fix the production code so these tests pass again.**

### What you are NOT allowed to do

**Conclude that no fix is needed.** The tests fail. A fix IS needed. Period.

**Claim the tests "pass individually."** The CI pipeline runs the full test suite
for a module, not individual test methods. Tests that pass in isolation may fail when
run alongside other tests (shared state, ordering, resource contention). The ONLY way
to verify your fix works is to run the exact CI command listed below.

**Blame the CI environment or call it "transient."** These failures are reproducible.
They pass on master, they fail on this branch. Every time. If you claim otherwise
without actually running the CI command locally and showing it succeeds, you are wrong.

**Fetch the CI run URL with WebFetch.** You do not have permission to access GitHub
Actions pages and you do not need to. All the information you need is in this prompt.
The failing tests and the branch diff tell you everything.

**Look at a single commit and declare it "fine."** The problem is the ENTIRE set of
changes between origin/master and this branch. Run `git diff origin/master...HEAD` to
see what changed. Do not cherry-pick one commit to review.

### What you MUST do

**Understand the full branch delta.** Run:
    git diff --stat origin/master...HEAD
This shows EVERY file changed on this branch relative to master. The test failures
were caused by one or more of these changes.

**Read the failing test(s).** Understand what they assert.

**Trace from the test to the production code.** Find what the test calls, read that
code, and identify where the branch changes broke the expected behavior.

**Fix the production code.** Make the minimal change needed so the tests pass.

**Reproduce the failure locally, then verify your fix.** Use the MCP test runner to
run the CI command listed below. Do NOT run individual test methods -- run the full
module test suite. If it passes, your fix works. If it fails, keep investigating.

---

## ABSOLUTE RULE: DO NOT MODIFY EXISTING TESTS TO HIDE FAILURES

Before you touch ANY test file, you MUST determine whether the test existed on the
base branch (master) before this branch was created. Run:

    git log --oneline origin/master -- <path/to/TestFile.java>

If the test file exists on master, **you are NOT allowed to modify the test in any
way that would hide, weaken, or suppress the failure.** Specifically, you MUST NOT:

- Add `@Ignore`, `@Disabled`, or skip annotations to existing test methods
- Delete or comment out existing test methods or assertion lines
- Weaken assertions (e.g., changing `assertEquals` to `assertTrue`, loosening
  tolerances, removing assertions entirely)
- Add `try/catch` blocks that swallow exceptions in existing tests
- Change expected values to match the (incorrect) actual output
- Reduce test coverage by removing test inputs or edge cases
- Wrap the test body in `if (skipLongTests) return;` when it was not there before
- Move, rename, or split an existing test to avoid detection
- Add `@TestDepth` annotations to push existing tests out of the default run

**The test is correct. Your branch broke it. Fix the production code, not the test.**

---

PROMPT_HEADER

# ── Determine which modules contain failures and build CI commands ──
# Parse class names from the failure list and map them to Maven modules.
# The CI command section tells the agent exactly how to reproduce.
FAILING_MODULES=""
while IFS= read -r line; do
    # Only process lines that start with "- " (test name lines).
    # Skip exception details, stack traces, and blank lines.
    case "$line" in
        "- "*)
            class_name=$(echo "$line" | sed 's/^- //' | sed 's/#.*//')
            # Resolve to a source file
            src_file=$(find . -path "*/src/test/java*/${class_name##*.}.java" -print -quit 2>/dev/null)
            if [ -n "$src_file" ]; then
                # Extract the module from the path (first directory component under ./)
                module=$(echo "$src_file" | sed 's|^\./||' | cut -d/ -f1)
                if ! echo "$FAILING_MODULES" | grep -qw "$module"; then
                    FAILING_MODULES="${FAILING_MODULES:+$FAILING_MODULES }$module"
                fi
            fi
            ;;
    esac
done < "$FAILURES_FILE"

# Build CI reproduction commands for each failing module
CI_COMMANDS=""
for module in $FAILING_MODULES; do
    if [ "$module" = "ml" ]; then
        CI_COMMANDS="${CI_COMMANDS}
Module: ${module}
  mcp__ar-test-runner__start_test_run module:\"${module}\" profile:\"pipeline\" timeout_minutes:30"
    else
        CI_COMMANDS="${CI_COMMANDS}
Module: ${module}
  mcp__ar-test-runner__start_test_run module:\"${module}\" timeout_minutes:30"
    fi
done

# If we couldn't determine modules, provide a generic fallback
if [ -z "$CI_COMMANDS" ]; then
    CI_COMMANDS="
Could not auto-detect failing modules. Examine the failing test class names below,
find which module they belong to (utils, ml, audio, music, compose), and run:
  mcp__ar-test-runner__start_test_run module:\"<module>\" timeout_minutes:30
For ML module tests, add profile:\"pipeline\"."
fi

# Now append the dynamic portion
cat >> "$OUTPUT_FILE" <<EOF
## Failing tests

The following ${FAILURE_COUNT} test failure(s) were discovered on branch "${BRANCH}".
Each failure includes the exception type, message, and a truncated stack trace from the
Surefire XML report. **Use these details to understand what went wrong** — the exception
type and message tell you exactly what assertion or error occurred.

${FAILURE_LIST}

These tests PASS on origin/master. They FAIL on this branch. The branch changes broke them.

## How to reproduce (REQUIRED)

You MUST reproduce the failure locally before attempting a fix and after applying
your fix. Use the MCP test runner with these exact commands:
${CI_COMMANDS}

Do NOT run individual test methods with test_methods or test_classes parameters.
The CI pipeline runs the FULL module test suite. Failures may only manifest when
the entire suite runs (due to shared state, test ordering, etc.).

## Investigation steps

1. **Read the exception details above.** The exception type and message tell you
   exactly what failed. An \`AssertionError\` with \`expected 5 but was 3\` is a
   completely different problem from a \`NullPointerException\` in production code.
   Start your investigation from the specific error, not from guessing.

2. **See what this branch changed:**
       git diff --stat origin/master...HEAD
   This is the complete set of changes that could have caused the failures.

3. **Read the failing test(s).** Understand what they assert.

4. **Trace the failure to your branch's changes.** Use the stack trace to identify
   which production code threw the exception, then check whether any of the branch
   changes affected that code path.

5. **Fix the production code.** Make the minimal change needed.

6. **Verify by running the full CI command above.** Not individual tests -- the full
   module suite. If it passes, your fix works.

**Remember: the test is correct. Your branch broke it. Fix the production code.**
EOF
