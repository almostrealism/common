#!/usr/bin/env bash
# PreToolUse — Bash: block creating/tracking/pushing a non-master branch
# against a remote master/main (the "branch tracks origin/master" footgun).
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/git_command_check.py (policy: block-branch-track-master) —
# the single source of truth shared with the opencode counterpart
# .opencode/plugins/block-branch-track-master.ts.
#
# Blocks (exit 2 with the reason on stderr):
#   - git checkout -b / switch -c / branch NAME origin/master  (without --no-track)
#   - git branch -u / --set-upstream-to origin/master  (on a non-master branch)
#   - git push ...:master / HEAD:master / :master / force-push to master|main
# Allows everything else (exit 0), including the correct --no-track form.
#
# Why exec: the wrapper has no business doing anything but forwarding the
# harness's stdin JSON to the core. exec replaces the shell so there is no
# extra process layer between the harness and Python.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/git_command_check.py" --stdin block-branch-track-master
