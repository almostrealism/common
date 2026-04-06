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

# Map of path-relative-to-repo-root -> max allowed line count at time of exemption
declare -A EXEMPT_FILES
EXEMPT_FILES["compute/algebra/src/main/java/org/almostrealism/collect/CollectionFeatures.java"]=3738
EXEMPT_FILES["base/code/src/main/java/io/almostrealism/expression/Expression.java"]=2094
EXEMPT_FILES["engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java"]=2084
EXEMPT_FILES["domain/graph/src/main/java/org/almostrealism/layers/LayerFeatures.java"]=2006

FAILED=0

for REL_PATH in "${!EXEMPT_FILES[@]}"; do
    MAX="${EXEMPT_FILES[$REL_PATH]}"
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
