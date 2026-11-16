#!/usr/bin/env bash
# PreToolUse — every tool: refuse all work while the current non-master branch
# tracks a remote master/main, until the session fixes the upstream.
#
# block-branch-track-master.sh refuses the commands that would create that
# state; this hook catches the state itself, however it arose (a command run
# outside the session, a script, an old branch). Only the remedies
# (`git branch --unset-upstream`, `git push -u origin <branch>`, ...) and
# read-only git inspection are let through while it holds.
#
# Thin wrapper: the decision lives in .claude/hooks/lib/branch_upstream_check.py,
# shared with .opencode/plugins/require-own-upstream.ts.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/branch_upstream_check.py" --stdin
