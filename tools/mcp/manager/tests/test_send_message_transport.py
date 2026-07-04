"""Real-transport regression tests for the per-request token decode path
and the send_message loud-fail guard.

The existing test suite for ``_decode_current_request_token_full`` and
``send_message`` uses ``MagicMock`` to stand in for the FastMCP context.
Those tests prove the decode logic given a faked bearer header, but they
do NOT prove the bearer header actually reaches the decode function
under a real FastMCP streamable-HTTP transport — which is the precise
gap that the opencode ``send_message`` top-level post bug exploited.
The prior fix in ``feature/send-message-token-context`` (the
``OpencodeConfigBuilder.translateMcpServers`` ``oauth: false`` change)
unblocked the bearer at the client side, but the test that was meant
to guard the server side passed only because the test faked the
context.

This file's tests construct the real ASGI stack the production server
uses: a :class:`BearerAuthMiddleware` wrapping the FastMCP
``streamable_http_app()`` produced by the live ``mcp`` module. They
then drive a real HTTP request — initialize, then tools/call, reusing
the session id when the transport is stateful — and assert that the
tool handler sees the bearer via the per-request propagation path
that ``ServerMessageMetadata`` provides. If the FastMCP version ever
stops propagating the request, or the propagation is conditional on
a transport mode that production does not actually use, these tests
fail. That is the anti-regression.

The second class in this file covers the Part 2 loud-fail guard: when
the caller passes neither ``workstream_id`` nor ``job_id`` and the
resolution paths return empty for both, ``send_message`` must return
``ok=false`` with a clear error rather than silently posting to the
workstream top-level. The earlier warning log
``send_message_missing_job_id`` is now backed by an actual non-ok
return so the agent sees the failure.
"""

import asyncio
import json
import os
import socket
import sys
import threading
import time
import unittest
import uuid
from contextlib import contextmanager
from unittest.mock import patch

import httpx


_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

# The real-transport tests mint HMAC temp tokens against the shared
# secret and drive a real FastMCP streamable-HTTP server. The HMAC
# path uses ``server.SHARED_SECRET`` (a module-level constant loaded
# at import time from ``AR_MANAGER_SHARED_SECRET``). The conftest
# hook only fires under pytest; the CI runner (``python3 -m unittest
# discover -v -s tools/mcp/manager -p "test_*.py"``) does not load
# conftest.py at all. We therefore re-bind the two module-level
# constants this test needs directly on the imported ``server``
# module so the test thread and the uvicorn server thread (which
# share the same module object) agree on the values. We do NOT
# re-import the module — a re-import would create a second module
# object, leaving earlier test files' ``import server`` reference
# pointing at the old module and breaking their ``@patch("server.urlopen")``
# mocks (which resolve ``server`` via ``sys.modules`` at call time
# and would target a different module object than the one whose
# ``_controller_post`` is actually being called).
_TEST_SHARED_SECRET = "real-transport-test-secret"
_TEST_CONTROLLER_URL = "http://127.0.0.1:1"

import server  # noqa: E402  (intentional: module is loaded by sibling test files first)

# Overwrite the constants this test cares about. ``SHARED_SECRET`` is
# read by both ``_mint_temp_token`` and ``_validate_temp_token`` on
# every call (not memoised at the call site), and ``CONTROLLER_URL``
# is interpolated into the URL string at call time, so the rebind
# takes effect for any subsequent call regardless of which thread
# runs it.
server.SHARED_SECRET = _TEST_SHARED_SECRET
server.CONTROLLER_URL = _TEST_CONTROLLER_URL


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


# NOTE: the previous module-level ``_build_mcp_app()`` helper was
# removed: no test class in this file calls it. Each of the two
# real-transport test classes (``TestRealTransportPerRequestDecode``,
# ``TestRealTransportSendMessageLoudFail``) builds its own FastMCP
# instance via a class-level ``_build_app()`` static method so the
# test gets a fresh mcp instance per case and the per-request /
# loud-fail paths can be exercised independently. The old helper also
# registered a tool named ``call_send_message`` while
# ``_send_send_message_tool_call()`` looks for ``invoke_send_message``,
# so even if a future test wanted to reuse it the helper would fail
# at runtime with a "tool not found" error. Keeping it around as dead
# code invited a misleading future wiring-up.


