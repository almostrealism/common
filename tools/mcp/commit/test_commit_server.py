"""Tests for the ar-commit server and the checks in commit_check.

The server module is loaded from its explicit path under a directory-specific
name, because several ``tools/mcp`` directories define a top-level
``server.py`` (see ``common/test_layout.py``).
"""

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_SERVER_DIR = Path(__file__).resolve().parent
if str(_SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVER_DIR))

import commit_check  # noqa: E402

_spec = importlib.util.spec_from_file_location("commit_server_under_test", str(_SERVER_DIR / "server.py"))
server = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(server)

PRODUCTS = commit_check.private_product_pattern(["acmeapp", "Widget"])
"""Stand-in private product names: one matched in any case, one only as written."""


def message_checks(message: str) -> list[str]:
    """Returns the check names of the problems found in ``message``."""
    return [p.check for p in commit_check.message_problems(message, Path("."), [], PRODUCTS)]


class AttributionTest(unittest.TestCase):
    """Author attribution is refused wherever it appears in the message."""

    def test_trailer_is_refused(self):
        self.assertIn("attribution", message_checks("Fix it\n\nBody.\n\nCo-Authored-By: Someone <a@b.c>\n"))

    def test_tool_credit_is_refused(self):
        self.assertIn("attribution", message_checks("Fix it\n\n🤖 Generated with Claude Code\n"))

    def test_identity_line_is_refused(self):
        self.assertIn("attribution", message_checks("Fix it\n\n<noreply@anthropic.com>\n"))

    def test_inline_credit_is_refused(self):
        self.assertIn("attribution", message_checks("Fix it\n\nThis was written by ChatGPT overnight.\n"))

    def test_ordinary_prose_is_accepted(self):
        self.assertEqual([], message_checks(
            "Fix the reader\n\nThe table was written by hand and created with the new script.\n"))


class MessageContentTest(unittest.TestCase):
    """The other message rules: subject, private links, temporary ids, product names."""

    def test_blank_subject_is_refused(self):
        self.assertIn("message", message_checks("\nBody only.\n"))

    def test_private_link_is_refused(self):
        self.assertIn("private link", message_checks(
            "Fix it\n\nhttps://claude.ai/code/session_0123456789abcdefABCDEF\n"))

    def test_identifier_beside_job_is_refused(self):
        self.assertIn("temporary id", message_checks("Fix it\n\nAs found by job 7f3e9a21b4c8.\n"))  # temporary-identifier-ok: fixture the check must find

    def test_lower_case_product_name_matches_any_case(self):
        self.assertIn("private product", message_checks("Fix it\n\nSeen in AcmeApp.\n"))

    def test_capitalized_product_name_matches_only_as_written(self):
        self.assertIn("private product", message_checks("Fix it\n\nSeen in Widget.\n"))
        self.assertEqual([], message_checks("Fix it\n\nEvery widget redraws.\n"))

    def test_no_names_configured_checks_no_names(self):
        self.assertIsNone(commit_check.private_product_pattern(commit_check.private_product_names("")))


class BranchTest(unittest.TestCase):
    """A commit needs a feature branch."""

    def test_detached_and_default_branches_are_refused(self):
        self.assertEqual(["branch"], [p.check for p in commit_check.branch_problems("")])
        self.assertEqual(["branch"], [p.check for p in commit_check.branch_problems("master")])
        self.assertEqual([], commit_check.branch_problems("feature/x"))


class CommitToolTest(unittest.TestCase):
    """The commit tool against a scratch repository."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self._environment = os.environ.get(commit_check.PRIVATE_NAMES_VARIABLE)
        os.environ[commit_check.PRIVATE_NAMES_VARIABLE] = "acmeapp"
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.com")
        (self.root / "a.txt").write_text("one\n")
        self.git("add", "a.txt")
        self.git("commit", "-q", "-m", "Initial")
        self.git("checkout", "-q", "-b", "feature/change")

    def tearDown(self):
        if self._environment is None:
            os.environ.pop(commit_check.PRIVATE_NAMES_VARIABLE, None)
        else:
            os.environ[commit_check.PRIVATE_NAMES_VARIABLE] = self._environment
        self._tmp.cleanup()

    def git(self, *args: str) -> str:
        """Runs git in the scratch repository and returns its output."""
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              capture_output=True, text=True).stdout

    def stage(self, text: str):
        """Changes the tracked file and stages it."""
        (self.root / "a.txt").write_text(text)
        self.git("add", "a.txt")

    def test_commit_uses_the_message_and_removes_it(self):
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n\nIt says two now.\n")

        result = server.commit(str(self.root))

        self.assertTrue(result["committed"], result)
        self.assertEqual("Change a", result["subject"])
        self.assertEqual("Change a\n\nIt says two now.\n", self.git("log", "-1", "--format=%B").rstrip("\n") + "\n")
        self.assertFalse((self.root / "commit.txt").exists())

    def test_refused_commit_changes_nothing(self):
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n\nCo-Authored-By: Someone <a@b.c>\n")
        head = self.git("rev-parse", "HEAD")

        result = server.commit(str(self.root))

        self.assertFalse(result["committed"])
        self.assertTrue(any(p.startswith("attribution") for p in result["problems"]), result)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertTrue((self.root / "commit.txt").exists())

    def test_product_name_in_added_line_is_refused(self):
        self.stage("made for AcmeApp\n")
        (self.root / "commit.txt").write_text("Change a\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("private product: a.txt:1") for p in result["problems"]), result)

    def test_missing_message_and_empty_change_are_refused(self):
        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any("nothing is staged" in p for p in result["problems"]), result)
        self.assertTrue(any("does not exist" in p for p in result["problems"]), result)

    def test_staged_message_file_is_refused(self):
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n")
        self.git("add", "commit.txt")

        result = server.check_commit(str(self.root))

        self.assertTrue(any("must not be part of the commit" in p for p in result["problems"]), result)


if __name__ == "__main__":
    unittest.main()
