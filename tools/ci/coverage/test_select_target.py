"""Tests for select-target.py — the coverage-qa target selector.

Run with:  python3 -m unittest discover -s tools/ci/coverage -p 'test_*.py'

The selector is exercised both against the checked-in fixture reports
(``testdata/coverage.xml``, ``testdata/python-coverage.xml`` — a merged
JaCoCo report and a coverage.py Cobertura report respectively) and
against small XML/history fixtures built ad hoc in each test, so ranking,
the size floor, exclusion matching, cooldown and give-up behavior can
each be pinned down independently of the fixture files' specific numbers.
"""

import importlib.util
import io
import os
import tempfile
import time
import unittest
from contextlib import redirect_stdout

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "select-target.py")
_spec = importlib.util.spec_from_file_location("select_target", _SCRIPT)
st = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(st)

_TESTDATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata")
_FIXTURE_JAVA_XML = os.path.join(_TESTDATA, "coverage.xml")
_FIXTURE_PYTHON_XML = os.path.join(_TESTDATA, "python-coverage.xml")


def _write(path, content):
    with open(path, "w") as handle:
        handle.write(content)


def _history_row(timestamp, unit, language, before, after):
    return "{}\t{}\t{}\t{}\t{}\n".format(timestamp, unit, language, before, after)


class ParseJavaUnitsTests(unittest.TestCase):
    """The merged JaCoCo fixture parses into one Unit per <package>."""

    def test_parses_line_counters_per_package(self):
        units = {u.name: u for u in st.parse_java_units(_FIXTURE_JAVA_XML)}
        self.assertIn("org.almostrealism.collect.computations", units)
        big = units["org.almostrealism.collect.computations"]
        self.assertEqual(big.covered, 600)
        self.assertEqual(big.missed, 1400)
        self.assertEqual(big.total, 2000)

    def test_package_name_dots_not_slashes(self):
        units = {u.name for u in st.parse_java_units(_FIXTURE_JAVA_XML)}
        self.assertIn("org.almostrealism.hardware.mem", units)
        self.assertNotIn("org/almostrealism/hardware/mem", units)

    def test_missing_file_yields_no_units(self):
        self.assertEqual(st.parse_java_units("/no/such/file.xml"), [])


class ParsePythonUnitsTests(unittest.TestCase):
    """Cobertura <class> entries roll up to their top-level tool directory."""

    def test_rolls_up_to_three_path_components(self):
        units = {u.name: u for u in st.parse_python_units(_FIXTURE_PYTHON_XML)}
        self.assertIn("tools/mcp/common", units)
        self.assertIn("tools/mcp/manager", units)

    def test_test_files_excluded_from_rollup(self):
        # test_foo.py in the fixture has 10 lines, all hit. If it were
        # counted, tools/mcp/common would show 16 total lines instead of 6
        # and a much higher coverage fraction.
        units = {u.name: u for u in st.parse_python_units(_FIXTURE_PYTHON_XML)}
        common = units["tools/mcp/common"]
        self.assertEqual(common.total, 6)
        self.assertEqual(common.covered, 3)
        self.assertEqual(common.missed, 3)

    def test_missing_file_yields_no_units(self):
        self.assertEqual(st.parse_python_units("/no/such/file.xml"), [])


class SizeFloorTests(unittest.TestCase):
    """A unit below MIN_LINES is never eligible, however low its coverage."""

    def test_tiny_package_excluded_even_at_zero_percent(self):
        tiny = st.Unit("org.almostrealism.tiny", "java", covered=0, missed=12)
        self.assertFalse(st.is_eligible(
            tiny, excludes=[], min_lines=50, java_threshold=80.0,
            python_threshold=80.0, history={}, cooldown_seconds=0,
            max_attempts=2, min_progress=5.0, now=time.time()))

    def test_unit_at_the_floor_is_eligible(self):
        at_floor = st.Unit("org.almostrealism.small", "java", covered=0, missed=50)
        self.assertTrue(st.is_eligible(
            at_floor, excludes=[], min_lines=50, java_threshold=80.0,
            python_threshold=80.0, history={}, cooldown_seconds=0,
            max_attempts=2, min_progress=5.0, now=time.time()))


