#!/usr/bin/env bash
# file-length-advisory.sh — PostToolUse hook on Read
#
# When Claude reads a source code file >= THRESHOLD lines, inject a
# system-reminder telling Claude the file is approaching the SOFT_LIMIT
# line cap and that the right response is to refactor into focused
# collaborators — NOT to compact code (collapsing if-statements, stripping
# javadoc, inlining lambdas) to squeeze under the cap. The checkstyle
# FileLength rule's documented intent is "Files larger than this are a
# sign that a class is doing too much and should be split."
#
# THRESHOLD is set 100 lines below SOFT_LIMIT so Claude is reminded
# *before* hitting the actual checkstyle ceiling, giving it room to make
# a real structural decision rather than a last-minute compaction.
#
# Triggers on these source extensions:
#   java, py, ts, tsx, js, jsx, kt, scala, go, rs, swift,
#   c, cc, cpp, h, hpp, cs, rb
#
# Exit 0 always (silent on no-op; emits JSON to stdout when triggering)

set -euo pipefail

THRESHOLD=1400
SOFT_LIMIT=1500

INPUT="$(cat)"
FILE_PATH="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
resp = d.get('tool_response') or {}
inp = d.get('tool_input') or {}
print(resp.get('filePath') or inp.get('file_path') or '')
" 2>/dev/null || true)"

# Nothing to do if no path or file is missing.
[ -n "$FILE_PATH" ] || exit 0
[ -f "$FILE_PATH" ] || exit 0

# Only flag known source-code extensions. Conservative on purpose —
# we don't want to nag on data files, config, generated artifacts, or
# vendored code.
case "$FILE_PATH" in
    *.java|*.py|*.ts|*.tsx|*.js|*.jsx|*.kt|*.scala|*.go|*.rs|*.swift\
    |*.c|*.cc|*.cpp|*.h|*.hpp|*.cs|*.rb) ;;
    *) exit 0 ;;
esac

LINES="$(wc -l < "$FILE_PATH" 2>/dev/null | tr -d ' ' || true)"
[ -n "$LINES" ] || exit 0
[ "$LINES" -ge "$THRESHOLD" ] || exit 0

# Compose and emit the advisory. The wording after the header is verbatim
# from the project owner's spec — do not soften or paraphrase.
python3 - "$FILE_PATH" "$LINES" "$SOFT_LIMIT" "$THRESHOLD" <<'PY'
import json, sys
file_path, lines, soft, threshold = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
msg = (
    f"[file-length advisory: {file_path} is {lines} lines; "
    f"soft limit {soft}, warning threshold {threshold}] "
    "this file is getting close to the line limit, which means it "
    "will soon need to be refactored into multiple separate files. "
    "the line limit is not a game for you to work to outwit with "
    "canny adjustments like compressing if statements into a single "
    "line - it is a mechanism to REMIND you to properly organize code "
    "into separate pieces that relate to one another in a coherent way. "
    "respect the SPIRIT of this requirement and THINK about ways to "
    "organize code into files that HELPS future developers understand "
    "what is going on."
)
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PostToolUse",
        "additionalContext": msg,
    }
}))
PY
