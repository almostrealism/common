#!/usr/bin/env bash
# file-length-advisory.sh — PostToolUse hook on Read
#
# Three thresholds govern source-file length:
#   ANNOUNCE   (1480) — when a file Claude reads reaches this, inject an
#                       early heads-up so there is room to plan a real
#                       refactor rather than a last-minute scramble.
#   RECOMMENDED (1500) — the length we recommend files stay at or below.
#                       A recommendation, not a build gate.
#   HARD_LIMIT (1600) — the checkstyle FileLength ceiling. The build FAILS
#                       beyond this. It sits 100 lines above the
#                       recommendation to leave headroom for an orderly
#                       split, NOT to license further growth.
#
# The advisory tells Claude the right response is to refactor into focused
# collaborators — NOT to compact code (collapsing if-statements, stripping
# javadoc, inlining lambdas) to squeeze under a threshold. The checkstyle
# FileLength rule's documented intent is "Files larger than this are a
# sign that a class is doing too much and should be split."
#
# Triggers on these source extensions:
#   java, py, ts, tsx, js, jsx, kt, scala, go, rs, swift,
#   c, cc, cpp, h, hpp, cs, rb
#
# Exit 0 always (silent on no-op; emits JSON to stdout when triggering)

set -euo pipefail

ANNOUNCE=1480
RECOMMENDED=1500
HARD_LIMIT=1600

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
[ "$LINES" -ge "$ANNOUNCE" ] || exit 0

# Compose and emit the advisory. The recommend-vs-require distinction and the
# anti-compaction spirit are the project owner's spec — keep them intact.
python3 - "$FILE_PATH" "$LINES" "$RECOMMENDED" "$HARD_LIMIT" <<'PY'
import json, sys
file_path, lines, recommended, hard = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
msg = (
    f"[file-length advisory: {file_path} is {lines} lines; "
    f"recommended max {recommended}, hard limit {hard}] "
    f"we recommend keeping source files at or below about {recommended} "
    f"lines. This is a recommendation, not a build failure: the checkstyle "
    f"FileLength rule does not fail the build until {hard} lines — 100 "
    "beyond the recommendation. That headroom exists to give you room for "
    "an orderly refactor as the file approaches the recommendation, NOT to "
    "license the file to keep growing toward the hard limit. "
    "The right response is to split the file into focused collaborators. "
    "The line limit is not a game for you to work to outwit with canny "
    "adjustments like compressing if statements into a single line - it is "
    "a mechanism to REMIND you to properly organize code into separate "
    "pieces that relate to one another in a coherent way. respect the "
    "SPIRIT of this requirement and THINK about ways to organize code into "
    "files that HELPS future developers understand what is going on. "
    "Your job, above and beyond any specific task you have been asked to "
    "complete, is to improve the quality of this repository over time: as a "
    "file crosses the recommendation, plan the refactor rather than deferring "
    "it until the build is about to fail."
)
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PostToolUse",
        "additionalContext": msg,
    }
}))
PY
