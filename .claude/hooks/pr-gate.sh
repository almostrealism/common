#!/usr/bin/env bash
# Pull request gate: makes the agent list a repository's open pull requests
# before opening another one, and confirm a second pull request deliberately,
# because every pull request costs a whole CI run on the shared runners.
#
#   pr-gate.sh record   PostToolUse on github_list_open_prs
#   pr-gate.sh gate     PreToolUse on github_create_pr
#   pr-gate.sh bash     PreToolUse on Bash (denies gh pr create)
#
# The logic and its tests are in .claude/hooks/lib/pr_gate_check.py.
HERE="$(cd "$(dirname "$0")" && pwd)"
exec python3 "${HERE}/lib/pr_gate_check.py" "$1"
