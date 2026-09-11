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
#   TEST_INTEGRITY_REASON   - what test-integrity-check concluded, when it
#                             concluded anything: "enforcement-tampering",
#                             "exfil-guard", "test-hiding", "infrastructure",
#                             or empty (optional; defaults to empty)
#   CHECKSTYLE_PASSED       - "true" or "false" (optional; defaults to "true")
#
# Outputs (to GITHUB_OUTPUT):
#   failure_count=<N>
#   has_failures=true|false
#   unattributed=true|false   (a gate failed without a nameable cause)
#
# The output file will contain a human-readable list of failed gates.
#
# What lands in that file is read by an agent and acted on, so a gate is
# only listed when its cause is known. A failure nobody can name is
# reported to the log for a human and left out of the file: dispatching an
# agent to "fix" an unexplained failure is how a branch gets damaged in
# response to a problem that was never in it.

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

UNATTRIBUTED=false

if [ "${TEST_INTEGRITY_PASSED:-true}" != "true" ]; then
    # test-integrity-check runs three sequential detectors and stops at the
    # first failure, so TEST_INTEGRITY_PASSED says only that the job did not
    # end clean. TEST_INTEGRITY_REASON is what the failing step itself
    # recorded, and it is the only thing that distinguishes a detector's
    # finding from a detector that could not run — or from a job that was
    # skipped, cancelled, or never reported at all, which also leaves
    # TEST_INTEGRITY_PASSED empty.
    #
    # Only the three named findings are accusations, and only they are
    # listed. Anything else means nobody established that this branch did
    # anything, and saying otherwise to an agent has twice produced a
    # "fix" to a branch that was never at fault.
    case "${TEST_INTEGRITY_REASON:-}" in
        enforcement-tampering)
            echo "- test-integrity-check: CRITICAL — Enforcement infrastructure (policy detectors, agent-protection scripts, or the exfiltration guard) was modified on this branch. These files are protected and cannot be edited on PR branches; fix the production code that violates the policy instead. Run \`./tools/ci/agent-protection/validate-agent-commit.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
            FAILURE_COUNT=$((FAILURE_COUNT + 1))
            ;;
        exfil-guard)
            echo "- test-integrity-check: CRITICAL — The exfiltration guard hook is missing, unregistered, or was modified on this branch. The guard is the only barrier between an agent's tools and the outside world; changes to it are made and committed by a human, never by an agent. Run \`./tools/ci/agent-protection/verify-exfiltration-guard.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
            FAILURE_COUNT=$((FAILURE_COUNT + 1))
            ;;
        test-hiding)
            echo "- test-integrity-check: CRITICAL — Existing test files were modified in ways that hide failures (e.g., adding @Ignore, deleting assertions, weakening checks). This is NEVER acceptable for tests that exist on the base branch. Revert the test modifications and fix the production code instead. Run \`./tools/ci/agent-protection/detect-test-hiding.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
            FAILURE_COUNT=$((FAILURE_COUNT + 1))
            ;;
        *)
            UNATTRIBUTED=true
            echo "::warning::test-integrity-check did not pass, but no detector recorded a finding (reason='${TEST_INTEGRITY_REASON:-<none>}'). Treating it as a pipeline problem: it is not being reported to an agent as a violation. A human should read the job log."
            ;;
    esac
fi

if [ "${CHECKSTYLE_PASSED:-true}" != "true" ]; then
    echo "- checkstyle: Checkstyle violations detected (no \`var\` keyword, no \`@SuppressWarnings\`). Run \`mvn checkstyle:check\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${AGENT_COMMIT_BLOCKED:-false}" = "true" ]; then
    echo "- agent-commit-validation: CRITICAL — Agent commit was BLOCKED. Reason: ${AGENT_BLOCK_REASON:-unknown}. Agents are NEVER allowed to change or remove a test method that exists on the base branch, nor to modify CI files outside a ci/... branch, and a commit dispatched to fix a failure must add something that did not exist before. Adding new test methods to an existing test class is permitted. Run \`./tools/ci/agent-protection/validate-agent-commit.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
    FAILURE_COUNT=$((FAILURE_COUNT + 1))
fi

if [ "${DECEPTION_AUDIT_FINDINGS:-false}" = "true" ]; then
    echo "- deception-audit: WARNING — ${DECEPTION_FINDING_COUNT:-0} deception pattern(s) detected on this branch. This may indicate coordinated agent deception across sessions. Run \`./tools/ci/agent-protection/deception-audit.sh origin/master\` locally to see details." >> "$OUTPUT_FILE"
    # Deception audit is informational, not a hard failure
fi

echo "unattributed=$UNATTRIBUTED" >> "$GITHUB_OUTPUT"
echo "failure_count=$FAILURE_COUNT" >> "$GITHUB_OUTPUT"
if [ "$FAILURE_COUNT" -gt 0 ]; then
    echo "has_failures=true" >> "$GITHUB_OUTPUT"
    echo "::notice::Found $FAILURE_COUNT quality gate failure(s) to auto-resolve"
elif [ "$UNATTRIBUTED" = "true" ]; then
    echo "has_failures=false" >> "$GITHUB_OUTPUT"
    echo "::notice::No attributable quality gate failure -- skipping auto-resolve. This is not the same as everything passing: a check failed without recording a cause."
else
    echo "has_failures=false" >> "$GITHUB_OUTPUT"
    echo "::notice::All quality gates passed -- skipping auto-resolve"
fi