class ExclusionMatchingTests(unittest.TestCase):
    """fnmatch-style glob patterns from the exclusion file."""

    def test_exact_and_wildcard_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "exclusions.txt")
            _write(path, "\n".join([
                "# a comment, ignored",
                "",
                "org.almostrealism.generated",
                "org.almostrealism.generated.*",
                "org.almostrealism.hardware.mem",
            ]))
            patterns = st.load_exclusions(path)
            self.assertTrue(st.is_excluded("org.almostrealism.generated", patterns))
            self.assertTrue(st.is_excluded("org.almostrealism.generated.expression", patterns))
            self.assertTrue(st.is_excluded("org.almostrealism.hardware.mem", patterns))
            self.assertFalse(st.is_excluded("org.almostrealism.hardware.metal", patterns))
            self.assertFalse(st.is_excluded("org.almostrealism.collect", patterns))

    def test_missing_exclusions_file_excludes_nothing(self):
        self.assertEqual(st.load_exclusions("/no/such/file.txt"), [])


class CooldownTests(unittest.TestCase):
    """A unit selected within COOLDOWN_DAYS is skipped; older selections expire."""

    def test_recent_selection_is_skipped(self):
        now = time.time()
        history = {"org.almostrealism.foo": [
            (now - 5 * 86400, 10.0, 15.0),
        ]}
        unit = st.Unit("org.almostrealism.foo", "java", covered=10, missed=90)
        self.assertFalse(st.is_eligible(
            unit, excludes=[], min_lines=50, java_threshold=80.0,
            python_threshold=80.0, history=history, cooldown_seconds=30 * 86400,
            max_attempts=2, min_progress=5.0, now=now))

    def test_selection_older_than_cooldown_is_eligible_again(self):
        now = time.time()
        history = {"org.almostrealism.foo": [
            (now - 40 * 86400, 10.0, 15.0),
        ]}
        unit = st.Unit("org.almostrealism.foo", "java", covered=10, missed=90)
        self.assertTrue(st.is_eligible(
            unit, excludes=[], min_lines=50, java_threshold=80.0,
            python_threshold=80.0, history=history, cooldown_seconds=30 * 86400,
            max_attempts=2, min_progress=5.0, now=now))


class GiveUpMarkerTests(unittest.TestCase):
    """MAX_ATTEMPTS rounds under MIN_PROGRESS points auto-excludes a unit."""

    def test_given_up_after_max_attempts_of_low_progress(self):
        history = {"org.almostrealism.stuck": [
            (1000, 10.0, 12.0),   # +2 points
            (2000, 12.0, 13.0),   # +1 point
        ]}
        self.assertTrue(st.is_given_up(
            "org.almostrealism.stuck", history, max_attempts=2, min_progress=5.0))

    def test_not_given_up_before_max_attempts(self):
        history = {"org.almostrealism.stuck": [
            (1000, 10.0, 12.0),
        ]}
        self.assertFalse(st.is_given_up(
            "org.almostrealism.stuck", history, max_attempts=2, min_progress=5.0))

    def test_not_given_up_when_a_recent_attempt_made_real_progress(self):
        history = {"org.almostrealism.improving": [
            (1000, 10.0, 12.0),   # +2 points, poor
            (2000, 12.0, 30.0),   # +18 points, real progress
        ]}
        self.assertFalse(st.is_given_up(
            "org.almostrealism.improving", history, max_attempts=2, min_progress=5.0))

    def test_auto_exclude_appends_exact_name_not_wildcard(self):
        with tempfile.TemporaryDirectory() as tmp:
            exclusions_path = os.path.join(tmp, "exclusions.txt")
            _write(exclusions_path, "# seed\n")
            history = {"org.almostrealism.stuck": [
                (1000, 10.0, 12.0),
                (2000, 12.0, 13.0),
            ]}
            units = [st.Unit("org.almostrealism.stuck", "java", covered=13, missed=87)]
            newly_excluded = st.auto_exclude_given_up(
                units, history, excludes=[], exclusions_path=exclusions_path,
                max_attempts=2, min_progress=5.0)
            self.assertEqual(newly_excluded, ["org.almostrealism.stuck"])
            with open(exclusions_path) as handle:
                contents = handle.read()
            self.assertIn("org.almostrealism.stuck", contents)
            self.assertNotIn("org.almostrealism.stuck.*", contents)
            self.assertNotIn("org.almostrealism.stuck*", contents)

    def test_auto_exclude_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            exclusions_path = os.path.join(tmp, "exclusions.txt")
            _write(exclusions_path, "org.almostrealism.stuck\n")
            history = {"org.almostrealism.stuck": [
                (1000, 10.0, 12.0),
                (2000, 12.0, 13.0),
            ]}
            units = [st.Unit("org.almostrealism.stuck", "java", covered=13, missed=87)]
            excludes = st.load_exclusions(exclusions_path)
            newly_excluded = st.auto_exclude_given_up(
                units, history, excludes=excludes, exclusions_path=exclusions_path,
                max_attempts=2, min_progress=5.0)
            self.assertEqual(newly_excluded, [])


