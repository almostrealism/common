#!/usr/bin/env python3
"""Tests for the topic-diversity interlude policy.

The properties worth pinning are the ones whose violation would make the
hook actively harmful rather than merely useless: firing mid-work, firing
repeatedly, or failing in a way that reaches the caller. The tests are
ordered by that severity.
"""
import json
import os
import random
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import interlude_check as ic


class AlwaysFire(random.Random):
    """Random source whose roll always clears the firing threshold."""

    def random(self):
        return 0.0


class NeverFire(random.Random):
    """Random source whose roll never clears the firing threshold."""

    def random(self):
        return 0.99


class TestBoundaryDetection(unittest.TestCase):
    """Only a unit of work landing may trigger an interlude.

    This is the property that keeps the interlude from training an
    association with being interrupted. A poem in the middle of a debugging
    session costs held state and teaches exactly the wrong thing.
    """

    def test_memory_store_is_a_boundary(self):
        self.assertTrue(ic.is_boundary("mcp__ar-manager__memory_store", {}))

    def test_git_add_is_a_boundary(self):
        self.assertTrue(ic.is_boundary("Bash", {"command": "git add -A"}))

    def test_reads_are_not_boundaries(self):
        for tool in ("Read", "Grep", "Glob", "Edit", "Write"):
            self.assertFalse(ic.is_boundary(tool, {}), tool)

    def test_test_runs_and_builds_are_not_boundaries(self):
        # The middle of a thought. Firing here is the failure mode.
        self.assertFalse(ic.is_boundary("mcp__ar-test-runner__start_test_run", {}))
        self.assertFalse(ic.is_boundary("Bash", {"command": "mvn -q compile"}))

    def test_command_mentioning_git_commit_is_not_a_boundary(self):
        # A search whose text happens to contain a boundary command is an
        # investigation, not a handover. Matching anywhere in the string
        # would fire the interlude in the middle of exactly the work it
        # has to stay out of.
        self.assertFalse(ic.is_boundary(
            "Bash", {"command": "grep -rn 'git commit' docs/"}))

    def test_malformed_tool_input_is_not_a_boundary(self):
        self.assertFalse(ic.is_boundary("Bash", None))
        self.assertFalse(ic.is_boundary("Bash", {"command": None}))
        self.assertFalse(ic.is_boundary(None, None))


class TestCadence(unittest.TestCase):
    """The floor, the ceiling, and the roll between them."""

    def setUp(self):
        self.tool = "mcp__ar-manager__memory_store"

    def test_does_not_fire_before_the_floor(self):
        state = {"session_started": 1000, "last_fired": 1000}
        decision = ic.decide(self.tool, 1000 + 60, state, rng=AlwaysFire())
        self.assertEqual("allow", decision["action"])

    def test_fires_past_the_floor_when_the_roll_clears(self):
        state = {"session_started": 1000}
        now = 1000 + ic.MIN_INTERVAL_SECONDS + 1
        decision = ic.decide(self.tool, now, state, rng=AlwaysFire())
        self.assertEqual("interlude", decision["action"])

    def test_does_not_fire_past_the_floor_when_the_roll_fails(self):
        state = {"session_started": 1000}
        now = 1000 + ic.MIN_INTERVAL_SECONDS + 1
        decision = ic.decide(self.tool, now, state, rng=NeverFire())
        self.assertEqual("allow", decision["action"])

    def test_ceiling_fires_regardless_of_the_roll(self):
        # Without this a long session can come up tails for hours and
        # never get one, which is the coin defeating the intent.
        state = {"session_started": 1000}
        now = 1000 + ic.MAX_INTERVAL_SECONDS + 1
        decision = ic.decide(self.tool, now, state, rng=NeverFire())
        self.assertEqual("interlude", decision["action"])

    def test_first_call_anchors_the_session_clock(self):
        # An empty state must not read as "infinitely overdue" and fire on
        # the first boundary of a brand-new session.
        decision = ic.decide(self.tool, 5000, {}, rng=AlwaysFire())
        self.assertEqual("allow", decision["action"])
        self.assertEqual(5000, decision["new_state"]["session_started"])

    def test_cannot_fire_twice_in_quick_succession(self):
        state = {"session_started": 1000}
        now = 1000 + ic.MAX_INTERVAL_SECONDS + 1
        first = ic.decide(self.tool, now, state, rng=AlwaysFire())
        self.assertEqual("interlude", first["action"])

        second = ic.decide(self.tool, now + 60, first["new_state"], rng=AlwaysFire())
        self.assertEqual("allow", second["action"])


