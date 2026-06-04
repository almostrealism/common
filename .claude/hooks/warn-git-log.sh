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
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/git_command_check.py — the single source of
# truth for this policy across both Claude Code (this script) and
# opencode (.opencode/plugins/warn-git-log.ts).
#
# Behavior preserved bit-for-bit vs. the previous inline-grep version:
#   - exit 0 with the same banner text on stderr on a `git log` invocation
#   - exit 0 (no output) on anything else
#
# Why exec: the wrapper has no business doing anything but forwarding
# the harness's stdin JSON to the core. exec replaces the shell so
# there is no extra process layer between the harness and Python.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/git_command_check.py" --stdin warn-git-log
