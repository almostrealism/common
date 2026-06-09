#!/usr/bin/env python3
"""Decide whether to remind the agent to call `memory_store` (or the
`ar-consultant` analogue `remember`).

This module is the single source of truth for the "memory reminder
This module is the single source of truth for the "memory reminder
nudge" policy. It is invoked by:

  - .claude/hooks/memory-reminder.sh       (Claude Code, argv)
  - .opencode/plugins/memory-reminder.ts   (opencode, argv)
The decision logic is purely a function of the *current* state and the
new tool event. The core is **stateless** — it does not read or write
the on-disk / in-memory state store itself. Each adapter persists the
returned `new_state` in its harness-appropriate way (on-disk JSON
file for Claude Code, module-level Map for opencode). The shape of
`state` is the same for both adapters; only the persistence layer
differs.

Two CLI entry points:

  python3 memory_reminder_check.py <tool_name> <now_ts> [state_json]
      Used by the .ts adapter. Returns the Decision as a JSON
      object on stdout, exit 0 always. The .ts adapter does the
      harness-native rendering (mutate output.output on warn).

  python3 memory_reminder_check.py --stdin
      Alternate entry point (used by unit tests and available for
      adapters that want to provide tool/session/state via stdin).
      Reads a JSON payload from stdin, computes the Decision, and
      renders natively (exit 0 + JSON additionalContext on warn; exit 0
      silent on allow).

The Decision shape (always JSON, no trailing newline required):

    {
      "action":    "allow" | "warn",
      "reason":    "str",   # shown to the model on block (unused here; "allow" never has a reason)
      "context":   "str",   # injected into the model's next turn on warn
      "stderr":    "str",   # printed to stderr for the human (advisory)
      "new_state": {...}    # the state the adapter must persist for the next call
    }

Design summary (see docs/plans/MEMORY_REMINDER_HOOK.md for the full
design):

  - Trigger: hybrid of side-effect-call count and wall-clock time,
    whichever first crosses its threshold.
  - "Side-effect" = any tool that is not in the read-only set.
  - Reset: exact match on `mcp__ar-manager__memory_store` or
    `mcp__ar-consultant__remember`. Reads (`memory_recall`,
    `consult`, `recall`, etc.) do NOT reset.
  - Force: soft-inject only. Backoff so we don't nag.
  - Failsafe: any internal error returns `action: "allow"` with
    `new_state: null`. A hook malfunction must never block
    legitimate work.
"""
import json
import os
import sys
import time


# Tools that do not count as "side-effect" calls. The default for any
# tool not in this set is "side effect" — meaning new write/edit/MCP
# tools are counted by default, while only reads opt out. This is
# intentionally inverted: missing a read in the list is harmless
# (threshold is generous; reads don't drive substantive work), while
# missing a write would let the agent do real work without
# checkpointing.
READ_ONLY_TOOL_NAMES = frozenset({
    # Claude Code built-in read-only tools.
    "Read",
    "Glob",
    "Grep",
    "LS",
    "WebFetch",
    "WebSearch",
    "TaskOutput",
    # opencode built-in read-only tools.
    "read",
    "glob",
    "grep",
    "list",
    "webfetch",
    "websearch",
    # ar-consultant: every read tool.
    "mcp__ar-consultant__consult",
    "mcp__ar-consultant__recall",
    "mcp__ar-consultant__consultant_status",
    "mcp__ar-consultant__recall_namespaces",
    "mcp__ar-consultant__list_request_history",
    "mcp__ar-consultant__export_request_history",
})

