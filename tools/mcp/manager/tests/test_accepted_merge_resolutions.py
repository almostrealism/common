"""Tests for the operator-only ``accepted_merge_resolutions`` option on
``workstream_submit_task``.

The option names paths whose merge resolution the owner has reviewed and
accepts, so the harness commits the agent's resolution of them instead of
failing the merge. Accepting a resolution the guardrails rejected is the
owner's decision: an in-flight coding agent with a workstream-bound HMAC
token must never be able to request it, since the controller would sign the
acceptance on its behalf.
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


class TestAcceptedMergeResolutionsWireFormat(unittest.TestCase):
    """An operator's accepted paths reach the controller as a JSON list."""

    @patch.object(server, "_controller_post")
    def test_accepted_merge_resolutions_default_omitted(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-amr-default"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("acceptedMergeResolutions", payload)

    @patch.object(server, "_controller_post")
    def test_accepted_merge_resolutions_forwarded_as_list(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-amr"}
        result = server.workstream_submit_task(
            prompt="Task",
            accepted_merge_resolutions=" src/A.java, ,docs/b.md ")
        self.assertTrue(result["ok"], msg=result.get("error"))
        payload = mock_post.call_args[0][1]
        self.assertEqual(["src/A.java", "docs/b.md"],
                         payload["acceptedMergeResolutions"])


class TestAcceptedMergeResolutionsAgentGuard(unittest.TestCase):
    """An in-flight agent cannot request an accepted merge resolution, and
    the rejection happens before the controller is contacted."""

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
    def test_accepted_merge_resolutions_from_agent_is_rejected(
            self, mock_post, mock_get):
        result = server.workstream_submit_task(
            prompt="Delegated task",
            workstream_id="ws-other",
            accepted_merge_resolutions="src/A.java",
        )
        self.assertFalse(result["ok"])
        self.assertIn("accepted_merge_resolutions", result["error"])
        self.assertIn("operator", result["error"].lower())
        mock_get.assert_not_called()
        mock_post.assert_not_called()


if __name__ == "__main__":
    unittest.main()
