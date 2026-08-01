"""Tests for workstream_context and workstream_get_job MCP tools.

Covers the narrative/tools that provide job history and cost information:

- workstream_context returns branch memories, commits, and a compact jobs timeline
- workstream_get_job returns operational details for a specific job including cost
- job cost data is included in job responses and weekly stats
"""

import os
import sys
import unittest
from unittest.mock import patch, MagicMock

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
    import server


def _grant_all_scopes():
    server._set_scopes(
        ["read", "write", "submit", "pipeline", "github", "memory-read", "memory-write"],
        label="test",
    )


class TestWorkstreamContext(unittest.TestCase):
    """Tests for workstream_context MCP tool."""

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_workstream_context_returns_ok_with_valid_workstream(
        self, mock_client_fn, mock_controller_get, mock_gh
    ):
        """workstream_context returns ok=True when workstream is found."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        mock_controller_get.return_value = [
            {"workstreamId": "w-1", "repoUrl": "https://github.com/org/repo",
             "defaultBranch": "feature/x", "baseBranch": "master"}
        ]
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_workstream_context_includes_memories_from_memory_client(
        self, mock_client_fn, mock_controller_get, mock_gh
    ):
        """workstream_context includes memories from the memory service."""
        _grant_all_scopes()
        memories = [
            {"id": "m1", "content": "test memory", "created_at": "2026-04-01",
             "tags": ["message"], "namespace": "messages"}
        ]
        client = MagicMock()
        client.search_by_branch.return_value = memories
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        mock_controller_get.return_value = [
            {"workstreamId": "w-1", "repoUrl": "https://github.com/org/repo",
             "defaultBranch": "feature/x", "baseBranch": "master"}
        ]
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertIn("memories", result)
        self.assertEqual(len(result["memories"]), 1)

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_workstream_context_includes_jobs_timeline(
        self, mock_client_fn, mock_controller_get, mock_gh
    ):
        """workstream_context includes a compact jobs timeline."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [{"workstreamId": "w-1", "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x", "baseBranch": "master"}]
        jobs_list = [
            {"jobId": "j-1", "timestamp": "2026-04-20T10:00:00Z",
             "status": "SUCCESS", "description": "Fix bug",
             "commitHash": "abc1234567890def",
             "pullRequestUrl": "https://github.com/org/repo/pull/7",
             "costUsd": 4.50}
        ]
        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                return jobs_list
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertIn("jobs", result)
        self.assertEqual(1, len(result["jobs"]))
        self.assertEqual("j-1", result["jobs"][0]["jobId"])


class TestWorkstreamGetJob(unittest.TestCase):
    """Tests for workstream_get_job MCP tool."""

    @patch.object(server, "_controller_get")
    def test_workstream_get_job_returns_job_details(self, mock_controller_get):
        """workstream_get_job returns operational details for a specific job."""
        _grant_all_scopes()
        mock_controller_get.return_value = {
            "jobId": "j-123",
            "status": "SUCCESS",
            "description": "Test job",
            "timestamp": "2026-04-20T10:00:00Z",
            "costUsd": 2.50,
            "targetBranch": "feature/test",
            "commitHash": "abc123",
            "pullRequestUrl": "https://github.com/org/repo/pull/5"
        }
        result = server.workstream_get_job(job_id="j-123")
        self.assertEqual(result["jobId"], "j-123")
        self.assertEqual(result["status"], "SUCCESS")
        self.assertIn("costUsd", result)

    def test_workstream_get_job_requires_read_scope(self):
        """workstream_get_job requires read scope."""
        server._set_scopes(["write"], label="test")
        with self.assertRaises(PermissionError):
            server.workstream_get_job(job_id="j-123")

    @patch.object(server, "_controller_get")
    def test_workstream_get_job_long_id_rejected(self, mock_controller_get):
        """workstream_get_job validates that job_id does not exceed max length."""
        _grant_all_scopes()
        long_id = "x" * 1500
        result = server.workstream_get_job(job_id=long_id)
        self.assertFalse(result["ok"])
        self.assertIn("job_id", result["error"].lower())


class TestJobCostReporting(unittest.TestCase):
    """Tests for job cost reporting in workstream responses."""

    @patch.object(server, "_controller_get")
    def test_job_cost_included_in_workstream_get_job_response(self, mock_controller_get):
        """workstream_get_job includes costUsd in the response."""
        _grant_all_scopes()
        mock_controller_get.return_value = {
            "jobId": "j-cost-1",
            "status": "SUCCESS",
            "costUsd": 1.75
        }
        result = server.workstream_get_job(job_id="j-cost-1")
        self.assertIn("costUsd", result)
        self.assertEqual(result["costUsd"], 1.75)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_job_cost_by_model_in_weekly_stats(self, mock_client_fn, mock_controller_get):
        """Weekly stats include cost breakdown by model."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_controller_get.return_value = {
            "workstreamId": "w-cost",
            "thisWeek": {
                "jobCount": 5,
                "costUsd": 10.0,
                "costByModel": {
                    "claude-sonnet-4-6": 6.0,
                    "openrouter/minimax/minimax-m2.7": 4.0
                }
            }
        }
        result = server.workstream_get_status(workstream_id="w-cost")
        self.assertIn("thisWeek", result)
        self.assertIn("costByModel", result["thisWeek"])


if __name__ == "__main__":
    unittest.main()
