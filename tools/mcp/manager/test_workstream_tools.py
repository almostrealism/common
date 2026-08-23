"""Tests for the workstream tools, excluding task submission.

Split from ``test_server.py``, which had grown past the file-length cap. The
tests are unchanged; shared fixtures live in ``manager_test_support``.
"""

import json
import os
import subprocess
import sys
import unittest
from unittest import mock
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from inference import Synthesis  # noqa: E402
from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _grant_scopes, _clear_scopes,
    _set_workspaces, _clear_workspaces, _reset_workspace_cache,
)


class TestWorkstreamListFilters(unittest.TestCase):
    """Server-side filtering on workstream_list.

    The filters exist so "which workstreams match P?" is one call. Answering it
    by listing everything and scanning client-side was the operator's original
    blocker, and each entry is expensive enough that the scan is not free.
    """

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_controller_get", return_value=[])
    def test_no_filters_sends_no_query(self, mock_get):
        server.workstream_list()
        self.assertEqual(mock_get.call_args[0][0], "/api/workstreams")

    @patch.object(server, "_controller_get", return_value=[])
    def test_filters_reach_the_controller(self, mock_get):
        server.workstream_list(
            workspace_id="ws-a", repo_url="https://github.com/org/repo",
            dispatch_capable=True)
        path = mock_get.call_args[0][0]
        self.assertIn("workspaceId=ws-a", path)
        self.assertIn("repoUrl=", path)
        self.assertIn("dispatchCapable=true", path)

    @patch.object(server, "_controller_get", return_value=[])
    def test_dispatch_capable_false_is_sent_not_dropped(self, mock_get):
        # False is a filter, not an absent value; treating it as absent would
        # silently return dispatch-capable workstreams too.
        server.workstream_list(dispatch_capable=False)
        self.assertIn("dispatchCapable=false", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get", return_value=[])
    def test_archived_selector_is_sent(self, mock_get):
        server.workstream_list(archived=True)
        self.assertIn("archived=true", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get", return_value=[])
    def test_include_archived_still_works(self, mock_get):
        server.workstream_list(include_archived=True)
        self.assertIn("includeArchived=true", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get", return_value=[])
    def test_status_enrichment_is_off_by_default(self, mock_get):
        # The enrichment costs a job-history read per workstream returned, so
        # the default listing must not pay for it.
        server.workstream_list()
        path = mock_get.call_args[0][0]
        self.assertNotIn("includeStatus", path)
        self.assertNotIn("includePullRequest", path)

    @patch.object(server, "_controller_get", return_value=[])
    def test_status_enrichment_reaches_the_controller(self, mock_get):
        server.workstream_list(include_status=True, include_pull_request=True)
        path = mock_get.call_args[0][0]
        self.assertIn("includeStatus=true", path)
        self.assertIn("includePullRequest=true", path)

    @patch.object(server, "_controller_get", return_value=[])
    def test_enrichment_composes_with_filters(self, mock_get):
        server.workstream_list(workspace_id="ws-a", include_status=True)
        path = mock_get.call_args[0][0]
        self.assertIn("workspaceId=ws-a", path)
        self.assertIn("includeStatus=true", path)

    @patch.object(server, "_controller_get", return_value=[])
    def test_scope_filtering_still_applies(self, mock_get):
        # The server-side filters narrow further; they do not replace the
        # token-scope filter, which is a security boundary rather than a
        # convenience.
        with patch.object(server, "_filter_workstreams_by_scope",
                          return_value=[]) as mock_scope:
            server.workstream_list(workspace_id="ws-a")
            mock_scope.assert_called_once()


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
    def test_plan_path_becomes_the_planning_document(self, mock_post):
        """A caller who names the file the plan job will write has said where
        the planning document lives. Not recording it left project_read_plan
        failing against a document that existed, until a second
        workstream_update_config call was made to say so."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-plan"}
        server.workstream_register(
            default_branch="feature/x",
            plan_path="docs/plans/X.md",
            plan_instructions="Write the plan",
        )
        payload = mock_post.call_args_list[0][0][1]
        self.assertEqual(payload["planningDocument"], "docs/plans/X.md")

    @patch.object(server, "_controller_post")
    def test_explicit_planning_document_wins_over_plan_path(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-both"}
        server.workstream_register(
            default_branch="feature/x",
            planning_document="docs/plans/EXPLICIT.md",
            plan_path="docs/plans/X.md",
            plan_instructions="Write the plan",
        )
        payload = mock_post.call_args_list[0][0][1]
        self.assertEqual(payload["planningDocument"], "docs/plans/EXPLICIT.md")

    @patch.object(server, "_controller_post")
    def test_no_planning_document_when_neither_is_given(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "workstreamId": "ws-none"}
        server.workstream_register(default_branch="feature/x")
        payload = mock_post.call_args_list[0][0][1]
        self.assertNotIn("planningDocument", payload)

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

class TestWorkstreamContextMemoryOptOut(unittest.TestCase):
    """Opting out of the memory payload, and the two names callers reach for.

    The memories dominate this response, so "what PR is on this branch?"
    should not have to carry tens of KB of agent prose to get an answer.
    """

    def setUp(self):
        _grant_all_scopes()
        server.repo_config._cache = {}
        server.repo_config._cache_expires = float("inf")

    def tearDown(self):
        server.repo_config._cache = None
        server.repo_config._cache_expires = 0.0

    @patch.object(server, "_github_request", return_value=[])
    @patch.object(server, "_get_memory_client")
    def test_include_memories_false_skips_the_search(self, mock_client, _):
        client = MagicMock()
        mock_client.return_value = client

        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            include_memories=False, include_commits=False)

        # Not fetched-and-discarded — not fetched.
        client.search_by_branch.assert_not_called()
        self.assertEqual(result["memories"], [])

    @patch.object(server, "_github_request", return_value=[])
    @patch.object(server, "_get_memory_client")
    def test_memories_are_returned_by_default(self, mock_client, _):
        client = MagicMock()
        client.search_by_branch.return_value = [
            {"id": "m1", "content": "a note", "created_at": "2026-08-01"},
        ]
        mock_client.return_value = client

        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            include_commits=False)

        client.search_by_branch.assert_called()
        self.assertEqual(len(result["memories"]), 1)

    def test_max_memories_is_rejected_with_the_real_name(self):
        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            max_memories=1)
        self.assertFalse(result["ok"])
        self.assertIn("use limit", result["error"])

    def test_max_activities_is_rejected_with_the_real_name(self):
        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            max_activities="primary")
        self.assertFalse(result["ok"])
        self.assertIn("use include_activities", result["error"])

    def test_falsey_misuse_is_still_rejected(self):
        """A truthiness guard would wave through exactly the mistaken calls
        these parameters exist to intercept — max_memories=0 reads as "give me
        no memories", which is a caller trying to use the parameter."""
        zero = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            max_memories=0)
        self.assertFalse(zero["ok"])
        self.assertIn("use limit", zero["error"])

        empty = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            max_activities="")
        self.assertFalse(empty["ok"])
        self.assertIn("use include_activities", empty["error"])

    @patch.object(server, "_github_request", return_value=[])
    @patch.object(server, "_get_memory_client")
    def test_the_rejected_names_are_inert_when_unused(self, mock_client, _):
        # Their sentinel defaults must not look like a caller supplying them.
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client.return_value = client

        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x",
            include_commits=False)

        self.assertTrue(result["ok"])

