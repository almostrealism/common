#!/usr/bin/env bash
# ─── Build the quality-gate auto-resolve prompt for the FlowTree agent ──
#
# Reads a quality gate failure list and assembles a natural-language
# prompt for the coding agent. This is used as a fallback when no test
# failures exist but quality gates have failed.
#
# Usage:
#   build-quality-gate-prompt.sh <failures-file> <output-file>
#
# Required environment variables:
#   FAILURE_COUNT   - number of quality gate failures
#   BRANCH          - branch name where failures occurred
#   COMMIT_SHA      - commit SHA where failures occurred
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

for var in FAILURE_COUNT BRANCH COMMIT_SHA; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

FAILURE_LIST=$(cat "$FAILURES_FILE")

cat > "$OUTPUT_FILE" <<EOF
All tests are passing on branch "${BRANCH}" (commit ${COMMIT_SHA}), but ${FAILURE_COUNT} CI quality gate(s) failed:

${FAILURE_LIST}

These are code quality and style issues, not functional test failures. Fix them using the MCP build validator.

## How to reproduce and fix each gate

Run the build validator to see the exact violations. Choose the checks that match the failing gate(s):

**For checkstyle violations** (style rules: no var, no @SuppressWarnings, file length, System.out usage):
  mcp__ar-build-validator__start_validation checks:["checkstyle"]
  (No build step needed — this is pure source analysis and runs immediately.)

**For Javadoc coverage violations** (missing Javadoc on non-private types/methods):
  mcp__ar-build-validator__start_validation checks:["javadoc"]

**For code policy violations** (Producer pattern, GPU memory model, naming conventions):
  mcp__ar-build-validator__start_validation checks:["code_policy"]

**For test timeout violations** (@Test annotations missing timeout parameter):
  mcp__ar-build-validator__start_validation checks:["test_timeouts"]

**For duplicate code violations** (10+ identical lines across files):
  mcp__ar-build-validator__start_validation checks:["duplicate_code"]

**To run all gates at once:**
  mcp__ar-build-validator__start_validation

After starting, poll status with:
  mcp__ar-build-validator__get_validation_status run_id:<id>

Then get structured violations (file, line, rule) with:
  mcp__ar-build-validator__get_validation_violations run_id:<id>

Fix the violations in production code, then re-run the validator with skip_build:true
to verify (since the project is already built at that point).
EOF
