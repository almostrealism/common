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
from unittest import mock

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

    def test_configured_default_branch_is_refused(self):
        self.assertEqual(["branch"], [p.check for p in commit_check.branch_problems("develop", {"develop"})])
        self.assertEqual([], commit_check.branch_problems("develop"))


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

    def test_plan_ledger_is_read_from_the_index_not_the_working_tree(self):
        ledger = self.root / "docs" / "qa" / "retrospectives" / "ledger.tsv"
        ledger.parent.mkdir(parents=True)
        ledger.write_text("proposal_ids\tnote\nfoo-proposal\tx\n")
        self.git("add", "docs/qa/retrospectives/ledger.tsv")
        self.git("commit", "-q", "-m", "Add ledger")
        # An unstaged deletion of the tracked ledger must neither abort the check
        # nor drop the slug it defines: the slug is read from the staged index.
        ledger.unlink()
        self.stage("mentions foo-proposal here\n")
        (self.root / "commit.txt").write_text("Change a\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("temporary id: a.txt:1") for p in result["problems"]), result)

    def test_tracked_commit_txt_is_refused(self):
        # A repository that already tracks a commit.txt must not be committed
        # through: a successful commit would unlink that tracked file, leaving an
        # uncommitted deletion of committed content.
        (self.root / "commit.txt").write_text("Change a\n")
        self.git("add", "commit.txt")
        self.git("commit", "-q", "-m", "Track a commit.txt")
        self.stage("two\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any("tracked file" in p for p in result["problems"]), result)

    def test_product_name_in_plan_file_added_line_is_refused(self):
        # A plan path is exempt from the temporary-identifier check (plans define
        # those identifiers), but never from the private-product check.
        plan = self.root / "docs" / "plans" / "PLAN-20260101-example.md"
        plan.parent.mkdir(parents=True)
        plan.write_text("# Plan\n\nBuilt for AcmeApp.\n")
        self.git("add", "docs/plans/PLAN-20260101-example.md")
        (self.root / "commit.txt").write_text("Change a\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("private product: docs/plans/PLAN-20260101-example.md:3")
                            for p in result["problems"]), result)

    def test_working_tree_generated_header_does_not_hide_staged_identifier(self):
        # scan_added judges whether a file is generated from the staged snapshot,
        # not the working tree: an unstaged @generated header must not suppress a
        # temporary identifier on the staged lines.
        source = self.root / "notes.txt"
        source.write_text("header\n")
        self.git("add", "notes.txt")
        self.git("commit", "-q", "-m", "Add notes")
        source.write_text("header\ncommit 7f3e9a21b4c8 landed\n")  # temporary-identifier-ok: fixture the check must find
        self.git("add", "notes.txt")
        source.write_text("@generated\nheader\ncommit 7f3e9a21b4c8 landed\n")  # temporary-identifier-ok: fixture the check must find
        (self.root / "commit.txt").write_text("Change notes\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("temporary id: notes.txt") for p in result["problems"]), result)

    def test_remote_default_branch_is_refused(self):
        # A repository whose default branch is not master or main records it as
        # the target of the remote's HEAD; committing on it is refused too.
        self.git("update-ref", "refs/remotes/origin/develop", "HEAD")
        self.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/develop")
        self.git("checkout", "-q", "-b", "develop")
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertIn("branch: develop: is a default branch; commit on a feature branch", result["problems"])
        self.git("checkout", "-q", "feature/change")
        self.assertTrue(server.check_commit(str(self.root))["ok"])

    def test_failed_staged_diff_refuses_the_commit(self):
        # An inspection that git cannot complete must refuse the commit rather
        # than check an empty diff while the staged change is still committed.
        self.stage("made for AcmeApp\n")
        (self.root / "commit.txt").write_text("Change a\n")
        head = self.git("rev-parse", "HEAD")
        real_git = server._git

        def failing_diff(root, *args, stdin=None):
            if args[:2] == ("diff", "--cached") and "--name-only" not in args:
                return subprocess.CompletedProcess(["git", *args], 1, "", "external diff died")
            return real_git(root, *args, stdin=stdin)

        with mock.patch.object(server, "_git", failing_diff):
            checked = server.check_commit(str(self.root))
            committed = server.commit(str(self.root))

        self.assertFalse(checked["ok"])
        self.assertIn("external diff died", checked["error"])
        self.assertFalse(committed["ok"])
        self.assertIn("external diff died", committed["error"])
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertTrue((self.root / "commit.txt").exists())

    def test_git_timeout_refuses_the_commit(self):
        # A git command (a commit hook, say) that outlives the timeout must be
        # reported as a structured error, not raised out of the tool, and must
        # leave HEAD and commit.txt untouched.
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n")
        head = self.git("rev-parse", "HEAD")
        real_run = subprocess.run

        def timing_out_commit(argv, *args, **kwargs):
            if "commit" in argv:
                raise subprocess.TimeoutExpired(argv, server.GIT_TIMEOUT_SECONDS)
            return real_run(argv, *args, **kwargs)

        with mock.patch.object(server.subprocess, "run", timing_out_commit):
            result = server.commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertFalse(result["committed"])
        self.assertIn("timed out", result["error"])
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertTrue((self.root / "commit.txt").exists())

    def test_git_timeout_with_captured_bytes_refuses_the_commit(self):
        # TimeoutExpired carries the captured output as raw bytes on POSIX even
        # under text=True, so the timeout branch must decode it rather than
        # concatenate str and bytes (which would raise TypeError out of the tool).
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n")
        head = self.git("rev-parse", "HEAD")
        real_run = subprocess.run

        def timing_out_commit(argv, *args, **kwargs):
            if "commit" in argv:
                raise subprocess.TimeoutExpired(argv, server.GIT_TIMEOUT_SECONDS,
                                                output=b"partial out",
                                                stderr=b"hook noise")
            return real_run(argv, *args, **kwargs)

        with mock.patch.object(server.subprocess, "run", timing_out_commit):
            result = server.commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertFalse(result["committed"])
        self.assertIn("timed out", result["error"])
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertTrue((self.root / "commit.txt").exists())

    def test_staged_message_file_is_refused(self):
        self.stage("two\n")
        (self.root / "commit.txt").write_text("Change a\n")
        self.git("add", "commit.txt")

        result = server.check_commit(str(self.root))

        self.assertTrue(any("must not be part of the commit" in p for p in result["problems"]), result)


class RepositoryNameTest(unittest.TestCase):
    """The repository name is read from either origin URL form."""

    def test_scp_form(self):
        self.assertEqual("acme-private",
                         commit_check.repository_name("git@github.com:acme/acme-private.git"))

    def test_https_form_with_suffix(self):
        self.assertEqual("acme-private",
                         commit_check.repository_name("https://github.com/acme/acme-private.git"))

    def test_https_form_without_suffix(self):
        self.assertEqual("acme-private",
                         commit_check.repository_name("https://github.com/acme/acme-private"))

    def test_trailing_slash_after_suffix(self):
        self.assertEqual("acme-private",
                         commit_check.repository_name("https://github.com/acme/acme-private.git/"))

    def test_trailing_slash_without_suffix(self):
        self.assertEqual("acme-private",
                         commit_check.repository_name("https://github.com/acme/acme-private/"))

    def test_empty_url_has_no_name(self):
        self.assertIsNone(commit_check.repository_name(""))

    def test_matching_is_case_insensitive(self):
        self.assertTrue(commit_check.names_own_repository("acmestudio", ["AcmeStudio"]))
        self.assertTrue(commit_check.names_own_repository("AcmeStudio", ["acmestudio"]))
        self.assertFalse(commit_check.names_own_repository("some-other-repo", ["acme-private"]))
        self.assertFalse(commit_check.names_own_repository(None, ["acme-private"]))


class RepositoryScopingTest(CommitToolTest):
    """The product-name check is skipped only in a private product's own repository."""

    def configure(self, names: str, origin: str = None):
        """Sets the configured product names, and adds an ``origin`` remote when given."""
        os.environ[commit_check.PRIVATE_NAMES_VARIABLE] = names
        if origin is not None:
            self.git("remote", "add", "origin", origin)

    def test_matching_repository_skips_product_check(self):
        self.configure("acme-private,AcmeStudio", "git@github.com:acme/acme-private.git")
        self.stage("made for acme-private\n")
        (self.root / "commit.txt").write_text("Support acme-private\n\nThe acme-private renderer.\n")

        result = server.check_commit(str(self.root))

        self.assertTrue(result["ok"], result)
        self.assertTrue(any("acme-private" in w for w in result["warnings"]), result)

    def test_matching_repository_still_enforces_other_checks(self):
        self.configure("acme-private", "https://github.com/acme/acme-private.git")
        self.stage("made for acme-private\n")
        (self.root / "commit.txt").write_text("Support it\n\nCo-Authored-By: Someone <a@b.c>\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("attribution") for p in result["problems"]), result)
        self.assertFalse(any(p.startswith("private product") for p in result["problems"]), result)

    def test_non_matching_repository_enforces_product_check(self):
        self.configure("acme-private", "git@github.com:acme/some-other-repo.git")
        self.stage("made for acme-private\n")
        (self.root / "commit.txt").write_text("Change a\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("private product: a.txt:1") for p in result["problems"]), result)

    def test_no_origin_remote_enforces_product_check(self):
        self.configure("acme-private")
        self.stage("made for acme-private\n")
        (self.root / "commit.txt").write_text("Change a\n")

        result = server.check_commit(str(self.root))

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("private product: a.txt:1") for p in result["problems"]), result)

    def test_global_origin_does_not_bypass_product_check(self):
        # The origin lookup is scoped to the repository-local config, so a
        # global (or system) remote.origin.url inherited from the invoking
        # environment cannot stand in for a missing local origin and silently
        # disable the guard: a repository with no local origin stays fail-closed.
        self.configure("acme-private")
        global_config = self.root / "global.gitconfig"
        global_config.write_text('[remote "origin"]\n\turl = git@github.com:acme/acme-private.git\n')
        self.stage("made for acme-private\n")
        (self.root / "commit.txt").write_text("Change a\n")
        saved = os.environ.get("GIT_CONFIG_GLOBAL")
        os.environ["GIT_CONFIG_GLOBAL"] = str(global_config)
        try:
            result = server.check_commit(str(self.root))
        finally:
            if saved is None:
                os.environ.pop("GIT_CONFIG_GLOBAL", None)
            else:
                os.environ["GIT_CONFIG_GLOBAL"] = saved

        self.assertFalse(result["ok"])
        self.assertTrue(any(p.startswith("private product: a.txt:1") for p in result["problems"]), result)

    def test_case_insensitive_repository_match_skips_product_check(self):
        self.configure("AcmeStudio", "https://github.com/acme/acmestudio.git")
        self.stage("made for AcmeStudio\n")
        (self.root / "commit.txt").write_text("Support AcmeStudio\n")

        result = server.check_commit(str(self.root))

        self.assertTrue(result["ok"], result)
        self.assertTrue(any("acmestudio" in w.lower() for w in result["warnings"]), result)


if __name__ == "__main__":
    unittest.main()
