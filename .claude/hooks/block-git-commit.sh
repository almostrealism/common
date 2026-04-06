#!/usr/bin/env bash
# PreToolUse — Bash: block git commit.
# Agents stage with git add; humans commit.
set -euo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('command', ''))
" 2>/dev/null || echo "")

if echo "$COMMAND" | grep -qE 'git(\s+--[a-z-]+)*\s+commit'; then
    echo "BLOCKED: git commit is not permitted for agents." >&2
    echo "Stage changes with 'git add' only. The developer reviews and commits." >&2
    echo "This rule exists to prevent unauthorized commits. See CLAUDE.md." >&2
    exit 2
fi

exit 0
