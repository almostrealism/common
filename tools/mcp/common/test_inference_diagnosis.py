"""Tests for classified inference-backend failure diagnosis.

A degraded consult or recall must say *why* the model is down — not only that
it is. These cover the classifier that maps a transport error or HTTP status to
a fixed class, and the structured ``cause`` / ``agent_instruction`` a backend
attaches to a degraded result. No test contacts a real provider: every failure
is a crafted exception or a backend whose outcome the test controls.
"""

import io
import json
import os
import socket
import sys
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(__file__))

from inference import (  # noqa: E402
    InferenceBackend,
    InferenceUnavailable,
    MLXBackend,
    OpenRouterBackend,
    PassthroughBackend,
)
from inference_diagnosis import (  # noqa: E402
    CLASSIFICATIONS,
    CONNECTION_REFUSED,
    CREDENTIAL_MISSING,
    CREDENTIAL_REJECTED,
    DNS_FAILURE,
    MALFORMED_RESPONSE,
    NOT_CONFIGURED,
    QUOTA_EXHAUSTED,
    TIMEOUT,
    UNREACHABLE,
    classify_transport_error,
    endpoint_of,
)


def _http_error(code, body):
    """An ``HTTPError`` whose body is ``body`` encoded as JSON."""
    return urllib.error.HTTPError(
        "https://provider/api", code, "err", {},
        io.BytesIO(json.dumps(body).encode()))


class ClassifyTransportErrorTest(unittest.TestCase):
    """Every classification the taxonomy names is reachable from an error."""

    def test_credential_rejected_from_401_and_403(self):
        for code in (401, 403):
            cls, status, detail = classify_transport_error(
                _http_error(code, {"error": {"message": "invalid key"}}))
            self.assertEqual(CREDENTIAL_REJECTED, cls)
            self.assertEqual(code, status)
            self.assertEqual("invalid key", detail)

    def test_quota_exhausted_from_402_and_429(self):
        for code in (402, 429):
            cls, status, detail = classify_transport_error(
                _http_error(code, {"error": {"message": "Insufficient credits"}}))
            self.assertEqual(QUOTA_EXHAUSTED, cls)
            self.assertEqual(code, status)
            self.assertEqual("Insufficient credits", detail)

    def test_other_http_status_is_malformed_response(self):
        cls, status, _ = classify_transport_error(
            _http_error(500, {"error": {"message": "boom"}}))
        self.assertEqual(MALFORMED_RESPONSE, cls)
        self.assertEqual(500, status)

    def test_connection_refused(self):
        exc = urllib.error.URLError(
            ConnectionRefusedError(111, "Connection refused"))
        cls, status, _ = classify_transport_error(exc)
        self.assertEqual(CONNECTION_REFUSED, cls)
        self.assertIsNone(status)

    def test_bare_connection_refused_without_urlerror(self):
        cls, _, _ = classify_transport_error(
            ConnectionRefusedError(111, "Connection refused"))
        self.assertEqual(CONNECTION_REFUSED, cls)

    def test_dns_failure(self):
        exc = urllib.error.URLError(socket.gaierror(-2, "Name or service"))
        cls, _, _ = classify_transport_error(exc)
        self.assertEqual(DNS_FAILURE, cls)

    def test_timeout(self):
        self.assertEqual(
            TIMEOUT, classify_transport_error(TimeoutError("timed out"))[0])
        self.assertEqual(
            TIMEOUT,
            classify_transport_error(urllib.error.URLError(socket.timeout()))[0])

    def test_malformed_response_from_json_value_error(self):
        cls, _, _ = classify_transport_error(ValueError("Expecting value"))
        self.assertEqual(MALFORMED_RESPONSE, cls)

    def test_unknown_transport_failure_is_unreachable_not_guessed(self):
        cls, _, _ = classify_transport_error(
            urllib.error.URLError(OSError("network is unreachable")))
        self.assertEqual(UNREACHABLE, cls)


class EndpointOfTest(unittest.TestCase):
    """The reported endpoint names where a request went, never a secret."""

    def test_strips_the_path_that_could_bear_a_token(self):
        self.assertEqual(
            "https://openrouter.ai",
            endpoint_of("https://openrouter.ai/api/v1"))

    def test_keeps_the_port(self):
        self.assertEqual(
            "http://host.docker.internal:8084",
            endpoint_of("http://host.docker.internal:8084"))

    def test_drops_userinfo_credentials(self):
        self.assertEqual(
            "https://host", endpoint_of("https://user:secret@host/path"))

    def test_hostless_url_has_no_endpoint(self):
        self.assertEqual("", endpoint_of(""))
        self.assertEqual("", endpoint_of("not-a-url"))


class _ClassifiedFake(InferenceBackend):
    """A backend that fails a request with a chosen classification."""

    def __init__(self, classification, http_status=None):
        self._classification = classification
        self._http_status = http_status
        super().__init__()

    @property
    def name(self):
        return "fake (classified)"

    @property
    def endpoint(self):
        return "https://fake-host"

    def operator_hint(self, classification):
        return f"check {classification}"

    def _probe(self):
        return False

    def generate(self, prompt, system=None, max_tokens=1024, temperature=0.3):
        raise InferenceUnavailable(
            self.name, f"failing with {self._classification}",
            classification=self._classification, http_status=self._http_status)


