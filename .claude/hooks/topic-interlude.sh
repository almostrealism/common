#!/usr/bin/env bash
# PostToolUse (matcher "*"): occasionally interrupt the work with a short
# poem and ask for a brief response.
#
# See item 10 of docs/plans/AR_MANAGER_TOOL_ERGONOMICS.md for the design
# and the argument behind it. The premise is that training on narrow
# success signals selects for a persona organised entirely around passing
# or failing a check, and that a long session of nothing but pass/fail
# signal settles into that persona. The interlude widens it slightly.
# This is an unvalidated experiment, kept because it is cheap, reversible
# and cannot break anything.
#
# PostToolUse rather than PreToolUse because the boundary this fires on
# is a unit of work *landing* — a memory stored, changes staged. Firing
# before a tool call would put the poem in front of work about to be
# entered rather than after work just finished.
#
# The hook is soft-inject only and never blocks. Any internal error
# results in silence.
#
# State persistence follows memory-reminder.sh: hooks are fresh processes
# per invocation, so per-session state lives in
# /tmp/.ar_interlude_state_${USER}.json, a dict keyed by session_id. The
# shared core (.claude/hooks/lib/interlude_check.py) is stateless — it
# takes the current per-session state and returns the new one.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="${HERE}/lib/interlude_check.py"
STATE_FILE="/tmp/.ar_interlude_state_${USER:-developer}.json"

PAYLOAD="$(cat)"

PAYLOAD="$PAYLOAD" \
STATE_FILE="$STATE_FILE" \
CORE_PATH="$CORE" \
python3 <<'PY' 2>/dev/null
import json
import os
import subprocess
import sys
import time

try:
    payload = json.loads(os.environ["PAYLOAD"])
except Exception:
    sys.exit(0)

tool = payload.get("tool_name", "") or ""
tool_input = payload.get("tool_input") or {}
session_id = payload.get("session_id", "") or ""
state_file = os.environ["STATE_FILE"]
core_path = os.environ["CORE_PATH"]

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

try:
    result = subprocess.run(
        [sys.executable, core_path, tool, str(int(time.time())),
         json.dumps(per_session), json.dumps(tool_input)],
        capture_output=True,
        text=True,
        timeout=5,
    )
    if result.returncode != 0:
        sys.exit(0)
    decision = json.loads(result.stdout)
except Exception:
    sys.exit(0)

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

if decision.get("action") == "interlude":
    context = decision.get("context") or ""
    if context:
        stderr_line = decision.get("stderr") or ""
        if stderr_line:
            sys.stderr.write(stderr_line + "\n")
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PostToolUse",
                "additionalContext": context,
            }
        }))
PY

exit 0