class TestWorkstreamArchiveMany(unittest.TestCase):
    """Batch archive/unarchive.

    Archiving six workstreams took six calls. Only the reversible operations
    are batched — deletion stays one at a time on purpose.
    """

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_require_workstream_in_scope")
    @patch.object(server, "_controller_post")
    def test_archives_each_id(self, mock_post, _):
        mock_post.return_value = {"ok": True, "archivedAt": "2026-08-21"}
        result = server.workstream_archive_many(workstream_ids=["ws-a", "ws-b"])
        self.assertTrue(result["ok"])
        self.assertEqual(result["succeeded"], 2)
        self.assertEqual(result["failed"], 0)
        self.assertEqual([r["workstream_id"] for r in result["results"]],
                         ["ws-a", "ws-b"])

    @patch.object(server, "_require_workstream_in_scope")
    @patch.object(server, "_controller_post")
    def test_one_failure_does_not_decide_the_batch(self, mock_post, _):
        # The realistic case: a workstream with a job still running refuses,
        # and the operator still wants the other five archived.
        mock_post.side_effect = [
            {"ok": True, "archivedAt": "2026-08-21"},
            {"ok": False, "error": "active jobs: job-1"},
            {"ok": True, "archivedAt": "2026-08-21"},
        ]
        result = server.workstream_archive_many(
            workstream_ids="ws-a,ws-b,ws-c")
        self.assertTrue(result["ok"])
        self.assertEqual(result["succeeded"], 2)
        self.assertEqual(result["failed"], 1)
        self.assertIn("active jobs", result["results"][1]["error"])

    @patch.object(server, "_require_workstream_in_scope")
    @patch.object(server, "_controller_post")
    def test_accepts_a_json_array_string(self, mock_post, _):
        mock_post.return_value = {"ok": True}
        result = server.workstream_archive_many(
            workstream_ids='["ws-a","ws-b"]')
        self.assertEqual(result["succeeded"], 2)

    @patch.object(server, "_require_workstream_in_scope")
    @patch.object(server, "_controller_post")
    def test_repeated_ids_are_processed_once(self, mock_post, _):
        mock_post.return_value = {"ok": True}
        result = server.workstream_archive_many(
            workstream_ids="ws-a,ws-a,ws-b")
        self.assertEqual(len(result["results"]), 2)
        self.assertEqual(mock_post.call_count, 2)

    def test_empty_input_is_rejected(self):
        result = server.workstream_archive_many(workstream_ids="")
        self.assertFalse(result["ok"])
        self.assertIn("No workstream ids", result["error"])

    @patch.object(server, "_require_workstream_in_scope")
    @patch.object(server, "_controller_post")
    def test_unarchive_many_uses_the_unarchive_path(self, mock_post, _):
        mock_post.return_value = {"ok": True}
        server.workstream_unarchive_many(workstream_ids=["ws-a"])
        called = mock_post.call_args[0][0]
        self.assertIn("/unarchive", called)

    @patch.object(server, "_require_workstream_in_scope")
    @patch.object(server, "_controller_post")
    def test_slack_flag_reaches_the_archive_call(self, mock_post, _):
        mock_post.return_value = {"ok": True}
        server.workstream_archive_many(
            workstream_ids=["ws-a"], archive_slack_channel=False)
        self.assertFalse(mock_post.call_args[0][1]["archiveSlackChannel"])

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
    def test_scoped_requires_workspace_id(self, mock_post):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.workstream_register(default_branch="feature/x")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_scoped_rejects_out_of_scope_workspace(self, mock_post):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.workstream_register(
                default_branch="feature/x", workspace_id="TBBB")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_scoped_passes_workspace_to_controller(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "w-1"}
        _set_workspaces("TAAA")
        server.workstream_register(
            default_branch="feature/x", workspace_id="TAAA")
        args, _ = mock_post.call_args
        self.assertEqual("/api/workstreams", args[0])
        self.assertEqual("TAAA", args[1]["workspaceId"])

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
        self.assertEqual("almostrealism", args[1]["workspaceId"])
        self.assertNotIn("slackWorkspaceId", args[1],
                         "the wire payload carries one name for one concept")

    @patch.object(server, "_controller_post")
    def test_slack_workspace_id_is_rejected(self, mock_post):
        """The alias is refused rather than forwarded.

        Workspace identity is the operator's, not Slack's — Slack is an
        optional integration a workspace may have. Accepting the name would
        keep teaching callers otherwise and leave two permanent names for one
        concept, so the caller is told the real one instead.
        """
        _set_workspaces("TAAA")
        result = server.workstream_register(
            default_branch="feature/x", slack_workspace_id="TAAA")
        self.assertFalse(result["ok"])
        self.assertIn("use workspace_id", result["error"])
        mock_post.assert_not_called()

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
