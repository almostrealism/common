"""Tests for the resource-limit parameters of ``workstream_submit_task``.

The controller clamps ``maxTurns`` and ``maxBudgetUsd`` to the workstream's
limits and holds ``inactivityTimeoutSeconds`` to the runner's own silence
budget; the tool's part is to forward what the caller asked for, and to
refuse an inactivity budget that is not positive before it reaches the
controller.
"""

import inspect
import os
import sys
import unittest
from unittest.mock import patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import server, _grant_all_scopes  # noqa: E402


class TestSubmitResourceLimits(unittest.TestCase):

    @patch.object(server, "_controller_post")
    def test_inactivity_timeout_is_forwarded(self, mock_post):
        """A positive silence budget reaches the controller as inactivityTimeoutSeconds."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-quiet"}
        server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test", inactivity_timeout_seconds=600)
        self.assertEqual(600, mock_post.call_args[0][1]["inactivityTimeoutSeconds"])

    @patch.object(server, "_controller_post")
    def test_inactivity_timeout_is_omitted_by_default(self, mock_post):
        """Without the parameter the job uses its runner's budget, so nothing is sent."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-default"}
        server.workstream_submit_task(prompt="Task", workstream_id="ws-test")
        self.assertNotIn("inactivityTimeoutSeconds", mock_post.call_args[0][1])

    @patch.object(server, "_controller_post")
    def test_non_positive_inactivity_timeout_is_rejected(self, mock_post):
        """A budget that is not positive is refused and never forwarded."""
        _grant_all_scopes()
        for value in (0, -30):
            result = server.workstream_submit_task(
                prompt="Task", workstream_id="ws-test", inactivity_timeout_seconds=value)
            self.assertFalse(result["ok"])
            self.assertIn("inactivity_timeout_seconds", result["error"])
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_clamped_limits_are_passed_back(self, mock_post):
        """The controller's report of clamped limits reaches the caller."""
        _grant_all_scopes()
        mock_post.return_value = {
            "ok": True, "jobId": "job-clamped", "maxTurns": 200,
            "clampedLimits": {"maxTurns": "200"},
        }
        result = server.workstream_submit_task(
            prompt="Task", workstream_id="ws-test", max_turns=5000)
        self.assertEqual(5000, mock_post.call_args[0][1]["maxTurns"])
        self.assertEqual({"maxTurns": "200"}, result["clampedLimits"])

    def test_inactivity_timeout_does_not_shift_positional_parameters(self):
        """The new parameter follows every pre-existing one, so positional calls keep their meaning."""
        names = list(inspect.signature(server.workstream_submit_task).parameters)
        wall_clock = names.index("max_wall_clock_hours")
        self.assertEqual("sensitive_file_protection_enabled", names[wall_clock + 1])
        self.assertEqual(names.index("self_notify") + 1,
                         names.index("inactivity_timeout_seconds"))
        self.assertLess(names.index("inactivity_timeout_seconds"), names.index("model"))


if __name__ == "__main__":
    unittest.main()
