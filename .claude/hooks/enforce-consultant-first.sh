#!/usr/bin/env bash
# PreToolUse — Write/Edit: warn if consult has not been called recently.
#
# The tool moved to ar-manager, which carries the documentation corpus in
# its image and so answers from any repository rather than only from a
# checkout of this one. The marker path is unchanged so an in-flight
# session keeps its timestamp across the switch.
# before writing Java source files in computation-layer paths.
#
# This is a soft guard (exit 0) — it injects a reminder but does not block.
# The hard enforcement for producer violations happens in scan-producer-violations.sh.
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

# Only check Java source files in computation-layer paths
if [[ "$FILE_PATH" != *.java ]]; then
    exit 0
fi

RELEVANT=false
for path in "engine/ml/src/main" "engine/audio/src/main" "studio/" "domain/graph/src/main" "compute/"; do
    if [[ "$FILE_PATH" == *"$path"* ]]; then
        RELEVANT=true
        break
    fi
done

[[ "$RELEVANT" == "true" ]] || exit 0

MARKER="/tmp/.ar_consultant_last_${USER:-developer}.ts"
THRESHOLD=1800  # 30 minutes

if [[ ! -f "$MARKER" ]]; then
    cat <<'MSG'

╔══════════════════════════════════════════════════════════════════╗
║  AR-CONSULTANT HAS NOT BEEN CALLED THIS SESSION                  ║
╠══════════════════════════════════════════════════════════════════╣
║  CLAUDE.md Rule 1: Your FIRST tool call for every new task       ║
║  MUST be:                                                        ║
║                                                                  ║
║    mcp__ar-manager__consult                                   ║
║      question:"..."                                              ║
║      keywords:["SpecificClass", "method"]                        ║
║                                                                  ║
║  The consultant KNOWS THINGS YOU DO NOT KNOW.                    ║
║  Skipping it WILL produce wrong code.                            ║
╚══════════════════════════════════════════════════════════════════╝

MSG
    exit 0
fi

LAST=$(cat "$MARKER" 2>/dev/null || echo 0)
NOW=$(date +%s)
AGE=$((NOW - LAST))

if [[ "$AGE" -gt "$THRESHOLD" ]]; then
    MINUTES=$((AGE / 60))
    echo ""
    echo "⚠  consult was last called ${MINUTES} minutes ago."
    echo "   If this is a new task or a new code area, call consult first."
    echo "   mcp__ar-manager__consult question:\"...\" keywords:[\"SpecificClass\"]"
    echo ""
fi

exit 0
