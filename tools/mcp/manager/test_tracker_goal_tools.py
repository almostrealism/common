"""Tests for the tracker permissions of agent jobs.

Every agent sees the same tracker tools; what a call may do depends on the
calling job's workstream:

* A workstream holding the ``planner`` tracker role may create tasks with every
  field set (a goal-derived task, ready to be claimed), edit any task in its
  workspace, and create releases.
* Any other workstream may edit only the tasks linked to it — the task its
  branch was given — and may not move them out of its hands.
* No agent may delete, or reorganise projects and releases; those are left to
  people even when a harness offers the tools.

The role is read from the controller's workstream list, so these tests drive
it the same way: a mocked ``/api/workstreams`` response and a job token bound
to a workstream.
"""

import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _set_workspaces, _clear_workspaces,
)


def _as_job(workstream_id, capabilities):
    """Bind the request to a job on *workstream_id* holding *capabilities*,
    and return the controller workstream list that grants them."""
    server._set_token_context(workstream_id=workstream_id, job_id="job-1")
    server._tracker_capability_cache["map"] = None
    server._tracker_capability_cache["fetched"] = 0.0
    entry = {"workstreamId": workstream_id, "workspaceId": "W1"}
    if capabilities:
        entry["trackerCapabilities"] = list(capabilities)
    return [entry]


def _task(task_id="t1", workstream_id=None, workspace_id="W1", source="person"):
    """A stored task as the tracker returns it."""
    return {"id": task_id, "title": "T", "workstream_id": workstream_id,
            "workspace_id": workspace_id, "source": source}


def _tracker_records(task=None, projects=None):
    """A ``_tracker_get`` stand-in serving one task and some projects by id."""
    projects = projects or {}

    def _get(path):
        if path.startswith("/v1/tasks/") and task is not None:
            return {"ok": True, "task": task}
        if path.startswith("/v1/projects/"):
            project_id = path.rsplit("/", 1)[1]
            if project_id in projects:
                return {"ok": True, "project": {"id": project_id,
                                                "workspace_id": projects[project_id]}}
            return {"ok": False, "error": "Project not found"}
        return {"ok": False, "error": "unexpected " + path}
    return _get


