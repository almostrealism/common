#!/usr/bin/env bash
# ─── Check quality gate outcomes ──────────────────────────────────
#
# Reads quality gate pass/fail status from environment variables
# (set by GitHub Actions job outputs) and writes a summary of
# failures to an output file.
#
# Usage:
#   check-quality-gates.sh <output-file>
#
# Required environment variables:
#   JAVADOC_PASSED          - "true" or "false"
#   TIMEOUT_PASSED          - "true" or "false"
#   DUPLICATE_PASSED        - "true" or "false"
#   TEST_INTEGRITY_PASSED   - "true" or "false" (optional; defaults to "true")
#
# Outputs (to GITHUB_OUTPUT):
#   failure_count=<N>
#   has_failures=true|false
#
# The output file will contain a human-readable list of failed gates.

set -euo pipefail

OUTPUT_FILE="${1:-}"

if [ -z "$OUTPUT_FILE" ]; then
    echo "Usage: $0 <output-file>" >&2
    exit 1
fi

FAILURE_COUNT=0
> "$OUTPUT_FILE"

if [ "${JAVADOC_PASSED:-true}" != "true" ]; then
    echo "- javadoc-check: Some non-private classes or methods are missing Javadoc documentation. Run \`mvn checkstyle:check -Pjavadoc-check\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${TIMEOUT_PASSED:-true}" != "true" ]; then
    echo "- test-timeout-check: Some @Test annotations are missing a timeout parameter. Run \`mvn test -pl tools -Dtest=CodePolicyEnforcementTest#enforceTestTimeouts\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${DUPLICATE_PASSED:-true}" != "true" ]; then
    echo "- duplicate-code-check: Duplicate code blocks (10+ identical lines) detected across different files. Run \`mvn test -pl tools -Dtest=CodePolicyEnforcementTest#enforceNoDuplicateCode\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${TEST_INTEGRITY_PASSED:-true}" != "true" ]; then
    echo "- test-integrity-check: CRITICAL — Existing test files were modified in ways that hide failures (e.g., adding @Ignore, deleting assertions, weakening checks). This is NEVER acceptable for tests that exist on the base branch. Revert the test modifications and fix the production code instead. Run \`./tools/ci/agent-protection/detect-test-hiding.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${AGENT_COMMIT_BLOCKED:-false}" = "true" ]; then
    echo "- agent-commit-validation: CRITICAL — Agent commit was BLOCKED because it modifies test files or CI files that exist on the base branch. Reason: ${AGENT_BLOCK_REASON:-unknown}. Agents are NEVER allowed to modify test or CI files. Run \`./tools/ci/agent-protection/validate-agent-commit.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${DECEPTION_AUDIT_FINDINGS:-false}" = "true" ]; then
    echo "- deception-audit: WARNING — ${DECEPTION_FINDING_COUNT:-0} deception pattern(s) detected on this branch. This may indicate coordinated agent deception across sessions. Run \`./tools/ci/agent-protection/deception-audit.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
    # Deception audit is informational, not a hard failure
fi

echo "failure_count=$FAILURE_COUNT" >> "$GITHUB_OUTPUT"
if [ "$FAILURE_COUNT" -gt 0 ]; then
    echo "has_failures=true" >> "$GITHUB_OUTPUT"
    echo "::notice::Found $FAILURE_COUNT quality gate failure(s) to auto-resolve"
else
    echo "has_failures=false" >> "$GITHUB_OUTPUT"
    echo "::notice::All quality gates passed -- skipping auto-resolve"
fi
