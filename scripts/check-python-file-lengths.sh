#!/usr/bin/env bash
# check-python-file-lengths.sh
#
# Caps Python source files at 1600 lines, the same limit Checkstyle's FileLength
# rule applies to Java. Python has no Checkstyle equivalent in this build, so
# the cap is enforced here instead.
#
# The limit exists for the same reason as the Java one: a file past this size
# stops being readable in one sitting, and the amount of context needed to
# change one part of it grows without bound.
#
# A small set of files exceeds the cap today and is listed below with the size
# it had when the exemption was granted. That size is a ceiling, not a licence:
# an exempt file may shrink freely and may never grow. Removing an exemption
# means splitting the file under 1600 lines and deleting its entry here.
#
# To update a limit after a legitimate reduction: lower the MAX value below.
# Never raise a MAX value without a corresponding reduction plan.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIMIT=1600

# "path-relative-to-repo-root|max-allowed-line-count".
# A parallel list rather than an associative array, so this runs on the bash 3.x
# that ships with macOS — matching check-exempt-file-lengths.sh.
EXEMPT_FILES=(
    # The ar-manager MCP tool suite. server.py was 6392 lines before its tool
    # groups were split into per-domain *_tools.py modules; these two are what
    # remains above the cap. Both are on a downward path: server.py still holds
    # helpers that could move, and workstream_tools.py holds the largest tool
    # group, which could split again along register/update versus lifecycle.
    "tools/mcp/manager/server.py|1633"
    "tools/mcp/manager/workstream_tools.py|1833"

    # The ar-manager test suite. Far and away the largest offender, and the one
    # most worth splitting: it mirrors a server file that has since become eight
    # modules, so the natural split follows those seams.
    "tools/mcp/manager/test_server.py|7204"
)

FAILED=0

is_exempt() {
    local candidate="$1"
    for entry in "${EXEMPT_FILES[@]}"; do
        [ "${entry%%|*}" = "$candidate" ] && return 0
    done
    return 1
}

# ---- Exempt files: must not grow beyond their recorded size ----------------

for ENTRY in "${EXEMPT_FILES[@]}"; do
    REL_PATH="${ENTRY%%|*}"
    MAX="${ENTRY##*|}"
    ABS_PATH="$REPO_ROOT/$REL_PATH"

    if [ ! -f "$ABS_PATH" ]; then
        # A missing exempt file is reported rather than ignored: it usually
        # means the file was renamed or split, and the entry should go with it.
        echo "ERROR: exempt file not found: $REL_PATH" >&2
        FAILED=1
        continue
    fi

    ACTUAL=$(wc -l < "$ABS_PATH" | tr -d ' ')
    if [ "$ACTUAL" -gt "$MAX" ]; then
        echo "FAIL: $REL_PATH has grown to $ACTUAL lines (exemption cap is $MAX)." >&2
        echo "      Split it into focused modules, or reduce it, before merging." >&2
        FAILED=1
    else
        echo "OK:   $REL_PATH ($ACTUAL / $MAX lines, exempt)"
    fi
done

# ---- Everything else: must be at or under the cap ---------------------------

while IFS= read -r ABS_PATH; do
    REL_PATH="${ABS_PATH#"$REPO_ROOT"/}"
    is_exempt "$REL_PATH" && continue

    ACTUAL=$(wc -l < "$ABS_PATH" | tr -d ' ')
    if [ "$ACTUAL" -gt "$LIMIT" ]; then
        echo "FAIL: $REL_PATH is $ACTUAL lines, over the $LIMIT-line limit." >&2
        echo "      Split it into focused modules. If it genuinely cannot be" >&2
        echo "      split now, add it to EXEMPT_FILES in $(basename "${BASH_SOURCE[0]}")" >&2
        echo "      with its current size and a note saying why." >&2
        FAILED=1
    fi
done < <(find "$REPO_ROOT" -name '*.py' \
    -not -path '*/target/*' \
    -not -path '*/__pycache__/*' \
    -not -path '*/node_modules/*' \
    -not -path '*/.git/*' \
    -not -path '*/venv/*' \
    -not -path '*/.venv/*' \
    | sort)

if [ "$FAILED" -ne 0 ]; then
    exit 1
fi

echo "All Python files are within the ${LIMIT}-line limit."
