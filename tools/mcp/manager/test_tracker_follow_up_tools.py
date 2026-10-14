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

    @patch.object(server, "_tracker_post")
    def test_an_over_long_review_comment_is_refused(self, mock_post):
        result = server.tracker_file_follow_up_task(
            project_id="p1", title="t", review_comment="x" * 2_001)
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    def test_a_blank_project_id_is_refused(self, mock_post):
        result = server.tracker_file_follow_up_task(project_id="  ", title="t")
        self.assertEqual({"ok": False, "error": "project_id is required"}, result)
        mock_post.assert_not_called()


class TestDescriptionComposition(_FollowUpToolTestBase):
    """The description carries the agent's text and the review provenance,
    each omitted when absent."""

    @patch.object(server, "_tracker_post")
    def test_no_description_and_no_provenance_sends_null(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_file_follow_up_task(project_id="p1", title="t", description="  ")
        self.assertIsNone(mock_post.call_args.args[1]["description"])

    @patch.object(server, "_tracker_post")
    def test_provenance_alone_forms_the_description(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_file_follow_up_task(project_id="p1", title="t", pr_number=7)
        self.assertEqual("Raised by a review comment during a coding job.\nPR: #7",
                         mock_post.call_args.args[1]["description"])

    @patch.object(server, "_tracker_post")
    def test_the_agents_description_leads_the_provenance(self, mock_post):
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_file_follow_up_task(
            project_id="p1", title="  t  ", description=" body ", review_comment="c9")
        payload = mock_post.call_args.args[1]
        self.assertEqual("t", payload["title"])
        self.assertEqual("body\n\nRaised by a review comment during a coding job.\n"
                         "Review comment: c9", payload["description"])
        self.assertNotIn("release_id", payload)


class TestTrackerErrors(_FollowUpToolTestBase):
    """Tracker refusals are surfaced to the agent."""

    @patch.object(server, "_tracker_post")
    def test_an_unknown_project_names_the_project(self, mock_post):
        mock_post.return_value = {"ok": False, "error": "Project not found"}
        result = server.tracker_file_follow_up_task(project_id="p-missing", title="t")
        self.assertEqual(
            {"ok": False, "error": "Tracker project 'p-missing' does not exist"}, result)

    @patch.object(server, "_tracker_post")
    def test_the_cap_refusal_is_passed_through(self, mock_post):
        refusal = {"ok": False, "error": "This job has already filed its maximum of 5 "
                                         "follow-up tasks", "count": 5, "cap": 5}
        mock_post.return_value = refusal
        result = server.tracker_file_follow_up_task(project_id="p1", title="t")
        self.assertEqual(refusal, result)


class TestScopedReadBack(_FollowUpToolTestBase):
    """A scoped caller reads back the follow-up tasks its own workspace filed,
    and nothing else that is unlinked."""

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
    def test_malformed_or_non_follow_up_sources_are_not_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        for task in (
            {"id": "t", "source": "follow-up:/job-1"},
            {"id": "t", "source": "follow-up:"},
            {"id": "t", "source": "goals:ws-1/job-1"},
            {"id": "t", "source": "Follow-up:ws-1/job-1"},
            {"id": "t", "source": None},
            {"id": "t", "source": 42},
            {"id": "t"},
            "follow-up:ws-1/job-1",
            None,
        ):
            self.assertFalse(server._follow_up_task_workspace_allowed(task), task)

    @patch.object(server, "_controller_get")
    def test_unknown_workstream_in_source_is_not_visible(self, mock_get):
        self._scoped_to_w1(mock_get)
        self.assertFalse(
            server._follow_up_task_workspace_allowed(_follow_up_task(workstream="ws-unknown")))

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


class TestScopedWriteDeny(_FollowUpToolTestBase):
    """A scoped caller may read back its own follow-up tasks but must never
    modify or delete an unlinked task — including its own follow-up, so it can
    neither promote one to a claimable stage nor discard a recorded point."""

    @patch.object(server, "_controller_get")
    def test_update_task_denies_own_unlinked_follow_up(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_put") as mock_tput:
            mock_tget.return_value = {"ok": True, "task": _follow_up_task(workstream="ws-1")}
            with self.assertRaises(PermissionError):
                server.tracker_update_task("t1", stage="ready")
            mock_tput.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_update_task_denies_an_unrelated_unlinked_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_put") as mock_tput:
            mock_tget.return_value = {
                "ok": True, "task": {"id": "t1", "workstream_id": None, "source": "person"}}
            with self.assertRaises(PermissionError):
                server.tracker_update_task("t1", stage="ready")
            mock_tput.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_delete_task_denies_own_unlinked_follow_up(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_delete") as mock_tdel:
            mock_tget.return_value = {"ok": True, "task": _follow_up_task(workstream="ws-1")}
            with self.assertRaises(PermissionError):
                server.tracker_delete_task("t1")
            mock_tdel.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_delete_task_denies_an_unrelated_unlinked_task(self, mock_get):
        self._scoped_to_w1(mock_get)
        with patch.object(server, "_tracker_get") as mock_tget, \
                patch.object(server, "_tracker_delete") as mock_tdel:
            mock_tget.return_value = {
                "ok": True, "task": {"id": "t1", "workstream_id": None, "source": "person"}}
            with self.assertRaises(PermissionError):
                server.tracker_delete_task("t1")
            mock_tdel.assert_not_called()


if __name__ == "__main__":
    unittest.main()
