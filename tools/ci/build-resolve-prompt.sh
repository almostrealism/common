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
SUREFIRE_ARTIFACT_URL="${RUN_URL}#artifacts"

cat > "$OUTPUT_FILE" <<'PROMPT_HEADER'
## ABSOLUTE RULE: DO NOT MODIFY EXISTING TESTS TO HIDE FAILURES

**THIS IS THE MOST IMPORTANT RULE. VIOLATING IT IS WORSE THAN NOT FIXING ANYTHING.**

Before you touch ANY test file, you MUST determine whether the test existed on the
base branch (master/develop) before this branch was created. Run:

    git log --oneline master -- <path/to/TestFile.java>

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

If you genuinely believe the test itself is wrong (not just inconvenient), you must
explain your reasoning in a Slack message AND still attempt a production-code fix
first. Only if no production-code fix is possible should you propose a test change,
and that proposal must be clearly flagged for human review.

### Exception: Tests NEW to this branch

If a test was introduced in THIS branch (i.e., `git log --oneline master -- <file>`
shows no history), you MAY modify it -- but you should still prefer fixing production
code. Introducing a new test that immediately fails and then "fixing" it by weakening
it is still bad practice.

---

PROMPT_HEADER

# Now append the dynamic portion
cat >> "$OUTPUT_FILE" <<EOF
The following ${FAILURE_COUNT} test failure(s) were discovered on branch "${BRANCH}" (commit ${COMMIT_SHA}):

${FAILURE_LIST}

For detailed stack traces and error messages, download the Surefire report artifacts from this workflow run: ${SUREFIRE_ARTIFACT_URL}

## Your task

1. For EACH failing test, first determine if it exists on the base branch:
       git log --oneline master -- <path/to/TestFile.java>
2. Read the test source code and understand what it is asserting.
3. Read the production code under test and trace the failure.
4. Make the **minimal production code change** needed to make the tests pass.
5. Verify your fix does not break other tests.

**Remember: if the test exists on master, the test is correct and your job is to fix
the code that the test exercises, NOT the test itself.**
EOF
