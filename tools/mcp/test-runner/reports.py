#!/usr/bin/env python3
"""Surefire XML reports for a run.

Maven writes its results as ``TEST-*.xml`` under a module's ``target``
directory, where the next run overwrites them. A run therefore takes its own
copy under the run directory, and every question the MCP tools answer about
what happened — how many tests ran, which failed, how long each took — is a
question about that copy. Both halves live here: collecting the reports out of
the project, and reading them back.

The reports directory is the subject, so these are methods on
:class:`SurefireReports` rather than helpers taking a path.
"""

import re
import shutil
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from typing import Optional

# Max lines kept when a stacktrace is truncated; head and tail are preserved
# around an elision marker, since both the throw site and the assertion matter.
DEFAULT_STACKTRACE_LINES = 30

# Zeroed tallies, rebuilt per call so a caller can never mutate a shared dict.
def _empty_counts() -> dict:
    return {"tests_run": 0, "failures": 0, "errors": 0, "skipped": 0}


def _empty_summary() -> dict:
    return {"total": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0}


def truncate_stacktrace(stacktrace: str,
                        max_lines: int = DEFAULT_STACKTRACE_LINES) -> str:
    """Truncate a stacktrace to max_lines, keeping head and tail."""
    if not stacktrace:
        return ""
    lines = stacktrace.split('\n')
    if len(lines) <= max_lines:
        return stacktrace
    head = max_lines // 2
    tail = max_lines - head
    truncated_lines = (
        lines[:head] +
        [f"    ... ({len(lines) - max_lines} lines truncated) ..."] +
        lines[-tail:]
    )
    return '\n'.join(truncated_lines)


def _testcase_status(testcase) -> str:
    """Return the outcome a ``<testcase>`` element records.

    Surefire reports an outcome as the presence of a child element, and absence
    of all of them means the test passed.
    """
    if testcase.find("failure") is not None:
        return "failed"
    if testcase.find("error") is not None:
        return "error"
    if testcase.find("skipped") is not None:
        return "skipped"
    return "passed"


def module_output(project_root: Path, module: str) -> Path:
    """Return where Maven writes surefire reports for a module."""
    return project_root / module / "target" / "surefire-reports"


