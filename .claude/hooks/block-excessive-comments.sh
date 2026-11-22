#!/usr/bin/env bash
# PreToolUse - Write/Edit/MultiEdit/NotebookEdit: block an edit that leaves a long
# inline comment in a source file.
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/inline_comment_check.py - the single source of truth for this
# policy. It judges the file as the edit will leave it, so text added to a comment
# that is already there is measured with it. The same module is called by
# check-source-writes.sh (source files a Bash command changed), by the ar-commit
# server (the staged change), by the opencode plugin, and by the checkstyle job in
# CI, which is the gate that holds whatever wrote the change.
#
# Exit 0  -> allow
# Exit 2  -> BLOCK (reason on stderr is shown to the model)
#
# Anything other than a clean allow blocks. A check that crashed, or an interpreter
# that is missing, has not decided the edit is acceptable.
set -uo pipefail

python3 "$(cd "$(dirname "$0")" && pwd)/lib/inline_comment_check.py" --stdin
status=$?

if [ "$status" -eq 0 ]; then
    exit 0
fi

if [ "$status" -ne 2 ]; then
    echo "BLOCKED: the inline-comment check did not run to a verdict (exit $status); an edit it could not judge is not allowed through." >&2
fi

exit 2
