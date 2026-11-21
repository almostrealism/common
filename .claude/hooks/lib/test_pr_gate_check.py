#!/usr/bin/env python3
"""Unit tests for pr_gate_check.py and the hook registration that runs it."""
import importlib.util
import json
import os
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "pr_gate_check.py")
SETTINGS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "settings.json")


def _load_core():
    spec = importlib.util.spec_from_file_location("pr_gate_check", CORE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


core = _load_core()

LISTED = {"org": "acme", "repo": "app"}
OPEN = json.dumps({"ok": True, "count": 1, "prs": [
    {"number": 104, "head": "fix/one", "title": "Fix one thing", "user": "dev"}]})
NONE_OPEN = json.dumps({"ok": True, "count": 0, "prs": []})


def create(head="fix/two", title="Fix another thing"):
    """Returns github_create_pr input for the listed repository."""
    return {"org": "acme", "repo": "app", "head": head, "title": title, "body": "x"}


class ParseTest(unittest.TestCase):

    def test_parses_string_dict_and_content_blocks(self):
        for response in (OPEN, json.loads(OPEN), [{"type": "text", "text": OPEN}],
                         {"content": [{"type": "text", "text": OPEN}]}):
            prs = core.parse_open_prs(response)
            self.assertEqual([104], [p["number"] for p in prs])

    def test_failed_listing_is_not_a_record(self):
        state = core.record({}, LISTED, json.dumps({"ok": False, "error": "x"}), 100)
        self.assertEqual({}, state)


class GateTest(unittest.TestCase):

    def test_create_without_listing_is_denied(self):
        decision, _, _ = core.gate({}, create(), 100)
        self.assertEqual("deny", decision["action"])
        self.assertIn("github_list_open_prs", decision["reason"])

    def test_stale_listing_is_denied(self):
        state = core.record({}, LISTED, NONE_OPEN, 100)
        decision, _, _ = core.gate(state, create(), 100 + core.FRESHNESS_SECONDS + 1)
        self.assertEqual("deny", decision["action"])

    def test_missing_repo_is_denied(self):
        decision, _, _ = core.gate({}, {"title": "t", "body": "b"}, 100)
        self.assertEqual("deny", decision["action"])
        self.assertIn("org and repo", decision["reason"])

    def test_no_open_pull_request_allows(self):
        state = core.record({}, LISTED, NONE_OPEN, 100)
        decision, _, override = core.gate(state, create(), 200)
        self.assertEqual("allow", decision["action"])
        self.assertIsNone(override)

    def test_open_pull_request_on_same_branch_allows(self):
        state = core.record({}, LISTED, OPEN, 100)
        decision, _, _ = core.gate(state, create(head="fix/one"), 200)
        self.assertEqual("allow", decision["action"])

    def test_second_pull_request_is_denied_then_confirmed_and_logged(self):
        state = core.record({}, LISTED, OPEN, 100)
        decision, state, override = core.gate(state, create(), 200)
        self.assertEqual("deny", decision["action"])
        self.assertIn("#104", decision["reason"])
        self.assertIn("commit it to the open branch", decision["reason"])
        self.assertIsNone(override)

        decision, state, override = core.gate(state, create(), 260)
        self.assertEqual("allow", decision["action"])
        self.assertEqual([104], override["open"])
        self.assertEqual("acme/app", override["repo"])

        decision, _, _ = core.gate(state, create(), 300)
        self.assertEqual("deny", decision["action"], "a confirmation is spent once it is used")

    def test_confirmation_expires(self):
        state = core.record({}, LISTED, OPEN, 100)
        _, state, _ = core.gate(state, create(), 200)
        state = core.record(state, LISTED, OPEN, 200 + core.CONFIRM_SECONDS)
        decision, _, _ = core.gate(state, create(), 200 + core.CONFIRM_SECONDS + 1)
        self.assertEqual("deny", decision["action"])

    def test_different_call_is_not_a_confirmation(self):
        state = core.record({}, LISTED, OPEN, 100)
        _, state, _ = core.gate(state, create(), 200)
        decision, _, _ = core.gate(state, create(title="Something else"), 210)
        self.assertEqual("deny", decision["action"])


class BashTest(unittest.TestCase):

    def test_gh_pr_create_is_denied(self):
        for command in ("gh pr create --title t --body b", "cd x && gh pr create -f",
                        "gh -R acme/app pr create"):
            self.assertEqual("deny", core.bash(command)["action"], command)

    def test_other_gh_commands_allow(self):
        for command in ("gh pr view 104", "gh pr list", "git push origin fix/one"):
            self.assertEqual("allow", core.bash(command)["action"], command)


class RegistrationTest(unittest.TestCase):

    def commands(self, event, matcher_part):
        with open(SETTINGS, "r", encoding="utf-8") as f:
            settings = json.load(f)
        return [h["command"] for entry in settings["hooks"][event]
                if matcher_part in entry.get("matcher", "")
                for h in entry["hooks"] if "pr-gate.sh" in h["command"]]

    def test_hook_is_registered_for_every_mode(self):
        self.assertTrue(any(c.endswith(" record") for c in self.commands("PostToolUse", "github_list_open_prs")))
        self.assertTrue(any(c.endswith(" gate") for c in self.commands("PreToolUse", "github_create_pr")))
        self.assertTrue(any(c.endswith(" bash") for c in self.commands("PreToolUse", "Bash")))


if __name__ == "__main__":
    unittest.main()