READ_ONLY_TOOL_PREFIXES = (
    # ar-docs: all tools are reads.
    "mcp__ar-docs__",
    # ar-manager: read-only tool name patterns.
    "mcp__ar-manager__memory_recall",
    "mcp__ar-manager__workstream_list",
    "mcp__ar-manager__workstream_get_",
    "mcp__ar-manager__workstream_context",
    "mcp__ar-manager__github_list_open_prs",
    "mcp__ar-manager__github_pr_find",
    "mcp__ar-manager__github_pr_check_status",
    "mcp__ar-manager__github_pr_review_comments",
    "mcp__ar-manager__github_pr_conversation",
    "mcp__ar-manager__github_read_file",
    "mcp__ar-manager__tracker_get_",
    "mcp__ar-manager__tracker_list_",
    "mcp__ar-manager__tracker_search_tasks",
    "mcp__ar-manager__tracker_project_summary",
    "mcp__ar-manager__workspace_secret_list_",
    "mcp__ar-manager__controller_",
    # ar-build-validator: read tools.
    "mcp__ar-build-validator__list_",
    "mcp__ar-build-validator__get_",
    # ar-test-runner: read tools (the start_ tool is a side effect).
    "mcp__ar-test-runner__get_",
    # ar-jmx: read tools.
    "mcp__ar-jmx__get_",
    "mcp__ar-jmx__attach_",
    "mcp__ar-jmx__diff_",
    "mcp__ar-jmx__analyze_",
    # ar-profile-analyzer: read tools.
    "mcp__ar-profile-analyzer__list_",
    "mcp__ar-profile-analyzer__load_",
    "mcp__ar-profile-analyzer__find_",
    "mcp__ar-profile-analyzer__search_",
    "mcp__ar-profile-analyzer__get_",
)

# Tools that RESET the counter (the agent just stored a memory).
STORE_TOOL_NAMES = frozenset({
    "mcp__ar-manager__memory_store",
    "mcp__ar-consultant__remember",
})


# Reminder text. Kept as one short paragraph — a multi-line box would
# be overkill for a soft-inject nudge and would consume more of the
# model's context window on each fire.
REMINDER_TEXT = (
    "[ar-hooks/memory-reminder] It's been a while since you stored a "
    "memory. Cross-session continuity depends on durable memories, not "
    "transcripts. If you've made a non-obvious decision, hit a dead end, "
    "learned a project-specific gotcha, or changed direction, store it "
    "now via `mcp__ar-manager__memory_store` (or "
    "`mcp__ar-consultant__remember` for personal notes). This is a soft "
    "nudge; the threshold is tuned to fire on long dry spells, not to "
    "nag. See docs/plans/MEMORY_REMINDER_HOOK.md for the design."
)


def _env_int(name, default):
    """Read an env var as int, falling back to default on any parse error.

    A bad value should never crash the hook; it just means we run with
    the default. The failsafe contract is that a hook malfunction
    returns "allow" — env-var parsing errors are no exception.
    """
    raw = os.environ.get(name, "")
    if not raw:
        return default
    try:
        return int(raw)
    except (TypeError, ValueError):
        return default


def _env_disabled():
    """True if the hook is disabled via env var."""
    raw = os.environ.get("AR_MEMORY_REMIND_DISABLED", "").strip().lower()
    return raw in ("1", "true", "yes", "on")


def _thresholds():
    """Read the current threshold values from env vars.

    Defaults are deliberately skewed to "fires later rather than
    sooner." The cost of a missed nudge is "agent loses some
    recoverable context"; the cost of an over-nudge is "agent learns
    to ignore the hook." The asymmetry favors the latter.
    """
    return {
        "calls_threshold": _env_int("AR_MEMORY_REMIND_CALLS_THRESHOLD", 15),
        "seconds_threshold": _env_int("AR_MEMORY_REMIND_SECONDS_THRESHOLD", 1200),
        "backoff_calls": _env_int("AR_MEMORY_REMIND_BACKOFF_CALLS", 8),
        "backoff_seconds": _env_int("AR_MEMORY_REMIND_BACKOFF_SECONDS", 600),
        "warmup_calls": _env_int("AR_MEMORY_REMIND_WARMUP_CALLS", 5),
        "cooldown_calls": _env_int("AR_MEMORY_REMIND_COOLDOWN_CALLS", 3),
    }


