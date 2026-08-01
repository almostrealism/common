#!/usr/bin/env bash
# PreToolUse — Bash: block `git worktree add`.
# All agent changes must live in the single working tree the developer can see
# directly. Creating an additional worktree stores work out of the developer's
# line of sight, which is not permitted. Read-only inspection
# (`git worktree list`) is allowed.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/git_command_check.py — the single source of
# truth for this policy across both Claude Code (this script) and
# opencode (.opencode/plugins/block-git-worktree.ts).
#
# Behavior preserved bit-for-bit vs. the previous inline-grep version:
#   - exit 2 with "BLOCKED: 'git worktree add' is not permitted for agents..."
#     on stderr on a `git worktree add ...` invocation
#   - exit 0 (no output) on other worktree subcommands
#
# Why exec: the wrapper has no business doing anything but forwarding
# the harness's stdin JSON to the core. exec replaces the shell so
# there is no extra process layer between the harness and Python.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/git_command_check.py" --stdin block-git-worktree
