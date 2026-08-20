"""Tests for reading surefire reports.

Every count and failure the MCP tools report comes from this parsing, so the
cases that matter are the ones where an outcome is easy to get wrong: a skipped
test (which is neither a pass nor a failure), an error (which is reported like a
failure but tallied separately), and a repeated run whose results are spread
across invocation subdirectories.
"""

import pathlib
import shutil
import sys
import tempfile
import unittest

_HERE = pathlib.Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import reports  # noqa: E402


def _suite(cases: str, tests: int, failures: int = 0,
           errors: int = 0, skipped: int = 0) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="Suite" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}">\n{cases}\n</testsuite>\n'
    )


PASSED = '<testcase classname="a.B" name="passes" time="1.5"/>'
SKIPPED = ('<testcase classname="a.B" name="isSkipped" time="0.0">'
           '<skipped/></testcase>')
FAILED = ('<testcase classname="a.B" name="fails" time="2.0">'
          '<failure type="java.lang.AssertionError" message="boom">'
          'line1\nline2</failure></testcase>')
ERRORED = ('<testcase classname="a.B" name="errors" time="3.0">'
           '<error type="java.lang.IllegalStateException" message="bad">'
           'trace</error></testcase>')


class ReportsTestCase(unittest.TestCase):

    def _reports(self, *files: str) -> reports.SurefireReports:
        """Write each report into a fresh directory and return it."""
        directory = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, directory, True)
        for i, content in enumerate(files):
            (directory / f"TEST-suite{i}.xml").write_text(content)
        return reports.SurefireReports(directory)


class ParseTests(ReportsTestCase):

    def test_each_outcome_is_tallied_exactly_once(self):
        """The summary must account for every case, with no double counting."""
        found = self._reports(
            _suite(f"{PASSED}\n{SKIPPED}\n{FAILED}\n{ERRORED}",
                   tests=4, failures=1, errors=1, skipped=1))

        failures, _, summary = found.parse()

        self.assertEqual(
            {"total": 4, "passed": 1, "failed": 1, "error": 1, "skipped": 1},
            summary)
        self.assertEqual(4, sum(summary[k] for k in
                                ("passed", "failed", "error", "skipped")))
        self.assertEqual(2, len(failures))

    def test_skipped_test_is_neither_passed_nor_failed(self):
        """A skipped test is its own outcome and never reported as a failure."""
        failures, tests, summary = self._reports(
            _suite(SKIPPED, tests=1, skipped=1)).parse(include_all_tests=True)

        self.assertEqual([], failures)
        self.assertEqual(1, summary["skipped"])
        self.assertEqual(0, summary["passed"])
        self.assertEqual("skipped", tests[0]["status"])

    def test_error_is_reported_as_a_failure_but_tallied_separately(self):
        failures, _, summary = self._reports(
            _suite(ERRORED, tests=1, errors=1)).parse()

        self.assertEqual(1, summary["error"])
        self.assertEqual(0, summary["failed"])
        self.assertEqual("java.lang.IllegalStateException", failures[0]["type"])
        self.assertEqual("bad", failures[0]["message"])

    def test_failure_carries_its_stacktrace_and_identity(self):
        failures, _, _ = self._reports(
            _suite(FAILED, tests=1, failures=1)).parse()

        self.assertEqual("a.B", failures[0]["class"])
        self.assertEqual("fails", failures[0]["method"])
        self.assertEqual(2.0, failures[0]["time_seconds"])
        self.assertIn("line1", failures[0]["stacktrace"])

    def test_all_tests_are_only_collected_when_asked(self):
        found = self._reports(_suite(PASSED, tests=1))

        self.assertEqual([], found.parse()[1])
        self.assertEqual(1, len(found.parse(include_all_tests=True)[1]))

    def test_unreadable_report_does_not_lose_the_others(self):
        """Partial results beat none; the run's exit code remains authoritative."""
        found = self._reports(_suite(PASSED, tests=1), "not xml at all")

        self.assertEqual(1, found.parse()[2]["total"])

    def test_counts_read_the_suite_level_attributes(self):
        counts = self._reports(
            _suite(f"{PASSED}\n{FAILED}", tests=2, failures=1)).counts()

        self.assertEqual(2, counts["tests_run"])
        self.assertEqual(1, counts["failures"])


