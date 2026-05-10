"""Tests for the ar-secrets MCP server.

The controller is fully mocked via ``unittest.mock.patch`` on
``urllib.request.urlopen`` so no running controller is required. Tests
cover startup-config validation, list/render success paths, error
propagation from the controller, template substitution, file write
atomicity (size-check post-write), and refusal to start without the
required environment variables.
"""

import base64
import hmac
import importlib
import io
import json
import os
import stat
import sys
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch

# Ensure the secrets package is importable as a flat module
_SECRETS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SECRETS_DIR not in sys.path:
    sys.path.insert(0, _SECRETS_DIR)


def _mint_token(workstream_id: str, job_id: str = "job-1",
                shared_secret: str = "test-secret",
                ttl_seconds: int = 3600) -> str:
    """Mint an ``armt_tmp_*`` token matching the controller's format."""
    expiry = int(time.time()) + ttl_seconds
    payload = f"{workstream_id}:{job_id}:{expiry}"
    digest = hmac.new(
        shared_secret.encode("utf-8"),
        payload.encode("utf-8"),
        "sha256",
    ).digest()
    hmac_b64 = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    payload_b64 = base64.urlsafe_b64encode(
        payload.encode("utf-8")).rstrip(b"=").decode("ascii")
    return f"armt_tmp_{hmac_b64}:{payload_b64}"


_DEFAULT_ENV = {
    "AR_CONTROLLER_URL": "http://controller:7780",
    "AR_WORKSTREAM_ID": "ws-abc",
    "AR_MANAGER_TOKEN": _mint_token("ws-abc"),
}


def _import_server(env: dict = None):
    """Import (or reload) the server module with a controlled environment."""
    effective = dict(_DEFAULT_ENV)
    if env is not None:
        effective.update(env)
    # Drop unset markers
    effective = {k: v for k, v in effective.items() if v is not None}
    with patch.dict(os.environ, effective, clear=False):
        if "server" in sys.modules:
            del sys.modules["server"]
        import server as srv  # type: ignore
        return srv


def _mock_response(body: dict):
    """Build a urlopen context-manager mock for a given JSON body."""
    payload = json.dumps(body).encode("utf-8")
    response = MagicMock()
    response.read.return_value = payload
    response.__enter__ = MagicMock(return_value=response)
    response.__exit__ = MagicMock(return_value=False)
    return response


class TestStartupConfig(unittest.TestCase):

    def test_refuses_without_controller_url(self):
        srv = _import_server({"AR_CONTROLLER_URL": ""})
        result = srv.secret_list_names()
        self.assertFalse(result["ok"])
        self.assertIn("AR_CONTROLLER_URL", result["error"])

    def test_refuses_without_workstream_id(self):
        srv = _import_server({"AR_WORKSTREAM_ID": ""})
        result = srv.secret_list_names()
        self.assertFalse(result["ok"])
        self.assertIn("AR_WORKSTREAM_ID", result["error"])

    def test_refuses_without_manager_token(self):
        srv = _import_server({"AR_MANAGER_TOKEN": ""})
        result = srv.secret_list_names()
        self.assertFalse(result["ok"])
        self.assertIn("AR_MANAGER_TOKEN", result["error"])

    def test_refuses_when_token_workstream_mismatches(self):
        srv = _import_server({
            "AR_WORKSTREAM_ID": "ws-abc",
            "AR_MANAGER_TOKEN": _mint_token("ws-other"),
        })
        result = srv.secret_render_file(
            secret_name="x", template="x", output_path="/tmp/x")
        self.assertFalse(result["ok"])
        self.assertIn("workstream", result["error"].lower())


class TestSecretListNames(unittest.TestCase):

    def test_returns_names_from_controller(self):
        srv = _import_server()
        with patch.object(srv, "urlopen",
                          return_value=_mock_response({"ok": True,
                                                       "names": ["aws", "gh"]})):
            result = srv.secret_list_names()
        self.assertTrue(result["ok"])
        self.assertEqual(result["names"], ["aws", "gh"])

    def test_propagates_controller_error(self):
        srv = _import_server()
        with patch.object(srv, "urlopen",
                          return_value=_mock_response({"ok": False,
                                                       "error": "boom"})):
            result = srv.secret_list_names()
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "boom")

    def test_includes_workstream_id_in_url(self):
        srv = _import_server()
        with patch.object(srv, "urlopen") as mock_open:
            mock_open.return_value = _mock_response({"ok": True, "names": []})
            srv.secret_list_names()
            request = mock_open.call_args.args[0]
            self.assertIn("workstream_id=ws-abc", request.full_url)
            self.assertEqual(request.headers["Authorization"],
                             f"Bearer {_DEFAULT_ENV['AR_MANAGER_TOKEN']}")


class TestSecretRenderFile(unittest.TestCase):

    def _render(self, srv, payload, template, output_path, mode="0600"):
        with patch.object(srv, "urlopen",
                          return_value=_mock_response({"ok": True,
                                                       "payload": payload})):
            return srv.secret_render_file(
                secret_name="x", template=template,
                output_path=output_path, mode=mode)

    def test_writes_substituted_file(self):
        srv = _import_server()
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "creds.json")
            result = self._render(
                srv,
                payload={"email": "alice@example.com", "password": "s3cret"},
                template='{"email":"{{email}}","password":"{{password}}"}',
                output_path=out,
            )
            self.assertTrue(result["ok"], result)
            with open(out) as fh:
                self.assertEqual(
                    fh.read(),
                    '{"email":"alice@example.com","password":"s3cret"}')

    def test_sets_file_mode(self):
        srv = _import_server()
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "f")
            result = self._render(srv, {"k": "v"}, "{{k}}", out, mode="0640")
            self.assertTrue(result["ok"])
            self.assertEqual(stat.S_IMODE(os.stat(out).st_mode), 0o640)

    def test_rejects_missing_placeholder_keys(self):
        srv = _import_server()
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "f")
            result = self._render(srv, {"a": "1"}, "{{missing}}", out)
            self.assertFalse(result["ok"])
            self.assertIn("missing", result["error"])
            self.assertFalse(os.path.exists(out))

    def test_rejects_bad_mode(self):
        srv = _import_server()
        result = srv.secret_render_file(
            secret_name="x", template="x", output_path="/tmp/x", mode="0999")
        self.assertFalse(result["ok"])

    def test_creates_parent_directory(self):
        srv = _import_server()
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "nested", "dir", "f.txt")
            result = self._render(srv, {"k": "hello"}, "{{k}}", out)
            self.assertTrue(result["ok"], result)
            self.assertTrue(os.path.exists(out))

    def test_propagates_controller_error(self):
        srv = _import_server()
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "f")
            with patch.object(srv, "urlopen",
                              return_value=_mock_response(
                                  {"ok": False, "error": "denied"})):
                result = srv.secret_render_file(
                    secret_name="x", template="x", output_path=out)
            self.assertFalse(result["ok"])
            self.assertEqual(result["error"], "denied")

    def test_post_write_size_matches(self):
        srv = _import_server()
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "f")
            result = self._render(srv, {"k": "abc"}, "[{{k}}]", out)
            self.assertTrue(result["ok"])
            self.assertEqual(os.stat(out).st_size, len("[abc]"))


class TestToolRegistration(unittest.TestCase):

    def test_expected_tools_registered(self):
        srv = _import_server()
        registered = set(srv.mcp._tool_manager._tools.keys())
        self.assertEqual(
            registered,
            {"secret_list_names", "secret_render_file"},
            f"Unexpected tool registry: {registered}",
        )


if __name__ == "__main__":
    unittest.main()