class RankingTests(unittest.TestCase):
    """score = missed * (1 - fraction), descending, with the plan's tie-breaks."""

    def test_bigger_absolute_win_ranks_first(self):
        big_low_pct = st.Unit("big", "java", covered=600, missed=1400)     # score=1400*0.7=980
        small_high_pct = st.Unit("small", "java", covered=220, missed=80)  # score=80*(80/300)=~21.3
        ranked = st.rank([small_high_pct, big_low_pct], history={})
        self.assertEqual([u.name for u in ranked], ["big", "small"])

    def test_tie_break_prefers_more_lines(self):
        # Two units tied EXACTLY on score (both fractions and the
        # multiplication land on values with an exact binary
        # representation, so there is no floating-point near-miss): x has
        # 100 instrumented lines at 50% (score 50*0.5=25.0), y2 has 25
        # lines at 0% (score 25*1.0=25.0).
        x = st.Unit("x", "java", covered=50, missed=50)
        y2 = st.Unit("y2", "java", covered=0, missed=25)
        self.assertEqual(x.score, y2.score)
        ranked = st.rank([y2, x], history={})
        self.assertEqual([u.name for u in ranked], ["x", "y2"])

    def test_tie_break_prefers_not_selected_more_recently(self):
        p = st.Unit("p", "java", covered=0, missed=10)
        q = st.Unit("q", "java", covered=0, missed=10)
        history = {
            "p": [(500, 0.0, 0.0)],   # selected long ago
            "q": [(5000, 0.0, 0.0)],  # selected more recently
        }
        ranked = st.rank([q, p], history=history)
        self.assertEqual([u.name for u in ranked], ["p", "q"])

    def test_never_selected_beats_previously_selected_on_tie(self):
        p = st.Unit("p", "java", covered=0, missed=10)
        q = st.Unit("q", "java", covered=0, missed=10)
        history = {"q": [(100, 0.0, 0.0)]}
        ranked = st.rank([q, p], history=history)
        self.assertEqual([u.name for u in ranked], ["p", "q"])

    def test_lexical_tie_break_is_deterministic(self):
        b = st.Unit("bbb", "java", covered=0, missed=10)
        a = st.Unit("aaa", "java", covered=0, missed=10)
        ranked = st.rank([b, a], history={})
        self.assertEqual([u.name for u in ranked], ["aaa", "bbb"])


class PythonThresholdFallbackTests(unittest.TestCase):
    """COVERAGE_THRESHOLD_PYTHON falls back to the global threshold when unset."""

    def test_python_unit_uses_python_threshold(self):
        unit = st.Unit("tools/mcp/common", "python", covered=0, missed=0)
        self.assertEqual(st.threshold_for(unit, java_threshold=80.0, python_threshold=70.0), 70.0)

    def test_java_unit_uses_java_threshold(self):
        unit = st.Unit("org.almostrealism.foo", "java", covered=0, missed=0)
        self.assertEqual(st.threshold_for(unit, java_threshold=80.0, python_threshold=70.0), 80.0)