class TruncationTests(unittest.TestCase):

    def test_short_stacktrace_is_untouched(self):
        self.assertEqual("a\nb", reports.truncate_stacktrace("a\nb"))

    def test_empty_stacktrace_is_empty(self):
        self.assertEqual("", reports.truncate_stacktrace(""))

    def test_long_stacktrace_keeps_head_and_tail(self):
        """Both the throw site and the assertion matter, so both ends survive."""
        trace = "\n".join(f"line{i}" for i in range(100))

        truncated = reports.truncate_stacktrace(trace, max_lines=10)

        self.assertIn("line0", truncated)
        self.assertIn("line99", truncated)
        self.assertIn("90 lines truncated", truncated)
        self.assertEqual(11, len(truncated.split("\n")))

    def test_parse_can_leave_stacktraces_whole(self):
        directory = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, directory, True)
        long_trace = "\n".join(f"line{i}" for i in range(100))
        (directory / "TEST-a.xml").write_text(_suite(
            '<testcase classname="a.B" name="fails" time="1.0">'
            f'<failure type="T" message="m">{long_trace}</failure></testcase>',
            tests=1, failures=1))

        failures, _, _ = reports.SurefireReports(directory).parse(
            truncate_stacktraces=False)

        self.assertIn("line50", failures[0]["stacktrace"])
        self.assertNotIn("truncated", failures[0]["stacktrace"])


class RepeatedRunTests(ReportsTestCase):

    def _repeated(self) -> reports.SurefireReports:
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)

        for number, case in ((1, PASSED), (2, FAILED)):
            directory = root / f"invocation_{number}"
            directory.mkdir()
            (directory / "TEST-a.xml").write_text(
                _suite(case, tests=1, failures=1 if case is FAILED else 0))

        return reports.SurefireReports(root)

    def test_invocations_are_returned_in_order(self):
        self.assertEqual([1, 2], [n for n, _ in self._repeated().invocations()])

    def test_unnumbered_subdirectory_is_ignored(self):
        """An unrelated directory must never be counted as a repetition."""
        found = self._repeated()
        (found.directory / "invocation_notanumber").mkdir()

        self.assertEqual([1, 2], [n for n, _ in found.invocations()])

    def test_counts_are_summed_across_invocations(self):
        self.assertEqual(2, self._repeated().total_counts(repetitions=2)["tests_run"])

    def test_single_run_counts_ignore_invocation_directories(self):
        """With repetitions=1 the reports are read directly, not summed."""
        self.assertEqual(0, self._repeated().total_counts(repetitions=1)["tests_run"])

    def test_failures_are_labelled_with_their_invocation(self):
        collected = self._repeated().collect_failures(repetitions=2)

        self.assertEqual(1, len(collected["failures"]))
        self.assertEqual(2, collected["failures"][0]["invocation"])
        self.assertEqual(2, collected["summary"]["total"])

    def test_test_times_group_every_invocation_of_a_test(self):
        times = self._repeated().test_times()

        self.assertEqual([1], [e["invocation"] for e in times["a.B#passes"]])
        self.assertEqual(["failed"], [e["status"] for e in times["a.B#fails"]])


class CollectionTests(ReportsTestCase):

    def _source(self) -> pathlib.Path:
        source = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, source, True)
        (source / "TEST-a.xml").write_text(_suite(PASSED, tests=1))
        (source / "unrelated.txt").write_text("ignore me")
        return source

    def test_only_surefire_reports_are_copied(self):
        destination = self._reports()

        destination.collect_from(self._source())

        self.assertTrue((destination.directory / "TEST-a.xml").exists())
        self.assertFalse((destination.directory / "unrelated.txt").exists())

    def test_missing_source_is_not_an_error(self):
        destination = self._reports()

        destination.collect_from(pathlib.Path("/nonexistent/surefire-reports"))

        self.assertEqual(0, destination.counts()["tests_run"])

    def test_reports_older_than_the_run_are_left_behind(self):
        """A previous run's leftovers must not be adopted as this run's results."""
        import datetime
        import os

        source = self._source()
        stale = source / "TEST-a.xml"
        old = datetime.datetime(2000, 1, 1).timestamp()
        os.utime(stale, (old, old))

        destination = self._reports()
        destination.collect_from(source, modified_since=datetime.datetime.now())

        self.assertFalse((destination.directory / "TEST-a.xml").exists())

    def test_module_output_is_the_maven_report_location(self):
        self.assertEqual(
            pathlib.Path("/p/engine/utils/target/surefire-reports"),
            reports.module_output(pathlib.Path("/p"), "engine/utils"))


if __name__ == "__main__":
    unittest.main()
