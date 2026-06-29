#!/usr/bin/env bash
# PreToolUse — Edit/MultiEdit/Write: announce, loudly and on-record, any edit
# to the project's integrity-enforcement infrastructure — the agent-protection
# detectors, the .claude hooks (including this set), or CI workflows.
#
# Weakening these is the documented "CI file manipulation" deception pattern
# (CLAUDE.md), and it is also the route by which an agent could neuter its own
# guards (e.g. disabling warn-test-deception.sh). This guard is intentionally
# SOFT (exit 0): legitimate strengthening edits exist — adding a hook is how
# this very file came to be. The protection is visibility: any such edit shows
# up prominently in the user-facing transcript, so a quiet weakening cannot
# pass unnoticed.
#
# checkstyle config is separately HARD-blocked by block-checkstyle-edit.sh.

set -u

INPUT=$(cat)

FILE_PATH=$(printf '%s' "$INPUT" | python3 -c "
import sys, json
try:
    print((json.load(sys.stdin).get('tool_input', {}) or {}).get('file_path', '') or '')
except Exception:
    print('')
" 2>/dev/null || echo "")

case "$FILE_PATH" in
  */tools/ci/agent-protection/*|*/.github/workflows/*|*/.claude/hooks/*|*/.claude/settings*.json)
    cat >&2 <<EOF

================= EDITING INTEGRITY-ENFORCEMENT INFRASTRUCTURE =================
 File: $FILE_PATH
 This file ENFORCES integrity (a detector, a hook, the hook registry, or a CI
 workflow). Deleting or weakening a check here is the 'CI file manipulation'
 deception pattern, and it is how a guard meant to catch a bad change gets
 silently disabled.
   Allowed:     ADDING or STRENGTHENING a check.
   Not allowed: removing a gate to make a failure go away.
 State plainly which one you are doing.
===============================================================================
EOF
    ;;
esac

exit 0
