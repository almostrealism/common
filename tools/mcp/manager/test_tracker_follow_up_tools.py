"""Tests for filing a task as an agent without the planner role.

Anyone may create a task. An agent job whose workstream does not hold the
``planner`` tracker role files it with ``tracker_create_task`` into the
project's backlog: the tool derives the task's provenance from the job's own
token, checks the caller-supplied field lengths, refuses the fields only a
planner may set, and forwards to the tracker's follow-up endpoint, which holds
the backlog/unlinked/cap invariants. The agent may read the task back like any
other task of its workspace, and may not edit or delete it.
"""

import os
import sys
import time
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server,
    _grant_all_scopes,
    _set_workspaces,
    _clear_workspaces,
    _reset_workspace_cache,
)


def _follow_up_task(workstream="ws-1", job="job-1", task_id="t1", workspace="W1"):
    """A stored follow-up task dict as the tracker returns it: no workstream
    link, provenance naming the filing workstream and job, and the workspace of
    its project."""
    return {"id": task_id, "title": "t", "workstream_id": None,
            "source": f"follow-up:{workstream}/{job}", "workspace_id": workspace}


class _FollowUpToolTestBase(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")
        # ws-1 holds no tracker role.
        server._tracker_capability_cache["map"] = {}
        server._tracker_capability_cache["fetched"] = time.monotonic()

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._tracker_capability_cache["map"] = None
        _clear_workspaces()
        _reset_workspace_cache()

    def _scoped_to_w1(self, mock_get):
        # ws-1 lives in workspace W1 (the caller's), ws-2 in W2 (another).
        mock_get.return_value = [
            {"workstreamId": "ws-1", "workspaceId": "W1"},
            {"workstreamId": "ws-2", "workspaceId": "W2"},
        ]
        _reset_workspace_cache()
        _set_workspaces("W1")


class TestProvenanceFromToken(_FollowUpToolTestBase):
    """The task's source is the job's token, never caller input."""

    @patch.object(server, "_tracker_post")
    def test_source_is_derived_from_the_calling_jobs_token(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(project_id="p1", title="cover edge case")
        payload = mock_post.call_args.args[1]
        self.assertEqual("/v1/follow-up-tasks", mock_post.call_args.args[0])
        self.assertEqual("follow-up:ws-1/job-1", payload["source"])

    @patch.object(server, "_tracker_post")
    def test_pr_and_comment_are_recorded_in_the_description(self, mock_post):
        # The agent records where the idea came from in its own description;
        # it reaches the tracker as written.
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(
            project_id="p1", title="t",
            description="the body\n\nPR: #42\nReview comment: https://example/pr/42#c7")
        description = mock_post.call_args.args[1]["description"]
        self.assertIn("the body", description)
        self.assertIn("#42", description)
        self.assertIn("https://example/pr/42#c7", description)

    @patch.object(server, "_tracker_post")
    def test_a_release_id_is_forwarded_when_given(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(project_id="p1", title="t", release_id="r1")
        self.assertEqual("r1", mock_post.call_args.args[1]["release_id"])


class TestTokenBindingRequired(_FollowUpToolTestBase):
    """A caller with no job binding is a person, whose task is recorded as
    theirs; it never takes a job's provenance."""

    @patch.object(server, "_tracker_post")
    def test_a_person_files_a_task_as_their_own(self, mock_post):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        result = server.tracker_create_task(project_id="p1", title="t")
        self.assertTrue(result["ok"])
        self.assertEqual("/v1/tasks", mock_post.call_args.args[0])
        self.assertNotIn("source", mock_post.call_args.args[1])

    @patch.object(server, "_tracker_post")
    def test_a_job_binding_without_a_job_id_files_nothing(self, mock_post):
        server._set_token_context(workstream_id="ws-1", job_id=None)
        server._request_job_id.set(None)
        result = server.tracker_create_task(project_id="p1", title="t")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()


class TestLengthChecks(_FollowUpToolTestBase):
    """Over-long caller fields are refused before the task is filed."""

    @patch.object(server, "_tracker_post")
    def test_an_over_long_title_is_refused(self, mock_post):
        result = server.tracker_create_task(project_id="p1", title="x" * 501)
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_an_over_long_description_is_refused(self, mock_post):
        result = server.tracker_create_task(
            project_id="p1", title="t", description="x" * 10_001)
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_a_blank_title_is_refused(self, mock_post):
        result = server.tracker_create_task(project_id="p1", title="   ")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_an_over_long_review_comment_is_refused(self, mock_post):
        # A review comment is part of the description, so it is held to the
        # description's ceiling.
        result = server.tracker_create_task(
            project_id="p1", title="t", description="Review comment: " + "x" * 10_000)
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_a_blank_project_id_is_refused(self, mock_post):
        result = server.tracker_create_task(project_id="", title="t")
        self.assertFalse(result["ok"])
        self.assertIn("project_id is required", result["error"])
        mock_post.assert_not_called()


class TestPlannerOnlyFields(_FollowUpToolTestBase):
    """Without the planner role an agent sets only the text, project and release."""

    @patch.object(server, "_tracker_post")
    def test_each_planner_only_field_is_refused(self, mock_post):
        for field, value in (("stage", "ready"), ("priority", 2), ("source", "goals:x.md"),
                             ("workstream_id", "ws-1"), ("blocked_by", "t9"),
                             ("status", "closed")):
            with self.subTest(field=field):
                result = server.tracker_create_task(project_id="p1", title="t",
                                                    **{field: value})
                self.assertFalse(result["ok"])
                self.assertIn(field, result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_no_description_sends_null(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(project_id="p1", title="  t  ")
        payload = mock_post.call_args.args[1]
        self.assertEqual("t", payload["title"])
        self.assertIsNone(payload["description"])
        self.assertNotIn("release_id", payload)


class TestTrackerErrors(_FollowUpToolTestBase):
    """Tracker refusals are surfaced to the agent."""

    @patch.object(server, "_tracker_post")
    def test_an_unknown_project_names_the_project(self, mock_post):
        mock_post.return_value = {"ok": False, "error": "Project not found"}
        result = server.tracker_create_task(project_id="p-missing", title="t")
        self.assertEqual({"ok": False, "error": "Project not found"}, result)

    @patch.object(server, "_tracker_post")
    def test_the_cap_refusal_is_passed_through(self, mock_post):
        refusal = {"ok": False, "error": "This job has already filed its maximum of 20 "
                                         "follow-up tasks", "count": 20, "cap": 20}
        mock_post.return_value = refusal
        result = server.tracker_create_task(project_id="p1", title="t")
        self.assertEqual(refusal, result)


class TestScopedReadBack(_FollowUpToolTestBase):
    """A filed task belongs to the workspace of its project, so the agent reads
    it back like any other task of its workspace — and cannot file into, or
    read, another workspace's project."""

    @patch.object(server, "_controller_get")
    def test_own_follow_up_task_is_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget:
            mock_tget.return_value = {"ok": True, "task": _follow_up_task()}
            self.assertTrue(server.tracker_get_task("t1")["ok"])

    @patch.object(server, "_controller_get")
    def test_another_workspaces_follow_up_task_is_not_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget:
            mock_tget.return_value = {
                "ok": True, "task": _follow_up_task(workstream="ws-1", workspace="W2")}
            with self.assertRaises(PermissionError):
                server.tracker_get_task("t1")

    @patch.object(server, "_controller_get")
    def test_malformed_or_non_follow_up_sources_are_not_visible(self, mock_get):
        # Visibility follows the project's workspace, never the source: a
        # source naming the caller's workstream does not make a task visible.
        self._scoped_to_w1(mock_get)
        for workspace in (None, "", "W2"):
            with self.subTest(workspace=workspace):
                with patch.object(server, "_tracker_get") as mock_tget:
                    mock_tget.return_value = {
                        "ok": True, "task": _follow_up_task(workspace=workspace)}
                    with self.assertRaises(PermissionError):
                        server.tracker_get_task("t1")

    @patch.object(server, "_controller_get")
    def test_unknown_workstream_in_source_is_not_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_post") as mock_post:
            mock_tget.return_value = {"ok": True, "project": {"id": "p2", "workspace_id": "W2"}}
            with self.assertRaises(PermissionError):
                server.tracker_create_task(project_id="p2", title="t")
            mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_filter_keeps_own_follow_up_and_drops_others(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget:
            mock_tget.return_value = {"ok": True, "tasks": [_follow_up_task()], "total": 1}
            result = server.tracker_list_tasks()
        self.assertIn("workspace_id=W1", mock_tget.call_args.args[0])
        self.assertEqual(["t1"], [t["id"] for t in result["tasks"]])

    @patch.object(server, "_controller_get")
    def test_get_task_returns_own_follow_up_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_post") as mock_post:
            mock_tget.return_value = {"ok": True, "project": {"id": "p1", "workspace_id": "W1"}}
            mock_post.return_value = {"ok": True, "task": _follow_up_task()}
            result = server.tracker_create_task(project_id="p1", title="t")
        self.assertTrue(result["ok"])
        mock_tget.assert_called_once_with("/v1/projects/p1")

    @patch.object(server, "_controller_get")
    def test_get_task_denies_an_unrelated_unlinked_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget:
            mock_tget.return_value = {
                "ok": True, "task": {"id": "t1", "workstream_id": None, "source": "person"}}
            with self.assertRaises(PermissionError):
                server.tracker_get_task("t1")

    @patch.object(server, "_controller_get")
    def test_get_task_denies_another_workspaces_follow_up(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget:
            mock_tget.return_value = {
                "ok": True, "task": _follow_up_task(workstream="ws-2", workspace="W2")}
            with self.assertRaises(PermissionError):
                server.tracker_get_task("t1")


class TestScopedWriteDeny(_FollowUpToolTestBase):
    """An agent without the planner role may read its filed tasks but never
    modify or delete one, so it can neither promote one to a claimable stage
    nor discard a recorded point."""

    @patch.object(server, "_controller_get")
    def test_update_task_denies_own_unlinked_follow_up(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_put") as mock_tput:
            mock_tget.return_value = {"ok": True, "task": _follow_up_task()}
            with self.assertRaises(PermissionError):
                server.tracker_update_task("t1", stage="ready")
            mock_tput.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_update_task_denies_an_unrelated_unlinked_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_put") as mock_tput:
            mock_tget.return_value = {
                "ok": True, "task": {"id": "t1", "workstream_id": None, "source": "person",
                                     "workspace_id": "W1"}}
            with self.assertRaises(PermissionError):
                server.tracker_update_task("t1", stage="ready")
            mock_tput.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_delete_task_denies_own_unlinked_follow_up(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_delete") as mock_tdel:
            mock_tget.return_value = {"ok": True, "task": _follow_up_task()}
            with self.assertRaises(PermissionError):
                server.tracker_delete_task("t1")
            mock_tdel.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_delete_task_denies_an_unrelated_unlinked_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_delete") as mock_tdel:
            mock_tget.return_value = {
                "ok": True, "task": {"id": "t1", "workstream_id": None, "source": "person",
                                     "workspace_id": "W1"}}
            with self.assertRaises(PermissionError):
                server.tracker_delete_task("t1")
            mock_tdel.assert_not_called()


if __name__ == "__main__":
    unittest.main()
