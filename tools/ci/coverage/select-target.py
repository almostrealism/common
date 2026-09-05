#!/usr/bin/env python3
"""Select the next coverage-qa target from merged coverage reports.

Reads the merged Java JaCoCo report (``coverage.xml``) and the Python
coverage.py Cobertura report (``python-coverage.xml``), applies the
exclusion list, the ``MIN_LINES`` size floor, the cooldown ledger and the
give-up marker, ranks the remaining candidates by
``score = missed * (1 - coverage_fraction)``, and emits exactly one
target — or none, when nothing is eligible.

Usage:
    select-target.py [options]

Options mirror the environment variables the coverage-qa workflow job
sets (``COVERAGE_THRESHOLD``, ``MIN_LINES``, ``COOLDOWN_DAYS``, ...); each
also has a matching ``--flag`` so the script can be driven directly (unit
tests use this to point at fixture files instead of a real checkout).

Outputs (to stdout, and appended to ``$GITHUB_OUTPUT`` when set):
    target=<unit name, or empty when nothing is eligible>
    language=java|python
    current=<line coverage percentage before this round>
    module=<same as target; kept for readability in workflow summaries>
"""

import argparse
import calendar
import fnmatch
import os
import sys
import time
import xml.etree.ElementTree as ET
from collections import defaultdict

DEFAULT_JAVA_COVERAGE_XML = "coverage.xml"
DEFAULT_PYTHON_COVERAGE_XML = "python-coverage.xml"
DEFAULT_EXCLUSIONS_FILE = "tools/ci/coverage-exclusions.txt"
DEFAULT_HISTORY_FILE = "tools/ci/coverage-history.tsv"

DEFAULT_COVERAGE_THRESHOLD = 80.0
DEFAULT_MIN_LINES = 50
DEFAULT_COOLDOWN_DAYS = 30
DEFAULT_MAX_ATTEMPTS = 2
DEFAULT_MIN_PROGRESS = 5.0

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


class Unit:
    """One coverage-selection unit: a Java package or a Python directory."""

    def __init__(self, name, language, covered, missed):
        self.name = name
        self.language = language
        self.covered = covered
        self.missed = missed

    @property
    def total(self):
        return self.covered + self.missed

    @property
    def fraction(self):
        return self.covered / self.total if self.total else 1.0

    @property
    def percent(self):
        return round(100.0 * self.fraction, 1)

    @property
    def score(self):
        return self.missed * (1.0 - self.fraction)


def format_timestamp(epoch):
    return time.strftime(TIMESTAMP_FORMAT, time.gmtime(epoch))


def parse_timestamp(value):
    return calendar.timegm(time.strptime(value, TIMESTAMP_FORMAT))


def load_exclusions(path):
    """Returns the list of fnmatch glob patterns in an exclusion file."""
    patterns = []
    if not path or not os.path.isfile(path):
        return patterns
    with open(path) as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            patterns.append(line)
    return patterns


def is_excluded(name, patterns):
    return any(fnmatch.fnmatch(name, pattern) for pattern in patterns)


def load_history(path):
    """Returns ``{unit_name: [(epoch, before_pct, after_pct), ...]}``.

    Rows for each unit are sorted ascending by timestamp. Malformed rows
    (wrong column count, unparseable timestamp or percentage) are skipped
    rather than raising — a ledger the selector cannot fully parse must
    not crash the pipeline; it simply loses cooldown/give-up memory of the
    unparseable rows.
    """
    history = defaultdict(list)
    if not path or not os.path.isfile(path):
        return history
    with open(path) as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 5:
                continue
            timestamp, unit, _language, before_pct, after_pct = parts
            try:
                epoch = parse_timestamp(timestamp)
                before = float(before_pct)
                after = float(after_pct)
            except ValueError:
                continue
            history[unit].append((epoch, before, after))
    for unit in history:
        history[unit].sort(key=lambda row: row[0])
    return history


def parse_java_units(path):
    """Parses a JaCoCo XML report into one Unit per ``<package>`` element.

    A report that fails to parse as XML (a truncated download, a corrupt
    artifact) yields no units rather than crashing the selector — the same
    "no data, no units" standing a missing file already has, so a single
    bad report degrades selection instead of failing the whole pipeline.
    """
    units = []
    if not path or not os.path.isfile(path):
        return units
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        print("::warning::{} is not valid XML ({}); treating it as empty".format(
            path, error), file=sys.stderr)
        return units
    for package in root.iter("package"):
        name = package.get("name")
        if not name:
            continue
        line_counter = next(
            (c for c in package.findall("counter") if c.get("type") == "LINE"), None
        )
        if line_counter is None:
            continue
        covered = int(line_counter.get("covered", "0"))
        missed = int(line_counter.get("missed", "0"))
        units.append(Unit(name.replace("/", "."), "java", covered, missed))
    return units