async def _start_uvicorn(app, port, ready_event):
    import uvicorn
    config = uvicorn.Config(
        app, host="127.0.0.1", port=port, log_level="warning", lifespan="on")
    srv = uvicorn.Server(config)
    task = asyncio.create_task(srv.serve())
    deadline = asyncio.get_event_loop().time() + 10
    while asyncio.get_event_loop().time() < deadline:
        if srv.started:
            break
        await asyncio.sleep(0.05)
    if not srv.started:
        raise RuntimeError("server did not start")
    ready_event.set()
    try:
        await task
    except asyncio.CancelledError:
        srv.should_exit = True
        raise


@contextmanager
def _running_server(app, *, port=None):
    """Spin up a uvicorn-served FastMCP app for the duration of the
    test. The app is the production-shaped ASGI chain (FastMCP
    streamable_http_app wrapped in BearerAuthMiddleware). The caller
    is responsible for building the app with the right transport
    mode (stateless_http setting must be applied BEFORE
    streamable_http_app() is called — see the test code).

    The server runs in a dedicated background thread with its own
    event loop so it does not interfere with the unittest test
    runner's loop. The thread is joined before the context manager
    returns, so port reuse between tests is safe."""
    if port is None:
        port = _free_port()

    ready_holder = {}
    error_holder = {}

    def runner():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        ready_holder["loop"] = loop
        ready = asyncio.Event()

        async def run():
            try:
                await _start_uvicorn(app, port, ready)
            except Exception as e:  # pragma: no cover
                error_holder["error"] = e
        try:
            loop.run_until_complete(run())
        finally:
            try:
                loop.close()
            except Exception:
                pass

    thread = threading.Thread(target=runner, daemon=True)
    thread.start()
    # Wait for the server to become reachable on the port. The
    # event-loop wakeup is asynchronous so we poll the port from
    # this thread; the actual uvicorn startup is driven by the
    # background thread.
    import socket as _socket
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if "error" in error_holder:
            raise error_holder["error"]
        try:
            with _socket.create_connection(("127.0.0.1", port), timeout=0.5):
                break
        except OSError:
            time.sleep(0.1)
    else:
        raise RuntimeError("server did not start listening in time")
    try:
        yield port
    finally:
        # Signal the server to stop by cancelling the run task and
        # shutting down the loop. We do this by closing the loop
        # from inside the thread, which causes uvicorn's serve to
        # return. The daemon thread will exit shortly after.
        loop = ready_holder.get("loop")
        if loop is not None and not loop.is_closed():
            try:
                loop.call_soon_threadsafe(loop.stop)
            except RuntimeError:
                pass
        thread.join(timeout=5)


def _send_initialize_and_tool_call(port, token_value, tool_name, *,
                                   stateful=False):
    """Send initialize + tools/call(<tool_name>) and return the parsed
    JSON-RPC result body as a dict, plus the session id (or None in
    stateless mode)."""
    base = f"http://127.0.0.1:{port}/"
    headers = {
        "Authorization": f"Bearer {token_value}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    init_req = {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "real-transport-test", "version": "0.0.1"},
        },
    }
    tool_req = {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": tool_name, "arguments": {}},
    }
    with httpx.Client(timeout=10.0) as client:
        r1 = client.post(base, json=init_req, headers=headers)
        r1.raise_for_status()
        sid = r1.headers.get("mcp-session-id")
        h2 = dict(headers)
        if sid and stateful:
            h2["mcp-session-id"] = sid
        r2 = client.post(base, json=tool_req, headers=h2)
        r2.raise_for_status()
        return _extract_tool_result(r2.text), sid


def _extract_tool_result(sse_text):
    """Parse the SSE response and pull the JSON-RPC result body."""
    last = None
    for line in sse_text.splitlines():
        if line.startswith("data:"):
            last = line[len("data:"):].strip()
    if last is None:
        raise AssertionError(f"no data: line in response: {sse_text!r}")
    payload = json.loads(last)
    if "error" in payload:
        raise AssertionError(f"JSON-RPC error: {payload['error']}")
    result = payload["result"]
    # FastMCP wraps the tool's dict in a TextContent block.
    text = result["content"][0]["text"]
    return json.loads(text)


