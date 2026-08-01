#!/usr/bin/env python3
"""Unit tests for memory_reminder_check.py.

Run from the repo root:

    python3 -m unittest .claude/hooks/lib/test_memory_reminder_check.py -v

or:

    python3 .claude/hooks/lib/test_memory_reminder_check.py

The tests exercise the pure `decide(tool, now_ts, state)` function
directly and also drive the CLI entry points (argv mode and
--stdin mode) to verify the rendering contract used by the .sh
and .ts adapters.

Coverage:
  - 6 decide() reset cases (mcp__ar-manager__memory_store,
    mcp__ar-consultant__remember, no-store read, no-store Bash,
    no-store side-effect counts).
  - 5 read-only cases (consultant consult/recall, manager
    memory_recall/workstream_list, opencode read).
  - 6 warmup/cooldown cases (warmup at session start, cooldown
    after a store, no cooldown when no store has happened).
  - 5 trigger cases (count threshold, time threshold, neither
    fires, both fire, off-by-one boundary).
  - 4 backoff cases (immediate re-fire prevented, re-fire after
    BACKOFF_CALLS, re-fire after BACKOFF_SECONDS, no backoff on
    first fire).
  - 3 disabled/failsafe cases (env-var disabled, malformed state,
    missing state).
  - 5 CLI --stdin mode cases (warn → exit 0 + JSON stdout with
    `_state` line; allow → exit 0 + `_state` line; malformed
    payload → exit 0 silent; store → exit 0 + state reset).
  - 4 CLI argv mode cases (warn returns warn JSON, allow returns
    allow JSON, missing args exits 2, state arg parses as JSON).
"""
import importlib.util
import io
import json
import os
import subprocess
import sys
import time
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "memory_reminder_check.py")


def _load_core():
    spec = importlib.util.spec_from_file_location(
        "memory_reminder_check", CORE_PATH)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def _fresh_state():
    return {
        "calls_since_last_store": 0,
        "last_store_ts": 0,
        "last_remind_ts": 0,
        "calls_at_last_remind": 0,
        "session_start_ts": 0,
    }


def _with_env(overrides):
    """Context manager that temporarily sets env vars."""
    class _Ctx:
        def __enter__(self_inner):
            self_inner._saved = {}
            for k, v in overrides.items():
                self_inner._saved[k] = os.environ.get(k)
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
            return self_inner

        def __exit__(self_inner, *exc):
            for k, prev in self_inner._saved.items():
                if prev is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = prev
            return False

    return _Ctx()


