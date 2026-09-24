#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for a python-tests failure ──────
#
# Assembles a prompt telling the coding agent that the pipeline's
# `python-tests` job failed, with the tail of that job's captured output.
# Used by the `auto-resolve-python` job, which submits as soon as
# python-tests fails, before any Java test suite has run.
#
# Usage:
#   build-python-failure-prompt.sh <log-file> <output-file>
#
# Required environment variables:
#   BRANCH          - branch name where the tests failed
#   COMMIT_SHA      - commit SHA where the tests failed
#
# Exit codes:
#   0 - prompt written successfully
#   1 - invalid arguments or missing env vars

set -euo pipefail

LOG_FILE="${1:-}"
OUTPUT_FILE="${2:-}"

if [ -z "$LOG_FILE" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <log-file> <output-file>" >&2
    exit 1
fi

for var in BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=prompt-render.sh
source "${SCRIPT_DIR}/prompt-render.sh"

# The job stops at its first failing suite, and unittest prints its failure
# report last, so the tail of the log is where the failures are.
LOG_TAIL=""
if [ -s "$LOG_FILE" ]; then
    LOG_TAIL=$(tail -n 200 "$LOG_FILE")
fi
if [ -z "$LOG_TAIL" ]; then
    LOG_TAIL="(no output was captured — reproduce the failure with the commands below)"
fi

cat > "$OUTPUT_FILE" <<PROMPT
## ABSOLUTE RULE: DO NOT MODIFY EXISTING TESTS TO HIDE FAILURES

Every \`def test_*\` that exists on the base branch (master) must still exist
when you are done, and no test file may end up with fewer assertions than it
has now. Do not delete, rename, skip or weaken a failing test. **The test is
correct. Your branch broke it. Fix the code under test, not the test.**

---

The \`python-tests\` job failed on branch "${BRANCH}" (commit ${COMMIT_SHA}).

This was reported as soon as the Python suites failed. The Java test suites
have not run for this commit, so nothing here says whether they pass.

## Captured output (last 200 lines)

\`\`\`
${LOG_TAIL}
\`\`\`

## Your task

1. Reproduce the failure. The \`python-tests\` job in
   \`.github/workflows/analysis.yaml\` lists every suite it runs and the exact
   command for each; run the failing one locally. The job stops at the first
   failing suite, so run every suite before you finish — a later one may fail
   too.
2. Find what this branch changed that broke it:
       git diff origin/master...HEAD -- <path>
3. Make the minimal change to the code under test that makes the suite pass.
4. Re-run every suite the job lists and confirm they all pass.
PROMPT

append_prompt_fragment pr-feedback.txt "$OUTPUT_FILE" BRANCH
