#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for a build failure ────────────
#
# Assembles a natural-language prompt telling the coding agent that
# the build itself failed (compilation, packaging, etc.), which is
# equivalent to every test failing.
#
# Usage:
#   build-build-failure-prompt.sh <output-file>
#
# Required environment variables:
#   BRANCH          - branch name where the build failed
#   COMMIT_SHA      - commit SHA where the build failed
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

OUTPUT_FILE="${1:-}"

if [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <output-file>" >&2
    exit 1
fi

for var in BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

cat > "$OUTPUT_FILE" <<PROMPT
## ABSOLUTE RULE: DO NOT MODIFY EXISTING TESTS TO HIDE FAILURES

**THIS IS THE MOST IMPORTANT RULE. VIOLATING IT IS WORSE THAN NOT FIXING ANYTHING.**

Before you touch ANY test file, you MUST determine whether the test existed on the
base branch (master) before this branch was created. Run:

    git log --oneline origin/master -- <path/to/TestFile.java>

If the test file exists on master, **you are NOT allowed to modify the test in any
way that would hide, weaken, or suppress the failure.**

**The test is correct. Your branch broke it. Fix the production code, not the test.**

---

The **build itself failed** on branch "${BRANCH}" (commit ${COMMIT_SHA}).

This is a compilation or packaging failure — no tests were able to run. A build
failure is equivalent to every test in the project failing.

## Your task

1. Run \`mvn install -DskipTests\` to reproduce the compilation error locally.
   The error output will tell you exactly which file(s) and line(s) failed.
2. Read the failing source files and understand what changed on this branch:
       git diff origin/master -- <path/to/FailingFile.java>
3. Make the **minimal production code change** needed to fix the build.
4. Verify the build succeeds after your fix:
       mvn install -DskipTests
5. Do NOT modify tests or weaken assertions — the build must compile cleanly
   with all existing code intact.
PROMPT
