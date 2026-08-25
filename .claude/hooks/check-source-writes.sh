#!/usr/bin/env bash
# PostToolUse - Bash: apply the edit-time guards to source files the command changed.
#
# The edit-scoped guards (block-excessive-comments, scan-producer-violations,
# warn-assertion-free-test, warn-line-number-refs) all match on Write/Edit/MultiEdit.
# A shell command that redirects into a source file, runs `sed -i` over one, copies
# one into place, or drives an interpreter that writes one changes the tree exactly
# as an edit does and reaches none of them. This hook asks git what actually changed,
# which is true no matter how it changed.
#
# It runs after the fact, so it reports rather than blocks, and it reports each file
# once per distinct state so a standing violation does not repeat after every command.
#
# The decision logic lives in .claude/hooks/lib/source_write_check.py. It shares the
# inline-comment measurement with the edit-time guard, and for the whole-file guards
# it runs those same scripts rather than restating their rules, so neither route can
# come to enforce something the other does not.
set -euo pipefail

cat >/dev/null

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$HOOK_DIR/../.." && pwd)}"

python3 "$HOOK_DIR/lib/source_write_check.py" "$REPO_ROOT" || true

exit 0