class _GoalToolTestBase(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._tracker_capability_cache["map"] = None
        _clear_workspaces()


class TestCapabilityGate(_GoalToolTestBase):
    """Creating a release needs the planner role; a person needs no role."""

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_a_person_needs_no_role(self, mock_get, mock_post):
        # A caller with no job binding is a person, not a job lacking a role.
        server._set_token_context(workstream_id=None, job_id=None)
        server._request_workstream_id.set(None)
        mock_get.return_value = []
        mock_post.return_value = {"ok": True, "release": {"id": "r1"}}
        self.assertTrue(server.tracker_create_release("Framework 1.2", project_id="p1")["ok"])

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_a_workstream_without_the_capability_is_refused(self, mock_get, mock_post):
        mock_get.return_value = _as_job("ws-1", [])
        with self.assertRaises(PermissionError):
            server.tracker_create_release("Framework 1.2", project_id="p1")
        mock_post.assert_not_called()
        mock_get.return_value = _as_job("ws-1", ["planner"])
        mock_post.return_value = {"ok": True, "release": {"id": "r1"}}
        self.assertTrue(server.tracker_create_release("Framework 1.2", project_id="p1")["ok"])

    @patch.object(server, "_controller_get")
    def test_an_unreachable_controller_grants_nothing(self, mock_get):
        server._set_token_context(workstream_id="ws-1", job_id="job-1")
        server._tracker_capability_cache["map"] = None
        mock_get.side_effect = OSError("down")
        with self.assertRaises(PermissionError):
            server.tracker_create_release("Framework 1.2", project_id="p1")

    @patch.object(server, "_controller_get")
    def test_the_denial_names_the_string_argument_form(self, mock_get):
        # The message must show tracker_capabilities as the comma-separated
        # string the MCP tool declares, not a list literal.
        mock_get.return_value = _as_job("ws-1", [])
        with self.assertRaises(PermissionError) as caught:
            server.tracker_create_release("Framework 1.2", project_id="p1")
        self.assertIn('tracker_capabilities="', str(caught.exception))

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_a_retired_steward_grant_reads_as_planner(self, mock_get, mock_post):
        mock_get.return_value = _as_job("ws-1", ["steward"])
        mock_post.return_value = {"ok": True, "release": {"id": "r1"}}
        self.assertTrue(server.tracker_create_release("Framework 1.2", project_id="p1")["ok"])


class TestPlannerCreatesTasks(_GoalToolTestBase):
    """A planner sets every field of a new task."""

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_a_goal_task_is_created_ready_with_its_blockers(self, mock_get, mock_post):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(
            title="Add X", description="why", project_id="p1", release_id="r1",
            priority=1, stage="ready", source=" goals:org/app/docs/GOALS.md ",
            blocked_by="t7, t8,")
        mock_post.assert_called_once_with("/v1/tasks", {
            "title": "Add X", "status": "open", "priority": 1, "description": "why",
            "project_id": "p1", "release_id": "r1", "stage": "ready",
            "source": "goals:org/app/docs/GOALS.md", "blocked_by": ["t7", "t8"]})

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_without_a_source_the_job_is_the_provenance(self, mock_get, mock_post):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        mock_post.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_create_task(title="t", project_id="p1", stage="ready")
        self.assertEqual("follow-up:ws-plan/job-1", mock_post.call_args.args[1]["source"])

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_controller_get")
    def test_an_agent_cannot_claim_a_person_wrote_the_task(self, mock_get, mock_post):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        result = server.tracker_create_task(title="t", project_id="p1", source="person")
        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_tracker_post")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_project_of_another_workspace_is_refused(self, mock_get, mock_tget, mock_post):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        _set_workspaces("W1")
        mock_tget.side_effect = _tracker_records(projects={"p2": "W2"})
        with self.assertRaises(PermissionError):
            server.tracker_create_task(title="t", project_id="p2", stage="ready")
        mock_post.assert_not_called()


class TestPlannerEditsTasks(_GoalToolTestBase):
    """A planner may edit any task of its workspace, and nothing outside it."""

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_planner_edits_a_task_it_does_not_hold(self, mock_get, mock_tget, mock_put):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        _set_workspaces("W1")
        mock_tget.side_effect = _tracker_records(task=_task(workstream_id="ws-other"))
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_update_task("t1", description="clearer", blocked_by="null")
        mock_put.assert_called_once_with("/v1/tasks/t1", {
            "description": "clearer", "blocked_by": []})

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_task_of_another_workspace_is_refused(self, mock_get, mock_tget, mock_put):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        _set_workspaces("W1")
        mock_tget.side_effect = _tracker_records(task=_task(workspace_id="W2"))
        with self.assertRaises(PermissionError):
            server.tracker_update_task("t1", title="x")
        mock_put.assert_not_called()

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_a_task_cannot_be_moved_to_another_workspace(self, mock_get, mock_tget, mock_put):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        _set_workspaces("W1")
        mock_tget.side_effect = _tracker_records(task=_task(), projects={"p2": "W2"})
        with self.assertRaises(PermissionError):
            server.tracker_update_task("t1", project_id="p2")
        result = server.tracker_update_task("t1", project_id="null")
        self.assertFalse(result["ok"])
        mock_put.assert_not_called()


class TestHolderEditsItsTask(_GoalToolTestBase):
    """Without the role, an agent edits only the tasks linked to its workstream."""

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_the_holder_edits_its_own_task(self, mock_get, mock_tget, mock_put):
        mock_get.return_value = _as_job("ws-1", [])
        mock_tget.side_effect = _tracker_records(task=_task(workstream_id="ws-1"))
        mock_put.return_value = {"ok": True, "task": {"id": "t1"}}
        server.tracker_update_task("t1", description="what the plan found", priority=0)
        mock_put.assert_called_once_with("/v1/tasks/t1", {
            "description": "what the plan found", "priority": 0})

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_another_workstreams_task_is_refused(self, mock_get, mock_tget, mock_put):
        mock_get.return_value = _as_job("ws-1", [])
        mock_tget.side_effect = _tracker_records(task=_task(workstream_id="ws-2"))
        with self.assertRaises(PermissionError) as caught:
            server.tracker_update_task("t1", title="x")
        self.assertIn("tracker_create_task", str(caught.exception))
        mock_put.assert_not_called()

    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_the_holder_cannot_let_go_of_or_re_attribute_its_task(
            self, mock_get, mock_tget, mock_put):
        mock_get.return_value = _as_job("ws-1", [])
        mock_tget.side_effect = _tracker_records(task=_task(workstream_id="ws-1"))
        for field, value in (("workstream_id", "null"), ("project_id", "p2"),
                             ("source", "goals:x.md")):
            with self.subTest(field=field):
                result = server.tracker_update_task("t1", **{field: value})
                self.assertFalse(result["ok"])
                self.assertIn(field, result["error"])
        mock_put.assert_not_called()


class TestPeopleOnlyTools(_GoalToolTestBase):
    """Deleting and reorganising are refused to every agent job, planner or not."""

    @patch.object(server, "_tracker_delete")
    @patch.object(server, "_tracker_put")
    @patch.object(server, "_tracker_post")
    @patch.object(server, "_tracker_get")
    @patch.object(server, "_controller_get")
    def test_each_people_only_tool_refuses_a_job(
            self, mock_get, mock_tget, mock_post, mock_put, mock_delete):
        mock_get.return_value = _as_job("ws-plan", ["planner"])
        mock_tget.side_effect = _tracker_records(task=_task(workstream_id="ws-plan"))
        for call in (lambda: server.tracker_delete_task("t1"),
                     lambda: server.tracker_create_project("P", "W1"),
                     lambda: server.tracker_update_project("p1", name="Q"),
                     lambda: server.tracker_delete_project("p1"),
                     lambda: server.tracker_update_release("r1", name="R"),
                     lambda: server.tracker_delete_release("r1")):
            with self.assertRaises(PermissionError):
                call()
        mock_post.assert_not_called()
        mock_put.assert_not_called()
        mock_delete.assert_not_called()


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
        # The retired "steward" role is read as "planner", which replaced it.
        self.assertEqual(["planner"], payload["trackerCapabilities"])

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
