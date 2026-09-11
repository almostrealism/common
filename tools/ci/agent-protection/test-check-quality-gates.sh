#!/usr/bin/env bash
# ─── Regression tests for check-quality-gates.sh ────────────────────
#
# check-quality-gates.sh turns a set of pass/fail environment variables
# (job outputs from analysis.yaml) into a human-readable failure list for
# the auto-resolve prompt. The test-integrity-check job runs three
# sequential steps under one boolean output (enforcement tampering, the
# exfiltration guard, then test-hiding); an earlier step failing skips
# the later ones, so TEST_INTEGRITY_PASSED=false alone does not say which
# one actually failed. These tests pin down that ENFORCEMENT_TAMPERED and
# EXFIL_GUARD_FAILED select the correct specific message, and that the
# generic test-hiding message remains the fallback when neither is set.
#
# Usage:
#   test-check-quality-gates.sh
#
# Exit codes:
#   0 - all tests passed
#   1 - one or more tests failed

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$SCRIPT_DIR/check-quality-gates.sh"

PASS=0
FAIL=0
FAILED_TESTS=()

# run_case NAME EXPECTED_HAS_FAILURES MUST_CONTAIN [MUST_NOT_CONTAIN] -- ENV=VAL...
# Runs the script with the given environment (all other gate variables
# default to passing), and checks the output file for expected substrings
# and the has_failures output.
run_case() {
    local name="$1" expect_failures="$2" must_contain="$3" must_not_contain="$4"
    shift 4
    local out gh_out actual_has_failures=""
    out=$(mktemp)
    gh_out=$(mktemp)
    env -i PATH="$PATH" HOME="${HOME:-/tmp}" GITHUB_OUTPUT="$gh_out" "$@" \
        bash "$SCRIPT" "$out" >/dev/null 2>&1
    actual_has_failures=$(grep '^has_failures=' "$gh_out" | tail -1 | cut -d= -f2)

    local ok=true
    if [ "$actual_has_failures" != "$expect_failures" ]; then
        ok=false
    fi
    if [ -n "$must_contain" ] && ! grep -qF "$must_contain" "$out"; then
        ok=false
    fi
    if [ -n "$must_not_contain" ] && grep -qF "$must_not_contain" "$out"; then
        ok=false
    fi

    if [ "$ok" = "true" ]; then
        PASS=$((PASS + 1))
        echo "  PASS: $name"
    else
        FAIL=$((FAIL + 1))
        FAILED_TESTS+=("$name")
        echo "  FAIL: $name (has_failures=$actual_has_failures)"
        echo "    --- output file ---"
        sed 's/^/    /' "$out"
    fi
    rm -f "$out" "$gh_out"
}

echo "check-quality-gates.sh tests"

run_case "all gates passing produces no failures" "false" "" "" \
    JAVADOC_PASSED=true TIMEOUT_PASSED=true DUPLICATE_PASSED=true \
    TEST_INTEGRITY_PASSED=true CHECKSTYLE_PASSED=true

run_case "enforcement tampering reports the tampering message, not test-hiding" "true" \
    "Enforcement infrastructure" "Existing test files were modified" \
    TEST_INTEGRITY_PASSED=false ENFORCEMENT_TAMPERED=true EXFIL_GUARD_FAILED=false

run_case "exfiltration guard failure reports the guard message, not test-hiding" "true" \
    "exfiltration guard hook is missing, unregistered, or was modified" "Existing test files were modified" \
    TEST_INTEGRITY_PASSED=false ENFORCEMENT_TAMPERED=false EXFIL_GUARD_FAILED=true

run_case "plain test-hiding failure falls back to the generic message" "true" \
    "Existing test files were modified in ways that hide failures" "" \
    TEST_INTEGRITY_PASSED=false ENFORCEMENT_TAMPERED=false EXFIL_GUARD_FAILED=false

run_case "test-hiding fallback also applies when the two flags are unset" "true" \
    "Existing test files were modified in ways that hide failures" "" \
    TEST_INTEGRITY_PASSED=false

run_case "enforcement flag takes priority when both specific flags are set" "true" \
    "Enforcement infrastructure" "exfiltration guard hook is missing" \
    TEST_INTEGRITY_PASSED=false ENFORCEMENT_TAMPERED=true EXFIL_GUARD_FAILED=true

echo ""
echo "Passed: $PASS  Failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf '  failed: %s\n' "${FAILED_TESTS[@]}"
    exit 1
fi
exit 0
