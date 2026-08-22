"""Tests for the messaging tools.

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


class TestSendMessageActivity(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)
        # Clear any AR_AGENT_ACTIVITY env var set by tests
        os.environ.pop("AR_AGENT_ACTIVITY", None)

    @patch.object(server, "_controller_post")
    def test_activity_passed_to_controller(self, mock_post):
        """Explicit activity parameter is forwarded in the POST body."""
        mock_post.return_value = {"ok": True}
        server.send_message(text="Hello", activity="deduplication")
        mock_post.assert_called_once()
        call_args = mock_post.call_args
        body = call_args[0][1]
        self.assertEqual(body["text"], "Hello")
        self.assertEqual(body["activity"], "deduplication")

    @patch.object(server, "_controller_post")
    def test_no_activity_omits_field(self, mock_post):
        """When no activity is given and env var is unset, body has no activity field."""
        mock_post.return_value = {"ok": True}
        os.environ.pop("AR_AGENT_ACTIVITY", None)
        server.send_message(text="Primary work")
        body = mock_post.call_args[0][1]
        self.assertNotIn("activity", body)

    @patch.object(server, "_controller_post")
    def test_env_var_fallback(self, mock_post):
        """AR_AGENT_ACTIVITY env var is used when activity param is empty."""
        mock_post.return_value = {"ok": True}
        os.environ["AR_AGENT_ACTIVITY"] = "organizational_placement"
        server.send_message(text="Audit msg")
        body = mock_post.call_args[0][1]
        self.assertEqual(body["activity"], "organizational_placement")

    @patch.object(server, "_controller_post")
    def test_explicit_activity_overrides_env_var(self, mock_post):
        """Explicit activity takes precedence over AR_AGENT_ACTIVITY env var."""
        mock_post.return_value = {"ok": True}
        os.environ["AR_AGENT_ACTIVITY"] = "organizational_placement"
        server.send_message(text="Override", activity="deduplication")
        body = mock_post.call_args[0][1]
        self.assertEqual(body["activity"], "deduplication")

class TestSendMessageWorkstreamIdOptional(unittest.TestCase):
    """Regression tests for the silent-opencode fix: ``send_message`` must
    accept a call with only ``{text, activity}`` when the in-flight request's
    HMAC temp token resolves to a workstream and job. The reported failure
    mode was the agent's very first ``send_message`` call returning
    ``"workstream_id is required ..."`` and the agent then giving up on
    operator status updates for the entire session. The fix ensures that
    token-based resolution is the default path; the explicit
    ``workstream_id`` argument remains an operator-side override.
    """

    def setUp(self):
        # Clear any leftover ContextVar / thread-local state so the
        # resolution paths are tested in isolation. The base behaviour
        # of opencode primary-phase jobs is "no prior state on the
        # server-task" since the streamable-HTTP transport is stateless.
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id

    def tearDown(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id

    @staticmethod
    def _fake_context_with_bearer(token_value):
        """Build a stand-in MCP context whose request carries ``token_value``
        as its Bearer header — the same shape as a real opencode/Claude
        Code stateless-HTTP request."""
        headers = {}
        if token_value is not None:
            headers["authorization"] = "Bearer " + token_value
        fake_request = MagicMock()
        fake_request.headers = headers
        fake_request_context = MagicMock()
        fake_request_context.request = fake_request
        fake_ctx = MagicMock()
        fake_ctx.request_context = fake_request_context
        return fake_ctx

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_resolves_workstream_from_temp_token_with_only_text(self, mock_post):
        """The headline regression: a job session's ``send_message`` call with
        only ``text`` (no workstream_id, no job_id) must succeed and post to
        the workstream/job the temp token resolves to.
        """
        _grant_all_scopes()
        token = server._mint_temp_token("ws-A", "job-7", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(text="Hello from opencode")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()
        called_path = mock_post.call_args[0][0]
        self.assertIn("/api/workstreams/ws-A/jobs/job-7/messages",
                      called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_text_and_activity_only_resolves_via_token(self, mock_post):
        """A call with ``{text, activity}`` only — the exact shape an
        opencode agent emits during its first status update — must succeed
        when the bearer is a valid temp token."""
        _grant_all_scopes()
        token = server._mint_temp_token("ws-B", "job-9", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(
                text="Starting work", activity="primary")
        self.assertTrue(result["ok"], msg=result.get("error"))
        body = mock_post.call_args[0][1]
        self.assertEqual(body["text"], "Starting work")
        self.assertEqual(body["activity"], "primary")
        called_path = mock_post.call_args[0][0]
        self.assertIn("ws-B", called_path)
        self.assertIn("job-9", called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_explicit_workstream_id_overrides_token(self, mock_post):
        """The override path: an explicit workstream_id still wins when
        supplied, even if the bearer resolves to a different workstream.
        This preserves the operator-side ability to route messages
        deliberately."""
        _grant_all_scopes()
        token = server._mint_temp_token("ws-TOKEN", "job-TOKEN", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(
                text="Routed elsewhere", workstream_id="ws-OVERRIDE")
        self.assertTrue(result["ok"], msg=result.get("error"))
        called_path = mock_post.call_args[0][0]
        self.assertIn("ws-OVERRIDE", called_path)
        self.assertNotIn("ws-TOKEN", called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_falls_back_to_thread_local_when_token_decode_fails(
            self, mock_post):
        """When the per-request bearer decode finds nothing (e.g. a static
        admin token on the wire), the legacy ContextVar/thread-local set by
        the auth middleware is still consulted. This keeps in-process tests
        and stdio-transport callers working."""
        _grant_all_scopes()
        server._set_token_context("ws-LOCAL", "job-LOCAL")
        mock_post.return_value = {"ok": True}
        # No request context → per-request decode returns ``no_context``;
        # the call must still resolve via the thread-local fallback.
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            result = server.send_message(text="Fallback path")
        self.assertTrue(result["ok"], msg=result.get("error"))
        called_path = mock_post.call_args[0][0]
        self.assertIn("ws-LOCAL", called_path)
        self.assertIn("job-LOCAL", called_path)

    @patch.object(server, "_controller_post")
    def test_genuinely_unresolvable_call_returns_clear_error(self, mock_post):
        """When there is no explicit workstream_id, no resolvable bearer,
        and no ContextVar/thread-local context, the call must error with a
        message that names both the override path and the token path so
        the caller can fix the missing context. This is the genuinely-
        unresolvable case — not the common opencode failure mode the
        token-fallback fix addresses."""
        _grant_all_scopes()
        # Explicitly clear any leftover state so this is truly unresolvable.
        server._set_token_context("", "")
        # No MCP context at all (the typical out-of-request test path).
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            result = server.send_message(text="Nowhere to go")
        self.assertFalse(result["ok"])
        self.assertIn("workstream_id", result["error"])
        # The new error message names both the explicit arg and the token
        # path so the caller knows the two ways to provide context.
        self.assertIn("token", result["error"].lower())
        # No POST attempted — the call short-circuits at validation.
        mock_post.assert_not_called()
