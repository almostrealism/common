#!/usr/bin/env python3
"""Unit tests for pr_gate_check.py and the hook registration that runs it."""
import importlib.util
import json
import os
import tempfile
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

    def test_base_filtered_listing_is_not_recorded(self):
        filtered = {"org": "acme", "repo": "app", "base": "develop"}
        state = core.record({}, filtered, NONE_OPEN, 100)
        self.assertEqual({}, state, "a base-filtered listing is not repository-wide")


class GateTest(unittest.TestCase):

    def test_create_without_listing_is_denied(self):
        decision, _, _ = core.gate({}, create(), 100)
        self.assertEqual("deny", decision["action"])
        self.assertIn("github_list_open_prs", decision["reason"])

    def test_stale_listing_is_denied(self):
        state = core.record({}, LISTED, NONE_OPEN, 100)
        decision, _, _ = core.gate(state, create(), 100 + core.FRESHNESS_SECONDS + 1)
        self.assertEqual("deny", decision["action"])

    def test_workstream_context_is_keyed_and_gated(self):
        # No org/repo: the call is resolved from workstream_id, a normal supported
        # form. A listing in that context is recorded and a create in it is allowed.
        listed = {"workstream_id": "ws-7"}
        created = {"workstream_id": "ws-7", "head": "fix/two", "title": "t", "body": "b"}
        state = core.record({}, listed, NONE_OPEN, 100)
        self.assertIn("ws:ws-7", state.get("repos", {}))
        decision, _, _ = core.gate(state, created, 200)
        self.assertEqual("allow", decision["action"])

    def test_token_context_without_listing_is_denied_for_freshness(self):
        # A create with no addressing (token context) is not rejected for missing
        # org/repo; it is denied only because no fresh listing exists yet.
        decision, _, _ = core.gate({}, {"title": "t", "body": "b"}, 100)
        self.assertEqual("deny", decision["action"])
        self.assertIn("github_list_open_prs", decision["reason"])

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

    def test_changed_body_is_not_a_confirmation(self):
        # The confirmation fingerprint covers the whole tool input, so changing a
        # material field the first call did not show (the body) is a new request.
        state = core.record({}, LISTED, OPEN, 100)
        first = create()
        _, state, _ = core.gate(state, first, 200)
        changed = dict(first, body="a completely different description")
        decision, _, override = core.gate(state, changed, 210)
        self.assertEqual("deny", decision["action"])
        self.assertIsNone(override)

    def test_empty_head_resolves_to_current_branch(self):
        # github_create_pr defaults head to the current branch; the gate resolves
        # the same branch so an open PR from it is seen as the same branch, not a
        # different-branch PR that forces a confirmation.
        state = core.record({}, LISTED, OPEN, 100)
        same = {"org": "acme", "repo": "app", "title": "t", "body": "x"}
        decision, _, _ = core.gate(state, same, 200, head="fix/one")
        self.assertEqual("allow", decision["action"])


class BashTest(unittest.TestCase):

    def test_gh_pr_create_is_denied(self):
        for command in ("gh pr create --title t --body b", "cd x && gh pr create -f",
                        "gh -R acme/app pr create"):
            self.assertEqual("deny", core.bash(command)["action"], command)

    def test_quoted_and_escaped_spellings_are_denied(self):
        # Shell-aware tokenization: these all run as `gh pr create` and must not
        # slip past a match on the raw command text.
        for command in ('gh "pr" create --title t', "gh pr cre'ate'",
                        'gh pr create --body "a b"', "gh \\\npr create"):
            self.assertEqual("deny", core.bash(command)["action"], command)

    def test_dynamic_subcommand_is_denied(self):
        # The subcommand (or, after `pr`, the sub-subcommand) is built from a shell
        # expansion the gate cannot resolve, so `gh pr create` may be what runs.
        for command in ('sub=create; gh pr "$sub"', "gh pr $sub", "gh pr ${sub}",
                        "gh pr $(echo create)", "gh $cmd", 'gh "$sub" create',
                        "gh -R acme/app pr `echo create`"):
            self.assertEqual("deny", core.bash(command)["action"], command)

    def test_dynamic_argument_to_safe_gh_subcommand_is_allowed(self):
        # An expansion in an argument of an already-safe subcommand is not a
        # subcommand, so it must not be blocked.
        for command in ('gh pr view "$num"', "gh pr list --jq '$.title'", 'gh api "$url"'):
            self.assertEqual("allow", core.bash(command)["action"], command)

    def test_unparseable_gh_command_fails_closed(self):
        # Unbalanced quotes: the gate cannot tokenize it to prove it is safe.
        decision = core.bash('gh pr create --title "unterminated')
        self.assertEqual("deny", decision["action"])

    def test_unparseable_non_gh_command_is_allowed(self):
        # A malformed command that has nothing to do with gh must not be blocked.
        self.assertEqual("allow", core.bash('echo "unterminated')["action"])

    def test_other_gh_commands_allow(self):
        for command in ("gh pr view 104", "gh pr list", "git push origin fix/one"):
            self.assertEqual("allow", core.bash(command)["action"], command)


class CurrentBranchTest(unittest.TestCase):

    def test_reads_branch_from_git_head(self):
        with tempfile.TemporaryDirectory() as project:
            os.mkdir(os.path.join(project, ".git"))
            with open(os.path.join(project, ".git", "HEAD"), "w", encoding="utf-8") as f:
                f.write("ref: refs/heads/ci/pr-creation-gate\n")
            self.assertEqual("ci/pr-creation-gate", core.current_branch(project))

    def test_detached_head_is_empty(self):
        with tempfile.TemporaryDirectory() as project:
            os.mkdir(os.path.join(project, ".git"))
            with open(os.path.join(project, ".git", "HEAD"), "w", encoding="utf-8") as f:
                f.write("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2\n")
            self.assertEqual("", core.current_branch(project))

    def test_missing_git_dir_is_empty(self):
        with tempfile.TemporaryDirectory() as project:
            self.assertEqual("", core.current_branch(project))


class ResolveHeadTest(unittest.TestCase):

    def test_explicit_head_is_returned(self):
        self.assertEqual("fix/two", core.resolve_head({"head": "fix/two"}, "/nonexistent"))

    def test_direct_org_repo_without_head_is_unresolved(self):
        # github_create_pr with explicit org/repo defaults head to the REMOTE repo
        # default branch, which the gate cannot read locally, so it stays "".
        with tempfile.TemporaryDirectory() as project:
            os.mkdir(os.path.join(project, ".git"))
            with open(os.path.join(project, ".git", "HEAD"), "w", encoding="utf-8") as f:
                f.write("ref: refs/heads/feature\n")
            self.assertEqual("", core.resolve_head({"org": "acme", "repo": "app"}, project))

    def test_workstream_without_head_resolves_to_current_branch(self):
        # A workstream/token-context call defaults head to the workstream default
        # branch, which for an agent session is the current checkout.
        with tempfile.TemporaryDirectory() as project:
            os.mkdir(os.path.join(project, ".git"))
            with open(os.path.join(project, ".git", "HEAD"), "w", encoding="utf-8") as f:
                f.write("ref: refs/heads/feature\n")
            self.assertEqual("feature", core.resolve_head({"workstream_id": "ws-7"}, project))


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