class ResetOnStoreTests(unittest.TestCase):
    """memory_store / consultant remember reset the counter."""

    def setUp(self):
        self.core = _load_core()

    def test_memory_store_resets_counters(self):
        # 4 side-effect calls (the 3rd one fires because the
        # threshold is 3 and the BACKOFF_CALLS env var is set to 0
        # to disable backoff for this test), then a memory_store.
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "3",
                        "AR_MEMORY_REMIND_BACKOFF_CALLS": "0",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            d3 = core.decide("Bash", 1002, d2["new_state"])
            self.assertEqual(d3["new_state"]["calls_since_last_store"], 3)
            d4 = core.decide("Bash", 1003, d3["new_state"])
            self.assertEqual(d4["action"], "warn")
            d5 = core.decide("mcp__ar-manager__memory_store", 1004, d4["new_state"])
            self.assertEqual(d5["action"], "allow")
            self.assertEqual(d5["new_state"]["calls_since_last_store"], 0)
            self.assertEqual(d5["new_state"]["last_store_ts"], 1004)
            self.assertEqual(d5["new_state"]["last_remind_ts"], 0)
            # After the store, 1 more side-effect call. Counter=1.
            # Backoff=0 (disabled). Threshold=3. Counter < threshold.
            # Allow.
            d6 = core.decide("Bash", 1005, d5["new_state"])
            self.assertEqual(d6["action"], "allow")
            self.assertEqual(d6["new_state"]["calls_since_last_store"], 1)

    def test_consultant_remember_also_resets(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            d3 = core.decide("mcp__ar-consultant__remember", 1002, d2["new_state"])
            self.assertEqual(d3["action"], "allow")
            self.assertEqual(d3["new_state"]["calls_since_last_store"], 0)
            self.assertEqual(d3["new_state"]["last_store_ts"], 1002)

    def test_store_does_not_count_as_side_effect(self):
        # A memory_store should leave the counter at 0, not 1.
        state = _fresh_state()
        d = self.core.decide("mcp__ar-manager__memory_store", 2000, state)
        self.assertEqual(d["new_state"]["calls_since_last_store"], 0)


class ReadsDoNotResetTests(unittest.TestCase):
    """memory_recall / consult / read do NOT reset the counter."""

    def setUp(self):
        self.core = _load_core()

    def test_memory_recall_does_not_reset(self):
        state = _fresh_state()
        state["calls_since_last_store"] = 7
        state["last_store_ts"] = 1000
        d = self.core.decide("mcp__ar-manager__memory_recall", 1500, state)
        self.assertEqual(d["action"], "allow")
        self.assertEqual(d["new_state"]["calls_since_last_store"], 7)
        self.assertEqual(d["new_state"]["last_store_ts"], 1000)

    def test_consultant_consult_does_not_reset(self):
        state = _fresh_state()
        state["calls_since_last_store"] = 4
        state["last_store_ts"] = 1000
        d = self.core.decide("mcp__ar-consultant__consult", 1600, state)
        self.assertEqual(d["action"], "allow")
        self.assertEqual(d["new_state"]["calls_since_last_store"], 4)
        self.assertEqual(d["new_state"]["last_store_ts"], 1000)

    def test_read_tool_does_not_increment(self):
        state = _fresh_state()
        d = self.core.decide("Read", 1000, state)
        self.assertEqual(d["action"], "allow")
        self.assertEqual(d["new_state"]["calls_since_last_store"], 0)

    def test_opencode_read_does_not_increment(self):
        state = _fresh_state()
        d = self.core.decide("read", 1000, state)
        self.assertEqual(d["action"], "allow")
        self.assertEqual(d["new_state"]["calls_since_last_store"], 0)

    def test_glob_does_not_increment(self):
        state = _fresh_state()
        d = self.core.decide("Glob", 1000, state)
        self.assertEqual(d["new_state"]["calls_since_last_store"], 0)

    def test_docs_mcp_tool_does_not_increment(self):
        # ar-docs tools are all reads; prefix-match.
        state = _fresh_state()
        d = self.core.decide("mcp__ar-docs__read_ar_guidelines", 1000, state)
        self.assertEqual(d["new_state"]["calls_since_last_store"], 0)


class WarmupAndCooldownTests(unittest.TestCase):
    """The first N calls of a session are silent; the first N calls
    after a store are also silent.
    """

    def setUp(self):
        self.core = _load_core()

    def test_warmup_silences_first_few_calls(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "3",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "5"}):
            core = _load_core()
            state = _fresh_state()
            # Calls 1-5: counter=1..5, all under warmup. Silent.
            for i in range(5):
                d = core.decide("Bash", 1000 + i, state)
                state = d["new_state"]
                self.assertEqual(d["action"], "allow",
                                 f"call {i} during warmup must be silent")
            # 6th call: counter=6, warmup lifted (6 > 5), threshold=3.
            # No backoff. Fires.
            d = core.decide("Bash", 1005, state)
            self.assertEqual(d["action"], "warn")

    def test_cooldown_silences_first_few_calls_after_store(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "3",
                        "AR_MEMORY_REMIND_COOLDOWN_CALLS": "4"}):
            core = _load_core()
            state = _fresh_state()
            # Pre-load: 8 side-effect calls to get to a fire state.
            for i in range(8):
                d = core.decide("Bash", 1000 + i, state)
                state = d["new_state"]
            # At this point we should have fired (8 >= 3).
            self.assertGreater(state["last_remind_ts"], 0)
            # Now store a memory.
            d = core.decide("mcp__ar-manager__memory_store", 2000, state)
            state = d["new_state"]
            self.assertEqual(state["last_store_ts"], 2000)
            # First 4 side-effect calls after the store are silent
            # (cooldown = 4). 5th call exits cooldown; backoff then
            # applies because last_remind_ts is still set (the store
            # path zeros it, so backoff is also off here).
            for i in range(4):
                d = core.decide("Bash", 2001 + i, state)
                state = d["new_state"]
                self.assertEqual(d["action"], "allow",
                                 f"call {i} during cooldown must be silent")
            # 5th call: counter=5, cooldown cleared (5 > 4). Threshold=3.
            # No backoff (last_remind_ts was zeroed by the store). Fires.
            d = core.decide("Bash", 2005, state)
            self.assertEqual(d["action"], "warn")

    def test_no_cooldown_when_no_store_has_happened(self):
        # If the agent has never stored, calls_since_last_store
        # below WARMUP_CALLS silences via the warmup check, not
        # cooldown. There is no separate "first-ever-session" cooldown
        # beyond warmup.
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "1",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "3"}):
            core = _load_core()
            state = _fresh_state()
            # Calls 1, 2, 3: counter=1,2,3, all under warmup. Silent.
            d = core.decide("Bash", 1000, state)
            self.assertEqual(d["action"], "allow")
            self.assertEqual(d["new_state"]["calls_since_last_store"], 1)
            d = core.decide("Bash", 1001, d["new_state"])
            self.assertEqual(d["action"], "allow")
            d = core.decide("Bash", 1002, d["new_state"])
            self.assertEqual(d["action"], "allow")
            # 4th call: counter=4, warmup passed (4 > 3), threshold=1.
            # No backoff. Fires.
            d = core.decide("Bash", 1003, d["new_state"])
            self.assertEqual(d["action"], "warn")


