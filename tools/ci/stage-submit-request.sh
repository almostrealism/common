#!/usr/bin/env bash
# ─── Stage an auto-resolve submission request ────────────────────────
#
# Writes the already-built agent prompt and the submission parameters into
# an output directory so a downstream workflow_run workflow ("Auto-Resolve
# Submit") can pick them up and POST them to the FlowTree controller.
#
# The controller submission is deliberately NOT performed here: it is gated
# on the `worker` GitHub Environment (required-reviewers approval), and a job
# carrying an `environment:` in a pull_request run attaches a deployment
# status to the PR head commit. An abandoned/cancelled `worker` deployment
# then shows as a spurious "had a problem deploying" red X on the PR. By
# staging the request as an artifact and submitting from a workflow_run
# workflow, the deployment attaches to the default-branch context instead,
# keeping the approval-gate deployment status off the pull request.
#
# Usage:
#   stage-submit-request.sh <prompt-file> <output-dir>
#
# Required environment variables:
#   BRANCH        - target branch for the agent to work on
#   BASE_BRANCH   - base branch for comparison
#
# Optional environment variables (forwarded verbatim to submit-agent-job.sh):
#   STARTED_AFTER, DESCRIPTION, PROTECT_TEST_FILES, ENFORCE_CHANGES
#
# Output:
#   <output-dir>/agent-prompt.txt  - copy of the prompt
#   <output-dir>/submit.env        - KEY=value lines (one per submission param)

set -euo pipefail

PROMPT_FILE="${1:?usage: stage-submit-request.sh <prompt-file> <output-dir>}"
OUT_DIR="${2:?usage: stage-submit-request.sh <prompt-file> <output-dir>}"

if [ ! -f "$PROMPT_FILE" ]; then
    echo "ERROR: prompt file not found: $PROMPT_FILE" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
cp "$PROMPT_FILE" "$OUT_DIR/agent-prompt.txt"

# Emit KEY=value lines. The submit workflow appends this file to $GITHUB_ENV,
# which parses each line literally (values may contain spaces/parentheses, as
# in "Resolve 3 test failure(s)") — so do NOT quote the values here.
{
    printf 'BRANCH=%s\n' "${BRANCH:?BRANCH is required}"
    printf 'BASE_BRANCH=%s\n' "${BASE_BRANCH:?BASE_BRANCH is required}"
    [ -n "${STARTED_AFTER:-}" ] && printf 'STARTED_AFTER=%s\n' "$STARTED_AFTER"
    [ -n "${DESCRIPTION:-}" ] && printf 'DESCRIPTION=%s\n' "$DESCRIPTION"
    [ -n "${PROTECT_TEST_FILES:-}" ] && printf 'PROTECT_TEST_FILES=%s\n' "$PROTECT_TEST_FILES"
    [ -n "${ENFORCE_CHANGES:-}" ] && printf 'ENFORCE_CHANGES=%s\n' "$ENFORCE_CHANGES"
} > "$OUT_DIR/submit.env"

echo "::notice::Staged auto-resolve request: ${DESCRIPTION:-(no description)}"
