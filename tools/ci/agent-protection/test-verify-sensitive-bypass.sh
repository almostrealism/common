#!/usr/bin/env bash
# ─── Regression tests for verify-sensitive-bypass.sh ──────────
#
# Verifies the HMAC-SHA256 signature verification in
# `verify-sensitive-bypass.sh` against the same algorithm the
# controller uses to produce the signature (HMAC-SHA256 with the
# shared secret, base64-URL-safe no padding).
#
# Usage:
#   test-verify-sensitive-bypass.sh
#
# Exit codes:
#   0  - all tests passed
#   1  - one or more tests failed

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-sensitive-bypass.sh"

PASS=0
FAIL=0
FAILED_TESTS=()

# ── Helpers ─────────────────────────────────────────────────────

# Compute the expected signature using python (matches the algorithm
# the Java controller uses and the one the script compares against).
expected_sig() {
    local secret="$1"
    local job_id="$2"
    SECRET="$secret" JOB_ID="$job_id" python3 - <<'PY'
import base64, hashlib, hmac, os
print(base64.urlsafe_b64encode(
    hmac.new(os.environ["SECRET"].encode("utf-8"),
             os.environ["JOB_ID"].encode("utf-8"),
             hashlib.sha256).digest()
).rstrip(b"=").decode("ascii"))
PY
}

# run_case NAME EXPECTED_EXIT [extra args to verify] BODY
# Writes BODY into a temp file, runs the verify script on it, and
# asserts the exit code equals EXPECTED_EXIT.
run_case() {
    local name="$1"
    local expected_exit="$2"
    local secret="$3"
    local msg_body="$4"

    local tmpdir msgfile actual_exit stdout_text
    tmpdir=$(mktemp -d)
    msgfile="$tmpdir/commit-msg.txt"
    printf '%s' "$msg_body" > "$msgfile"

    actual_exit=0
    stdout_text=$(AR_AGENT_BYPASS_SECRET="$secret" bash "$VERIFY" "$msgfile" 2>/dev/null) \
        || actual_exit=$?

    if [ "$actual_exit" -eq "$expected_exit" ]; then
        PASS=$((PASS + 1))
        printf '  PASS  %s\n' "$name"
    else
        FAIL=$((FAIL + 1))
        FAILED_TESTS+=("$name (expected $expected_exit, got $actual_exit; stdout='$stdout_text')")
        printf '  FAIL  %s  expected=%d got=%d\n' "$name" "$expected_exit" "$actual_exit"
    fi

    rm -rf "$tmpdir"
}

SECRET="this-is-only-a-test-shared-secret-1234567890"

# ── True positives ─────────────────────────────────────────────

SIG1=$(expected_sig "$SECRET" "job-abc-123")
run_case "valid signature" 0 "$SECRET" "Some title

Body of the commit message.

Sensitive-File-Bypass: job-abc-123=$SIG1"

SIG2=$(expected_sig "$SECRET" "job-XYZ-with-many-chars")
run_case "valid signature no leading text" 0 "$SECRET" \
    "Sensitive-File-Bypass: job-XYZ-with-many-chars=$SIG2"

SIG3=$(expected_sig "$SECRET" "job-1")
run_case "valid signature with trailing newline" 0 "$SECRET" \
    "title

Sensitive-File-Bypass: job-1=$SIG3
"

# ── True negatives ─────────────────────────────────────────────

run_case "no trailer present" 2 "$SECRET" "Just a regular commit message
with no Sensitive-File-Bypass trailer at all."

run_case "signature wrong secret" 3 "$SECRET" \
    "title

Sensitive-File-Bypass: job-abc-123=wrong-signature-here"

# Compute a signature for one job and present it as if it were for another
SIG4=$(expected_sig "$SECRET" "job-real")
run_case "signature wrong job" 3 "$SECRET" \
    "title

Sensitive-File-Bypass: job-OTHER=$SIG4"

# Forging attempt: agent supplies a trailer without a valid signature.
# The script must reject the trailer (not honour it).
run_case "forged signature rejected" 3 "$SECRET" \
    "title

Sensitive-File-Bypass: job-abc-123=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

# Case-insensitive key matching: the agent tries to disguise the key
# (the script uses a case-insensitive match, so this is a valid
# bypass attempt and must be VERIFIED, not rejected outright). An
# invalid signature under the disguised key still fails.
SIG5=$(expected_sig "$SECRET" "job-abc-123")
run_case "case-insensitive key valid sig" 0 "$SECRET" \
    "title

sensitive-file-bypass: job-abc-123=$SIG5"

# ── Output contract ─────────────────────────────────────────────

# On success the script prints the verified job ID on stdout (for
# audit logging). Verify that contract explicitly.
tmpdir=$(mktemp -d)
msgfile="$tmpdir/msg.txt"
SIG6=$(expected_sig "$SECRET" "job-audit-check")
printf 'title\n\nSensitive-File-Bypass: job-audit-check=%s\n' "$SIG6" > "$msgfile"
OUT=$(AR_AGENT_BYPASS_SECRET="$SECRET" bash "$VERIFY" "$msgfile" 2>/dev/null) || true
if [ "$OUT" = "job-audit-check" ]; then
    PASS=$((PASS + 1))
    printf '  PASS  success path prints verified job ID on stdout\n'
else
    FAIL=$((FAIL + 1))
    FAILED_TESTS+=("success path stdout: expected 'job-audit-check', got '$OUT'")
    printf '  FAIL  success path stdout: expected "job-audit-check", got "%s"\n' "$OUT"
fi
rm -rf "$tmpdir"

# ── Missing-secret error path ─────────────────────────────────

if AR_AGENT_BYPASS_SECRET="" bash "$VERIFY" /dev/null 2>/dev/null; then
    FAIL=$((FAIL + 1))
    FAILED_TESTS+=("missing secret should exit non-zero")
    printf '  FAIL  missing secret should exit non-zero\n'
else
    PASS=$((PASS + 1))
    printf '  PASS  missing secret exits non-zero\n'
fi

# ── Summary ────────────────────────────────────────────────────

printf '\nResults: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf '\nFailed tests:\n'
    for t in "${FAILED_TESTS[@]}"; do
        printf '  - %s\n' "$t"
    done
    exit 1
fi
exit 0
