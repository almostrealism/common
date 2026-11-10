#!/usr/bin/env bash
# ─── Build the auto-resolve prompt for a python-tests/python-evals failure ─
#
# Assembles a prompt telling the coding agent that the pipeline's
# `python-tests` or `python-evals` job failed, with the tail of the captured
# output. Used by the `auto-resolve-python` job, which submits as soon as
# either fails, whatever state the Java test suites are in.
#
# Usage:
#   build-python-failure-prompt.sh <log-file> <output-file>
#
# A third positional argument is optional: a file holding the rendered
# "new TODOs this branch adds" section (extract_branch_todos.py's output),
# passed through to the shared branch-feedback block. It is optional so a
# caller that has not computed it still produces a prompt.
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
TODOS_FILE="${3:-}"

if [ -z "$LOG_FILE" ] || [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <log-file> <output-file> [todos-section-file]" >&2
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

The \`python-tests\` or \`python-evals\` job failed on branch "${BRANCH}" (commit ${COMMIT_SHA}).

This was reported as soon as the Python suites failed. The rest of the
pipeline runs alongside them, so the Java test suites for this commit may not
have started, may still be running, or may have finished; nothing here says
whether they pass. Fix the Python failure — the next pipeline will report the
Java suites.

## Captured output (last 200 lines)

\`\`\`
${LOG_TAIL}
\`\`\`

## Your task

1. Reproduce the failure. The \`python-tests\` and \`python-evals\` jobs in
   \`.github/workflows/analysis.yaml\` list every suite they run and the exact
   command for each; run the failing one locally. The job stops at the first
   failing suite, so run every suite before you finish — a later one may fail
   too.
2. Find what this branch changed that broke it:
       git diff origin/master...HEAD -- <path>
3. Make the minimal change to the code under test that makes the suite pass.
4. Re-run every suite both jobs list and confirm they all pass.

\`python-evals\` runs the consult evaluation set (\`tools/mcp/manager/eval\`)
over this checkout's documentation and sources, so a documentation change can
fail it. If a scripted answer in \`eval/fixtures/scripted/\` cites text by a
phrase (\`[D:<path>@"<phrase>"]\`) that your branch deliberately reworded, cite
the same content by a phrase it now contains; that is fixture data, not a test.
If the documentation change was not deliberate, fix the documentation.
PROMPT

append_branch_feedback "$OUTPUT_FILE" "$TODOS_FILE"