def _send_send_message_tool_call(port, token_value, *, stateful=False,
                                 text="hello from real transport"):
    """Drive the ``invoke_send_message`` tool (which wraps
    ``server.send_message``) through the real transport and return
    the tool's return value as a dict."""
    base = f"http://127.0.0.1:{port}/"
    headers = {
        "Authorization": f"Bearer {token_value}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    init_req = {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "real-transport-test", "version": "0.0.1"},
        },
    }
    tool_req = {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {
            "name": "invoke_send_message",
            "arguments": {"text": text},
        },
    }
    with httpx.Client(timeout=10.0) as client:
        r1 = client.post(base, json=init_req, headers=headers)
        r1.raise_for_status()
        sid = r1.headers.get("mcp-session-id")
        h2 = dict(headers)
        if sid and stateful:
            h2["mcp-session-id"] = sid
        r2 = client.post(base, json=tool_req, headers=h2)
        r2.raise_for_status()
        text_blob = None
        for line in r2.text.splitlines():
            if line.startswith("data:"):
                text_blob = line[len("data:"):].strip()
        if text_blob is None:
            raise AssertionError(f"no data: line in response: {r2.text!r}")
        payload = json.loads(text_blob)
        result = payload["result"]
        tool_text = result["content"][0]["text"]
        return json.loads(tool_text)


class TestRealTransportPerRequestDecode(unittest.TestCase):
    """Anti-regression: the bearer the client sent on a real HTTP
    request must reach the per-request decode function in both
    ``stateless_http`` and the default stateful mode. This is the
    path the opencode ``send_message`` fix depends on, and the test
    the prior fix (PR #286) did not include because mocking
    ``mcp.get_context`` does not exercise the transport."""

    def setUp(self):
        # The per-request decode tests mint a temp token, which
        # BearerAuthMiddleware then has to resolve to a Slack workspace
        # via the controller's /api/workstreams endpoint. The fail-closed
        # workspace_map fix in PR #332 means an unreachable controller
        # no longer silently downgrades to legacy/single-workspace mode,
        # so we must hand the middleware a mocked controller response
        # that maps the test workstreams to a workspace before sending
        # the request. Also reset the workspace cache so a previous
        # test's state cannot leak into the per-request decode path.
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["org_map"] = None
        server._workspace_map_cache["fetched"] = 0.0
        server._workspace_map_cache["fetch_ok_ever"] = False

    def tearDown(self):
        server._workspace_map_cache["map"] = None
        server._workspace_map_cache["org_map"] = None
        server._workspace_map_cache["fetched"] = 0.0
        server._workspace_map_cache["fetch_ok_ever"] = False

    @staticmethod
    def _build_app(*, stateless):
        from mcp.server.fastmcp import FastMCP

        mcp_instance = FastMCP(f"rt-{uuid.uuid4().hex[:8]}")
        mcp_instance.settings.streamable_http_path = "/"
        # CRITICAL: stateless_http must be set BEFORE streamable_http_app()
        # is called — the session manager is created lazily on the
        # first call to that method, and the setting is read once.
        mcp_instance.settings.stateless_http = stateless

        @mcp_instance.tool()
        def probe() -> dict:
            ws, job, label, reason = server._decode_current_request_token_full()
            return {
                "per_request_ws": ws,
                "per_request_job": job,
                "per_request_label": label,
                "per_request_reason": reason,
            }

        app = mcp_instance.streamable_http_app()
        tokens = [{
            "value": "rt-admin",
            "scopes": ["read", "write", "submit"],
            "label": "rt-admin",
        }]
        return server.BearerAuthMiddleware(
            app, tokens, issuer_url=None)

    def test_stateless_mode_resolves_temp_token_via_per_request_path(self):
        token = server._mint_temp_token("ws-RT", "job-RT", ttl_seconds=60)
        app = self._build_app(stateless=True)
        # Map the test workstream to a real workspace so the fail-closed
        # auth path accepts the temp token (otherwise an unreachable
        # controller would reject the request with 401 before the per-
        # request decode ever runs).
        with patch.object(server, "_controller_get",
                          return_value=[{"workstreamId": "ws-RT",
                                         "slackWorkspaceId": "T-RT"}]):
            with _running_server(app) as port:
                result, _ = _send_initialize_and_tool_call(
                    port, token, "probe", stateful=False)
        self.assertEqual(result["per_request_ws"], "ws-RT",
                         msg=f"per-request decode did not see the temp token bearer; got: {result}")
        self.assertEqual(result["per_request_job"], "job-RT",
                         msg=f"per-request decode did not see the temp token bearer; got: {result}")
        self.assertEqual(result["per_request_reason"], "ok",
                         msg=f"per-request decode did not return ok; got: {result}")

    def test_stateful_mode_resolves_temp_token_via_per_request_path(self):
        token = server._mint_temp_token("ws-RT-2", "job-RT-2", ttl_seconds=60)
        app = self._build_app(stateless=False)
        with patch.object(server, "_controller_get",
                          return_value=[{"workstreamId": "ws-RT-2",
                                         "slackWorkspaceId": "T-RT-2"}]):
            with _running_server(app) as port:
                result, sid = _send_initialize_and_tool_call(
                    port, token, "probe", stateful=True)
        # Stateful transport must have handed us a session id that
        # the second request reuses; if it did not, the assertion
        # below would not be exercising the per-session stateful
        # dispatch path the opencode client uses.
        self.assertIsNotNone(sid,
                             msg="stateful transport did not return a session id")
        self.assertEqual(result["per_request_ws"], "ws-RT-2",
                         msg=f"per-request decode did not see the temp token bearer in stateful mode; got: {result}")
        self.assertEqual(result["per_request_job"], "job-RT-2",
                         msg=f"per-request decode did not see the temp token bearer in stateful mode; got: {result}")
        self.assertEqual(result["per_request_reason"], "ok",
                         msg=f"per-request decode did not return ok in stateful mode; got: {result}")


