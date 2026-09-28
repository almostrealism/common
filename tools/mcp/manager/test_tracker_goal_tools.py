"""Tests for the tracker tools used by planning and goal-decomposition agents.

Agents cannot use the general tracker write tools. Three narrow tools let the
two agent roles of goal-driven release automation do their jobs, and each
enforces its limits itself:

* ``tracker_claim_next_task`` — for a workstream holding the ``planner``
  capability; claims for the calling workstream only.
* ``tracker_list_release_tasks`` and ``tracker_upsert_goal_task`` — for a
  workstream holding ``steward``; the upsert only ever changes tasks derived
  from goal documents, and never a task a person wrote.

The capability is read from the controller's workstream list, so these tests
drive it the same way: a mocked ``/api/workstreams`` response and a job token
bound to a workstream.
"""

import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import server, _grant_all_scopes  # noqa: E402

_RELEASE = {"id": "r1", "name": "Framework 1.2", "project_id": "p1",
            "project_name": "Framework"}


def _as_job(workstream_id, capabilities):
    """Bind the request to a job on *workstream_id* holding *capabilities*,
    and return the controller workstream list that grants them."""
    server._set_token_context(workstream_id=workstream_id, job_id="job-1")
    server._tracker_capability_cache["map"] = None
    server._tracker_capability_cache["fetched"] = 0.0
    entry = {"workstreamId": workstream_id}
    if capabilities:
        entry["trackerCapabilities"] = list(capabilities)
    return [entry]


