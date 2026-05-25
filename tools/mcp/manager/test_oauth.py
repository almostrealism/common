"""Tests for the ar-manager OAuth 2.1 middleware (``oauth.py``).

The focus here is durability of dynamic client registrations. A container
restart wipes the in-memory registry, so without persistence every connected
MCP client (claude.ai, ChatGPT, ...) is forced to re-register and its cached
``client_id`` stops validating at ``/oauth/authorize`` (it returns 400
"Unknown client_id"). These tests pin the load/save round-trip that keeps
registrations across restarts.
"""

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


if __name__ == "__main__":
    unittest.main()
