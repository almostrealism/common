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

from manager_test_support import server, _grant_all_scopes  # noqa: E402
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


if __name__ == "__main__":
    unittest.main()