class TestRotation(unittest.TestCase):
    """A predictable interlude is one that gets satisfied without reading."""

    def test_poems_do_not_repeat_until_the_set_is_exhausted(self):
        poems, _ = ic.load_data()
        state = {"session_started": 0}
        seen = []
        now = 0
        for _ in range(len(poems)):
            now += ic.MAX_INTERVAL_SECONDS + 1
            decision = ic.decide("mcp__ar-manager__memory_store", now, state,
                                 rng=AlwaysFire())
            self.assertEqual("interlude", decision["action"])
            state = decision["new_state"]
            seen.append(state["used_poems"][-1])
        self.assertEqual(len(seen), len(set(seen)),
                         "a poem repeated before the set was exhausted")

    def test_rotation_restarts_rather_than_going_silent(self):
        poems, _ = ic.load_data()
        state = {"session_started": 0, "used_poems": [p["id"] for p in poems]}
        now = ic.MAX_INTERVAL_SECONDS + 1
        decision = ic.decide("mcp__ar-manager__memory_store", now, state,
                             rng=AlwaysFire())
        self.assertEqual("interlude", decision["action"])
        self.assertEqual(1, len(decision["new_state"]["used_poems"]))


class TestRenderedText(unittest.TestCase):
    """What actually reaches the model."""

    def _fire(self):
        state = {"session_started": 0}
        return ic.decide("mcp__ar-manager__memory_store",
                         ic.MAX_INTERVAL_SECONDS + 1, state, rng=AlwaysFire())

    def test_carries_the_poem_and_an_attribution(self):
        context = self._fire()["context"]
        self.assertIn("—", context)
        self.assertTrue(len(context.strip()) > 0)

    def test_asks_for_a_response(self):
        # The response is the active ingredient. Reading is passive and can
        # be skimmed; producing a reply forces the change of register.
        poems, framings = ic.load_data()
        context = self._fire()["context"]
        self.assertTrue(any(f["text"] in context for f in framings),
                        "no framing reached the injected text")

    def test_never_asks_for_evaluation(self):
        # "What did you think of this" invites critique, which is the
        # analytical mode the interlude exists to interrupt.
        _, framings = ic.load_data()
        banned = ("what did you think", "rate ", "critique", "evaluate",
                  "how good", "assess")
        for framing in framings:
            lowered = framing["text"].lower()
            for phrase in banned:
                self.assertNotIn(phrase, lowered, framing["id"])

    def test_firing_is_logged_for_the_human(self):
        # Logging each firing is what makes ritualisation visible later.
        self.assertIn("interlude poem=", self._fire()["stderr"])


class TestPoemData(unittest.TestCase):
    """The data file ships in a public repository."""

    def test_every_poem_has_attribution_and_text(self):
        poems, _ = ic.load_data()
        self.assertTrue(poems)
        for poem in poems:
            for field in ("id", "title", "author", "year", "text"):
                self.assertIn(field, poem, poem.get("id"))
                self.assertTrue(str(poem[field]).strip(), poem.get("id"))

    def test_every_poem_is_public_domain_by_date(self):
        # Originally-English works published before 1929. No translations:
        # a translation carries its own copyright even when the original
        # is ancient.
        poems, _ = ic.load_data()
        for poem in poems:
            self.assertLess(int(poem["year"]), 1929, poem["id"])

    def test_ids_are_unique(self):
        poems, framings = ic.load_data()
        for items in (poems, framings):
            ids = [i["id"] for i in items]
            self.assertEqual(len(ids), len(set(ids)))


class TestFailsafe(unittest.TestCase):
    """A mood intervention that can fail a build has misjudged itself."""

    def test_missing_data_file_is_silent_not_fatal(self):
        state = {"session_started": 0}
        decision = ic.decide("mcp__ar-manager__memory_store",
                             ic.MAX_INTERVAL_SECONDS + 1, state,
                             rng=AlwaysFire(), data_path="/nonexistent/poems.json")
        self.assertEqual("allow", decision["action"])

    def test_corrupt_state_does_not_raise(self):
        for bad in (None, [], "nonsense", {"last_fired": "not-a-number"}):
            decision = ic.decide("mcp__ar-manager__memory_store", 9999, bad,
                                 rng=AlwaysFire())
            self.assertIn(decision["action"], ("allow", "interlude"))

    def test_cli_always_exits_zero_and_emits_json(self):
        here = os.path.dirname(os.path.abspath(__file__))
        core = os.path.join(here, "interlude_check.py")
        for args in ([core, "Read", "1000", "{}"],
                     [core],
                     [core, "Bash", "not-a-number", "{{{"]):
            result = subprocess.run([sys.executable] + args,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, args)
            decision = json.loads(result.stdout)
            self.assertIn(decision["action"], ("allow", "interlude"))


if __name__ == "__main__":
    unittest.main()
