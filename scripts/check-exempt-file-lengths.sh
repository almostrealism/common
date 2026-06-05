#!/usr/bin/env bash
# check-exempt-file-lengths.sh
#
# Enforces that files exempted from the global Checkstyle FileLength limit do not
# grow beyond the line counts recorded at the time of exemption.
#
# These files are too large to fix immediately and are excluded from the 1800-line
# Checkstyle rule via SuppressionSingleFilter entries in checkstyle.xml.  This
# script acts as the compensating control: it fails the build if any exempt file
# has grown since the exemption was granted.
#
# To update a limit after a legitimate reduction: lower the MAX value below.
# Never raise a MAX value without a corresponding reduction plan.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# List of "path-relative-to-repo-root|max-allowed-line-count" entries.
# Using a parallel list (instead of `declare -A`) so this works on bash 3.x,
# which is the default shell on macOS.
#
# To update a limit after a legitimate reduction: lower the MAX value below.
# Never raise a MAX value without a corresponding reduction plan.
EXEMPT_FILES=(
    # Pre-existing exemptions (exceeded 1800-line limit before the cap was lowered to 1600)
    "compute/algebra/src/main/java/org/almostrealism/collect/CollectionFeatures.java|3738"
    "engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java|2084"
)


FAILED=0

for ENTRY in "${EXEMPT_FILES[@]}"; do
    REL_PATH="${ENTRY%%|*}"
    MAX="${ENTRY##*|}"
    ABS_PATH="$REPO_ROOT/$REL_PATH"

    if [ ! -f "$ABS_PATH" ]; then
        echo "ERROR: exempt file not found: $REL_PATH" >&2
        FAILED=1
        continue
    fi

    ACTUAL=$(wc -l < "$ABS_PATH")

    if [ "$ACTUAL" -gt "$MAX" ]; then
        echo "FAIL: $REL_PATH has grown to $ACTUAL lines (exemption cap is $MAX)." >&2
        echo "      Reduce the file before merging, or split it into focused collaborators." >&2
        FAILED=1
    else
        echo "OK:   $REL_PATH ($ACTUAL / $MAX lines)"
    fi
done

if [ "$FAILED" -ne 0 ]; then
    exit 1
fi
