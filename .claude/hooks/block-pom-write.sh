#!/usr/bin/env bash
# PreToolUse — Write/Edit: block pom.xml modification.
# The dependency graph is externally controlled. Agents must never modify pom.xml.
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
inp = data.get('tool_input', {})
# Write uses file_path; Edit uses file_path
print(inp.get('file_path', ''))
" 2>/dev/null || echo "")

if [[ "$FILE_PATH" == *pom.xml ]]; then
    echo "BLOCKED: pom.xml modification is not permitted for agents." >&2
    echo "" >&2
    echo "The Maven module dependency graph is externally controlled." >&2
    echo "Write code assuming dependencies exist, run 'mvn compile'," >&2
    echo "and inform the user if it fails. Do NOT touch pom.xml." >&2
    exit 2
fi

exit 0