class _GoalToolTestBase(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._tracker_capability_cache["map"] = None


class TestCapabilityGate(_GoalToolTestBase):
    """Each tool refuses a caller that does not hold its capability."""

    @patch.object(server, "_controller_get")
    def test_a_caller_without_a_workstream_is_refused(self, mock_get):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        mock_get.return_value = []
        with self.assertRaises(PermissionError):
            server.tracker_claim_next_task("Framework", "Framework 1.2")

    @patch.object(server, "_controller_get")
    def test_a_workstream_without_the_capability_is_refused(self, mock_get):
        mock_get.return_value = _as_job("ws-1", ["steward"])
        with self.assertRaises(PermissionError):
            server.tracker_claim_next_task("Framework", "Framework 1.2")
        mock_get.return_value = _as_job("ws-1", ["planner"])
        with self.assertRaises(PermissionError):
            server.tracker_upsert_goal_task("Framework", "Framework 1.2", "t", "goals:x")
        with self.assertRaises(PermissionError):
            server.tracker_list_release_tasks("Framework", "Framework 1.2")

    @patch.object(server, "_controller_get")
    def test_an_unreachable_controller_grants_nothing(self, mock_get):
        server._set_token_context(workstream_id="ws-1", job_id="job-1")
        server._tracker_capability_cache["map"] = None
        mock_get.side_effect = OSError("down")
        with self.assertRaises(PermissionError):
            server.tracker_claim_next_task("Framework", "Framework 1.2")

    @patch.object(server, "_controller_get")
    def test_the_denial_names_the_string_argument_form(self, mock_get):
        # The message must show tracker_capabilities as the comma-separated
        # string the MCP tool declares, not a list literal.
        mock_get.return_value = _as_job("ws-1", ["steward"])
        with self.assertRaises(PermissionError) as caught:
            server.tracker_claim_next_task("Framework", "Framework 1.2")
        self.assertIn('tracker_capabilities="', str(caught.exception))


class TestClaimNextTask(_GoalToolTestBase):

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_a_planner_claims_for_its_own_workstream(self, mock_get, mock_post):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        result = server.tracker_claim_next_task("Framework", "Framework 1.2")
        self.assertEqual("t1", result["task"]["id"])
        mock_post.assert_called_once_with("/v1/claim", {
            "project": "Framework", "release": "Framework 1.2",
            "workstream_id": "ws-plan"})


class TestListReleaseTasks(_GoalToolTestBase):

    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_lists_every_task_of_the_release(self, mock_get, mock_tracker):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker.side_effect = [
            {"ok": True, "release": _RELEASE},
            {"ok": True, "tasks": [{"id": "t1", "workstream_id": None}], "total": 1},
        ]
        result = server.tracker_list_release_tasks("Framework", "Framework 1.2")
        # Unlike tracker_list_tasks, an unlinked task is not filtered out.
        self.assertEqual(["t1"], [t["id"] for t in result["tasks"]])
        self.assertEqual("r1", result["release"]["id"])
        self.assertIn("release_id=r1", mock_tracker.call_args_list[1][0][0])

    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_release_that_does_not_exist_is_empty(self, mock_get, mock_tracker):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker.return_value = {"ok": False, "error": "Release not found"}
        result = server.tracker_list_release_tasks("Framework", "Framework 9.9")
        self.assertTrue(result["ok"])
        self.assertEqual([], result["tasks"])
        self.assertIsNone(result["release"])

    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_tracker_error_is_not_reported_as_an_empty_release(self, mock_get, mock_tracker):
        # An outage or auth failure must surface, not read as "no tasks", which
        # would let the steward decompose from a false empty view.
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker.return_value = {"ok": False, "error": "Tracker unreachable: down"}
        result = server.tracker_list_release_tasks("Framework", "Framework 1.2")
        self.assertFalse(result["ok"])
        self.assertEqual("Tracker unreachable: down", result["error"])


class TestUpsertGoalTask(_GoalToolTestBase):

    @patch.object(server, "_controller_get")
    def test_the_source_must_name_a_goal_document(self, mock_get):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        result = server.tracker_upsert_goal_task(
            "Framework", "Framework 1.2", "t", "person")
        self.assertFalse(result["ok"])

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_new_task_is_created_ready(self, mock_get, mock_tracker_get, mock_post):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker_get.return_value = {"ok": True, "release": _RELEASE}
        mock_post.return_value = {"ok": True, "task": {"id": "new"}}
        server.tracker_upsert_goal_task(
            "Framework", "Framework 1.2", "Add a thing", "goals:docs/PLAN.md",
            description="why", priority=1, blocked_by="a, b")
        mock_post.assert_called_once_with("/v1/tasks", {
            "title": "Add a thing", "description": "why", "priority": 1,
            "project_id": "p1", "release_id": "r1", "blocked_by": ["a", "b"],
            "source": "goals:docs/PLAN.md", "stage": "ready", "status": "open"})

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_missing_release_is_created_in_the_project(
            self, mock_get, mock_tracker_get, mock_post):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker_get.side_effect = [
            {"ok": False, "error": "Release not found"},
            {"ok": True, "projects": [{"id": "p1", "name": "Framework"}]},
        ]
        mock_post.side_effect = [
            {"ok": True, "release": {"id": "r2", "name": "Framework 1.3", "project_id": "p1"}},
            {"ok": True, "task": {"id": "new"}},
        ]
        server.tracker_upsert_goal_task(
            "Framework", "Framework 1.3", "t", "goals:docs/PLAN.md")
        self.assertEqual(
            ("/v1/releases", {"name": "Framework 1.3", "project_id": "p1"}),
            mock_post.call_args_list[0][0])
        self.assertEqual("r2", mock_post.call_args_list[1][0][1]["release_id"])

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_project_is_never_created(self, mock_get, mock_tracker_get, mock_post):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker_get.side_effect = [
            {"ok": False, "error": "Release not found"},
            {"ok": True, "projects": []},
        ]
        result = server.tracker_upsert_goal_task(
            "Nowhere", "Nowhere 1.0", "t", "goals:docs/PLAN.md")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_tracker_outage_does_not_create_a_release(
            self, mock_get, mock_tracker_get, mock_post):
        # A transient failure must not be mistaken for "release not found" and
        # trigger a create that duplicates the release once the tracker recovers.
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker_get.return_value = {"ok": False, "error": "Tracker unreachable: down"}
        result = server.tracker_upsert_goal_task(
            "Framework", "Framework 1.3", "t", "goals:docs/PLAN.md")
        self.assertFalse(result["ok"])
        self.assertEqual("Tracker unreachable: down", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_task_a_person_wrote_is_never_changed(
            self, mock_get, mock_tracker_get, mock_put):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker_get.side_effect = [
            {"ok": True, "release": _RELEASE},
            {"ok": True, "task": {"id": "t1", "source": "person"}},
        ]
        result = server.tracker_upsert_goal_task(
            "Framework", "Framework 1.2", "t", "goals:docs/PLAN.md", task_id="t1")
        self.assertFalse(result["ok"])
        mock_put.assert_not_called()

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_updating_a_goal_task_leaves_its_stage_and_status_alone(
            self, mock_get, mock_tracker_get, mock_put):
        mock_get.return_value = _as_job("ws-steward", ["steward"])
        mock_tracker_get.side_effect = [
            {"ok": True, "release": _RELEASE},
            {"ok": True, "task": {"id": "t1", "source": "goals:docs/PLAN.md"}},
        ]
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_upsert_goal_task(
            "Framework", "Framework 1.2", "renamed", "goals:docs/PLAN.md", task_id="t1")
        path, payload = mock_put.call_args[0]
        self.assertEqual("/v1/tasks/t1", path)
        self.assertEqual("renamed", payload["title"])
        self.assertNotIn("stage", payload)
        self.assertNotIn("status", payload)


class TestWorkstreamTrackerCapabilities(_GoalToolTestBase):
    """Operators grant the roles through workstream registration and update."""

    def setUp(self):
        super().setUp()
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)

    @patch.object(server, "_controller_post")
    @patch.object(server, "_controller_get")
    def test_register_forwards_the_roles(self, mock_get, mock_post):
        mock_get.return_value = []
        mock_post.return_value = {"ok": True, "workstreamId": "ws-new"}
        server.workstream_register(default_branch="feature/x",
                                   tracker_capabilities="planner, steward")
        payload = mock_post.call_args[0][1]
        self.assertEqual(["planner", "steward"], payload["trackerCapabilities"])

    @patch.object(server, "_controller_post")
    @patch.object(server, "_controller_get")
    def test_register_rejects_an_unknown_role(self, mock_get, mock_post):
        mock_get.return_value = []
        result = server.workstream_register(default_branch="feature/x",
                                            tracker_capabilities="admin")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_controller_get")
    def test_register_without_roles_sends_none(self, mock_get, mock_post):
        mock_get.return_value = []
        mock_post.return_value = {"ok": True, "workstreamId": "ws-new"}
        server.workstream_register(default_branch="feature/x")
        self.assertNotIn("trackerCapabilities", mock_post.call_args[0][1])

    @patch.object(server, "_controller_post")
    @patch.object(server, "_controller_get")
    def test_update_distinguishes_revoke_from_no_change(self, mock_get, mock_post):
        mock_get.return_value = [{"workstreamId": "ws-1"}]
        mock_post.return_value = {"ok": True}
        server.workstream_update_config("ws-1", tracker_capabilities="")
        self.assertEqual([], mock_post.call_args[0][1]["trackerCapabilities"])
        server.workstream_update_config("ws-1", planning_document="docs/x.md")
        self.assertNotIn("trackerCapabilities", mock_post.call_args[0][1])


if __name__ == "__main__":
    unittest.main()
