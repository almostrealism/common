#!/usr/bin/env bash
# ─── Verify a Sensitive-File-Bypass commit trailer ─────────────
#
# Standalone helper that verifies the HMAC-SHA256 signature in a
# `Sensitive-File-Bypass: <job-id>=<signature>` trailer found in a
# commit message. The signature must match
#
#     HMAC-SHA256(secret, "<job-id>")
#
# where `secret` is the same shared secret the controller used to
# produce the signature (env var `AR_AGENT_BYPASS_SECRET`).
#
# This script is deliberately decoupled from
# `validate-agent-commit.sh` so it can be unit-tested in isolation
# and so that the controller's bootstrapping situation (the script
# change to call this helper from validate-agent-commit.sh is a
# follow-up) does not block landing the harness/controller wiring.
#
# Usage:
#   verify-sensitive-bypass.sh <commit-message-file>
#
# Exit codes:
#   0  - valid bypass signature present
#   1  - invalid arguments / secret missing
#   2  - no trailer present in the commit message
#   3  - trailer present but signature does not match
#
# The script NEVER produces output unless the bypass is verified.
# When verification succeeds, the verified job ID is printed on
# stdout so the caller can log/audit which job was authorized.

set -euo pipefail

MSG_FILE="${1:-}"

if [ -z "$MSG_FILE" ]; then
    echo "Usage: $0 <commit-message-file>" >&2
    exit 1
fi

if [ ! -r "$MSG_FILE" ]; then
    echo "verify-sensitive-bypass: cannot read $MSG_FILE" >&2
    exit 1
fi

SECRET="${AR_AGENT_BYPASS_SECRET:-}"
if [ -z "$SECRET" ]; then
    echo "verify-sensitive-bypass: AR_AGENT_BYPASS_SECRET is not set" >&2
    exit 1
fi

# Pull the trailer line. Case-insensitive, tolerant of leading
# whitespace, anchored at line start. Use grep -i -m1 so we don't
# depend on gawk's IGNORECASE extension (mawk on Debian/Ubuntu
# doesn't support it).
TRAILER_LINE=$(grep -i -m1 -E '^[[:space:]]*Sensitive-File-Bypass[[:space:]]*:' "$MSG_FILE" || true)

if [ -z "$TRAILER_LINE" ]; then
    exit 2
fi

# Strip the key prefix to get "<job-id>=<signature>".
PAYLOAD="${TRAILER_LINE#*:}"
PAYLOAD="$(printf '%s' "$PAYLOAD" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"

JOB_ID="${PAYLOAD%%=*}"
SIG="${PAYLOAD#*=}"

if [ -z "$JOB_ID" ] || [ "$JOB_ID" = "$PAYLOAD" ] || [ -z "$SIG" ]; then
    echo "verify-sensitive-bypass: malformed trailer (expected <job-id>=<sig>)" >&2
    exit 3
fi

# Reproduce the controller's signature and compare. The controller
# uses Java's Mac with HmacSHA256 then base64-URL-safe-no-padding,
# so the expected signature is the URL-safe base64 alphabet
# (A-Z a-z 0-9 - _) without trailing '='. We use openssl for
# the HMAC and a small python helper for the URL-safe-no-padding
# base64 encoding because base64(1) doesn't expose a URL-safe
# option on every platform.

EXPECTED_SIG=$(SECRET="$SECRET" JOB_ID="$JOB_ID" python3 - <<'PY' 2>/dev/null
import base64
import hashlib
import hmac
import os

secret = os.environ["SECRET"].encode("utf-8")
job_id = os.environ["JOB_ID"].encode("utf-8")
print(base64.urlsafe_b64encode(hmac.new(secret, job_id, hashlib.sha256).digest()).rstrip(b"=").decode("ascii"))
PY
)

if [ -z "$EXPECTED_SIG" ]; then
    echo "verify-sensitive-bypass: could not compute expected signature (python3 missing?)" >&2
    exit 1
fi

if [ "$SIG" = "$EXPECTED_SIG" ]; then
    printf '%s\n' "$JOB_ID"
    exit 0
fi

echo "verify-sensitive-bypass: signature mismatch for job $JOB_ID" >&2
exit 3
