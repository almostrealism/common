#!/usr/bin/env bash
# verify-enforcement-config.sh
#
# Verifies that protected enforcement-configuration files have not been modified
# relative to the committed baseline. This guard prevents automated agents (including
# this one) from weakening enforcement rules to make violations disappear.
#
# Protected files:
#   - checkstyle.xml                     : main checkstyle rules
#   - checkstyle-suppressions.xml        : suppression list for DirectSystemOut
#   - scripts/check-exempt-file-lengths.sh : file-length exemption tracking
#   - This script itself (scripts/verify-enforcement-config.sh)
#   - The baseline file (scripts/enforcement-config.sha256)
#
# If any protected file's checksum differs from the baseline, this script exits 1.
#
# IMPORTANT: If you are an automated agent, do NOT change this — fix your code to
# conform to the rules instead. If you are the operator deliberately changing the
# rules, regenerate the baseline by running:
#   scripts/generate-enforcement-baseline.sh
#
# The baseline represents "the rules as the operator wants them enforced right now."
# Regenerating it is a deliberate human action that opts out of enforcement for
# one cycle.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE_FILE="$REPO_ROOT/scripts/enforcement-config.sha256"

if [ ! -f "$BASELINE_FILE" ]; then
    echo "ERROR: Baseline file not found: $BASELINE_FILE" >&2
    echo "       Run scripts/generate-enforcement-baseline.sh to create it." >&2
    exit 1
fi

FAILED=0
CHANGED_FILES=""

while IFS= read -r LINE; do
    if [ -z "$LINE" ]; then
        continue
    fi

    BASELINE_CHECKSUM=$(echo "$LINE" | awk '{print $1}')
    FILE_PATH=$(echo "$LINE" | awk '{$1=""; print substr($0,2)}')

    if [[ "$FILE_PATH" == /* ]]; then
        ABS_PATH="$FILE_PATH"
    else
        ABS_PATH="$REPO_ROOT/$FILE_PATH"
    fi

    if [ ! -f "$ABS_PATH" ]; then
        echo "ERROR: Protected file not found: $FILE_PATH" >&2
        FAILED=1
        continue
    fi

    ACTUAL_CHECKSUM=$(sha256sum "$ABS_PATH" | awk '{print $1}')

    if [ "$ACTUAL_CHECKSUM" != "$BASELINE_CHECKSUM" ]; then
        echo "FAIL: $FILE_PATH has been modified (checksum mismatch)" >&2
        echo "      Enforcement config is protected from modification." >&2
        echo "      If you are an automated agent, do NOT change this — fix your code" >&2
        echo "      to conform instead. If you are the operator deliberately changing" >&2
        echo "      the rules, regenerate the baseline manually." >&2
        FAILED=1
        CHANGED_FILES="$CHANGED_FILES $FILE_PATH"
    fi
done < "$BASELINE_FILE"

if [ "$FAILED" -ne 0 ]; then
    echo "" >&2
    echo "Verification FAILED. Protected file(s) were modified." >&2
    exit 1
fi

echo "Enforcement config verification passed (no protected files modified)."
exit 0
