#!/usr/bin/env bash
# PreToolUse - Write/Edit/MultiEdit: block a file written with the edit tools to the
# session scratchpad or the system temporary directory.
#
# The person an agent works for usually runs as a different account and cannot read
# those places, so a script, report or test written there has been delivered to
# nobody. The decision logic lives in .claude/hooks/lib/scratch_write_check.py. A
# throwaway intermediate produced by a shell command is not affected.
#
# Exit 0  -> allow
# Exit 2  -> BLOCK (reason on stderr is shown to the model)
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/scratch_write_check.py" --stdin
