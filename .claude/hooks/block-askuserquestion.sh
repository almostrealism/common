#!/usr/bin/env bash
# PreToolUse — AskUserQuestion: block the multiple-choice question tool.
#
# AskUserQuestion (the tool that poses a multiple-choice question to the user)
# is broken in the current Claude Code version, so it is disabled here. The
# matcher in settings.json already scopes this hook to AskUserQuestion; the
# tool_name re-check below keeps the script correct if it is ever wired to a
# broader matcher.
#
# Behavior: exit 2 with a BLOCKED message on stderr (which Claude Code feeds
# back to the model) when the tool is AskUserQuestion; exit 0 (no output)
# otherwise. The model should ask its question as plain text in its normal
# response instead.
set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(printf '%s' "$INPUT" | python3 -c "
import sys, json
try:
    print((json.load(sys.stdin) or {}).get('tool_name', '') or '')
except Exception:
    print('')
" 2>/dev/null || echo "")

if [ "$TOOL_NAME" = "AskUserQuestion" ]; then
  echo "BLOCKED: AskUserQuestion is disabled (it is broken in the current Claude Code version). Ask your question as plain text in your response instead of using the multiple-choice tool." >&2
  exit 2
fi

exit 0