def _python_directory(filename):
    """Rolls a Python file path up to its top-level tool directory.

    Matches the ``python-tests`` CI job's discovery granularity: the first
    (up to) three directory path components, the filename itself dropped
    before truncating (e.g. ``tools/mcp/manager/foo.py`` ->
    ``tools/mcp/manager``, and — the case a naive ``parts[:3]`` on the
    whole path gets wrong — ``tools/tests/foo.py`` -> ``tools/tests``, not
    ``tools/tests/foo.py``, since a two-directories-deep file has only
    three path components total and the filename must not be mistaken for
    a third directory level).
    """
    directory_parts = filename.split("/")[:-1]
    if not directory_parts:
        return filename
    return "/".join(directory_parts[:3])


def parse_python_units(path):
    """Parses a coverage.py Cobertura XML report into one Unit per directory.

    Rolls per-file ``<class>`` line data up to the directory returned by
    ``_python_directory``. Files whose basename starts with ``test_`` are
    skipped defensively even though the pipeline is expected to configure
    coverage.py to omit them already (see the plan's Python measurement
    caveat) — a stray inclusion here must not silently inflate a
    directory's reported coverage.

    A report that fails to parse as XML yields no units rather than
    crashing the selector, matching ``parse_java_units``.
    """
    if not path or not os.path.isfile(path):
        return []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        print("::warning::{} is not valid XML ({}); treating it as empty".format(
            path, error), file=sys.stderr)
        return []
    agg = defaultdict(lambda: [0, 0])
    for cls in root.iter("class"):
        filename = cls.get("filename") or ""
        basename = filename.rsplit("/", 1)[-1]
        if basename.startswith("test_"):
            continue
        directory = _python_directory(filename)
        lines_el = cls.find("lines")
        if lines_el is None:
            continue
        for line in lines_el.findall("line"):
            hit = line.get("hits", "0") != "0"
            agg[directory][0 if hit else 1] += 1
    return [
        Unit(directory, "python", covered, missed)
        for directory, (covered, missed) in agg.items()
    ]


def threshold_for(unit, java_threshold, python_threshold):
    return python_threshold if unit.language == "python" else java_threshold


def is_given_up(unit_name, history, max_attempts, min_progress):
    """A unit is given up on when its last MAX_ATTEMPTS rounds each made
    less than MIN_PROGRESS percentage points of gain."""
    rows = history.get(unit_name, [])
    if len(rows) < max_attempts:
        return False
    recent = rows[-max_attempts:]
    return all((after - before) < min_progress for (_epoch, before, after) in recent)


def is_eligible(unit, excludes, min_lines, java_threshold, python_threshold,
                history, cooldown_seconds, max_attempts, min_progress, now):
    if unit.total < min_lines:
        return False
    if is_excluded(unit.name, excludes):
        return False
    if unit.percent >= threshold_for(unit, java_threshold, python_threshold):
        return False
    if is_given_up(unit.name, history, max_attempts, min_progress):
        return False
    rows = history.get(unit.name, [])
    if rows and (now - rows[-1][0]) < cooldown_seconds:
        return False
    return True


def rank(units, history):
    """Sorts eligible units by the plan's ranking, most-preferred first.

    Primary: score = missed * (1 - coverage_fraction), descending.
    Tie-break 1: more instrumented lines (bigger absolute win), descending.
    Tie-break 2: not selected more recently — never-selected units, then
    the oldest last-selection timestamp, sort first.
    Tie-break 3: lexical order of the unit name, ascending, for determinism.
    """

    def sort_key(unit):
        rows = history.get(unit.name, [])
        last_selected = rows[-1][0] if rows else -1
        return (-unit.score, -unit.total, last_selected, unit.name)

    return sorted(units, key=sort_key)


