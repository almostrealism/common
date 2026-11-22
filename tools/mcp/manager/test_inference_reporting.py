"""Tests for how the manager tools report the inference backend's state.

Covers the on-demand ``inference_health`` tool and the structured ``cause``
every degraded ``memory_recall`` carries, including the unexpected-error path
that does not go through ``InferenceBackend.synthesize``.
"""

import os
import sys
import unittest
from unittest.mock import MagicMock, patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import server, _grant_all_scopes  # noqa: E402
import inference  # noqa: E402
from inference import PassthroughBackend  # noqa: E402


class TestInferenceHealth(unittest.TestCase):
    """``inference_health`` reports the backend diagnosis on demand."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_get_llm", return_value=None)
    def test_reports_when_no_backend_can_be_built(self, _mock_llm):
        result = server.inference_health()
        self.assertFalse(result["ok"])
        self.assertIn("No inference backend", result["error"])
        # The no-backend case is exactly what this tool diagnoses, so it still
        # carries the structured fields a live probe returns, classified
        # not_configured.
        self.assertFalse(result["available"])
        self.assertEqual("not_configured", result["classification"])
        self.assertIsNone(result["backend"])
        self.assertEqual("", result["endpoint"])
        self.assertTrue(result["operator_hint"])
        self.assertIsNone(result["last_synthesis_success"])

    @patch.object(server, "_get_llm")
    def test_reports_an_available_backend(self, mock_llm):
        llm = MagicMock()
        llm.probe_diagnosis.return_value = {
            "available": True, "backend": "llamacpp (http://host:8084)",
            "selection": "auto-detected llamacpp", "endpoint": "http://host:8084",
            "classification": None, "http_status": None, "detail": "",
            "operator_hint": "",
        }
        llm.last_success_at = "2026-11-21T00:00:00+00:00"
        mock_llm.return_value = llm

        result = server.inference_health()

        self.assertTrue(result["ok"])
        self.assertTrue(result["available"])
        self.assertEqual("auto-detected llamacpp", result["backend_selection"])
        self.assertEqual("http://host:8084", result["endpoint"])
        self.assertEqual("2026-11-21T00:00:00+00:00",
                         result["last_synthesis_success"])

    @patch.object(server, "_get_llm")
    def test_reports_a_classified_failure_with_candidates(self, mock_llm):
        llm = MagicMock()
        llm.probe_diagnosis.return_value = {
            "available": False, "backend": "openrouter (minimax/minimax-m3)",
            "selection": "auto-detection reached no backend",
            "endpoint": "https://openrouter.ai",
            "classification": "credential_rejected", "http_status": 401,
            "detail": "invalid key",
            "operator_hint": "Rotate the openrouter-api-key secret.",
            "candidates": [{"backend": "llamacpp", "available": False,
                            "classification": "connection_refused"}],
        }
        llm.last_success_at = None
        mock_llm.return_value = llm

        result = server.inference_health()

        self.assertTrue(result["ok"])
        self.assertFalse(result["available"])
        self.assertEqual("credential_rejected", result["classification"])
        self.assertEqual(401, result["http_status"])
        self.assertIsNone(result["last_synthesis_success"])
        self.assertEqual(1, len(result["candidates"]))


class TestRecallUnexpectedSynthesisError(unittest.TestCase):
    """An error raised outside ``synthesize`` still yields a classified cause."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(inference, "build_consult_prompt",
                  side_effect=RuntimeError("prompt layout bug"))
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_raised_error_keeps_memories_and_carries_a_cause(
            self, mock_client_fn, mock_llm, _prompt):
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "a note", "score": 0.5}]
        mock_client_fn.return_value = client
        backend = PassthroughBackend()
        mock_llm.return_value = backend

        result = server.memory_recall(query="test", scope="all")

        self.assertTrue(result["ok"])
        self.assertEqual("a note", result["memories"][0]["content"])
        self.assertTrue(result["degraded"])
        self.assertIn("prompt layout bug", result["note"])
        cause = result["cause"]
        self.assertEqual("unreachable", cause["classification"])
        self.assertEqual(backend.provider, cause["backend"])
        self.assertIsNone(cause["http_status"])
        self.assertIn("prompt layout bug", cause["detail"])
        self.assertTrue(cause["operator_hint"])
        self.assertIn("Tell the person", result["agent_action_required"])


if __name__ == "__main__":
    unittest.main()
