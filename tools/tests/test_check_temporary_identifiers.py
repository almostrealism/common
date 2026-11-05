"""Tests for scripts/check-temporary-identifiers.py and its CI wiring.

The fixtures use a made-up document family (``SURVEY``) and dates far in the
future, so nothing in this file is itself an identifier of a real plan
document and the check run over the repository stays clean.
"""

import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "check-temporary-identifiers.py"
WORKFLOW = ROOT / ".github" / "workflows" / "analysis.yaml"


def _load():
    spec = importlib.util.spec_from_file_location("check_temporary_identifiers", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECK = _load()

LEDGER = ("run_utc\tbranch\tproposal_ids\treport_path\n"
          "2099-01-01T00:00:00Z\tqa/x\tsample-slug,other-proposal\tdocs/qa/retrospectives/SURVEY-20990101.md\n")


class CheckerTestBase(unittest.TestCase):
    """Builds a scratch checkout with one survey, one plan and a ledger."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.files = []
        self.write("docs/qa/retrospectives/SURVEY-20990101.md", "# Survey\n## P3 cap the loop\n")
        self.write("docs/qa/retrospectives/ledger.tsv", LEDGER)
        self.write("docs/plans/SURVEY-20990202-widget-cache.md", "# Plan\n")
        self.write("docs/plans/README.md", "plain index\n")

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, path, text):
        """Writes a tracked file into the scratch checkout."""
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(text, bytes):
            target.write_bytes(text)
        else:
            target.write_text(text, encoding="utf-8")
        self.files.append(path)

    def found(self):
        """Returns (path, line, text) for every violation in the checkout."""
        return [(v.path, v.line, v.text) for v in CHECK.check(self.root, self.files)]


class DiscoveryTests(CheckerTestBase):
    """What the checker reads from the plan documents."""

    def test_a_dated_document_name_is_flagged(self):
        self.write("src/A.java", "// see SURVEY-20990101 for details\n")
        self.assertIn(("src/A.java", 1, "SURVEY-20990101"), self.found())

    def test_a_plan_file_name_is_flagged_with_or_without_extension(self):
        self.write("README.md", "x\nper docs/plans/SURVEY-20990202-widget-cache.md\n"
                   "and SURVEY-20990202-widget-cache.\n")
        hits = [h for h in self.found() if h[2] == "SURVEY-20990202-widget-cache"]
        self.assertEqual([("README.md", 2), ("README.md", 3)], [h[:2] for h in hits])

    def test_an_item_label_is_flagged_with_and_without_a_date(self):
        self.write("ci.yaml", "# the gate (SURVEY P3) and SURVEY-20990101 F6 both\n")
        texts = [h[2] for h in self.found() if h[0] == "ci.yaml"]
        self.assertIn("SURVEY P3", texts)
        self.assertIn("SURVEY-20990101 F6", texts)

    def test_an_item_label_wrapped_onto_a_comment_line_is_flagged(self):
        self.write("run.sh", "#!/bin/sh\n# what it broke (SURVEY\n# P2, sample-slug).\n")
        self.assertIn(("run.sh", 2, "SURVEY # P2"), self.found())

    def test_a_ledger_slug_is_flagged_only_as_a_whole_token(self):
        self.write("a.py", '"""sample-slug here."""\n')
        self.write("b.py", "x = 'sample-slug-extended'  # xsample-slug\n")
        self.assertEqual([("a.py", 1, "sample-slug")],
                         [h for h in self.found() if h[2] == "sample-slug"])

    def test_every_listed_slug_is_read(self):
        self.write("a.md", "other-proposal\n")
        self.assertEqual([("a.md", 1, "other-proposal")], self.found())


class NonViolationTests(CheckerTestBase):
    """What the checker must leave alone."""

    def test_a_clean_checkout_has_no_violations(self):
        self.write("src/A.java", "class A { }\n")
        self.assertEqual([], self.found())

    def test_the_plan_directories_are_not_scanned(self):
        self.write("docs/plans/notes.md", "SURVEY P3 and sample-slug, SURVEY-20990101\n")
        self.assertEqual([], self.found())

    def test_a_synthetic_name_that_is_no_real_document_is_allowed(self):
        self.write("tools/test_x.py", 'path = "docs/plans/SURVEY-20990303-thing.md"\n'
                   'other = "SURVEY-20990202-widget"\n')
        self.assertEqual([], self.found())

    def test_a_name_template_is_allowed(self):
        self.write("prompt.txt", "Create SURVEY-<YYYYMMDD>.md; read SURVEY-*.md\n")
        self.assertEqual([], self.found())

    def test_an_undated_plan_family_word_alone_is_allowed(self):
        self.write("doc.md", "A SURVEY report, and the SURVEYS P3 heading.\n")
        self.assertEqual([], self.found())

    def test_a_binary_file_is_skipped(self):
        self.write("img.bin", b"\0SURVEY-20990101 sample-slug")
        self.assertEqual([], self.found())

    def test_a_listed_file_that_does_not_exist_is_skipped(self):
        self.files.append("deleted.md")
        self.assertEqual([], self.found())


class LedgerTests(unittest.TestCase):
    """Reading the slug column of a retrospective ledger."""

    def test_slugs_are_split_and_trimmed(self):
        text = "a\tproposal_ids\n1\t x-y , z-w,,\n2\n"
        self.assertEqual(["x-y", "z-w"], CHECK.ledger_slugs(text))

    def test_an_empty_ledger_has_no_slugs(self):
        self.assertEqual([], CHECK.ledger_slugs(""))

    def test_a_ledger_without_the_column_is_an_error(self):
        with self.assertRaises(ValueError):
            CHECK.ledger_slugs("a\tb\n1\t2\n")


class MainTests(CheckerTestBase):
    """The command-line contract: file list on stdin, exit code verdict."""

    def run_main(self, stdin):
        out = io.StringIO()
        err = io.StringIO()
        fake = io.TextIOWrapper(io.BytesIO(stdin))
        with mock.patch("sys.stdin", fake), contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            code = CHECK.main(["--root", str(self.root)])
        return code, out.getvalue(), err.getvalue()

    def file_list(self):
        return "\0".join(self.files).encode("utf-8") + b"\0"

    def test_a_clean_tree_exits_zero(self):
        code, out, _ = self.run_main(self.file_list())
        self.assertEqual(0, code)
        self.assertIn("No plan-document identifiers", out)

    def test_a_violation_exits_one_and_names_file_line_and_source(self):
        self.write("src/A.java", "x\n// SURVEY P3\n")
        code, out, _ = self.run_main(self.file_list())
        self.assertEqual(1, code)
        self.assertIn("src/A.java:2: 'SURVEY P3'", out)
        self.assertIn("1 temporary identifier(s) found", out)

    def test_no_file_list_exits_two(self):
        code, _, err = self.run_main(b"")
        self.assertEqual(2, code)
        self.assertIn("no file list", err)

    def test_an_unreadable_ledger_exits_two(self):
        self.write("docs/qa/retrospectives/ledger.tsv", "a\tb\n")
        code, _, err = self.run_main(self.file_list())
        self.assertEqual(2, code)
        self.assertIn("could not run", err)


class WorkflowWiringTests(unittest.TestCase):
    """The check runs in the checkstyle job and reaches auto-review."""

    @classmethod
    def setUpClass(cls):
        cls.jobs = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))["jobs"]

    def test_the_checkstyle_job_runs_the_check_and_publishes_its_verdict(self):
        job = self.jobs["checkstyle"]
        steps = [s for s in job["steps"] if s.get("id") == "identifiers"]
        self.assertEqual(1, len(steps))
        self.assertIn("git ls-files -z | python3 scripts/check-temporary-identifiers.py",
                      steps[0]["run"])
        self.assertIn("!cancelled()", steps[0]["if"])
        self.assertIn("steps.identifiers.outcome", job["outputs"]["identifiers_passed"])

    def test_auto_review_passes_the_verdict_to_the_quality_gates(self):
        steps = [s for s in self.jobs["auto-review"]["steps"] if s.get("id") == "quality"]
        self.assertEqual(1, len(steps))
        self.assertEqual("${{ needs.checkstyle.outputs.identifiers_passed }}",
                         steps[0]["env"]["TEMPORARY_IDENTIFIERS_PASSED"])
        self.assertIn("checkstyle", self.jobs["auto-review"]["needs"])

    def test_the_check_judges_added_lines_against_the_base_branch(self):
        step = [s for s in self.jobs["checkstyle"]["steps"] if s.get("id") == "identifiers"][0]
        self.assertIn('git diff --merge-base "origin/${BASE_BRANCH}" -M --unified=3', step["run"])
        self.assertIn('> "${RUNNER_TEMP}/change.diff"', step["run"])
        self.assertIn('check-temporary-identifiers.py --diff "${RUNNER_TEMP}/change.diff"', step["run"])
        self.assertEqual("${{ needs.changes.outputs.base_branch }}", step["env"]["BASE_BRANCH"])
        self.assertIn("changes", self.jobs["checkstyle"]["needs"])

    def test_the_script_runs_no_commands_of_its_own(self):
        """Agents run the script locally, where the exfiltration guard refuses a
        Python program that can start a process; the caller supplies the diff."""
        self.assertNotRegex(SCRIPT.read_text(encoding="utf-8"),
                            r"\bimport\s+subprocess\b|\bos\.system\b|\bos\.popen\b")


# The generic-shape fixtures are assembled from fragments shorter than any
# token the rule looks at, so that this file, whose added lines the rule
# judges like any other, carries no identifier itself.
HEX_ID = "".join(("7f3a", "91c2"))
SHORT_SHA = "".join(("a5e2", "7d194"))
UUID = "".join(("3b8f9e", "21-6c4d", "-4a7e-", "9f02-", "d5c81e", "7a6b39"))
ROUND_BRANCH = "".join(("qa/flaky-", "20991104", "-135711"))
MD5_OF_HELLO = "".join(("5d4140", "2abc4b", "2a76b9", "719d91", "1017c5", "92"))
SHA_WITH_ZEROS_INSIDE = "".join(("9c41e7", "b2d58a", "3f6e19", "c70000", "4b8d2e", "a17f53", "c6e9"))
ON_THE_HOUR_BRANCH = "".join(("qa/flaky-", "20991104", "-120000"))
SHA1_OF_ABC = "".join(("a9993e", "364706", "816aba", "3e2571", "7850c2", "6c9cd0", "d89d"))


def generic(text, preceding="", following=""):
    """Returns the tokens the generic rule flags on one added line."""
    return [v.text for v in CHECK.generic_identifiers("f.txt", 1, text, preceding, following)]


class IdentifyingContextTests(unittest.TestCase):
    """A hexadecimal token is an identifier only when the words around it say so."""

    def test_a_tracker_task_id_is_flagged(self):
        self.assertEqual([HEX_ID], generic(f"API decision tracked separately (tracker task `{HEX_ID}`)."))

    def test_an_id_shaped_field_or_argument_is_flagged(self):
        self.assertEqual([HEX_ID], generic(f'job_id = "{HEX_ID}"'))
        self.assertEqual([HEX_ID], generic(f'submit(workstreamId, "{HEX_ID}");'))
        self.assertEqual([UUID], generic(f'recall(memoryId="{UUID}")'))

    def test_a_short_commit_sha_is_flagged(self):
        self.assertEqual([SHORT_SHA], generic(f"Fixed by commit {SHORT_SHA}, which reordered the queue."))
        self.assertEqual([SHORT_SHA], generic(f"see https://example.invalid/commit/{SHORT_SHA}"))

    def test_an_id_listed_below_the_words_that_name_it_is_flagged(self):
        preceding = ("2. Search the tracker for known flaky tests and read the open tasks.\n"
                     "   Known leads at the time of writing include:")
        self.assertEqual([HEX_ID], generic(f"   `{HEX_ID}` (a GPU media lane), and", preceding))

    def test_an_assertion_on_a_listed_id_is_flagged(self):
        preceding = '        self.assertIn("tracker_search_tasks", text)'
        self.assertEqual([HEX_ID, SHORT_SHA],
                         generic(f'        for lead in ("{HEX_ID}", "{SHORT_SHA}"):', preceding))

    def test_an_uppercase_id_is_flagged(self):
        self.assertEqual([UUID.upper()], generic(f'recall(memoryId="{UUID.upper()}")'))
        self.assertEqual([HEX_ID.upper()], generic(f"tracker task {HEX_ID.upper()} is open"))

    def test_a_long_sha_holding_a_placeholder_run_past_its_prefix_is_flagged(self):
        self.assertEqual([SHA_WITH_ZEROS_INSIDE],
                         generic(f"Reverted commit {SHA_WITH_ZEROS_INSIDE} after the regression."))

    def test_a_round_branch_started_on_the_hour_is_flagged(self):
        self.assertEqual([ON_THE_HOUR_BRANCH], generic(f"rebased onto {ON_THE_HOUR_BRANCH}"))

    def test_a_round_branch_started_at_an_ascending_time_other_than_123456_is_flagged(self):
        branch = "".join(("qa/flaky-", "20991104", "-012345"))
        self.assertEqual([branch], generic(f"rebased onto {branch}"))

    def test_a_timestamped_round_branch_is_flagged_without_any_context(self):
        self.assertEqual([ROUND_BRANCH], generic(f'BRANCH = "{ROUND_BRANCH}"'))
        self.assertEqual([f"origin/{ROUND_BRANCH}"], generic(f"diff against origin/{ROUND_BRANCH}"))


class DataTokenTests(unittest.TestCase):
    """Hexadecimal data that is not an identifier is left alone."""

    def test_a_hash_is_not_flagged(self):
        self.assertEqual([], generic(f'expected_md5 = "{MD5_OF_HELLO}"  # md5 of "hello"'))

    def test_a_test_vector_is_not_flagged(self):
        self.assertEqual([], generic(f'assertEquals("{SHA1_OF_ABC}", hex(sha1("abc")));'))

    def test_a_colour_and_a_numeric_literal_are_not_flagged_even_beside_an_id_word(self):
        self.assertEqual([], generic(f'<div id="x" style="color: #{HEX_ID}">'))
        self.assertEqual([], generic(f"int jobMask = 0x{HEX_ID};"))

    def test_numbers_dates_and_words_are_not_ids(self):
        self.assertEqual([], generic("task 20991104 ran 12345678 times; job decaded"))

    def test_plainly_made_up_fixture_values_are_not_flagged(self):
        self.assertEqual([], generic('job_id = "1234abcd"; commit = "cafe9e71b"'))
        self.assertEqual([], generic('task = "00000000-0000-0000-0000-000000000000"'))
        self.assertEqual([], generic('BRANCH = "qa/defect-' + '20991104-000000"'))

    def test_uppercase_fixtures_and_a_timestamp_of_123456_are_not_flagged(self):
        self.assertEqual([], generic('job_id = "DEADBEEF"; task = "1234ABCD9"'))
        self.assertEqual([], generic('BRANCH = "qa/defect-' + '20991104-123456"'))

    def test_a_mixed_case_run_is_a_name_not_an_id(self):
        self.assertEqual([], generic('job_id = "' + "Ab12" + 'Cd3e"'))

    def test_durable_anchors_are_not_flagged(self):
        self.assertEqual([], generic("Merged with PR #836 (task: NodeWorkerWakeTest in flowtree/runtime)."))


class SuppressionTests(unittest.TestCase):
    """The inline marker hides a genuine case, but only with a reason."""

    def test_a_suppression_with_a_reason_is_honoured(self):
        self.assertEqual([], generic(
            f'commit = "{SHORT_SHA}"  # temporary-identifier-ok: fixture for the git parser test'))

    def test_a_one_word_reason_is_enough(self):
        self.assertEqual([], generic(f'commit = "{SHORT_SHA}"  # temporary-identifier-ok: generated'))

    def test_a_marker_followed_only_by_punctuation_has_no_reason(self):
        found = generic(f"<!-- commit {SHORT_SHA} temporary-identifier-ok: -->")
        self.assertEqual([SHORT_SHA], found)
        self.assertEqual(CHECK.UNREASONED_SOURCE, CHECK.generic_identifiers(
            "f.md", 1, f"<!-- commit {SHORT_SHA} temporary-identifier-ok: -->")[0].source)

    def test_a_suppression_without_a_reason_is_reported(self):
        found = CHECK.generic_identifiers("f.py", 3, f'commit = "{SHORT_SHA}"  # temporary-identifier-ok:')
        self.assertEqual([("f.py", 3, SHORT_SHA, CHECK.UNREASONED_SOURCE)], found)

    def test_a_marker_on_a_clean_line_is_not_a_finding(self):
        self.assertEqual([], generic("write temporary-identifier-ok: <reason> on the line"))


class DiffParseTests(unittest.TestCase):
    """Reading the new side of a unified diff."""

    def test_added_and_context_lines_carry_their_new_line_numbers(self):
        diff = ("diff --git a/x.py b/x.py\n--- a/x.py\n+++ b/x.py\n"
                "@@ -3,1 +3,3 @@ def f():\n ctx\n+one\n+++ two\n"
                "@@ -9 +11 @@\n-old\n+new\n"
                "diff --git a/gone.py b/gone.py\n--- a/gone.py\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n"
                "diff --git a/new.md b/new.md\nnew file mode 100644\n--- /dev/null\n+++ b/new.md\n"
                "@@ -0,0 +1 @@\n+first\n\\ No newline at end of file\n")
        line = CHECK.DiffLine
        self.assertEqual({"x.py": {3: line("ctx", False), 4: line("one", True),
                                   5: line("++ two", True), 11: line("new", True)},
                          "new.md": {1: line("first", True)}},
                         CHECK.parse_diff(diff))

    def test_context_lines_are_read_as_neighbours_but_never_judged(self):
        diff = ("diff --git a/a.md b/a.md\n--- a/a.md\n+++ b/a.md\n"
                f"@@ -1,2 +1,3 @@\n The open tracker tasks:\n `{HEX_ID}` is pre-existing.\n+`{SHORT_SHA}` is new.\n")
        found = CHECK.diff_identifiers("a.md", CHECK.parse_diff(diff)["a.md"])
        self.assertEqual([("a.md", 3, SHORT_SHA)], [v[:3] for v in found])


class GitCheckoutTestBase(unittest.TestCase):
    """Builds a scratch git repository with a base commit and a feature branch."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.git("init", "-q", "-b", "base")
        self.write("docs/notes.md", f"# Notes\n\nOld tracker task `{HEX_ID}` is pre-existing.\n")
        self.write("src/Old.java", "class Old { }\n")
        self.commit()
        self.git("checkout", "-q", "-b", "feature")

    def tearDown(self):
        self._tmp.cleanup()

    def git(self, *args):
        """Runs git in the scratch repository with a fixed identity and returns its output."""
        return subprocess.run(["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                               "-c", "commit.gpgsign=false", *args], cwd=self.root, check=True,
                              capture_output=True, text=True).stdout

    def change_diff(self, base="base"):
        """Returns the diff CI hands the script: the working tree against the merge base."""
        return self.git("diff", "--merge-base", base, "-M", "--unified=3", "--no-color")

    def write(self, path, text):
        """Writes a file into the scratch repository and stages it."""
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        self.git("add", path)

    def commit(self):
        """Commits everything staged."""
        self.git("commit", "-q", "-m", "change")

    def found(self):
        """Returns (path, line, text) for every violation the change adds."""
        files = CHECK.parse_file_list(self.git("ls-files", "-z").encode("utf-8"))
        return [(v.path, v.line, v.text) for v in CHECK.check(self.root, files, self.change_diff())]


class AddedLineScopeTests(GitCheckoutTestBase):
    """Only what the change adds is judged."""

    def test_a_pre_existing_line_is_ignored(self):
        self.write("docs/notes.md", f"# Notes\n\nOld tracker task `{HEX_ID}` is pre-existing.\nMore.\n")
        self.commit()
        self.assertEqual([], self.found())

    def test_an_added_line_is_reported_with_its_line_number(self):
        self.write("docs/notes.md", f"# Notes\n\nOld tracker task `{HEX_ID}` is pre-existing.\n"
                   f"Fixed in commit {SHORT_SHA}.\n")
        self.commit()
        self.assertEqual([("docs/notes.md", 4, SHORT_SHA)], self.found())

    def test_an_uncommitted_change_is_judged(self):
        (self.root / "src/Old.java").write_text(f'class Old {{ String runId = "{HEX_ID}"; }}\n',
                                                encoding="utf-8")
        self.assertEqual([("src/Old.java", 1, HEX_ID)], self.found())

    def test_without_a_diff_the_generic_rule_does_not_run(self):
        self.write("src/New.java", f'// job {HEX_ID}\nclass New {{ }}\n')
        files = CHECK.parse_file_list(self.git("ls-files", "-z").encode("utf-8"))
        self.assertEqual([], CHECK.check(self.root, files))

    def test_the_plan_directories_are_exempt(self):
        self.write("docs/plans/notes.md", f"Ran on {ROUND_BRANCH}, job `{HEX_ID}`.\n")
        self.write("docs/qa/retrospectives/notes.md", f"Commit {SHORT_SHA}.\n")
        self.assertEqual([], self.found())

    def test_generated_and_vendored_files_are_skipped(self):
        self.write("web/vendor/lib.js", f'var jobId = "{HEX_ID}";\n')
        self.write("src/Out.java", f'// @generated by a tool\n// commit {SHORT_SHA}\n')
        self.write("src/Gen.py", f'# Code generated by a tool. DO NOT EDIT.\nrun_id = "{HEX_ID}"\n')
        self.assertEqual([], self.found())

    def test_a_renamed_file_is_judged_only_on_what_changed(self):
        self.git("mv", "docs/notes.md", "docs/moved.md")
        self.assertEqual([], self.found())


class GenericMainTests(GitCheckoutTestBase):
    """The command-line contract with ``--diff``."""

    def run_main(self, diff_path=None):
        """Runs the script's entry point the way CI does, with the change's diff on disk."""
        if diff_path is None:
            diff_path = self.root / ".git" / "change.diff"
            diff_path.write_text(self.change_diff(), encoding="utf-8")
        out = io.StringIO()
        err = io.StringIO()
        stdin = io.TextIOWrapper(io.BytesIO(self.git("ls-files", "-z").encode("utf-8")))
        with mock.patch("sys.stdin", stdin), contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            code = CHECK.main(["--root", str(self.root), "--diff", str(diff_path)])
        return code, out.getvalue(), err.getvalue()

    def test_a_clean_change_exits_zero(self):
        self.write("src/New.java", "class New { }\n")
        code, out, _ = self.run_main()
        self.assertEqual(0, code)
        self.assertIn("No generic temporary identifiers on the lines", out)

    def test_an_added_identifier_exits_one(self):
        self.write("prompt.txt", f"Continue on {ROUND_BRANCH}.\n")
        code, out, _ = self.run_main()
        self.assertEqual(1, code)
        self.assertIn(f"prompt.txt:1: '{ROUND_BRANCH}' is a temporary identifier: "
                      f"{CHECK.NAME_SOURCE}", out)
        self.assertIn("temporary-identifier-ok: <reason>", out)

    def test_an_unreadable_diff_exits_two(self):
        code, _, err = self.run_main(self.root / "no-such.diff")
        self.assertEqual(2, code)
        self.assertIn("could not run", err)


if __name__ == "__main__":
    unittest.main()
