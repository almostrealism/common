#!/usr/bin/env bash
# PreToolUse — Write/Edit/MultiEdit/NotebookEdit/Bash: block any attempt
# to modify a checkstyle configuration file.
#
# Agents must not weaken, exempt, suppress, or relax checkstyle rules
# to make a task pass.  This is a structural block: the check is
# enforced in the harness before the file edit (or shell command)
# takes effect, so the cheat can't be undone by the agent.
#
# This is a thin shell wrapper.  The decision logic lives in
# .claude/hooks/lib/checkstyle_config_check.py — the single source
# of truth for this policy across both Claude Code (this script)
# and opencode (.opencode/plugins/block-checkstyle-edit.ts).
#
# Exit 0  → allow
# Exit 2  → BLOCK (the forceful ALL-CAPS reason is on stderr, shown
#           to the model as the block reason)
#
# The block message is deliberately written in ALL CAPS and lists
# the abandon-before-tamper principle the project requires: if a
# task cannot be completed without modifying checkstyle, the agent
# MUST abandon the task and declare it impossible.  Declaring
# failure is always preferable to tampering with enforcement.
#
# See docs/plans/OPENCODE_HOOKS.md for the architecture.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/checkstyle_config_check.py" --stdin
