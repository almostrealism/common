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


class TestWorkstreamIntrospect(unittest.TestCase):
    """Reporting which tools an agent on a workstream can actually invoke.

    The tool exists because enabling an orchestrator needs two independent
    grants and a denial names neither, so the report has to show both sides
    and has to agree with what is enforced.
    """

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_find_workstream")
    @patch("workspace_map._get_dispatch_capable_ids")
    def test_reports_both_sides_when_dispatch_capable(self, mock_ids, mock_ws):
        mock_ws.return_value = {"workstreamId": "ws-orch"}
        mock_ids.return_value = {"ws-orch"}

        result = server.workstream_introspect(workstream_id="ws-orch")

        self.assertTrue(result["ok"])
        self.assertTrue(result["controller"]["dispatch_capable"])
        self.assertTrue(all(result["controller"]["gates"].values()))
        # The orchestration tools must appear on the harness side too, or the
        # report would claim a grant the agent cannot use.
        self.assertIn("workstream_register",
                      result["harness"]["claude_code"]["tools_granted"])
        self.assertIn("mcp__ar-manager__workstream_register",
                      result["harness"]["claude_code"]["allowlist_csv"])

    @patch.object(server, "_find_workstream")
    @patch("workspace_map._get_dispatch_capable_ids")
    def test_reports_the_withheld_tools_when_not_dispatch_capable(
            self, mock_ids, mock_ws):
        mock_ws.return_value = {"workstreamId": "ws-plain"}
        mock_ids.return_value = set()

        result = server.workstream_introspect(workstream_id="ws-plain")

        self.assertFalse(result["controller"]["dispatch_capable"])
        self.assertFalse(any(result["controller"]["gates"].values()))
        granted = result["harness"]["claude_code"]["tools_granted"]
        self.assertNotIn("workstream_register", granted)
        self.assertIn("workstream_register",
                      result["harness"]["claude_code"]["tools_withheld"])
        self.assertIn("dispatch_capable=True", " ".join(result["next_steps"]))

    @patch.object(server, "_find_workstream")
    @patch("workspace_map._get_dispatch_capable_ids")
    def test_reads_the_live_gate_not_the_workstream_record(
            self, mock_ids, mock_ws):
        # The record can be stale; the enforcement path reads the dispatch set,
        # so a report sourced from the record could contradict the decision it
        # is meant to explain.
        mock_ws.return_value = {"workstreamId": "ws-x", "dispatchCapable": True}
        mock_ids.return_value = set()

        result = server.workstream_introspect(workstream_id="ws-x")

        self.assertFalse(result["controller"]["dispatch_capable"])

    @patch.object(server, "_find_workstream", return_value=None)
    def test_unknown_workstream_is_reported(self, _):
        result = server.workstream_introspect(workstream_id="ws-missing")
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["error"])

    @patch.object(server, "_get_token_workstream_id", return_value="")
    def test_no_workstream_to_introspect(self, _):
        result = server.workstream_introspect()
        self.assertFalse(result["ok"])
        self.assertIn("No workstream", result["error"])

    @patch.object(server, "_find_workstream")
    @patch("workspace_map._get_dispatch_capable_ids")
    def test_defaults_to_the_calling_workstream(self, mock_ids, mock_ws):
        mock_ws.return_value = {"workstreamId": "ws-token"}
        mock_ids.return_value = set()
        with patch.object(server, "_get_token_workstream_id",
                          return_value="ws-token"):
            result = server.workstream_introspect()
        self.assertEqual(result["workstream_id"], "ws-token")


