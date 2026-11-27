"""Tests that a note naming a file changed after the note was written is
history: the snapshot index records file change dates (``path_dates``), reads
them from ``git log`` output, and the tier rule demotes such a note to C."""

import json
import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timezone

_DIR = os.path.dirname(os.path.abspath(__file__))
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

from consult_evidence import TIER_CURRENT, TIER_HISTORICAL, TIER_STALE  # noqa: E402
from consult_tiers import (  # noqa: E402
    RECENCY_WINDOW_DAYS, STATE_TARGET, BranchState, Caller, tier,
)
from snapshot_index import (  # noqa: E402
    PATH_DATES_WINDOW_DAYS, SnapshotIndex, main, parse_git_log_dates,
)

_LOG = """@2026-10-15T09:00:00+00:00
tools/ci/agent-protection/validate-agent-commit.sh
docs/README.md

@2026-10-01T09:00:00+00:00
tools/ci/agent-protection/validate-agent-commit.sh
tools/ci/README.md
"""

_PATHS = ["tools/ci/agent-protection/validate-agent-commit.sh", "tools/ci/README.md",
          "docs/README.md", "tools/ci/agent-protection/other.sh"]

WRITTEN = datetime(2026, 10, 10, tzinfo=timezone.utc)


def _index(dates=None):
    data = {"paths": _PATHS}
    if dates is not None:
        data["path_dates"] = dates
    return SnapshotIndex(data)


class TestGitLogDates(unittest.TestCase):

    def test_the_newest_change_of_each_file_wins(self):
        dates = parse_git_log_dates(_LOG)
        self.assertEqual(dates["since_days"], PATH_DATES_WINDOW_DAYS)
        self.assertEqual(dates["dates"], {
            "tools/ci/agent-protection/validate-agent-commit.sh": "2026-10-15T09:00:00+00:00",
            "docs/README.md": "2026-10-15T09:00:00+00:00",
            "tools/ci/README.md": "2026-10-01T09:00:00+00:00",
        })

    def test_the_latest_author_date_wins_whatever_the_log_order(self):
        # A rebased commit lists first (newest commit date) yet carries an
        # older author date than a commit listed after it.
        log = ("@2026-10-01T09:00:00+00:00\ntools/ci/README.md\n\n"
               "@2026-10-15T09:00:00+02:00\ntools/ci/README.md\n\n"
               "@2026-09-01T09:00:00Z\ntools/ci/README.md\n")
        self.assertEqual(parse_git_log_dates(log)["dates"],
                         {"tools/ci/README.md": "2026-10-15T09:00:00+02:00"})
        index = _index(parse_git_log_dates(log))
        self.assertEqual(index.changed_after(["tools/ci/README.md"], WRITTEN),
                         ["tools/ci/README.md"])

    def test_a_malformed_date_line_contributes_nothing(self):
        log = ("@not-a-date\ndocs/README.md\n\n"
               "@2026-10-01T09:00:00+00:00\ntools/ci/README.md\n")
        self.assertEqual(parse_git_log_dates(log)["dates"],
                         {"tools/ci/README.md": "2026-10-01T09:00:00+00:00"})

    def test_empty_log_records_no_dates(self):
        self.assertEqual(parse_git_log_dates("")["dates"], {})

    def test_the_window_covers_the_recency_window(self):
        self.assertGreaterEqual(PATH_DATES_WINDOW_DAYS, RECENCY_WINDOW_DAYS)

    def _tree(self):
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        for rel in ("tools/ci/README.md", "tools/ci/agent-protection/validate-agent-commit.sh"):
            os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
            with open(os.path.join(root, rel), "w") as handle:
                handle.write("x")
        return root

    def _build(self, root, dates_file):
        out = os.path.join(root, "out", "index.json")
        self.assertEqual(main(["--root", root, "--path-dates", dates_file, "--out", out]), 0)
        with open(out) as handle:
            return json.load(handle)

    def test_the_build_records_dates_only_for_indexed_files(self):
        root = self._tree()
        log = os.path.join(root, "dates.txt")
        with open(log, "w") as handle:
            handle.write(_LOG)
        index = self._build(root, log)
        self.assertEqual(index["path_dates"]["dates"], {
            "tools/ci/README.md": "2026-10-01T09:00:00+00:00",
            "tools/ci/agent-protection/validate-agent-commit.sh": "2026-10-15T09:00:00+00:00",
        })

    def test_a_missing_or_empty_dates_file_records_none(self):
        root = self._tree()
        self.assertNotIn("path_dates", self._build(root, os.path.join(root, "absent.txt")))
        empty = os.path.join(root, "empty.txt")
        open(empty, "w").close()
        self.assertNotIn("path_dates", self._build(root, empty))


class TestChangedAfter(unittest.TestCase):

    def test_a_file_changed_after_the_note_is_reported(self):
        index = _index(parse_git_log_dates(_LOG))
        self.assertEqual(index.changed_after(
            ["tools/ci/agent-protection/validate-agent-commit.sh"], WRITTEN),
            ["tools/ci/agent-protection/validate-agent-commit.sh"])

    def test_a_fragment_or_unique_file_name_resolves(self):
        index = _index(parse_git_log_dates(_LOG))
        self.assertEqual(index.changed_after(["validate-agent-commit.sh"], WRITTEN),
                         ["validate-agent-commit.sh"])

    def test_a_file_changed_before_the_note_is_not(self):
        index = _index(parse_git_log_dates(_LOG))
        self.assertEqual(index.changed_after(["tools/ci/README.md"], WRITTEN), [])

    def test_an_ambiguous_name_or_a_directory_is_not_judged(self):
        index = _index(parse_git_log_dates(_LOG))
        self.assertEqual(index.changed_after(["README.md", "tools/ci/"], WRITTEN), [])

    def test_without_dates_nothing_changed(self):
        self.assertEqual(_index().changed_after(
            ["tools/ci/agent-protection/validate-agent-commit.sh"], WRITTEN), [])
        self.assertEqual(_index(parse_git_log_dates(_LOG)).changed_after(
            ["validate-agent-commit.sh"], None), [])


class TestTierOfAChangedNote(unittest.TestCase):

    def _tier(self, changed=None, absent=()):
        return tier({"created_at": "2026-10-10", "branch": "master"},
                    BranchState(STATE_TARGET), Caller(), "2026-10-19T00:00:00Z",
                    list(absent), now=datetime(2026, 10, 19, tzinfo=timezone.utc),
                    changed_names=changed)

    def test_a_note_naming_a_changed_file_is_history(self):
        level, why = self._tier(["validate-agent-commit.sh"])
        self.assertEqual(level, TIER_HISTORICAL)
        self.assertIn("validate-agent-commit.sh", why)

    def test_an_unchanged_note_stays_current(self):
        self.assertEqual(self._tier()[0], TIER_CURRENT)
        self.assertEqual(self._tier([])[0], TIER_CURRENT)

    def test_an_absent_name_still_makes_it_stale(self):
        self.assertEqual(self._tier(["a.sh"], absent=["Gone"])[0], TIER_STALE)


if __name__ == "__main__":
    unittest.main()
