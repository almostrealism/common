#!/usr/bin/env python3
"""Unit tests for private_url_check.py and the hook registration that runs it."""
import importlib.util
import json
import os
import re
import subprocess
import tempfile
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "private_url_check.py")
WRAPPER = os.path.join(os.path.dirname(HERE), "block-private-url.sh")
SETTINGS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "settings.json")

SESSION_LINK = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"
ENTRIES = ["amd-halo", "amd-halo.local", "192.168.8.0/24", "100.64.0.0/10",
           "*.docker.internal", "*.example-tailnet.ts.net"]


def _load_core():
    spec = importlib.util.spec_from_file_location("private_url_check", CORE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


core = _load_core()


def bash(command, cwd=None):
    return {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": cwd}


class DecideTests(unittest.TestCase):

    def assertAction(self, payload, action):
        self.assertEqual(action, core.decide(payload, ENTRIES)["action"], payload)

    def test_a_session_link_in_a_pr_body_blocks(self):
        self.assertAction(bash('gh pr create --title T --body "Fix.\n\n%s"' % SESSION_LINK),
                          "block")

    def test_a_session_link_in_a_heredoc_body_blocks(self):
        command = "gh pr edit 7 --body \"$(cat <<'EOF'\nFix.\n%s\nEOF\n)\"" % SESSION_LINK
        self.assertAction(bash(command), "block")

    def test_a_session_link_in_a_body_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "body.md"), "w") as handle:
                handle.write("Fix.\n\n%s\n" % SESSION_LINK)
            self.assertAction(bash("gh pr create --title T --body-file body.md", tmp), "block")
            self.assertAction(bash("git commit -F body.md", tmp), "block")

    def test_a_session_trailer_in_a_commit_message_blocks(self):
        self.assertAction(bash('git commit -m "Fix" -m "Claude-Session: %s"' % SESSION_LINK),
                          "block")

    def test_a_bare_session_id_blocks(self):
        self.assertAction(bash('gh pr comment 7 --body "see session_01AbCdEfGhIjKlMnOpQr"'),
                          "block")

    def test_other_claude_ai_pages_block(self):
        for link in ("https://claude.ai/chat/0b7c", "claude.ai/artifact/abc123",
                     "https://claude.ai/project/x"):
            self.assertAction(bash('gh issue comment 3 --body "%s"' % link), "block")

    def test_the_public_claude_ai_api_is_allowed(self):
        self.assertAction(bash('gh pr create --body "callback https://claude.ai/api/mcp/auth_callback"'),
                          "allow")

    def test_a_lab_host_url_blocks(self):
        for url in ("http://amd-halo:8080/jobs", "http://192.168.8.14/", "http://100.70.1.2:9000",
                    "https://box.example-tailnet.ts.net/x", "http://10.0.0.5/", "http://amd-halo.local"):
            self.assertAction(bash('gh pr create --body "runs at %s"' % url), "block")

    def test_an_unlisted_local_host_blocks(self):
        """Any ``.local`` name is a private machine, even one not in [lab-hosts]."""
        self.assertAction(bash('gh pr create --body "runs at http://unlisted.local/x"'), "block")

    def test_url_userinfo_does_not_evade_the_private_ip_check(self):
        """Userinfo before the host must not hide the private address it precedes."""
        for url in ("http://user:pass@10.0.0.5/", "http://user@192.168.8.14/jobs"):
            self.assertAction(bash('gh pr create --body "see %s"' % url), "block")

    def test_a_public_host_behind_userinfo_is_still_allowed(self):
        self.assertAction(bash('gh pr create --body "https://user:pass@github.com/x/y"'), "allow")

    def test_gh_global_options_before_the_subcommand_are_detected(self):
        """``gh --repo owner/repo pr create`` still publishes its body and body file."""
        self.assertAction(
            bash('gh --repo owner/repo pr create --title T --body "Fix.\n\n%s"' % SESSION_LINK),
            "block")
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "body.md"), "w") as handle:
                handle.write('Fix.\n\n%s\n' % SESSION_LINK)
            self.assertAction(
                bash('gh --repo owner/repo pr create --title T --body-file body.md', tmp), "block")

    def test_loopback_and_public_urls_are_allowed(self):
        self.assertAction(bash('gh pr create --body "http://localhost:8080 and '
                               'https://github.com/almostrealism/common/pull/7"'), "allow")

    def test_a_command_that_publishes_nothing_is_not_scanned(self):
        self.assertAction(bash("echo %s > notes.txt" % SESSION_LINK), "allow")
        self.assertAction(bash("gh run view 123 --log | grep %s" % SESSION_LINK), "allow")

    def test_a_github_mcp_write_is_scanned(self):
        payload = {"tool_name": "mcp__claude_ai_ar-manager__github_create_pr",
                   "tool_input": {"title": "Fix", "body": "Fix.\n\n" + SESSION_LINK}}
        self.assertAction(payload, "block")
        payload["tool_name"] = "mcp__ar-manager__github_pr_reply"
        self.assertAction(payload, "block")

    def test_a_github_mcp_read_is_not_scanned(self):
        payload = {"tool_name": "mcp__ar-manager__github_pr_find",
                   "tool_input": {"query": SESSION_LINK}}
        self.assertAction(payload, "allow")

    def test_commit_txt_writes_are_scanned(self):
        self.assertAction({"tool_name": "Write", "tool_input": {
            "file_path": "/w/commit.txt", "content": "Fix\n\n" + SESSION_LINK}}, "block")
        self.assertAction({"tool_name": "Edit", "tool_input": {
            "file_path": "/w/commit.txt", "old_string": "a", "new_string": SESSION_LINK}}, "block")
        self.assertAction({"tool_name": "MultiEdit", "tool_input": {
            "file_path": "commit.txt", "edits": [{"new_string": SESSION_LINK}]}}, "block")

    def test_other_files_are_not_scanned(self):
        self.assertAction({"tool_name": "Write", "tool_input": {
            "file_path": "/w/docs/notes.md", "content": SESSION_LINK}}, "allow")

    def test_the_reason_names_the_link_and_the_rule(self):
        reason = core.decide(bash('gh pr create --body "%s"' % SESSION_LINK), ENTRIES)["reason"]
        self.assertIn(SESSION_LINK, reason)
        self.assertIn("Never Publish a Private Link", reason)