class TestWorkspaceUpdateConfig(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_update_name_and_default_channel(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        result = server.workspace_update_config(
            workspace_id="T0123456789",
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
            workspace_id="T0123456789",
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
            workspace_id="T0123456789",
            default_runner="opencode",
        )
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])
        self.assertIn("no longer supported", result["error"])
        self.assertEqual(result["removed_parameters"], ["default_runner"])

    def test_no_fields_returns_error(self):
        _grant_all_scopes()
        result = server.workspace_update_config(workspace_id="T0123456789")
        self.assertFalse(result["ok"])
        self.assertIn("No fields to update", result["error"])

    @patch.object(server, "_controller_post")
    def test_empty_string_fields_do_not_change_payload(self, mock_post):
        """Empty-string optional fields are omitted from the controller payload."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True}
        server.workspace_update_config(
            workspace_id="T0123456789",
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
        """Calling with no workspace identifier returns an error."""
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
    def test_render_names_the_host_that_received_the_file(self, mock_scope, mock_get):
        """The caller is told which machine holds the file, since it may not be theirs."""
        import socket, tempfile, os
        mock_scope.return_value = None
        mock_get.return_value = self._make_payload_resp()
        with tempfile.TemporaryDirectory() as tmpdir:
            result = server.workspace_secret_render_file(
                workstream_id="ws-abc",
                secret_name="aws-prod",
                template="region = {{region}}\n",
                output_path=os.path.join(tmpdir, "credentials"),
            )
        self.assertTrue(result["ok"])
        self.assertEqual(result["host"], socket.gethostname())
        self.assertIn(result["host"], result["note"])
        self.assertIn("ar-secrets", result["note"])
        self.assertNotIn("us-east-1", str(result))

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


class TestArchivedWorkstreamReadOnly(unittest.TestCase):
    """Archived workstreams are readable but frozen.

    A retrospective that surveys merged QA rounds must be able to read an
    archived round's context, job history and memories; the first run of the
    retrospective could not, because every helper that resolves a workstream's
    ownership read the controller's live-only list, so an archived id came
    back as "not found" / "not in scope". These tests pin that ownership now
    resolves for archived workstreams (so the read tools see them), and that
    the write tools nonetheless refuse to submit a job to, or change the
    configuration of, an archived workstream.
    """

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server, "_controller_get")
    def test_scope_map_includes_archived(self, mock_get):
        """The workspace map resolves an archived workstream's owner, and asks
        the controller for archived rows to do it."""
        mock_get.return_value = [
            {"workstreamId": "ws-live", "workspaceId": "TAAA"},
            {"workstreamId": "ws-arch", "workspaceId": "TBBB", "archived": True},
        ]
        self.assertEqual("TBBB", server._workspace_for_workstream("ws-arch"))
        self.assertEqual("/api/workstreams?includeArchived=true",
                         mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_find_workstream_resolves_archived(self, mock_get):
        """_find_workstream returns an archived row (carrying archived=true)
        rather than treating it as absent."""
        mock_get.return_value = [
            {"workstreamId": "ws-arch", "workspaceId": "TBBB", "archived": True},
        ]
        ws = server._find_workstream("ws-arch")
        self.assertIsNotNone(ws)
        self.assertTrue(ws.get("archived"))
        self.assertIn("includeArchived=true", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_find_workstream_by_branch_matches_archived(self, mock_get):
        """repo_url + branch resolves an archived workstream for the job
        timeline, honouring the controller's archived list."""
        mock_get.return_value = [
            {"workstreamId": "ws-arch", "defaultBranch": "qa/retro-x",
             "workspaceId": "TBBB", "archived": True},
        ]
        ws = server._find_workstream_by_branch(
            "git@github.com:org/repo.git", "qa/retro-x")
        self.assertIsNotNone(ws)
        self.assertEqual("ws-arch", ws["workstreamId"])
        self.assertIn("includeArchived=true", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_find_workstream_by_branch_resolves_branch_only_unique(self, mock_get):
        """With no repo_url, a branch that uniquely names one archived round
        still resolves — mirroring the controller's branch-only resolution,
        which the submit guard must cover (the controller has no archived
        check and accepts target_branch with no repoUrl)."""
        mock_get.return_value = [
            {"workstreamId": "ws-live", "defaultBranch": "feature/other",
             "workspaceId": "TAAA"},
            {"workstreamId": "ws-arch", "defaultBranch": "qa/retro-x",
             "workspaceId": "TBBB", "archived": True},
        ]
        ws = server._find_workstream_by_branch("", "qa/retro-x")
        self.assertIsNotNone(ws)
        self.assertEqual("ws-arch", ws["workstreamId"])
        # A branch-only lookup must not pin the query to any repository.
        self.assertNotIn("repoUrl", mock_get.call_args[0][0])
        self.assertIn("includeArchived=true", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_find_workstream_by_branch_branch_only_ambiguous_is_none(self, mock_get):
        """A branch shared by two repositories does not resolve branch-only —
        the controller refuses to route it, so the guard must not treat it as
        a resolved (archived) target either."""
        mock_get.return_value = [
            {"workstreamId": "ws-a", "defaultBranch": "shared",
             "workspaceId": "TAAA", "archived": True},
            {"workstreamId": "ws-b", "defaultBranch": "shared",
             "workspaceId": "TBBB"},
        ]
        self.assertIsNone(server._find_workstream_by_branch("", "shared"))

    @patch.object(server, "_controller_get")
    def test_find_workstream_by_branch_scope_hides_out_of_scope(self, mock_get):
        """By default an out-of-scope match is hidden, so a scoped caller
        cannot resolve a round in another workspace by branch."""
        _set_workspaces("TAAA")
        mock_get.return_value = [
            {"workstreamId": "ws-arch", "defaultBranch": "qa/retro-x",
             "workspaceId": "TBBB", "archived": True},
        ]
        self.assertIsNone(server._find_workstream_by_branch(
            "git@github.com:org/repo.git", "qa/retro-x"))

    @patch.object(server, "_controller_get")
    def test_find_workstream_by_branch_enforce_scope_false_sees_archived(
            self, mock_get):
        """enforce_scope=False bypasses the workspace filter so the archived
        read-only guard sees a round the caller's scope would otherwise hide —
        an archived round is frozen for every caller, and memory_store applies
        no workspace scope of its own to catch it."""
        _set_workspaces("TAAA")
        mock_get.return_value = [
            {"workstreamId": "ws-arch", "defaultBranch": "qa/retro-x",
             "workspaceId": "TBBB", "archived": True},
        ]
        ws = server._find_workstream_by_branch(
            "git@github.com:org/repo.git", "qa/retro-x", enforce_scope=False)
        self.assertIsNotNone(ws)
        self.assertEqual("ws-arch", ws["workstreamId"])
        self.assertTrue(ws.get("archived"))

    @patch.object(server, "_find_workstream")
    def test_archived_error_uses_supplied_ws_without_refetch(self, mock_find):
        """When a pre-resolved dict is supplied, the guard reads its archived
        flag directly and does not re-fetch through the scope-filtered
        _find_workstream (which would re-hide an out-of-scope archived round)."""
        err = server._archived_workstream_error(
            "store a memory", "ws-arch",
            ws={"workstreamId": "ws-arch", "archived": True})
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("archived", err["error"])
        mock_find.assert_not_called()

    @patch.object(server, "_find_workstream")
    def test_archived_error_fires_for_archived(self, mock_find):
        mock_find.return_value = {"workstreamId": "ws-arch", "archived": True}
        err = server._archived_workstream_error("submit a task", "ws-arch")
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("archived", err["error"])

    @patch.object(server, "_find_workstream")
    def test_archived_error_silent_for_live(self, mock_find):
        mock_find.return_value = {"workstreamId": "ws-live"}
        self.assertIsNone(
            server._archived_workstream_error("submit a task", "ws-live"))

    @patch.object(server, "_find_workstream", return_value=None)
    def test_archived_error_silent_for_unknown(self, _):
        self.assertIsNone(
            server._archived_workstream_error("submit a task", "ws-missing"))

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream")
    def test_submit_rejected_for_archived(self, mock_find, mock_post):
        """No job may be submitted to an archived workstream, and the refusal
        reaches the caller before the controller is contacted."""
        mock_find.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.workstream_submit_task(
            prompt="Fix the bug", workstream_id="ws-arch")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream")
    def test_submit_allowed_for_live(self, mock_find, mock_post):
        """A live workstream is unaffected by the archived guard."""
        mock_find.return_value = {"workstreamId": "ws-live"}
        mock_post.return_value = {"ok": True, "jobId": "job-1",
                                  "workstreamId": "ws-live"}
        result = server.workstream_submit_task(
            prompt="Fix the bug", workstream_id="ws-live")
        self.assertTrue(result["ok"])
        mock_post.assert_called_once()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream")
    def test_update_config_rejected_for_archived(self, mock_find, mock_post):
        """An archived workstream's configuration is frozen."""
        mock_find.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.workstream_update_config(
            workstream_id="ws-arch", repo_url="git@github.com:org/repo.git")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_get_memory_client")
    def test_context_jobs_resolved_from_repo_and_branch(
            self, mock_client_fn, mock_by_branch, mock_get, mock_pr):
        """A repo_url + branch caller now gets the job timeline, which an
        archived round depends on since it is addressed by branch, not id."""
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_pr.return_value = {"ok": True, "found": False}
        mock_by_branch.return_value = {"workstreamId": "ws-arch"}
        mock_get.return_value = [
            {"jobId": "job-9", "timestamp": "2026-10-10T00:00:00Z",
             "status": "SUCCESS", "description": "retro"},
        ]

        result = server.workstream_context(
            repo_url="git@github.com:org/repo.git",
            branch="qa/retro-x",
            include_commits=False,
        )

        self.assertTrue(result["ok"])
        self.assertIn("jobs", result)
        self.assertEqual([j["jobId"] for j in result["jobs"]], ["job-9"])
        # The timeline was fetched for the branch-resolved workstream.
        self.assertIn("/api/workstreams/ws-arch/jobs",
                      mock_get.call_args[0][0])

    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_get_memory_client")
    def test_context_jobs_prefer_explicit_branch_over_workstream_id(
            self, mock_client_fn, mock_by_branch, mock_get, mock_pr):
        """When a caller supplies both an explicit repo_url + branch and a
        workstream_id, _resolve_branch_context keys the memories/commits/PR on
        the explicit branch; the job timeline must follow the same round rather
        than the unrelated id, or the response would attach one round's jobs to
        another's branch."""
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_pr.return_value = {"ok": True, "found": False}
        mock_by_branch.return_value = {"workstreamId": "ws-branch"}
        mock_get.return_value = [
            {"jobId": "job-branch", "timestamp": "2026-10-10T00:00:00Z",
             "status": "SUCCESS", "description": "retro"},
        ]

        result = server.workstream_context(
            repo_url="git@github.com:org/repo.git",
            branch="qa/retro-x",
            workstream_id="ws-other",
            include_commits=False,
        )

        self.assertTrue(result["ok"])
        self.assertEqual([j["jobId"] for j in result["jobs"]], ["job-branch"])
        # The timeline is the branch-resolved round, not the supplied id.
        mock_by_branch.assert_called_once_with(
            "git@github.com:org/repo.git", "qa/retro-x")
        self.assertIn("/api/workstreams/ws-branch/jobs",
                      mock_get.call_args[0][0])
        self.assertNotIn("ws-other", mock_get.call_args[0][0])

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_submit_rejected_for_archived_via_target_branch(
            self, mock_find, mock_by_branch, mock_post):
        """The archived guard also covers the target_branch path: a submission
        with no workstream_id but a repo_url + target_branch that resolves to
        an archived round is refused before the controller — which has no
        archived check of its own — is contacted."""
        mock_by_branch.return_value = {"workstreamId": "ws-arch", "archived": True}
        mock_find.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.workstream_submit_task(
            prompt="Fix the bug",
            repo_url="git@github.com:org/repo.git",
            target_branch="qa/retro-x",
        )
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_by_branch.assert_called_once_with(
            "git@github.com:org/repo.git", "qa/retro-x")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_submit_rejected_for_archived_via_branch_only(
            self, mock_find, mock_by_branch, mock_post):
        """The guard covers the branch-only submit form too: a target_branch
        with no repo_url that the controller would resolve to an archived
        round is refused here. Without this, the ar-manager guard's old
        repo_url requirement let that form reach the controller, which has no
        archived check and accepts target_branch with no repoUrl."""
        mock_by_branch.return_value = {"workstreamId": "ws-arch", "archived": True}
        mock_find.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.workstream_submit_task(
            prompt="Fix the bug",
            target_branch="qa/retro-x",
        )
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_by_branch.assert_called_once_with("", "qa/retro-x")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_submit_allowed_for_live_target_branch(
            self, mock_find, mock_by_branch, mock_post):
        """A target_branch that resolves to a live workstream is unaffected by
        the archived guard."""
        mock_by_branch.return_value = {"workstreamId": "ws-live"}
        mock_find.return_value = {"workstreamId": "ws-live"}
        mock_post.return_value = {"ok": True, "jobId": "job-1",
                                  "workstreamId": "ws-live"}
        result = server.workstream_submit_task(
            prompt="Fix the bug",
            repo_url="git@github.com:org/repo.git",
            target_branch="feature/live",
        )
        self.assertTrue(result["ok"])
        mock_post.assert_called_once()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream")
    def test_commit_plan_rejected_for_archived(self, mock_find, mock_gh):
        """project_commit_plan is a GitHub PUT on the branch; it must refuse an
        archived workstream rather than mutating it, and must not contact
        GitHub to do so."""
        mock_find.return_value = {
            "workstreamId": "ws-arch", "archived": True,
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "qa/retro-x",
        }
        result = server.project_commit_plan(
            workstream_id="ws-arch", content="# Plan")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream")
    def test_verify_branch_rejected_for_archived(self, mock_find, mock_gh):
        """project_verify_branch dispatches an agent onto the branch, which is
        new work on an archived round; it must be refused without dispatching
        the workflow."""
        mock_find.return_value = {
            "workstreamId": "ws-arch", "archived": True,
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "qa/retro-x",
        }
        result = server.project_verify_branch(workstream_id="ws-arch")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_submit_unknown_workstream_id_falls_back_to_branch(
            self, mock_find, mock_by_branch, mock_post):
        """An unknown workstream_id does not short-circuit the branch guard. The
        controller's resolveOrRegister ignores an unknown body workstreamId and
        then resolves targetBranch, so a submission carrying both an unknown id
        and an archived target_branch would be routed to the archived round; the
        guard must fall back to branch resolution and refuse it."""
        mock_find.return_value = None  # the workstream_id does not resolve
        mock_by_branch.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.workstream_submit_task(
            prompt="Fix the bug",
            workstream_id="ws-unknown",
            repo_url="git@github.com:org/repo.git",
            target_branch="qa/retro-x",
        )
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        mock_by_branch.assert_called_once_with(
            "git@github.com:org/repo.git", "qa/retro-x")
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    @patch.object(server, "_find_workstream")
    def test_submit_archived_guard_uses_resolved_ws_without_refetch(
            self, mock_find, mock_post):
        """The resolved workstream dict is handed to the guard, so the archived
        flag is read once rather than re-fetched — a second lookup that failed
        transiently would otherwise be read as "not archived"."""
        mock_find.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.workstream_submit_task(
            prompt="Fix the bug", workstream_id="ws-arch")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        self.assertEqual(1, mock_find.call_count)
        mock_post.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream")
    def test_commit_plan_archived_guard_uses_resolved_ws_without_refetch(
            self, mock_find, mock_gh):
        """project_commit_plan hands its already-resolved workstream to the
        guard, so the archived check does not re-fetch (and cannot fail open on
        a transient second lookup)."""
        mock_find.return_value = {
            "workstreamId": "ws-arch", "archived": True,
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "qa/retro-x",
        }
        result = server.project_commit_plan(
            workstream_id="ws-arch", content="# Plan")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        self.assertEqual(1, mock_find.call_count)
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream")
    def test_verify_branch_archived_guard_uses_resolved_ws_without_refetch(
            self, mock_find, mock_gh):
        """project_verify_branch hands its already-resolved workstream to the
        guard, so the archived check does not re-fetch (and cannot fail open on
        a transient second lookup)."""
        mock_find.return_value = {
            "workstreamId": "ws-arch", "archived": True,
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "qa/retro-x",
        }
        result = server.project_verify_branch(workstream_id="ws-arch")
        self.assertFalse(result["ok"])
        self.assertIn("archived", result["error"])
        self.assertEqual(1, mock_find.call_count)
        mock_gh.assert_not_called()

    @patch.object(server, "_find_workstream_by_branch")
    def test_archived_branch_error_fires_for_archived_round(self, mock_by_branch):
        """The branch guard resolves the round without the scope filter and
        refuses an archived one, naming the round in the error."""
        mock_by_branch.return_value = {"workstreamId": "ws-arch", "archived": True}
        err = server._archived_branch_error(
            "commit a plan", "git@github.com:org/repo.git", "qa/retro-x")
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("Cannot commit a plan", err["error"])
        self.assertIn("ws-arch", err["error"])
        mock_by_branch.assert_called_once_with(
            "git@github.com:org/repo.git", "qa/retro-x", enforce_scope=False)

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_find_workstream_by_branch")
    def test_archived_branch_error_silent_for_live_and_unregistered(
            self, mock_by_branch, mock_find):
        """A live round and a branch no round owns both pass, without a
        second (scope-filtered) lookup of the resolved round."""
        mock_by_branch.return_value = {"workstreamId": "ws-live"}
        self.assertIsNone(server._archived_branch_error(
            "commit a plan", "git@github.com:org/repo.git", "feature/live"))
        mock_by_branch.return_value = None
        self.assertIsNone(server._archived_branch_error(
            "commit a plan", "git@github.com:org/repo.git", "feature/new"))
        mock_find.assert_not_called()

    def _live_ws(self):
        return {
            "workstreamId": "ws-live",
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/live",
        }

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_verify_branch_rejected_for_archived_branch_override(
            self, mock_find, mock_by_branch, mock_gh):
        """A live workstream_id plus an archived round's branch must not
        dispatch the verify workflow onto that archived branch."""
        mock_find.return_value = self._live_ws()
        mock_by_branch.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.project_verify_branch(
            workstream_id="ws-live", branch="qa/retro-x")
        self.assertFalse(result["ok"])
        self.assertIn("ws-arch", result["error"])
        mock_by_branch.assert_called_once_with(
            "https://github.com/org/repo", "qa/retro-x", enforce_scope=False)
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_verify_branch_allowed_for_live_branch_override(
            self, mock_find, mock_by_branch, mock_gh):
        """An override naming a branch no archived round owns is dispatched on
        that branch."""
        mock_find.return_value = self._live_ws()
        mock_by_branch.return_value = None
        mock_gh.return_value = {"ok": True, "status": 204}
        result = server.project_verify_branch(
            workstream_id="ws-live", branch="feature/side")
        self.assertTrue(result["ok"], msg=result.get("error"))
        self.assertEqual("feature/side", result["branch"])
        self.assertEqual("feature/side", mock_gh.call_args[0][2]["ref"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_verify_branch_own_default_branch_skips_branch_lookup(
            self, mock_find, mock_by_branch, mock_gh):
        """No override (or an override equal to the workstream's own default
        branch) is covered by the id guard alone."""
        mock_find.return_value = self._live_ws()
        mock_gh.return_value = {"ok": True, "status": 204}
        self.assertTrue(server.project_verify_branch(workstream_id="ws-live")["ok"])
        self.assertTrue(server.project_verify_branch(
            workstream_id="ws-live", branch="feature/live")["ok"])
        mock_by_branch.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_commit_plan_rejected_for_archived_branch_override(
            self, mock_find, mock_by_branch, mock_gh):
        """A live workstream_id plus an archived round's branch must not commit
        the plan onto that archived branch."""
        mock_find.return_value = self._live_ws()
        mock_by_branch.return_value = {"workstreamId": "ws-arch", "archived": True}
        result = server.project_commit_plan(
            workstream_id="ws-live", content="# Plan", branch="qa/retro-x")
        self.assertFalse(result["ok"])
        self.assertIn("Cannot commit a plan", result["error"])
        self.assertIn("ws-arch", result["error"])
        mock_gh.assert_not_called()

    @patch.object(server, "_github_request")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_find_workstream")
    def test_commit_plan_live_branch_override_passes_guard(
            self, mock_find, mock_by_branch, mock_gh):
        """An override naming a live round passes the guard and reaches
        GitHub."""
        mock_find.return_value = self._live_ws()
        mock_by_branch.return_value = {"workstreamId": "ws-other"}
        mock_gh.return_value = {"ok": False, "error": "stop here"}
        server.project_commit_plan(
            workstream_id="ws-live", content="# Plan", branch="feature/other")
        mock_by_branch.assert_called_once()
        mock_gh.assert_called()

    def _context_with_override(self, mock_client_fn, mock_by_branch, mock_get,
                               mock_pr, mock_find, **kwargs):
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_pr.return_value = {"ok": True, "found": False}
        mock_find.return_value = {
            "workstreamId": "ws-live",
            "repoUrl": "git@github.com:org/repo.git",
            "defaultBranch": "feature/live",
        }
        mock_by_branch.return_value = {"workstreamId": "ws-branch"}
        mock_get.return_value = [
            {"jobId": "job-branch", "timestamp": "2026-10-10T00:00:00Z",
             "status": "SUCCESS", "description": "retro"},
        ]
        return server.workstream_context(
            workstream_id="ws-live", include_commits=False, **kwargs)

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_get_memory_client")
    def test_context_jobs_follow_branch_override_on_workstream_id(
            self, mock_client_fn, mock_by_branch, mock_get, mock_pr, mock_find):
        """A workstream_id with only a branch override keys memories on the
        override branch, so the job timeline must follow that round rather
        than attaching the id's own jobs to the overridden branch."""
        result = self._context_with_override(
            mock_client_fn, mock_by_branch, mock_get, mock_pr, mock_find,
            branch="qa/retro-x")
        self.assertTrue(result["ok"])
        mock_by_branch.assert_called_once_with(
            "git@github.com:org/repo.git", "qa/retro-x")
        self.assertIn("/api/workstreams/ws-branch/jobs", mock_get.call_args[0][0])
        self.assertEqual([j["jobId"] for j in result["jobs"]], ["job-branch"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_get_memory_client")
    def test_context_jobs_follow_repo_override_on_workstream_id(
            self, mock_client_fn, mock_by_branch, mock_get, mock_pr, mock_find):
        """A workstream_id with only a repo_url override resolves the timeline
        from the overridden repository and the id's default branch."""
        result = self._context_with_override(
            mock_client_fn, mock_by_branch, mock_get, mock_pr, mock_find,
            repo_url="git@github.com:org/fork.git")
        self.assertTrue(result["ok"])
        mock_by_branch.assert_called_once_with(
            "git@github.com:org/fork.git", "feature/live")
        self.assertIn("/api/workstreams/ws-branch/jobs", mock_get.call_args[0][0])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_find_recent_pr_by_branch")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_find_workstream_by_branch")
    @patch.object(server, "_get_memory_client")
    def test_context_jobs_use_workstream_id_without_overrides(
            self, mock_client_fn, mock_by_branch, mock_get, mock_pr, mock_find):
        """With no override the id is the sole source, used directly."""
        result = self._context_with_override(
            mock_client_fn, mock_by_branch, mock_get, mock_pr, mock_find)
        self.assertTrue(result["ok"])
        mock_by_branch.assert_not_called()
        self.assertIn("/api/workstreams/ws-live/jobs", mock_get.call_args[0][0])
