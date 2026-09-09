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

# Imported after ``manager_test_support`` so ``server`` is fully loaded:
# ``messaging_tools`` imports ``server``, which imports the tools back.
import messaging_tools  # noqa: E402

# Mirrors AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS: the duration of
# agent stdout silence after which AgentInactivityMonitor destroys the
# process tree. A blocking await emits nothing, so the await cap has to
# stay under this.
INACTIVITY_WATCHDOG_SECONDS = 35 * 60


class TestAwaitMessage(unittest.TestCase):
    """Tests for ``await_message``, the receiving half of agent collaboration.

    Every test mocks ``_controller_get``, so nothing here blocks on a real
    long poll; what is asserted is the request the tool builds, the cursor
    it threads through successive calls, and the shape of what it returns.
    """

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)

    @staticmethod
    def _message(seq, text, sender="job:other"):
        """Builds one message as the controller renders it."""
        return {"seq": seq, "createdAt": "2026-09-08T19:00:00Z",
                "sender": sender, "text": text}

    @patch.object(server, "_controller_get")
    def test_delivered_message_is_returned_with_next_cursor(self, mock_get):
        """A message ends the wait and reports the cursor for the next call."""
        mock_get.return_value = {"ok": True, "nextSince": 7,
                                 "messages": [self._message(7, "ready")]}
        result = server.await_message()
        self.assertTrue(result["ok"])
        self.assertFalse(result["timed_out"])
        self.assertEqual(result["next_since"], 7)
        self.assertEqual(result["messages"][0]["text"], "ready")

    @patch.object(server, "_controller_get")
    def test_reader_excludes_its_own_messages(self, mock_get):
        """The job's own identity is excluded so it does not hear its echo."""
        mock_get.return_value = {"ok": True, "nextSince": 1, "messages": []}
        server.await_message(timeout_seconds=0)
        path = mock_get.call_args[0][0]
        self.assertIn("exclude=job%3Ajob-1", path)

    @patch.object(server, "_controller_get")
    def test_include_own_drops_the_exclusion(self, mock_get):
        """A caller that wants the full record gets no sender filter."""
        mock_get.return_value = {"ok": True, "nextSince": 1, "messages": []}
        server.await_message(timeout_seconds=0, include_own=True)
        self.assertNotIn("exclude=", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_default_since_waits_for_new_messages(self, mock_get):
        """The default cursor asks the controller to start at the head."""
        mock_get.return_value = {"ok": True, "nextSince": 4, "messages": []}
        server.await_message(timeout_seconds=0)
        self.assertIn("since=-1", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_explicit_since_replays_the_conversation(self, mock_get):
        """``since=0`` is how a later job picks up the whole history."""
        mock_get.return_value = {"ok": True, "nextSince": 2,
                                 "messages": [self._message(1, "earlier"),
                                              self._message(2, "later")]}
        result = server.await_message(since=0, timeout_seconds=0)
        self.assertIn("since=0", mock_get.call_args[0][0])
        self.assertEqual(len(result["messages"]), 2)

    @patch.object(server, "_controller_get")
    def test_timeout_is_success_not_failure(self, mock_get):
        """An empty wait returns ok with guidance to call again."""
        mock_get.return_value = {"ok": True, "nextSince": 3, "messages": []}
        result = server.await_message(timeout_seconds=0)
        self.assertTrue(result["ok"])
        self.assertTrue(result["timed_out"])
        self.assertEqual(result["next_since"], 3)
        self.assertIn("await_message again", result["hint"])

    @patch.object(server, "_controller_get")
    def test_budget_spans_several_controller_polls(self, mock_get):
        """A wait longer than one poll is composed from successive polls."""
        mock_get.side_effect = [
            {"ok": True, "nextSince": 1, "messages": []},
            {"ok": True, "nextSince": 2, "messages": [self._message(2, "go")]},
        ]
        with patch.object(messaging_tools, "CONTROLLER_POLL_SECONDS", 0), \
                patch.object(messaging_tools, "MINIMUM_POLL_INTERVAL_SECONDS", 0):
            result = server.await_message(timeout_seconds=5)
        self.assertEqual(mock_get.call_count, 2)
        self.assertEqual(result["messages"][0]["text"], "go")
        self.assertIn("since=1", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_a_controller_that_does_not_block_is_not_polled_hot(self, mock_get):
        """A long poll answered instantly must not become a spin loop.

        With the controller returning immediately, the number of polls a
        five-second budget can produce is bounded by the interval floor.
        Without the floor this loop runs as fast as the interpreter allows.
        """
        mock_get.return_value = {"ok": True, "nextSince": 0, "messages": []}
        with patch.object(messaging_tools, "CONTROLLER_POLL_SECONDS", 0):
            result = server.await_message(timeout_seconds=3)
        self.assertTrue(result["timed_out"])
        self.assertLessEqual(mock_get.call_count, 5)

    @patch.object(server, "_controller_get")
    def test_wait_beyond_the_cap_is_clamped(self, mock_get):
        """A caller asking for more than the cap is clamped and told so."""
        mock_get.return_value = {"ok": True, "nextSince": 0, "messages": []}
        with patch.object(messaging_tools, "MAX_AWAIT_SECONDS", 0):
            result = server.await_message(timeout_seconds=99999)
        self.assertTrue(result["clamped_to_max_await_seconds"])
        self.assertTrue(result["timed_out"])

    def test_max_await_stays_below_the_inactivity_watchdog(self):
        """The real cap must leave room inside the stdout-silence watchdog.

        Kept apart from the clamping test, and deliberately patching
        nothing, because this asserts a property of the shipped constant
        rather than of any one call: raising MAX_AWAIT_SECONDS past the
        watchdog would let a single await outlast it and have the agent's
        process tree killed mid-wait.
        """
        self.assertLess(messaging_tools.MAX_AWAIT_SECONDS,
                        INACTIVITY_WATCHDOG_SECONDS)

    @patch.object(server, "_controller_get")
    def test_controller_error_is_reported(self, mock_get):
        """A rejected read fails loudly rather than looking like a timeout."""
        mock_get.return_value = {"ok": False, "error": "Unknown workstream: ws-1"}
        result = server.await_message(since=4, timeout_seconds=0)
        self.assertFalse(result["ok"])
        self.assertEqual(result["next_since"], 4)
        self.assertIn("Unknown workstream", result["error"])

    @patch.object(server, "_controller_get")
    def test_unresolvable_workstream_never_calls_the_controller(self, mock_get):
        """Without a workstream there is no conversation to read."""
        server._set_token_context(workstream_id=None, job_id=None)
        with patch.object(server, "_decode_current_request_token_full",
                          return_value=(None, None, None, "no_request")):
            result = server.await_message()
        self.assertFalse(result["ok"])
        self.assertIn("workstream_id", result["error"])
        mock_get.assert_not_called()

    def test_read_scope_is_required(self):
        """An unauthenticated request cannot read a conversation."""
        _clear_scopes()
        try:
            with self.assertRaises(PermissionError):
                server.await_message()
        finally:
            _grant_all_scopes()


class TestSendMessageSenderIdentity(unittest.TestCase):
    """``send_message`` must name its sender, or peers cannot filter echoes."""

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)

    @patch.object(server, "_controller_post")
    def test_job_identifies_itself_by_job_id(self, mock_post):
        mock_post.return_value = {"ok": True}
        server.send_message(text="ready")
        self.assertEqual(mock_post.call_args[0][1]["sender"], "job:job-1")

    @patch.object(server, "_controller_post")
    def test_operator_identifies_itself_by_token_label(self, mock_post):
        mock_post.return_value = {"ok": True}
        server._set_token_context(workstream_id=None, job_id=None)
        server.send_message(text="proceed", workstream_id="ws-1")
        self.assertEqual(mock_post.call_args[0][1]["sender"], "caller:test")


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


class TestSendAlert(unittest.TestCase):
    """Covers the send_alert pass-through to the controller alert endpoint."""

    def setUp(self):
        _grant_all_scopes()
        server._set_token_context(workstream_id="ws-1", job_id="job-1")

    def tearDown(self):
        server._set_token_context(workstream_id=None, job_id=None)

    @patch.object(server, "_controller_post")
    def test_posts_to_alert_endpoint(self, mock_post):
        """A well-formed call reaches /api/alerts with the parsed recipients."""
        mock_post.return_value = {"ok": True, "delivered": ["michael"]}
        result = server.send_alert(text="Build is green", recipients="michael")

        mock_post.assert_called_once()
        path, body = mock_post.call_args[0][0], mock_post.call_args[0][1]
        self.assertEqual(path, "/api/alerts")
        self.assertEqual(body["text"], "Build is green")
        self.assertEqual(body["recipients"], ["michael"])
        self.assertEqual(body["severity"], "INFO")
        self.assertTrue(result["ok"])

    @patch.object(server, "_controller_post")
    def test_recipients_are_split_and_trimmed(self, mock_post):
        """A comma-separated list becomes a JSON array with blanks dropped."""
        mock_post.return_value = {"ok": True}
        server.send_alert(text="Note", recipients=" michael , mmurray ,, ")

        self.assertEqual(mock_post.call_args[0][1]["recipients"],
                         ["michael", "mmurray"])

    @patch.object(server, "_controller_post")
    def test_severity_is_forwarded(self, mock_post):
        """An explicit severity is passed through untouched."""
        mock_post.return_value = {"ok": True}
        server.send_alert(text="Disk full", recipients="michael", severity="ERROR")

        self.assertEqual(mock_post.call_args[0][1]["severity"], "ERROR")

    @patch.object(server, "_controller_post")
    def test_caller_is_the_token_label(self, mock_post):
        """The rate-limit key is the same identity the audit log records."""
        mock_post.return_value = {"ok": True}
        server.send_alert(text="Note", recipients="michael")

        self.assertEqual(mock_post.call_args[0][1]["caller"],
                         server._get_token_label())

    @patch.object(server, "_controller_post")
    def test_missing_recipients_is_rejected_locally(self, mock_post):
        """An empty recipient list fails without reaching the controller."""
        result = server.send_alert(text="Note", recipients="  , ")

        self.assertFalse(result["ok"])
        self.assertIn("recipients", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_missing_text_is_rejected_locally(self, mock_post):
        """Blank text fails without reaching the controller."""
        result = server.send_alert(text="   ", recipients="michael")

        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_overlong_text_is_rejected_locally(self, mock_post):
        """Text past the controller's limit fails without a round trip."""
        result = server.send_alert(text="x" * (server.MAX_ALERT_TEXT_LEN + 1),
                                   recipients="michael")

        self.assertFalse(result["ok"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_text_at_the_limit_is_accepted(self, mock_post):
        """The boundary itself is allowed, matching the controller."""
        mock_post.return_value = {"ok": True}
        server.send_alert(text="x" * server.MAX_ALERT_TEXT_LEN, recipients="michael")

        mock_post.assert_called_once()

    def test_alert_limit_is_below_the_content_limit(self):
        """send_alert must not validate against the general content cap.

        The controller rejects an alert body past MAX_ALERT_TEXT_LEN, so
        validating against MAX_CONTENT_LEN would forward payloads that were
        always going to be refused.
        """
        self.assertLess(server.MAX_ALERT_TEXT_LEN, server.MAX_CONTENT_LEN)

    def test_requires_write_scope(self):
        """A read-only token cannot send an alert."""
        _grant_scopes(["read"])
        try:
            with self.assertRaises(PermissionError):
                server.send_alert(text="Note", recipients="michael")
        finally:
            _grant_all_scopes()
