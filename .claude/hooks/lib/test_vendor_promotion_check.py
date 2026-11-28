#!/usr/bin/env python3
"""Tests for the hook that refuses vendor attribution, vendor links and vendor-hosted documents."""

import io
import json
import os
import re
import subprocess
import tempfile
import unittest

import vendor_promotion_check as check

HERE = os.path.dirname(os.path.abspath(__file__))
CORE_PATH = os.path.join(HERE, "vendor_promotion_check.py")
WRAPPER = os.path.join(os.path.dirname(HERE), "block-vendor-promotion.sh")
SETTINGS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "settings.json")

SESSION_LINK = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"


def write(path, content):
    """A Write payload."""
    return {"tool_name": "Write", "tool_input": {"file_path": path, "content": content}}


def edit(path, old, new):
    """An Edit payload."""
    return {"tool_name": "Edit",
            "tool_input": {"file_path": path, "old_string": old, "new_string": new}}


class VendorDocumentTests(unittest.TestCase):
    """Writes to the claude.ai document connector are refused; reads are not."""

    def test_each_write_tool_is_blocked(self):
        for tool in ("mcp__claude_ai_Claude_Docs__batch", "mcp__claude_ai_Claude_Docs__create",
                     "mcp__claude_ai_Claude_Docs__update", "mcp__Claude_Docs__create"):
            decision = check.decide({"tool_name": tool, "tool_input": {}})
            self.assertEqual("block", decision["action"], tool)
            self.assertIn("vendor-promotion-log.md", decision["reason"])

    def test_reads_are_allowed(self):
        for tool in ("mcp__claude_ai_Claude_Docs__read", "mcp__claude_ai_Claude_Docs__query",
                     "mcp__claude_ai_Claude_Docs__export", "mcp__claude_ai_Claude_Docs__guide"):
            self.assertEqual("allow", check.decide({"tool_name": tool, "tool_input": {}})["action"],
                             tool)


