#!/usr/bin/env bash
# PreToolUse — Bash: block a genuine direct `mvn test` invocation.
#
# Agents must use mcp__ar-test-runner__start_test_run to run tests.
# `mvn install -DskipTests`, `mvn compile`, `mvn clean install`, `mvn
# test-compile`, etc. are allowed.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/mvn_test_check.py — the single source of
# truth for this policy across both Claude Code (this script) and
# opencode (.opencode/plugins/block-mvn-test-direct.ts).
#
# Behavior preserved bit-for-bit vs. the previous inline-Python version:
#   - exit 2 with the same multi-line BLOCKED message on stderr on a
#     genuine `mvn test` (including `bash -c "mvn test"` recursion)
#   - exit 0 with the same "use the MCP test runner" recommendation
#     to stderr AND a JSON hookSpecificOutput.additionalContext to
#     stdout for commands the tokenizer cannot parse but which
#     contain both `mvn` and `test` (the "uncertain" path)
#   - exit 0 (no output) for clear allows
#
# Why exec: the wrapper has no business doing anything but forwarding
# the harness's stdin JSON to the core. exec replaces the shell so
# there is no extra process layer between the harness and Python.
#
# See docs/plans/OPENCODE_HOOKS.md for the architecture.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/mvn_test_check.py" --stdin
