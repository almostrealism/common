#!/usr/bin/env bash
# PreToolUse — EnterWorktree / Agent / Task / Edit / Write / MultiEdit /
# NotebookEdit / Bash: in a FlowTree job, keep all work in the job's own
# working tree. The harness captures nothing else, so work done in another git
# worktree is lost while the job reports success. Interactive sessions are not
# affected and may use worktrees freely.
#
# The decision logic lives in .claude/hooks/lib/job_worktree_check.py; job
# detection is shared with block-git-worktree through lib/agent_session.py.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/job_worktree_check.py" --stdin