class FileEditTests(unittest.TestCase):
    """An edit that adds vendor attribution or a vendor link to a file is refused."""

    def assertAction(self, payload, action):
        self.assertEqual(action, check.decide(payload)["action"], payload)

    def test_a_session_link_in_a_source_comment_is_blocked(self):
        self.assertAction(write("src/main/java/A.java", "// see " + SESSION_LINK + "\n"), "block")

    def test_a_claude_ai_page_in_a_document_is_blocked(self):
        for link in ("https://claude.ai/chat/0b7c", "claude.ai/artifact/abc123",
                     "https://claude.ai/project/x"):
            self.assertAction(edit("docs/guide.md", "", "Notes: " + link), "block")

    def test_a_bare_session_id_is_blocked(self):
        self.assertAction(edit("README.md", "", "from session_01AbCdEfGhIjKlMnOpQr"), "block")

    def test_attribution_lines_are_blocked(self):
        for line in ("Claude-Session: anything",
                     "Co-Authored-By: Claude <noreply@anthropic.com>",
                     "co-authored-by: Claude Opus",
                     "Generated with [Claude Code](https://claude.com/claude-code)",
                     "\U0001F916 Generated with Claude Code",
                     "Created by Claude Code",
                     "Generated with Anthropic",
                     "Assisted-By: Claude",
                     "Produced by Claude"):
            self.assertAction(write("docs/notes.md", "Summary\n\n" + line + "\n"), "block")

    def test_attribution_inside_a_comment_is_blocked(self):
        """A credit hidden behind a comment leader is still a promotion route."""
        for path, line in ((".claude/x.java", "// Generated with Claude Code"),
                           ("docs/page.html", "<!-- Claude-Session: 01AbCd -->"),
                           ("scripts/x.py", "# Created by Claude"),
                           ("src/main/java/A.java", " * Co-Authored-By: Claude")):
            self.assertAction(write(path, "body\n" + line + "\n"), "block")

    def test_multiedit_is_checked_edit_by_edit(self):
        payload = {"tool_name": "MultiEdit", "tool_input": {
            "file_path": "docs/notes.md",
            "edits": [{"old_string": "a", "new_string": "b"},
                      {"old_string": "c", "new_string": "Claude-Session: x"}]}}
        self.assertAction(payload, "block")

    def test_a_markdown_link_credit_is_still_blocked(self):
        """The vendor as the object of the preposition blocks even behind a ``[``."""
        self.assertAction(write("docs/notes.md", "Generated with [Claude Code](https://x)\n"),
                          "block")

    def test_a_credit_verb_then_an_unrelated_vendor_mention_is_allowed(self):
        """A line opening with a credit verb whose vendor word is incidental prose,
        not the object of ``with``/``by``, is description rather than attribution."""
        for line in ("Generated with the build tool; see CLAUDE.md for details",
                     "Created by the pipeline, documented under claude-notes",
                     "Produced by our own compiler, unlike Anthropic's offering"):
            self.assertAction(write("docs/notes.md", "Body\n\n" + line + "\n"), "allow")

    def test_rules_that_describe_attribution_are_allowed(self):
        """CLAUDE.md names the trailers and the link shape without carrying them."""
        text = ("- a `Co-Authored-By:` / `Authored-By:` / `Assisted-By:` trailer\n"
                "- a tool credit such as \"Generated with ...\", \"Created by ...\"\n"
                "- any claude.ai page: a Claude Code session (`claude.ai/code/session_...`)\n"
                "- a claude.ai session link, appended as a `Claude-Session:` trailer\n")
        self.assertAction(write("CLAUDE.md", text), "allow")

    def test_a_trailer_naming_a_person_is_allowed(self):
        self.assertAction(write("docs/notes.md", "Co-Authored-By: A Person <a@example.com>\n"),
                          "allow")

    def test_the_public_api_endpoint_is_allowed(self):
        self.assertAction(write("tools/oauth.py", 'URL = "https://claude.ai/api/oauth"\n'), "allow")

    def test_a_link_the_edit_does_not_add_is_allowed(self):
        self.assertAction(edit("docs/guide.md", "Old " + SESSION_LINK, "New " + SESSION_LINK),
                          "allow")

    def test_a_link_already_in_a_rewritten_file_is_allowed(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as handle:
            handle.write("Old text " + SESSION_LINK + "\n")
        self.addCleanup(os.unlink, handle.name)
        self.assertAction(write(handle.name, "New text " + SESSION_LINK + "\n"), "allow")
        self.assertAction(write(handle.name, "New https://claude.ai/chat/other\n"), "block")

    def test_test_sources_are_exempt(self):
        for path in (".claude/hooks/lib/test_private_url_check.py",
                     "flowtree/runtime/src/test/java/io/flowtree/jobs/AuthorAttributionTest.java",
                     "engine/utils/src/main/java/x/FooTests.java",
                     ".opencode/plugins/__tests__/block-private-url.test.ts",
                     ".opencode/plugins/__tests__/block-private-url.smoke.cjs"):
            self.assertTrue(check.is_test_source(path), path)
            self.assertAction(write(path, SESSION_LINK), "allow")

    def test_a_test_named_production_source_is_not_exempt(self):
        for path in ("src/main/java/x/Testing.java", "docs/test-examples.md",
                     "tools/attest.py"):
            self.assertFalse(check.is_test_source(path), path)
            self.assertAction(write(path, SESSION_LINK), "block")

    def test_commit_txt_is_left_to_the_private_link_hook(self):
        self.assertAction(write("commit.txt", SESSION_LINK), "allow")

    def test_other_tools_are_allowed(self):
        self.assertAction({"tool_name": "Bash", "tool_input": {"command": "echo " + SESSION_LINK}},
                          "allow")
        self.assertAction({"tool_name": "Read", "tool_input": {"file_path": "x"}}, "allow")


class EntryPointTests(unittest.TestCase):
    """The --stdin hook contract and the opencode JSON-argument contract."""

    def test_stdin_blocks_with_exit_2(self):
        err = io.StringIO()
        status = check.main(["--stdin"], io.StringIO(json.dumps(write("a.md", SESSION_LINK))), err)
        self.assertEqual(2, status)
        self.assertIn("vendor", err.getvalue())

    def test_stdin_allows_with_exit_0_and_ignores_bad_json(self):
        self.assertEqual(0, check.main(["--stdin"], io.StringIO(json.dumps(write("a.md", "x"))),
                                       io.StringIO()))
        self.assertEqual(0, check.main(["--stdin"], io.StringIO("not json"), io.StringIO()))

    def test_wrapper_blocks_with_exit_2(self):
        result = subprocess.run(["bash", WRAPPER], input=json.dumps(write("a.md", SESSION_LINK)),
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("vendor", result.stderr)

    def test_the_opencode_entry_point_prints_the_decision(self):
        for payload, action in ((write("a.md", SESSION_LINK), "block"), (write("a.md", "x"), "allow")):
            result = subprocess.run(["python3", CORE_PATH, json.dumps(payload)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode)
            self.assertEqual(action, json.loads(result.stdout)["action"])


class RegistrationTests(unittest.TestCase):
    """The hook is registered for every route it judges."""

    def test_registered_for_each_route(self):
        with open(SETTINGS) as handle:
            entries = json.load(handle)["hooks"]["PreToolUse"]
        matchers = [entry["matcher"] for entry in entries
                    if any(h["command"].endswith("block-vendor-promotion.sh")
                           for h in entry["hooks"])]
        self.assertTrue(matchers)
        for tool in ("Write", "Edit", "MultiEdit", "mcp__claude_ai_Claude_Docs__batch",
                     "mcp__claude_ai_Claude_Docs__create", "mcp__claude_ai_Claude_Docs__update"):
            self.assertTrue(any(re.fullmatch(m, tool) for m in matchers), tool)


if __name__ == "__main__":
    unittest.main()
