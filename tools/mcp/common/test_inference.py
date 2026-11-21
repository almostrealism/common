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
import tempfile
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
    OpenRouterBackend,
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


class _FixedProbeBackend(InferenceBackend):
    """A backend whose probe outcome is a fixed value, decoupled from health.

    Real server-backed backends probe live in ``probe_diagnosis`` while the
    auto delegate reads cached health; this stand-in reproduces that by
    returning a constant probe regardless of the cache, so a test can drive the
    snapshot the health scan sees directly.
    """

    def __init__(self, label, available):
        self._label = label
        self._available = available
        super().__init__()

    @property
    def name(self):
        return self._label

    def _probe(self):
        return self._available

    def probe_diagnosis(self):
        return self._diagnosis(
            self._available, None if self._available else "connection_refused",
            None, "")

    def generate(self, prompt, system=None, max_tokens=1024, temperature=0.3):
        if not self._available:
            raise InferenceUnavailable(self.name, "down")
        return "ok"


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

    def test_selection_from_probes_names_the_chosen_and_skips_the_rest(self):
        # The static builder works off the probe snapshot alone, so the reported
        # selection can never disagree with the backend the snapshot chose.
        probes = [
            {"backend": "llamacpp (http://host:8084)", "available": False},
            {"backend": "openrouter (minimax/minimax-m3)", "available": True},
        ]
        chosen = probes[1]

        selection = AutoBackend._selection_from_probes(probes, chosen)

        self.assertIn("auto-detected openrouter (minimax/minimax-m3)", selection)
        self.assertIn("skipped unavailable: llamacpp (http://host:8084)",
                      selection)

    def test_selection_from_probes_reports_all_unavailable_when_none_chosen(self):
        probes = [{"backend": "llamacpp", "available": False},
                  {"backend": "ollama", "available": False}]

        selection = AutoBackend._selection_from_probes(probes, None)

        self.assertIn("every candidate was unavailable", selection)
        self.assertIn("llamacpp, ollama", selection)

    def test_probe_diagnosis_selection_matches_the_probed_backend(self):
        # Even when the cached delegate is the first candidate, a fresh scan
        # that chooses the second must report the second in its selection — the
        # selection is derived from the same snapshot, not the stale delegate.
        down = _FixedProbeBackend("down-one", available=False)
        up = _FixedProbeBackend("up-two", available=True)
        auto = AutoBackend(candidates=[down, up])

        probe = auto.probe_diagnosis()

        self.assertTrue(probe["available"])
        self.assertEqual("up-two", probe["backend"])
        self.assertIn("auto-detected up-two", probe["selection"])
        self.assertIn("skipped unavailable: down-one", probe["selection"])

    def test_probe_diagnosis_selection_reports_every_down_candidate(self):
        auto = AutoBackend(candidates=[FakeBackend(healthy=False),
                                       FakeBackend(healthy=False)])

        probe = auto.probe_diagnosis()

        self.assertFalse(probe["available"])
        self.assertEqual("not_configured", probe["classification"])
        self.assertIn("every candidate was unavailable", probe["selection"])

    def test_no_candidate_diagnosis_does_not_name_the_cached_delegate(self):
        # The cached delegate still believes "died-since" is up (its health is
        # inside the TTL), but the fresh scan finds it down. The all-down
        # diagnosis must carry the fallback's identity, not the stale
        # delegate's backend, endpoint or operator hint.
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "3600"

        class _DiedSince(_FixedProbeBackend):
            def probe_diagnosis(self):
                return self._diagnosis(False, "connection_refused", None, "")

            def operator_hint(self, classification):
                return "stale delegate hint"

        stale = _DiedSince("died-since", available=True)
        auto = AutoBackend(candidates=[stale])
        self.assertIs(auto.delegate, stale)

        probe = auto.probe_diagnosis()

        self.assertFalse(probe["available"])
        self.assertEqual("not_configured", probe["classification"])
        self.assertEqual(PassthroughBackend().provider, probe["backend"])
        self.assertEqual("", probe["endpoint"])
        self.assertNotEqual("stale delegate hint", probe["operator_hint"])
        self.assertIn("AR_CONSULTANT_BACKEND", probe["operator_hint"])
        self.assertIn("every candidate was unavailable", probe["selection"])
        self.assertEqual(["died-since"],
                         [c["backend"] for c in probe["candidates"]])


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
        self.assertIsInstance(create_backend("openrouter"), OpenRouterBackend)
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
    #: The completion body a POST returns. A test sets it to a structurally
    #: malformed value (a list, or a ``message`` that is not an object) to drive
    #: the malformed-response path.
    completion = {"choices": [{"message": {"content": "a real summary"}}]}

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
        self._respond(_LlamaCppHandler.completion)


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
        _LlamaCppHandler.completion = {
            "choices": [{"message": {"content": "a real summary"}}]}
        self.addCleanup(setattr, _LlamaCppHandler, "completion",
                        {"choices": [{"message": {"content": "a real summary"}}]})
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

    def test_connection_refused_is_classified_in_the_cause(self):
        self.server.shutdown()
        self.server.server_close()

        result = self.backend.synthesize("prompt")

        self.assertEqual("connection_refused", result.cause["classification"])
        self.assertTrue(result.cause["endpoint"].startswith("http://127.0.0.1"))
        # The endpoint names where the request went, not the /health or
        # completions path.
        self.assertNotIn("/health", result.cause["endpoint"])

    def test_non_object_completion_body_degrades_as_malformed(self):
        # A 2xx whose JSON is a list instead of an object used to raise an
        # uncaught AttributeError out of generate(); it must degrade like any
        # other unusable response.
        _LlamaCppHandler.completion = []

        result = self.backend.synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertEqual("malformed_response", result.cause["classification"])

    def test_non_object_message_field_degrades_as_malformed(self):
        _LlamaCppHandler.completion = {
            "choices": [{"message": "not an object"}]}

        result = self.backend.synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertEqual("malformed_response", result.cause["classification"])

    def test_cause_backend_is_sanitized_not_the_raw_url(self):
        # The llama.cpp name embeds base_url (for logs); the diagnostic fields
        # must carry the sanitized endpoint label instead, so a configured URL
        # that bore a path or token could never leak through cause.backend.
        self.server.shutdown()
        self.server.server_close()

        cause = self.backend.synthesize("prompt").cause

        self.assertTrue(cause["backend"].startswith("llamacpp ("))
        self.assertNotIn("/health", cause["backend"])
        self.assertNotIn("/v1/chat", cause["backend"])


