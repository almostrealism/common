"""Regression tests for the workspace-map fail-open fixed in
``workspace_map.py``.

Prior behaviour: ``_refresh_workspace_map`` treated any non-list response
from ``/api/workstreams`` — including the ``{"ok": False, "error": ...}``
dict that ``controller_client._controller_get`` returns for an unreachable
or erroring controller — as "an empty workstream list". ``_get_cached_maps``
then wrote that empty map into the cache unconditionally, even when a
previous fetch had populated it with real data. An empty map flips
``_is_multi_workspace_mode()`` to ``False``, and ``BearerAuthMiddleware``
only rejects a temp-token request whose workstream cannot be resolved to a
workspace when multi-workspace mode is detected — so a controller outage
would silently downgrade every unresolvable temp-token request to
"unscoped" (full access) instead of being rejected. That is a fail-OPEN on
controller unreachability.

These tests prove, against the real ``BearerAuthMiddleware.__call__`` ASGI
entry point (not an intermediate flag), that:

1. A controller fetch failure does not clobber a previously-populated
   cache — a temp token for a workstream resolved before the outage is
   still correctly scoped after the outage begins.
2. A temp token for a workstream that has never been resolved is REJECTED
   (fails closed) when the controller cannot be queried, rather than
   being treated as unscoped.
3. The pre-existing legacy (single-workspace) behaviour — an unscoped
   temp-token request is accepted when the controller is reachable and
   reports no workspace-bound workstreams at all — is unchanged.
"""

import asyncio
import os
import sys
import unittest
from unittest.mock import patch

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://127.0.0.1:1"}):
    import server

_CONTROLLER_UNREACHABLE = {
    "ok": False,
    "error": "Controller unreachable: [Errno 111] Connection refused",
}


def _reset_workspace_cache():
    """Reset the workspace-map cache to a cold, never-fetched state."""
    server._workspace_map_cache["map"] = None
    server._workspace_map_cache["org_map"] = None
    server._workspace_map_cache["fetched"] = 0.0
    server._workspace_map_cache["fetch_ok_ever"] = False


def _clear_request_scope_state():
    """Clear per-request auth state left on the test thread by a prior
    ``BearerAuthMiddleware`` invocation, so tests don't leak state."""
    for attr in ("scopes", "token_label", "workspace_scopes",
                 "workstream_id", "job_id"):
        if hasattr(server._thread_local, attr):
            delattr(server._thread_local, attr)


async def _drive_middleware(tokens, bearer_token, path="/mcp"):
    """Send a single POST request through a real ``BearerAuthMiddleware``
    instance and return ``(app_invoked, sent_messages)``.

    ``app_invoked`` proves the real auth decision (the request reached the
    wrapped application) rather than an intermediate helper flag.
    """
    invoked = {"value": False}

    async def app(_scope, _receive, _send):
        invoked["value"] = True
        await _send({"type": "http.response.start", "status": 200, "headers": []})
        await _send({"type": "http.response.body", "body": b"{}"})

    sent = []

    async def send(message):
        sent.append(message)

    async def receive():
        return {"type": "http.disconnect"}

    scope = {
        "type": "http",
        "path": path,
        "method": "POST",
        "headers": [(b"authorization", f"Bearer {bearer_token}".encode("utf-8"))],
    }

    middleware = server.BearerAuthMiddleware(app=app, tokens=tokens)
    await middleware(scope, receive, send)
    return invoked["value"], sent


