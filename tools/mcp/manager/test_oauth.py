"""Tests for the ar-manager OAuth 2.1 middleware (``oauth.py``).

The focus here is durability of dynamic client registrations. A container
restart wipes the in-memory registry, so without persistence every connected
MCP client (claude.ai, ChatGPT, ...) is forced to re-register and its cached
``client_id`` stops validating at ``/oauth/authorize`` (it returns 400
"Unknown client_id"). These tests pin the load/save round-trip that keeps
registrations across restarts.
"""

import json
import os
import shutil
import sys
import tempfile
import unittest

# Ensure the manager package is importable when run from any directory.
_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

import oauth  # noqa: E402  -- sys.path adjusted above


async def _noop_app(scope, receive, send):
    """Minimal downstream ASGI app (never reached in these tests)."""


class OAuthClientPersistenceTest(unittest.TestCase):
    """Round-trip behaviour of the on-disk client registry."""

    def setUp(self):
        self._tmpdir = tempfile.mkdtemp()
        self._state_file = os.path.join(self._tmpdir, "oauth-clients.json")
        self._tokens = [{"value": "tok", "scopes": ["read"], "label": "t"}]

    def tearDown(self):
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _make(self, state_file=None):
        """Construct middleware, defaulting to this test's state file."""
        return oauth.OAuthMiddleware(
            _noop_app, self._tokens,
            state_file=self._state_file if state_file is None else state_file)

    def test_registration_is_written_and_reloaded(self):
        """A registered client is persisted and present after a fresh load."""
        middleware = self._make()
        with middleware._lock:
            middleware._clients["cid-1"] = {
                "redirect_uris": ["https://claude.ai/api/mcp/auth_callback"],
                "client_name": "Claude",
                "created_at": 1.0,
                "last_used": 1.0,
            }
            middleware._save_clients_locked()

        self.assertTrue(os.path.exists(self._state_file))

        reloaded = self._make()
        self.assertIn("cid-1", reloaded._clients)
        # The reloaded client must validate exactly as before the restart.
        self.assertIsNone(reloaded._validate_client_redirect(
            "cid-1", "https://claude.ai/api/mcp/auth_callback"))

    def test_missing_state_file_yields_empty_registry(self):
        """A non-existent state file is treated as an empty registry."""
        self.assertEqual(self._make()._clients, {})

    def test_corrupt_state_file_does_not_raise(self):
        """A malformed state file is tolerated and yields an empty registry."""
        with open(self._state_file, "w", encoding="utf-8") as handle:
            handle.write("{ not valid json")
        self.assertEqual(self._make()._clients, {})

    def test_no_state_file_configured_skips_persistence(self):
        """Without a state_file, nothing is written to disk."""
        middleware = oauth.OAuthMiddleware(_noop_app, self._tokens)
        with middleware._lock:
            middleware._clients["cid-2"] = {
                "redirect_uris": ["https://example.com/cb"],
                "client_name": "x",
                "created_at": 1.0,
                "last_used": 1.0,
            }
            middleware._save_clients_locked()
        self.assertFalse(os.path.exists(self._state_file))


class DeriveIssuerTest(unittest.TestCase):
    """Issuer/base-URL resolution shared by the OAuth and Bearer middleware."""

    def _scope(self, host, proto=None):
        headers = [(b"host", host.encode())]
        if proto:
            headers.append((b"x-forwarded-proto", proto.encode()))
        return {"headers": headers}

    def test_configured_takes_precedence_and_strips_slash(self):
        """An explicit issuer is used verbatim, minus any trailing slash."""
        self.assertEqual(
            oauth.derive_issuer(self._scope("ignored"), "https://h.example/"),
            "https://h.example")

    def test_public_host_assumed_https(self):
        """A non-localhost host is assumed HTTPS (TLS-terminating funnel)."""
        self.assertEqual(
            oauth.derive_issuer(self._scope("mac-studio.taild0f87.ts.net")),
            "https://mac-studio.taild0f87.ts.net")

    def test_localhost_is_http(self):
        """Loopback hosts stay on http so local probing works."""
        self.assertEqual(
            oauth.derive_issuer(self._scope("127.0.0.1:8010")),
            "http://127.0.0.1:8010")

    def test_forwarded_proto_overrides(self):
        """X-Forwarded-Proto wins over the host-based scheme guess."""
        self.assertEqual(
            oauth.derive_issuer(self._scope("h.example", "http")),
            "http://h.example")


