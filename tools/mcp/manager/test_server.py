"""Tests for all ar-manager MCP tools.

Each tool is tested by mocking the underlying HTTP calls
(_controller_get, _controller_post, _github_request, _get_memory_client)
so no running controller, GitHub, or ar-memory server is required.
"""

import importlib
import json
import os
import sys
import unittest
from unittest import mock
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

# Ensure the manager package is importable
_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

# Suppress startup prints during import
with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
    import server


def _grant_all_scopes():
    """Grant all scopes for the current request context."""
    server._set_scopes(
        [
            "read",
            "write",
            "submit",
            "pipeline",
            "github",
            "memory-read",
            "memory-write",
        ],
        label="test",
    )


def _grant_scopes(*scopes):
    """Grant specific scopes for the current request context."""
    server._set_scopes(list(scopes), label="test")


def _clear_scopes():
    """Clear scopes (simulates no-auth mode)."""
    server._request_scopes.set(None)
    server._request_token_label.set(None)
    if hasattr(server._thread_local, "scopes"):
        del server._thread_local.scopes
    if hasattr(server._thread_local, "token_label"):
        del server._thread_local.token_label


# -----------------------------------------------------------------------
# Tier 1: Universal tools
# -----------------------------------------------------------------------


class TestControllerHealth(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_returns_health(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {
            "status": "ok",
            "version": "1.0",
            "server_time": "2026-05-11T18:23:45.123456789Z",
        }
        result = server.controller_health()
        mock_get.assert_called_once_with("/api/health")
        self.assertEqual(result["status"], "ok")
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_get")
    def test_server_time_present_and_utc(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {
            "status": "ok",
            "server_time": "2026-05-11T18:23:45.123456789Z",
        }
        result = server.controller_health()
        self.assertIn("server_time", result)
        server_time = result["server_time"]
        # Must match ISO-8601 UTC: YYYY-MM-DDTHH:MM:SS[.fractional]Z
        import re
        iso_utc_pattern = re.compile(
            r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$'
        )
        self.assertRegex(
            server_time,
            iso_utc_pattern,
            f"server_time must be ISO-8601 UTC (ending in Z), got: {server_time!r}",
        )

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.controller_health()


class TestAgentOptions(unittest.TestCase):
    """Tests for the agent_options tool."""

    @patch.object(server, "_controller_get")
    def test_returns_controller_response(self, mock_get):
        """agent_options proxies the /api/agents response as-is."""
        _grant_all_scopes()
        mock_response = {
            "ok": True,
            "runners": [
                {
                    "name": "claude",
                    "capabilities": {
                        "reportsCost": True,
                        "reportsTurns": True,
                        "supportsEffortLevel": True,
                        "supportsMaxBudget": True,
                        "supportsMcpHttpTransport": True,
                        "supportsMcpStdioTransport": True,
                        "supportsPermissionDenialReporting": True,
                        "supportedModels": ["sonnet", "opus"],
                    },
                },
                {
                    "name": "opencode",
                    "capabilities": {
                        "reportsCost": False,
                        "reportsTurns": False,
                        "supportsEffortLevel": False,
                        "supportsMaxBudget": False,
                        "supportsMcpHttpTransport": False,
                        "supportsMcpStdioTransport": True,
                        "supportsPermissionDenialReporting": False,
                        "supportedModels": [],
                    },
                },
            ],
            "phases": [
                {"name": "primary", "description": "Primary work."},
                {"name": "deduplication", "description": "Deduplication audit."},
            ],
            "models": ["sonnet", "opus", "haiku"],
            "defaultRunner": "claude",
        }
        mock_get.return_value = mock_response
        result = server.agent_options()
        mock_get.assert_called_once_with("/api/agents")
        self.assertTrue(result["ok"])
        self.assertEqual(result["defaultRunner"], "claude")
        self.assertEqual(len(result["runners"]), 2)
        runner_names = [r["name"] for r in result["runners"]]
        self.assertIn("claude", runner_names)
        self.assertIn("opencode", runner_names)
        self.assertEqual(len(result["phases"]), 2)
        self.assertEqual(result["phases"][0]["name"], "primary")
        self.assertIn("models", result)

    def test_requires_read_scope(self):
        """agent_options must require at least read scope."""
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.agent_options()

    @patch.object(server, "_controller_get")
    def test_phase_list_wired_correctly(self, mock_get):
        """Phases list in response must contain name and description keys."""
        _grant_all_scopes()
        mock_get.return_value = {
            "ok": True,
            "runners": [],
            "phases": [{"name": "primary", "description": "Primary work."}],
            "models": [],
            "defaultRunner": "claude",
        }
        result = server.agent_options()
        phases = result.get("phases", [])
        self.assertTrue(len(phases) > 0)
        for phase in phases:
            self.assertIn("name", phase)
            self.assertIn("description", phase)


class TestControllerUpdateConfig(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_read_current_setting(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {"acceptAutomatedJobs": True}
        result = server.controller_update_config()
        mock_get.assert_called_once_with("/api/config/accept-automated-jobs")
        self.assertTrue(result["acceptAutomatedJobs"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_set_false(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": False}
        result = server.controller_update_config(accept_automated_jobs="false")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": False})
        self.assertFalse(result["acceptAutomatedJobs"])

    @patch.object(server, "_controller_post")
    def test_set_true(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": True}
        result = server.controller_update_config(accept_automated_jobs="true")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": True})
        self.assertTrue(result["acceptAutomatedJobs"])

    @patch.object(server, "_controller_post")
    def test_case_insensitive(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": True}
        server.controller_update_config(accept_automated_jobs="True")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": True})

    @patch.object(server, "_controller_post")
    def test_non_true_string_treated_as_false(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": False}
        server.controller_update_config(accept_automated_jobs="no")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": False})

    def test_requires_write_scope(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server.controller_update_config()


class TestWorkstreamList(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_returns_list(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = [
            {"workstreamId": "ws-1", "pipelineCapable": True},
            {"workstreamId": "ws-2", "pipelineCapable": False},
        ]
        result = server.workstream_list()
        mock_get.assert_called_once_with("/api/workstreams")
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 2)
        self.assertEqual(len(result["workstreams"]), 2)
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_get")
    def test_error_from_controller(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {"ok": False, "error": "Connection refused"}
        result = server.workstream_list()
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.workstream_list()

    @patch.object(server, "_controller_get")
    def test_include_archived_flag(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = [
            {"workstreamId": "ws-1"},
            {"workstreamId": "ws-2", "archived": True},
        ]
        result = server.workstream_list(include_archived=True)
        mock_get.assert_called_once_with("/api/workstreams?includeArchived=true")
        self.assertEqual(result["count"], 2)
        archived = [w for w in result["workstreams"] if w.get("archived")]
        self.assertEqual(len(archived), 1)
        self.assertEqual(archived[0]["workstreamId"], "ws-2")


class TestWorkstreamArchive(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_archive_passes_slack_flag(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": True, "workstreamId": "ws-archive",
            "archivedAt": "2026-05-19T00:00:00Z",
            "slackChannelArchived": True,
        }
        result = server.workstream_archive(workstream_id="ws-archive")
        path, payload = mock_post.call_args[0][:2]
        self.assertEqual(path, "/api/workstreams/ws-archive/archive")
        self.assertEqual(payload, {"archiveSlackChannel": True})
        self.assertTrue(result["ok"])
        self.assertTrue(result["slackChannelArchived"])

    @patch.object(server, "_controller_post")
    def test_archive_can_skip_slack(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-a"}
        server.workstream_archive(
            workstream_id="ws-a", archive_slack_channel=False)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload, {"archiveSlackChannel": False})

    @patch.object(server, "_controller_post")
    def test_archive_surfaces_active_job_error(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": False,
            "error": "workstream has 2 active jobs; cancel or wait for completion"
                     " before archiving. Active job IDs: job-a, job-b",
        }
        result = server.workstream_archive(workstream_id="ws-busy")
        self.assertFalse(result["ok"])
        self.assertIn("active job", result["error"])
        self.assertIn("job-a", result["error"])

    def test_archive_requires_write_scope(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server.workstream_archive(workstream_id="ws-x")


class TestWorkstreamUnarchive(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_unarchive_posts_empty_body(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-restore"}
        result = server.workstream_unarchive(workstream_id="ws-restore")
        path, payload = mock_post.call_args[0][:2]
        self.assertEqual(path, "/api/workstreams/ws-restore/unarchive")
        self.assertEqual(payload, {})
        self.assertTrue(result["ok"])

    def test_unarchive_requires_write_scope(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server.workstream_unarchive(workstream_id="ws-x")


class TestWorkstreamDelete(unittest.TestCase):

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_post")
    def test_delete_clears_tracker_linkage(
            self, mock_post, mock_tracker_get, mock_tracker_put):
        _grant_all_scopes()
        # First call returns two tasks; second call returns empty (tasks are gone).
        mock_tracker_get.side_effect = [
            {"ok": True, "tasks": [{"id": "task-1"}, {"id": "task-2"}]},
            {"ok": True, "tasks": []},
        ]
        mock_tracker_put.return_value = {"ok": True}
        mock_post.return_value = {"ok": True, "workstreamId": "ws-gone"}
        result = server.workstream_delete(workstream_id="ws-gone")
        self.assertTrue(result["ok"])
        self.assertEqual(result["deletedTrackerTasks"], 2)
        self.assertNotIn("trackerCleanupWarning", result)
        # Tracker tasks were cleared
        clear_calls = mock_tracker_put.call_args_list
        self.assertEqual(len(clear_calls), 2)
        for call in clear_calls:
            self.assertEqual(call.args[1], {"workstream_id": None})
        # Controller endpoint was hit with force=False by default
        path, payload = mock_post.call_args[0][:2]
        self.assertEqual(path, "/api/workstreams/ws-gone/delete")
        self.assertEqual(payload, {"force": False})

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_post")
    def test_delete_force_passes_through(
            self, mock_post, mock_tracker_get, mock_tracker_put):
        _grant_all_scopes()
        mock_tracker_get.return_value = {"ok": True, "tasks": []}
        mock_post.return_value = {"ok": True, "workstreamId": "ws-x"}
        server.workstream_delete(workstream_id="ws-x", force=True)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload, {"force": True})

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_post")
    def test_delete_rejects_when_not_archived(
            self, mock_post, mock_tracker_get, mock_tracker_put):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": False,
            "error": "Workstream ws-live is not archived. Archive it first"
                     " (workstream_archive) or pass force=true to delete"
                     " a live workstream.",
        }
        result = server.workstream_delete(workstream_id="ws-live")
        self.assertFalse(result["ok"])
        self.assertIn("Archive it first", result["error"])
        # Tracker linkage is preserved when the controller refuses delete.
        mock_tracker_get.assert_not_called()
        mock_tracker_put.assert_not_called()

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_post")
    def test_delete_surfaces_warning_on_tracker_query_failure(
            self, mock_post, mock_tracker_get, mock_tracker_put):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-x"}
        mock_tracker_get.return_value = {"ok": False, "error": "tracker down"}
        result = server.workstream_delete(workstream_id="ws-x")
        self.assertTrue(result["ok"])
        self.assertIn("trackerCleanupWarning", result)
        self.assertIn("tracker query failed", result["trackerCleanupWarning"])

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_post")
    def test_delete_surfaces_warning_on_tracker_update_stall(
            self, mock_post, mock_tracker_get, mock_tracker_put):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-y"}
        mock_tracker_get.return_value = {"ok": True, "tasks": [{"id": "task-stuck"}]}
        mock_tracker_put.return_value = {"ok": False, "error": "read-only"}
        result = server.workstream_delete(workstream_id="ws-y")
        self.assertTrue(result["ok"])
        self.assertIn("trackerCleanupWarning", result)
        self.assertIn("stalled", result["trackerCleanupWarning"])

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_post")
    def test_delete_no_warning_on_clean_success(
            self, mock_post, mock_tracker_get, mock_tracker_put):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-clean"}
        mock_tracker_get.side_effect = [
            {"ok": True, "tasks": [{"id": "task-a"}, {"id": "task-b"}]},
            {"ok": True, "tasks": []},
        ]
        mock_tracker_put.return_value = {"ok": True}
        result = server.workstream_delete(workstream_id="ws-clean")
        self.assertTrue(result["ok"])
        self.assertEqual(result["deletedTrackerTasks"], 2)
        self.assertNotIn("trackerCleanupWarning", result)

    def test_delete_requires_write_scope(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server.workstream_delete(workstream_id="ws-x")


class TestWorkstreamGetStatus(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_returns_stats(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {"thisWeek": {"jobs": 5}, "lastWeek": {"jobs": 3}}
        result = server.workstream_get_status(workstream_id="ws-test")
        paths = [c.args[0] for c in mock_get.call_args_list]
        self.assertTrue(
            any("workstream=ws-test" in p and "period=weekly" in p for p in paths),
            f"Expected a stats call; got {paths}")
        self.assertEqual(result["workstream_id"], "ws-test")
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_get")
    def test_rejects_unsupported_period(self, mock_get):
        _grant_all_scopes()
        result = server.workstream_get_status(
            workstream_id="ws-test", period="daily")
        self.assertFalse(result.get("ok"))
        self.assertIn("weekly", result.get("error", ""))
        mock_get.assert_not_called()

    def test_rejects_long_workstream_id(self):
        _grant_all_scopes()
        result = server.workstream_get_status(workstream_id="x" * 1001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])


class TestWorkstreamSubmitTask(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_submit_basic(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": True, "jobId": "job-1", "workstreamId": "ws-test"}
        result = server.workstream_submit_task(
            prompt="Fix the bug", workstream_id="ws-test")
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["prompt"], "Fix the bug")
        self.assertEqual(payload["workstreamId"], "ws-test")
        self.assertTrue(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_submit_with_options(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-2"}
        server.workstream_submit_task(
            prompt="Task",
            target_branch="feature/x",
            description="test task",
            max_turns=10,
            max_budget_usd=5.0,
            protect_test_files=True,
            enforce_changes=True,
            started_after="1710000000000",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["targetBranch"], "feature/x")
        self.assertEqual(payload["description"], "test task")
        self.assertEqual(payload["maxTurns"], 10)
        self.assertEqual(payload["maxBudgetUsd"], 5.0)
        self.assertTrue(payload["protectTestFiles"])
        self.assertTrue(payload["enforceChanges"])
        self.assertEqual(payload["startedAfter"], "1710000000000")

    @patch.object(server, "_controller_post")
    def test_submit_omits_zero_defaults(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxTurns", payload)
        self.assertNotIn("maxBudgetUsd", payload)
        self.assertNotIn("protectTestFiles", payload)
        self.assertNotIn("enforceChanges", payload)
        self.assertNotIn("startedAfter", payload)

    @patch.object(server, "_controller_post")
    def test_submit_error(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": False, "error": "No agents"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    def test_rejects_oversized_prompt(self):
        _grant_all_scopes()
        result = server.workstream_submit_task(prompt="x" * 50_001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])

    @patch.object(server, "_controller_post")
    def test_submit_repo_url_forwarded(self, mock_post):
        # Two workstreams that share a default branch but live on
        # different repos must be disambiguated by repo_url.  Verify the
        # tool forwards repo_url as repoUrl so the controller-side
        # findByBranchAndRepo lookup can pick the right workstream.
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-r1"}
        server.workstream_submit_task(
            prompt="Task",
            target_branch="feature/audio-prototypes",
            repo_url="git@github.com:almostrealism/common.git",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["targetBranch"], "feature/audio-prototypes")
        self.assertEqual(payload["repoUrl"],
                         "git@github.com:almostrealism/common.git")

    @patch.object(server, "_controller_post")
    def test_submit_repo_url_omitted_when_blank(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-r2"}
        server.workstream_submit_task(prompt="Task", target_branch="feature/x")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("repoUrl", payload)

    @patch.object(server, "_controller_post")
    def test_submit_required_labels(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-3"}
        server.workstream_submit_task(
            prompt="Task", required_labels="platform:macos,gpu:true")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["requiredLabels"], {
            "platform": "macos", "gpu": "true"})

    @patch.object(server, "_controller_post")
    def test_submit_deduplication_mode_local(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-d1"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="local")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "local")

    @patch.object(server, "_controller_post")
    def test_submit_deduplication_mode_spawn(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-d2"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="spawn")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "spawn")

    @patch.object(server, "_controller_post")
    def test_submit_deduplication_mode_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-d3"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("deduplicationMode", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_deduplication_passes_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dp1"}
        server.workstream_submit_task(prompt="Task", max_deduplication_passes=5)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxDeduplicationPasses"], 5)

    @patch.object(server, "_controller_post")
    def test_submit_max_deduplication_passes_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dp2"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxDeduplicationPasses", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_deduplication_passes_one(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dp3"}
        server.workstream_submit_task(prompt="Task", max_deduplication_passes=1)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxDeduplicationPasses"], 1)

    @patch.object(server, "_controller_post")
    def test_submit_max_review_passes_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp1"}
        server.workstream_submit_task(prompt="Task", max_review_passes=3)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxReviewPasses"], 3)

    @patch.object(server, "_controller_post")
    def test_submit_max_review_passes_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp2"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxReviewPasses", payload)

    @patch.object(server, "_controller_post")
    def test_submit_review_enabled_omitted_when_true(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp3"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("reviewEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_review_enabled_false_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rp4"}
        server.workstream_submit_task(prompt="Task", review_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["reviewEnabled"], False)

    @patch.object(server, "_controller_post")
    def test_submit_retrospective_enabled_omitted_by_default(self, mock_post):
        """retrospective_enabled is opt-in (default false), so the wire payload
        must omit retrospectiveEnabled when the caller did not pass it."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rt1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("retrospectiveEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_retrospective_enabled_true_forwarded(self, mock_post):
        """retrospective_enabled=True must reach the controller as
        retrospectiveEnabled=True so the controller can opt the job into the
        retrospective phase."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rt2"}
        server.workstream_submit_task(prompt="Task", retrospective_enabled=True)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["retrospectiveEnabled"], True)

    @patch.object(server, "_controller_post")
    def test_submit_retrospective_enabled_false_omitted(self, mock_post):
        """retrospective_enabled=False is the default; the wire payload must
        omit the key rather than forward an explicit false (matches the
        organizational_placement_enabled behaviour)."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-rt3"}
        server.workstream_submit_task(prompt="Task", retrospective_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("retrospectiveEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_preserves_job_id_in_next_steps(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": True, "jobId": "job-42", "workstreamId": "ws-x"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-x")
        self.assertTrue(result["ok"])
        self.assertEqual(result["workstreamId"], "ws-x")
        # next_steps should mention the workstream
        self.assertTrue(any("ws-x" in s for s in result["next_steps"]))

    @patch.object(server, "_controller_post")
    def test_submit_controller_timeout(self, mock_post):
        """Simulate controller timeout — returns an error dict."""
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": False, "error": "Internal error contacting controller"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_submit_controller_returns_no_ok_field(self, mock_post):
        """Controller returns success-like response without explicit 'ok' key."""
        _grant_all_scopes()
        mock_post.return_value = {
            "jobId": "job-99", "workstreamId": "ws-test"}
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test")
        # Without "ok" key, result.get("ok") is None/falsy, so error next_steps added
        self.assertIn("next_steps", result)

    def test_requires_submit_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.workstream_submit_task(prompt="Task")

    def test_submit_rejects_legacy_model(self):
        """The dropped `model` param is rejected with a 400-style error
        pointing callers at default_phase_config / phase_configs."""
        _grant_all_scopes()
        result = server.workstream_submit_task(prompt="Task", model="opus")
        self.assertFalse(result["ok"])
        self.assertIn("model", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["model"])

    def test_submit_rejects_legacy_effort(self):
        """The dropped `effort` param is rejected with a 400-style error."""
        _grant_all_scopes()
        result = server.workstream_submit_task(prompt="Task", effort="high")
        self.assertFalse(result["ok"])
        self.assertIn("effort", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["effort"])

    @patch.object(server, "_controller_post")
    def test_submit_omits_model_and_effort_when_unset(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-me-2"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("model", payload)
        self.assertNotIn("effort", payload)

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_command_included_in_payload(self, mock_post):
        """post_completion_command is forwarded to the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pcc"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="mvn -pl flowtree/runtime test -Dtest=FooTest",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(
            payload["postCompletionCommand"],
            "mvn -pl flowtree/runtime test -Dtest=FooTest",
        )

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_command_omitted_by_default(self, mock_post):
        """post_completion_command must not appear in the payload when not set."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pcc-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("postCompletionCommand", payload)
        self.assertNotIn("postCompletionTimeoutSeconds", payload)

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_timeout_included_when_set(self, mock_post):
        """A non-zero post_completion_timeout_seconds is forwarded."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pct"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            post_completion_timeout_seconds=600,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["postCompletionTimeoutSeconds"], 600)

    @patch.object(server, "_controller_post")
    def test_submit_post_completion_timeout_omitted_when_zero(self, mock_post):
        """Timeout=0 (the default) must not appear in the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pct-zero"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            post_completion_timeout_seconds=0,
        )
        payload = mock_post.call_args[0][1]
        self.assertNotIn("postCompletionTimeoutSeconds", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_post_completion_passes_forwarded(self, mock_post):
        """max_post_completion_passes is forwarded to the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-mpcp"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            max_post_completion_passes=5,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxPostCompletionPasses"], 5)

    @patch.object(server, "_controller_post")
    def test_submit_max_post_completion_passes_omitted_by_default(self, mock_post):
        """max_post_completion_passes must not appear in the payload when not set."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-mpcp-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxPostCompletionPasses", payload)

    @patch.object(server, "_controller_post")
    def test_submit_max_post_completion_passes_one(self, mock_post):
        """max_post_completion_passes=1 is forwarded correctly."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-mpcp-one"}
        server.workstream_submit_task(
            prompt="Task",
            post_completion_command="make test",
            max_post_completion_passes=1,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxPostCompletionPasses"], 1)

    @patch.object(server, "_controller_post")
    def test_submit_delay_seconds_forwarded(self, mock_post):
        """delay_seconds is forwarded to the controller payload as delaySeconds."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-delay"}
        server.workstream_submit_task(prompt="Task", delay_seconds=30)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["delaySeconds"], 30)

    @patch.object(server, "_controller_post")
    def test_submit_delay_seconds_omitted_by_default(self, mock_post):
        """delay_seconds must not appear in the payload when not specified."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-nodelay"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("delaySeconds", payload)

    @patch.object(server, "_controller_post")
    def test_submit_delay_seconds_zero_omitted(self, mock_post):
        """delay_seconds=0 must not appear in the payload (immediate dispatch)."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-nodelay-zero"}
        server.workstream_submit_task(prompt="Task", delay_seconds=0)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("delaySeconds", payload)

    def test_submit_rejects_legacy_runners(self):
        """The dropped `runners` map is rejected with a 400-style error
        pointing callers at phase_configs / default_phase_config."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            runners='{"primary":"claude","deduplication":"opencode"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["runners"])

    def test_submit_rejects_legacy_default_runner(self):
        """The dropped `default_runner` shortcut is rejected."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task", default_runner="opencode")
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["default_runner"])

    @patch.object(server, "_controller_post")
    def test_submit_runners_omitted_by_default(self, mock_post):
        """No runners argument means no runners key in the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-norunners"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("runners", payload)

    @patch.object(server, "_controller_post")
    def test_submit_default_phase_config_forwarded(self, mock_post):
        """default_phase_config JSON is parsed and forwarded as a nested object."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-default-pc"}
        server.workstream_submit_task(
            prompt="Task",
            default_phase_config='{"runner":"claude","model":"opus","effort":"high"}',
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["defaultPhaseConfig"],
                         {"runner": "claude", "model": "opus", "effort": "high"})

    @patch.object(server, "_controller_post")
    def test_submit_phase_configs_forwarded(self, mock_post):
        """phase_configs JSON is parsed and forwarded as a nested object map."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pc"}
        server.workstream_submit_task(
            prompt="Task",
            phase_configs='{"review":{"model":"opus","effort":"high"},'
                          '"commit-message":{"runner":"opencode"}}',
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["phaseConfigs"], {
            "review": {"model": "opus", "effort": "high"},
            "commit-message": {"runner": "opencode"},
        })

    def test_submit_phase_configs_rejects_unknown_phase(self):
        """Unknown phase names in phase_configs are rejected client-side."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            phase_configs='{"future-phase":{"model":"opus"}}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("future-phase", result["error"])

    def test_submit_default_phase_config_rejects_unknown_key(self):
        """Unknown keys in default_phase_config are rejected client-side."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            default_phase_config='{"bogus":"value"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("bogus", result["error"])

    def test_submit_rejects_legacy_runners_even_with_phase_configs(self):
        """A legacy `runners` map is rejected up front even when the new
        phase_configs form is also supplied — no silent translation."""
        _grant_all_scopes()
        result = server.workstream_submit_task(
            prompt="Task",
            runners='{"primary":"opencode"}',
            phase_configs='{"review":{"effort":"high"}}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])

    @patch.object(server, "_controller_post")
    def test_submit_phase_configs_omitted_by_default(self, mock_post):
        """No phase_configs argument means no defaultPhaseConfig/phaseConfigs keys."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-noPC"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("defaultPhaseConfig", payload)
        self.assertNotIn("phaseConfigs", payload)


class TestSubmitCommitLanguageLinter(unittest.TestCase):
    """Server-side linter rejects prompts that imply the agent controls
    git commits.  allow_commit_language=True bypasses the linter.
    """

    def setUp(self):
        _grant_all_scopes()

    def tearDown(self):
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes

    def _bad_prompt(self, text):
        """Submit a prompt that should be rejected; return the result."""
        return server.workstream_submit_task(prompt=text)

    def _good_prompt(self, text):
        """Submit a prompt with allow_commit_language=True and assert the
        linter did not fire. The controller HTTP call is mocked here so the
        test does not depend on CONTROLLER_URL reachability — without the
        mock urlopen would attempt a real network round-trip and pollute the
        observable error with a transport-level failure, making the linter
        assertion racy/slow on offline runners.
        """
        with mock.patch.object(server, "_controller_post",
                               return_value={"ok": True, "taskId": "test"}):
            result = server.workstream_submit_task(
                prompt=text, allow_commit_language=True)
        # Linter must not have fired — the mocked controller call is the
        # only thing that should have responded.
        error = result.get("error", "")
        self.assertNotIn("sequence of commits", error,
                         "allow_commit_language=True should bypass the linter")
        self.assertNotIn("commit-language", error,
                         "allow_commit_language=True should bypass the linter")

    # -- Forbidden patterns ---------------------------------------------------

    def test_rejects_commit_number_phrase(self):
        result = self._bad_prompt(
            "Do the following: Commit 1: set up the schema. "
            "Commit 2: add the service layer.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("commit-number phrase", result["error"])

    def test_rejects_first_commit_phrase(self):
        result = self._bad_prompt(
            "In the first commit add the migration file, "
            "then write the test.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("first commit", result["error"])

    def test_rejects_next_commit_phrase(self):
        result = self._bad_prompt(
            "After the setup is done, in the next commit "
            "wire up the controller.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("next commit", result["error"])

    def test_rejects_final_commit_phrase(self):
        result = self._bad_prompt(
            "The final commit should include the documentation update.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("final commit", result["error"])

    def test_rejects_as_separate_commits_phrase(self):
        result = self._bad_prompt(
            "Please land each layer as separate commits so reviewers "
            "can examine them independently.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])
        self.assertIn("separate", result["error"])

    def test_rejects_in_n_commits_phrase(self):
        result = self._bad_prompt(
            "Please land the whole feature in 3 commits with clear boundaries.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    def test_rejects_commit_message_should_phrase(self):
        result = self._bad_prompt(
            "When you are done, your commit message should summarize "
            "the entire changeset.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    def test_rejects_commit_this_before_phrase(self):
        result = self._bad_prompt(
            "Commit this before starting on the second part of the task.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    def test_rejects_commit_between_each_phrase(self):
        result = self._bad_prompt(
            "Please commit between each major change so the diff is small.")
        self.assertFalse(result["ok"])
        self.assertIn("sequence of commits", result["error"])

    # -- Rejection message quality --------------------------------------------

    def test_rejection_message_names_the_phrase_and_line(self):
        result = self._bad_prompt(
            "Here is the plan:\n"
            "Commit 1: implement the parser.\n"
            "Commit 2: add tests.\n")
        self.assertFalse(result["ok"])
        error = result["error"]
        self.assertIn("Line 2", error)
        self.assertIn("commit-number phrase", error)

    # -- Escape hatch ---------------------------------------------------------

    @patch.object(server, "_controller_post")
    def test_allow_commit_language_bypasses_linter(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j1"}
        result = server.workstream_submit_task(
            prompt="Commit 1: do X. Commit 2: do Y.",
            allow_commit_language=True,
        )
        self.assertTrue(result["ok"],
                        "allow_commit_language=True must bypass the linter")
        mock_post.assert_called_once()

    # -- Short-prompt exemption -----------------------------------------------

    @patch.object(server, "_controller_post")
    def test_short_prompt_skips_linter(self, mock_post):
        """Prompts shorter than 50 chars are too brief for the linter to run."""
        mock_post.return_value = {"ok": True, "jobId": "j2"}
        # "Commit 1: fix" is only 14 chars — well under the threshold.
        result = server.workstream_submit_task(prompt="Commit 1: fix")
        # The linter must not fire; the call goes to the controller.
        mock_post.assert_called_once()
        self.assertTrue(result["ok"])

    # -- False-positive guard -------------------------------------------------

    @patch.object(server, "_controller_post")
    def test_does_not_reject_commit_message_convention_reference(self, mock_post):
        """A prompt that references the existing commit message convention
        (e.g. for documentation purposes) should not be flagged.
        """
        mock_post.return_value = {"ok": True, "jobId": "j3"}
        prompt = (
            "Update the CONTRIBUTING guide to explain that the existing "
            "commit message convention follows the Angular format: "
            "<type>(<scope>): <subject>.  Add examples of good and bad "
            "commit subject lines so contributors know what to write."
        )
        result = server.workstream_submit_task(prompt=prompt)
        self.assertTrue(result["ok"],
                        "Normal English references to commit conventions "
                        "should not be rejected by the linter")


class TestWorkstreamSubmitSelfCollision(unittest.TestCase):
    """workstream_submit_task must not let an agent submit work to its
    own workstream — concurrent commits on the same branch break the
    git lifecycle the controller relies on. The token-bound caller
    workstream comes from the temp token's payload (set during auth).
    """

    def setUp(self):
        _grant_all_scopes()
        server._set_workspace_scopes(["TAAA"])
        server._set_token_context(workstream_id="ws-self", job_id="job-self")
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_rejects_submission_to_calling_workstream(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Do something", workstream_id="ws-self")
        self.assertFalse(result["ok"])
        self.assertIn("calling workstream itself", result["error"])
        self.assertIn("ws-self", result["error"])
        self.assertIn("git collisions", result["error"])
        # Error should explain the user-confusion case clearly.
        self.assertIn("misunderstanding", result["error"])
        self.assertIn("directly", result["error"])
        # Must point at workstream_list as the discovery path.
        self.assertIn("workstream_list", result["error"])
        self.assertIn("next_steps", result)
        # Controller must NOT have been called.
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_rejects_missing_workstream_id_for_agent(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Task", target_branch="feature/somewhere")
        self.assertFalse(result["ok"])
        self.assertIn("workstream_id is required", result["error"])
        self.assertIn("workstream_list", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_allows_submission_to_other_workstream_in_workspace(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        mock_post.return_value = {
            "ok": True, "jobId": "job-1", "workstreamId": "ws-other"}
        result = server.workstream_submit_task(
            prompt="Delegated task", workstream_id="ws-other")
        self.assertTrue(result["ok"])
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["workstreamId"], "ws-other")

    @patch.object(server, "_controller_get")
    def test_rejects_submission_to_workstream_in_other_workspace(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-foreign", "slackWorkspaceId": "TBBB"},
        ]
        with self.assertRaises(PermissionError):
            server.workstream_submit_task(
                prompt="Task", workstream_id="ws-foreign")


class TestWorkstreamSubmitUnscopedCallerUnaffected(unittest.TestCase):
    """Unscoped operator callers (no workstream-bound token) must retain
    the prior behaviour: no self-collision check, target_branch alone is
    accepted, the controller resolves the workstream.
    """

    def setUp(self):
        _grant_all_scopes()  # no workspace scopes, no token context

    @patch.object(server, "_controller_post")
    def test_unscoped_target_branch_only(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "job-x"}
        result = server.workstream_submit_task(
            prompt="Task", target_branch="feature/x")
        self.assertTrue(result["ok"])
        mock_post.assert_called_once()


class TestWorkstreamSubmitStaticTokenIsolation(unittest.TestCase):
    """Regression: a static-token (Claude.ai web chat / third-party API)
    request that lands on a thread previously used to handle an in-cluster
    HMAC-temp-token request must not inherit that prior request's
    workstream binding via the thread-local fallback in
    ``_get_token_workstream_id``.

    Before the fix, the auth middleware's static-token path set request
    scopes but never reset the thread-local workstream_id. A subsequent
    static-token request handled on the same worker thread would find
    the stale value via ``_thread_local.workstream_id`` (since the
    contextvar was unset for the new request) and the self-collision
    check would refuse a perfectly legitimate cross-workstream
    submission.
    """

    def setUp(self):
        _grant_all_scopes()
        # Simulate a previous HMAC-temp-token request (an in-cluster
        # Claude Code agent running on workstream "ws-prior") having
        # left thread-local state behind on this worker thread.
        server._thread_local.workstream_id = "ws-prior"
        server._thread_local.job_id = "job-prior"
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def tearDown(self):
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["fetched"] = 0.0

    def test_static_token_path_clears_stale_thread_local(self):
        # Mirror what AuthMiddleware does for a matched static token.
        server._set_scopes(["read", "write", "submit"], label="static")
        server._set_workspace_scopes(None)
        server._set_token_context("", "")
        # The self-collision check uses _get_token_workstream_id();
        # after the middleware fix, it must not see the leaked binding.
        self.assertFalse(bool(server._get_token_workstream_id()))

    @patch.object(server, "_controller_post")
    def test_static_token_can_submit_to_previously_bound_workstream(
            self, mock_post):
        # Mirror what AuthMiddleware does for a matched static token.
        server._set_scopes(["read", "write", "submit"], label="static")
        server._set_workspace_scopes(None)
        server._set_token_context("", "")
        mock_post.return_value = {"ok": True, "jobId": "job-1"}
        # A static-token caller submitting to the workstream that was
        # bound to a previous temp-token request on this thread must
        # succeed — the caller has no checkout and cannot collide.
        result = server.workstream_submit_task(
            prompt="Delegated task", workstream_id="ws-prior")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()


class TestAuthMiddlewareTokenContextLifecycle(unittest.TestCase):
    """The auth middleware must bind a fresh token context on every
    authenticated request — including static-token requests, which have
    no workstream binding of their own — so that thread-local state from
    a prior HMAC-temp-token request cannot leak into the new request's
    self-collision check.
    """

    def setUp(self):
        # Simulate a previous temp-token request on this thread.
        server._thread_local.workstream_id = "ws-cluster-prior"
        server._thread_local.job_id = "job-cluster-prior"

    def tearDown(self):
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._request_scopes.set(None)
        server._request_token_label.set(None)
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "scopes"):
            del server._thread_local.scopes
        if hasattr(server._thread_local, "token_label"):
            del server._thread_local.token_label
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes

    def test_static_token_match_clears_token_context(self):
        import asyncio

        captured = {}

        async def downstream(scope, receive, send):
            captured["workstream_id"] = server._get_token_workstream_id()
            captured["job_id"] = server._get_token_job_id()
            await send({"type": "http.response.start", "status": 200,
                        "headers": []})
            await send({"type": "http.response.body", "body": b""})

        middleware = server.BearerAuthMiddleware(
            downstream,
            tokens=[{"value": "static-token-xyz", "scopes": ["submit"],
                     "label": "static-test"}],
        )
        scope = {
            "type": "http",
            "path": "/mcp",
            "headers": [(b"authorization", b"Bearer static-token-xyz")],
        }

        async def receive():
            return {"type": "http.request"}

        async def send(_msg):
            return None

        asyncio.run(middleware(scope, receive, send))
        # Static tokens carry no workstream binding; the middleware must
        # have cleared the leaked thread-local values.
        self.assertFalse(bool(captured.get("workstream_id")))
        self.assertFalse(bool(captured.get("job_id")))


class TestWorkstreamRegister(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_register_basic(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-new"}
        result = server.workstream_register(default_branch="feature/new")
        mock_post.assert_called_once()
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["defaultBranch"], "feature/new")
        self.assertTrue(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_register_with_all_fields(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-full"}
        server.workstream_register(
            default_branch="feature/full",
            base_branch="develop",
            repo_url="https://github.com/org/repo",
            planning_document="docs/plans/PLAN.md",
            channel_name="#w-full",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["baseBranch"], "develop")
        self.assertEqual(payload["repoUrl"], "https://github.com/org/repo")
        self.assertEqual(payload["planningDocument"], "docs/plans/PLAN.md")
        self.assertEqual(payload["channelName"], "#w-full")

    @patch.object(server, "_controller_post")
    def test_register_suggests_repo_url(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-no-repo"}
        result = server.workstream_register(default_branch="feature/x")
        steps_text = " ".join(result["next_steps"])
        self.assertIn("repo_url", steps_text)

    def test_register_rejects_legacy_model(self):
        """The dropped `model` param is rejected with a 400-style error."""
        _grant_all_scopes()
        result = server.workstream_register(
            default_branch="feature/me", model="sonnet")
        self.assertFalse(result["ok"])
        self.assertIn("model", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["model"])

    def test_register_rejects_legacy_effort(self):
        """The dropped `effort` param is rejected."""
        _grant_all_scopes()
        result = server.workstream_register(
            default_branch="feature/me", effort="medium")
        self.assertFalse(result["ok"])
        self.assertIn("effort", result["error"])
        self.assertIn("no longer supported", result["error"])

    def test_register_rejects_legacy_runners(self):
        """The dropped `runners` map is rejected."""
        _grant_all_scopes()
        result = server.workstream_register(
            default_branch="feature/x",
            runners='{"primary":"opencode","deduplication":"opencode"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])

    def test_register_rejects_legacy_default_runner(self):
        """The dropped `default_runner` shortcut is rejected."""
        _grant_all_scopes()
        result = server.workstream_register(
            default_branch="feature/x",
            default_runner="opencode",
        )
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])

    @patch.object(server, "_controller_post")
    def test_register_runners_omitted_by_default(self, mock_post):
        """No runners argument means no runners key in the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-no-runners"}
        server.workstream_register(default_branch="feature/x")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("runners", payload)


class TestWorkstreamUpdateConfig(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_update_fields(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        result = server.workstream_update_config(
            workstream_id="ws-test",
            default_branch="feature/updated",
            repo_url="https://github.com/org/repo",
        )
        call_path = mock_post.call_args[0][0]
        self.assertIn("ws-test", call_path)
        self.assertIn("/update", call_path)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["defaultBranch"], "feature/updated")
        self.assertEqual(payload["repoUrl"], "https://github.com/org/repo")
        self.assertTrue(result["ok"])

    def test_no_fields_returns_error(self):
        _grant_all_scopes()
        result = server.workstream_update_config(workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("No fields to update", result["error"])

    @patch.object(server, "_controller_post")
    def test_update_with_repo_includes_pipeline_hint(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        result = server.workstream_update_config(
            workstream_id="ws-test",
            repo_url="https://github.com/org/repo",
        )
        steps_text = " ".join(result["next_steps"])
        self.assertIn("pipeline", steps_text)

    def test_update_rejects_legacy_model(self):
        """The dropped `model` param is rejected with a 400-style error."""
        _grant_all_scopes()
        result = server.workstream_update_config(
            workstream_id="ws-test", model="haiku")
        self.assertFalse(result["ok"])
        self.assertIn("model", result["error"])
        self.assertIn("no longer supported", result["error"])

    def test_update_rejects_legacy_effort(self):
        """The dropped `effort` param is rejected."""
        _grant_all_scopes()
        result = server.workstream_update_config(
            workstream_id="ws-test", effort="low")
        self.assertFalse(result["ok"])
        self.assertIn("effort", result["error"])
        self.assertIn("no longer supported", result["error"])

    def test_update_rejects_legacy_runners(self):
        """The dropped `runners` map is rejected."""
        _grant_all_scopes()
        result = server.workstream_update_config(
            workstream_id="ws-test",
            runners='{"primary":"opencode"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])

    def test_update_rejects_legacy_default_runner(self):
        """The dropped `default_runner` shortcut is rejected."""
        _grant_all_scopes()
        result = server.workstream_update_config(
            workstream_id="ws-test",
            default_runner="opencode",
        )
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])


class TestWorkspaceUpdateConfig(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_update_name_and_default_channel(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        result = server.workspace_update_config(
            slack_workspace_id="T0123456789",
            name="Acme Inc",
            default_channel="C9999",
        )
        call_path = mock_post.call_args[0][0]
        self.assertIn("T0123456789", call_path)
        self.assertIn("/config", call_path)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["name"], "Acme Inc")
        self.assertEqual(payload["defaultChannel"], "C9999")
        self.assertTrue(result["ok"])

    def test_update_rejects_legacy_runners(self):
        """The dropped `runners` map is rejected with a 400-style error."""
        _grant_all_scopes()
        result = server.workspace_update_config(
            slack_workspace_id="T0123456789",
            runners='{"primary":"opencode"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["runners"])

    def test_update_rejects_legacy_default_runner(self):
        """The dropped `default_runner` shortcut is rejected."""
        _grant_all_scopes()
        result = server.workspace_update_config(
            slack_workspace_id="T0123456789",
            default_runner="opencode",
        )
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["default_runner"])

    def test_no_fields_returns_error(self):
        _grant_all_scopes()
        result = server.workspace_update_config(slack_workspace_id="T0123456789")
        self.assertFalse(result["ok"])
        self.assertIn("No fields to update", result["error"])

    @patch.object(server, "_controller_post")
    def test_empty_string_fields_do_not_change_payload(self, mock_post):
        """Empty-string optional fields are omitted from the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            slack_workspace_id="T0123456789",
            name="Acme",
            default_channel="",
            default_runner="",
            runners="",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload, {"name": "Acme"})

    @patch.object(server, "_controller_post")
    def test_accepts_new_workspace_id_param(self, mock_post):
        """The canonical workspace_id parameter routes the same as the legacy alias."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", name="Acme")
        call_path = mock_post.call_args[0][0]
        self.assertIn("almostrealism", call_path)

    @patch.object(server, "_controller_post")
    def test_new_id_forwarded_as_newId_payload(self, mock_post):
        """Supplying new_id forwards a newId field to the controller."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="T0123456789", new_id="almostrealism")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["newId"], "almostrealism")

    @patch.object(server, "_controller_post")
    def test_new_id_equal_to_workspace_id_is_omitted(self, mock_post):
        """new_id matching the existing id is a no-op and is omitted from the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism",
            new_id="almostrealism",
            name="Acme")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("newId", payload)
        self.assertEqual(payload["name"], "Acme")

    @patch.object(server, "_controller_post")
    def test_slack_team_id_set_to_nonempty_forwards(self, mock_post):
        """A non-empty slack_team_id binds the workspace to that Slack team."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", slack_team_id="T0123456789")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["slackTeamId"], "T0123456789")

    @patch.object(server, "_controller_post")
    def test_slack_team_id_explicit_empty_clears(self, mock_post):
        """An explicit empty string for slack_team_id clears the Slack binding."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", slack_team_id="")
        payload = mock_post.call_args[0][1]
        # slackTeamId is present (so the controller knows to clear it),
        # but its value is the empty string.
        self.assertIn("slackTeamId", payload)
        self.assertEqual(payload["slackTeamId"], "")

    @patch.object(server, "_controller_post")
    def test_slack_team_id_omitted_leaves_payload_unset(self, mock_post):
        """Omitting slack_team_id leaves the field absent from the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", name="Acme")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("slackTeamId", payload)

    def test_missing_workspace_id_returns_error(self):
        """Calling with neither workspace_id nor slack_workspace_id returns an error."""
        _grant_all_scopes()
        result = server.workspace_update_config(name="Acme")
        self.assertFalse(result["ok"])
        self.assertIn("workspace_id", result["error"])


class TestPhaseConfigParameters(unittest.TestCase):
    """End-to-end coverage of ``default_phase_config`` and ``phase_configs``
    across all four MCP entry points (``workstream_submit_task``,
    ``workstream_register``, ``workstream_update_config``,
    ``workspace_update_config``).

    The four tools share the same parser pair
    (``_parse_default_phase_config_json`` / ``_parse_phase_configs_json``)
    so the validation behaviour is uniform. These tests pin that
    behaviour at the entry-point level so a future refactor that splits
    the parsers per-tool cannot silently regress one of them.
    """

    def setUp(self):
        _grant_all_scopes()

    def _invoke(self, tool, **kwargs):
        """Dispatch ``tool`` (one of the four entry point names) with the
        smallest required positional args plus ``kwargs``. Hides the
        per-tool boilerplate so the test bodies focus on the new
        parameters."""
        if tool == "submit":
            return server.workstream_submit_task(prompt="Task", **kwargs)
        if tool == "register":
            return server.workstream_register(default_branch="feature/x", **kwargs)
        if tool == "update":
            return server.workstream_update_config(workstream_id="ws-test", **kwargs)
        if tool == "workspace":
            return server.workspace_update_config(
                workspace_id="almostrealism", **kwargs)
        raise AssertionError("unknown tool: " + tool)

    def _payload(self, mock_post):
        return mock_post.call_args[0][1]

    # ---- default_phase_config only ----------------------------------------

    @patch.object(server, "_controller_post")
    def test_default_phase_config_forwarded_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            self._invoke(
                tool,
                default_phase_config='{"runner":"claude","model":"opus","effort":"high"}')
            payload = self._payload(mock_post)
            self.assertEqual(
                payload["defaultPhaseConfig"],
                {"runner": "claude", "model": "opus", "effort": "high"},
                "tool=" + tool)

    # ---- phase_configs only -----------------------------------------------

    @patch.object(server, "_controller_post")
    def test_phase_configs_forwarded_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            self._invoke(
                tool,
                phase_configs='{"review":{"model":"opus","effort":"high"},'
                              '"commit-message":{"runner":"opencode"}}')
            payload = self._payload(mock_post)
            self.assertEqual(payload["phaseConfigs"], {
                "review": {"model": "opus", "effort": "high"},
                "commit-message": {"runner": "opencode"},
            }, "tool=" + tool)

    # ---- both supplied ----------------------------------------------------

    @patch.object(server, "_controller_post")
    def test_both_parameters_forwarded_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            self._invoke(
                tool,
                default_phase_config='{"runner":"claude","model":"opus"}',
                phase_configs='{"review":{"effort":"high"}}')
            payload = self._payload(mock_post)
            self.assertEqual(payload["defaultPhaseConfig"],
                             {"runner": "claude", "model": "opus"},
                             "tool=" + tool)
            self.assertEqual(payload["phaseConfigs"],
                             {"review": {"effort": "high"}}, "tool=" + tool)

    # ---- empty strings leave fields unchanged -----------------------------

    @patch.object(server, "_controller_post")
    def test_empty_strings_omit_fields_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            # workspace_update_config requires at least one writable field
            # before it forwards a payload, so we pair the empty
            # phase-config args with ``name`` to keep the call valid.
            extra = {"name": "Acme"} if tool == "workspace" else {}
            if tool == "update":
                extra = {"default_branch": "feature/x"}
            self._invoke(
                tool,
                default_phase_config="",
                phase_configs="",
                **extra)
            payload = self._payload(mock_post)
            self.assertNotIn("defaultPhaseConfig", payload, "tool=" + tool)
            self.assertNotIn("phaseConfigs", payload, "tool=" + tool)

    # ---- malformed JSON ---------------------------------------------------

    def test_default_phase_config_rejects_malformed_json_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(tool, default_phase_config="not-valid-json")
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("JSON object", result["error"], "tool=" + tool)

    def test_phase_configs_rejects_malformed_json_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(tool, phase_configs="not-valid-json")
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("JSON object", result["error"], "tool=" + tool)

    def test_default_phase_config_rejects_non_object_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(tool, default_phase_config='["a","b"]')
            self.assertFalse(result["ok"], "tool=" + tool)

    # ---- unknown phase wire name ------------------------------------------

    def test_phase_configs_rejects_unknown_phase_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, phase_configs='{"future-phase":{"runner":"claude"}}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("future-phase", result["error"], "tool=" + tool)

    # ---- unknown runner name ----------------------------------------------

    def test_default_phase_config_rejects_unknown_runner_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, default_phase_config='{"runner":"not-a-runner"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("not-a-runner", result["error"], "tool=" + tool)
            self.assertIn("agent_options", result["error"], "tool=" + tool)

    def test_phase_configs_rejects_unknown_runner_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool,
                phase_configs='{"review":{"runner":"not-a-runner"}}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("not-a-runner", result["error"], "tool=" + tool)

    # ---- unknown effort value ---------------------------------------------

    def test_default_phase_config_rejects_unknown_effort_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, default_phase_config='{"effort":"extreme"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("extreme", result["error"], "tool=" + tool)

    def test_phase_configs_rejects_unknown_effort_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, phase_configs='{"review":{"effort":"extreme"}}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("extreme", result["error"], "tool=" + tool)

    # ---- unknown inner key ------------------------------------------------

    def test_default_phase_config_rejects_unknown_inner_key_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, default_phase_config='{"bogus":"value"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("bogus", result["error"], "tool=" + tool)

    # ---- dropped legacy params are rejected, never translated -------------

    @patch.object(server, "_controller_post")
    def test_dropped_legacy_params_rejected_for_all_tools(self, mock_post):
        """The legacy config params are no longer accepted on any entry point.
        Each of ``model`` / ``effort`` / ``default_runner`` / ``runners`` is
        rejected with a 400-style error that names the parameter and points at
        the per-phase replacement; the controller is never called and no
        silent translation occurs. ``model`` / ``effort`` were never workspace
        fields, so only ``default_runner`` / ``runners`` apply to that tool."""
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        legacy = {
            "model": "opus",
            "effort": "high",
            "default_runner": "claude",
            "runners": '{"primary":"opencode"}',
        }
        for tool in ("submit", "register", "update", "workspace"):
            params = (("default_runner", "runners") if tool == "workspace"
                      else tuple(legacy))
            for param in params:
                mock_post.reset_mock()
                label = "tool=" + tool + " param=" + param
                result = self._invoke(tool, **{param: legacy[param]})
                self.assertFalse(result["ok"], label)
                self.assertIn(param, result["error"], label)
                self.assertIn("no longer supported", result["error"], label)
                self.assertEqual(result["removed_parameters"], [param], label)
                mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_dropped_legacy_param_rejected_even_with_new_shape(self, mock_post):
        """A legacy param is rejected even when a valid new-shape param is
        also supplied — the clean break takes priority over translation."""
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            result = self._invoke(
                tool,
                default_phase_config='{"runner":"claude"}',
                runners='{"review":"opencode"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("runners", result["error"], "tool=" + tool)
            mock_post.assert_not_called()


# -----------------------------------------------------------------------
# Tier 2: Pipeline tools
# -----------------------------------------------------------------------


class TestProjectCreateBranch(unittest.TestCase):

    @patch.object(server, "_github_request")
    def test_dispatch_default_repo(self, mock_gh):
        _grant_all_scopes()
        mock_gh.return_value = {"ok": True, "status": 204}
        result = server.project_create_branch(plan_title="my-feature")
        mock_gh.assert_called_once()
        call_path = mock_gh.call_args[0][1]
        self.assertIn("almostrealism/common", call_path)
        self.assertIn("project-manager.yaml", call_path)
        self.assertTrue(result["ok"])
        self.assertTrue(result["triggered"])

    @patch.object(server, "_github_request")
    def test_dispatch_explicit_repo(self, mock_gh):
        _grant_all_scopes()
        mock_gh.return_value = {"status": 204}
        result = server.project_create_branch(
            repo_url="https://github.com/myorg/myrepo")
        call_path = mock_gh.call_args[0][1]
        self.assertIn("myorg/myrepo", call_path)
        self.assertTrue(result["ok"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_dispatch_from_workstream(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "baseBranch": "main",
        }
        mock_gh.return_value = {"status": 204}
        result = server.project_create_branch(workstream_id="ws-test")
        payload = mock_gh.call_args[0][2]
        self.assertEqual(payload["ref"], "main")
        self.assertTrue(result["ok"])

    @patch.object(server, "_find_workstream")
    def test_workstream_not_found(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = None
        result = server.project_create_branch(workstream_id="ws-bad")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

    @patch.object(server, "_github_request")
    def test_workflow_failure(self, mock_gh):
        _grant_all_scopes()
        mock_gh.return_value = {"ok": False, "error": "Not Found"}
        result = server.project_create_branch()
        self.assertIn("next_steps", result)

    def test_requires_pipeline_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.project_create_branch()


class TestProjectVerifyBranch(unittest.TestCase):

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_dispatch_verify(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
            "baseBranch": "master",
        }
        mock_gh.return_value = {"status": 204}
        result = server.project_verify_branch(workstream_id="ws-test")
        call_path = mock_gh.call_args[0][1]
        self.assertIn("verify-completion.yaml", call_path)
        payload = mock_gh.call_args[0][2]
        self.assertEqual(payload["ref"], "feature/x")
        self.assertTrue(result["ok"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_custom_branch(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        mock_gh.return_value = {"status": 204}
        server.project_verify_branch(
            workstream_id="ws-test", branch="feature/custom")
        payload = mock_gh.call_args[0][2]
        self.assertEqual(payload["ref"], "feature/custom")

    @patch.object(server, "_find_workstream")
    def test_missing_repo_url(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {"defaultBranch": "feature/x"}
        result = server.project_verify_branch(workstream_id="ws-test")
        self.assertFalse(result["ok"])

    @patch.object(server, "_find_workstream")
    def test_workstream_not_found(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = None
        result = server.project_verify_branch(workstream_id="ws-bad")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])


class TestProjectCommitPlan(unittest.TestCase):

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_commit_plan(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        mock_gh.side_effect = [
            {"sha": "abc123"},  # GET existing file
            {"content": {"sha": "new"}, "commit": {"sha": "def456"}},  # PUT
        ]
        result = server.project_commit_plan(
            workstream_id="ws-test",
            content="# Plan\nDo stuff",
            path="docs/plans/PLAN.md",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["path"], "docs/plans/PLAN.md")
        self.assertEqual(result["commit_sha"], "def456")

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_github_request")
    def test_auto_generates_path(self, mock_gh, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/my-plan",
        }
        mock_gh.side_effect = [
            {},  # GET existing (not found)
            {"content": {"sha": "new"}, "commit": {"sha": "abc"}},
        ]
        result = server.project_commit_plan(
            workstream_id="ws-test", content="# Plan")
        self.assertTrue(result["ok"])
        self.assertIn("PLAN-", result["path"])
        self.assertIn("feature-my-plan", result["path"])

    @patch.object(server, "_find_workstream")
    def test_path_traversal_blocked(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        result = server.project_commit_plan(
            workstream_id="ws-test",
            content="# Plan",
            path="../../../etc/passwd",
        )
        self.assertFalse(result["ok"])

    @patch.object(server, "_find_workstream")
    def test_sensitive_path_blocked(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        result = server.project_commit_plan(
            workstream_id="ws-test",
            content="# Plan",
            path=".github/workflows/evil.yaml",
        )
        self.assertFalse(result["ok"])

    def test_rejects_oversized_content(self):
        _grant_all_scopes()
        result = server.project_commit_plan(
            workstream_id="ws-test", content="x" * 100_001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])


class TestProjectReadPlan(unittest.TestCase):

    @patch.object(server, "github_read_file")
    @patch.object(server, "_find_workstream")
    def test_read_plan(self, mock_find, mock_read_file):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
            "planningDocument": "docs/plans/PLAN.md",
        }
        mock_read_file.return_value = {
            "ok": True,
            "path": "docs/plans/PLAN.md",
            "content": "# My Plan",
            "sha": "abc123",
            "ref": "feature/x",
            "repo": "org/repo",
        }
        result = server.project_read_plan(workstream_id="ws-test")
        self.assertTrue(result["ok"])
        self.assertEqual(result["content"], "# My Plan")
        self.assertEqual(result["path"], "docs/plans/PLAN.md")

    @patch.object(server, "github_read_file")
    @patch.object(server, "_find_workstream")
    def test_delegates_to_github_read_file(self, mock_find, mock_read_file):
        """project_read_plan must resolve the planningDocument and delegate to github_read_file."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
            "planningDocument": "docs/plans/PLAN.md",
        }
        mock_read_file.return_value = {
            "ok": True,
            "path": "docs/plans/PLAN.md",
            "content": "# My Plan",
            "sha": "abc123",
            "repo": "org/repo",
        }
        result = server.project_read_plan(workstream_id="ws-test")
        mock_read_file.assert_called_once_with(
            path="docs/plans/PLAN.md",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            workstream_id="ws-test",
        )
        self.assertTrue(result["ok"])
        next_steps = result.get("next_steps", [])
        self.assertTrue(
            any("project_commit_plan" in s for s in next_steps),
            "Expected project_commit_plan in next_steps",
        )

    @patch.object(server, "github_read_file")
    @patch.object(server, "_find_workstream")
    def test_delegate_uses_explicit_branch(self, mock_find, mock_read_file):
        """An explicit branch parameter is forwarded to github_read_file."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "master",
            "planningDocument": "docs/plans/PLAN.md",
        }
        mock_read_file.return_value = {
            "ok": True,
            "path": "docs/plans/PLAN.md",
            "content": "# Plan",
            "sha": "def456",
            "repo": "org/repo",
        }
        server.project_read_plan(workstream_id="ws-test", branch="feature/x")
        mock_read_file.assert_called_once_with(
            path="docs/plans/PLAN.md",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            workstream_id="ws-test",
        )

    @patch.object(server, "_find_workstream")
    def test_no_planning_document(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        result = server.project_read_plan(workstream_id="ws-test")
        self.assertFalse(result["ok"])
        self.assertIn("planning document", result["error"])

    @patch.object(server, "_find_workstream")
    def test_workstream_not_found(self, mock_find):
        _grant_all_scopes()
        mock_find.return_value = None
        result = server.project_read_plan(workstream_id="ws-bad")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.project_read_plan(workstream_id="ws-test")


# -----------------------------------------------------------------------
# Tier 3: Memory tools
# -----------------------------------------------------------------------


class TestMemoryRecall(unittest.TestCase):

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_recall_basic(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "Found something", "score": 0.9,
             "tags": ["test"], "created_at": "2026-01-01", "repo_url": "", "branch": ""},
        ]
        mock_client_fn.return_value = client
        # scope="all" bypasses repo_url resolution — the test just verifies
        # that a bare query plumbs through to client.search.
        result = server.memory_recall(query="test query", scope="all")
        client.search.assert_called_once()
        self.assertTrue(result["ok"])
        self.assertEqual(len(result["memories"]), 1)
        self.assertEqual(result["memories"][0]["content"], "Found something")

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        _grant_all_scopes()
        result = server.memory_recall(query="test")
        self.assertFalse(result["ok"])
        self.assertIn("unavailable", result["error"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_no_results(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = []
        mock_client_fn.return_value = client
        result = server.memory_recall(query="nothing", scope="all")
        self.assertTrue(result["ok"])
        self.assertEqual(len(result["memories"]), 0)

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_include_messages(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.side_effect = [
            [{"id": "m1", "content": "memory", "score": 0.5}],
            [{"id": "m2", "content": "message", "score": 0.3}],
        ]
        mock_client_fn.return_value = client
        result = server.memory_recall(
            query="test", include_messages=True, scope="all")
        self.assertEqual(client.search.call_count, 2)
        second_call = client.search.call_args_list[1]
        self.assertEqual(second_call[1]["namespace"], "messages")

    def test_requires_memory_read_scope(self):
        _grant_scopes("read", "write", "memory-write")
        with self.assertRaises(PermissionError):
            server.memory_recall(query="test")

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    @patch.object(server, "_find_workstream")
    def test_resolve_from_workstream_repo_scope(self, mock_find, mock_client_fn, _):
        """Default scope=repo resolves repo_url but does not filter by branch."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        client = MagicMock()
        client.search.return_value = []
        mock_client_fn.return_value = client
        server.memory_recall(query="test", workstream_id="ws-test")
        call_kwargs = client.search.call_args[1]
        self.assertEqual(call_kwargs["repo_url"], "https://github.com/org/repo")
        self.assertIsNone(call_kwargs["branch"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    @patch.object(server, "_find_workstream")
    def test_resolve_from_workstream_branch_scope(self, mock_find, mock_client_fn, _):
        """scope=branch resolves both repo_url and branch from the workstream."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        client = MagicMock()
        client.search.return_value = []
        mock_client_fn.return_value = client
        server.memory_recall(query="test", workstream_id="ws-test", scope="branch")
        call_kwargs = client.search.call_args[1]
        self.assertEqual(call_kwargs["repo_url"], "https://github.com/org/repo")
        self.assertEqual(call_kwargs["branch"], "feature/x")


class TestMemoryBranchContext(unittest.TestCase):

    @patch.object(server, "_github_request")
    @patch.object(server, "_get_memory_client")
    def test_branch_context(self, mock_client_fn, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = [
            {"id": "m1", "content": "context", "created_at": "2026-01-01"},
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {
            "commits": [
                {"sha": "abc1234567", "commit": {
                    "author": {"name": "Dev", "date": "2026-01-01"},
                    "message": "Fix bug\nDetails"}},
            ]
        }
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_messages=False,
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 1)
        self.assertIn("commits", result)
        self.assertEqual(len(result["commits"]), 1)
        self.assertEqual(result["commits"][0]["message"], "Fix bug")

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        _grant_all_scopes()
        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x")
        self.assertFalse(result["ok"])

    def test_requires_repo_or_workstream(self):
        _grant_all_scopes()
        result = server.workstream_context()
        self.assertFalse(result["ok"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_get_memory_client")
    def test_include_messages_merge_with_explicit_namespace(self, mock_client_fn, mock_gh):
        # Back-compat path: when the caller narrows to a specific namespace,
        # include_messages=True still performs a second fetch against
        # "messages" and merges the two streams.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.side_effect = [
            [{"id": "m1", "content": "mem", "created_at": "2026-01-02"}],
            [{"id": "m2", "content": "msg", "created_at": "2026-01-01"}],
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            namespace="default",
            include_messages=True,
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 2)

    @patch.object(server, "_github_request")
    @patch.object(server, "_get_memory_client")
    def test_default_returns_all_namespaces_single_fetch(self, mock_client_fn, mock_gh):
        # Default behaviour: namespace is empty → server returns a merged
        # newest-first stream across every namespace in one call.
        # include_messages is a no-op in this mode.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = [
            {"id": "m1", "namespace": "feedback", "content": "fb",
             "created_at": "2026-01-03"},
            {"id": "m2", "namespace": "messages", "content": "msg",
             "created_at": "2026-01-02"},
            {"id": "m3", "namespace": "project", "content": "proj",
             "created_at": "2026-01-01"},
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 3)
        # Exactly one fetch — no per-namespace double-query.
        self.assertEqual(client.search_by_branch.call_count, 1)
        # The call must forward a None namespace (wildcard) to the client.
        call_kwargs = client.search_by_branch.call_args.kwargs
        self.assertIsNone(call_kwargs["namespace"])

    @patch.object(server, "_get_memory_client")
    def test_skip_commits(self, mock_client_fn):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        self.assertNotIn("commits", result)

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_jobs_timeline_compact(self, mock_client_fn, mock_controller_get, mock_gh):
        # workstream_context must include a compact jobs timeline when a
        # workstream is provided — just enough fields to situate memories,
        # NOT the operational payload (no costUsd, no targetBranch).
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        # _find_workstream() is invoked twice inside workstream_context
        # (once via _resolve_branch_context, once in the commit block),
        # followed by the jobs fetch. Return the workstream list for the
        # first two calls and the jobs list for the third.
        ws_list = [{"workstreamId": "w-1",
                    "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x",
                    "baseBranch": "master"}]
        jobs_list = [
            {"jobId": "j-1", "timestamp": "2026-04-20T10:00:00Z",
             "status": "SUCCESS", "description": "Fix bug",
             "commitHash": "abc1234567890def",
             "pullRequestUrl": "https://github.com/org/repo/pull/7",
             "targetBranch": "feature/x", "costUsd": 4.50},
            {"jobId": "j-2", "timestamp": "2026-04-20T09:00:00Z",
             "status": "FAILURE", "description": "Attempt",
             "errorMessage": "Git push failed",
             "costUsd": 1.20},
        ]
        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                return jobs_list
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertIn("jobs", result)
        self.assertEqual(2, len(result["jobs"]))
        j1, j2 = result["jobs"]
        # Compact fields present
        self.assertEqual("j-1", j1["jobId"])
        self.assertEqual("SUCCESS", j1["status"])
        self.assertEqual("Fix bug", j1["description"])
        self.assertEqual("abc1234567", j1["commitHash"])  # truncated to 10
        self.assertEqual("https://github.com/org/repo/pull/7", j1["pullRequestUrl"])
        # Operational fields absent
        self.assertNotIn("costUsd", j1)
        self.assertNotIn("targetBranch", j1)
        # Failure job includes errorMessage
        self.assertEqual("Git push failed", j2["errorMessage"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_jobs_field_present_even_when_empty(self, mock_client_fn, mock_controller_get, mock_gh):
        # When a workstream is provided and job_limit > 0, the "jobs" key
        # must appear in the response even if the workstream has no jobs —
        # an empty list is a meaningful signal distinct from omission.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [{"workstreamId": "w-1",
                    "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x"}]

        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                return []
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertIn("jobs", result)
        self.assertEqual([], result["jobs"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_job_limit_coerced_and_query_encoded(self, mock_client_fn, mock_controller_get, mock_gh):
        # Negative/garbage job_limit must not produce a malformed controller URL.
        # A negative int coerces to 0 (jobs fetch is skipped); a stringified
        # number coerces to the int value and is sent via urlencode.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [{"workstreamId": "w-1",
                    "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x"}]
        calls = []

        def controller_side_effect(path, timeout=10):
            calls.append(path)
            if "/jobs" in path:
                return []
            return ws_list

        # Negative → no jobs fetch, no /jobs path seen, jobs field omitted.
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1", job_limit=-5)
        self.assertNotIn("jobs", result)
        self.assertFalse(any("/jobs" in p for p in calls))

        # Stringified int → coerced, urlencoded query string.
        calls.clear()
        mock_controller_get.side_effect = controller_side_effect
        server.workstream_context(workstream_id="w-1", job_limit="3")
        jobs_paths = [p for p in calls if "/jobs" in p]
        self.assertEqual(1, len(jobs_paths))
        self.assertIn("limit=3", jobs_paths[0])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_jobs_timeline_omitted_when_job_limit_zero(self, mock_client_fn, mock_controller_get, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [
            {"workstreamId": "w-1",
             "repoUrl": "https://github.com/org/repo",
             "defaultBranch": "feature/x"},
        ]
        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                self.fail("jobs endpoint must not be called when job_limit=0")
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1", job_limit=0)
        self.assertTrue(result["ok"])
        self.assertNotIn("jobs", result)


class TestMemoryStore(unittest.TestCase):

    @patch.object(server, "_get_memory_client")
    def test_store_basic(self, mock_client_fn):
        _grant_all_scopes()
        client = MagicMock()
        client.store.return_value = {"id": "new-1", "content": "stored"}
        mock_client_fn.return_value = client
        result = server.memory_store(
            content="Something important",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        client.store.assert_called_once()
        call_kwargs = client.store.call_args[1]
        self.assertEqual(call_kwargs["content"], "Something important")
        self.assertTrue(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_get_memory_client")
    def test_store_with_tags(self, mock_client_fn):
        _grant_all_scopes()
        client = MagicMock()
        client.store.return_value = {"id": "new-2"}
        mock_client_fn.return_value = client
        server.memory_store(
            content="Tagged note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            tags=["bug", "fix"],
            source="test",
        )
        call_kwargs = client.store.call_args[1]
        self.assertEqual(call_kwargs["tags"], ["bug", "fix"])
        self.assertEqual(call_kwargs["source"], "test")

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        _grant_all_scopes()
        result = server.memory_store(
            content="test",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        self.assertFalse(result["ok"])

    def test_requires_branch_context(self):
        _grant_all_scopes()
        # Make sure no leftover thread-local / ContextVar / per-request
        # bearer survives from another test class — otherwise the new
        # token fallback in _resolve_branch_context would resolve here.
        server._set_token_context("", "")
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            result = server.memory_store(content="test")
        self.assertFalse(result["ok"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_get_memory_client")
    def test_store_resolves_repo_branch_from_token_workstream(
            self, mock_client_fn, mock_find_ws):
        """Regression: memory_store with only ``content`` and a valid token
        context must resolve repo_url and branch from the workstream the
        temp token is bound to. This mirrors the
        :func:`send_message` fix — job-scoped tools should accept a minimal
        payload because the workstream is already implicit in the bearer."""
        _grant_all_scopes()
        # Simulate the auth middleware having decoded a temp token bound to
        # workstream ``ws-token``.
        server._set_token_context("ws-token", "job-token")
        mock_find_ws.return_value = {
            "workstreamId": "ws-token",
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        client = MagicMock()
        client.store.return_value = {"id": "auto-resolved"}
        mock_client_fn.return_value = client

        # No workstream_id, no repo_url, no branch — only content.
        result = server.memory_store(content="Note from a job session")
        self.assertTrue(result["ok"], msg=result.get("error"))
        call_kwargs = client.store.call_args[1]
        self.assertEqual(call_kwargs["repo_url"],
                         "https://github.com/org/repo")
        self.assertEqual(call_kwargs["branch"], "feature/x")
        # Clean up to avoid leaking state into sibling tests.
        server._set_token_context("", "")

    def test_rejects_oversized_content(self):
        _grant_all_scopes()
        result = server.memory_store(
            content="x" * 50_001,
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        self.assertFalse(result["ok"])


# -----------------------------------------------------------------------
# send_message activity tagging
# -----------------------------------------------------------------------


class TestSendMessageActivity(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        # Clear any AR_AGENT_ACTIVITY env var set by tests
        os.environ.pop("AR_AGENT_ACTIVITY", None)

    @patch.object(server, "_controller_post")
    def test_activity_passed_to_controller(self, mock_post):
        """Explicit activity parameter is forwarded in the POST body."""
        mock_post.return_value = {"ok": True}
        server.send_message(text="Hello", activity="deduplication")
        mock_post.assert_called_once()
        call_args = mock_post.call_args
        body = call_args[0][1]
        self.assertEqual(body["text"], "Hello")
        self.assertEqual(body["activity"], "deduplication")

    @patch.object(server, "_controller_post")
    def test_no_activity_omits_field(self, mock_post):
        """When no activity is given and env var is unset, body has no activity field."""
        mock_post.return_value = {"ok": True}
        os.environ.pop("AR_AGENT_ACTIVITY", None)
        server.send_message(text="Primary work")
        body = mock_post.call_args[0][1]
        self.assertNotIn("activity", body)

    @patch.object(server, "_controller_post")
    def test_env_var_fallback(self, mock_post):
        """AR_AGENT_ACTIVITY env var is used when activity param is empty."""
        mock_post.return_value = {"ok": True}
        os.environ["AR_AGENT_ACTIVITY"] = "organizational_placement"
        server.send_message(text="Audit msg")
        body = mock_post.call_args[0][1]
        self.assertEqual(body["activity"], "organizational_placement")

    @patch.object(server, "_controller_post")
    def test_explicit_activity_overrides_env_var(self, mock_post):
        """Explicit activity takes precedence over AR_AGENT_ACTIVITY env var."""
        mock_post.return_value = {"ok": True}
        os.environ["AR_AGENT_ACTIVITY"] = "organizational_placement"
        server.send_message(text="Override", activity="deduplication")
        body = mock_post.call_args[0][1]
        self.assertEqual(body["activity"], "deduplication")


# -----------------------------------------------------------------------
# Per-request token decoding (FastMCP stateful-transport workaround)
# -----------------------------------------------------------------------


class TestPerRequestTokenDecoding(unittest.TestCase):
    """Regression tests for the fix that decodes the Bearer token directly
    from the current MCP tool call's HTTP request rather than relying on
    a ContextVar/threading.local set by the auth middleware.

    The opencode runner exposed a defect in the contextvar/thread-local
    mechanism: under FastMCP's streamable-HTTP **stateful** transport,
    tool handlers run on a long-lived "session task" whose context was
    captured at session creation time and is not updated by subsequent
    per-request auth middleware passes. The thread-local fallback is
    racy across concurrent requests on a single event-loop thread. The
    result was that ``send_message`` from an opencode session saw an
    empty workstream/job context and either errored out or landed
    messages at the top level of the workspace's Slack channel instead
    of inside the job thread.

    The fix decodes the Bearer token from the current request directly
    via ``mcp.get_context().request_context.request.headers``. This
    class verifies the decode helper and the upstream getters honour
    the per-request value.
    """

    def setUp(self):
        # Clear any leftover ContextVar / thread-local state so we are
        # testing the per-request path in isolation.
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id

    def tearDown(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id

    @staticmethod
    def _fake_context_with_bearer(token_value):
        """Return a stand-in for ``mcp.get_context()`` whose
        ``request_context.request.headers`` reports the given Bearer
        token (or no auth header at all when ``token_value`` is None).
        """
        headers = {}
        if token_value is not None:
            headers["authorization"] = "Bearer " + token_value

        fake_request = MagicMock()
        fake_request.headers = headers

        fake_request_context = MagicMock()
        fake_request_context.request = fake_request

        fake_ctx = MagicMock()
        fake_ctx.request_context = fake_request_context
        return fake_ctx

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_workstream_and_job_from_temp_token(self):
        token = server._mint_temp_token("ws-A", "job-A", ttl_seconds=60)
        self.assertIsNotNone(token)
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            ws, job = server._decode_current_request_token()
        self.assertEqual(ws, "ws-A")
        self.assertEqual(job, "job-A")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_no_bearer_header(self):
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(None)):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_no_request_context(self):
        fake_ctx = MagicMock()
        fake_ctx.request_context = None
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    # ------------------------------------------------------------------
    # Reason-string accuracy tests (Copilot review comment, PR #237)
    # _decode_current_request_token_full() must return "no_request" when
    # there is no live MCP request, and "no_context" only when the MCP
    # context itself is unavailable.
    # ------------------------------------------------------------------

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_full_decode_reason_no_context_when_get_context_raises(self):
        # mcp.get_context() raises → no MCP context at all → "no_context"
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no context")):
            _, _, _, reason = server._decode_current_request_token_full()
        self.assertEqual(reason, "no_context")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_full_decode_reason_no_request_when_request_context_none(self):
        # Context exists but request_context is None → no active request → "no_request"
        fake_ctx = MagicMock()
        fake_ctx.request_context = None
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            _, _, _, reason = server._decode_current_request_token_full()
        self.assertEqual(reason, "no_request")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_full_decode_reason_no_request_when_request_context_raises(self):
        # request_context property raises ValueError (e.g. unit-test context)
        # → no active request → "no_request"
        fake_ctx = MagicMock()
        type(fake_ctx).request_context = mock.PropertyMock(
            side_effect=ValueError("Context is not available outside of a request"))
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            _, _, _, reason = server._decode_current_request_token_full()
        self.assertEqual(reason, "no_request")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_for_static_token_bearer(self):
        # A non-HMAC bearer (e.g. a static long-lived admin token) is
        # not an ``armt_tmp_`` token; the decode helper must reject it.
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(
                              "static-admin-token")):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_get_context_lookup_errors(self):
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_request_context_property_raises(self):
        # ``FastMCP.Context.request_context`` raises ValueError when the
        # tool is invoked outside of a live MCP request (e.g. unit tests
        # that call the function directly). The decoder must treat that
        # as "no request" rather than crashing the tool.
        fake_ctx = MagicMock()
        type(fake_ctx).request_context = mock.PropertyMock(
            side_effect=ValueError("Context is not available outside of a request"))
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_token_getters_prefer_per_request_value_over_contextvar(self):
        # Simulate the FastMCP stateful-transport hazard: the session
        # task's ContextVar/thread-local were captured from a prior
        # request bound to a different workstream/job, but the actual
        # current HTTP request carries a temp token for ws-CURRENT.
        server._set_token_context("ws-STALE", "job-STALE")
        token = server._mint_temp_token("ws-CURRENT", "job-CURRENT", ttl_seconds=60)
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            self.assertEqual(server._get_token_workstream_id(), "ws-CURRENT")
            self.assertEqual(server._get_token_job_id(), "job-CURRENT")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_token_getters_fall_back_when_no_request(self):
        # When there is no active MCP request context, the legacy
        # ContextVar/thread-local fallback still applies. This keeps
        # in-process tests and stdio-transport callers working.
        server._set_token_context("ws-FALLBACK", "job-FALLBACK")
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            self.assertEqual(server._get_token_workstream_id(), "ws-FALLBACK")
            self.assertEqual(server._get_token_job_id(), "job-FALLBACK")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_send_message_uses_per_request_token_for_thread_routing(
            self, mock_post):
        """The end-to-end regression test for opencode's threading bug.

        With the session task carrying stale ContextVar state (as
        happens in FastMCP stateful streamable-HTTP transport),
        ``send_message`` must still post into the *current* request's
        job thread — derived from the Bearer token attached to the
        in-flight HTTP request — not the stale session-task context.
        """
        _grant_all_scopes()
        # Stale session-task context (the FastMCP hazard).
        server._set_token_context("ws-STALE", "job-STALE")
        # Current HTTP request carries a temp token for ws-A / job-7.
        token = server._mint_temp_token("ws-A", "job-7", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(text="Hello from opencode")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()
        called_path = mock_post.call_args[0][0]
        # Must route to /api/workstreams/ws-A/jobs/job-7/messages — NOT
        # to /api/workstreams/ws-STALE/... and NOT to the workstream-
        # level ``/messages`` endpoint (which would land at the top of
        # the channel).
        self.assertIn("/api/workstreams/ws-A/jobs/job-7/messages",
                      called_path)
        self.assertNotIn("ws-STALE", called_path)


# -----------------------------------------------------------------------
# send_message workstream_id is optional when a temp token resolves it
# -----------------------------------------------------------------------


class TestSendMessageWorkstreamIdOptional(unittest.TestCase):
    """Regression tests for the silent-opencode fix: ``send_message`` must
    accept a call with only ``{text, activity}`` when the in-flight request's
    HMAC temp token resolves to a workstream and job. The reported failure
    mode was the agent's very first ``send_message`` call returning
    ``"workstream_id is required ..."`` and the agent then giving up on
    operator status updates for the entire session. The fix ensures that
    token-based resolution is the default path; the explicit
    ``workstream_id`` argument remains an operator-side override.
    """

    def setUp(self):
        # Clear any leftover ContextVar / thread-local state so the
        # resolution paths are tested in isolation. The base behaviour
        # of opencode primary-phase jobs is "no prior state on the
        # server-task" since the streamable-HTTP transport is stateless.
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id

    def tearDown(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id

    @staticmethod
    def _fake_context_with_bearer(token_value):
        """Build a stand-in MCP context whose request carries ``token_value``
        as its Bearer header — the same shape as a real opencode/Claude
        Code stateless-HTTP request."""
        headers = {}
        if token_value is not None:
            headers["authorization"] = "Bearer " + token_value
        fake_request = MagicMock()
        fake_request.headers = headers
        fake_request_context = MagicMock()
        fake_request_context.request = fake_request
        fake_ctx = MagicMock()
        fake_ctx.request_context = fake_request_context
        return fake_ctx

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_resolves_workstream_from_temp_token_with_only_text(self, mock_post):
        """The headline regression: a job session's ``send_message`` call with
        only ``text`` (no workstream_id, no job_id) must succeed and post to
        the workstream/job the temp token resolves to.
        """
        _grant_all_scopes()
        token = server._mint_temp_token("ws-A", "job-7", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(text="Hello from opencode")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()
        called_path = mock_post.call_args[0][0]
        self.assertIn("/api/workstreams/ws-A/jobs/job-7/messages",
                      called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_text_and_activity_only_resolves_via_token(self, mock_post):
        """A call with ``{text, activity}`` only — the exact shape an
        opencode agent emits during its first status update — must succeed
        when the bearer is a valid temp token."""
        _grant_all_scopes()
        token = server._mint_temp_token("ws-B", "job-9", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(
                text="Starting work", activity="primary")
        self.assertTrue(result["ok"], msg=result.get("error"))
        body = mock_post.call_args[0][1]
        self.assertEqual(body["text"], "Starting work")
        self.assertEqual(body["activity"], "primary")
        called_path = mock_post.call_args[0][0]
        self.assertIn("ws-B", called_path)
        self.assertIn("job-9", called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_explicit_workstream_id_overrides_token(self, mock_post):
        """The override path: an explicit workstream_id still wins when
        supplied, even if the bearer resolves to a different workstream.
        This preserves the operator-side ability to route messages
        deliberately."""
        _grant_all_scopes()
        token = server._mint_temp_token("ws-TOKEN", "job-TOKEN", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(
                text="Routed elsewhere", workstream_id="ws-OVERRIDE")
        self.assertTrue(result["ok"], msg=result.get("error"))
        called_path = mock_post.call_args[0][0]
        self.assertIn("ws-OVERRIDE", called_path)
        self.assertNotIn("ws-TOKEN", called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_falls_back_to_thread_local_when_token_decode_fails(
            self, mock_post):
        """When the per-request bearer decode finds nothing (e.g. a static
        admin token on the wire), the legacy ContextVar/thread-local set by
        the auth middleware is still consulted. This keeps in-process tests
        and stdio-transport callers working."""
        _grant_all_scopes()
        server._set_token_context("ws-LOCAL", "job-LOCAL")
        mock_post.return_value = {"ok": True}
        # No request context → per-request decode returns ``no_context``;
        # the call must still resolve via the thread-local fallback.
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            result = server.send_message(text="Fallback path")
        self.assertTrue(result["ok"], msg=result.get("error"))
        called_path = mock_post.call_args[0][0]
        self.assertIn("ws-LOCAL", called_path)
        self.assertIn("job-LOCAL", called_path)

    @patch.object(server, "_controller_post")
    def test_genuinely_unresolvable_call_returns_clear_error(self, mock_post):
        """When there is no explicit workstream_id, no resolvable bearer,
        and no ContextVar/thread-local context, the call must error with a
        message that names both the override path and the token path so
        the caller can fix the missing context. This is the genuinely-
        unresolvable case — not the common opencode failure mode the
        token-fallback fix addresses."""
        _grant_all_scopes()
        # Explicitly clear any leftover state so this is truly unresolvable.
        server._set_token_context("", "")
        # No MCP context at all (the typical out-of-request test path).
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            result = server.send_message(text="Nowhere to go")
        self.assertFalse(result["ok"])
        self.assertIn("workstream_id", result["error"])
        # The new error message names both the explicit arg and the token
        # path so the caller knows the two ways to provide context.
        self.assertIn("token", result["error"].lower())
        # No POST attempted — the call short-circuits at validation.
        mock_post.assert_not_called()


# -----------------------------------------------------------------------
# workstream_context activity filtering
# -----------------------------------------------------------------------


class TestWorkstreamContextActivityFilter(unittest.TestCase):
    """Tests for the include_activities filtering in workstream_context."""

    def _make_memories(self):
        """Return a mix of primary, deduplication, and organizational messages."""
        return [
            {"id": "m1", "content": "primary work", "created_at": "2026-04-01",
             "tags": ["message"], "namespace": "messages"},
            {"id": "m2", "content": "dedup audit", "created_at": "2026-04-02",
             "tags": ["message", "activity:deduplication"], "namespace": "messages"},
            {"id": "m3", "content": "org placement", "created_at": "2026-04-03",
             "tags": ["message", "activity:organizational_placement"], "namespace": "messages"},
            {"id": "m4", "content": "tagged primary", "created_at": "2026-04-04",
             "tags": ["message", "activity:primary"], "namespace": "messages"},
            {"id": "m5", "content": "no tags", "created_at": "2026-04-05",
             "tags": [], "namespace": "default"},
        ]

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_default_hides_audit_activities(self, mock_client_fn, _gh):
        """Default include_activities=primary hides audit-phase messages."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = self._make_memories()
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        ids = [m["id"] for m in result["memories"]]
        # Primary (m1, m4, m5) included; dedup (m2) and org (m3) excluded
        self.assertIn("m1", ids)
        self.assertIn("m4", ids)
        self.assertIn("m5", ids)
        self.assertNotIn("m2", ids)
        self.assertNotIn("m3", ids)

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_include_all_returns_everything(self, mock_client_fn, _gh):
        """include_activities='all' disables filtering."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = self._make_memories()
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
            include_activities="all",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 5)

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_specific_activity_filter(self, mock_client_fn, _gh):
        """Requesting a specific activity returns that activity's messages plus primary/untagged messages."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = self._make_memories()
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
            include_activities="deduplication",
        )
        self.assertTrue(result["ok"])
        ids = [m["id"] for m in result["memories"]]
        # m2 (dedup) and untagged memories (m1, m5) included; org (m3) excluded
        self.assertIn("m2", ids)
        self.assertIn("m1", ids)
        self.assertIn("m5", ids)
        self.assertNotIn("m3", ids)

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_multiple_activities_comma_separated(self, mock_client_fn, _gh):
        """Comma-separated list includes all named activities."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = self._make_memories()
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
            include_activities="deduplication,organizational_placement",
        )
        self.assertTrue(result["ok"])
        ids = [m["id"] for m in result["memories"]]
        self.assertIn("m2", ids)
        self.assertIn("m3", ids)


class TestWorkstreamContextPullRequest(unittest.TestCase):
    """Tests for the pull_request field in workstream_context."""

    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_pull_request_included_when_found(self, mock_client_fn, _gh, mock_find_pr):
        """When a PR exists, pull_request field is included in response."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client

        mock_pr = {
            "number": 42,
            "title": "Add new feature",
            "html_url": "https://github.com/org/repo/pull/42",
            "state": "open",
            "created_at": "2026-01-01T00:00:00Z",
            "updated_at": "2026-01-02T00:00:00Z",
            "merged_at": None,
            "closed_at": None,
            "user": {"login": "author"},
            "base": {"ref": "master"},
            "head": {"ref": "feature/x"},
        }
        mock_find_pr.return_value = {"ok": True, "found": True, "pr": mock_pr}

        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        self.assertIn("pull_request", result)
        pr = result["pull_request"]
        self.assertEqual(pr["number"], 42)
        self.assertEqual(pr["title"], "Add new feature")
        self.assertEqual(pr["url"], "https://github.com/org/repo/pull/42")
        self.assertEqual(pr["state"], "open")
        self.assertEqual(pr["author"], "author")
        self.assertEqual(pr["base_branch"], "master")
        self.assertEqual(pr["head_branch"], "feature/x")

    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_pull_request_omitted_when_not_found(self, mock_client_fn, _gh, mock_find_pr):
        """When no PR exists, pull_request field is omitted."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client

        mock_find_pr.return_value = {"ok": True, "found": False, "branch": "feature/x"}

        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        self.assertNotIn("pull_request", result)

    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_pr_error_is_recorded(self, mock_client_fn, _gh, mock_find_pr):
        """When PR lookup fails, pr_error field is included."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client

        mock_find_pr.return_value = {"ok": False, "error": "GitHub API error"}

        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        self.assertIn("pr_error", result)
        self.assertEqual(result["pr_error"], "GitHub API error")

    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_get_memory_client")
    def test_closed_pr_has_merged_and_closed_timestamps(self, mock_client_fn, mock_find_pr):
        """Closed/merged PR includes merged_at and closed_at timestamps."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client

        mock_pr = {
            "number": 42,
            "title": "Feature merged",
            "html_url": "https://github.com/org/repo/pull/42",
            "state": "closed",
            "created_at": "2026-01-01T00:00:00Z",
            "updated_at": "2026-01-10T00:00:00Z",
            "merged_at": "2026-01-09T00:00:00Z",
            "closed_at": "2026-01-10T00:00:00Z",
            "user": {"login": "author"},
            "base": {"ref": "master"},
            "head": {"ref": "feature/x"},
        }
        mock_find_pr.return_value = {"ok": True, "found": True, "pr": mock_pr}

        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        pr = result["pull_request"]
        self.assertEqual(pr["state"], "closed")
        self.assertEqual(pr["merged_at"], "2026-01-09T00:00:00Z")
        self.assertEqual(pr["closed_at"], "2026-01-10T00:00:00Z")


class TestFindRecentPrByBranch(unittest.TestCase):
    """Unit tests for _find_recent_pr_by_branch GitHub API integration."""

    @patch.object(server, "_github_request")
    def test_calls_pulls_list_api_with_correct_params(self, mock_gh):
        """Verifies _find_recent_pr_by_branch uses the Pulls list API with state=all."""
        mock_gh.return_value = []
        result = server._find_recent_pr_by_branch("org", "repo", "feature/x")
        mock_gh.assert_called_once()
        call_args = mock_gh.call_args
        self.assertEqual(call_args[0][0], "GET")
        self.assertIn("/repos/org/repo/pulls", call_args[0][1])
        self.assertIn("head=org:feature/x", call_args[0][1])
        self.assertIn("state=all", call_args[0][1])
        self.assertIn("sort=updated", call_args[0][1])
        self.assertIn("direction=desc", call_args[0][1])
        self.assertIn("per_page=1", call_args[0][1])
        self.assertTrue(result["ok"])
        self.assertFalse(result["found"])

    @patch.object(server, "_github_request")
    def test_returns_pr_with_full_fields(self, mock_gh):
        """Verifies the returned PR object contains merged_at, base, and head fields."""
        mock_pr = {
            "number": 42,
            "title": "Feature",
            "html_url": "https://github.com/org/repo/pull/42",
            "state": "closed",
            "merged_at": "2026-01-09T00:00:00Z",
            "closed_at": "2026-01-10T00:00:00Z",
            "user": {"login": "author"},
            "base": {"ref": "master"},
            "head": {"ref": "feature/x"},
        }
        mock_gh.return_value = [mock_pr]
        result = server._find_recent_pr_by_branch("org", "repo", "feature/x")
        self.assertTrue(result["ok"])
        self.assertTrue(result["found"])
        pr = result["pr"]
        self.assertEqual(pr["merged_at"], "2026-01-09T00:00:00Z")
        self.assertEqual(pr["base"]["ref"], "master")
        self.assertEqual(pr["head"]["ref"], "feature/x")

    @patch.object(server, "_github_request")
    def test_handles_github_error(self, mock_gh):
        """Verifies GitHub API errors are propagated correctly."""
        mock_gh.return_value = {"ok": False, "error": "rate limited"}
        result = server._find_recent_pr_by_branch("org", "repo", "feature/x")
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "rate limited")


# -----------------------------------------------------------------------
# Controller HTTP helpers
# -----------------------------------------------------------------------


class TestControllerPost(unittest.TestCase):

    @patch("server.urlopen")
    def test_success_response(self, mock_urlopen):
        resp = MagicMock()
        resp.read.return_value = b'{"ok":true,"jobId":"j1"}'
        resp.__enter__ = lambda s: s
        resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = resp
        result = server._controller_post("/api/submit", {"prompt": "test"})
        self.assertTrue(result["ok"])
        self.assertEqual(result["jobId"], "j1")

    @patch("server.urlopen")
    def test_empty_body_returns_ok(self, mock_urlopen):
        resp = MagicMock()
        resp.read.return_value = b""
        resp.__enter__ = lambda s: s
        resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = resp
        result = server._controller_post("/api/test", {})
        self.assertTrue(result["ok"])

    @patch("server.urlopen")
    def test_http_error_with_json_body(self, mock_urlopen):
        error = HTTPError(
            url="http://test/api/submit", code=400, msg="Bad Request",
            hdrs=None, fp=None)
        error.read = lambda: b'{"ok":false,"error":"Missing prompt"}'
        mock_urlopen.side_effect = error
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("Missing prompt", result["error"])

    @patch("server.urlopen")
    def test_http_error_without_json(self, mock_urlopen):
        error = HTTPError(
            url="http://test/api/submit", code=500, msg="Server Error",
            hdrs=None, fp=None)
        error.read = lambda: b"Internal Server Error"
        mock_urlopen.side_effect = error
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("500", result["error"])

    @patch("server.urlopen")
    def test_url_error_returns_unreachable(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("Connection refused")
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("unreachable", result["error"])

    @patch("server.urlopen")
    def test_timeout_returns_error(self, mock_urlopen):
        mock_urlopen.side_effect = TimeoutError("timed out")
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("error", result)


# -----------------------------------------------------------------------
# _parse_dependent_repos helper
# -----------------------------------------------------------------------


class TestParseDependentRepos(unittest.TestCase):

    def test_empty_string_returns_empty(self):
        self.assertEqual([], server._parse_dependent_repos(""))

    def test_none_returns_empty(self):
        self.assertEqual([], server._parse_dependent_repos(None))

    def test_single_url_csv(self):
        result = server._parse_dependent_repos("https://github.com/org/repo")
        self.assertEqual(["https://github.com/org/repo"], result)

    def test_multiple_urls_csv(self):
        result = server._parse_dependent_repos(
            "https://github.com/org/a,https://github.com/org/b"
        )
        self.assertEqual(
            ["https://github.com/org/a", "https://github.com/org/b"], result
        )

    def test_csv_drops_empty_entries(self):
        result = server._parse_dependent_repos(
            "https://github.com/org/a,,https://github.com/org/b"
        )
        self.assertEqual(
            ["https://github.com/org/a", "https://github.com/org/b"], result
        )

    def test_json_array(self):
        result = server._parse_dependent_repos(
            '["https://github.com/org/a","https://github.com/org/b"]'
        )
        self.assertEqual(
            ["https://github.com/org/a", "https://github.com/org/b"], result
        )

    def test_json_array_drops_empty_entries(self):
        result = server._parse_dependent_repos('["https://github.com/org/a","","  "]')
        self.assertEqual(["https://github.com/org/a"], result)

    def test_invalid_json_falls_back_to_csv(self):
        result = server._parse_dependent_repos(
            "[https://github.com/org/a,https://github.com/org/b]"
        )
        # Invalid JSON — falls back to CSV splitting on commas
        self.assertIsInstance(result, list)
        # Should not raise; best-effort result acceptable

    def test_whitespace_only_returns_empty(self):
        self.assertEqual([], server._parse_dependent_repos("   "))


# -----------------------------------------------------------------------
# Input validation & auth
# -----------------------------------------------------------------------


class TestInputValidation(unittest.TestCase):

    def test_check_short_strings(self):
        result = server._check_short_strings(field="x" * 1001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])

    def test_check_short_strings_ok(self):
        result = server._check_short_strings(field="short")
        self.assertIsNone(result)

    def test_check_length(self):
        result = server._check_length("x" * 101, "field", 100)
        self.assertFalse(result["ok"])

    def test_check_length_ok(self):
        result = server._check_length("short", "field", 100)
        self.assertIsNone(result)


class TestScopeEnforcement(unittest.TestCase):

    def test_no_auth_permits_all(self):
        _clear_scopes()
        # Should not raise
        server._require_scope("read")
        server._require_scope("write")
        server._require_scope("pipeline")
        server._require_scope("memory")

    def test_scope_mismatch_raises(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server._require_scope("write")

    def test_scope_match_permits(self):
        _grant_scopes("read", "write")
        server._require_scope("read")
        server._require_scope("write")


class TestExtractOwnerRepo(unittest.TestCase):

    def test_https_url(self):
        result = server._extract_owner_repo("https://github.com/org/repo")
        self.assertEqual(result, ("org", "repo"))

    def test_https_url_with_git_suffix(self):
        result = server._extract_owner_repo("https://github.com/org/repo.git")
        self.assertEqual(result, ("org", "repo"))

    def test_ssh_url(self):
        result = server._extract_owner_repo("git@github.com:org/repo.git")
        self.assertEqual(result, ("org", "repo"))

    def test_invalid_url(self):
        result = server._extract_owner_repo("not-a-url")
        self.assertIsNone(result)


# -----------------------------------------------------------------------
# github_read_file
# -----------------------------------------------------------------------


class TestGithubReadFile(unittest.TestCase):
    """Tests for github_read_file."""

    def setUp(self):
        _grant_all_scopes()

    def _make_contents_response(self, path, content_text, size=None):
        """Build a mock GitHub Contents API response for a text file."""
        import base64 as _b64
        encoded = _b64.b64encode(content_text.encode("utf-8")).decode("ascii")
        return {
            "path": path,
            "sha": "abc123",
            "size": size if size is not None else len(content_text.encode("utf-8")),
            "content": encoded,
            "encoding": "base64",
        }

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_success(self, mock_gh, mock_repo):
        mock_gh.return_value = self._make_contents_response(
            "docs/README.md", "# Hello World\n"
        )
        result = server.github_read_file(path="docs/README.md")
        self.assertTrue(result["ok"])
        self.assertEqual(result["content"], "# Hello World\n")
        self.assertEqual(result["path"], "docs/README.md")
        self.assertEqual(result["repo"], "owner/repo")
        self.assertIn("sha", result)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_file_not_found(self, mock_gh, mock_repo):
        mock_gh.return_value = {"ok": False, "error": "GitHub returned HTTP 404: Not Found"}
        result = server.github_read_file(path="missing/file.py")
        self.assertFalse(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_binary_file_rejected(self, mock_gh, mock_repo):
        # Craft a response whose base64 decodes to non-UTF-8 bytes.
        import base64 as _b64
        raw_binary = bytes([0x00, 0xFF, 0xFE, 0x80, 0x81])
        encoded = _b64.b64encode(raw_binary).decode("ascii")
        mock_gh.return_value = {
            "path": "image.png",
            "sha": "abc",
            "size": len(raw_binary),
            "content": encoded,
            "encoding": "base64",
        }
        result = server.github_read_file(path="image.png")
        self.assertFalse(result["ok"])
        self.assertIn("binary", result["error"].lower())

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_oversized_file_rejected(self, mock_gh, mock_repo):
        # Size field exceeds 1 MB limit — content should never be decoded.
        import base64 as _b64
        small_content = "x"
        encoded = _b64.b64encode(small_content.encode()).decode("ascii")
        mock_gh.return_value = {
            "path": "big.bin",
            "sha": "abc",
            "size": 1_100_000,  # > 1 MB
            "content": encoded,
            "encoding": "base64",
        }
        result = server.github_read_file(path="big.bin")
        self.assertFalse(result["ok"])
        self.assertIn("1 MB", result["error"])
        self.assertEqual(result["size"], 1_100_000)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_request")
    def test_ref_used_in_request(self, mock_gh, mock_repo):
        mock_gh.return_value = self._make_contents_response(
            "src/main.py", "print('hello')\n"
        )
        server.github_read_file(path="src/main.py", ref="v1.2.3")
        call_args = mock_gh.call_args[0]
        self.assertIn("v1.2.3", call_args[1])

    @patch.object(server, "_github_request")
    def test_explicit_repo_url(self, mock_gh):
        mock_gh.return_value = self._make_contents_response(
            "README.md", "content\n"
        )
        result = server.github_read_file(
            path="README.md",
            repo_url="https://github.com/myorg/myrepo",
            branch="develop",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["repo"], "myorg/myrepo")
        call_path = mock_gh.call_args[0][1]
        self.assertIn("develop", call_path)

    @patch.object(server, "_github_request")
    def test_invalid_repo_url_returns_error(self, mock_gh):
        result = server.github_read_file(
            path="README.md",
            repo_url="not-a-github-url",
        )
        self.assertFalse(result["ok"])
        self.assertIn("Cannot parse", result["error"])
        mock_gh.assert_not_called()

    def test_missing_path_returns_error(self):
        result = server.github_read_file(path="")
        self.assertFalse(result["ok"])
        self.assertIn("path is required", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_read_file(path="README.md")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "not found"}))
    def test_repo_resolution_error_propagates(self, mock_repo):
        result = server.github_read_file(path="README.md", workstream_id="bad")
        self.assertFalse(result["ok"])


# -----------------------------------------------------------------------
# github_pr_check_status
# -----------------------------------------------------------------------


class TestGithubPrCheckStatus(unittest.TestCase):
    """Tests for github_pr_check_status."""

    def setUp(self):
        _grant_all_scopes()

    def _make_pr(self, number, sha="abc123", ref="feature/x"):
        return {
            "number": number,
            "head": {"sha": sha, "ref": ref},
            "html_url": f"https://github.com/owner/repo/pull/{number}",
        }

    def _make_runs(self, head_sha, runs_data):
        """Build a mock workflow runs API response."""
        return {"workflow_runs": [
            {
                "id": r.get("id", 1),
                "name": r.get("name", "CI"),
                "status": r.get("status", "completed"),
                "conclusion": r.get("conclusion", "success"),
                "head_sha": r.get("head_sha", head_sha),
                "created_at": "2026-04-24T00:00:00Z",
                "updated_at": "2026-04-24T00:01:00Z",
                "html_url": "https://github.com/owner/repo/actions/runs/1",
            }
            for r in runs_data
        ]}

    def _make_checks(self, checks_data):
        return {"check_runs": [
            {
                "id": c.get("id", 1),
                "name": c.get("name", "test"),
                "status": c.get("status", "completed"),
                "conclusion": c.get("conclusion", "success"),
                "html_url": "https://github.com/owner/repo/runs/1",
                "started_at": "2026-04-24T00:00:00Z",
                "completed_at": "2026-04-24T00:01:00Z",
                "details_url": c.get("details_url", ""),
            }
            for c in checks_data
        ]}

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_all_success(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            self._make_runs("abc123", [{"head_sha": "abc123", "conclusion": "success"}]),
            self._make_checks([{"conclusion": "success"}, {"conclusion": "skipped"}]),
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "success")
        self.assertTrue(result["pipeline_current"])
        self.assertEqual(result["pr_number"], 42)
        self.assertEqual(result["head_sha"], "abc123")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_partial_failure(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            self._make_runs("abc123", [{"head_sha": "abc123", "conclusion": "failure"}]),
            self._make_checks([
                {"name": "build", "conclusion": "success"},
                {"name": "test", "conclusion": "failure", "details_url": "https://logs/123"},
            ]),
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "failure")
        failed_checks = [c for c in result["check_runs"] if c["conclusion"] == "failure"]
        self.assertEqual(len(failed_checks), 1)
        self.assertIn("details_url", failed_checks[0])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_no_workflow_runs(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            {"workflow_runs": []},
            {"check_runs": []},
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "no_runs")
        self.assertFalse(result["pipeline_current"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_stale_workflow_run(self, mock_gh, mock_repo):
        # Workflow run exists but targets an older commit SHA
        mock_gh.side_effect = [
            self._make_pr(42, sha="new_sha"),
            self._make_runs("new_sha", [{"head_sha": "old_sha", "conclusion": "success"}]),
            {"check_runs": []},
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "stale")
        self.assertFalse(result["pipeline_current"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_lookup_pr_by_branch(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            [self._make_pr(7, sha="sha7")],  # PR list
            self._make_runs("sha7", [{"head_sha": "sha7"}]),
            {"check_runs": []},
        ]
        result = server.github_pr_check_status()
        self.assertTrue(result["ok"])
        self.assertEqual(result["pr_number"], 7)
        self.assertEqual(result["head_sha"], "sha7")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request", return_value=[])
    def test_no_open_pr_for_branch(self, mock_gh, mock_repo):
        result = server.github_pr_check_status()
        self.assertFalse(result["ok"])
        self.assertIn("No open PR", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "", None))
    def test_no_branch_and_no_pr_number(self, mock_repo):
        result = server.github_pr_check_status()
        self.assertFalse(result["ok"])
        self.assertIn("pr_number or branch", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "bad repo"}))
    def test_repo_resolution_error_propagates(self, mock_repo):
        result = server.github_pr_check_status(pr_number=1)
        self.assertFalse(result["ok"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_pr_check_status(pr_number=1)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "feature/x", None))
    @patch.object(server, "_github_request")
    def test_pending_checks(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            self._make_pr(42, sha="abc123"),
            self._make_runs("abc123", [{"head_sha": "abc123", "conclusion": None,
                                        "status": "in_progress"}]),
            self._make_checks([{"status": "in_progress", "conclusion": None}]),
        ]
        result = server.github_pr_check_status(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["overall_status"], "pending")


# -----------------------------------------------------------------------
# Tool registration (no duplicate names)
# -----------------------------------------------------------------------


class TestToolRegistration(unittest.TestCase):

    def test_no_duplicate_tool_names(self):
        """Verify all @mcp.tool() functions have unique names."""
        tool_names = []
        for tool in server.mcp._tool_manager._tools.values():
            tool_names.append(tool.name)
        self.assertEqual(len(tool_names), len(set(tool_names)),
                         f"Duplicate tool names: {tool_names}")

    def test_controller_update_config_registered(self):
        """The new controller_update_config tool must be registered."""
        tools = server.mcp._tool_manager._tools
        self.assertIn("controller_update_config", tools)

    def test_expected_tool_count(self):
        """Verify all tools are registered."""
        tools = server.mcp._tool_manager._tools
        expected = {
            "controller_health",
            "controller_update_config",
            "agent_options",
            "workstream_list",
            "workstream_get_status",
            "workstream_get_job",
            "workstream_submit_task",
            "workstream_register",
            "workstream_update_config",
            "workspace_update_config",
            "workstream_archive",
            "workstream_unarchive",
            "workstream_delete",
            "project_create_branch",
            "project_verify_branch",
            "project_commit_plan",
            "project_read_plan",
            "memory_recall",
            "workstream_context",
            "memory_store",
            "send_message",
            "github_pr_find",
            "github_pr_review_comments",
            "github_pr_conversation",
            "github_pr_reply",
            "github_list_open_prs",
            "github_create_pr",
            "github_request_copilot_review",
            "github_read_file",
            "github_pr_check_status",
            "tracker_list_projects",
            "tracker_create_project",
            "tracker_update_project",
            "tracker_delete_project",
            "tracker_list_releases",
            "tracker_create_release",
            "tracker_update_release",
            "tracker_delete_release",
            "tracker_create_task",
            "tracker_get_task",
            "tracker_list_tasks",
            "tracker_update_task",
            "tracker_delete_task",
            "tracker_search_tasks",
            "tracker_project_summary",
            "workspace_secret_list_names",
            "workspace_secret_render_file",
        }
        registered = set(tools.keys())
        missing = expected - registered
        extra = registered - expected
        self.assertFalse(missing, f"Missing tools: {missing}")
        self.assertFalse(extra, f"Unexpected tools: {extra}")


# -----------------------------------------------------------------------
# GitHub PR tools
# -----------------------------------------------------------------------


class TestGithubPrReviewComments(unittest.TestCase):
    """Tests for github_pr_review_comments (GraphQL-based, paginated)."""

    def setUp(self):
        _grant_all_scopes()

    def _make_graphql_response(self, threads, has_next=False, cursor="abc"):
        """Build a mock GraphQL response for reviewThreads."""
        return {
            "data": {
                "repository": {
                    "pullRequest": {
                        "reviewThreads": {
                            "pageInfo": {
                                "hasNextPage": has_next,
                                "endCursor": cursor,
                            },
                            "nodes": threads,
                        }
                    }
                }
            }
        }

    def _make_thread(self, resolved, comments):
        """Build a reviewThread node."""
        return {
            "isResolved": resolved,
            "comments": {
                "nodes": [
                    {
                        "databaseId": c.get("id", 1),
                        "path": c.get("path", "file.py"),
                        "line": c.get("line", 10),
                        "originalLine": c.get("originalLine"),
                        "body": c.get("body", "fix this"),
                        "author": {"login": c.get("user", "reviewer")},
                        "createdAt": c.get("createdAt", "2026-01-01T00:00:00Z"),
                    }
                    for c in comments
                ]
            },
        }

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_returns_unresolved_comments(self, mock_gql, mock_repo):
        unresolved = self._make_thread(False, [
            {"id": 101, "body": "please fix", "user": "alice",
             "createdAt": "2026-03-01T10:00:00Z"},
        ])
        resolved = self._make_thread(True, [
            {"id": 102, "body": "looks good", "user": "bob",
             "createdAt": "2026-03-02T10:00:00Z"},
        ])
        mock_gql.return_value = self._make_graphql_response(
            [unresolved, resolved], has_next=False)

        result = server.github_pr_review_comments(pr_number=42)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 1)
        self.assertEqual(result["comments"][0]["id"], 101)
        self.assertEqual(result["comments"][0]["body"], "please fix")
        self.assertEqual(result["comments"][0]["user"], "alice")
        self.assertIsNone(result["comments"][0]["in_reply_to_id"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_paginates_through_multiple_pages(self, mock_gql, mock_repo):
        page1_thread = self._make_thread(False, [
            {"id": 1, "body": "page1", "createdAt": "2026-01-01T00:00:00Z"},
        ])
        page2_thread = self._make_thread(False, [
            {"id": 2, "body": "page2", "createdAt": "2026-02-01T00:00:00Z"},
        ])
        mock_gql.side_effect = [
            self._make_graphql_response([page1_thread], has_next=True, cursor="c1"),
            self._make_graphql_response([page2_thread], has_next=False),
        ]

        result = server.github_pr_review_comments(pr_number=10)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 2)
        self.assertEqual(result["comments"][0]["id"], 2)
        self.assertEqual(result["comments"][1]["id"], 1)
        self.assertEqual(mock_gql.call_count, 2)
        first_vars = mock_gql.call_args_list[0][0][1]
        self.assertIsNone(first_vars["cursor"])
        second_vars = mock_gql.call_args_list[1][0][1]
        self.assertEqual(second_vars["cursor"], "c1")

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_empty_when_all_resolved(self, mock_gql, mock_repo):
        resolved = self._make_thread(True, [{"id": 1, "body": "done"}])
        mock_gql.return_value = self._make_graphql_response(
            [resolved], has_next=False)

        result = server.github_pr_review_comments(pr_number=5)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 0)
        self.assertEqual(result["comments"], [])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_caps_at_50_comments(self, mock_gql, mock_repo):
        comments = [
            {"id": i, "body": f"comment {i}",
             "createdAt": f"2026-01-{i+1:02d}T00:00:00Z"}
            for i in range(60)
        ]
        thread = self._make_thread(False, comments)
        mock_gql.return_value = self._make_graphql_response(
            [thread], has_next=False)

        result = server.github_pr_review_comments(pr_number=1)
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 50)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_graphql_error_returns_error(self, mock_gql, mock_repo):
        mock_gql.return_value = {
            "errors": [{"message": "Field 'foo' doesn't exist"}]
        }

        result = server.github_pr_review_comments(pr_number=99)
        self.assertFalse(result["ok"])
        self.assertIn("foo", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "no repo"}))
    def test_repo_resolution_error(self, mock_repo):
        result = server.github_pr_review_comments(pr_number=1)
        self.assertFalse(result["ok"])
        self.assertIn("no repo", result["error"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_pr_review_comments(pr_number=1)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "main", None))
    @patch.object(server, "_github_graphql_request")
    def test_uses_line_fallback_to_originalLine(self, mock_gql, mock_repo):
        thread = self._make_thread(False, [
            {"id": 1, "line": None, "originalLine": 42, "body": "outdated"},
        ])
        mock_gql.return_value = self._make_graphql_response(
            [thread], has_next=False)

        result = server.github_pr_review_comments(pr_number=1)
        self.assertEqual(result["comments"][0]["line"], 42)


# -----------------------------------------------------------------------
# github_request_copilot_review tests
# -----------------------------------------------------------------------


class TestGithubRequestCopilotReview(unittest.TestCase):
    """Tests for github_request_copilot_review and the _request_copilot_review helper."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "branch", None))
    @patch.object(server, "_request_copilot_review",
                  return_value={"ok": True})
    def test_requests_review_with_explicit_pr_number(self, mock_review, mock_repo):
        result = server.github_request_copilot_review(pr_number=42)
        self.assertTrue(result["ok"])
        mock_review.assert_called_once_with("owner", "repo", 42)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "my-branch", None))
    @patch.object(server, "_github_request")
    @patch.object(server, "_request_copilot_review",
                  return_value={"ok": True})
    def test_looks_up_pr_when_number_omitted(self, mock_review, mock_gh, mock_repo):
        mock_gh.return_value = [{"number": 99}]
        result = server.github_request_copilot_review()
        self.assertTrue(result["ok"])
        mock_review.assert_called_once_with("owner", "repo", 99)

    @patch.object(server, "_resolve_github_repo",
                  return_value=("owner", "repo", "my-branch", None))
    @patch.object(server, "_github_request", return_value=[])
    def test_returns_error_when_no_open_pr(self, mock_gh, mock_repo):
        result = server.github_request_copilot_review()
        self.assertFalse(result["ok"])
        self.assertIn("No open PR", result["error"])

    @patch.object(server, "_resolve_github_repo",
                  return_value=("", "", "", {"ok": False, "error": "bad repo"}))
    def test_repo_resolution_error_propagates(self, mock_repo):
        result = server.github_request_copilot_review(pr_number=1)
        self.assertFalse(result["ok"])

    def test_requires_github_scope(self):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_request_copilot_review(pr_number=1)

    # ------------------------------------------------------------------
    # Helpers for building GraphQL response payloads used by these tests.
    # ------------------------------------------------------------------

    @staticmethod
    def _lookup_response(pr_id="PR_kg1", bot_id="BOT_kg1",
                          bot_login="copilot-pull-request-reviewer",
                          include_copilot=True):
        """Build a CopilotLookup GraphQL response."""
        actors = []
        if include_copilot:
            actors.append({"__typename": "Bot", "login": bot_login, "id": bot_id})
        actors.append({"__typename": "User", "login": "alice", "id": "U_kg1"})
        return {"data": {"repository": {
            "pullRequest": {"id": pr_id} if pr_id else None,
            "suggestedActors": {"nodes": actors},
        }}}

    @staticmethod
    def _rest_response(reviewer_logins):
        """Build a realistic REST requested_reviewers PR response."""
        reviewers = [{"login": lg, "type": "Bot", "id": 12345}
                     for lg in reviewer_logins]
        return {"id": 1234567, "number": 10, "state": "open",
                "requested_reviewers": reviewers, "requested_teams": []}

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_success(self, mock_gql, mock_rest):
        # The implementation uses REST with the bot's login string, not
        # the GraphQL requestReviews mutation (which fails for Bot node IDs).
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1",
            bot_login="copilot-pull-request-reviewer")
        mock_rest.return_value = self._rest_response(["copilot-pull-request-reviewer"])
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        # Only one GraphQL call (the lookup) — no GraphQL mutation.
        self.assertEqual(mock_gql.call_count, 1)
        lookup_call_args = mock_gql.call_args_list[0][0]
        self.assertIn("CopilotLookup", lookup_call_args[0])
        self.assertEqual(lookup_call_args[1],
                         {"owner": "owner", "name": "repo", "number": 10})
        # REST called with the bot's login string, not its Bot node ID.
        mock_rest.assert_called_once_with(
            "owner", "repo", 10, "copilot-pull-request-reviewer"
        )

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_no_op_without_bot_is_failure(
            self, mock_gql, mock_rest):
        # Regression guard: the REST call returns 2xx but the bot is absent
        # from requested_reviewers (e.g. Copilot toggle disabled after the
        # lookup gate). Must NOT claim success.
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        mock_rest.return_value = self._rest_response([])
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("not added", result["error"].lower())
        self.assertIn("Copilot", result["error"])

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_recognises_all_observed_logins(
            self, mock_gql, mock_rest):
        # GitHub exposes Copilot under different login strings on different
        # endpoints (verified on live PRs against github.com):
        #   - ``copilot-pull-request-reviewer``        (suggestedActors Bot.login)
        #   - ``Copilot``                              (GET /pulls/N/comments user.login)
        #   - ``copilot-pull-request-reviewer[bot]``   (GET /pulls/N/reviews user.login)
        # The REST response's requested_reviewers must match all three.
        for login in ("copilot-pull-request-reviewer", "Copilot",
                      "copilot-pull-request-reviewer[bot]"):
            mock_gql.reset_mock()
            mock_rest.reset_mock()
            mock_gql.return_value = self._lookup_response(
                pr_id="PR_kg1", bot_id="BOT_kg1")
            mock_rest.return_value = self._rest_response([login])
            result = server._request_copilot_review("owner", "repo", 10)
            self.assertTrue(result["ok"],
                            f"Expected ok=True for login={login!r}, got {result}")

    def test_is_copilot_login_rejects_unrelated_users(self):
        # Sanity-check the helper directly: only logins whose lowercase form
        # contains "copilot" count. Non-Copilot users must not be recognised.
        self.assertFalse(server._is_copilot_login("ashesfall"))
        self.assertFalse(server._is_copilot_login("dependabot[bot]"))
        self.assertFalse(server._is_copilot_login(""))
        self.assertFalse(server._is_copilot_login(None))  # type: ignore[arg-type]
        self.assertTrue(server._is_copilot_login("copilot"))
        self.assertTrue(server._is_copilot_login("Copilot"))
        self.assertTrue(server._is_copilot_login("copilot-pull-request-reviewer"))
        self.assertTrue(server._is_copilot_login("copilot-pull-request-reviewer[bot]"))

    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_pr_not_found(self, mock_gql):
        """When the lookup query returns no PR, surfaces a clear error."""
        mock_gql.return_value = self._lookup_response(pr_id=None)
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("PR #10", result["error"])

    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_copilot_disabled(self, mock_gql):
        """When Copilot is absent from suggestedActors, returns a clear error
        instructing the user to enable Copilot code review for the repo."""
        mock_gql.return_value = self._lookup_response(include_copilot=False)
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("Copilot is not available", result["error"])

    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_lookup_transport_error(self, mock_gql):
        """When the lookup transport fails, the error propagates unchanged."""
        mock_gql.return_value = {"ok": False, "error": "controller proxy unreachable"}
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("controller proxy unreachable", result["error"])

    @patch.object(server, "_dismiss_copilot_review", return_value={"ok": True})
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_retries_after_dismiss(
            self, mock_gql, mock_rest, mock_dismiss):
        """When REST returns 'cannot be requested', dismisses the prior review
        and retries. The retry succeeds and the helper returns ok=True."""
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        # First REST call: GitHub 422 "already reviewed"
        mock_rest.side_effect = [
            {"ok": False,
             "error": "GitHub returned HTTP 422: Review cannot be requested at this time"},
            self._rest_response(["copilot-pull-request-reviewer"]),
        ]
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        mock_dismiss.assert_called_once_with("owner", "repo", 10)
        self.assertEqual(mock_rest.call_count, 2)

    @patch.object(server, "_dismiss_copilot_review",
                  return_value={"ok": False, "error": "No dismissible reviews"})
    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_error_when_dismiss_fails(
            self, mock_gql, mock_rest, mock_dismiss):
        """When REST fails with 'cannot be requested' and dismiss also fails,
        returns ok=False propagating the original error."""
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        mock_rest.return_value = {
            "ok": False,
            "error": "GitHub returned HTTP 422: Review cannot be requested at this time",
        }
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("Review request failed", result["error"])
        self.assertIn("Review cannot be requested", result["error"])

    @patch.object(server, "_request_reviews_via_rest")
    @patch.object(server, "_github_graphql_request")
    def test_request_copilot_review_helper_rest_transport_error(
            self, mock_gql, mock_rest):
        """Surfaces a REST transport error that is not the 'already reviewed'
        shape without attempting a dismiss/retry."""
        mock_gql.return_value = self._lookup_response(
            pr_id="PR_kg1", bot_id="BOT_kg1")
        mock_rest.return_value = {
            "ok": False,
            "error": "GitHub returned HTTP 403: Resource not accessible by token",
        }
        result = server._request_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("Resource not accessible by token", result["error"])

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_success(self, mock_gh):
        """Dismisses the most recent CHANGES_REQUESTED Copilot review."""
        mock_gh.side_effect = [
            [
                {
                    "id": 42,
                    "user": {"login": "copilot-pull-request-reviewer[bot]"},
                    "state": "CHANGES_REQUESTED",
                    "submitted_at": "2026-04-14T10:00:00Z",
                }
            ],
            {"id": 42, "state": "DISMISSED"},
        ]
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        mock_gh.assert_any_call("GET", "/repos/owner/repo/pulls/10/reviews")
        mock_gh.assert_any_call(
            "PUT",
            "/repos/owner/repo/pulls/10/reviews/42/dismissals",
            {"message": "Dismissing prior Copilot review to allow re-review"},
        )

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_no_dismissible_reviews(self, mock_gh):
        """Returns ok=False when no dismissible Copilot reviews exist."""
        mock_gh.return_value = [
            {
                "id": 1,
                "user": {"login": "copilot-pull-request-reviewer[bot]"},
                "state": "COMMENTED",
                "submitted_at": "2026-04-14T09:00:00Z",
            }
        ]
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])
        self.assertIn("No dismissible", result["error"])

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_list_error(self, mock_gh):
        """Returns ok=False when the reviews list request fails."""
        mock_gh.return_value = {"ok": False, "error": "not found"}
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertFalse(result["ok"])

    @patch.object(server, "_github_request")
    def test_dismiss_copilot_review_picks_most_recent(self, mock_gh):
        """Dismisses the most recently submitted review when multiple exist."""
        mock_gh.side_effect = [
            [
                {
                    "id": 1,
                    "user": {"login": "copilot-pull-request-reviewer[bot]"},
                    "state": "APPROVED",
                    "submitted_at": "2026-04-13T08:00:00Z",
                },
                {
                    "id": 2,
                    "user": {"login": "copilot-pull-request-reviewer[bot]"},
                    "state": "CHANGES_REQUESTED",
                    "submitted_at": "2026-04-14T10:00:00Z",
                },
            ],
            {"id": 2, "state": "DISMISSED"},
        ]
        result = server._dismiss_copilot_review("owner", "repo", 10)
        self.assertTrue(result["ok"])
        mock_gh.assert_any_call(
            "PUT",
            "/repos/owner/repo/pulls/10/reviews/2/dismissals",
            {"message": "Dismissing prior Copilot review to allow re-review"},
        )


# -----------------------------------------------------------------------
# Workspace scope enforcement
# -----------------------------------------------------------------------


def _set_workspaces(*workspace_ids):
    """Mark the current request as scoped to the given workspace IDs."""
    server._set_workspace_scopes(list(workspace_ids) if workspace_ids else None)


def _clear_workspaces():
    server._request_workspace_scopes.set(None)
    if hasattr(server._thread_local, "workspace_scopes"):
        del server._thread_local.workspace_scopes


def _reset_workspace_cache():
    server._workspace_map_cache["map"] = None
    server._workspace_map_cache["fetched"] = 0.0


class TestWorkspaceScopeHelpers(unittest.TestCase):

    def setUp(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def test_unscoped_allows_any_workspace(self):
        self.assertTrue(server._is_workspace_allowed("TAAA"))
        self.assertTrue(server._is_workspace_allowed(None))

    def test_scoped_allows_listed_workspace(self):
        _set_workspaces("TAAA")
        self.assertTrue(server._is_workspace_allowed("TAAA"))

    def test_scoped_denies_other_workspace(self):
        _set_workspaces("TAAA")
        self.assertFalse(server._is_workspace_allowed("TBBB"))

    def test_scoped_denies_unknown_workspace(self):
        _set_workspaces("TAAA")
        self.assertFalse(server._is_workspace_allowed(None))
        self.assertFalse(server._is_workspace_allowed(""))

    def test_require_workspace_raises_on_mismatch(self):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server._require_workspace("TBBB")

    def test_require_workspace_noop_when_unscoped(self):
        server._require_workspace("TANYTHING")  # does not raise


class TestWorkspaceCacheAndFilter(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_workspace_for_workstream_resolves_and_caches(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-1", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-2", "slackWorkspaceId": "TBBB"},
        ]
        self.assertEqual("TAAA", server._workspace_for_workstream("ws-1"))
        self.assertEqual("TBBB", server._workspace_for_workstream("ws-2"))
        self.assertEqual(1, mock_get.call_count)  # cache hit on second call

    @patch.object(server, "_controller_get")
    def test_workspace_for_workstream_returns_none_for_unknown(self, mock_get):
        mock_get.return_value = [{"workstreamId": "ws-1", "slackWorkspaceId": "TAAA"}]
        self.assertIsNone(server._workspace_for_workstream("ws-missing"))

    def test_filter_workstreams_passthrough_unscoped(self):
        entries = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "b", "slackWorkspaceId": "TBBB"},
        ]
        self.assertEqual(entries, server._filter_workstreams_by_scope(entries))

    def test_filter_workstreams_restricts_to_scope(self):
        _set_workspaces("TAAA")
        entries = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "b", "slackWorkspaceId": "TBBB"},
            {"workstreamId": "c", "slackWorkspaceId": "TAAA"},
        ]
        filtered = server._filter_workstreams_by_scope(entries)
        self.assertEqual(["a", "c"], [e["workstreamId"] for e in filtered])


class TestWorkstreamListFiltering(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_unscoped_sees_everything(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "b", "slackWorkspaceId": "TBBB"},
        ]
        result = server.workstream_list()
        self.assertEqual(2, result["count"])
        self.assertEqual({"a", "b"}, {w["workstreamId"] for w in result["workstreams"]})

    @patch.object(server, "_controller_get")
    def test_scoped_sees_only_in_scope(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "b", "slackWorkspaceId": "TBBB"},
        ]
        _set_workspaces("TBBB")
        result = server.workstream_list()
        self.assertEqual(1, result["count"])
        self.assertEqual(["b"], [w["workstreamId"] for w in result["workstreams"]])


class TestWorkstreamWriteEnforcement(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_submit_rejected_for_out_of_scope(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
        ]
        _set_workspaces("TBBB")
        with self.assertRaises(PermissionError):
            server.workstream_submit_task(workstream_id="a", prompt="hi")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_submit_allowed_when_in_scope(self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
        ]
        mock_post.return_value = {"ok": True, "jobId": "j-1"}
        _set_workspaces("TAAA")
        result = server.workstream_submit_task(workstream_id="a", prompt="hi")
        self.assertTrue(result["ok"])

    def test_controller_update_config_rejected_for_scoped_token(self):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.controller_update_config(accept_automated_jobs="true")


class TestWorkstreamRegisterScope(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_post")
    def test_scoped_requires_slack_workspace_id(self, mock_post):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.workstream_register(default_branch="feature/x")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_scoped_rejects_out_of_scope_workspace(self, mock_post):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.workstream_register(
                default_branch="feature/x", slack_workspace_id="TBBB")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_scoped_passes_slack_workspace_to_controller(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        _set_workspaces("TAAA")
        server.workstream_register(
            default_branch="feature/x", slack_workspace_id="TAAA")
        args, _ = mock_post.call_args
        self.assertEqual("/api/workstreams", args[0])
        self.assertEqual("TAAA", args[1]["slackWorkspaceId"])

    @patch.object(server, "_controller_post")
    def test_unscoped_need_not_pass_slack_workspace(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        # No workspace scope set — rely on controller-side derivation.
        result = server.workstream_register(
            default_branch="feature/x",
            repo_url="git@github.com:almostrealism/common.git")
        self.assertTrue(result["ok"])
        args, _ = mock_post.call_args
        self.assertNotIn("slackWorkspaceId", args[1])

    @patch.object(server, "_controller_post")
    def test_workspace_id_param_routes_like_legacy(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        _set_workspaces("almostrealism")
        server.workstream_register(
            default_branch="feature/x", workspace_id="almostrealism")
        args, _ = mock_post.call_args
        self.assertEqual("/api/workstreams", args[0])
        # Both names go on the wire for cross-version compatibility.
        self.assertEqual("almostrealism", args[1]["workspaceId"])
        self.assertEqual("almostrealism", args[1]["slackWorkspaceId"])

    @patch.object(server, "_controller_post")
    def test_legacy_slack_workspace_id_alias_still_accepted(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        _set_workspaces("TAAA")
        server.workstream_register(
            default_branch="feature/x", slack_workspace_id="TAAA")
        args, _ = mock_post.call_args
        self.assertEqual("TAAA", args[1]["workspaceId"])
        self.assertEqual("TAAA", args[1]["slackWorkspaceId"])


class TestBearerAuthWorkspaceScopes(unittest.TestCase):

    def test_empty_workspace_scopes_treated_as_unscoped(self):
        tokens = [{"value": "tok", "label": "t", "scopes": ["read"],
                   "workspaceScopes": []}]
        middleware = server.BearerAuthMiddleware(app=None, tokens=tokens)
        # The fourth field is workspace_scopes — empty list must normalise to None.
        entry = middleware.token_entries[0]
        self.assertIsNone(entry[3])

    def test_populated_workspace_scopes_retained(self):
        tokens = [{"value": "tok", "label": "t", "scopes": ["read"],
                   "workspaceScopes": ["TAAA", "TBBB"]}]
        middleware = server.BearerAuthMiddleware(app=None, tokens=tokens)
        self.assertEqual(["TAAA", "TBBB"], middleware.token_entries[0][3])


# -----------------------------------------------------------------------
# workstream_register plan follow-up (plan_content / plan_instructions)
# -----------------------------------------------------------------------


class TestWorkstreamRegisterPlanFollowup(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def test_rejects_both_plan_content_and_plan_instructions(self):
        result = server.workstream_register(
            default_branch="feature/x",
            plan_content="# Plan",
            plan_instructions="Write a plan about X",
        )
        self.assertFalse(result["ok"])
        self.assertIn("mutually exclusive", result["error"])

    @patch.object(server, "project_commit_plan")
    @patch.object(server, "_controller_post")
    def test_plan_content_committed_successfully(self, mock_post, mock_commit):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        mock_commit.return_value = {
            "ok": True, "path": "docs/plans/x.md",
            "branch": "feature/x", "commit_sha": "abc123",
            "repo": "almostrealism/common",
        }
        result = server.workstream_register(
            default_branch="feature/x",
            plan_content="# Plan for X",
        )
        self.assertTrue(result["ok"])
        self.assertEqual("committed", result["plan"]["mode"])
        self.assertEqual("docs/plans/x.md", result["plan"]["path"])
        self.assertEqual("abc123", result["plan"]["commit_sha"])

    @patch.object(server, "project_commit_plan")
    @patch.object(server, "_controller_post")
    def test_plan_content_commit_rejected_still_registers(self, mock_post, mock_commit):
        # GitHub rejects the direct commit (e.g. missing contents:write).
        # Registration itself must still succeed.
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        mock_commit.return_value = {
            "ok": False,
            "error": "403: Resource not accessible by personal access token",
        }
        result = server.workstream_register(
            default_branch="feature/x",
            plan_content="# Plan",
        )
        self.assertTrue(result["ok"])
        self.assertEqual("w-1", result["workstreamId"])
        self.assertEqual("failed", result["plan"]["mode"])
        self.assertEqual("commit_rejected", result["plan"]["reason"])
        self.assertIn("403", result["plan"]["error"])
        self.assertIn("fallback_instructions", result["plan"])

    @patch.object(server, "project_commit_plan")
    @patch.object(server, "_controller_post")
    def test_plan_content_permission_error_still_registers(self, mock_post, mock_commit):
        # Plan commit requires the 'pipeline' scope. A token without it
        # raises PermissionError inside project_commit_plan. Register must
        # still succeed and the response must explain the fallback.
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        mock_commit.side_effect = PermissionError(
            "Token does not have required scope: pipeline")
        result = server.workstream_register(
            default_branch="feature/x",
            plan_content="# Plan",
        )
        self.assertTrue(result["ok"])
        self.assertEqual("failed", result["plan"]["mode"])
        self.assertEqual("insufficient_scope", result["plan"]["reason"])
        self.assertIn("pipeline", result["plan"]["error"])

    @patch.object(server, "workstream_submit_task")
    @patch.object(server, "_controller_post")
    def test_plan_instructions_submits_job(self, mock_post, mock_submit):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        mock_submit.return_value = {"ok": True, "jobId": "j-42"}
        result = server.workstream_register(
            default_branch="feature/x",
            plan_instructions="Describe how we will refactor the foo subsystem.",
            plan_path="docs/plans/foo.md",
        )
        self.assertTrue(result["ok"])
        self.assertEqual("submitted", result["plan"]["mode"])
        self.assertEqual("j-42", result["plan"]["job_id"])
        # The prompt passed to submit_task must embed the instructions and the
        # target path so the agent knows where to write.
        submit_kwargs = mock_submit.call_args.kwargs
        self.assertEqual("w-1", submit_kwargs["workstream_id"])
        self.assertIn("docs/plans/foo.md", submit_kwargs["prompt"])
        self.assertIn("refactor the foo subsystem", submit_kwargs["prompt"])

    @patch.object(server, "workstream_submit_task")
    @patch.object(server, "_controller_post")
    def test_plan_instructions_submit_rejected_still_registers(self, mock_post, mock_submit):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        mock_submit.return_value = {"ok": False, "error": "No agents connected"}
        result = server.workstream_register(
            default_branch="feature/x",
            plan_instructions="Describe the plan.",
        )
        self.assertTrue(result["ok"])
        self.assertEqual("failed", result["plan"]["mode"])
        self.assertEqual("submit_rejected", result["plan"]["reason"])
        self.assertIn("No agents", result["plan"]["error"])

    @patch.object(server, "_controller_post")
    def test_no_plan_fields_leaves_plan_absent(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        result = server.workstream_register(default_branch="feature/x")
        self.assertTrue(result["ok"])
        self.assertNotIn("plan", result)


# -----------------------------------------------------------------------
# ar-manager no longer holds a GitHub token — proxy-only GitHub access
# -----------------------------------------------------------------------


class TestNoDirectGithubPath(unittest.TestCase):

    def test_server_module_has_no_github_token_symbol(self):
        # The legacy GITHUB_TOKEN / AR_MANAGER_GITHUB_TOKEN plumbing is gone;
        # nothing in the module should reference a local token anymore.
        self.assertFalse(hasattr(server, "GITHUB_TOKEN"))

    def test_github_api_module_has_no_direct_request(self):
        import github_api
        self.assertFalse(hasattr(github_api, "_github_direct_request"))
        self.assertFalse(hasattr(github_api, "_github_token"))


class TestOrgScopeGate(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_org_map_derived_from_workstream_list(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/Plytrix/plytrix-platform.git"},
        ]
        self.assertEqual({"TAAA"}, server._workspaces_for_org("almostrealism"))
        self.assertEqual({"TBBB"}, server._workspaces_for_org("Plytrix"))
        self.assertEqual(set(), server._workspaces_for_org("other-org"))

    @patch.object(server, "_controller_get")
    def test_org_spanning_multiple_workspaces_tracks_all(self, mock_get):
        # Same org registered under two workspaces → both must appear so
        # _require_org_in_scope can detect the ambiguity.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:shared-org/repo-a.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "git@github.com:shared-org/repo-b.git"},
        ]
        self.assertEqual({"TAAA", "TBBB"}, server._workspaces_for_org("shared-org"))

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_unscoped_passes(self, mock_get):
        mock_get.return_value = []
        server._require_org_in_scope("any-org")  # no raise

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_scoped_accepts_in_scope(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        _set_workspaces("TAAA")
        server._require_org_in_scope("almostrealism")  # no raise

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_scoped_rejects_out_of_scope(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/Plytrix/plytrix-platform.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server._require_org_in_scope("Plytrix")

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_scoped_rejects_unknown_org(self, mock_get):
        # An org with no workstream on any workspace is treated as unknown
        # and therefore out-of-scope for scoped tokens.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server._require_org_in_scope("some-untracked-org")

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_rejects_ambiguous_multi_workspace_org(self, mock_get):
        # Same org under two workspaces: even when the caller's scope
        # contains ONE of them, direct-org addressing must be denied
        # because the controller's per-org PAT is last-wins and the
        # proxy may end up using the other workspace's token.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:shared-org/repo-a.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "git@github.com:shared-org/repo-b.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError) as ctx:
            server._require_org_in_scope("shared-org")
        self.assertIn("multiple", str(ctx.exception).lower())


class TestTempTokenWorkspaceScoping(unittest.TestCase):
    """Covers the security fix that temp tokens are no longer treated
    as superadmin — their workspace scope is derived from the bound
    workstream's ``slackWorkspaceId`` at validate time."""

    def setUp(self):
        _clear_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_multi_workspace_mode_detection(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        self.assertTrue(server._is_multi_workspace_mode())

    @patch.object(server, "_controller_get")
    def test_legacy_mode_detection_empty_workspaces(self, mock_get):
        # No workstream has a slackWorkspaceId — single-workspace legacy.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": None,
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        self.assertFalse(server._is_multi_workspace_mode())


if False:
    # Placeholder — BearerAuthMiddleware tests using asgi scope are covered
    # via the synchronous helpers above; a full ASGI roundtrip test would
    # require an event loop and is disproportionate to the logic under test.
    pass


class TestGithubToolsDirectAddressing(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    def test_pr_find_with_explicit_org_repo_bypasses_workstream(
            self, mock_get, mock_gh):
        # The workstream list is empty; without direct addressing this would
        # fall through to the error path. With org+repo it should succeed.
        mock_get.return_value = []
        mock_gh.return_value = [
            {"number": 1, "title": "t", "html_url": "u", "state": "open"},
        ]
        result = server.github_pr_find(
            branch="feature/x", org="almostrealism", repo="common")
        self.assertTrue(result["ok"])
        self.assertTrue(result["found"])
        # The proxy call path should include almostrealism/common
        called_path = mock_gh.call_args.args[1]
        self.assertIn("/repos/almostrealism/common/pulls", called_path)

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    def test_pr_find_rejects_half_addressing(self, mock_get, mock_gh):
        # Supplying only org (no repo) is ambiguous; the resolver must error
        # before any HTTP is attempted.
        mock_get.return_value = []
        result = server.github_pr_find(org="almostrealism")
        self.assertFalse(result["ok"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    def test_pr_find_scoped_token_rejects_out_of_scope_org(
            self, mock_get, mock_gh):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/Plytrix/plytrix-platform.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.github_pr_find(org="Plytrix", repo="plytrix-platform",
                                  branch="feature/x")
        mock_gh.assert_not_called()


# -----------------------------------------------------------------------
# Tracker MCP tools
# -----------------------------------------------------------------------


class TestTrackerTools(unittest.TestCase):
    """Tests for tracker_* MCP tools in ar-manager server.py.

    All tracker HTTP calls are mocked so no running ar-tracker service
    is required.
    """

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_tracker_get")
    def test_tracker_list_projects(self, mock_get):
        mock_get.return_value = {"ok": True, "projects": [{"id": "p1", "name": "Rings"}]}
        result = server.tracker_list_projects()
        self.assertTrue(result["ok"])
        mock_get.assert_called_once_with("/v1/projects")

    @patch.object(server, "_tracker_post")
    def test_tracker_create_project(self, mock_post):
        mock_post.return_value = {"ok": True, "project": {"id": "p1", "name": "Rings"}}
        result = server.tracker_create_project("Rings")
        self.assertTrue(result["ok"])
        mock_post.assert_called_once_with("/v1/projects", {"name": "Rings"})

    @patch.object(server, "_tracker_put")
    def test_tracker_update_project(self, mock_put):
        mock_put.return_value = {"ok": True, "project": {"id": "p1", "name": "New"}}
        result = server.tracker_update_project("p1", "New")
        self.assertTrue(result["ok"])
        mock_put.assert_called_once_with("/v1/projects/p1", {"name": "New"})

    @patch.object(server, "_tracker_delete")
    def test_tracker_delete_project(self, mock_del):
        mock_del.return_value = {"ok": True}
        result = server.tracker_delete_project("p1")
        self.assertTrue(result["ok"])
        mock_del.assert_called_once_with("/v1/projects/p1")

    @patch.object(server, "_tracker_get")
    def test_tracker_list_releases_no_filter(self, mock_get):
        mock_get.return_value = {"ok": True, "releases": []}
        server.tracker_list_releases()
        mock_get.assert_called_once_with("/v1/releases")

    @patch.object(server, "_tracker_get")
    def test_tracker_list_releases_with_project(self, mock_get):
        mock_get.return_value = {"ok": True, "releases": []}
        server.tracker_list_releases(project_id="p1")
        mock_get.assert_called_once_with("/v1/releases?project_id=p1")

    @patch.object(server, "_tracker_post")
    def test_tracker_create_release_with_project(self, mock_post):
        mock_post.return_value = {"ok": True, "release": {"id": "r1"}}
        server.tracker_create_release("0.38", project_id="p1")
        mock_post.assert_called_once_with(
            "/v1/releases", {"name": "0.38", "project_id": "p1"}
        )

    @patch.object(server, "_tracker_put")
    def test_tracker_update_release_name_only(self, mock_put):
        mock_put.return_value = {"ok": True, "release": {"id": "r1"}}
        server.tracker_update_release("r1", name="0.39")
        mock_put.assert_called_once_with("/v1/releases/r1", {"name": "0.39"})

    @patch.object(server, "_tracker_delete")
    def test_tracker_delete_release(self, mock_del):
        mock_del.return_value = {"ok": True}
        server.tracker_delete_release("r1")
        mock_del.assert_called_once_with("/v1/releases/r1")

    @patch.object(server, "_tracker_post")
    def test_tracker_create_task_minimal(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1", "title": "Fix bug"}}
        result = server.tracker_create_task("Fix bug")
        self.assertTrue(result["ok"])
        args = mock_post.call_args
        self.assertEqual(args[0][0], "/v1/tasks")
        self.assertEqual(args[0][1]["title"], "Fix bug")
        self.assertEqual(args[0][1]["status"], "open")
        self.assertEqual(args[0][1]["priority"], 0)

    @patch.object(server, "_tracker_post")
    def test_tracker_create_task_full(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(
            "Add OAuth",
            description="Details",
            project_id="p1",
            release_id="r1",
            workstream_id="",
            status="closed",
            priority=2,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["description"], "Details")
        self.assertEqual(payload["project_id"], "p1")
        self.assertEqual(payload["status"], "closed")
        self.assertEqual(payload["priority"], 2)

    @patch.object(server, "_tracker_get")
    def test_tracker_get_task(self, mock_get):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "title": "T"}}
        result = server.tracker_get_task("t1")
        self.assertTrue(result["ok"])
        mock_get.assert_called_once_with("/v1/tasks/t1")

    @patch.object(server, "_tracker_get")
    def test_tracker_list_tasks_no_filters(self, mock_get):
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0}
        server.tracker_list_tasks()
        mock_get.assert_called_once_with("/v1/tasks")

    @patch.object(server, "_tracker_get")
    def test_tracker_list_tasks_with_status(self, mock_get):
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0}
        server.tracker_list_tasks(status="open", project_id="p1")
        called = mock_get.call_args[0][0]
        self.assertIn("status=open", called)
        self.assertIn("project_id=p1", called)

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    def test_tracker_update_task_closes_it(self, mock_get, mock_put):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "workstream_id": ""}}
        mock_put.return_value = {"ok": True, "task": {"id": "t1", "status": "closed"}}
        result = server.tracker_update_task("t1", status="closed")
        self.assertTrue(result["ok"])
        payload = mock_put.call_args[0][1]
        self.assertEqual(payload["status"], "closed")

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    def test_tracker_update_task_null_release(self, mock_get, mock_put):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "workstream_id": ""}}
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_update_task("t1", release_id="null")
        payload = mock_put.call_args[0][1]
        self.assertIsNone(payload["release_id"])

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    def test_tracker_update_task_priority_sentinel_omitted(self, mock_get, mock_put):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "workstream_id": ""}}
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_update_task("t1", status="closed")
        payload = mock_put.call_args[0][1]
        self.assertNotIn("priority", payload)

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    def test_tracker_update_task_sets_priority(self, mock_get, mock_put):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "workstream_id": ""}}
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_update_task("t1", priority=-2)
        payload = mock_put.call_args[0][1]
        self.assertEqual(payload["priority"], -2)

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    def test_tracker_update_task_priority_zero_is_a_real_value(self, mock_get, mock_put):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "workstream_id": ""}}
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_update_task("t1", priority=0)
        payload = mock_put.call_args[0][1]
        self.assertEqual(payload["priority"], 0)

    @patch.object(server, "_tracker_get")
    def test_tracker_list_tasks_with_sort_priority(self, mock_get):
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0}
        server.tracker_list_tasks(sort="priority", order="desc")
        called = mock_get.call_args[0][0]
        self.assertIn("sort=priority", called)
        self.assertIn("order=desc", called)

    @patch.object(server, "_tracker_delete")
    @patch.object(server, "_tracker_get")
    def test_tracker_delete_task(self, mock_get, mock_del):
        mock_get.return_value = {"ok": True, "task": {"id": "t1", "workstream_id": ""}}
        mock_del.return_value = {"ok": True}
        result = server.tracker_delete_task("t1")
        self.assertTrue(result["ok"])
        mock_del.assert_called_once_with("/v1/tasks/t1")

    @patch.object(server, "_tracker_get")
    def test_tracker_search_tasks(self, mock_get):
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0, "query": "oauth"}
        server.tracker_search_tasks("oauth")
        called = mock_get.call_args[0][0]
        self.assertIn("/v1/search/tasks", called)
        self.assertIn("oauth", called)

    @patch.object(server, "_tracker_get")
    def test_tracker_get_task_workspace_scoping(self, mock_get):
        """tracker_get_task must enforce workstream scope after fetching the task."""
        # The task is linked to a workstream outside the caller's scope.
        mock_get.return_value = {
            "ok": True,
            "task": {"id": "t1", "title": "T", "workstream_id": "ws-other"},
        }
        server._set_scopes(["read"], label="test")
        # Simulate the workstream being outside scope by patching the check.
        with patch.object(server, "_require_workstream_in_scope",
                          side_effect=PermissionError("out of scope")) as mock_check:
            with self.assertRaises(PermissionError):
                server.tracker_get_task("t1")
            mock_check.assert_called_once_with("ws-other")

    @patch.object(server, "_tracker_get")
    def test_tracker_get_task_no_workstream_no_scope_check(self, mock_get):
        """tracker_get_task must not call scope check when task has no workstream."""
        mock_get.return_value = {
            "ok": True,
            "task": {"id": "t1", "title": "T", "workstream_id": None},
        }
        with patch.object(server, "_require_workstream_in_scope") as mock_check:
            result = server.tracker_get_task("t1")
            mock_check.assert_not_called()
        self.assertTrue(result["ok"])

    @patch.object(server, "_tracker_get")
    def test_tracker_list_tasks_headlines(self, mock_get):
        """tracker_list_tasks must pass fields=headlines to the API."""
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0}
        server.tracker_list_tasks(fields="headlines")
        called = mock_get.call_args[0][0]
        self.assertIn("fields=headlines", called)

    @patch.object(server, "_tracker_get")
    def test_tracker_list_tasks_full_omits_fields_param(self, mock_get):
        """tracker_list_tasks must not append fields=full to the URL (it's the default)."""
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0}
        server.tracker_list_tasks(fields="full")
        called = mock_get.call_args[0][0]
        self.assertNotIn("fields=", called)

    @patch.object(server, "_tracker_get")
    def test_tracker_search_tasks_headlines(self, mock_get):
        """tracker_search_tasks must pass fields=headlines to the API."""
        mock_get.return_value = {"ok": True, "tasks": [], "total": 0, "query": "q"}
        server.tracker_search_tasks("q", fields="headlines")
        called = mock_get.call_args[0][0]
        self.assertIn("fields=headlines", called)

    @patch.object(server, "_tracker_get")
    def test_tracker_project_summary(self, mock_get):
        """tracker_project_summary fetches the summary endpoint and returns it."""
        mock_get.return_value = {
            "ok": True,
            "summary": {
                "project_id": "p1",
                "total_tasks": 5,
                "by_status": {"open": 3, "closed": 2},
                "by_priority": {0: 5},
                "by_release": [],
                "by_workstream": [{"workstream_id": None, "task_count": 5, "open_count": 3}],
            },
        }
        result = server.tracker_project_summary("p1")
        mock_get.assert_called_once_with("/v1/projects/p1/summary")
        self.assertTrue(result["ok"])
        self.assertEqual(result["summary"]["total_tasks"], 5)

    @patch.object(server, "_tracker_get")
    def test_tracker_project_summary_filters_workstreams_by_scope(self, mock_get):
        """by_workstream entries outside caller scope must be silently dropped."""
        mock_get.return_value = {
            "ok": True,
            "summary": {
                "project_id": "p1",
                "total_tasks": 2,
                "by_status": {"open": 2},
                "by_priority": {},
                "by_release": [],
                "by_workstream": [
                    {"workstream_id": "ws-good", "task_count": 1, "open_count": 1},
                    {"workstream_id": "ws-bad", "task_count": 1, "open_count": 0},
                    {"workstream_id": None, "task_count": 0, "open_count": 0},
                ],
            },
        }

        def _scope_check(ws_id):
            if ws_id == "ws-bad":
                raise PermissionError("out of scope")

        with patch.object(server, "_require_workstream_in_scope",
                          side_effect=_scope_check):
            result = server.tracker_project_summary("p1")

        by_ws = result["summary"]["by_workstream"]
        ws_ids = [e["workstream_id"] for e in by_ws]
        self.assertIn("ws-good", ws_ids)
        self.assertNotIn("ws-bad", ws_ids)
        self.assertIn(None, ws_ids)

    def test_tracker_tools_require_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.tracker_list_projects()
        with self.assertRaises(PermissionError):
            server.tracker_get_task("t1")
        with self.assertRaises(PermissionError):
            server.tracker_list_tasks()
        with self.assertRaises(PermissionError):
            server.tracker_search_tasks("q")
        with self.assertRaises(PermissionError):
            server.tracker_project_summary("p1")

    def test_tracker_tools_require_write_scope(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server.tracker_create_project("P")
        with self.assertRaises(PermissionError):
            server.tracker_update_project("p1", "New")
        with self.assertRaises(PermissionError):
            server.tracker_delete_project("p1")
        with self.assertRaises(PermissionError):
            server.tracker_create_release("r")
        with self.assertRaises(PermissionError):
            server.tracker_create_task("t")


class TestTrackerScopedFiltering(unittest.TestCase):
    """Scoped callers (agents) must only see tasks attached to a
    workstream in their workspace. tracker_list_tasks (without an
    explicit workstream_id filter) and tracker_search_tasks have to
    post-filter results, since the underlying tracker has no notion of
    workspace and would otherwise return tasks from other workspaces.
    """

    def setUp(self):
        _grant_all_scopes()
        _set_workspaces("TAAA")
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def _seed_workstream_map(self, mock_controller_get):
        mock_controller_get.return_value = [
            {"workstreamId": "ws-good", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-bad", "slackWorkspaceId": "TBBB"},
        ]

    @patch.object(server, "_controller_get")
    @patch.object(server, "_tracker_get")
    def test_list_tasks_filters_when_workstream_id_omitted(
            self, mock_tracker, mock_controller):
        self._seed_workstream_map(mock_controller)
        mock_tracker.return_value = {
            "ok": True,
            "tasks": [
                {"id": "t1", "workstream_id": "ws-good"},
                {"id": "t2", "workstream_id": "ws-bad"},
                {"id": "t3", "workstream_id": None},
            ],
        }
        result = server.tracker_list_tasks()
        self.assertTrue(result["ok"])
        ids = [t["id"] for t in result["tasks"]]
        self.assertEqual(["t1"], ids)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_tracker_get")
    def test_list_tasks_does_not_filter_when_workstream_id_specified(
            self, mock_tracker, mock_controller):
        # When the caller passes workstream_id, _require_workstream_in_scope
        # already gates the call. We must not double-filter the results
        # (which would be wasteful and could mask tracker bugs).
        self._seed_workstream_map(mock_controller)
        mock_tracker.return_value = {
            "ok": True,
            "tasks": [
                {"id": "t1", "workstream_id": "ws-good"},
                {"id": "t2", "workstream_id": "ws-good"},
            ],
        }
        result = server.tracker_list_tasks(workstream_id="ws-good")
        self.assertEqual(["t1", "t2"], [t["id"] for t in result["tasks"]])

    @patch.object(server, "_controller_get")
    @patch.object(server, "_tracker_get")
    def test_search_tasks_filters_results(
            self, mock_tracker, mock_controller):
        self._seed_workstream_map(mock_controller)
        mock_tracker.return_value = {
            "ok": True,
            "tasks": [
                {"id": "t1", "workstream_id": "ws-good"},
                {"id": "t2", "workstream_id": "ws-bad"},
                {"id": "t3", "workstream_id": None},
            ],
        }
        result = server.tracker_search_tasks("oauth")
        self.assertTrue(result["ok"])
        ids = [t["id"] for t in result["tasks"]]
        self.assertEqual(["t1"], ids)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_tracker_get")
    def test_get_task_denies_unattached_for_scoped_caller(
            self, mock_tracker, mock_controller):
        self._seed_workstream_map(mock_controller)
        mock_tracker.return_value = {
            "ok": True,
            "task": {"id": "t1", "workstream_id": None},
        }
        with self.assertRaises(PermissionError):
            server.tracker_get_task("t1")


# -----------------------------------------------------------------------
# Workspace secrets tools
# -----------------------------------------------------------------------


class TestWorkspaceSecretListNames(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_list_names_success(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"names": ["aws-prod", "github-deploy-key"]}
        result = server.workspace_secret_list_names("ws-abc")
        self.assertTrue(result["ok"])
        self.assertEqual(result["names"], ["aws-prod", "github-deploy-key"])
        # Verify that the call included a workstream_id query parameter
        called_path = mock_get.call_args[0][0]
        self.assertIn("workstream_id=ws-abc", called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_list_names_empty_workspace(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"names": []}
        result = server.workspace_secret_list_names("ws-abc")
        self.assertTrue(result["ok"])
        self.assertEqual(result["names"], [])

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_controller_error_propagated(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"ok": False, "error": "workspace not found"}
        result = server.workspace_secret_list_names("ws-abc")
        self.assertFalse(result["ok"])
        self.assertIn("workspace not found", result["error"])

    @patch.object(server, "SHARED_SECRET", "")
    @patch.object(server, "_require_workstream_in_scope")
    def test_returns_error_when_no_shared_secret(self, mock_scope):
        mock_scope.return_value = None
        result = server.workspace_secret_list_names("ws-abc")
        self.assertFalse(result["ok"])
        self.assertIn("Shared secret not configured", result["error"])

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.workspace_secret_list_names("ws-abc")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_requires_workstream_in_scope(self):
        """Scoped tokens must not access out-of-scope workstreams."""
        _set_workspaces("TAAA")
        _reset_workspace_cache()
        try:
            with patch.object(
                server,
                "_require_workstream_in_scope",
                side_effect=PermissionError("out of scope"),
            ):
                with self.assertRaises(PermissionError):
                    server.workspace_secret_list_names("ws-other")
        finally:
            _clear_workspaces()
            _reset_workspace_cache()


class TestWorkspaceSecretRenderFile(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    def _make_payload_resp(self):
        return {
            "name": "aws-prod",
            "workspace_id": "T0123456789",
            "payload": {
                "access_key_id": "AKIATEST",
                "secret_access_key": "SECRET123",
                "region": "us-east-1",
            },
        }

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_render_success(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        template = "[default]\naws_access_key_id = {{access_key_id}}\n" \
                   "aws_secret_access_key = {{secret_access_key}}\nregion = {{region}}\n"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "credentials")
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
                mode="0600",
            )
            self.assertTrue(result["ok"])
            self.assertEqual(result["output_path"], output)
            # File must exist and contain rendered values
            self.assertTrue(os.path.exists(output))
            with open(output) as fh:
                content = fh.read()
            self.assertIn("AKIATEST", content)
            self.assertIn("SECRET123", content)
            # File must not be in the returned dict's values
            self.assertNotIn("AKIATEST", str(result))
            self.assertNotIn("SECRET123", str(result))

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_output_file_permissions(self, mock_scope, mock_get):
        import tempfile, os, stat
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        template = "{{access_key_id}}"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "creds")
            server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
                mode="0600",
            )
            mode = os.stat(output).st_mode
            self.assertEqual(stat.S_IMODE(mode), 0o600)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_missing_placeholder_returns_error_no_file(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        template = "[default]\ntoken = {{missing_key}}\n"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "creds")
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
            )
            self.assertFalse(result["ok"])
            self.assertIn("missing_key", result["error"])
            # No file written
            self.assertFalse(os.path.exists(output))

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_extra_payload_keys_silently_ignored(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        # Template uses only one of the three keys
        template = "key={{access_key_id}}"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "creds")
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
            )
            self.assertTrue(result["ok"])

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_tilde_expansion(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        # We can't use ~ to write to actual home in tests; verify expansion
        # by patching os.path.expanduser instead.
        with tempfile.TemporaryDirectory() as tmpdir:
            expected_path = os.path.join(tmpdir, ".aws", "credentials")
            with patch("os.path.expanduser", return_value=expected_path):
                result = server.workspace_secret_render_file(
                    workstream_id="ws-abc",
                    secret_name="aws-prod",
                    template="key={{access_key_id}}",
                    output_path="~/.aws/credentials",
                )
            self.assertTrue(result["ok"])
            self.assertEqual(result["output_path"], expected_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_controller_error_propagated(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"ok": False, "error": "secret not found"}
        result = server.workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="no-such-secret",
            template="{{foo}}",
            output_path="/tmp/out",
        )
        self.assertFalse(result["ok"])
        self.assertIn("secret not found", result["error"])

    @patch.object(server, "SHARED_SECRET", "")
    @patch.object(server, "_require_workstream_in_scope")
    def test_returns_error_when_no_shared_secret(self, mock_scope):
        mock_scope.return_value = None
        result = server.workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="aws-prod",
            template="{{foo}}",
            output_path="/tmp/out",
        )
        self.assertFalse(result["ok"])
        self.assertIn("Shared secret not configured", result["error"])

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template="",
                output_path="/tmp/out",
            )

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_requires_workstream_in_scope(self):
        """Scoped tokens must not access secrets from out-of-scope workstreams."""
        with patch.object(
            server,
            "_require_workstream_in_scope",
            side_effect=PermissionError("out of scope"),
        ):
            with self.assertRaises(PermissionError):
                server.workspace_secret_render_file(
                    workstream_id="ws-other",
                    secret_name="aws-prod",
                    template="",
                    output_path="/tmp/out",
                )


if __name__ == "__main__":
    unittest.main()
