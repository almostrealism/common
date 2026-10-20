"""Tests for the per-job sensitive-file protection flag on
``workstream_submit_task``.

The flag is operator-only: an in-flight coding agent with a workstream-bound
HMAC token must never be allowed to opt out, because doing so would cause
the controller to compute a controller-signed bypass HMAC for the new
job, letting the resulting commit modify normally-protected files on the
target workstream. Unscoped operator callers retain the legitimate
opt-out path that triggers the bypass HMAC computation.

These tests live in ``tests/`` so the post-completion smoke filter
``pytest tests/ -k "sensitive or bypass or submit"`` actually exercises
the new behaviour rather than deselecting everything.
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


class TestSubmitSensitiveFileProtectionWireFormat(unittest.TestCase):
    """Wire-format contract for ``sensitive_file_protection_enabled``:

    the field is enabled by default and the wire payload only carries an
    explicit opt-out (``sensitiveFileProtectionEnabled=False``). The
    default and an explicit True are both omitted so existing callers
    see no behaviour change.
    """

    @patch.object(server, "_controller_post")
    def test_submit_sensitive_file_protection_default_omitted(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-sfp-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("sensitiveFileProtectionEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_sensitive_file_protection_explicit_true_omitted(
            self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-sfp-true"}
        server.workstream_submit_task(
            prompt="Task", sensitive_file_protection_enabled=True)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("sensitiveFileProtectionEnabled", payload)

    @patch.object(server, "_controller_post")
    def test_submit_sensitive_file_protection_disabled_forwarded(
            self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-sfp-false"}
        server.workstream_submit_task(
            prompt="Task", sensitive_file_protection_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["sensitiveFileProtectionEnabled"], False)


class TestSubmitAgentSensitiveFileProtectionGuard(unittest.TestCase):
    """An in-flight agent with a workstream-bound HMAC token cannot opt
    out of sensitive-file protection. The rejection happens locally
    before the controller is contacted, so a controller-signed bypass
    HMAC can never be minted at the agent's request.
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
    def test_sensitive_bypass_request_from_agent_is_rejected(
            self, mock_post, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-self", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-other", "slackWorkspaceId": "TAAA"},
        ]
        result = server.workstream_submit_task(
            prompt="Delegated task",
            workstream_id="ws-other",
            sensitive_file_protection_enabled=False,
        )
        self.assertFalse(result["ok"])
        self.assertIn("sensitive_file_protection_enabled", result["error"])
        self.assertIn("operator", result["error"].lower())
        # Controller must NOT be contacted — no bypass HMAC can be minted
        # at the request of an in-flight agent.
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_sensitive_bypass_request_rejected_before_scope_resolution(
            self, mock_post, mock_get):
        """The guard runs before workspace-scope resolution, so a rejected
        request neither refreshes the workspace map from the controller nor
        surfaces a scope error in place of the operator-only one."""
        mock_get.side_effect = ConnectionError("controller unreachable")
        result = server.workstream_submit_task(
            prompt="Delegated task",
            workstream_id="ws-other",
            sensitive_file_protection_enabled=False,
        )
        self.assertFalse(result["ok"])
        self.assertIn("sensitive_file_protection_enabled=False", result["error"])
        self.assertIn("operator", result["error"].lower())
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_sensitive_bypass_rejection_is_audited(self, mock_post, mock_get):
        """A rejected attempt to disable sensitive-file protection is recorded
        in the local audit log, as it was before the guard moved ahead of
        scope resolution."""
        with self.assertLogs("ar-manager.audit", level="INFO") as audit:
            result = server.workstream_submit_task(
                prompt="Delegated task",
                workstream_id="ws-other",
                sensitive_file_protection_enabled=False,
            )
        self.assertFalse(result["ok"])
        rejected = [line for line in audit.output
                    if "tool=workstream_submit_task.rejected" in line]
        self.assertEqual(1, len(rejected), msg=audit.output)
        self.assertIn("'caller_workstream_id': 'ws-self'", rejected[0])
        self.assertIn("'workstream_id': 'ws-other'", rejected[0])
        self.assertIn(
            "'setting': 'sensitive_file_protection_enabled=False'", rejected[0])
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_sensitive_bypass_rejection_audited_before_request_validation(
            self, mock_post, mock_get):
        """The guard runs before the rest of request validation, so an
        attempt with no workstream_id is still rejected as operator-only and
        recorded, rather than failing the self-submission check unaudited."""
        with self.assertLogs("ar-manager.audit", level="INFO") as audit:
            result = server.workstream_submit_task(
                prompt="Delegated task",
                sensitive_file_protection_enabled=False,
            )
        self.assertFalse(result["ok"])
        self.assertIn("sensitive_file_protection_enabled=False", result["error"])
        rejected = [line for line in audit.output
                    if "tool=workstream_submit_task.rejected" in line]
        self.assertEqual(1, len(rejected), msg=audit.output)
        self.assertIn("'workstream_id': ''", rejected[0])
        mock_get.assert_not_called()
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_sensitive_bypass_agent_default_passes_through(
            self, mock_post, mock_get):
        """Leaving the flag at its default (True) is harmless: the
        controller sees no field, computes no bypass, and applies the
        standard protections on the new job."""
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
        self.assertNotIn("sensitiveFileProtectionEnabled", payload)


class TestSubmitUnscopedOperatorCanBypass(unittest.TestCase):
    """Unscoped operator callers (no workstream-bound token) retain
    the legitimate opt-out path that triggers the controller-side
    bypass HMAC computation. The operator-only restriction is enforced
    by the presence of a workstream binding on the token, not by the
    opt-out itself.
    """

    @patch.object(server, "_controller_post")
    def test_sensitive_bypass_unscoped_operator_is_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-op-bypass"}
        result = server.workstream_submit_task(
            prompt="Operator task",
            sensitive_file_protection_enabled=False,
        )
        self.assertTrue(result["ok"], msg=result.get("error"))
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["sensitiveFileProtectionEnabled"], False)


if __name__ == "__main__":
    unittest.main()
