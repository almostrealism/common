#!/usr/bin/env bash
# guard-ci-file.sh — PreToolUse hook
#
# Fires whenever Claude reads, edits, or writes analysis.yaml (the CI pipeline).
# Injects the full contents of .github/CLAUDE.md into the transcript so that
# CI architecture context is always present before any CI modification.
#
# Exit 0  → allow the tool call (after printing injected content)
# Exit 2  → block the tool call (not used here — we only inject, not block)

set -euo pipefail

# Parse the tool input from stdin (Claude passes JSON)
INPUT="$(cat)"
TOOL_NAME="$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tool_name',''))" 2>/dev/null || true)"
FILE_PATH="$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
# Different tools use different field names
print(inp.get('file_path', inp.get('path', '')))
" 2>/dev/null || true)"

# Only fire for analysis.yaml
if ! echo "$FILE_PATH" | grep -q "analysis\.yaml"; then
    exit 0
fi

CLAUDE_MD_PATH="$(dirname "$(dirname "$0")")/../.github/CLAUDE.md"

if [ ! -f "$CLAUDE_MD_PATH" ]; then
    # Try alternate relative path
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
    CLAUDE_MD_PATH="$REPO_ROOT/.github/CLAUDE.md"
fi

if [ ! -f "$CLAUDE_MD_PATH" ]; then
    echo "WARNING: .github/CLAUDE.md not found — CI architecture context unavailable" >&2
    exit 0
fi

# Inject content into transcript via stdout
cat <<'INJECTION_HEADER'
╔══════════════════════════════════════════════════════════════════════════════╗
║  CI ARCHITECTURE CONTEXT — AUTO-INJECTED by guard-ci-file hook             ║
║  You are about to touch analysis.yaml. Read every word below FIRST.        ║
╚══════════════════════════════════════════════════════════════════════════════╝

INJECTION_HEADER

cat "$CLAUDE_MD_PATH"

cat <<'INJECTION_FOOTER'

╔══════════════════════════════════════════════════════════════════════════════╗
║  END CI ARCHITECTURE CONTEXT                                                ║
║  MANDATORY CHECKLIST before editing analysis.yaml:                         ║
║  [ ] Have you verified BOTH directions of every dependency you mentioned?  ║
║  [ ] Have you grepped ALL pom.xml files (not just the obvious one)?        ║
║  [ ] Does analysis.needs include every job that uploads coverage-* ?       ║
║  [ ] Have you checked .github/CI_ARCHITECTURE.md for the full job list?   ║
╚══════════════════════════════════════════════════════════════════════════════╝
INJECTION_FOOTER

exit 0
