#!/usr/bin/env bash
# PreToolUse — Bash: block git commit.
# Agents stage with git add; humans commit.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/git_command_check.py — the single source of
# truth for this policy across both Claude Code (this script) and
# opencode (.opencode/plugins/block-git-commit.ts).
#
# Behavior preserved bit-for-bit vs. the previous inline-grep version:
#   - exit 2 with "BLOCKED: git commit is not permitted for agents..."
#     on stderr on a `git commit ...` invocation
#   - exit 0 (no output) on anything else
#
# Why exec: the wrapper has no business doing anything but forwarding
# the harness's stdin JSON to the core. exec replaces the shell so
# there is no extra process layer between the harness and Python.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/git_command_check.py" --stdin block-git-commit