class TestRealTransportSendMessageLoudFail(unittest.TestCase):
    """Anti-regression: when ``send_message`` cannot resolve a job_id
    (token decode failed AND no explicit args were passed), the tool
    must return ``ok=false`` with a clear error rather than silently
    posting at the workstream top-level. The earlier
    ``send_message_missing_job_id`` warning log was invisible to the
    caller; the loud-fail surfaces it in the response so the agent
    can act on it.

    These tests drive the real FastMCP streamable-HTTP transport.
    The bearer used is a STATIC admin token, which exercises the
    "no workstream/job binding resolvable, caller didn't ask for one
    explicitly" path the loud-fail must catch. Even when the
    transport propagates the bearer correctly (per
    :class:`TestRealTransportPerRequestDecode` above), a static
    admin bearer is not a temp token so the per-request decode
    returns ``not_temp_token`` and the first guard at the top of
    ``send_message`` triggers — the call is refused with a
    workstream-required error and the loud-fail second-guard
    never needs to fire. The point of the test is to confirm the
    first guard's behaviour survives end-to-end on the real
    transport: no silent top-level post, controller never called.
    """

    @staticmethod
    def _build_app(*, stateless):
        from mcp.server.fastmcp import FastMCP

        mcp_instance = FastMCP(f"sf-{uuid.uuid4().hex[:8]}")
        mcp_instance.settings.streamable_http_path = "/"
        mcp_instance.settings.stateless_http = stateless

        @mcp_instance.tool()
        def invoke_send_message(text: str = "hello") -> dict:
            # Strip scope check by pre-granting, since the test
            # passes a static admin bearer.
            server._set_scopes(
                ["read", "write", "submit", "github",
                 "memory-read", "memory-write"],
                label="rt-admin")
            return server.send_message(text=text)

        app = mcp_instance.streamable_http_app()
        tokens = [{
            "value": "rt-admin",
            "scopes": ["read", "write", "submit", "github",
                       "memory-read", "memory-write"],
            "label": "rt-admin",
        }]
        return server.BearerAuthMiddleware(
            app, tokens, issuer_url=None)

    def test_static_admin_bearer_with_no_explicit_args_returns_clear_error(self):
        """A static admin bearer (no workstream/job binding at all)
        calling ``send_message`` with only text must return
        ``ok=false`` — the caller is not in a job session so threading
        is not expected, but the call must still be refused rather
        than silently posting to a workstream that was never bound.

        The error message names both ``workstream_id`` and the
        temp-token path so a future reader of the test can tell which
        guard fired and recover by either passing the explicit arg or
        switching to a job-session token."""
        app = self._build_app(stateless=True)
        with _running_server(app) as port:
            with patch.object(server, "_controller_post") as mock_post:
                result = _send_send_message_tool_call(
                    port, "rt-admin", stateful=False,
                    text="operator wants to broadcast")
        self.assertFalse(result["ok"],
                         msg=f"send_message should have refused the call; got: {result}")
        self.assertIn("workstream_id", result["error"],
                      msg=f"error should mention workstream_id; got: {result}")
        # Crucially: the controller was NOT called. The guard
        # short-circuits before any HTTP write so a fake-success
        # response never reaches the agent.
        mock_post.assert_not_called()

    def test_stateful_mode_loud_fail_short_circuits_before_controller(self):
        """Same as the stateless variant, but driving the default
        stateful transport to confirm the guard is reached on the
        per-session dispatch path the opencode client uses. The
        static admin bearer means the per-request decode returns
        ``not_temp_token`` — the only resolution path that would
        succeed is the explicit-arg path, which the caller did not
        take."""
        app = self._build_app(stateless=False)
        with _running_server(app) as port:
            with patch.object(server, "_controller_post") as mock_post:
                result = _send_send_message_tool_call(
                    port, "rt-admin", stateful=True,
                    text="operator wants to broadcast in stateful mode")
        self.assertFalse(result["ok"],
                         msg=f"send_message should have refused the call; got: {result}")
        mock_post.assert_not_called()


