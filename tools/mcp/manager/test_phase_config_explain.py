"""Tests for the ``phase_config_explain`` diagnostic tool.

Covers the client-side parsing/validation of the optional per-job override
arguments, the controller path and payload shape, and pass-through of the
resolution map the controller returns. Shared fixtures live in
``manager_test_support``.
"""

import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from inference import Synthesis  # noqa: E402,F401
from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _clear_scopes,
)


class TestPhaseConfigExplain(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_controller_post")
    def test_empty_overrides_post_empty_payload(self, mock_post):
        mock_post.return_value = {
            "ok": True,
            "workstreamId": "ws-x",
            "phaseConfigResolution": {
                "primary": {
                    "runner": {"value": "claude", "source": "controller default"},
                    "model": {"value": None, "source": "none"},
                    "effort": {"value": None, "source": "none"},
                    "provider": {"value": None, "source": "none"},
                },
            },
        }
        result = server.phase_config_explain(workstream_id="ws-x")
        path, payload = mock_post.call_args[0][:2]
        self.assertEqual(path, "/api/workstreams/ws-x/phase-config")
        self.assertEqual(payload, {})
        self.assertTrue(result["ok"])
        self.assertIn("phaseConfigResolution", result)
        self.assertEqual(
            result["phaseConfigResolution"]["primary"]["runner"]["source"],
            "controller default")

    @patch.object(server, "_controller_post")
    def test_override_arguments_build_payload(self, mock_post):
        mock_post.return_value = {"ok": True, "workstreamId": "ws-x"}
        server.phase_config_explain(
            workstream_id="ws-x",
            default_phase_config='{"model": "claude-opus-5-5"}',
            phase_configs='{"review": {"runner": "opencode"}}',
            fallback_default_phase_config='{"runner": "claude"}',
            fallback_phase_configs='{"primary": {"effort": "high"}}',
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["defaultPhaseConfig"], {"model": "claude-opus-5-5"})
        self.assertEqual(payload["phaseConfigs"], {"review": {"runner": "opencode"}})
        self.assertEqual(payload["fallbackPhaseConfig"], {"runner": "claude"})
        self.assertEqual(payload["fallbackPhaseConfigs"], {"primary": {"effort": "high"}})

    @patch.object(server, "_controller_post")
    def test_invalid_runner_rejected_before_controller(self, mock_post):
        result = server.phase_config_explain(
            workstream_id="ws-x",
            default_phase_config='{"runner": "not-a-runner"}')
        self.assertFalse(result["ok"])
        self.assertIn("not-a-runner", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_controller_error_passes_through(self, mock_post):
        mock_post.return_value = {
            "ok": False,
            "workstreamId": "ws-x",
            "error": "Invalid model 'gpt-4' for runner 'claude' (phase review).",
        }
        result = server.phase_config_explain(
            workstream_id="ws-x",
            phase_configs='{"review": {"model": "gpt-4"}}')
        self.assertFalse(result["ok"])
        self.assertIn("gpt-4", result["error"])

    def test_requires_read_scope(self):
        _clear_scopes()
        with self.assertRaises(PermissionError):
            server.phase_config_explain(workstream_id="ws-x")


if __name__ == "__main__":
    unittest.main()