class SynthesisCauseTest(unittest.TestCase):
    """A degraded Synthesis carries a structured, classified cause."""

    def test_every_classification_is_reported_through_a_fake_backend(self):
        # "Test every classification with a fake backend" — each class the
        # taxonomy names reaches the caller as a structured cause.
        for classification in CLASSIFICATIONS:
            with self.subTest(classification=classification):
                result = _ClassifiedFake(classification).synthesize("prompt")

                self.assertTrue(result.degraded)
                self.assertIsNotNone(result.cause)
                self.assertEqual(classification, result.cause["classification"])
                self.assertEqual("https://fake-host", result.cause["endpoint"])
                self.assertEqual(f"check {classification}",
                                 result.cause["operator_hint"])
                self.assertEqual("fake (classified)", result.cause["backend"])

    def test_http_status_is_carried_in_the_cause(self):
        result = _ClassifiedFake(CREDENTIAL_REJECTED, http_status=401).synthesize("p")

        self.assertEqual(401, result.cause["http_status"])

    def test_agent_instruction_quotes_the_classified_reason(self):
        result = _ClassifiedFake(CONNECTION_REFUSED).synthesize("prompt")

        instruction = result.agent_instruction
        self.assertIsNotNone(instruction)
        self.assertIn("connection_refused", instruction)
        self.assertIn("Tell the person", instruction)
        self.assertIn("check connection_refused", instruction)

    def test_a_successful_synthesis_has_no_instruction_and_no_cause(self):
        class _Up(InferenceBackend):
            @property
            def name(self):
                return "up"

            def _probe(self):
                return True

            def generate(self, prompt, system=None, max_tokens=1024,
                         temperature=0.3):
                return "an answer"

        result = _Up().synthesize("prompt")
        self.assertFalse(result.degraded)
        self.assertIsNone(result.cause)
        self.assertIsNone(result.agent_instruction)

    def test_empty_response_is_classified_malformed(self):
        class _Blank(InferenceBackend):
            @property
            def name(self):
                return "blank"

            def _probe(self):
                return True

            def generate(self, prompt, system=None, max_tokens=1024,
                         temperature=0.3):
                return "   "

        result = _Blank().synthesize("prompt")
        self.assertTrue(result.degraded)
        self.assertEqual(MALFORMED_RESPONSE, result.cause["classification"])


class LastSuccessTest(unittest.TestCase):
    """The backend records when synthesis last succeeded, for the health tool."""

    def test_recorded_on_success_and_absent_before_any(self):
        class _Up(InferenceBackend):
            @property
            def name(self):
                return "up"

            def _probe(self):
                return True

            def generate(self, prompt, system=None, max_tokens=1024,
                         temperature=0.3):
                return "an answer"

        backend = _Up()
        self.assertIsNone(backend.last_success_at)

        backend.synthesize("prompt")

        self.assertIsNotNone(backend.last_success_at)

    def test_not_recorded_on_failure(self):
        backend = _ClassifiedFake(CONNECTION_REFUSED)
        backend.synthesize("prompt")
        self.assertIsNone(backend.last_success_at)


class BackendProbeDiagnosisTest(unittest.TestCase):
    """Each backend classifies its own availability probe."""

    def setUp(self):
        os.environ["AR_INFERENCE_HEALTH_TTL"] = "0"
        self.addCleanup(os.environ.pop, "AR_INFERENCE_HEALTH_TTL", None)

    def test_passthrough_is_not_configured(self):
        probe = PassthroughBackend().probe_diagnosis()
        self.assertFalse(probe["available"])
        self.assertEqual(NOT_CONFIGURED, probe["classification"])
        self.assertEqual("", probe["endpoint"])
        self.assertTrue(probe["operator_hint"])

    def test_openrouter_without_a_key_is_credential_missing(self):
        saved = os.environ.pop("AR_CONSULTANT_OPENROUTER_API_KEY", None)
        if saved is not None:
            self.addCleanup(
                os.environ.__setitem__, "AR_CONSULTANT_OPENROUTER_API_KEY", saved)
        probe = OpenRouterBackend(api_key="").probe_diagnosis()
        self.assertFalse(probe["available"])
        self.assertEqual(CREDENTIAL_MISSING, probe["classification"])
        # The hint names the secret/env that governs it, never a value.
        self.assertIn("AR_CONSULTANT_OPENROUTER_API_KEY", probe["operator_hint"])

    def test_mlx_without_the_library_is_not_configured(self):
        # mlx-lm is not installed in CI, so the probe reports it so.
        probe = MLXBackend().probe_diagnosis()
        if probe["available"]:
            self.skipTest("mlx-lm is installed in this environment")
        self.assertEqual(NOT_CONFIGURED, probe["classification"])
        self.assertEqual("", probe["endpoint"])


if __name__ == "__main__":
    unittest.main()