class TestSendMessageLoudFailOnPartialResolution(unittest.TestCase):
    """The loud-fail second-guard is the production bug fix: when
    the per-request decode fails (e.g., the FastMCP stateful-transport
    request-propagation hazard) AND every fallback path also leaves
    the job_id empty, the tool must return ``ok=false`` with a
    recoverable error rather than silently posting at the
    workstream top-level. The earlier
    ``send_message_missing_job_id`` warning was invisible to the
    caller; the loud-fail surfaces it in the response so the agent
    can act.

    This class drives the loud-fail second-guard via a focused
    unit-level scenario: a temp-token bearer is sent (so the bearer
    IS the expected shape for a job session) but the per-request
    decode is forced to return ``no_request`` (the FastMCP hazard).
    The audit logged ``send_message_unthreaded`` evidence line is
    also captured. The transport-level coverage of the happy-path
    decode lives in :class:`TestRealTransportPerRequestDecode`
    above; this class is the matching coverage for the second-guard.
    """

    def setUp(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        for attr in ("workstream_id", "job_id", "scopes", "token_label",
                     "workspace_scopes"):
            if hasattr(server._thread_local, attr):
                delattr(server._thread_local, attr)
        server._set_scopes(
            ["read", "write", "submit", "github",
             "memory-read", "memory-write"],
            label="test")

    def tearDown(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        for attr in ("workstream_id", "job_id", "scopes", "token_label",
                     "workspace_scopes"):
            if hasattr(server._thread_local, attr):
                delattr(server._thread_local, attr)

    def test_loud_fail_when_per_request_decode_fails_and_ctx_empty(self):
        """The diagnostic scenario: a valid temp-token bearer is on
        the wire (so the agent expects auto-resolved threading), the
        per-request decode path is broken (returns ``no_request`` /
        ``no_context`` — the FastMCP stateful hazard), and neither
        the ContextVar nor the thread-local carry a fallback. The
        tool must refuse the call rather than silently posting at
        top level.

        The loud-fail second-guard is reached when the workstream
        resolves from a system source but the job does not. That
        only happens if a partial resolution leaks in (e.g. a
        legacy middleware that set only the workstream, or a future
        FastMCP version that propagates only the header but not the
        full token context). The test sets up that partial state
        via the ContextVar so the second-guard is exercised
        directly.
        """
        # The previous ``with patch.object(server, "_mint_temp_token")``
        # block was a no-op: it exited before any code that could
        # trigger the mint, and the actual ``server.send_message`` call
        # below runs AFTER the ``with`` block is closed, so the patch
        # was already undone. The mint function is never reached on
        # this test path (per-request decode returns ``no_context``
        # and there is no caller-bound workstream to mint a token
        # for), so removing the patch is the correct fix. The
        # behaviour the test exercises is the loud-fail second-guard
        # under partial-resolution, not token minting.
        # Force the per-request path to fail AND seed the
        # ContextVar with only the workstream half of the pair, to
        # simulate a partial-resolution leak (the production
        # shape the loud-fail second-guard was added for).
        server._request_workstream_id.set("ws-PARTIAL")
        server._request_job_id.set("")
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            with patch.object(server, "_controller_post") as mock_post:
                with self.assertLogs("ar-manager.audit", level="ERROR") as audit:
                    result = server.send_message(text="hello")
        self.assertFalse(result["ok"],
                         msg=f"loud-fail should have refused the call; got: {result}")
        self.assertIn("job_id", result["error"],
                      msg=f"error should mention job_id; got: {result}")
        self.assertIn("workstream_id", result["error"],
                      msg=f"error should mention workstream_id; got: {result}")
        # The error must surface the per-request decode reason so
        # the caller can tell whether the failure was the
        # FastMCP-transport hazard or a token-shape mismatch.
        self.assertEqual(result.get("per_request_decode_reason"), "no_context",
                         msg=f"loud-fail should expose the decode reason; got: {result}")
        # The loud-fail must NOT have made the controller call. The
        # tool returns BEFORE the URL is built and BEFORE the POST.
        mock_post.assert_not_called()
        # The unthreaded audit line was emitted at ERROR level so
        # operators see it without needing to grep through info-level
        # log noise.
        self.assertTrue(
            any("send_message_unthreaded" in line for line in audit.output),
            msg=f"expected send_message_unthreaded ERROR audit line; got: {audit.output}")

    def test_loud_fail_does_not_fire_when_explicit_workstream_id_given(self):
        """A caller who passes an explicit ``workstream_id`` is
        signalling they want a workstream top-level post. The
        loud-fail must NOT trip in that case; the existing
        workstream-only path is the documented behaviour. (The
        controller URL still drops the ``/jobs/`` segment, which is
        how the caller asked for it.)"""
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            with patch.object(server, "_controller_post") as mock_post:
                mock_post.return_value = {"ok": True}
                result = server.send_message(
                    text="admin broadcast",
                    workstream_id="ws-ADMIN")
        self.assertTrue(result["ok"],
                        msg=f"explicit-arg path should succeed; got: {result}")
        # Verify the URL is the workstream-level path (no /jobs/).
        called_path = mock_post.call_args[0][0]
        self.assertIn("/api/workstreams/ws-ADMIN/messages", called_path)
        self.assertNotIn("/jobs/", called_path)


class TestDecodeCurrentRequestTokenFallback(unittest.TestCase):
    """The defensive ``ctx_fallback`` / ``tl_fallback`` paths in
    :func:`server._decode_current_request_token_full` are reached
    when the per-request propagation is unavailable — the
    FastMCP-transport hazard the production bug is built around.
    Reaching the fallback is itself diagnostic evidence that the
    per-request path is broken; the reason slot
    (``ctx_fallback`` / ``tl_fallback``) is the audit hook. The
    tests below verify each path resolves the (workstream, job)
    pair correctly, and that the all-or-nothing invariant on
    (workstream, job) is preserved: a half-set ContextVar must
    not return a half-resolved answer."""

    def setUp(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        for attr in ("workstream_id", "job_id"):
            if hasattr(server._thread_local, attr):
                delattr(server._thread_local, attr)

    def tearDown(self):
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        for attr in ("workstream_id", "job_id"):
            if hasattr(server._thread_local, attr):
                delattr(server._thread_local, attr)

    def test_ctx_fallback_resolves_when_per_request_path_fails(self):
        server._request_workstream_id.set("ws-FB")
        server._request_job_id.set("job-FB")
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            ws, job, label, reason = (
                server._decode_current_request_token_full())
        self.assertEqual(ws, "ws-FB",
                         msg=f"ctx_fallback should have returned the ctx workstream; got ({ws!r}, {job!r}, {reason!r})")
        self.assertEqual(job, "job-FB")
        self.assertEqual(reason, "ctx_fallback",
                         msg=f"expected ctx_fallback reason; got {reason!r}")

    def test_tl_fallback_when_per_request_and_ctx_both_unavailable(self):
        if hasattr(server._thread_local, "workstream_id"):
            del server._thread_local.workstream_id
        if hasattr(server._thread_local, "job_id"):
            del server._thread_local.job_id
        server._thread_local.workstream_id = "ws-TL"
        server._thread_local.job_id = "job-TL"
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            ws, job, label, reason = (
                server._decode_current_request_token_full())
        self.assertEqual(ws, "ws-TL",
                         msg=f"tl_fallback should have returned the tl workstream; got ({ws!r}, {job!r}, {reason!r})")
        self.assertEqual(job, "job-TL")
        self.assertEqual(reason, "tl_fallback",
                         msg=f"expected tl_fallback reason; got {reason!r}")

    def test_half_set_ctx_does_not_emit_partial_answer(self):
        # If the ContextVar is half-set (workstream without job), the
        # decode must NOT return a half-resolved tuple — that would
        # be exactly the production failure shape. The
        # all-or-nothing invariant must hold: either both fields are
        # set or both are None.
        server._request_workstream_id.set("ws-PARTIAL")
        server._request_job_id.set("")
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            ws, job, label, reason = (
                server._decode_current_request_token_full())
        self.assertIsNone(ws,
                          msg=f"half-set ctx must not produce a half-resolved answer; got ({ws!r}, {job!r}, {reason!r})")
        self.assertIsNone(job)
        self.assertEqual(reason, "no_context",
                         msg=f"expected no_context on half-set ctx; got {reason!r}")


if __name__ == "__main__":
    unittest.main()
