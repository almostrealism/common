"""Tests for the per-job agent permission-prompt bypass on
``workstream_submit_task``.

The flag lets an operator grant one job's agent session the runtime's
permission-prompt bypass, so it can edit the agent tooling (``.claude/``)
that a headless session is otherwise denied. It is operator-only: an
in-flight coding agent with a workstream-bound HMAC token must never be
able to grant it to a job it delegates, because the grant lets that job
rewrite the guardrails it runs under.
"""

import os
import sys
import unittest
from unittest.mock import patch

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
    import server


def _grant_all_scopes():
    server._set_scopes(
        ["read", "write", "submit", "pipeline", "github",
         "memory-read", "memory-write"],
        label="test",
    )


class TestSubmitPermissionBypassWireFormat(unittest.TestCase):
    """The flag is off by default and only an explicit grant reaches the wire."""

    @patch.object(server, "_controller_post")
    def test_permission_bypass_default_omitted(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pb-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("bypassAgentPermissionPrompts", payload)

    @patch.object(server, "_controller_post")
    def test_permission_bypass_granted_by_operator_is_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-pb-granted"}
        result = server.workstream_submit_task(
            prompt="Register the new hooks in .claude/settings.json",
            skip_agent_permission_prompts=True,
        )
        self.assertTrue(result["ok"], msg=result.get("error"))
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["bypassAgentPermissionPrompts"], True)


class TestSubmitAgentPermissionBypassGuard(unittest.TestCase):
    """An in-flight agent cannot grant the bypass to a job it delegates.

    The rejection happens locally, before the controller is contacted.
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
    def test_permission_bypass_request_from_agent_is_rejected(
            self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Delegated task",
            workstream_id="ws-other",
            skip_agent_permission_prompts=True,
        )
        self.assertFalse(result["ok"])
        self.assertIn("skip_agent_permission_prompts", result["error"])
        self.assertIn("operator", result["error"].lower())
        mock_post.assert_not_called()
        # Workspace-scope resolution refreshes the cleared workspace map
        # from the controller, so the guard must run before it.
        mock_get.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_permission_bypass_request_from_agent_rejected_when_controller_unreachable(
            self, mock_post, mock_get):
        """The operator-only rejection does not depend on the controller:
        with the workspace map unavailable the agent still gets the
        operator-only error, not a scope failure."""
        mock_get.side_effect = ConnectionError("controller unreachable")
        result = server.workstream_submit_task(
            prompt="Delegated task",
            workstream_id="ws-other",
            skip_agent_permission_prompts=True,
        )
        self.assertFalse(result["ok"])
        self.assertIn("skip_agent_permission_prompts=True", result["error"])
        self.assertIn("ws-self", result["error"])
        self.assertEqual(
            "Leave skip_agent_permission_prompts at its default (False) and re-submit",
            result["next_steps"][0])
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_permission_bypass_rejection_is_audited(self, mock_post, mock_get):
        """A rejected escalation attempt still leaves a local audit record
        naming the caller, the target and the setting, without contacting
        the controller."""
        with self.assertLogs("ar-manager.audit", level="INFO") as audit:
            result = server.workstream_submit_task(
                prompt="Delegated task",
                workstream_id="ws-other",
                skip_agent_permission_prompts=True,
            )
        self.assertFalse(result["ok"])
        rejected = [line for line in audit.output
                    if "tool=workstream_submit_task.rejected" in line]
        self.assertEqual(1, len(rejected), msg=audit.output)
        self.assertIn("'caller_workstream_id': 'ws-self'", rejected[0])
        self.assertIn("'workstream_id': 'ws-other'", rejected[0])
        self.assertIn("'setting': 'skip_agent_permission_prompts=True'", rejected[0])
        self.assertFalse(
            any("tool=workstream_submit_task " in line for line in audit.output),
            msg=f"rejected call must not be audited as a submission: {audit.output}")
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    def _assert_rejection_audited_before_validation(self, **kwargs):
        """Submits with the bypass requested and asserts the operator-only
        rejection, not the other validation error, is what the agent gets,
        and that the attempt is audited."""
        with self.assertLogs("ar-manager.audit", level="INFO") as audit:
            result = server.workstream_submit_task(
                skip_agent_permission_prompts=True, **kwargs)
        self.assertFalse(result["ok"])
        self.assertIn("skip_agent_permission_prompts=True", result["error"])
        self.assertIn("operator-only", result["error"])
        rejected = [line for line in audit.output
                    if "tool=workstream_submit_task.rejected" in line]
        self.assertEqual(1, len(rejected), msg=audit.output)
        self.assertIn("'setting': 'skip_agent_permission_prompts=True'", rejected[0])
        return rejected[0]

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_permission_bypass_rejection_audited_without_workstream_id(
            self, mock_post, mock_get):
        """Omitting workstream_id must not let the attempt be rejected by the
        self-submission check without an audit record."""
        rejected = self._assert_rejection_audited_before_validation(
            prompt="Delegated task")
        self.assertIn("'workstream_id': ''", rejected)
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_permission_bypass_rejection_audited_with_empty_prompt(
            self, mock_post, mock_get):
        """An otherwise-invalid request (no prompt) is still recorded as an
        escalation attempt rather than silently failing validation."""
        self._assert_rejection_audited_before_validation(
            prompt="", workstream_id="ws-other")
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_permission_bypass_agent_default_passes_through(
            self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        mock_post.return_value = {
            "ok": True, "jobId": "job-1", "workstreamId": "ws-other"}
        result = server.workstream_submit_task(
            prompt="Delegated task", workstream_id="ws-other")
        self.assertTrue(result["ok"], msg=result.get("error"))
        payload = mock_post.call_args[0][1]
        self.assertNotIn("bypassAgentPermissionPrompts", payload)


if __name__ == "__main__":
    unittest.main()
