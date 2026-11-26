"""Integration tests: ``workstream_context`` surfaces enforcement loops.

Exercises the whole tool (memory client + jobs timeline + loop summarization)
to confirm a synthetic three-job loop is reported as one loop entry, the jobs
stream is collapsed by default, and ``collapse_loops=False`` restores every
individual job.
"""

import os
import sys
import unittest
from unittest.mock import MagicMock, patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import server, _grant_all_scopes  # noqa: E402


_WS_LIST = [{
    "workstreamId": "w-1",
    "repoUrl": "https://github.com/org/repo",
    "defaultBranch": "feature/x",
    "baseBranch": "master",
}]

# A synthetic loop: three consecutive jobs, same enforcement phase, same
# verdict, on the same commit — exactly the "no-op review pass on the same
# commit" pattern the task cites.
_LOOP_JOBS = [
    {"jobId": "j3", "timestamp": "2026-04-20T12:00:00Z",
     "status": "SUCCESS", "description": "no-op review pass", "commitHash": "abcdef1234"},
    {"jobId": "j2", "timestamp": "2026-04-20T11:00:00Z",
     "status": "SUCCESS", "description": "no-op review pass", "commitHash": "abcdef1234"},
    {"jobId": "j1", "timestamp": "2026-04-20T10:00:00Z",
     "status": "SUCCESS", "description": "no-op review pass", "commitHash": "abcdef1234"},
]


class WorkstreamContextLoopTests(unittest.TestCase):

    def _controller(self, jobs):
        def side_effect(path, timeout=10):
            if "/jobs" in path:
                return jobs
            return _WS_LIST
        return side_effect

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_three_job_loop_reported_as_one_entry(
            self, mock_client_fn, mock_controller_get, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        mock_controller_get.side_effect = self._controller(_LOOP_JOBS)

        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        # One loop detected, spanning the three jobs.
        self.assertEqual(len(result["loops"]), 1)
        loop = result["loops"][0]
        self.assertEqual(loop["kind"], "jobs")
        self.assertEqual(loop["count"], 3)
        self.assertEqual(loop["from"], "2026-04-20T10:00:00Z")
        self.assertEqual(loop["to"], "2026-04-20T12:00:00Z")
        # The jobs stream is collapsed to the single representative.
        self.assertEqual(len(result["jobs"]), 1)
        self.assertEqual(result["jobs"][0]["loop"]["count"], 3)

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_collapse_loops_false_keeps_every_job(
            self, mock_client_fn, mock_controller_get, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        mock_controller_get.side_effect = self._controller(_LOOP_JOBS)

        result = server.workstream_context(
            workstream_id="w-1", collapse_loops=False)
        self.assertTrue(result["ok"])
        # Loop still reported, but all three jobs remain listed.
        self.assertEqual(len(result["loops"]), 1)
        self.assertEqual(len(result["jobs"]), 3)
        self.assertNotIn("loop", result["jobs"][0])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_no_loop_when_jobs_distinct(
            self, mock_client_fn, mock_controller_get, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        distinct = [
            {"jobId": "j3", "timestamp": "2026-04-20T12:00:00Z",
             "status": "SUCCESS", "description": "merge PR"},
            {"jobId": "j2", "timestamp": "2026-04-20T11:00:00Z",
             "status": "FAILURE", "description": "enforce changes"},
            {"jobId": "j1", "timestamp": "2026-04-20T10:00:00Z",
             "status": "SUCCESS", "description": "primary"},
        ]
        mock_controller_get.side_effect = self._controller(distinct)

        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertEqual(result["loops"], [])
        self.assertEqual(len(result["jobs"]), 3)

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_near_identical_memories_within_one_job_collapsed(
            self, mock_client_fn, mock_controller_get, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = [
            {"id": "m3", "namespace": "review", "created_at": "2026-04-20T10:40:00+00:00",
             "content": "OOP review done on Foo: CLEAN"},
            {"id": "m2", "namespace": "review", "created_at": "2026-04-20T10:30:00+00:00",
             "content": "OOP review done on Foo: CLEAN"},
            {"id": "m1", "namespace": "review", "created_at": "2026-04-20T10:20:00+00:00",
             "content": "OOP review done on Foo: CLEAN"},
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        jobs = [{"jobId": "job-1", "timestamp": "2026-04-20T10:00:00Z",
                 "status": "RUNNING", "description": "primary"}]
        mock_controller_get.side_effect = self._controller(jobs)

        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        memory_loops = [lp for lp in result["loops"] if lp["kind"] == "memories"]
        self.assertEqual(len(memory_loops), 1)
        self.assertEqual(memory_loops[0]["count"], 3)
        self.assertEqual(memory_loops[0]["job_id"], "job-1")
        # Collapsed to a single representative memory.
        self.assertEqual(result["count"], 1)


if __name__ == "__main__":
    unittest.main()
