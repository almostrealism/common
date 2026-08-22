"""Tests for the task-tracker tools.

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