def auto_exclude_given_up(units, history, excludes, exclusions_path,
                           max_attempts, min_progress):
    """Appends a documented entry for any newly given-up unit.

    Only units that are given up on AND not already present verbatim in
    the exclusion file are appended — this keeps re-runs idempotent and
    never widens an existing pattern. The appended pattern is the exact
    unit name (never a wildcard), so it cannot silently exclude siblings
    the give-up condition was never evaluated against.

    Returns the list of newly-excluded unit names, so the caller can
    report what changed.
    """
    newly_excluded = []
    for unit in units:
        if unit.name in excludes:
            continue
        if not is_given_up(unit.name, history, max_attempts, min_progress):
            continue
        newly_excluded.append(unit.name)

    if not newly_excluded or not exclusions_path:
        return newly_excluded

    with open(exclusions_path, "a") as handle:
        handle.write(
            "\n# ── AUTO-EXCLUDED by select-target.py (give-up marker) ──\n"
            "# Attempted {} time(s) with less than {} point(s) of progress "
            "each time.\n# Reviewer: confirm this unit is genuinely "
            "unimprovable before removing it.\n".format(max_attempts, min_progress)
        )
        for name in newly_excluded:
            handle.write(name + "\n")

    return newly_excluded


def select(java_xml, python_xml, exclusions_path, history_path,
           java_threshold, python_threshold, min_lines, cooldown_days,
           max_attempts, min_progress, now=None):
    """Runs the full selection pipeline; returns (target_unit_or_None, all_units)."""
    if now is None:
        now = time.time()

    units = parse_java_units(java_xml) + parse_python_units(python_xml)
    excludes = load_exclusions(exclusions_path)
    history = load_history(history_path)

    auto_exclude_given_up(units, history, excludes, exclusions_path,
                          max_attempts, min_progress)
    # Re-load so a freshly auto-excluded unit is honored in this same run.
    excludes = load_exclusions(exclusions_path)

    cooldown_seconds = cooldown_days * 86400
    eligible_units = [
        u for u in units
        if is_eligible(u, excludes, min_lines, java_threshold, python_threshold,
                       history, cooldown_seconds, max_attempts, min_progress, now)
    ]
    ranked = rank(eligible_units, history)
    return (ranked[0] if ranked else None), units


def _env_float(name, default):
    value = os.environ.get(name)
    return float(value) if value else default


def _env_int(name, default):
    value = os.environ.get(name)
    return int(value) if value else default


def build_arg_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-xml", default=os.environ.get(
        "JAVA_COVERAGE_XML", DEFAULT_JAVA_COVERAGE_XML))
    parser.add_argument("--python-xml", default=os.environ.get(
        "PYTHON_COVERAGE_XML", DEFAULT_PYTHON_COVERAGE_XML))
    parser.add_argument("--exclusions", default=os.environ.get(
        "EXCLUSIONS_FILE", DEFAULT_EXCLUSIONS_FILE))
    parser.add_argument("--history", default=os.environ.get(
        "HISTORY_FILE", DEFAULT_HISTORY_FILE))
    parser.add_argument("--threshold", type=float, default=_env_float(
        "COVERAGE_THRESHOLD", DEFAULT_COVERAGE_THRESHOLD))
    parser.add_argument("--threshold-python", type=float, default=None)
    parser.add_argument("--min-lines", type=int, default=_env_int(
        "MIN_LINES", DEFAULT_MIN_LINES))
    parser.add_argument("--cooldown-days", type=int, default=_env_int(
        "COOLDOWN_DAYS", DEFAULT_COOLDOWN_DAYS))
    parser.add_argument("--max-attempts", type=int, default=_env_int(
        "MAX_ATTEMPTS", DEFAULT_MAX_ATTEMPTS))
    parser.add_argument("--min-progress", type=float, default=_env_float(
        "MIN_PROGRESS", DEFAULT_MIN_PROGRESS))
    parser.add_argument("--now", type=float, default=None,
                         help="Epoch seconds to treat as \"now\" (testing only).")
    return parser


def main(argv=None):
    args = build_arg_parser().parse_args(argv)

    # COVERAGE_THRESHOLD_PYTHON falls back to the global threshold when unset.
    threshold_python = args.threshold_python
    if threshold_python is None:
        env_value = os.environ.get("COVERAGE_THRESHOLD_PYTHON")
        threshold_python = float(env_value) if env_value else args.threshold

    now = args.now if args.now is not None else time.time()

    target, _all_units = select(
        args.java_xml, args.python_xml, args.exclusions, args.history,
        args.threshold, threshold_python, args.min_lines, args.cooldown_days,
        args.max_attempts, args.min_progress, now,
    )

    lines = []
    if target is None:
        lines.append("target=")
        print("No eligible coverage target found.")
    else:
        lines.append("target={}".format(target.name))
        lines.append("language={}".format(target.language))
        lines.append("current={}".format(target.percent))
        lines.append("module={}".format(target.name))
        print("Selected target: {} ({}) at {}%".format(
            target.name, target.language, target.percent))

    for line in lines:
        print(line)

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as handle:
            for line in lines:
                handle.write(line + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