def _is_read_only(tool):
    """True if the tool is a read-only lookup that should not count as
    a side-effect call.

    The default for unknown tools is "side effect" (returns False).
    This is intentional: missing a read in the list is harmless
    (reads don't drive substantive work, and the threshold is
    generous), while missing a write would let the agent do real
    work without checkpointing.
    """
    if not tool:
        return True
    if tool in READ_ONLY_TOOL_NAMES:
        return True
    return any(tool.startswith(p) for p in READ_ONLY_TOOL_PREFIXES)


def _is_store(tool):
    """True if the tool is a memory-store (resets the counter)."""
    return tool in STORE_TOOL_NAMES


def _default_state():
    """Fresh state for a new session / first call."""
    return {
        "calls_since_last_store": 0,
        "last_store_ts": 0,
        "last_remind_ts": 0,
        "calls_at_last_remind": 0,
        "session_start_ts": 0,
    }


def _normalize_state(state):
    """Backfill any missing keys with defaults. Defensive against
    a corrupted state file or a partial state from a prior version
    of the core. Never raises.
    """
    base = _default_state()
    if not isinstance(state, dict):
        return base
    for key in base:
        if key in state and isinstance(state[key], int):
            base[key] = state[key]
    return base


def decide(tool, now_ts, state):
    """Return a Decision dict for the (tool, now_ts, state) tuple.

    The result is harness-neutral; each adapter renders it in its own
    native way (exit code + stdout JSON for Claude Code; mutate
    output.output for opencode). The returned `new_state` is the
    state the adapter must persist before the next call.
    """
    if _env_disabled():
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": "",
            "new_state": _normalize_state(state),
        }

    th = _thresholds()
    s = _normalize_state(state)

    # First-ever call for this session: stamp session_start_ts.
    if s["session_start_ts"] == 0:
        s["session_start_ts"] = int(now_ts) if now_ts else int(time.time())

    # Store path: reset the counter; the store itself does not count
    # toward the side-effect call count.
    if _is_store(tool):
        s["calls_since_last_store"] = 0
        s["last_store_ts"] = int(now_ts) if now_ts else int(time.time())
        s["last_remind_ts"] = 0
        s["calls_at_last_remind"] = 0
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": "",
            "new_state": s,
        }

    # Read-only path: count neither as side effect nor as a reset.
    # Just return current state unchanged so the adapter persists it
    # (cheap, but keeps the on-disk / in-memory snapshot fresh and
    # the round-trip predictable).
    if _is_read_only(tool):
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": "",
            "new_state": s,
        }

    # Side-effect call: increment the counter, then decide.
    s["calls_since_last_store"] = s["calls_since_last_store"] + 1
    now = int(now_ts) if now_ts else int(time.time())

    # Warmup: don't fire in the first N side-effect calls of the
    # session. The first burst is almost always exploration; we want
    # the reminder to hit mid-session, not "you haven't done this in
    # the first 30 seconds." The comparison is `<=` so that exactly
    # N calls are silent and the (N+1)th call can fire.
    if s["last_store_ts"] == 0 and s["calls_since_last_store"] <= th["warmup_calls"]:
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": "",
            "new_state": s,
        }

    # Cooldown: right after a store, silence the hook for the next
    # COOLDOWN_CALLS side-effect calls. The agent just remembered;
    # give it room to act on the new context before asking it to
    # remember again. The comparison is `<=` so that exactly
    # N post-store calls are silent and the (N+1)th call can fire.
    if s["last_store_ts"] > 0 and s["calls_since_last_store"] <= th["cooldown_calls"]:
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": "",
            "new_state": s,
        }

    # Compute time-since-last-store against the later of
    # (last_store_ts, session_start_ts). This makes the time floor
    # meaningful even when the agent has never stored anything: the
    # floor measures "time since the session began" instead of
    # "time since unix epoch 0", which would always trip.
    store_anchor = s["last_store_ts"] if s["last_store_ts"] > 0 else s["session_start_ts"]
    sec_since_last_store = now - store_anchor

    # Trigger check: count OR time, whichever first.
    count_trigger = s["calls_since_last_store"] >= th["calls_threshold"]
    time_trigger = sec_since_last_store >= th["seconds_threshold"]

    if not (count_trigger or time_trigger):
        return {
            "action": "allow",
            "reason": "",
            "context": "",
            "stderr": "",
            "new_state": s,
        }

    # Backoff: after a reminder, don't re-fire on the immediate next
    # call. Re-arm only when EITHER another BACKOFF_CALLS additional
    # side-effect calls accumulate OR BACKOFF_SECONDS wall-clock
    # minutes pass without a store. This prevents nagging.
    if s["last_remind_ts"] > 0:
        sec_since_remind = now - s["last_remind_ts"]
        calls_since_remind = s["calls_since_last_store"] - s["calls_at_last_remind"]
        in_count_backoff = calls_since_remind < th["backoff_calls"]
        in_time_backoff = sec_since_remind < th["backoff_seconds"]
        if in_count_backoff and in_time_backoff:
            return {
                "action": "allow",
                "reason": "",
                "context": "",
                "stderr": "",
                "new_state": s,
            }

    # Fire.
    s["last_remind_ts"] = now
    s["calls_at_last_remind"] = s["calls_since_last_store"]
    return {
        "action": "warn",
        "reason": "",
        "context": REMINDER_TEXT,
        "stderr": REMINDER_TEXT,
        "new_state": s,
    }


