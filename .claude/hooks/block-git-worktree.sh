#!/usr/bin/env bash
# PreToolUse — Bash: block `git worktree add` in a FlowTree job.
# The harness stages, commits and pushes only the job's own working tree, so
# work done in another worktree is lost while the job reports success. An
# interactive session may create worktrees; the policy applies only when
# lib/agent_session.py identifies the session as a job. Read-only inspection
# (`git worktree list`) is always allowed. The other routes into a worktree
# (EnterWorktree, isolated subagents, edits and commands there) are covered by
# block-job-worktree.sh.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/git_command_check.py — the single source of
# truth for this policy across both Claude Code (this script) and
# opencode (.opencode/plugins/block-git-worktree.ts).
#
# Behavior preserved bit-for-bit vs. the previous inline-grep version:
#   - exit 2 with "BLOCKED: 'git worktree add' is not permitted in a FlowTree job..."
#     on stderr on a `git worktree add ...` invocation in a job session
#   - exit 0 (no output) on other worktree subcommands
#
# Why exec: the wrapper has no business doing anything but forwarding
# the harness's stdin JSON to the core. exec replaces the shell so
# there is no extra process layer between the harness and Python.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/git_command_check.py" --stdin block-git-worktree