class SurefireReports:
    """A directory of surefire ``TEST-*.xml`` reports."""

    def __init__(self, directory: Path):
        self.directory = Path(directory)

    def exists(self) -> bool:
        """True if the directory is present."""
        return self.directory.exists()

    def invocation(self, invocation_num: int) -> "SurefireReports":
        """Return the reports of one invocation of a repeated run."""
        return SurefireReports(self.directory / f"invocation_{invocation_num}")

    def invocations(self) -> list:
        """Return ``(number, reports)`` for each invocation subdirectory, in order.

        Ordered by the invocation number itself rather than by directory name,
        which are not the same order once a run reaches ten repetitions:
        ``invocation_10`` sorts before ``invocation_2`` as text. A run may
        repeat up to a hundred times, so callers reading per-invocation timings
        or failures would otherwise be handed them shuffled.

        Directories that do not carry an invocation number are ignored rather
        than guessed at, so an unrelated subdirectory cannot be counted as a
        repetition. The whole name must be the number, so a neighbouring
        directory that merely starts like one is not mistaken for it.
        """
        found = []
        for path in self.directory.glob("invocation_*"):
            match = re.fullmatch(r"invocation_(\d+)", path.name)
            if match:
                found.append((int(match.group(1)), SurefireReports(path)))

        found.sort(key=lambda entry: entry[0])
        return found

    def collect_from(self, source: Path,
                     modified_since: Optional[datetime] = None) -> None:
        """Copy reports out of a Maven output directory into this one.

        Args:
            source: The module's surefire output directory.
            modified_since: When set, only reports written at or after this
                time are copied, which is how a run avoids adopting the stale
                reports a previous run left in the module's target directory.
                Maven overwrites the report for a class it actually runs but
                leaves every other report in place, so a repeated invocation
                needs the same filter as the run does — against its own start
                rather than the run's — or it counts classes it never ran.

        A report that cannot be read or copied is skipped: partial results are
        more useful than none, and the run's own exit code is the authority on
        success.
        """
        if not source.exists():
            return

        self.directory.mkdir(parents=True, exist_ok=True)

        for xml_file in source.glob("TEST-*.xml"):
            try:
                if modified_since is not None:
                    written = datetime.fromtimestamp(xml_file.stat().st_mtime)
                    if written < modified_since:
                        continue
                shutil.copy2(xml_file, self.directory / xml_file.name)
            except Exception:
                pass

    def counts(self) -> dict:
        """Return the aggregate test counts across every report."""
        counts = _empty_counts()

        for xml_file in self.directory.glob("TEST-*.xml"):
            try:
                root = ET.parse(xml_file).getroot()
                counts["tests_run"] += int(root.get("tests", 0))
                counts["failures"] += int(root.get("failures", 0))
                counts["errors"] += int(root.get("errors", 0))
                counts["skipped"] += int(root.get("skipped", 0))
            except Exception:
                pass

        return counts

    def total_counts(self, repetitions: int = 1) -> dict:
        """Return the test counts for a run, summed over invocations when repeated."""
        if repetitions <= 1:
            return self.counts()

        counts = _empty_counts()
        for _, invocation in self.invocations():
            for key, value in invocation.counts().items():
                counts[key] += value
        return counts

    def test_times(self) -> dict:
        """Return per-test timings across every invocation of a repeated run.

        Returns:
            ``{"class#method": [{"time", "status", "invocation"}, ...]}`` — one
            entry per invocation the test appeared in, which is what makes a
            flaky or slow test visible across repetitions.
        """
        times: dict = {}

        for number, invocation in self.invocations():
            for xml_file in invocation.directory.glob("TEST-*.xml"):
                try:
                    root = ET.parse(xml_file).getroot()
                    for testcase in root.findall("testcase"):
                        key = (f"{testcase.get('classname', '')}"
                               f"#{testcase.get('name', '')}")
                        times.setdefault(key, []).append({
                            "time": float(testcase.get("time", 0)),
                            "status": _testcase_status(testcase),
                            "invocation": number
                        })
                except Exception:
                    pass

        return times

    def parse(self, include_all_tests: bool = False,
              truncate_stacktraces: bool = True,
              invocation: Optional[int] = None) -> tuple[list, list, dict]:
        """Parse every report into failures, optional per-test entries, and a summary.

        Args:
            include_all_tests: Whether to collect all test entries.
            truncate_stacktraces: Whether to truncate long stacktraces.
            invocation: If set, adds an 'invocation' field to each entry.

        Returns:
            Tuple of (failures, all_tests_or_empty, summary_dict).
        """
        failures = []
        all_tests = []
        summary = _empty_summary()

        for xml_file in self.directory.glob("TEST-*.xml"):
            try:
                root = ET.parse(xml_file).getroot()

                for testcase in root.findall("testcase"):
                    self._read_testcase(
                        testcase, failures, all_tests, summary,
                        include_all_tests, truncate_stacktraces, invocation)
            except Exception:
                pass

        return failures, all_tests, summary

    def _read_testcase(self, testcase, failures: list, all_tests: list,
                       summary: dict, include_all_tests: bool,
                       truncate_stacktraces: bool,
                       invocation: Optional[int]) -> None:
        """Fold one ``<testcase>`` into the accumulating results."""
        classname = testcase.get("classname", "")
        name = testcase.get("name", "")
        time_sec = float(testcase.get("time", 0))
        status = _testcase_status(testcase)

        # Every status is also a summary key, so the tally needs no mapping.
        summary["total"] += 1
        summary[status] += 1

        test_info = {
            "class": classname,
            "method": name,
            "time_seconds": time_sec,
            "status": status
        }
        if invocation is not None:
            test_info["invocation"] = invocation

        # A failed assertion and a thrown error differ only in which element
        # carries the detail and which tally they land in; the reported entry
        # is otherwise identical, so they share one path.
        problem = (testcase.find("failure") if status == "failed"
                   else testcase.find("error") if status == "error" else None)

        if problem is not None:
            stacktrace = problem.text or ""
            if truncate_stacktraces:
                stacktrace = truncate_stacktrace(stacktrace)

            entry = {
                "class": classname,
                "method": name,
                "time_seconds": time_sec,
                "type": problem.get("type", ""),
                "message": problem.get("message", ""),
                "stacktrace": stacktrace
            }
            if invocation is not None:
                entry["invocation"] = invocation
            failures.append(entry)

        if include_all_tests:
            all_tests.append(test_info)

    def collect_failures(self, include_all_tests: bool = False,
                         truncate_stacktraces: bool = True,
                         repetitions: int = 1) -> dict:
        """Return failures and a summary, aggregating invocations when repeated.

        A repeated run keeps each pass in its own subdirectory, so its results
        are the sum over those; a single run reads the directory directly.

        Returns:
            dict with ``failures``, ``summary``, and ``all_tests`` when asked.
        """
        if repetitions > 1:
            all_failures = []
            all_tests = []
            total = _empty_summary()

            for number, invocation in self.invocations():
                found, tests, summary = invocation.parse(
                    include_all_tests, truncate_stacktraces, invocation=number)
                all_failures.extend(found)
                all_tests.extend(tests)
                for key in total:
                    total[key] += summary[key]
        else:
            all_failures, all_tests, total = self.parse(
                include_all_tests, truncate_stacktraces)

        result = {"failures": all_failures, "summary": total}
        if include_all_tests:
            result["all_tests"] = all_tests
        return result


def empty_failures(run_id: str) -> dict:
    """Return the result shape used when a run produced no reports at all."""
    return {"run_id": run_id, "failures": [], "summary": _empty_summary()}
