#!/usr/bin/env bash
# PreToolUse — Write/Edit: block pom.xml changes that affect dependency structure.
#
# Non-dependency changes (compiler args, plugin config, properties) are allowed.
# Blocked: additions or removals of <dependency>, <dependencies>,
#          <dependencyManagement>, <module>, <modules>, <parent> elements.
#
# Exit 0  → allow
# Exit 2  → BLOCK (dependency structure change detected)
set -euo pipefail

INPUT=$(cat)

FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null || echo "")

if [[ "$FILE_PATH" != *pom.xml ]]; then
    exit 0
fi

TOOL_NAME=$(echo "$INPUT" | python3 -c "
import sys, json
print(json.load(sys.stdin).get('tool_name', ''))
" 2>/dev/null || echo "")

OLD_STRING=$(echo "$INPUT" | python3 -c "
import sys, json
print(json.load(sys.stdin).get('tool_input', {}).get('old_string', ''))
" 2>/dev/null || echo "")

NEW_STRING=$(echo "$INPUT" | python3 -c "
import sys, json
inp = json.load(sys.stdin).get('tool_input', {})
print(inp.get('new_string', inp.get('content', '')))
" 2>/dev/null || echo "")

RESULT=$(TOOL_NAME="$TOOL_NAME" \
         OLD_STRING="$OLD_STRING" \
         NEW_STRING="$NEW_STRING" \
         FILE_PATH="$FILE_PATH" \
         python3 - <<'PYEOF'
import difflib, os, re, sys

tool      = os.environ.get("TOOL_NAME", "")
old_str   = os.environ.get("OLD_STRING", "")
new_str   = os.environ.get("NEW_STRING", "")
file_path = os.environ.get("FILE_PATH", "")

# Structural elements that define the dependency graph.
# Changes to any of these lines are blocked.
BLOCKED = [
    r'<dependenc',      # <dependency>, <dependencies>, <dependencyManagement>
    r'</?modules?\b',   # <module>, <modules>, </module>, </modules>
    r'</?parent\b',     # <parent>, </parent>
]

def dep_lines(lines):
    found = []
    for line in lines:
        for pat in BLOCKED:
            if re.search(pat, line):
                found.append(line.strip())
                break
    return found

if tool == "Write":
    try:
        with open(file_path) as f:
            old_lines = f.read().splitlines()
    except FileNotFoundError:
        old_lines = []
    new_lines = new_str.splitlines()
elif tool == "Edit":
    old_lines = old_str.splitlines()
    new_lines = new_str.splitlines()
else:
    print("OK")
    sys.exit(0)

# Isolate only the lines that actually changed (added or removed),
# excluding unified-diff headers and unchanged context lines.
diff = list(difflib.unified_diff(old_lines, new_lines, lineterm=""))
changed = [
    line[1:]
    for line in diff
    if (line.startswith("+") or line.startswith("-"))
    and not line.startswith("+++")
    and not line.startswith("---")
]

blocking = dep_lines(changed)
if blocking:
    print("DEPENDENCY_CHANGE_DETECTED")
    for line in blocking:
        print(f"  {line}")
else:
    print("OK")
PYEOF
)

if echo "$RESULT" | grep -q "^DEPENDENCY_CHANGE_DETECTED"; then
    echo "BLOCKED: This pom.xml edit modifies dependency structure, which agents must not change." >&2
    echo "" >&2
    echo "Offending lines:" >&2
    echo "$RESULT" | grep -v "^DEPENDENCY_CHANGE_DETECTED" | sed 's/^/  /' >&2
    echo "" >&2
    echo "Dependency structure (<dependency>, <module>, <parent>) is externally controlled." >&2
    echo "Non-dependency changes (compiler arguments, plugin config, properties) are allowed." >&2
    exit 2
fi

exit 0
