"""Unit tests for github_check_status overall-status derivation."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import github_check_status as gcs


def _run(status="completed", conclusion="success", head_sha="sha"):
    return {"status": status, "conclusion": conclusion, "head_sha": head_sha}


class TestCommitStatusSignal(unittest.TestCase):

    def test_success(self):
        self.assertEqual(gcs.commit_status_signal("success"), ("completed", "success"))

    def test_failure_and_error_map_to_failure(self):
        self.assertEqual(gcs.commit_status_signal("failure"), ("completed", "failure"))
        self.assertEqual(gcs.commit_status_signal("error"), ("completed", "failure"))

    def test_pending(self):
        self.assertEqual(gcs.commit_status_signal("pending"), ("in_progress", None))


class TestOverallStatus(unittest.TestCase):

    def test_no_runs_without_errors(self):
        self.assertEqual(gcs.overall_status([], [], "", False, False), "no_runs")

    def test_unknown_when_errors_and_no_signal(self):
        self.assertEqual(gcs.overall_status([], [], "", False, True), "unknown")

    def test_stale_when_runs_not_current(self):
        self.assertEqual(
            gcs.overall_status([_run()], [], "", False, False), "stale")

    def test_check_runs_take_precedence(self):
        # A failing check run outranks a passing workflow run.
        overall = gcs.overall_status(
            [_run(conclusion="success")],
            [{"status": "completed", "conclusion": "failure"}],
            "", True, False)
        self.assertEqual(overall, "failure")

    def test_falls_back_to_workflow_runs(self):
        overall = gcs.overall_status(
            [_run(conclusion="failure"), _run(conclusion="success")],
            [], "", True, True)
        self.assertEqual(overall, "failure")

    def test_falls_back_to_commit_status(self):
        overall = gcs.overall_status([], [], "success", True, True)
        self.assertEqual(overall, "success")

    def test_incomplete_is_pending(self):
        overall = gcs.overall_status(
            [], [{"status": "in_progress", "conclusion": None}], "", True, False)
        self.assertEqual(overall, "pending")


if __name__ == "__main__":
    unittest.main()
