"""Tests for graceful degradation in the shared inference layer.

These cover the four ways the layer used to fail when no model was
reachable: a health flag frozen at process start, transport errors escaping
``generate()`` as tool errors, a passthrough fallback that an explicitly
pinned backend could never reach, and a raw context dump being presented as
synthesized text.
"""

import json
import os
import socketserver
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(__file__))

from inference import (  # noqa: E402
    AutoBackend,
    InferenceBackend,
    InferenceUnavailable,
    LlamaCppBackend,
    OllamaBackend,
    PassthroughBackend,
    Synthesis,
    create_backend,
)


class FakeBackend(InferenceBackend):
    """Backend whose health and generate outcome are controlled by the test."""

    def __init__(self, healthy=True, text="synthesized"):
        self.healthy = healthy
        self.text = text
        self.probe_count = 0
        self.generate_count = 0
        super().__init__()

    @property
    def name(self):
        return f"fake (healthy={self.healthy})"

    def _probe(self):
        self.probe_count += 1
        return self.healthy

    def generate(self, prompt, system=None, max_tokens=1024, temperature=0.3):
        self.generate_count += 1
        if not self.healthy:
            raise InferenceUnavailable(self.name, "fake backend is down")
        return self.text


class HealthCacheTest(unittest.TestCase):
    """``available`` must track the present, not the state at construction."""

    def setUp(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)

    def test_available_reflects_a_backend_that_went_down(self):
        backend = FakeBackend(healthy=True)
        self.assertTrue(backend.available)

        backend.healthy = False

        self.assertFalse(
            backend.available,
            "a backend that died must stop reporting itself as available; "
            "the old code memoized the first probe forever",
        )

    def test_available_reflects_a_backend_that_came_back(self):
        backend = FakeBackend(healthy=False)
        self.assertFalse(backend.available)

        backend.healthy = True

        self.assertTrue(
            backend.available,
            "a recovered backend must be usable without a process restart",
        )

    def test_probe_result_is_cached_within_the_ttl(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "3600"
        backend = FakeBackend(healthy=True)

        for _ in range(5):
            self.assertTrue(backend.available)

        self.assertEqual(
            backend.probe_count, 1,
            "health probes must not run on every read, or status checks "
            "would issue a network call per request",
        )

    def test_invalidate_forces_a_reprobe(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "3600"
        backend = FakeBackend(healthy=True)
        self.assertTrue(backend.available)

        backend.healthy = False
        backend.invalidate_availability()

        self.assertFalse(backend.available)
        self.assertEqual(backend.probe_count, 2)


class SynthesizeDegradationTest(unittest.TestCase):
    """``synthesize`` reports failure as a value instead of raising."""

    def test_degrades_instead_of_raising(self):
        result = FakeBackend(healthy=False).synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertIsNone(result.text)
        self.assertIn("fake backend is down", result.reason)

    def test_returns_text_when_healthy(self):
        result = FakeBackend(healthy=True, text="an answer").synthesize("prompt")

        self.assertFalse(result.degraded)
        self.assertEqual(result.text, "an answer")
        self.assertIsNone(result.reason)

    def test_empty_response_counts_as_degraded(self):
        result = FakeBackend(healthy=True, text="   ").synthesize("prompt")

        self.assertTrue(
            result.degraded,
            "whitespace is not an answer; returning it as one would put an "
            "empty summary in front of the caller",
        )

    def test_failed_request_invalidates_health(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "3600"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)

        backend = FakeBackend(healthy=True)
        self.assertTrue(backend.available)
        backend.healthy = False

        backend.synthesize("prompt")

        self.assertFalse(
            backend.available,
            "a request that just failed is stronger evidence than a stale "
            "probe, so health must be re-evaluated after it",
        )

    def test_generate_still_raises_for_direct_callers(self):
        with self.assertRaises(InferenceUnavailable):
            FakeBackend(healthy=False).generate("prompt")


class PassthroughTest(unittest.TestCase):
    """The passthrough banner is a context dump, not an answer."""

    def test_reports_itself_unavailable(self):
        self.assertFalse(PassthroughBackend().available)

    def test_synthesize_is_degraded(self):
        result = PassthroughBackend().synthesize("the prompt")

        self.assertTrue(result.degraded)
        self.assertIsNone(
            result.text,
            "returning the banner as text is what let raw context dumps be "
            "stored as memories",
        )

    def test_generate_keeps_the_banner_for_the_memory_store_guard(self):
        text = PassthroughBackend().generate("the prompt")

        self.assertTrue(text.startswith(PassthroughBackend.BANNER))
        self.assertIn("the prompt", text)


class AutoBackendTest(unittest.TestCase):
    """Auto-detection re-resolves rather than deciding once at startup."""

    def setUp(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)

    def test_prefers_the_first_healthy_candidate(self):
        first, second = FakeBackend(healthy=True), FakeBackend(healthy=True)
        auto = AutoBackend(candidates=[first, second])

        self.assertIs(auto.delegate, first)

    def test_skips_unhealthy_candidates(self):
        down, up = FakeBackend(healthy=False), FakeBackend(healthy=True, text="ok")
        auto = AutoBackend(candidates=[down, up])

        self.assertIs(auto.delegate, up)
        self.assertEqual(auto.synthesize("prompt").text, "ok")

    def test_degrades_when_every_candidate_is_down(self):
        auto = AutoBackend(candidates=[FakeBackend(healthy=False)])

        result = auto.synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertFalse(auto.available)

    def test_recovers_when_a_candidate_returns(self):
        candidate = FakeBackend(healthy=False)
        auto = AutoBackend(candidates=[candidate])
        self.assertTrue(auto.synthesize("prompt").degraded)

        candidate.healthy = True

        self.assertFalse(
            auto.synthesize("prompt").degraded,
            "a model that comes back must be picked up without restarting "
            "every MCP server holding this backend",
        )
        self.assertTrue(auto.available)

    def test_outage_does_not_reprobe_on_every_call(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "3600"
        candidate = FakeBackend(healthy=False)
        auto = AutoBackend(candidates=[candidate])

        for _ in range(5):
            self.assertTrue(auto.synthesize("prompt").degraded)

        self.assertEqual(
            candidate.probe_count, 1,
            "while everything is down the fallback answers without "
            "attempting a request, so there is no new evidence to act on; "
            "re-probing anyway put a full scan (including a DNS lookup for "
            "the remote host) in front of every single tool call",
        )

    def test_a_real_failure_still_forces_a_reprobe(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "3600"
        candidate = FakeBackend(healthy=True)
        auto = AutoBackend(candidates=[candidate])
        self.assertFalse(auto.synthesize("prompt").degraded)
        probes_before = candidate.probe_count

        candidate.healthy = False
        self.assertTrue(auto.synthesize("prompt").degraded)

        # Invalidation is lazy: the failure clears the cached health and the
        # next resolution pays for the re-probe.
        auto.synthesize("prompt")

        self.assertGreater(
            candidate.probe_count, probes_before,
            "a backend that failed an actual request must be re-probed even "
            "inside the TTL, so a healthier candidate can take over",
        )

    def test_falls_back_to_a_second_candidate_when_the_first_dies(self):
        first = FakeBackend(healthy=True, text="from first")
        second = FakeBackend(healthy=True, text="from second")
        auto = AutoBackend(candidates=[first, second])
        self.assertEqual(auto.synthesize("prompt").text, "from first")

        first.healthy = False

        self.assertEqual(auto.synthesize("prompt").text, "from second")


class CreateBackendTest(unittest.TestCase):
    """Every configuration produces a backend that degrades rather than raises."""

    def setUp(self):
        self._saved = os.environ.pop("AR_CONSULTANT_BACKEND", None)
        if self._saved is not None:
            self.addCleanup(
                os.environ.__setitem__, "AR_CONSULTANT_BACKEND", self._saved,
            )

    def test_auto_returns_a_reresolving_backend(self):
        self.assertIsInstance(create_backend("auto"), AutoBackend)

    def test_explicit_names_are_honoured(self):
        self.assertIsInstance(create_backend("llamacpp"), LlamaCppBackend)
        self.assertIsInstance(create_backend("ollama"), OllamaBackend)
        self.assertIsInstance(create_backend("passthrough"), PassthroughBackend)

    def test_unknown_name_is_rejected(self):
        with self.assertRaises(ValueError):
            create_backend("no-such-backend")

    def test_pinned_backend_degrades_when_unreachable(self):
        backend = create_backend("llamacpp")
        backend.base_url = "http://127.0.0.1:1"  # nothing listens here

        result = backend.synthesize("prompt")

        self.assertTrue(
            result.degraded,
            "pinning a backend used to bypass every availability check, so "
            "a dead server surfaced as a hard tool error",
        )
        self.assertIsNotNone(result.reason)


class _LlamaCppHandler(BaseHTTPRequestHandler):
    """Minimal stand-in for llama-server's health and completions endpoints."""

    healthy = True

    def log_message(self, fmt, *args):
        pass

    def _respond(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not _LlamaCppHandler.healthy:
            self.send_error(503)
            return
        self._respond({"status": "ok"})

    def do_POST(self):
        if not _LlamaCppHandler.healthy:
            self.send_error(503)
            return
        self._respond(
            {"choices": [{"message": {"content": "a real summary"}}]}
        )


class _LocalHTTPServer(HTTPServer):
    """HTTPServer that skips the reverse-DNS lookup when binding.

    ``HTTPServer.server_bind`` resolves the bound address with
    ``socket.getfqdn()``, which stalls for ~35s on a host with no reverse
    DNS for 127.0.0.1. Only the human-readable ``server_name`` depends on
    it, and no test reads that.
    """

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


class LlamaCppLiveTest(unittest.TestCase):
    """End-to-end against a real socket, covering the outage the user hit."""

    def setUp(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)

        _LlamaCppHandler.healthy = True
        self.server = _LocalHTTPServer(("127.0.0.1", 0), _LlamaCppHandler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.shutdown)

        port = self.server.server_address[1]
        self.backend = LlamaCppBackend(base_url=f"http://127.0.0.1:{port}")

    def test_synthesizes_while_the_server_is_up(self):
        self.assertTrue(self.backend.available)
        result = self.backend.synthesize("prompt")

        self.assertFalse(result.degraded)
        self.assertEqual(result.text, "a real summary")

    def test_degrades_when_the_server_starts_failing(self):
        self.assertTrue(self.backend.available)

        _LlamaCppHandler.healthy = False

        result = self.backend.synthesize("prompt")
        self.assertTrue(result.degraded)
        self.assertFalse(
            self.backend.available,
            "status must agree with what synthesize just did, rather than "
            "reporting the health recorded at startup",
        )

    def test_recovers_without_reconstruction(self):
        _LlamaCppHandler.healthy = False
        self.assertTrue(self.backend.synthesize("prompt").degraded)

        _LlamaCppHandler.healthy = True

        result = self.backend.synthesize("prompt")
        self.assertFalse(result.degraded)
        self.assertEqual(result.text, "a real summary")

    def test_connection_refused_does_not_escape(self):
        self.server.shutdown()
        self.server.server_close()

        result = self.backend.synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertIsInstance(result, Synthesis)


if __name__ == "__main__":
    unittest.main()
