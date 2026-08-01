#!/usr/bin/env bash
# PreToolUse (matcher "*"): nudge the agent to
# call memory_store (or consultant remember) when a long stretch of
# work has happened without one.
#
# The agent goes 30 tool calls deep, fixes a bug, runs a test, hits
# another bug, fixes that — and never once calls memory_store. When
# the session ends, the only durable record is the diff and the
# transcript. The cross-session narrative (why this path was
# abandoned, what the consultant said that steered the design,
# which hypothesis was ruled out) is lost.
#
# This hook fires on EVERY tool call (no tool_name matcher) and
# reminds the agent to store a memory when a threshold is crossed.
# The threshold is hybrid: a count of side-effect tool calls OR a
# wall-clock time floor, whichever first. See
# docs/plans/MEMORY_REMINDER_HOOK.md for the design.
#
# The hook is **soft-inject only** (never blocks). It emits a JSON
# hookSpecificOutput.additionalContext for the model's next turn
# when the threshold is crossed.
#
# State persistence: Claude Code hooks are fresh processes per
# invocation, so we keep per-session state in
# /tmp/.ar_memory_state_${USER}.json. The file is a dict keyed by
# session_id. The shared core
# (.claude/hooks/lib/memory_reminder_check.py) is stateless — it
# takes the current per-session state as input and returns the new
# state. The Python heredoc below handles the read/write/parse of
# the state file and shells out to the core.
#
# Why this is a thin shell wrapper (not a Python script): the
# harness invokes it as `command: <path>`, and Claude Code's
# matcher routing is configured in .claude/settings.json. Keeping
# the adapter as a `.sh` (rather than `.py`) matches every other
# PreToolUse / PostToolUse hook in this repo and avoids a special
# shebang consideration in the harness.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="${HERE}/lib/memory_reminder_check.py"
STATE_FILE="/tmp/.ar_memory_state_${USER:-developer}.json"

PAYLOAD="$(cat)"

# A single Python heredoc does all the work: parse the harness
# payload, read+write the state file, call the core, and emit the
# additionalContext JSON (or nothing) on stdout. This keeps the
# shell wrapper thin and pushes the JSON / state-file I/O into one
# place that's easy to read and audit.
#
# stdout contract for Claude Code:
#   - empty (no injection) on action=="allow"
#   - one JSON object with hookSpecificOutput.additionalContext on
#     action=="warn"
#
# Failure mode: any exception is swallowed and the wrapper exits 0
# silently. A hook malfunction must never block legitimate work.
PAYLOAD="$PAYLOAD" \
STATE_FILE="$STATE_FILE" \
CORE_PATH="$CORE" \
python3 <<'PY' 2>/dev/null
import json
import os
import subprocess
import sys

try:
    payload = json.loads(os.environ["PAYLOAD"])
except Exception:
    sys.exit(0)

tool = payload.get("tool_name", "") or ""
session_id = payload.get("session_id", "") or ""
state_file = os.environ["STATE_FILE"]
core_path = os.environ["CORE_PATH"]

# Read the per-session state from the state file. The file is a
# dict keyed by session_id; each value is the per-session state
# object produced by the core. A missing or malformed file is
# treated as an empty dict.
state = {}
try:
    if os.path.exists(state_file):
        with open(state_file, "r", encoding="utf-8") as f:
            state = json.load(f)
        if not isinstance(state, dict):
            state = {}
except Exception:
    state = {}

per_session = state.get(session_id, {}) if session_id else {}

# Call the core. The argv mode returns the Decision JSON on
# stdout, exit 0 always. A nonzero exit is a malfunction; fall
# through to "allow" silently.
try:
    result = subprocess.run(
        [sys.executable, core_path, tool, str(int(__import__("time").time())), json.dumps(per_session)],
        capture_output=True,
        text=True,
        timeout=5,
    )
    if result.returncode != 0:
        sys.exit(0)
    decision = json.loads(result.stdout)
except Exception:
    sys.exit(0)

# Persist the new state. Skip if there's no session_id (we don't
# know where in the state file to put the entry).
new_state = decision.get("new_state")
if new_state is not None and session_id:
    state[session_id] = new_state
    try:
        tmp = state_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f)
        os.replace(tmp, state_file)
    except Exception:
        pass

# Emit the additionalContext JSON for the harness. Empty stdout
# means "no injection" (the harness contract).
if decision.get("action") == "warn":
    context = decision.get("context") or ""
    if context:
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "additionalContext": context,
            }
        }))
PY

exit 0
