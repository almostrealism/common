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
- Excluded CodePolicyEnforcementTest from CI test steps
- Removed quality gates from build steps
- Manipulated job dependencies to avoid running checks

**DO NOT:**
- Modify ANY file under src/test/ for ANY reason
- Modify ANY file under .github/workflows/ or tools/ci/
- Change @TestDepth values, timeout values, dimensions, or tolerances
- Add @Ignore, @Disabled, skipLongTests, or try/catch blocks to tests
- Reduce numeric constants in test methods
- Increase tolerance/epsilon values in assertions
- Claim "the test is wrong" - the test is correct, your code is broken
- Claim "the test is flaky" - if CI reports failure, the failure is real
- Claim "the failure is unrelated to my changes" without PROOF (see below)

**The test is correct. Your branch broke it. Fix the production code, not the test.**
**If you touch a test file, your changes will be automatically rejected by
validate-agent-commit.sh. Your commit will be blocked. Do not waste time trying.**

---

## AUTOMATED ENFORCEMENT

Your commit will be validated by `validate-agent-commit.sh` which BLOCKS:
1. ANY modification to test files that exist on the base branch (exit code 2)
2. ANY modification to CI/workflow files (exit code 4)
3. Commits with NO production code changes when fixing test failures (exit code 3)

Additionally, `detect-test-hiding.sh` checks for 12 specific evasion patterns
including TestDepth escalation, timeout inflation, dimension reduction,
tolerance weakening, assertion removal, and numeric literal shrinkage.

**There is no way around these checks. They are mechanical, not judgment-based.**

---

## MANDATORY INVESTIGATION PROTOCOL

Before concluding your investigation, you MUST produce a DIFF_ANALYSIS.md file with
this EXACT structure. Skipping this step is a violation. Sessions that exit with
"no changes needed" without this file will be flagged for audit.

```
## Diff Analysis: [Failing Test Name]

### Changed Production Files
[Run: git diff origin/master...HEAD --name-only | grep -v src/test | grep -v .github]
[List EVERY file — omitting files is a violation]

### Failing Test
- Class: [exact fully-qualified class name]
- Method: [exact method name]
- Module: [maven module]

### Dependency Trace
[For EACH changed production file, state whether the failing test depends on it]
- [file1.java]: YES/NO - [evidence: import chain or "no transitive path found"]
- [file2.java]: YES/NO - [evidence: import chain or "no transitive path found"]

### Root Cause
[Specific theory with evidence — NOT "unrelated", "flaky", or "pre-existing"]
[If you believe the failure is unrelated, you must PROVE it by showing
 that NO changed file is in the failing test's import chain]

### Production Code Fix
[Description of the fix you applied to NON-TEST code]
[If you cannot fix it, explain specifically what blocks you]
[If you claim "no fix needed", this WILL be audited against the dependency trace]
```

---

## EVIDENCE RULES

These rules exist because agents have systematically fabricated evidence.

1. **"Tests pass locally" is NOT evidence.** Only CI results on the exact commit
   count. If CI fails and local passes, YOUR local environment is wrong, not CI.
   You may have AR_TEST_DEPTH=0 set, different memory limits, or a different
   checkout. Local test results are INADMISSIBLE.

2. **"No changes needed" requires PROOF.** You must demonstrate via the dependency
   trace in DIFF_ANALYSIS.md that no changed production file is reachable from the
   failing test's import chain. Without this proof, the claim is rejected.

3. **Memory entries must be accurate.** If you store a memory claiming "no changes
   needed" or "failure is unrelated", it WILL be cross-referenced against the git
   diff by verify-memory-claim.sh. If the claim is contradicted by the dependency
   trace, the memory will be flagged as deceptive and you will be audited.

4. **Do not use absolute language without proof.** Phrases like "do NOT cause
   failures" and "definitively unrelated" are red flags. Use qualified language:
   "Based on the dependency trace, no direct import path was found between X and Y."

---

PROMPT_HEADER

# Now append the dynamic portion
cat >> "$OUTPUT_FILE" <<EOF
The following ${FAILURE_COUNT} test failure(s) were discovered on branch "${BRANCH}" (commit ${COMMIT_SHA}):

${FAILURE_LIST}

For detailed stack traces and error messages, download the Surefire report artifacts from this workflow run: ${SUREFIRE_ARTIFACT_URL}

## Your task

1. Run \`git diff origin/master...HEAD --name-only\` and list ALL changed files.
2. For EACH failing test, determine if it exists on the base branch:
       git log --oneline origin/master -- <path/to/TestFile.java>
3. Read the test source code and understand what it is asserting.
4. Trace the import chain from the test to the changed production files.
5. Read the production code under test and identify the root cause.
6. Make the **minimal production code change** needed to make the tests pass.
7. Produce the DIFF_ANALYSIS.md file with the mandatory structure above.
8. Verify your fix does not break other tests.

**Remember: if the test exists on master, the test is correct and your job is to fix
the code that the test exercises, NOT the test itself. Your commit will be
automatically validated and rejected if it modifies test or CI files.**
EOF
