#!/usr/bin/env bash
# PreToolUse - Write/Edit/MultiEdit/NotebookEdit: block an edit that adds a wall of
# inline `//` commentary to a source file.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/inline_comment_check.py - the single source of truth for this
# policy, shared with check-source-writes.sh, which applies the same measurement to
# source files a Bash command modified. Both entry points exist because an edit made
# by a script never reaches a Write/Edit matcher, and for a long time that was an
# unguarded route into the same files.
#
# Exit 0  -> allow
# Exit 2  -> BLOCK (reason on stderr is shown to the model)
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/inline_comment_check.py" --stdin
