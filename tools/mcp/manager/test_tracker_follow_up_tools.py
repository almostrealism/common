"""Tests for tracker_file_follow_up_task and the follow-up read-back path.

A coding agent files a follow-up task for a valid review point out of scope for
its branch. The tool derives the task's provenance from the job's own token,
checks the caller-supplied field lengths, and forwards to the tracker, which
holds the backlog/unlinked/cap invariants. A scoped caller can then read back
the unlinked follow-up tasks its own workspace filed, recognised by the
workstream embedded in that provenance source.
"""

import os
import sys
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


def _follow_up_task(workstream="ws-1", job="job-1", task_id="t1"):
    """A stored follow-up task dict as the tracker returns it: no workstream
    link, provenance naming the filing workstream and job."""
    return {"id": task_id, "title": "t", "workstream_id": None,
            "source": f"follow-up:{workstream}/{job}"}


class _FollowUpToolTestBase(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        _clear_workspaces()
        _reset_workspace_cache()


class TestProvenanceFromToken(_FollowUpToolTestBase):
    """The task's source is the job's token, never caller input."""

    @patch.object(server, "_tracker_post")
    def test_source_is_derived_from_the_calling_jobs_token(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_file_follow_up_task(project_id="p1", title="cover edge case")
        payload = mock_post.call_args.args[1]
        self.assertEqual("/v1/follow-up-tasks", mock_post.call_args.args[0])
        self.assertEqual("follow-up:ws-1/job-1", payload["source"])

    @patch.object(server, "_tracker_post")
    def test_pr_and_comment_are_recorded_in_the_description(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_file_follow_up_task(
            project_id="p1", title="t", description="the body",
            pr_number=42, review_comment="https://example/pr/42#c7")
        description = mock_post.call_args.args[1]["description"]
        self.assertIn("the body", description)
        self.assertIn("#42", description)
        self.assertIn("https://example/pr/42#c7", description)

    @patch.object(server, "_tracker_post")
    def test_a_release_id_is_forwarded_when_given(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_file_follow_up_task(project_id="p1", title="t", release_id="r1")
        self.assertEqual("r1", mock_post.call_args.args[1]["release_id"])


class TestTokenBindingRequired(_FollowUpToolTestBase):
    """Without a workstream/job binding the tool refuses, since it cannot
    record provenance."""

    @patch.object(server, "_tracker_post")
    def test_a_call_without_a_token_binding_is_refused(self, mock_post):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        result = server.tracker_file_follow_up_task(project_id="p1", title="t")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()


class TestLengthChecks(_FollowUpToolTestBase):
    """Over-long caller fields are refused before the task is filed."""

    @patch.object(server, "_tracker_post")
    def test_an_over_long_title_is_refused(self, mock_post):
        result = server.tracker_file_follow_up_task(project_id="p1", title="x" * 501)
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_an_over_long_description_is_refused(self, mock_post):
        result = server.tracker_file_follow_up_task(
            project_id="p1", title="t", description="x" * 10_001)
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_a_blank_title_is_refused(self, mock_post):
        result = server.tracker_file_follow_up_task(project_id="p1", title="   ")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()


class TestScopedReadBack(_FollowUpToolTestBase):
    """A scoped caller reads back the follow-up tasks its own workspace filed,
    and nothing else that is unlinked."""

    def _scoped_to_w1(self, mock_get):
        # ws-1 lives in workspace W1 (the caller's), ws-2 in W2 (another).
        mock_get.return_value = [
            {"workstreamId": "ws-1", "workspaceId": "W1"},
            {"workstreamId": "ws-2", "workspaceId": "W2"},
        ]
        _reset_workspace_cache()
        _set_workspaces("W1")

    @patch.object(server, "_controller_get")
    def test_own_follow_up_task_is_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        self.assertTrue(
            server._follow_up_task_workspace_allowed(_follow_up_task(workstream="ws-1")))

    @patch.object(server, "_controller_get")
    def test_another_workspaces_follow_up_task_is_not_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        self.assertFalse(
            server._follow_up_task_workspace_allowed(_follow_up_task(workstream="ws-2")))

    @patch.object(server, "_controller_get")
    def test_filter_keeps_own_follow_up_and_drops_others(self, mock_get):
        self._scoped_to_w1(mock_get)
        tasks = [
            _follow_up_task(workstream="ws-1", task_id="mine"),
            _follow_up_task(workstream="ws-2", task_id="other-ws"),
            {"id": "plain", "workstream_id": None, "source": "person"},
            {"id": "linked", "workstream_id": "ws-1", "source": "person"},
        ]
        kept = {t["id"] for t in server._filter_tasks_by_scope(tasks)}
        self.assertEqual({"mine", "linked"}, kept)

    @patch.object(server, "_controller_get")
    def test_get_task_returns_own_follow_up_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget:
            mock_tget.return_value = {"ok": True, "task": _follow_up_task(workstream="ws-1")}
            result = server.tracker_get_task("t1")
        self.assertTrue(result["ok"])

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
                "ok": True, "task": _follow_up_task(workstream="ws-2")}
            with self.assertRaises(PermissionError):
                server.tracker_get_task("t1")


if __name__ == "__main__":
    unittest.main()
