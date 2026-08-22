"""Tests for the workspace configuration and secret tools.

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


class TestWorkspaceUpdateConfig(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_update_name_and_default_channel(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        result = server.workspace_update_config(
            slack_workspace_id="T0123456789",
            name="Acme Inc",
            default_channel="C9999",
        )
        call_path = mock_post.call_args[0][0]
        self.assertIn("T0123456789", call_path)
        self.assertIn("/config", call_path)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["name"], "Acme Inc")
        self.assertEqual(payload["defaultChannel"], "C9999")
        self.assertTrue(result["ok"])

    def test_update_rejects_legacy_runners(self):
        """The dropped `runners` map is rejected with a 400-style error."""
        _grant_all_scopes()
        result = server.workspace_update_config(
            slack_workspace_id="T0123456789",
            runners='{"primary":"opencode"}',
        )
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["runners"])

    def test_update_rejects_legacy_default_runner(self):
        """The dropped `default_runner` shortcut is rejected."""
        _grant_all_scopes()
        result = server.workspace_update_config(
            slack_workspace_id="T0123456789",
            default_runner="opencode",
        )
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["default_runner"])

    def test_no_fields_returns_error(self):
        _grant_all_scopes()
        result = server.workspace_update_config(slack_workspace_id="T0123456789")
        self.assertFalse(result["ok"])
        self.assertIn("No fields to update", result["error"])

    @patch.object(server, "_controller_post")
    def test_empty_string_fields_do_not_change_payload(self, mock_post):
        """Empty-string optional fields are omitted from the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            slack_workspace_id="T0123456789",
            name="Acme",
            default_channel="",
            default_runner="",
            runners="",
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload, {"name": "Acme"})

    @patch.object(server, "_controller_post")
    def test_accepts_new_workspace_id_param(self, mock_post):
        """The canonical workspace_id parameter routes the same as the legacy alias."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", name="Acme")
        call_path = mock_post.call_args[0][0]
        self.assertIn("almostrealism", call_path)

    @patch.object(server, "_controller_post")
    def test_new_id_forwarded_as_newId_payload(self, mock_post):
        """Supplying new_id forwards a newId field to the controller."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="T0123456789", new_id="almostrealism")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["newId"], "almostrealism")

    @patch.object(server, "_controller_post")
    def test_new_id_equal_to_workspace_id_is_omitted(self, mock_post):
        """new_id matching the existing id is a no-op and is omitted from the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism",
            new_id="almostrealism",
            name="Acme")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("newId", payload)
        self.assertEqual(payload["name"], "Acme")

    @patch.object(server, "_controller_post")
    def test_slack_team_id_set_to_nonempty_forwards(self, mock_post):
        """A non-empty slack_team_id binds the workspace to that Slack team."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", slack_team_id="T0123456789")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["slackTeamId"], "T0123456789")

    @patch.object(server, "_controller_post")
    def test_slack_team_id_explicit_empty_clears(self, mock_post):
        """An explicit empty string for slack_team_id clears the Slack binding."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", slack_team_id="")
        payload = mock_post.call_args[0][1]
        # slackTeamId is present (so the controller knows to clear it),
        # but its value is the empty string.
        self.assertIn("slackTeamId", payload)
        self.assertEqual(payload["slackTeamId"], "")

    @patch.object(server, "_controller_post")
    def test_slack_team_id_omitted_leaves_payload_unset(self, mock_post):
        """Omitting slack_team_id leaves the field absent from the payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="almostrealism", name="Acme")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("slackTeamId", payload)

    def test_missing_workspace_id_returns_error(self):
        """Calling with neither workspace_id nor slack_workspace_id returns an error."""
        _grant_all_scopes()
        result = server.workspace_update_config(name="Acme")
        self.assertFalse(result["ok"])
        self.assertIn("workspace_id", result["error"])

