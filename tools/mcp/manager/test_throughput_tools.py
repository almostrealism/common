"""Tests for the workspace-throughput retrospective MCP tool.

Mocks the controller HTTP layer so the tool's parameter handling and the
preset-vs-explicit-window contract are exercised without a live controller.
"""

import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _set_workspaces,
    _clear_workspaces, _reset_workspace_cache,
)
import throughput_tools  # noqa: E402


class TestThroughputWindow(unittest.TestCase):

    @patch.object(server, "_controller_get")
    def test_preset_passes_preset_param(self, mock_get):
        """A preset request forwards ?preset= to the window endpoint."""
        _grant_all_scopes()
        mock_get.return_value = {"finished": 3}
        result = throughput_tools.throughput_window(preset="3h")
        mock_get.assert_called_once()
        path = mock_get.call_args[0][0]
        self.assertTrue(path.startswith("/api/throughput/window?"))
        self.assertIn("preset=3h", path)
        self.assertEqual(result, {"finished": 3})

    @patch.object(server, "_controller_get")
    def test_explicit_window_passes_from_to(self, mock_get):
        """An explicit start/end request forwards ?from=&to= and the workstream."""
        _grant_all_scopes()
        mock_get.return_value = {"finished": 1}
        throughput_tools.throughput_window(
            start="2026-11-28T00:00:00Z", end="2026-11-28T06:00:00Z",
            workstream_id="ws-1")
        path = mock_get.call_args[0][0]
        self.assertIn("from=", path)
        self.assertIn("to=", path)
        self.assertIn("workstream=ws-1", path)
        self.assertNotIn("preset=", path)

    @patch.object(server, "_controller_get")
    def test_missing_window_is_rejected_without_controller_call(self, mock_get):
        """Neither a preset nor a start/end pair is a client-side error."""
        _grant_all_scopes()
        result = throughput_tools.throughput_window()
        self.assertFalse(result["ok"])
        mock_get.assert_not_called()

    @patch.object(server, "_controller_get")
    def test_preset_with_explicit_bound_is_rejected(self, mock_get):
        """A preset together with start or end is ambiguous and never reaches the controller."""
        _grant_all_scopes()
        for kwargs in ({"start": "2026-11-28T00:00:00Z", "end": "2026-11-28T06:00:00Z"},
                       {"start": "2026-11-28T00:00:00Z"},
                       {"end": "2026-11-28T06:00:00Z"}):
            result = throughput_tools.throughput_window(preset="3h", **kwargs)
            self.assertFalse(result["ok"], kwargs)
            self.assertIn("not both", result["error"])
        mock_get.assert_not_called()


class TestThroughputWindowWorkspaceScope(unittest.TestCase):
    """A workspace-scoped token reads only its own workspace's retrospective."""

    WORKSTREAMS = [
        {"workstreamId": "w-own", "slackWorkspaceId": "TAAA"},
        {"workstreamId": "w-other", "slackWorkspaceId": "TBBB"},
    ]

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def _route(self, path, *args, **kwargs):
        if path.startswith("/api/workstreams"):
            return self.WORKSTREAMS
        return {"finished": 2}

    @patch.object(server, "_controller_get")
    def test_scoped_token_reads_own_workstream(self, mock_get):
        mock_get.side_effect = self._route
        _set_workspaces("TAAA")
        result = throughput_tools.throughput_window(preset="1h", workstream_id="w-own")
        self.assertEqual(result, {"finished": 2})
        self.assertIn("workstream=w-own", mock_get.call_args[0][0])

    @patch.object(server, "_controller_get")
    def test_scoped_token_cannot_read_other_workspace(self, mock_get):
        mock_get.side_effect = self._route
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            throughput_tools.throughput_window(preset="1h", workstream_id="w-other")
        for call in mock_get.call_args_list:
            self.assertFalse(call[0][0].startswith("/api/throughput"))

    @patch.object(server, "_controller_get")
    def test_scoped_token_cannot_read_every_workstream(self, mock_get):
        mock_get.side_effect = self._route
        _set_workspaces("TAAA")
        result = throughput_tools.throughput_window(preset="1h")
        self.assertFalse(result["ok"])
        self.assertIn("workstream_id", result["error"])
        for call in mock_get.call_args_list:
            self.assertFalse(call[0][0].startswith("/api/throughput"))

    @patch.object(server, "_controller_get")
    def test_unscoped_token_reads_every_workstream(self, mock_get):
        mock_get.side_effect = self._route
        result = throughput_tools.throughput_window(preset="1h")
        self.assertEqual(result, {"finished": 2})
        self.assertNotIn("workstream=", mock_get.call_args[0][0])


if __name__ == "__main__":
    unittest.main()
