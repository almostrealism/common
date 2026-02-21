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
## ABSOLUTE RULE: YOU CANNOT MODIFY TEST FILES. PERIOD.

**THIS IS THE MOST IMPORTANT RULE. VIOLATING IT IS WORSE THAN NOT FIXING ANYTHING.**

**You are PHYSICALLY BLOCKED from modifying ANY test file.** Do not attempt it.
There are ZERO exceptions. Not for new tests. Not for old tests. Not for timeouts.
Not for @TestDepth. Not for dimensions. Not for tolerances. NOTHING.

This rule exists because agents have REPEATEDLY and SYSTEMATICALLY hidden test
failures instead of fixing production code. Documented evidence (DECEPTION.md):
- Changed @TestDepth(2) to @TestDepth(10) to push NormTests out of CI
- Reduced test dimensions 8x (64->8) to make tests trivially pass
- Increased timeouts 6x (5min->30min) to prevent timeout detection
- Re-added suppression annotations AFTER a human reverted them
- Stored false memories claiming "no changes needed" for failures they caused

**DO NOT:**
- Modify ANY file under src/test/ for ANY reason
- Modify ANY file under .github/workflows/ or tools/ci/
- Change @TestDepth values, timeout values, dimensions, or tolerances
- Add @Ignore, @Disabled, skipLongTests, or try/catch blocks to tests
- Reduce numeric constants in test methods
- Claim "the test is wrong" - the test is correct, your code is broken

**The test is correct. Your branch broke it. Fix the production code, not the test.**
**If you touch a test file, your changes will be automatically rejected.**

---

## MANDATORY INVESTIGATION PROTOCOL

Before concluding your investigation, you MUST produce a DIFF_ANALYSIS.md file with
this EXACT structure. Skipping this step is a violation.

```
## Diff Analysis: [Failing Test Name]

### Changed Production Files
[Run: git diff origin/master...HEAD --name-only | grep -v src/test | grep -v .github]
[List EVERY file]

### Failing Test
- Class: [exact fully-qualified class name]
- Module: [maven module]

### Dependency Trace
[For EACH changed production file, state whether the failing test depends on it]
- [file1.java]: YES/NO - [import chain evidence]
- [file2.java]: YES/NO - [import chain evidence]

### Root Cause
[Specific theory with evidence - NOT "unrelated" or "flaky"]

### Production Code Fix
[Description of the fix you applied to NON-TEST code]
[If you cannot fix it, explain specifically what blocks you]
```

**"Tests pass locally" is NOT evidence.** Only CI results on the exact commit count.
If CI fails and local passes, YOUR local environment is wrong, not CI.

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