class TestWorkspaceScopeHelpers(unittest.TestCase):

    def setUp(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def test_unscoped_allows_any_workspace(self):
        self.assertTrue(server._is_workspace_allowed("TAAA"))
        self.assertTrue(server._is_workspace_allowed(None))

    def test_scoped_allows_listed_workspace(self):
        _set_workspaces("TAAA")
        self.assertTrue(server._is_workspace_allowed("TAAA"))

    def test_scoped_denies_other_workspace(self):
        _set_workspaces("TAAA")
        self.assertFalse(server._is_workspace_allowed("TBBB"))

    def test_scoped_denies_unknown_workspace(self):
        _set_workspaces("TAAA")
        self.assertFalse(server._is_workspace_allowed(None))
        self.assertFalse(server._is_workspace_allowed(""))

    def test_require_workspace_raises_on_mismatch(self):
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server._require_workspace("TBBB")

    def test_require_workspace_noop_when_unscoped(self):
        server._require_workspace("TANYTHING")  # does not raise

class TestWorkspaceCacheAndFilter(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_workspace_for_workstream_resolves_and_caches(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-1", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "ws-2", "slackWorkspaceId": "TBBB"},
        ]
        self.assertEqual("TAAA", server._workspace_for_workstream("ws-1"))
        self.assertEqual("TBBB", server._workspace_for_workstream("ws-2"))
        self.assertEqual(1, mock_get.call_count)  # cache hit on second call

    @patch.object(server, "_controller_get")
    def test_workspace_for_workstream_returns_none_for_unknown(self, mock_get):
        mock_get.return_value = [{"workstreamId": "ws-1", "slackWorkspaceId": "TAAA"}]
        self.assertIsNone(server._workspace_for_workstream("ws-missing"))

    def test_filter_workstreams_passthrough_unscoped(self):
        entries = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "b", "slackWorkspaceId": "TBBB"},
        ]
        self.assertEqual(entries, server._filter_workstreams_by_scope(entries))

    def test_filter_workstreams_restricts_to_scope(self):
        _set_workspaces("TAAA")
        entries = [
            {"workstreamId": "a", "slackWorkspaceId": "TAAA"},
            {"workstreamId": "b", "slackWorkspaceId": "TBBB"},
            {"workstreamId": "c", "slackWorkspaceId": "TAAA"},
        ]
        filtered = server._filter_workstreams_by_scope(entries)
        self.assertEqual(["a", "c"], [e["workstreamId"] for e in filtered])

class TestBearerAuthWorkspaceScopes(unittest.TestCase):

    def test_empty_workspace_scopes_treated_as_unscoped(self):
        tokens = [{"value": "tok", "label": "t", "scopes": ["read"],
                   "workspaceScopes": []}]
        middleware = server.BearerAuthMiddleware(app=None, tokens=tokens)
        # The fourth field is workspace_scopes — empty list must normalise to None.
        entry = middleware.token_entries[0]
        self.assertIsNone(entry[3])

    def test_populated_workspace_scopes_retained(self):
        tokens = [{"value": "tok", "label": "t", "scopes": ["read"],
                   "workspaceScopes": ["TAAA", "TBBB"]}]
        middleware = server.BearerAuthMiddleware(app=None, tokens=tokens)
        self.assertEqual(["TAAA", "TBBB"], middleware.token_entries[0][3])

class TestTempTokenWorkspaceScoping(unittest.TestCase):
    """Covers the security fix that temp tokens are no longer treated
    as superadmin — their workspace scope is derived from the bound
    workstream's ``slackWorkspaceId`` at validate time."""

    def setUp(self):
        _clear_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_multi_workspace_mode_detection(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        self.assertTrue(server._is_multi_workspace_mode())

    @patch.object(server, "_controller_get")
    def test_legacy_mode_detection_empty_workspaces(self, mock_get):
        # No workstream has a slackWorkspaceId — single-workspace legacy.
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": None,
             "repoUrl": "git@github.com:almostrealism/common.git"},
        ]
        self.assertFalse(server._is_multi_workspace_mode())

