#!/usr/bin/env bash
# PreToolUse — Bash: block `git worktree add`.
# All agent changes must live in the single working tree the developer can see
# directly. Creating an additional worktree stores work out of the developer's
# line of sight, which is not permitted. Read-only inspection
# (`git worktree list`) is allowed.
set -euo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('command', ''))
" 2>/dev/null || echo "")

# Match the `worktree ... add` subcommand regardless of what precedes `worktree`
# (so `git -C <path> worktree add` and `git worktree --flag add` are both caught).
# Other subcommands (list, remove, prune, move, lock, unlock, repair) pass through.
if echo "$COMMAND" | grep -qE 'worktree([[:space:]]+-[^[:space:]]+)*[[:space:]]+add([[:space:]]|$)'; then
    echo "BLOCKED: 'git worktree add' is not permitted for agents." >&2
    echo "All changes must remain in the single working tree the developer can see directly." >&2
    echo "Do not create separate worktrees or otherwise store work out of sight." >&2
    echo "Read-only 'git worktree list' is allowed. See CLAUDE.md." >&2
    exit 2
fi

exit 0
