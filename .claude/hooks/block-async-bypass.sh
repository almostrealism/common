#!/usr/bin/env bash
# PreToolUse - Write/Edit/MultiEdit: block caller-side workarounds for the
# asynchronous-submission contract (a backend query asked of a Semaphore, a second
# submit method, a Semaphore type check or an in-flight cap in calling code, a wait
# followed by submit(null)).
#
# This is a thin shell wrapper. The decision logic lives in
# .claude/hooks/lib/async_bypass_check.py, shared with check-source-writes.sh,
# which replays it for source files a Bash command modified.
# See docs/internals/asynchronous-submission.md.
#
# Exit 0  -> allow (a warning, if any, is printed as additional context)
# Exit 2  -> BLOCK (reason on stderr is shown to the model)
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/lib/async_bypass_check.py" --stdin
