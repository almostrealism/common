"""Tests for server-level concerns: authentication, scopes, tokens, rate
limiting, tool registration and packaging.

Split from ``test_server.py``, which had grown past the file-length cap. The
tests are unchanged; shared fixtures live in ``manager_test_support``.
"""

import json
import os
import subprocess
import sys
import tempfile
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


class TestControllerHealth(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_returns_health(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {
            "status": "ok",
            "version": "1.0",
            "server_time": "2026-05-11T18:23:45.123456789Z",
        }
        result = server.controller_health()
        mock_get.assert_called_once_with("/api/health")
        self.assertEqual(result["status"], "ok")
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_get")
    def test_server_time_present_and_utc(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {
            "status": "ok",
            "server_time": "2026-05-11T18:23:45.123456789Z",
        }
        result = server.controller_health()
        self.assertIn("server_time", result)
        server_time = result["server_time"]
        # Must match ISO-8601 UTC: YYYY-MM-DDTHH:MM:SS[.fractional]Z
        import re
        iso_utc_pattern = re.compile(
            r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$'
        )
        self.assertRegex(
            server_time,
            iso_utc_pattern,
            f"server_time must be ISO-8601 UTC (ending in Z), got: {server_time!r}",
        )

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.controller_health()

class TestAgentOptions(unittest.TestCase):
    """Tests for the agent_options tool."""

    @patch.object(server, "_controller_get")
    def test_returns_controller_response(self, mock_get):
        """agent_options proxies the /api/agents response as-is."""
        _grant_all_scopes()
        mock_response = {
            "ok": True,
            "runners": [
                {
                    "name": "claude",
                    "capabilities": {
                        "reportsCost": True,
                        "reportsTurns": True,
                        "supportsEffortLevel": True,
                        "supportsMaxBudget": True,
                        "supportsMcpHttpTransport": True,
                        "supportsMcpStdioTransport": True,
                        "supportsPermissionDenialReporting": True,
                        "supportedModels": ["sonnet", "opus"],
                    },
                },
                {
                    "name": "opencode",
                    "capabilities": {
                        "reportsCost": False,
                        "reportsTurns": False,
                        "supportsEffortLevel": False,
                        "supportsMaxBudget": False,
                        "supportsMcpHttpTransport": False,
                        "supportsMcpStdioTransport": True,
                        "supportsPermissionDenialReporting": False,
                        "supportedModels": [],
                    },
                },
            ],
            "phases": [
                {"name": "primary", "description": "Primary work."},
                {"name": "deduplication", "description": "Deduplication audit."},
            ],
            "models": ["sonnet", "opus", "haiku"],
            "defaultRunner": "claude",
        }
        mock_get.return_value = mock_response
        result = server.agent_options()
        mock_get.assert_called_once_with("/api/agents")
        self.assertTrue(result["ok"])
        self.assertEqual(result["defaultRunner"], "claude")
        self.assertEqual(len(result["runners"]), 2)
        runner_names = [r["name"] for r in result["runners"]]
        self.assertIn("claude", runner_names)
        self.assertIn("opencode", runner_names)
        self.assertEqual(len(result["phases"]), 2)
        self.assertEqual(result["phases"][0]["name"], "primary")
        self.assertIn("models", result)

    def test_requires_read_scope(self):
        """agent_options must require at least read scope."""
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.agent_options()

    @patch.object(server, "_controller_get")
    def test_phase_list_wired_correctly(self, mock_get):
        """Phases list in response must contain name and description keys."""
        _grant_all_scopes()
        mock_get.return_value = {
            "ok": True,
            "runners": [],
            "phases": [{"name": "primary", "description": "Primary work."}],
            "models": [],
            "defaultRunner": "claude",
        }
        result = server.agent_options()
        phases = result.get("phases", [])
        self.assertTrue(len(phases) > 0)
        for phase in phases:
            self.assertIn("name", phase)
            self.assertIn("description", phase)

class TestControllerUpdateConfig(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_read_current_setting(self, mock_get):
        _grant_all_scopes()
        mock_get.return_value = {"acceptAutomatedJobs": True}
        result = server.controller_update_config()
        mock_get.assert_called_once_with("/api/config/accept-automated-jobs")
        self.assertTrue(result["acceptAutomatedJobs"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_controller_post")
    def test_set_false(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": False}
        result = server.controller_update_config(accept_automated_jobs="false")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": False})
        self.assertFalse(result["acceptAutomatedJobs"])

    @patch.object(server, "_controller_post")
    def test_set_true(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": True}
        result = server.controller_update_config(accept_automated_jobs="true")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": True})
        self.assertTrue(result["acceptAutomatedJobs"])

    @patch.object(server, "_controller_post")
    def test_case_insensitive(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": True}
        server.controller_update_config(accept_automated_jobs="True")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": True})

    @patch.object(server, "_controller_post")
    def test_non_true_string_treated_as_false(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "acceptAutomatedJobs": False}
        server.controller_update_config(accept_automated_jobs="no")
        mock_post.assert_called_once_with(
            "/api/config/accept-automated-jobs", {"accept": False})

    def test_requires_write_scope(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server.controller_update_config()

class TestAuthMiddlewareTokenContextLifecycle(unittest.TestCase):
    """The auth middleware must bind a fresh token context on every
    authenticated request — including static-token requests, which have
    no workstream binding of their own — so that thread-local state from
    a prior HMAC-temp-token request cannot leak into the new request's
    self-collision check.
    """

    def setUp(self):
        # Simulate a previous temp-token request on this thread.
        server._thread_local.workstream_id = "ws-cluster-prior"
        server._thread_local.job_id = "job-cluster-prior"

    def tearDown(self):
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        server._request_scopes.set(None)
        server._request_token_label.set(None)
        server._request_workspace_scopes.set(None)
        if hasattr(server._thread_local, "scopes"):
            del server._thread_local.scopes
        if hasattr(server._thread_local, "token_label"):
            del server._thread_local.token_label
        if hasattr(server._thread_local, "workspace_scopes"):
            del server._thread_local.workspace_scopes

    def test_static_token_match_clears_token_context(self):
        import asyncio

        captured = {}

        async def downstream(scope, receive, send):
            captured["workstream_id"] = server._get_token_workstream_id()
            captured["job_id"] = server._get_token_job_id()
            await send({"type": "http.response.start", "status": 200,
                        "headers": []})
            await send({"type": "http.response.body", "body": b""})

        middleware = server.BearerAuthMiddleware(
            downstream,
            tokens=[{"value": "static-token-xyz", "scopes": ["submit"],
                     "label": "static-test"}],
        )
        scope = {
            "type": "http",
            "path": "/mcp",
            "headers": [(b"authorization", b"Bearer static-token-xyz")],
        }

        async def receive():
            return {"type": "http.request"}

        async def send(_msg):
            return None

        asyncio.run(middleware(scope, receive, send))
        # Static tokens carry no workstream binding; the middleware must
        # have cleared the leaked thread-local values.
        self.assertFalse(bool(captured.get("workstream_id")))
        self.assertFalse(bool(captured.get("job_id")))

class TestPhaseConfigParameters(unittest.TestCase):
    """End-to-end coverage of ``default_phase_config`` and ``phase_configs``
    across all four MCP entry points (``workstream_submit_task``,
    ``workstream_register``, ``workstream_update_config``,
    ``workspace_update_config``).

    The four tools share the same parser pair
    (``_parse_default_phase_config_json`` / ``_parse_phase_configs_json``)
    so the validation behaviour is uniform. These tests pin that
    behaviour at the entry-point level so a future refactor that splits
    the parsers per-tool cannot silently regress one of them.
    """

    def setUp(self):
        _grant_all_scopes()

    def _invoke(self, tool, **kwargs):
        """Dispatch ``tool`` (one of the four entry point names) with the
        smallest required positional args plus ``kwargs``. Hides the
        per-tool boilerplate so the test bodies focus on the new
        parameters."""
        if tool == "submit":
            return server.workstream_submit_task(prompt="Task", **kwargs)
        if tool == "register":
            return server.workstream_register(default_branch="feature/x", **kwargs)
        if tool == "update":
            return server.workstream_update_config(workstream_id="ws-test", **kwargs)
        if tool == "workspace":
            return server.workspace_update_config(
                workspace_id="almostrealism", **kwargs)
        raise AssertionError("unknown tool: " + tool)

    def _payload(self, mock_post):
        return mock_post.call_args[0][1]

    # ---- default_phase_config only ----------------------------------------

    @patch.object(server, "_controller_post")
    def test_default_phase_config_forwarded_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            self._invoke(
                tool,
                default_phase_config='{"runner":"claude","model":"opus","effort":"high"}')
            payload = self._payload(mock_post)
            self.assertEqual(
                payload["defaultPhaseConfig"],
                {"runner": "claude", "model": "opus", "effort": "high"},
                "tool=" + tool)

    # ---- phase_configs only -----------------------------------------------

    @patch.object(server, "_controller_post")
    def test_phase_configs_forwarded_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            self._invoke(
                tool,
                phase_configs='{"review":{"model":"opus","effort":"high"},'
                              '"commit-message":{"runner":"opencode"}}')
            payload = self._payload(mock_post)
            self.assertEqual(payload["phaseConfigs"], {
                "review": {"model": "opus", "effort": "high"},
                "commit-message": {"runner": "opencode"},
            }, "tool=" + tool)

    # ---- both supplied ----------------------------------------------------

    @patch.object(server, "_controller_post")
    def test_both_parameters_forwarded_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            self._invoke(
                tool,
                default_phase_config='{"runner":"claude","model":"opus"}',
                phase_configs='{"review":{"effort":"high"}}')
            payload = self._payload(mock_post)
            self.assertEqual(payload["defaultPhaseConfig"],
                             {"runner": "claude", "model": "opus"},
                             "tool=" + tool)
            self.assertEqual(payload["phaseConfigs"],
                             {"review": {"effort": "high"}}, "tool=" + tool)

    # ---- empty strings leave fields unchanged -----------------------------

    @patch.object(server, "_controller_post")
    def test_empty_strings_omit_fields_for_all_tools(self, mock_post):
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            # workspace_update_config requires at least one writable field
            # before it forwards a payload, so we pair the empty
            # phase-config args with ``name`` to keep the call valid.
            extra = {"name": "Acme"} if tool == "workspace" else {}
            if tool == "update":
                extra = {"default_branch": "feature/x"}
            self._invoke(
                tool,
                default_phase_config="",
                phase_configs="",
                **extra)
            payload = self._payload(mock_post)
            self.assertNotIn("defaultPhaseConfig", payload, "tool=" + tool)
            self.assertNotIn("phaseConfigs", payload, "tool=" + tool)

    # ---- malformed JSON ---------------------------------------------------

    def test_default_phase_config_rejects_malformed_json_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(tool, default_phase_config="not-valid-json")
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("JSON object", result["error"], "tool=" + tool)

    def test_phase_configs_rejects_malformed_json_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(tool, phase_configs="not-valid-json")
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("JSON object", result["error"], "tool=" + tool)

    def test_default_phase_config_rejects_non_object_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(tool, default_phase_config='["a","b"]')
            self.assertFalse(result["ok"], "tool=" + tool)

    # ---- unknown phase wire name ------------------------------------------

    def test_phase_configs_rejects_unknown_phase_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, phase_configs='{"future-phase":{"runner":"claude"}}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("future-phase", result["error"], "tool=" + tool)

    # ---- unknown runner name ----------------------------------------------

    def test_default_phase_config_rejects_unknown_runner_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, default_phase_config='{"runner":"not-a-runner"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("not-a-runner", result["error"], "tool=" + tool)
            self.assertIn("agent_options", result["error"], "tool=" + tool)

    def test_phase_configs_rejects_unknown_runner_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool,
                phase_configs='{"review":{"runner":"not-a-runner"}}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("not-a-runner", result["error"], "tool=" + tool)

    # ---- unknown effort value ---------------------------------------------

    def test_default_phase_config_rejects_unknown_effort_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, default_phase_config='{"effort":"extreme"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("extreme", result["error"], "tool=" + tool)

    def test_phase_configs_rejects_unknown_effort_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, phase_configs='{"review":{"effort":"extreme"}}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("extreme", result["error"], "tool=" + tool)

    # ---- unknown inner key ------------------------------------------------

    def test_default_phase_config_rejects_unknown_inner_key_for_all_tools(self):
        for tool in ("submit", "register", "update", "workspace"):
            result = self._invoke(
                tool, default_phase_config='{"bogus":"value"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("bogus", result["error"], "tool=" + tool)

    # ---- dropped legacy params are rejected, never translated -------------

    @patch.object(server, "_controller_post")
    def test_dropped_legacy_params_rejected_for_all_tools(self, mock_post):
        """The legacy config params are no longer accepted on any entry point.
        Each of ``model`` / ``effort`` / ``default_runner`` / ``runners`` is
        rejected with a 400-style error that names the parameter and points at
        the per-phase replacement; the controller is never called and no
        silent translation occurs. ``model`` / ``effort`` were never workspace
        fields, so only ``default_runner`` / ``runners`` apply to that tool."""
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        legacy = {
            "model": "opus",
            "effort": "high",
            "default_runner": "claude",
            "runners": '{"primary":"opencode"}',
        }
        for tool in ("submit", "register", "update", "workspace"):
            params = (("default_runner", "runners") if tool == "workspace"
                      else tuple(legacy))
            for param in params:
                mock_post.reset_mock()
                label = "tool=" + tool + " param=" + param
                result = self._invoke(tool, **{param: legacy[param]})
                self.assertFalse(result["ok"], label)
                self.assertIn(param, result["error"], label)
                self.assertIn("no longer supported", result["error"], label)
                self.assertEqual(result["removed_parameters"], [param], label)
                mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_dropped_legacy_param_rejected_even_with_new_shape(self, mock_post):
        """A legacy param is rejected even when a valid new-shape param is
        also supplied — the clean break takes priority over translation."""
        mock_post.return_value = {"ok": True, "jobId": "j", "workstreamId": "w"}
        for tool in ("submit", "register", "update", "workspace"):
            mock_post.reset_mock()
            result = self._invoke(
                tool,
                default_phase_config='{"runner":"claude"}',
                runners='{"review":"opencode"}')
            self.assertFalse(result["ok"], "tool=" + tool)
            self.assertIn("runners", result["error"], "tool=" + tool)
            mock_post.assert_not_called()

class TestDefaultBranch(unittest.TestCase):
    """Resolution of a repository's default branch.

    Assuming "master" when no workstream supplies a baseBranch makes every
    Compare API call against a "main"-default repository 404, which surfaces
    to the caller as "no commits" rather than as an error.
    """

    def setUp(self):
        server.github_api._default_branch_cache.clear()
        self.addCleanup(server.github_api._default_branch_cache.clear)

    @patch.object(server.github_api, "_github_request")
    def test_returns_reported_default(self, mock_request):
        mock_request.return_value = {"default_branch": "main"}
        self.assertEqual(
            server.github_api.default_branch("org", "repo"), "main")

    @patch.object(server.github_api, "_github_request")
    def test_caches_successful_lookup(self, mock_request):
        mock_request.return_value = {"default_branch": "main"}
        server.github_api.default_branch("org", "repo")
        server.github_api.default_branch("org", "repo")
        mock_request.assert_called_once()

    @patch.object(server.github_api, "_github_request")
    def test_falls_back_when_github_errors(self, mock_request):
        mock_request.return_value = {"ok": False, "error": "unreachable"}
        self.assertEqual(
            server.github_api.default_branch("org", "repo"), "master")

    @patch.object(server.github_api, "_github_request")
    def test_does_not_cache_a_failure(self, mock_request):
        mock_request.return_value = {"ok": False, "error": "unreachable"}
        server.github_api.default_branch("org", "repo")
        mock_request.return_value = {"default_branch": "main"}
        # A transient outage must not pin "master" for the whole TTL.
        self.assertEqual(
            server.github_api.default_branch("org", "repo"), "main")

    @patch.object(server.github_api, "_github_request")
    def test_honours_explicit_fallback(self, mock_request):
        mock_request.return_value = {}
        self.assertEqual(
            server.github_api.default_branch("org", "repo", fallback="trunk"),
            "trunk")

class TestPerRequestTokenDecoding(unittest.TestCase):
    """Regression tests for the fix that decodes the Bearer token directly
    from the current MCP tool call's HTTP request rather than relying on
    a ContextVar/threading.local set by the auth middleware.

    The opencode runner exposed a defect in the contextvar/thread-local
    mechanism: under FastMCP's streamable-HTTP **stateful** transport,
    tool handlers run on a long-lived "session task" whose context was
    captured at session creation time and is not updated by subsequent
    per-request auth middleware passes. The thread-local fallback is
    racy across concurrent requests on a single event-loop thread. The
    result was that ``send_message`` from an opencode session saw an
    empty workstream/job context and either errored out or landed
    messages at the top level of the workspace's Slack channel instead
    of inside the job thread.

    The fix decodes the Bearer token from the current request directly
    via ``mcp.get_context().request_context.request.headers``. This
    class verifies the decode helper and the upstream getters honour
    the per-request value.
    """

    def setUp(self):
        # Clear any leftover ContextVar / thread-local state so we are
        # testing the per-request path in isolation.
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
        """Return a stand-in for ``mcp.get_context()`` whose
        ``request_context.request.headers`` reports the given Bearer
        token (or no auth header at all when ``token_value`` is None).
        """
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
    def test_decode_returns_workstream_and_job_from_temp_token(self):
        token = server._mint_temp_token("ws-A", "job-A", ttl_seconds=60)
        self.assertIsNotNone(token)
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            ws, job = server._decode_current_request_token()
        self.assertEqual(ws, "ws-A")
        self.assertEqual(job, "job-A")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_no_bearer_header(self):
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(None)):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_no_request_context(self):
        fake_ctx = MagicMock()
        fake_ctx.request_context = None
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    # ------------------------------------------------------------------
    # Reason-string accuracy tests (Copilot review comment, PR #237)
    # _decode_current_request_token_full() must return "no_request" when
    # there is no live MCP request, and "no_context" only when the MCP
    # context itself is unavailable.
    # ------------------------------------------------------------------

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_full_decode_reason_no_context_when_get_context_raises(self):
        # mcp.get_context() raises → no MCP context at all → "no_context"
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no context")):
            _, _, _, reason = server._decode_current_request_token_full()
        self.assertEqual(reason, "no_context")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_full_decode_reason_no_request_when_request_context_none(self):
        # Context exists but request_context is None → no active request → "no_request"
        fake_ctx = MagicMock()
        fake_ctx.request_context = None
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            _, _, _, reason = server._decode_current_request_token_full()
        self.assertEqual(reason, "no_request")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_full_decode_reason_no_request_when_request_context_raises(self):
        # request_context property raises ValueError (e.g. unit-test context)
        # → no active request → "no_request"
        fake_ctx = MagicMock()
        type(fake_ctx).request_context = mock.PropertyMock(
            side_effect=ValueError("Context is not available outside of a request"))
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            _, _, _, reason = server._decode_current_request_token_full()
        self.assertEqual(reason, "no_request")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_for_static_token_bearer(self):
        # A non-HMAC bearer (e.g. a static long-lived admin token) is
        # not an ``armt_tmp_`` token; the decode helper must reject it.
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(
                              "static-admin-token")):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_get_context_lookup_errors(self):
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_decode_returns_none_when_request_context_property_raises(self):
        # ``FastMCP.Context.request_context`` raises ValueError when the
        # tool is invoked outside of a live MCP request (e.g. unit tests
        # that call the function directly). The decoder must treat that
        # as "no request" rather than crashing the tool.
        fake_ctx = MagicMock()
        type(fake_ctx).request_context = mock.PropertyMock(
            side_effect=ValueError("Context is not available outside of a request"))
        with patch.object(server.mcp, "get_context", return_value=fake_ctx):
            ws, job = server._decode_current_request_token()
        self.assertIsNone(ws)
        self.assertIsNone(job)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_token_getters_prefer_per_request_value_over_contextvar(self):
        # Simulate the FastMCP stateful-transport hazard: the session
        # task's ContextVar/thread-local were captured from a prior
        # request bound to a different workstream/job, but the actual
        # current HTTP request carries a temp token for ws-CURRENT.
        server._set_token_context("ws-STALE", "job-STALE")
        token = server._mint_temp_token("ws-CURRENT", "job-CURRENT", ttl_seconds=60)
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            self.assertEqual(server._get_token_workstream_id(), "ws-CURRENT")
            self.assertEqual(server._get_token_job_id(), "job-CURRENT")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_token_getters_fall_back_when_no_request(self):
        # When there is no active MCP request context, the legacy
        # ContextVar/thread-local fallback still applies. This keeps
        # in-process tests and stdio-transport callers working.
        server._set_token_context("ws-FALLBACK", "job-FALLBACK")
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            self.assertEqual(server._get_token_workstream_id(), "ws-FALLBACK")
            self.assertEqual(server._get_token_job_id(), "job-FALLBACK")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_post")
    def test_send_message_uses_per_request_token_for_thread_routing(
            self, mock_post):
        """The end-to-end regression test for opencode's threading bug.

        With the session task carrying stale ContextVar state (as
        happens in FastMCP stateful streamable-HTTP transport),
        ``send_message`` must still post into the *current* request's
        job thread — derived from the Bearer token attached to the
        in-flight HTTP request — not the stale session-task context.
        """
        _grant_all_scopes()
        # Stale session-task context (the FastMCP hazard).
        server._set_token_context("ws-STALE", "job-STALE")
        # Current HTTP request carries a temp token for ws-A / job-7.
        token = server._mint_temp_token("ws-A", "job-7", ttl_seconds=60)
        mock_post.return_value = {"ok": True}
        with patch.object(server.mcp, "get_context",
                          return_value=self._fake_context_with_bearer(token)):
            result = server.send_message(text="Hello from opencode")
        self.assertTrue(result["ok"], msg=result.get("error"))
        mock_post.assert_called_once()
        called_path = mock_post.call_args[0][0]
        # Must route to /api/workstreams/ws-A/jobs/job-7/messages — NOT
        # to /api/workstreams/ws-STALE/... and NOT to the workstream-
        # level ``/messages`` endpoint (which would land at the top of
        # the channel).
        self.assertIn("/api/workstreams/ws-A/jobs/job-7/messages",
                      called_path)
        self.assertNotIn("ws-STALE", called_path)

class TestFindRecentPrByBranch(unittest.TestCase):
    """Unit tests for _find_recent_pr_by_branch GitHub API integration."""

    @patch.object(server, "_github_request")
    def test_calls_pulls_list_api_with_correct_params(self, mock_gh):
        """Verifies _find_recent_pr_by_branch uses the Pulls list API with state=all."""
        mock_gh.return_value = []
        result = server._find_recent_pr_by_branch("org", "repo", "feature/x")
        mock_gh.assert_called_once()
        call_args = mock_gh.call_args
        self.assertEqual(call_args[0][0], "GET")
        self.assertIn("/repos/org/repo/pulls", call_args[0][1])
        self.assertIn("head=org:feature/x", call_args[0][1])
        self.assertIn("state=all", call_args[0][1])
        self.assertIn("sort=updated", call_args[0][1])
        self.assertIn("direction=desc", call_args[0][1])
        self.assertIn("per_page=1", call_args[0][1])
        self.assertTrue(result["ok"])
        self.assertFalse(result["found"])

    @patch.object(server, "_github_request")
    def test_returns_pr_with_full_fields(self, mock_gh):
        """Verifies the returned PR object contains merged_at, base, and head fields."""
        mock_pr = {
            "number": 42,
            "title": "Feature",
            "html_url": "https://github.com/org/repo/pull/42",
            "state": "closed",
            "merged_at": "2026-01-09T00:00:00Z",
            "closed_at": "2026-01-10T00:00:00Z",
            "user": {"login": "author"},
            "base": {"ref": "master"},
            "head": {"ref": "feature/x"},
        }
        mock_gh.return_value = [mock_pr]
        result = server._find_recent_pr_by_branch("org", "repo", "feature/x")
        self.assertTrue(result["ok"])
        self.assertTrue(result["found"])
        pr = result["pr"]
        self.assertEqual(pr["merged_at"], "2026-01-09T00:00:00Z")
        self.assertEqual(pr["base"]["ref"], "master")
        self.assertEqual(pr["head"]["ref"], "feature/x")

    @patch.object(server, "_github_request")
    def test_handles_github_error(self, mock_gh):
        """Verifies GitHub API errors are propagated correctly."""
        mock_gh.return_value = {"ok": False, "error": "rate limited"}
        result = server._find_recent_pr_by_branch("org", "repo", "feature/x")
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "rate limited")

class TestControllerPost(unittest.TestCase):

    @patch("server.urlopen")
    def test_success_response(self, mock_urlopen):
        resp = MagicMock()
        resp.read.return_value = b'{"ok":true,"jobId":"j1"}'
        resp.__enter__ = lambda s: s
        resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = resp
        result = server._controller_post("/api/submit", {"prompt": "test"})
        self.assertTrue(result["ok"])
        self.assertEqual(result["jobId"], "j1")

    @patch("server.urlopen")
    def test_empty_body_returns_ok(self, mock_urlopen):
        resp = MagicMock()
        resp.read.return_value = b""
        resp.__enter__ = lambda s: s
        resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = resp
        result = server._controller_post("/api/test", {})
        self.assertTrue(result["ok"])

    @patch("server.urlopen")
    def test_http_error_with_json_body(self, mock_urlopen):
        error = HTTPError(
            url="http://test/api/submit", code=400, msg="Bad Request",
            hdrs=None, fp=None)
        error.read = lambda: b'{"ok":false,"error":"Missing prompt"}'
        mock_urlopen.side_effect = error
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("Missing prompt", result["error"])

    @patch("server.urlopen")
    def test_http_error_without_json(self, mock_urlopen):
        error = HTTPError(
            url="http://test/api/submit", code=500, msg="Server Error",
            hdrs=None, fp=None)
        error.read = lambda: b"Internal Server Error"
        mock_urlopen.side_effect = error
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("500", result["error"])

    @patch("server.urlopen")
    def test_url_error_returns_unreachable(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("Connection refused")
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("unreachable", result["error"])

    @patch("server.urlopen")
    def test_timeout_returns_error(self, mock_urlopen):
        mock_urlopen.side_effect = TimeoutError("timed out")
        result = server._controller_post("/api/submit", {})
        self.assertFalse(result["ok"])
        self.assertIn("error", result)

class TestParseDependentRepos(unittest.TestCase):

    def test_empty_string_returns_empty(self):
        self.assertEqual([], server._parse_dependent_repos(""))

    def test_none_returns_empty(self):
        self.assertEqual([], server._parse_dependent_repos(None))

    def test_single_url_csv(self):
        result = server._parse_dependent_repos("https://github.com/org/repo")
        self.assertEqual(["https://github.com/org/repo"], result)

    def test_multiple_urls_csv(self):
        result = server._parse_dependent_repos(
            "https://github.com/org/a,https://github.com/org/b"
        )
        self.assertEqual(
            ["https://github.com/org/a", "https://github.com/org/b"], result
        )

    def test_csv_drops_empty_entries(self):
        result = server._parse_dependent_repos(
            "https://github.com/org/a,,https://github.com/org/b"
        )
        self.assertEqual(
            ["https://github.com/org/a", "https://github.com/org/b"], result
        )

    def test_json_array(self):
        result = server._parse_dependent_repos(
            '["https://github.com/org/a","https://github.com/org/b"]'
        )
        self.assertEqual(
            ["https://github.com/org/a", "https://github.com/org/b"], result
        )

    def test_json_array_drops_empty_entries(self):
        result = server._parse_dependent_repos('["https://github.com/org/a","","  "]')
        self.assertEqual(["https://github.com/org/a"], result)

    def test_invalid_json_falls_back_to_csv(self):
        result = server._parse_dependent_repos(
            "[https://github.com/org/a,https://github.com/org/b]"
        )
        # Invalid JSON — falls back to CSV splitting on commas
        self.assertIsInstance(result, list)
        # Should not raise; best-effort result acceptable

    def test_whitespace_only_returns_empty(self):
        self.assertEqual([], server._parse_dependent_repos("   "))

class TestInputValidation(unittest.TestCase):

    def test_check_short_strings(self):
        result = server._check_short_strings(field="x" * 1001)
        self.assertFalse(result["ok"])
        self.assertIn("maximum length", result["error"])

    def test_check_short_strings_ok(self):
        result = server._check_short_strings(field="short")
        self.assertIsNone(result)

    def test_check_length(self):
        result = server._check_length("x" * 101, "field", 100)
        self.assertFalse(result["ok"])

    def test_check_length_ok(self):
        result = server._check_length("short", "field", 100)
        self.assertIsNone(result)

class TestScopeEnforcement(unittest.TestCase):

    def test_no_scopes_denies_all(self):
        # ar-manager fails closed: a request with no authenticated scopes
        # (which a properly-started server never serves, since it refuses
        # stdio / no-token operation) is denied every scope rather than
        # implicitly granted all of them.
        _clear_scopes()
        for scope in ("read", "write", "pipeline", "memory"):
            with self.assertRaises(PermissionError):
                server._require_scope(scope)

    def test_scope_mismatch_raises(self):
        _grant_scopes("read")
        with self.assertRaises(PermissionError):
            server._require_scope("write")

    def test_scope_match_permits(self):
        _grant_scopes("read", "write")
        server._require_scope("read")
        server._require_scope("write")

class TestStartupGuard(unittest.TestCase):
    """ar-manager refuses to start without HTTP transport + auth, closing the
    tokenless / stdio escape hatch. Each case launches server.py as a
    subprocess and asserts it exits non-zero with the expected FATAL message
    before binding any socket."""

    _TOKENS = json.dumps({"tokens": [{"value": "t", "scopes": ["read"],
                                      "label": "x"}]})

    def _run_server(self, env_overrides):
        env = dict(os.environ)
        for key in ("MCP_TRANSPORT", "AR_MANAGER_TOKENS", "AR_MANAGER_TOKEN_FILE"):
            env.pop(key, None)
        env.update(env_overrides)
        return subprocess.run(
            [sys.executable, os.path.join(_MANAGER_DIR, "server.py")],
            cwd=_MANAGER_DIR, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30,
        )

    def test_stdio_transport_refused(self):
        # Explicit stdio must be refused even with tokens present (the default
        # transport is http; stdio only happens when set deliberately).
        proc = self._run_server({"MCP_TRANSPORT": "stdio",
                                 "AR_MANAGER_TOKENS": self._TOKENS})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("unsupported MCP_TRANSPORT", proc.stdout.decode())

    def test_http_without_tokens_refused(self):
        proc = self._run_server({"MCP_TRANSPORT": "http"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("no auth tokens configured", proc.stdout.decode())

class TestScriptModeToolSurface(unittest.TestCase):
    """The tools a client actually sees come from running server.py as a
    script, which is how the Dockerfile ENTRYPOINT and start.sh launch it.

    Every other test in this file reaches the tools by importing the module,
    and under an import all of them register. That blind spot let a
    redeployment serve only the three tools defined above the tool-module
    imports at the bottom of server.py while the suite stayed green. This
    case runs the real entry point and compares what it would serve against
    what the import registers.
    """

    _TOKENS = json.dumps({"tokens": [{"value": "t", "scopes": ["read"],
                                      "label": "x"}]})

    _REPORTER = (
        "import sys, types\n"
        "fake = types.ModuleType('uvicorn')\n"
        "def run(app, **kw):\n"
        "    import __main__\n"
        "    print('TOOLS:' + ','.join(sorted(__main__.mcp._tool_manager._tools)))\n"
        "    raise SystemExit(0)\n"
        "fake.run = run\n"
        "sys.modules['uvicorn'] = fake\n"
    )

    def test_partial_surface_refuses_to_serve(self):
        """A server that reaches startup with tools missing must exit, not
        serve. /_health is a static 200 handler that cannot see the registry,
        so a degraded server would otherwise pass every liveness probe."""
        with tempfile.TemporaryDirectory() as tmp:
            # Drop a tool from the registry just before startup checks it,
            # standing in for any path that leaves the surface incomplete.
            with open(os.path.join(tmp, "sitecustomize.py"), "w") as handle:
                handle.write(
                    "import sys, types\n"
                    "fake = types.ModuleType('uvicorn')\n"
                    "def run(app, **kw):\n"
                    "    raise AssertionError('served a partial tool surface')\n"
                    "fake.run = run\n"
                    "sys.modules['uvicorn'] = fake\n"
                    "import tool_capabilities\n"
                    "tool_capabilities.GRANTED_TOOLS = "
                    "tool_capabilities.GRANTED_TOOLS + ('never_registered',)\n"
                )
            proc = self._run_as_script(tmp, timeout=120)

        output = proc.stdout.decode()
        self.assertNotEqual(proc.returncode, 0, output)
        self.assertIn("never_registered", output)
        self.assertIn("Refusing to serve a partial tool surface", output)

    def _run_as_script(self, sitecustomize_dir, timeout=120):
        """Launch server.py the way the Dockerfile and start.sh do.

        The interpreter imports ``sitecustomize`` before running the script,
        which is how each test here stands in for uvicorn and observes — or
        perturbs — startup without editing the file under test.
        """
        env = dict(os.environ)
        for key in ("MCP_TRANSPORT", "AR_MANAGER_TOKENS",
                    "AR_MANAGER_TOKEN_FILE"):
            env.pop(key, None)
        env["MCP_TRANSPORT"] = "http"
        env["AR_MANAGER_TOKENS"] = self._TOKENS
        env["PYTHONPATH"] = os.pathsep.join(
            [sitecustomize_dir, _MANAGER_DIR,
             env.get("PYTHONPATH", "")]).rstrip(os.pathsep)
        return subprocess.run(
            [sys.executable, os.path.join(_MANAGER_DIR, "server.py")],
            cwd=_MANAGER_DIR, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
        )

    def test_script_mode_registers_every_tool(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "sitecustomize.py"), "w") as handle:
                handle.write(self._REPORTER)
            proc = self._run_as_script(tmp)
            output = proc.stdout.decode()
            reported = [line for line in output.splitlines()
                        if line.startswith("TOOLS:")]
            self.assertTrue(
                reported,
                "server.py never reached serve time as a script:\n" + output)

            served = set(reported[0][len("TOOLS:"):].split(","))
            self.assertEqual(
                set(server.mcp._tool_manager._tools), served,
                "the script entry point serves a different tool set than the "
                "module import registers")


class TestExtractOwnerRepo(unittest.TestCase):

    def test_https_url(self):
        result = server._extract_owner_repo("https://github.com/org/repo")
        self.assertEqual(result, ("org", "repo"))

    def test_https_url_with_git_suffix(self):
        result = server._extract_owner_repo("https://github.com/org/repo.git")
        self.assertEqual(result, ("org", "repo"))

    def test_ssh_url(self):
        result = server._extract_owner_repo("git@github.com:org/repo.git")
        self.assertEqual(result, ("org", "repo"))

    def test_invalid_url(self):
        result = server._extract_owner_repo("not-a-url")
        self.assertIsNone(result)

class TestToolRegistration(unittest.TestCase):

    def test_no_duplicate_tool_names(self):
        """Verify all @mcp.tool() functions have unique names."""
        tool_names = []
        for tool in server.mcp._tool_manager._tools.values():
            tool_names.append(tool.name)
        self.assertEqual(len(tool_names), len(set(tool_names)),
                         f"Duplicate tool names: {tool_names}")

    def test_controller_update_config_registered(self):
        """The new controller_update_config tool must be registered."""
        tools = server.mcp._tool_manager._tools
        self.assertIn("controller_update_config", tools)

    def test_expected_tool_count(self):
        """Verify all tools are registered."""
        tools = server.mcp._tool_manager._tools
        expected = {
            "controller_health",
            "controller_update_config",
            "agent_options",
            "workstream_list",
            "workstream_get_status",
            "workstream_get_job",
            "workstream_submit_task",
            "workstream_register",
            "workstream_update_config",
            "workspace_update_config",
            "workstream_archive",
            "workstream_archive_many",
            "workstream_introspect",
            "workstream_unarchive_many",
            "workstream_unarchive",
            "workstream_delete",
            "project_create_branch",
            "project_verify_branch",
            "project_commit_plan",
            "project_read_plan",
            "memory_recall",
            "memory_namespaces",
            "consult",
            "workstream_context",
            "memory_store",
            "send_message",
            "github_pr_find",
            "github_pr_review_comments",
            "github_pr_conversation",
            "github_pr_reply",
            "github_list_open_prs",
            "github_create_pr",
            "github_request_copilot_review",
            "github_read_file",
            "github_pr_check_status",
            "github_list_workflow_runs",
            "github_workflow_run_status",
            "tracker_list_projects",
            "tracker_create_project",
            "tracker_update_project",
            "tracker_delete_project",
            "tracker_list_releases",
            "tracker_create_release",
            "tracker_update_release",
            "tracker_delete_release",
            "tracker_create_task",
            "tracker_get_task",
            "tracker_list_tasks",
            "tracker_update_task",
            "tracker_delete_task",
            "tracker_search_tasks",
            "tracker_project_summary",
            "workspace_secret_list_names",
            "workspace_secret_render_file",
        }
        registered = set(tools.keys())
        missing = expected - registered
        extra = registered - expected
        self.assertFalse(missing, f"Missing tools: {missing}")
        self.assertFalse(extra, f"Unexpected tools: {extra}")

    def test_completion_listeners_field_parsed(self):
        """The completion_listeners parameter must be parsed and forwarded to the controller.

        The completion-listener feature depends on this parameter being
        correctly parsed from the comma-separated / JSON-array string
        shape, and the parsed value being forwarded under the
        ``completionListeners`` key in the controller request body.
        Without this, the listener field is silently dropped and the
        workstream has no wake-up fan-out.
        """
        _grant_all_scopes()
        with patch.object(server, "_controller_post",
                          return_value={"ok": True, "workstreamId": "ws-x"}) as mock_post:
            result = server.workstream_register(
                default_branch="feature/completion-listeners",
                completion_listeners="ws-orchestrator,ws-orchestrator-2",
            )
            self.assertTrue(result.get("ok"), result)
            self.assertEqual(1, mock_post.call_count)
            url, body = mock_post.call_args[0]
            self.assertEqual("/api/workstreams", url)
            listeners = body.get("completionListeners")
            self.assertEqual(["ws-orchestrator", "ws-orchestrator-2"],
                             listeners)

    def test_completion_listeners_empty_string_no_field(self):
        """An empty completion_listeners value must NOT forward the field.

        The controller distinguishes "no change" (field omitted) from
        "set to empty" (passed an empty list). For the register path
        the only sensible interpretation is "no listeners" — the
        inert default — so an empty input produces no payload entry.
        """
        _grant_all_scopes()
        with patch.object(server, "_controller_post",
                          return_value={"ok": True, "workstreamId": "ws-y"}) as mock_post:
            result = server.workstream_register(
                default_branch="feature/no-listeners",
                completion_listeners="",
            )
            self.assertTrue(result.get("ok"), result)
            body = mock_post.call_args[0][1]
            self.assertNotIn("completionListeners", body)

    def test_completion_listeners_json_array_parsed(self):
        """A JSON-array string for completion_listeners is accepted."""
        _grant_all_scopes()
        with patch.object(server, "_controller_post",
                          return_value={"ok": True, "workstreamId": "ws-z"}) as mock_post:
            result = server.workstream_register(
                default_branch="feature/json-listeners",
                completion_listeners='["ws-a", "ws-b"]',
            )
            self.assertTrue(result.get("ok"), result)
            body = mock_post.call_args[0][1]
            self.assertEqual(["ws-a", "ws-b"], body.get("completionListeners"))

class TestOrgScopeGate(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_org_map_derived_from_workstream_list(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/Plytrix/plytrix-platform.git"},
        ]
        self.assertEqual({"TAAA"}, server._workspaces_for_org("almostrealism"))
        self.assertEqual({"TBBB"}, server._workspaces_for_org("Plytrix"))
        self.assertEqual(set(), server._workspaces_for_org("other-org"))

    @patch.object(server, "_controller_get")
    def test_org_spanning_multiple_workspaces_tracks_all(self, mock_get):
        # Same org registered under two workspaces → both must appear so
        # _require_org_in_scope can detect the ambiguity.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:shared-org/repo-a.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "git@github.com:shared-org/repo-b.git"},
        ]
        self.assertEqual({"TAAA", "TBBB"}, server._workspaces_for_org("shared-org"))

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_unscoped_passes(self, mock_get):
        mock_get.return_value = []
        server._require_org_in_scope("any-org")  # no raise

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_scoped_accepts_in_scope(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        _set_workspaces("TAAA")
        server._require_org_in_scope("almostrealism")  # no raise

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_scoped_rejects_out_of_scope(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/Plytrix/plytrix-platform.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server._require_org_in_scope("Plytrix")

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_scoped_rejects_unknown_org(self, mock_get):
        # An org with no workstream on any workspace is treated as unknown
        # and therefore out-of-scope for scoped tokens.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server._require_org_in_scope("some-untracked-org")

    @patch.object(server, "_controller_get")
    def test_require_org_in_scope_rejects_ambiguous_multi_workspace_org(self, mock_get):
        # Same org under two workspaces: even when the caller's scope
        # contains ONE of them, direct-org addressing must be denied
        # because the controller's per-org PAT is last-wins and the
        # proxy may end up using the other workspace's token.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:shared-org/repo-a.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "git@github.com:shared-org/repo-b.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError) as ctx:
            server._require_org_in_scope("shared-org")
        self.assertIn("multiple", str(ctx.exception).lower())
