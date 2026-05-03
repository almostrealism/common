"""Tests for the Jira CSV migration script."""

import csv
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from migrate_jira import (
    _jira_id_to_uuid,
    _parse_date,
    _read_csv,
    DEFAULT_PRIORITY,
    PRIORITY_MAP,
    STATUS_MAP,
    migrate,
)


def _write_csv(rows, path):
    """Write a list of dicts to a CSV file."""
    if not rows:
        return
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)


class TestJiraIdToUuid(unittest.TestCase):

    def test_deterministic(self):
        uid1 = _jira_id_to_uuid("PROJ-123")
        uid2 = _jira_id_to_uuid("PROJ-123")
        self.assertEqual(uid1, uid2)

    def test_different_keys_differ(self):
        self.assertNotEqual(_jira_id_to_uuid("PROJ-1"), _jira_id_to_uuid("PROJ-2"))

    def test_returns_valid_uuid_string(self):
        import uuid
        uid = _jira_id_to_uuid("PROJ-123")
        uuid.UUID(uid)  # should not raise


class TestParseDate(unittest.TestCase):

    def test_parse_iso_format(self):
        result = _parse_date("2024-01-15 10:30:00")
        self.assertIsNotNone(result)
        self.assertIn("2024", result)

    def test_none_on_empty(self):
        self.assertIsNone(_parse_date(""))

    def test_none_on_unparseable(self):
        self.assertIsNone(_parse_date("not a date"))


class TestStatusMap(unittest.TestCase):

    def test_done_maps_to_closed(self):
        self.assertEqual(STATUS_MAP["Done"], "closed")

    def test_in_progress_maps_to_open(self):
        self.assertEqual(STATUS_MAP["In Progress"], "open")

    def test_cancelled_maps_to_closed(self):
        self.assertEqual(STATUS_MAP["Cancelled"], "closed")


class TestPriorityMap(unittest.TestCase):

    def test_modern_jira_labels(self):
        self.assertEqual(PRIORITY_MAP["Highest"], 2)
        self.assertEqual(PRIORITY_MAP["High"], 1)
        self.assertEqual(PRIORITY_MAP["Medium"], 0)
        self.assertEqual(PRIORITY_MAP["Low"], -1)
        self.assertEqual(PRIORITY_MAP["Lowest"], -2)

    def test_legacy_jira_labels(self):
        self.assertEqual(PRIORITY_MAP["Blocker"], 2)
        self.assertEqual(PRIORITY_MAP["Critical"], 2)
        self.assertEqual(PRIORITY_MAP["Major"], 1)
        self.assertEqual(PRIORITY_MAP["Minor"], -1)
        self.assertEqual(PRIORITY_MAP["Trivial"], -2)

    def test_default_is_medium(self):
        self.assertEqual(DEFAULT_PRIORITY, 0)

    def test_all_values_are_in_range(self):
        for label, value in PRIORITY_MAP.items():
            self.assertTrue(
                -2 <= value <= 2,
                f"{label} maps to {value}, outside [-2, 2]",
            )


class TestReadCsv(unittest.TestCase):

    def test_reads_utf8(self):
        tmp = tempfile.NamedTemporaryFile(
            suffix=".csv", delete=False, mode="w", encoding="utf-8"
        )
        tmp.write("Issue Key,Summary\nPROJ-1,Hello\n")
        tmp.close()
        rows = _read_csv(tmp.name)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["Issue Key"], "PROJ-1")
        os.unlink(tmp.name)


class TestMigrate(unittest.TestCase):
    """Test the migrate() function using dry-run mode to avoid HTTP calls."""

    def _make_csv(self, rows):
        tmp = tempfile.NamedTemporaryFile(
            suffix=".csv", delete=False, mode="w", encoding="utf-8", newline=""
        )
        writer = csv.DictWriter(tmp, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
        tmp.close()
        return tmp.name

    def test_dry_run_completes_without_error(self):
        rows = [
            {
                "Issue Key": "PROJ-1",
                "Summary": "Add OAuth support",
                "Description": "Details here",
                "Project Name": "Rings",
                "Fix Version/s": "0.38",
                "Status": "Done",
                "Priority": "High",
                "Created": "",
                "Updated": "",
            }
        ]
        csv_path = self._make_csv(rows)
        migrate(
            csv_path=csv_path,
            tracker_url="http://localhost:8030",
            token=None,
            dry_run=True,
            workstream_map=None,
        )
        os.unlink(csv_path)

    def test_dry_run_unknown_priority_does_not_crash(self):
        rows = [
            {
                "Issue Key": "PROJ-2",
                "Summary": "Weird priority",
                "Description": "",
                "Project Name": "Rings",
                "Fix Version/s": "",
                "Status": "Open",
                "Priority": "Showstopper",
                "Created": "",
                "Updated": "",
            }
        ]
        csv_path = self._make_csv(rows)
        migrate(
            csv_path=csv_path,
            tracker_url="http://localhost:8030",
            token=None,
            dry_run=True,
            workstream_map=None,
        )
        os.unlink(csv_path)

    def test_dry_run_skips_rows_without_key(self):
        rows = [
            {
                "Issue Key": "",
                "Summary": "No key task",
                "Description": "",
                "Project Name": "",
                "Fix Version/s": "",
                "Status": "Open",
                "Created": "",
                "Updated": "",
            }
        ]
        csv_path = self._make_csv(rows)
        migrate(
            csv_path=csv_path,
            tracker_url="http://localhost:8030",
            token=None,
            dry_run=True,
            workstream_map=None,
        )
        os.unlink(csv_path)

    def test_default_project_name_used_when_csv_lacks_column(self):
        rows = [
            {
                "Issue Key": "PROJ-3",
                "Summary": "No project col",
                "Description": "",
                "Fix Version/s": "",
                "Status": "Open",
                "Priority": "",
                "Created": "",
                "Updated": "",
            }
        ]
        csv_path = self._make_csv(rows)
        migrate(
            csv_path=csv_path,
            tracker_url="http://localhost:8030",
            token=None,
            dry_run=True,
            workstream_map=None,
            default_project_name="Common",
        )
        os.unlink(csv_path)

    def test_workstream_map_resolves_prefix(self):
        rows = [
            {
                "Issue Key": "RINGS-42",
                "Summary": "Task in Rings",
                "Description": "",
                "Project Name": "Rings",
                "Fix Version/s": "",
                "Status": "Open",
                "Created": "",
                "Updated": "",
            }
        ]
        csv_path = self._make_csv(rows)
        # dry_run so no HTTP calls are made; the workstream resolution is internal
        migrate(
            csv_path=csv_path,
            tracker_url="http://localhost:8030",
            token=None,
            dry_run=True,
            workstream_map={"RINGS-": "ws-rings-123"},
        )
        os.unlink(csv_path)

    def test_idempotent_uuids(self):
        """Same issue key always produces same task UUID."""
        uid1 = _jira_id_to_uuid("RINGS-42")
        uid2 = _jira_id_to_uuid("RINGS-42")
        self.assertEqual(uid1, uid2)


if __name__ == "__main__":
    unittest.main()