class TestWorkspaceMapFailClosed(unittest.TestCase):

    def setUp(self):
        _reset_workspace_cache()
        _clear_request_scope_state()
        self._orig_secret = server.SHARED_SECRET
        server.SHARED_SECRET = "test-workspace-map-failclosed-secret"

    def tearDown(self):
        _reset_workspace_cache()
        _clear_request_scope_state()
        server.SHARED_SECRET = self._orig_secret

    # -- (a) fetch failure must not clobber a previously-good cache -------

    @patch.object(server, "_controller_get")
    def test_fetch_failure_does_not_clobber_populated_cache(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-1", "slackWorkspaceId": "TAAA"},
        ]
        self.assertEqual("TAAA", server._workspace_for_workstream("ws-1"))

        # Force the cache stale so the next lookup attempts a refresh, and
        # make that refresh fail the way an unreachable controller would.
        server._workspace_map_cache["fetched"] = 0.0
        mock_get.return_value = _CONTROLLER_UNREACHABLE

        self.assertEqual(
            "TAAA", server._workspace_for_workstream("ws-1"),
            "a failed refresh must preserve the last-known-good mapping, "
            "not clobber it with an empty map")
        self.assertTrue(
            server._is_multi_workspace_mode(),
            "multi-workspace mode must still be detected from the "
            "preserved cache after a failed refresh")

    def test_real_auth_decision_uses_last_known_good_cache_after_outage(self):
        """The full BearerAuthMiddleware decision — not just the cache
        helpers — must keep scoping a temp token correctly after the
        controller becomes unreachable, using the last-known-good map."""
        with patch.object(server, "_controller_get",
                          return_value=[{"workstreamId": "ws-1",
                                         "slackWorkspaceId": "TAAA"}]):
            # Warm the cache with a real, successful fetch.
            self.assertEqual("TAAA", server._workspace_for_workstream("ws-1"))

        server._workspace_map_cache["fetched"] = 0.0  # force next lookup stale

        with patch.object(server, "_controller_get",
                          return_value=_CONTROLLER_UNREACHABLE):
            token = server._mint_temp_token("ws-1", "job-z", ttl_seconds=60)
            self.assertIsNotNone(token)
            invoked, sent = asyncio.run(_drive_middleware([], token))

        self.assertTrue(
            invoked, "request for a previously-resolved workstream must "
            "still be let through despite the concurrent controller outage")
        self.assertEqual(["TAAA"], server._get_workspace_scopes())

    # -- (b) unresolved workstream must fail closed when unreachable ------

    def test_unresolved_temp_token_rejected_when_controller_unreachable(self):
        """A temp token for a workstream that has never been resolved must
        be REJECTED — not silently treated as unscoped — when the
        controller cannot be queried at all."""
        with patch.object(server, "_controller_get",
                          return_value=_CONTROLLER_UNREACHABLE):
            token = server._mint_temp_token("ws-never-registered", "job-x",
                                            ttl_seconds=60)
            self.assertIsNotNone(token)
            invoked, sent = asyncio.run(_drive_middleware([], token))

        self.assertFalse(
            invoked, "the wrapped application must never see a request "
            "whose workstream workspace could not be resolved while the "
            "controller is unreachable")
        self.assertEqual(401, sent[0]["status"])

    def test_unresolved_temp_token_rejected_when_controller_returns_error_dict(self):
        """Same as above, but for an HTTP-level error dict rather than a
        connection failure — both are fetch failures and must fail closed
        identically."""
        with patch.object(server, "_controller_get",
                          return_value={"ok": False,
                                        "error": "Controller returned HTTP 500"}):
            token = server._mint_temp_token("ws-never-registered", "job-x",
                                            ttl_seconds=60)
            invoked, sent = asyncio.run(_drive_middleware([], token))

        self.assertFalse(invoked)
        self.assertEqual(401, sent[0]["status"])

    # -- (c) do not weaken existing legacy single-workspace behaviour -----

    def test_legacy_single_workspace_mode_still_unscoped_when_reachable(self):
        """When the controller IS reachable and genuinely reports zero
        workspace-bound workstreams (legacy/single-workspace deployment),
        an unresolvable workstream must still be treated as unscoped —
        this pre-existing behaviour must not regress."""
        with patch.object(server, "_controller_get",
                          return_value=[{"workstreamId": "ws-legacy",
                                         "slackWorkspaceId": None}]):
            token = server._mint_temp_token("ws-legacy", "job-y", ttl_seconds=60)
            invoked, sent = asyncio.run(_drive_middleware([], token))

        self.assertTrue(
            invoked, "legacy single-workspace deployments must not be "
            "broken by the fail-closed fix")
        self.assertIsNone(server._get_workspace_scopes())


if __name__ == "__main__":
    unittest.main()