class TestWorkspaceSecretListNames(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_list_names_success(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"names": ["aws-prod", "github-deploy-key"]}
        result = server.workspace_secret_list_names("ws-abc")
        self.assertTrue(result["ok"])
        self.assertEqual(result["names"], ["aws-prod", "github-deploy-key"])
        # Verify that the call included a workstream_id query parameter
        called_path = mock_get.call_args[0][0]
        self.assertIn("workstream_id=ws-abc", called_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_list_names_empty_workspace(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"names": []}
        result = server.workspace_secret_list_names("ws-abc")
        self.assertTrue(result["ok"])
        self.assertEqual(result["names"], [])

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_controller_error_propagated(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"ok": False, "error": "workspace not found"}
        result = server.workspace_secret_list_names("ws-abc")
        self.assertFalse(result["ok"])
        self.assertIn("workspace not found", result["error"])

    @patch.object(server, "SHARED_SECRET", "")
    @patch.object(server, "_require_workstream_in_scope")
    def test_returns_error_when_no_shared_secret(self, mock_scope):
        mock_scope.return_value = None
        result = server.workspace_secret_list_names("ws-abc")
        self.assertFalse(result["ok"])
        self.assertIn("Shared secret not configured", result["error"])

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.workspace_secret_list_names("ws-abc")

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_requires_workstream_in_scope(self):
        """Scoped tokens must not access out-of-scope workstreams."""
        _set_workspaces("TAAA")
        _reset_workspace_cache()
        try:
            with patch.object(
                server,
                "_require_workstream_in_scope",
                side_effect=PermissionError("out of scope"),
            ):
                with self.assertRaises(PermissionError):
                    server.workspace_secret_list_names("ws-other")
        finally:
            _clear_workspaces()
            _reset_workspace_cache()

class TestWorkspaceSecretRenderFile(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    def _make_payload_resp(self):
        return {
            "name": "aws-prod",
            "workspace_id": "T0123456789",
            "payload": {
                "access_key_id": "AKIATEST",
                "secret_access_key": "SECRET123",
                "region": "us-east-1",
            },
        }

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_render_success(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        template = "[default]\naws_access_key_id = {{access_key_id}}\n" \
                   "aws_secret_access_key = {{secret_access_key}}\nregion = {{region}}\n"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "credentials")
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
                mode="0600",
            )
            self.assertTrue(result["ok"])
            self.assertEqual(result["output_path"], output)
            # File must exist and contain rendered values
            self.assertTrue(os.path.exists(output))
            with open(output) as fh:
                content = fh.read()
            self.assertIn("AKIATEST", content)
            self.assertIn("SECRET123", content)
            # File must not be in the returned dict's values
            self.assertNotIn("AKIATEST", str(result))
            self.assertNotIn("SECRET123", str(result))

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_output_file_permissions(self, mock_scope, mock_get):
        import tempfile, os, stat
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        template = "{{access_key_id}}"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "creds")
            server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
                mode="0600",
            )
            mode = os.stat(output).st_mode
            self.assertEqual(stat.S_IMODE(mode), 0o600)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_missing_placeholder_returns_error_no_file(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        template = "[default]\ntoken = {{missing_key}}\n"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "creds")
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
            )
            self.assertFalse(result["ok"])
            self.assertIn("missing_key", result["error"])
            # No file written
            self.assertFalse(os.path.exists(output))

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_extra_payload_keys_silently_ignored(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        # Template uses only one of the three keys
        template = "key={{access_key_id}}"
        with tempfile.TemporaryDirectory() as tmpdir:
            output = os.path.join(tmpdir, "creds")
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template=template,
                output_path=output,
            )
            self.assertTrue(result["ok"])

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_tilde_expansion(self, mock_scope, mock_get):
        import tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        # We can't use ~ to write to actual home in tests; verify expansion
        # by patching os.path.expanduser instead.
        with tempfile.TemporaryDirectory() as tmpdir:
            expected_path = os.path.join(tmpdir, ".aws", "credentials")
            with patch("os.path.expanduser", return_value=expected_path):
                result = server.workspace_secret_render_file(
                    workstream_id="ws-abc",
                    secret_name="aws-prod",
                    template="key={{access_key_id}}",
                    output_path="~/.aws/credentials",
                )
            self.assertTrue(result["ok"])
            self.assertEqual(result["output_path"], expected_path)

    @patch.object(server, "SHARED_SECRET", "test-secret")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_require_workstream_in_scope")
    def test_controller_error_propagated(self, mock_scope, mock_get):
        mock_scope.return_value = None
        mock_get.return_value = {"ok": False, "error": "secret not found"}
        result = server.workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="no-such-secret",
            template="{{foo}}",
            output_path="/tmp/out",
        )
        self.assertFalse(result["ok"])
        self.assertIn("secret not found", result["error"])

    @patch.object(server, "SHARED_SECRET", "")
    @patch.object(server, "_require_workstream_in_scope")
    def test_returns_error_when_no_shared_secret(self, mock_scope):
        mock_scope.return_value = None
        result = server.workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="aws-prod",
            template="{{foo}}",
            output_path="/tmp/out",
        )
        self.assertFalse(result["ok"])
        self.assertIn("Shared secret not configured", result["error"])

    def test_requires_read_scope(self):
        _grant_scopes("write")
        with self.assertRaises(PermissionError):
            server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template="",
                output_path="/tmp/out",
            )

    @patch.object(server, "SHARED_SECRET", "test-secret")
    def test_requires_workstream_in_scope(self):
        """Scoped tokens must not access secrets from out-of-scope workstreams."""
        with patch.object(
            server,
            "_require_workstream_in_scope",
            side_effect=PermissionError("out of scope"),
        ):
            with self.assertRaises(PermissionError):
                server.workspace_secret_render_file(
                    workstream_id="ws-other",
                    secret_name="aws-prod",
                    template="",
                    output_path="/tmp/out",
                )
