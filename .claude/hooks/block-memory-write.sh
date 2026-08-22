#!/usr/bin/env bash
# PreToolUse — Write/Edit/MultiEdit/NotebookEdit/Bash: block any attempt
# to store a memory using Claude Code's built-in file-based memory system.
#
# Target: ~/.claude/projects/<project>/memory/
#
# The built-in memory system is local to one conversation and invisible
# to other sessions, to other agents (including FlowTree agents), and to
# the shared documentation corpus. For this project, memories MUST be
# stored via the shared ar-memory service — use:
#   mcp__ar-manager__memory_store / mcp__ar-manager__memory_recall
# for every session, interactive or job.
#
# Exit 0 → allow
# Exit 2 → BLOCK (stderr shown to the model as reason)
set -euo pipefail

INPUT=$(cat)

RESULT=$(FLOWTREE_HOOK_INPUT="$INPUT" python3 <<'PYEOF'
import os, json, re, sys

raw = os.environ.get("FLOWTREE_HOOK_INPUT", "")
try:
    data = json.loads(raw)
except Exception:
    print("ALLOW")
    sys.exit(0)

tool = data.get("tool_name", "")
ti   = data.get("tool_input", {}) or {}

# Matches any path that passes through ".claude/projects/<something>/memory"
# (absolute, relative, or ~-expanded).
MEMORY_RE = re.compile(r'(?:^|/)\.claude/projects/[^/]+/memory(?:/|$)')

def hits_memory(path):
    if not path:
        return False
    # Expand ~ but don't require the file to exist.
    expanded = os.path.expanduser(path)
    return bool(MEMORY_RE.search(expanded))

if tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
    if hits_memory(ti.get("file_path", "")):
        print("BLOCK:" + ti.get("file_path", ""))
        sys.exit(0)
elif tool == "Bash":
    cmd = ti.get("command", "") or ""
    # Only block when the command both mentions the memory dir AND looks
    # like a write operation. Reads (grep/cat/ls on memory files) are
    # tolerated so past memories can be inspected before migration.
    if MEMORY_RE.search(cmd):
        write_hint = re.compile(
            r'(\s>\s|\s>>\s|^>|\btee\b|\btouch\b|\bmkdir\b|\brm\b|'
            r'\bcp\b|\bmv\b|\bln\b|\bsed\s+-i|\bdd\s+of=|\bpython3?\s+-c|'
            r'\bcat\s+>|\bprintf\b.*>)'
        )
        if write_hint.search(cmd):
            print("BLOCK:" + cmd[:200])
            sys.exit(0)

print("ALLOW")
PYEOF
)

if echo "$RESULT" | grep -q "^BLOCK:"; then
    TARGET=$(echo "$RESULT" | sed 's/^BLOCK://')
    cat >&2 <<EOF
BLOCKED: Claude Code's built-in memory system is disabled for this project.

Target: ${TARGET}

Do NOT write under \$HOME/.claude/projects/<project>/memory/. Those
files are local to one conversation and are invisible to every other
session, agent, and tool in the project.

The ONLY valid memory tools for this project are:

    mcp__ar-manager__memory_store      — store a memory (namespace, tags)
    mcp__ar-manager__memory_recall     — semantic search over memories
    mcp__ar-manager__memory_namespaces — list namespaces and their activity
    mcp__ar-manager__workstream_context — the full narrative of a branch

They are the same tools in every session, interactive or job. They
persist to the shared ar-memory service, so other sessions / agents /
tools can find what you stored. The built-in file-based system cannot
do that.

ar-manager runs remotely and cannot see this working directory, so it
takes the repo_url and branch you send at face value. Read them from
git — never from the harness gitStatus snapshot, which goes stale the
moment it is captured.

If you were about to store a user-profile, feedback, project, or
reference memory: call mcp__ar-manager__memory_store with an
appropriate namespace instead.
EOF
    exit 2
fi

exit 0
