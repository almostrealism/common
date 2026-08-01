#!/usr/bin/env bash
# PostToolUse — Write/Edit/MultiEdit: warn when a source-file write introduces a
# line-number reference in a comment (e.g. "Foo.java:123" or "line 660-664").
# Line numbers go stale the instant any line shifts above them — i.e. on almost
# every edit — and then they point the next reader at the WRONG place. Reference
# the class, method, or symbol instead; never a line number.
# Warns to stdout (visible in transcript); does not block (exit 0). The hard gate
# is CodePolicyViolationDetector (LINE_NUMBER_REFERENCE_IN_COMMENT), which fails the build.
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

# Source code files only.
case "$FILE_PATH" in
    *.java|*.pdsl|*.proto|*.kt|*.c|*.h|*.hpp|*.cpp|*.cc|*.py|*.js|*.ts) ;;
    *) exit 0 ;;
esac
[[ -f "$FILE_PATH" ]] || exit 0

HITS=$(grep -nE "\b[A-Za-z_][A-Za-z0-9_]*\.(java|pdsl|proto|kt|cpp|cc|hpp|h|py|js|ts):[0-9]+|\b[Ll]ines?[[:space:]]+[0-9]+" "$FILE_PATH" 2>/dev/null | head -20 || true)

if [[ -n "$HITS" ]]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    printf "║  LINE-NUMBER REFERENCE IN A COMMENT: %-29s║\n" "$(basename "$FILE_PATH")"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "$HITS" | sed 's/^/  ✗ /'
    echo ""
    echo "  This is a mistake. A line number is stale the instant any line is added"
    echo "  or removed above it — which is nearly every edit — and then it sends the"
    echo "  next reader to the WRONG place. NEVER cite a line number in a comment."
    echo "  Reference the class, method, or symbol and let the reader find it."
    echo ""
    echo "  The build fails on this (CodePolicyViolationDetector:"
    echo "  LINE_NUMBER_REFERENCE_IN_COMMENT). Fix it before committing."
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
fi

exit 0
