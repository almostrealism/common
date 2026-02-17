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

cat > "$OUTPUT_FILE" <<EOF
The following ${FAILURE_COUNT} new test failure(s) were discovered on branch "${BRANCH}" (commit ${COMMIT_SHA}):

${FAILURE_LIST}

For detailed stack traces and error messages, download the Surefire report artifacts from this workflow run: ${SUREFIRE_ARTIFACT_URL}

Please investigate and fix these test failures. Each entry above is formatted as ClassName#methodName. Look at the test source code and the classes under test to understand what is expected, then make the minimal code change needed to make the tests pass.
EOF