class ProtectedResourceMetadataTest(unittest.IsolatedAsyncioTestCase):
    """RFC 9728 metadata, including path-scoped resources.

    A connector configured with an arbitrary path (used by some clients as a
    per-connector cache key) must still receive a ``resource`` that matches its
    configured URL, while the authorization server stays at the root issuer.
    """

    async def _fetch_prm(self, path, host="mac-studio.taild0f87.ts.net"):
        middleware = oauth.OAuthMiddleware(
            _noop_app, [{"value": "t", "scopes": ["read"], "label": "t"}])
        scope = {"type": "http", "method": "GET", "path": path,
                 "headers": [(b"host", host.encode())]}
        sent = []

        async def send(message):
            sent.append(message)

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        await middleware(scope, receive, send)
        status = next(m["status"] for m in sent
                      if m["type"] == "http.response.start")
        body = b"".join(m.get("body", b"") for m in sent
                        if m["type"] == "http.response.body")
        return status, json.loads(body.decode())

    async def test_root_resource(self):
        """The root resource is advertised with a trailing slash (matching how
        MCP clients canonicalize it), while the authorization server stays as
        the bare origin."""
        status, meta = await self._fetch_prm(
            "/.well-known/oauth-protected-resource")
        self.assertEqual(status, 200)
        self.assertEqual(meta["resource"],
                         "https://mac-studio.taild0f87.ts.net/")
        self.assertEqual(meta["authorization_servers"],
                         ["https://mac-studio.taild0f87.ts.net"])

    async def test_path_scoped_resource_matches_configured_url(self):
        """A path-suffixed well-known path advertises that exact resource."""
        status, meta = await self._fetch_prm(
            "/.well-known/oauth-protected-resource/c1")
        self.assertEqual(status, 200)
        self.assertEqual(meta["resource"],
                         "https://mac-studio.taild0f87.ts.net/c1")
        # The authorization server is identified by the same path-scoped
        # issuer so the client resolves its metadata at the matching path.
        self.assertEqual(meta["authorization_servers"],
                         ["https://mac-studio.taild0f87.ts.net/c1"])


class AuthServerMetadataTest(unittest.IsolatedAsyncioTestCase):
    """RFC 8414 metadata, including path-scoped issuers.

    A client that derives the AS metadata URL from a path-scoped resource
    (``/.well-known/oauth-authorization-server/c1``) validates that the
    returned ``issuer`` matches; the OAuth endpoints must remain at the root.
    """

    async def _fetch_asm(self, path, host="mac-studio.taild0f87.ts.net"):
        middleware = oauth.OAuthMiddleware(
            _noop_app, [{"value": "t", "scopes": ["read"], "label": "t"}])
        scope = {"type": "http", "method": "GET", "path": path,
                 "headers": [(b"host", host.encode())]}
        sent = []

        async def send(message):
            sent.append(message)

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        await middleware(scope, receive, send)
        status = next(m["status"] for m in sent
                      if m["type"] == "http.response.start")
        body = b"".join(m.get("body", b"") for m in sent
                        if m["type"] == "http.response.body")
        return status, json.loads(body.decode())

    async def test_root_issuer(self):
        """Root well-known: issuer is the bare origin, endpoints at root."""
        status, meta = await self._fetch_asm(
            "/.well-known/oauth-authorization-server")
        self.assertEqual(status, 200)
        self.assertEqual(meta["issuer"], "https://mac-studio.taild0f87.ts.net")
        self.assertEqual(meta["authorization_endpoint"],
                         "https://mac-studio.taild0f87.ts.net/oauth/authorize")

    async def test_path_scoped_issuer(self):
        """Path-suffixed well-known: issuer carries the path, endpoints don't."""
        status, meta = await self._fetch_asm(
            "/.well-known/oauth-authorization-server/c1")
        self.assertEqual(status, 200)
        # issuer must match the URL the client derived it from (RFC 8414)
        self.assertEqual(meta["issuer"],
                         "https://mac-studio.taild0f87.ts.net/c1")
        # endpoints stay at the single root authorization server
        self.assertEqual(meta["authorization_endpoint"],
                         "https://mac-studio.taild0f87.ts.net/oauth/authorize")
        self.assertEqual(meta["token_endpoint"],
                         "https://mac-studio.taild0f87.ts.net/oauth/token")


if __name__ == "__main__":
    unittest.main()
