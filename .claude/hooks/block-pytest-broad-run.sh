#!/usr/bin/env bash
# PreToolUse — Bash: block a pytest invocation that is not a bounded test
# selection (no path, a directory, or more files/node ids than the caps).
#
# Thin wrapper; decision logic lives in
# .claude/hooks/lib/pytest_broad_run_check.py — mirrors
# block-mvn-test-direct.sh / mvn_test_check.py.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/pytest_broad_run_check.py" --stdin