def _read_stdin_payload():
    """Read a Claude-Code-style hook payload from stdin and return
    (tool, now_ts, state). On any parse error, returns ("", 0, None)
    so the caller can decide to fail safely.
    """
    try:
        raw = sys.stdin.read()
        payload = json.loads(raw)
    except Exception:
        return ("", 0, None)

    tool = (payload.get("tool_name", "") or "").strip()
    state = payload.get("state", None)
    now_ts = payload.get("now_ts", None)
    if now_ts is None:
        now_ts = int(time.time())
    return (tool, int(now_ts), state)


def _render_harness_native(decision):
    """Render a Decision in the Claude Code hook contract (exit code + stdout JSON)."""
    action = decision.get("action")
    context = decision.get("context", "") or ""
    stderr_msg = decision.get("stderr", "") or ""

    if action == "warn":
        if stderr_msg:
            sys.stderr.write(stderr_msg + "\n")
        # Print the new_state to stdout on its own line, then the
        # hookSpecificOutput JSON. The .sh adapter reads the new_state
        # off stdout to persist it to disk. The harness parses the
        # JSON line as additionalContext. Splitting with a delimiter
        # keeps the two channels well-defined.
        new_state = decision.get("new_state", {})
        print(json.dumps({"_state": new_state}))
        if context:
            print(json.dumps({
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "additionalContext": context,
                }
            }))
        sys.exit(0)

    # allow: emit the new_state line (for the .sh adapter to persist)
    # and nothing else. No stderr noise, no stdout additionalContext.
    new_state = decision.get("new_state", {})
    if new_state is not None:
        print(json.dumps({"_state": new_state}))
    sys.exit(0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)

    if argv and argv[0] == "--stdin":
        tool, now_ts, state = _read_stdin_payload()
        decision = decide(tool, now_ts, state)
        _render_harness_native(decision)
        return

    if len(argv) < 2:
        sys.stderr.write("usage: memory_reminder_check.py <tool> <now_ts> [state_json] | --stdin\n")
        sys.exit(2)

    tool = argv[0]
    try:
        now_ts = int(argv[1])
    except (TypeError, ValueError):
        now_ts = int(time.time())

    state = None
    if len(argv) >= 3 and argv[2]:
        try:
            state = json.loads(argv[2])
        except Exception:
            state = None

    print(json.dumps(decide(tool, now_ts, state)))
    sys.exit(0)


if __name__ == "__main__":
    main()