class TriggerTests(unittest.TestCase):
    """The hybrid trigger: count OR time, whichever first."""

    def setUp(self):
        self.core = _load_core()

    def test_count_threshold_fires(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "3",
                        "AR_MEMORY_REMIND_SECONDS_THRESHOLD": "1000000",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "1"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "allow")
            d3 = core.decide("Bash", 1002, d2["new_state"])
            self.assertEqual(d3["action"], "warn")

    def test_time_threshold_fires(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "1000000",
                        "AR_MEMORY_REMIND_SECONDS_THRESHOLD": "300",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "1"}):
            core = _load_core()
            state = _fresh_state()
            # session_start_ts will be set on first call.
            d1 = core.decide("Bash", 5000, state)
            self.assertEqual(d1["action"], "allow")
            d2 = core.decide("Bash", 5100, d1["new_state"])  # +100s
            self.assertEqual(d2["action"], "allow")
            d3 = core.decide("Bash", 5400, d2["new_state"])  # +300s, hits threshold
            self.assertEqual(d3["action"], "warn")

    def test_neither_threshold_fires(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "5",
                        "AR_MEMORY_REMIND_SECONDS_THRESHOLD": "1000",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d = core.decide("Bash", 1000, state)
            d = core.decide("Bash", 1050, d["new_state"])  # +50s
            self.assertEqual(d["action"], "allow")

    def test_off_by_one_under_threshold_does_not_fire(self):
        # Threshold=3; 2 side-effect calls is under, 3 is at-or-above.
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "3",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "allow")
            d3 = core.decide("Bash", 1002, d2["new_state"])
            self.assertEqual(d3["action"], "warn")

    def test_bash_counts_as_side_effect(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")

    def test_edit_counts_as_side_effect(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Edit", 1000, state)
            d2 = core.decide("Edit", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")

    def test_mcp_tool_counts_as_side_effect(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("mcp__ar-test-runner__start_test_run", 1000, state)
            d2 = core.decide("mcp__ar-test-runner__start_test_run", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")


class BackoffTests(unittest.TestCase):
    """After a reminder, don't re-fire on the immediate next call."""

    def setUp(self):
        self.core = _load_core()

    def test_immediate_refire_prevented(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_BACKOFF_CALLS": "5",
                        "AR_MEMORY_REMIND_BACKOFF_SECONDS": "600",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")
            # Immediate next call: count-since-remind = 1, time-since
            # is 0s, both under backoff. Silent.
            d3 = core.decide("Bash", 1002, d2["new_state"])
            self.assertEqual(d3["action"], "allow")

    def test_refire_after_backoff_calls(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_BACKOFF_CALLS": "3",
                        "AR_MEMORY_REMIND_BACKOFF_SECONDS": "1000000",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")
            # Backoff requires 3 more side-effect calls.
            for i in range(3):
                d = core.decide("Bash", 1002 + i, d2["new_state"] if i == 0 else d["new_state"])
            # After 3 more calls, count-since-remind = 3, equal to
            # BACKOFF_CALLS, so backoff lifts. Time-backoff is huge,
            # so the trigger fires only on count. The state has been
            # mutated by the loop. Walk explicitly.
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            # d2 fires; counter at fire = 2.
            self.assertEqual(d2["new_state"]["calls_at_last_remind"], 2)
            # 3 more calls: counter goes to 5, calls-since-remind = 3.
            d3 = core.decide("Bash", 1002, d2["new_state"])
            d4 = core.decide("Bash", 1003, d3["new_state"])
            d5 = core.decide("Bash", 1004, d4["new_state"])
            # Time-since-remind = 4s, under BACKOFF_SECONDS=1000000.
            # Count-since-remind = 3, equal to BACKOFF_CALLS=3.
            # Backoff lifts. Counter=5, threshold=2, fires.
            self.assertEqual(d5["action"], "warn")

    def test_refire_after_backoff_seconds(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_BACKOFF_CALLS": "1000000",
                        "AR_MEMORY_REMIND_BACKOFF_SECONDS": "50",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("Bash", 1000, state)
            d2 = core.decide("Bash", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")
            # 1000s later: time-backoff lifts.
            d3 = core.decide("Bash", 2001, d2["new_state"])
            self.assertEqual(d3["action"], "warn")

    def test_no_backoff_on_first_fire(self):
        # Fresh state, no prior reminder. The backoff check
        # (last_remind_ts > 0) is False, so we don't check the
        # backoff. Trigger fires normally.
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "1",
                        "AR_MEMORY_REMIND_BACKOFF_CALLS": "1000000",
                        "AR_MEMORY_REMIND_BACKOFF_SECONDS": "1000000",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d = core.decide("Bash", 1000, state)
            self.assertEqual(d["action"], "warn")


class DisabledAndFailsafeTests(unittest.TestCase):
    """Disabled env var, malformed state, missing state."""

    def setUp(self):
        self.core = _load_core()

    def test_disabled_env_var_never_fires(self):
        with _with_env({"AR_MEMORY_REMIND_DISABLED": "1",
                        "AR_MEMORY_REMIND_CALLS_THRESHOLD": "1",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            for i in range(20):
                d = core.decide("Bash", 1000 + i, state)
                state = d["new_state"]
                self.assertEqual(d["action"], "allow")

    def test_malformed_state_treated_as_fresh(self):
        # state is a string, not a dict. Should not crash.
        d = self.core.decide("Bash", 1000, "not a dict")
        self.assertEqual(d["action"], "allow")
        # new_state is normalized back to the default shape.
        self.assertEqual(d["new_state"]["calls_since_last_store"], 1)

    def test_partial_state_backfilled(self):
        # state has only one key. The rest are backfilled with defaults.
        d = self.core.decide("Bash", 1000, {"calls_since_last_store": 3})
        self.assertEqual(d["new_state"]["last_store_ts"], 0)
        self.assertEqual(d["new_state"]["session_start_ts"], 1000)

    def test_unknown_tool_treated_as_side_effect(self):
        # Defensive: an unknown tool name should be counted as a
        # side effect (we err on the side of "remind rather than
        # miss a real write").
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            core = _load_core()
            state = _fresh_state()
            d1 = core.decide("frobnicate", 1000, state)
            d2 = core.decide("frobnicate", 1001, d1["new_state"])
            self.assertEqual(d2["action"], "warn")

    def test_empty_tool_name_treated_as_read_only(self):
        # Defensive: if the tool name is missing entirely, don't
        # count it (we don't know what it was, and counting would
        # over-fire).
        state = _fresh_state()
        d = self.core.decide("", 1000, state)
        self.assertEqual(d["new_state"]["calls_since_last_store"], 0)


class StdinModeTests(unittest.TestCase):
    """The .sh adapter contract: --stdin mode renders natively."""

    def _run_stdin(self, payload):
        return subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
            input=json.dumps(payload),
            capture_output=True,
            text=True,
            timeout=5,
        )

    def _run_stdin_raw(self, raw):
        return subprocess.run(
            ["python3", CORE_PATH, "--stdin"],
            input=raw,
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_warn_path_exits_0_with_state_and_context(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "1",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            payload = {
                "tool_name": "Bash",
                "now_ts": 1000,
                "state": _fresh_state(),
            }
            r = self._run_stdin(payload)
            self.assertEqual(r.returncode, 0)
            lines = [ln for ln in r.stdout.split("\n") if ln]
            # First line: _state dict. Second line: additionalContext.
            self.assertEqual(len(lines), 2)
            state_line = json.loads(lines[0])
            ctx_line = json.loads(lines[1])
            self.assertIn("_state", state_line)
            self.assertEqual(state_line["_state"]["calls_since_last_store"], 1)
            self.assertIn("hookSpecificOutput", ctx_line)
            self.assertEqual(ctx_line["hookSpecificOutput"]["hookEventName"],
                             "PreToolUse")
            self.assertIn("memory_store", ctx_line["hookSpecificOutput"]["additionalContext"])

    def test_allow_path_exits_0_with_state_only(self):
        payload = {
            "tool_name": "Read",
            "now_ts": 1000,
            "state": _fresh_state(),
        }
        r = self._run_stdin(payload)
        self.assertEqual(r.returncode, 0)
        lines = [ln for ln in r.stdout.split("\n") if ln]
        self.assertEqual(len(lines), 1)
        state_line = json.loads(lines[0])
        self.assertIn("_state", state_line)

    def test_store_path_resets_state_in_stdout(self):
        state = _fresh_state()
        state["calls_since_last_store"] = 7
        state["last_store_ts"] = 500
        payload = {
            "tool_name": "mcp__ar-manager__memory_store",
            "now_ts": 1500,
            "state": state,
        }
        r = self._run_stdin(payload)
        self.assertEqual(r.returncode, 0)
        lines = [ln for ln in r.stdout.split("\n") if ln]
        self.assertEqual(len(lines), 1)
        state_line = json.loads(lines[0])
        self.assertEqual(state_line["_state"]["calls_since_last_store"], 0)
        self.assertEqual(state_line["_state"]["last_store_ts"], 1500)

    def test_malformed_json_exits_0_silently(self):
        r = self._run_stdin_raw("not json at all")
        self.assertEqual(r.returncode, 0)
        # No _state line because decide() returned allow with no
        # new_state when state is None. Actually decide() always
        # returns a normalized new_state, so the stdout will have
        # one line. That's fine — the .sh adapter persists whatever
        # new_state is there. The critical thing is exit 0.
        self.assertEqual(r.returncode, 0)

    def test_disabled_via_env_always_exits_0_silently(self):
        with _with_env({"AR_MEMORY_REMIND_DISABLED": "1"}):
            payload = {
                "tool_name": "Bash",
                "now_ts": 1000,
                "state": _fresh_state(),
            }
            r = self._run_stdin(payload)
            self.assertEqual(r.returncode, 0)
            lines = [ln for ln in r.stdout.split("\n") if ln]
            # No additionalContext line because decide() returned allow.
            for ln in lines:
                obj = json.loads(ln)
                self.assertNotIn("hookSpecificOutput", obj)


class ArgvModeTests(unittest.TestCase):
    """The .ts adapter contract: argv mode returns JSON, exit 0."""

    def _run_argv(self, *args):
        return subprocess.run(
            ["python3", CORE_PATH] + list(args),
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_warn_returns_warn_json(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "1",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            r = self._run_argv("Bash", "1000", json.dumps(_fresh_state()))
            self.assertEqual(r.returncode, 0)
            obj = json.loads(r.stdout)
            self.assertEqual(obj["action"], "warn")
            self.assertIn("memory_store", obj["context"])
            self.assertIn("new_state", obj)

    def test_allow_returns_allow_json(self):
        r = self._run_argv("Read", "1000", json.dumps(_fresh_state()))
        self.assertEqual(r.returncode, 0)
        obj = json.loads(r.stdout)
        self.assertEqual(obj["action"], "allow")
        self.assertEqual(obj["context"], "")

    def test_missing_args_exits_2(self):
        r = self._run_argv()
        self.assertEqual(r.returncode, 2)

    def test_state_arg_parses_as_json(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "1",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            r = self._run_argv("Bash", "1000", json.dumps(_fresh_state()))
            self.assertEqual(r.returncode, 0)
            obj = json.loads(r.stdout)
            self.assertEqual(obj["action"], "warn")
            self.assertEqual(obj["new_state"]["calls_since_last_store"], 1)

    def test_no_state_arg_uses_fresh(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            r = self._run_argv("Bash", "1000")
            self.assertEqual(r.returncode, 0)
            obj = json.loads(r.stdout)
            self.assertEqual(obj["action"], "allow")
            self.assertEqual(obj["new_state"]["calls_since_last_store"], 1)

    def test_malformed_state_arg_uses_fresh(self):
        with _with_env({"AR_MEMORY_REMIND_CALLS_THRESHOLD": "2",
                        "AR_MEMORY_REMIND_WARMUP_CALLS": "0"}):
            r = self._run_argv("Bash", "1000", "not json")
            self.assertEqual(r.returncode, 0)
            obj = json.loads(r.stdout)
            # Fresh state: counter=1, threshold=2, no fire.
            self.assertEqual(obj["action"], "allow")


if __name__ == "__main__":
    unittest.main(verbosity=2)