class AllowlistTests(unittest.TestCase):

    def test_lab_hosts_come_from_the_allowlist_without_loopback(self):
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as handle:
            handle.write("[lab-hosts]\nlocalhost\n127.0.0.1\namd-halo  # a box\n"
                         "[ar-servers]\n*.example.com\n")
        try:
            self.assertEqual(["amd-halo"], core.lab_hosts(handle.name))
        finally:
            os.unlink(handle.name)

    def test_the_real_allowlist_has_lab_hosts(self):
        self.assertTrue(core.lab_hosts())


class WrapperTests(unittest.TestCase):

    def run_hook(self, payload):
        return subprocess.run(["bash", WRAPPER], input=json.dumps(payload),
                              capture_output=True, text=True)

    def test_blocks_with_exit_2(self):
        result = self.run_hook(bash('gh pr create --body "%s"' % SESSION_LINK))
        self.assertEqual(2, result.returncode)
        self.assertIn("private link", result.stderr)

    def test_allows_with_exit_0(self):
        self.assertEqual(0, self.run_hook(bash("git status")).returncode)

    def test_the_opencode_entry_point_prints_the_decision(self):
        """The opencode adapter passes the payload as an argument and reads JSON back."""
        for payload, action in ((bash('gh pr create --body "%s"' % SESSION_LINK), "block"),
                                (bash("git status"), "allow")):
            result = subprocess.run(["python3", CORE_PATH, json.dumps(payload)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode)
            self.assertEqual(action, json.loads(result.stdout)["action"])


class RegistrationTests(unittest.TestCase):
    """The hook is registered for every route that publishes text."""

    def test_registered_for_each_route(self):
        with open(SETTINGS) as handle:
            entries = json.load(handle)["hooks"]["PreToolUse"]
        matchers = [entry["matcher"] for entry in entries
                    if any(h["command"].endswith("block-private-url.sh")
                           for h in entry["hooks"])]
        self.assertTrue(matchers)
        for tool in ("Bash", "Write", "Edit", "MultiEdit",
                     "mcp__claude_ai_ar-manager__github_create_pr",
                     "mcp__ar-manager__github_pr_reply"):
            self.assertTrue(any(re.fullmatch(m, tool) for m in matchers), tool)


if __name__ == "__main__":
    unittest.main()
