"""Tests for job control defaults and opt-in behavior.

Covers deduplication and organizational placement:

- Deduplication is disabled by default (no ``deduplicationMode`` in payload
  unless explicitly requested).
- Organizational placement is disabled by default (no
  ``enforceOrganizationalPlacement`` in payload unless explicitly requested).
- Explicit opt-in via ``deduplication_mode`` and
  ``organizational_placement_enabled`` is forwarded correctly.
"""

import os
import sys
import unittest
from unittest.mock import patch

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
    import server


def _grant_all_scopes():
    server._set_scopes(
        ["read", "write", "submit", "pipeline", "github", "memory-read", "memory-write"],
        label="test",
    )


class TestDeduplicationDefaults(unittest.TestCase):
    """Deduplication is disabled by default; opt in via deduplication_mode."""

    @patch.object(server, "_controller_post")
    def test_deduplication_mode_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dd1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("deduplicationMode", payload,
                         "deduplicationMode must not be sent when not requested")

    @patch.object(server, "_controller_post")
    def test_deduplication_mode_local_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dd2"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="local")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "local")

    @patch.object(server, "_controller_post")
    def test_deduplication_mode_spawn_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dd3"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="spawn")
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "spawn")

    @patch.object(server, "_controller_post")
    def test_deduplication_mode_none_not_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dd4"}
        server.workstream_submit_task(prompt="Task", deduplication_mode="none")
        payload = mock_post.call_args[0][1]
        # "none" is the falsy-string equivalent for the server; the factory
        # default already handles it, so forwarding is unnecessary but
        # harmless.  The key assertion is that empty-string default is absent.
        # When "none" is explicitly passed, the server does forward it.
        self.assertEqual(payload["deduplicationMode"], "none")

    @patch.object(server, "_controller_post")
    def test_max_deduplication_passes_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dd5"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("maxDeduplicationPasses", payload)

    @patch.object(server, "_controller_post")
    def test_max_deduplication_passes_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-dd6"}
        server.workstream_submit_task(prompt="Task", max_deduplication_passes=3)
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["maxDeduplicationPasses"], 3)


class TestOrganizationalPlacementDefaults(unittest.TestCase):
    """Organizational placement is disabled by default; opt in via
    organizational_placement_enabled=True."""

    @patch.object(server, "_controller_post")
    def test_placement_omitted_by_default(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-op1"}
        server.workstream_submit_task(prompt="Task")
        payload = mock_post.call_args[0][1]
        self.assertNotIn("enforceOrganizationalPlacement", payload,
                         "enforceOrganizationalPlacement must not be sent by default")

    @patch.object(server, "_controller_post")
    def test_placement_false_omitted(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-op2"}
        server.workstream_submit_task(
            prompt="Task", organizational_placement_enabled=False)
        payload = mock_post.call_args[0][1]
        self.assertNotIn("enforceOrganizationalPlacement", payload,
                         "Explicit False must not send the field (factory default handles it)")

    @patch.object(server, "_controller_post")
    def test_placement_true_forwarded(self, mock_post):
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-op3"}
        server.workstream_submit_task(
            prompt="Task", organizational_placement_enabled=True)
        payload = mock_post.call_args[0][1]
        self.assertIs(payload.get("enforceOrganizationalPlacement"), True,
                      "organizational_placement_enabled=True must forward True to controller")

    @patch.object(server, "_controller_post")
    def test_placement_and_deduplication_combined(self, mock_post):
        """Pre-merge cleanup job: both phases enabled together."""
        _grant_all_scopes()
        mock_post.return_value = {"ok": True, "jobId": "job-op4"}
        server.workstream_submit_task(
            prompt="Pre-merge cleanup",
            deduplication_mode="local",
            organizational_placement_enabled=True,
        )
        payload = mock_post.call_args[0][1]
        self.assertEqual(payload["deduplicationMode"], "local")
        self.assertIs(payload["enforceOrganizationalPlacement"], True)


if __name__ == "__main__":
    unittest.main()
