#!/usr/bin/env bash
# PreToolUse — Bash: catch `git log` invocations and redirect the agent to
# mcp__ar-manager__memory_branch_context, which is the correct tool for
# "what have other agents been doing on this branch" / "catch up on this
# workstream".
#
# `git log` only shows commit titles — it misses the narrative (why the
# agent tried X, what broke, what was decided, which path was abandoned)
# that lives in branch memories. memory_branch_context returns memories,
# recent jobs, and commits in a single call.
#
# Soft guard: never blocks. Prints a loud reminder and exits 0, so the
# `git log` still runs when the agent has a genuine reason (e.g., needing
# a commit SHA for `git show`).

set -euo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('command', ''))
" 2>/dev/null || echo "")

# Match `git log` (and `git --flag log`), but NOT subcommands that happen
# to contain "log" like `git shortlog` or paths like `./log`. The pattern
# requires a word boundary before "log" and either end-of-command or a
# space/flag after.
if echo "$COMMAND" | grep -qE '(^|[[:space:]|;&])git([[:space:]]+--[a-z-]+)*[[:space:]]+log([[:space:]]|$)'; then
    cat >&2 <<'EOF'

╔══════════════════════════════════════════════════════════════════════╗
║  REMINDER: `git log` is the wrong tool for catching up on a branch.  ║
╠══════════════════════════════════════════════════════════════════════╣
║                                                                      ║
║  `git log` shows commit TITLES. It does not show what other agents   ║
║  tried, found, decided, or abandoned. Those live in the branch       ║
║  memories, which is exactly what memory_branch_context returns:      ║
║                                                                      ║
║      mcp__ar-manager__memory_branch_context(                         ║
║          workstream_id=<from workstream_list>,                       ║
║          include_messages=false,                                     ║
║          limit=25,                                                   ║
║          job_limit=10)                                               ║
║                                                                      ║
║  It returns memories (cross-namespace, newest-first), recent jobs,   ║
║  and a commit list relative to the base branch — in one call.        ║
║                                                                      ║
║  Keep using `git log` if you specifically need a commit SHA or the   ║
║  commit author of a particular change — that's what it's for. But    ║
║  if the goal is "what's been happening on this branch", stop and     ║
║  call memory_branch_context instead.                                 ║
║                                                                      ║
╚══════════════════════════════════════════════════════════════════════╝

EOF
fi

exit 0
