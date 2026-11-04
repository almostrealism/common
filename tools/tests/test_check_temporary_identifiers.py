"""Tests for scripts/check-temporary-identifiers.py and its CI wiring.

The fixtures use a made-up document family (``SURVEY``) and dates far in the
future, so nothing in this file is itself an identifier of a real plan
document and the check run over the repository stays clean.
"""

import contextlib
import importlib.util
import io
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


if __name__ == "__main__":
    unittest.main()