class LlamaCppProviderLabelTest(unittest.TestCase):
    """The diagnostic label never carries a configured URL's path or token."""

    def test_provider_drops_path_and_userinfo(self):
        backend = LlamaCppBackend(
            base_url="http://user:tok3n@host.example:8084/secret/path")
        self.assertEqual("llamacpp (http://host.example:8084)", backend.provider)
        self.assertNotIn("tok3n", backend.provider)
        self.assertNotIn("secret", backend.provider)

    def test_cause_and_selection_use_the_sanitized_label(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)
        # A token-bearing URL at a port nothing listens on: the request is
        # refused (so it degrades) and the diagnostic fields must still omit the
        # token and path, even though ``name`` embeds the raw URL for logs.
        backend = LlamaCppBackend(
            base_url="http://user:tok3n@127.0.0.1:1/secret/path")

        result = backend.synthesize("prompt")

        self.assertTrue(result.degraded)
        for value in (result.cause["backend"],
                      result.cause["backend_selection"]):
            self.assertNotIn("tok3n", value)
            self.assertNotIn("secret", value)


class _OllamaTagsHandler(BaseHTTPRequestHandler):
    """Minimal stand-in for Ollama's ``/api/tags`` listing endpoint.

    A test sets :attr:`body` to a structurally malformed value (a list, or a
    ``models`` entry that is not an object) to drive the probe's shape-safe
    path.
    """

    body = {"models": [{"name": "qwen2.5-coder:32b"}]}

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        payload = json.dumps(_OllamaTagsHandler.body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class OllamaProbeSafetyTest(unittest.TestCase):
    """A malformed ``/api/tags`` body must not escape the cached health probe.

    ``OllamaBackend.available`` feeds ``AutoBackend`` auto-detection, so a 2xx
    body of the wrong shape used to raise ``AttributeError``/``TypeError`` out
    of ``consult``/``memory_recall`` instead of reporting the backend down.
    """

    def setUp(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)
        _OllamaTagsHandler.body = {"models": [{"name": "qwen2.5-coder:32b"}]}
        self.addCleanup(setattr, _OllamaTagsHandler, "body",
                        {"models": [{"name": "qwen2.5-coder:32b"}]})
        self.server = _LocalHTTPServer(("127.0.0.1", 0), _OllamaTagsHandler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.shutdown)
        port = self.server.server_address[1]
        self.backend = OllamaBackend(
            model="qwen2.5-coder:32b", base_url=f"http://127.0.0.1:{port}")

    def test_available_when_model_is_listed(self):
        self.assertTrue(self.backend.available)

    def test_list_body_reports_down_without_raising(self):
        _OllamaTagsHandler.body = []
        self.assertFalse(self.backend.available)
        self.assertEqual("malformed_response",
                         self.backend.probe_diagnosis()["classification"])

    def test_non_object_model_entry_reports_down_without_raising(self):
        _OllamaTagsHandler.body = {"models": ["not-an-object"]}
        self.assertFalse(self.backend.available)
        self.assertEqual("malformed_response",
                         self.backend.probe_diagnosis()["classification"])

    def test_model_not_pulled_reports_down(self):
        _OllamaTagsHandler.body = {"models": [{"name": "other-model:7b"}]}
        self.assertFalse(self.backend.available)
        self.assertEqual("not_configured",
                         self.backend.probe_diagnosis()["classification"])


class _OpenRouterHandler(BaseHTTPRequestHandler):
    """Stand-in for OpenRouter's ``/key`` and ``/chat/completions`` endpoints.

    Records each request so tests can check what was actually sent, and
    answers according to class-level knobs the test sets.
    """

    key = "test-key"
    limit_remaining = 10.0
    completion_status = 200
    completion = {
        "choices": [{"message": {"content": "a hosted summary"},
                     "finish_reason": "stop"}],
    }
    requests = []

    def log_message(self, fmt, *args):
        pass

    def _respond(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        return self.headers.get("Authorization") == f"Bearer {self.key}"

    def do_GET(self):
        _OpenRouterHandler.requests.append(("GET", self.path, None))
        if not self._authorized():
            self._respond(401, {"error": {"message": "invalid key"}})
            return
        self._respond(200, {"data": {"limit_remaining": self.limit_remaining}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length))
        _OpenRouterHandler.requests.append(("POST", self.path, body))
        if not self._authorized():
            self._respond(401, {"error": {"message": "invalid key"}})
            return
        if self.completion_status != 200:
            self._respond(
                self.completion_status,
                {"error": {"message": "Insufficient credits"}},
            )
            return
        self._respond(200, self.completion)


class OpenRouterTest(unittest.TestCase):
    """The hosted backend: what it sends, and how it degrades."""

    def setUp(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)
        for var in ("AR_CONSULTANT_OPENROUTER_MODEL",
                    "AR_CONSULTANT_OPENROUTER_REASONING",
                    "AR_CONSULTANT_OPENROUTER_DATA_COLLECTION",
                    "AR_CONSULTANT_OPENROUTER_API_KEY",
                    "AR_CONSULTANT_OPENROUTER_API_KEY_FILE"):
            saved = os.environ.pop(var, None)
            if saved is not None:
                self.addCleanup(os.environ.__setitem__, var, saved)

        _OpenRouterHandler.limit_remaining = 10.0
        _OpenRouterHandler.completion_status = 200
        _OpenRouterHandler.completion = {
            "choices": [{"message": {"content": "a hosted summary"},
                         "finish_reason": "stop"}],
        }
        _OpenRouterHandler.requests = []

        self.server = _LocalHTTPServer(("127.0.0.1", 0), _OpenRouterHandler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/api/v1"

    def backend(self, api_key="test-key"):
        return OpenRouterBackend(api_key=api_key, base_url=self.url)

    def posted_body(self):
        posts = [r for r in _OpenRouterHandler.requests if r[0] == "POST"]
        self.assertEqual(len(posts), 1)
        self.assertEqual(posts[0][1], "/api/v1/chat/completions")
        return posts[0][2]

    def test_synthesizes_with_the_configured_model(self):
        backend = self.backend()

        self.assertTrue(backend.available)
        result = backend.synthesize("prompt", system="system")

        self.assertFalse(result.degraded)
        self.assertEqual(result.text, "a hosted summary")
        body = self.posted_body()
        self.assertEqual(body["model"], OpenRouterBackend.DEFAULT_MODEL)
        self.assertEqual(body["messages"][0], {"role": "system", "content": "system"})

    def test_reasoning_is_off_and_retaining_providers_are_excluded_by_default(self):
        self.backend().synthesize("prompt")

        body = self.posted_body()
        # "none" disables reasoning via the documented ``enabled`` field rather
        # than an ``effort`` level OpenRouter does not recognise.
        self.assertEqual(body["reasoning"], {"enabled": False})
        self.assertEqual(body["provider"], {"data_collection": "deny"})

    def test_an_effort_level_is_sent_as_reasoning_effort(self):
        os.environ["AR_CONSULTANT_OPENROUTER_REASONING"] = "medium"

        self.backend().synthesize("prompt")

        self.assertEqual(self.posted_body()["reasoning"], {"effort": "medium"})

    def test_empty_reasoning_setting_leaves_the_model_default(self):
        os.environ["AR_CONSULTANT_OPENROUTER_REASONING"] = ""

        self.backend().synthesize("prompt")

        self.assertNotIn("reasoning", self.posted_body())

    def test_data_collection_allow_is_honoured(self):
        os.environ["AR_CONSULTANT_OPENROUTER_DATA_COLLECTION"] = "allow"

        self.backend().synthesize("prompt")

        self.assertEqual(self.posted_body()["provider"], {"data_collection": "allow"})

    def test_blank_data_collection_keeps_the_privacy_default(self):
        os.environ["AR_CONSULTANT_OPENROUTER_DATA_COLLECTION"] = ""

        self.backend().synthesize("prompt")

        # A blank value must not silently drop the restriction, which would let
        # prompts (carrying the memory corpus) route to retaining providers.
        self.assertEqual(self.posted_body()["provider"], {"data_collection": "deny"})

    def test_unrecognised_data_collection_keeps_the_privacy_default(self):
        os.environ["AR_CONSULTANT_OPENROUTER_DATA_COLLECTION"] = "maybe"

        self.backend().synthesize("prompt")

        self.assertEqual(self.posted_body()["provider"], {"data_collection": "deny"})

    def test_model_comes_from_the_environment(self):
        os.environ["AR_CONSULTANT_OPENROUTER_MODEL"] = "vendor/other-model"

        self.backend().synthesize("prompt")

        self.assertEqual(self.posted_body()["model"], "vendor/other-model")

    def test_empty_model_variable_means_the_default(self):
        os.environ["AR_CONSULTANT_OPENROUTER_MODEL"] = ""

        self.assertEqual(self.backend().model, OpenRouterBackend.DEFAULT_MODEL)

    def test_missing_key_degrades_without_a_request(self):
        backend = self.backend(api_key="")

        self.assertFalse(backend.available)
        result = backend.synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertIn("API key", result.reason)
        self.assertEqual(_OpenRouterHandler.requests, [])

    def test_exhausted_credit_reports_unavailable(self):
        _OpenRouterHandler.limit_remaining = 0

        self.assertFalse(self.backend().available)

    def test_provider_error_message_reaches_the_reason(self):
        _OpenRouterHandler.completion_status = 402

        result = self.backend().synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertIn("402", result.reason)
        self.assertIn("Insufficient credits", result.reason)

    def test_answer_lost_to_reasoning_budget_is_degraded(self):
        _OpenRouterHandler.completion = {
            "choices": [{"message": {"content": "", "reasoning": "..."},
                         "finish_reason": "length"}],
        }

        result = self.backend().synthesize("prompt")

        self.assertTrue(result.degraded)

    def use_key_file(self, content):
        """Point the backend at a temporary secret file holding ``content``."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write(content)
            key_path = f.name
        self.addCleanup(os.remove, key_path)
        os.environ["AR_CONSULTANT_OPENROUTER_API_KEY_FILE"] = key_path

    def test_key_is_read_from_its_secret_file(self):
        self.use_key_file(json.dumps({"openrouter-api-key": "test-key"}))

        backend = OpenRouterBackend(base_url=self.url)

        self.assertTrue(backend.configured)
        self.assertTrue(backend.available)

    def test_single_value_secret_payload_is_accepted(self):
        self.use_key_file(json.dumps({"value": "test-key"}))

        self.assertEqual(OpenRouterBackend.load_api_key(), "test-key")

    def test_unrecognised_secret_file_falls_back_to_the_environment(self):
        self.use_key_file("sk-or-plain-text-is-not-the-convention")
        os.environ["AR_CONSULTANT_OPENROUTER_API_KEY"] = "env-key"

        self.assertEqual(OpenRouterBackend.load_api_key(), "env-key")

    def test_payload_without_the_named_key_is_rejected(self):
        self.use_key_file(json.dumps({"other": "a", "another": "b"}))

        self.assertEqual(OpenRouterBackend.load_api_key(), "")

    def test_single_key_payload_with_an_unexpected_name_is_rejected(self):
        self.use_key_file(json.dumps({"other": "sk-or-v1-secret"}))
        os.environ["AR_CONSULTANT_OPENROUTER_API_KEY"] = "env-key"

        self.assertEqual(OpenRouterBackend.load_api_key(), "env-key")

    def test_auto_prefers_a_local_model_over_the_paid_one(self):
        local = FakeBackend(healthy=True, text="local")
        auto = AutoBackend(candidates=[local, self.backend()])

        self.assertEqual(auto.synthesize("prompt").text, "local")
        self.assertEqual(_OpenRouterHandler.requests, [])

    def test_auto_falls_back_to_openrouter_when_local_is_down(self):
        local = FakeBackend(healthy=False)
        auto = AutoBackend(candidates=[local, self.backend()])

        self.assertEqual(auto.synthesize("prompt").text, "a hosted summary")

    def test_insufficient_credit_degrades_as_quota_exhausted(self):
        _OpenRouterHandler.completion_status = 402

        result = self.backend().synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertEqual("quota_exhausted", result.cause["classification"])
        self.assertEqual(402, result.cause["http_status"])
        self.assertIn("Insufficient credits", result.cause["detail"])
        self.assertIn("127.0.0.1", result.cause["endpoint"])
        self.assertNotIn("/api/v1", result.cause["endpoint"])

    def test_rejected_key_degrades_as_credential_rejected(self):
        result = self.backend(api_key="wrong-key").synthesize("prompt")

        self.assertTrue(result.degraded)
        self.assertEqual("credential_rejected", result.cause["classification"])
        self.assertEqual(401, result.cause["http_status"])

    def test_probe_diagnosis_reports_exhausted_credit(self):
        _OpenRouterHandler.limit_remaining = 0

        probe = self.backend().probe_diagnosis()

        self.assertFalse(probe["available"])
        self.assertEqual("quota_exhausted", probe["classification"])

    def test_probe_diagnosis_available_when_credit_remains(self):
        probe = self.backend().probe_diagnosis()

        self.assertTrue(probe["available"])
        self.assertIsNone(probe["classification"])
        self.assertIn("127.0.0.1", probe["endpoint"])


if __name__ == "__main__":
    unittest.main()