class EndToEndSelectionTests(unittest.TestCase):
    """Dry-run select() and main() against the checked-in fixture reports."""

    def test_select_picks_the_highest_scoring_eligible_unit(self):
        with tempfile.TemporaryDirectory() as tmp:
            exclusions_path = os.path.join(tmp, "exclusions.txt")
            history_path = os.path.join(tmp, "history.tsv")
            _write(exclusions_path, "org.almostrealism.generated\norg.almostrealism.hardware.mem\n")
            _write(history_path, "")
            target, all_units = st.select(
                _FIXTURE_JAVA_XML, _FIXTURE_PYTHON_XML, exclusions_path, history_path,
                java_threshold=80.0, python_threshold=80.0, min_lines=5,
                cooldown_days=30, max_attempts=2, min_progress=5.0, now=time.time())
            self.assertIsNotNone(target)
            self.assertEqual(target.name, "org.almostrealism.collect.computations")
            self.assertGreaterEqual(len(all_units), 5)

    def test_select_returns_none_when_everything_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            exclusions_path = os.path.join(tmp, "exclusions.txt")
            history_path = os.path.join(tmp, "history.tsv")
            _write(exclusions_path, "*\n")  # exclude every unit
            _write(history_path, "")
            target, _all_units = st.select(
                _FIXTURE_JAVA_XML, _FIXTURE_PYTHON_XML, exclusions_path, history_path,
                java_threshold=80.0, python_threshold=80.0, min_lines=5,
                cooldown_days=30, max_attempts=2, min_progress=5.0, now=time.time())
            self.assertIsNone(target)

    def test_main_emits_github_output_lines_for_a_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            exclusions_path = os.path.join(tmp, "exclusions.txt")
            history_path = os.path.join(tmp, "history.tsv")
            github_output = os.path.join(tmp, "github_output.txt")
            _write(exclusions_path, "org.almostrealism.generated\norg.almostrealism.hardware.mem\n")
            _write(history_path, "")
            os.environ["GITHUB_OUTPUT"] = github_output
            try:
                buf = io.StringIO()
                with redirect_stdout(buf):
                    exit_code = st.main([
                        "--java-xml", _FIXTURE_JAVA_XML,
                        "--python-xml", _FIXTURE_PYTHON_XML,
                        "--exclusions", exclusions_path,
                        "--history", history_path,
                        "--threshold", "80",
                        "--min-lines", "5",
                        "--now", "1000000",
                    ])
            finally:
                del os.environ["GITHUB_OUTPUT"]
            self.assertEqual(exit_code, 0)
            with open(github_output) as handle:
                output = handle.read()
            self.assertIn("target=org.almostrealism.collect.computations", output)
            self.assertIn("language=java", output)

    def test_main_emits_empty_target_when_nothing_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            exclusions_path = os.path.join(tmp, "exclusions.txt")
            history_path = os.path.join(tmp, "history.tsv")
            github_output = os.path.join(tmp, "github_output.txt")
            _write(exclusions_path, "*\n")
            _write(history_path, "")
            os.environ["GITHUB_OUTPUT"] = github_output
            try:
                buf = io.StringIO()
                with redirect_stdout(buf):
                    exit_code = st.main([
                        "--java-xml", _FIXTURE_JAVA_XML,
                        "--python-xml", _FIXTURE_PYTHON_XML,
                        "--exclusions", exclusions_path,
                        "--history", history_path,
                        "--threshold", "80",
                        "--min-lines", "5",
                        "--now", "1000000",
                    ])
            finally:
                del os.environ["GITHUB_OUTPUT"]
            self.assertEqual(exit_code, 0)
            with open(github_output) as handle:
                output = handle.read()
            self.assertIn("target=\n", output)


if __name__ == "__main__":
    unittest.main()
