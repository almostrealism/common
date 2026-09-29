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
            bypass_agent_permission_prompts=True,
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
            bypass_agent_permission_prompts=True,
        )
        self.assertFalse(result["ok"])
        self.assertIn("bypass_agent_permission_prompts", result["error"])
        self.assertIn("operator", result["error"].lower())
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
