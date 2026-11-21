"""
LLM inference backend shared by AR MCP servers.

Supports multiple backends:
  - llamacpp: llama.cpp server (OpenAI-compatible API, recommended for
              Linux containers with the server running on the Mac host)
  - ollama:   Ollama HTTP API
  - mlx:      MLX-LM for Apple Silicon (native macOS only)
  - openrouter: OpenRouter's hosted OpenAI-compatible API (paid per token;
              used when no local model can be spared)
  - passthrough: No model; returns retrieved context directly (fallback)

The backend is selected via the AR_CONSULTANT_BACKEND environment variable.

Originally part of ar-consultant; extracted to tools/mcp/common/ so that
ar-manager and other MCP servers can use the same inference pipeline.
"""

import json
import logging
import os
import time
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

try:
    from .health import HealthCache, health_ttl
    from .inference_diagnosis import (
        CREDENTIAL_MISSING, MALFORMED_RESPONSE, NOT_CONFIGURED, UNREACHABLE,
        classify_transport_error, endpoint_of, http_error_message,
        probe_http_get,
    )
    # Re-exported so ``from inference import SYSTEM_PROMPT`` keeps working after
    # prompt construction moved to its own module (see inference_prompts.py).
    from .inference_prompts import (  # noqa: F401
        REFORMULATE_SYSTEM_PROMPT, SPECULATION_PHRASES, SYSTEM_PROMPT,
        build_consult_prompt, has_speculation,
    )
except ImportError:
    # Loaded by path (see consultant/inference.py) rather than as a package.
    from health import HealthCache, health_ttl
    from inference_diagnosis import (
        CREDENTIAL_MISSING, MALFORMED_RESPONSE, NOT_CONFIGURED, UNREACHABLE,
        classify_transport_error, endpoint_of, http_error_message,
        probe_http_get,
    )
    from inference_prompts import (  # noqa: F401
        REFORMULATE_SYSTEM_PROMPT, SPECULATION_PHRASES, SYSTEM_PROMPT,
        build_consult_prompt, has_speculation,
    )

log = logging.getLogger(__name__)


class InferenceUnavailable(RuntimeError):
    """Raised when a backend cannot reach the model it fronts.

    Backends raise this instead of letting transport errors (``URLError``,
    ``OSError``, ``TimeoutError``) escape, so callers can distinguish "the
    model is down" from a genuine bug in the calling code.

    It carries a :mod:`inference_diagnosis` ``classification`` (and the
    provider's HTTP status, when the failure was an HTTP error) so the
    degraded result a caller shows can name *why* the model is down, not only
    that it is. The classification defaults to :data:`UNREACHABLE` for a
    hand-raised instance; the transport paths use :meth:`from_exception` to
    classify the actual error.
    """

    def __init__(self, backend: str, detail: str,
                 classification: str = UNREACHABLE,
                 http_status: Optional[int] = None):
        super().__init__(f"{backend} unavailable: {detail}")
        self.backend = backend
        self.detail = detail
        self.classification = classification
        self.http_status = http_status

    @classmethod
    def from_exception(cls, backend: str,
                       exc: BaseException) -> "InferenceUnavailable":
        """Build a classified instance from a caught transport exception.

        Reads an :class:`~urllib.error.HTTPError` body once (for its provider
        message), so the caller must not have read it already.

        Args:
            backend: The backend's human-readable name.
            exc: The exception caught around the request.

        Returns:
            An :class:`InferenceUnavailable` whose classification, HTTP status
            and detail describe ``exc``.
        """
        classification, status, detail = classify_transport_error(exc)
        message = f"HTTP {status}: {detail}" if status is not None else detail
        return cls(backend, message, classification=classification,
                   http_status=status)


@dataclass(frozen=True)
class Synthesis:
    """The outcome of a synthesis request.

    ``text`` carries the model's output when synthesis succeeded and is
    ``None`` when it did not, which is the single check a caller needs
    (:attr:`degraded`). ``reason`` explains a failure in terms a caller can
    show to its own caller without inspecting the backend.

    This exists so degradation is a value a caller must handle rather than
    a banner string it has to sniff for: retrieval results stay usable when
    only the summarization step is lost.

    ``cause`` carries the structured diagnosis of a failure (``None`` on
    success): the selected backend and why it was selected, the
    ``scheme://host`` it contacted, a :mod:`inference_diagnosis`
    ``classification``, the provider's HTTP status and message when there was
    one, and a one-sentence ``operator_hint``. A caller surfaces it so a
    degraded answer is diagnosable rather than mute.
    """

    text: Optional[str]
    backend: str
    reason: Optional[str] = None
    cause: Optional[dict] = None

    @property
    def degraded(self) -> bool:
        """True when no model output is available."""
        return self.text is None

    @property
    def agent_instruction(self) -> Optional[str]:
        """What an agent must tell the person it works for when degraded.

        Returns ``None`` when synthesis succeeded. Otherwise it is an explicit
        instruction naming the classified reason, so the agent does not quietly
        proceed as though the answer were synthesized — the failure the whole
        diagnosis exists to make visible.
        """
        if not self.degraded:
            return None
        cause = self.cause or {}
        classification = cause.get("classification") or "unknown"
        where = cause.get("endpoint") or "no endpoint"
        hint = cause.get("operator_hint") or ""
        instruction = (
            "Tell the person you are working for that documentation and memory "
            "synthesis is unavailable right now, quoting the reason: "
            f"\"{classification}\" from {cause.get('backend', 'the backend')} "
            f"({where}). Retrieval still works; only the synthesized summary is "
            "missing.")
        return f"{instruction} Operator: {hint}" if hint else instruction


# ---------------------------------------------------------------------------
# Abstract backend
# ---------------------------------------------------------------------------

class InferenceBackend(ABC):
    """Abstract interface for LLM inference.

    Subclasses implement :meth:`generate` (raising :class:`InferenceUnavailable`
    when the model cannot be reached) and :meth:`_probe` (a liveness check).
    The base class turns those into the two things callers actually need: an
    :attr:`available` flag that reflects the present, not process start, and
    :meth:`synthesize`, which degrades instead of raising.
    """

    def __init__(self):
        self._health = HealthCache(self._probe)
        self._last_success_at: Optional[str] = None

    @property
    def endpoint(self) -> str:
        """The ``scheme://host[:port]`` this backend contacts, or ``""``.

        Never a path (which for some providers bears a token) and never a
        credential — see :func:`inference_diagnosis.endpoint_of`. A backend
        that runs in-process (MLX, passthrough) has no endpoint and returns
        the empty string.
        """
        return ""

    @property
    def selection(self) -> str:
        """Why this backend is the one serving requests.

        For a backend pinned with ``AR_CONSULTANT_BACKEND`` this reports the
        explicit choice; :class:`AutoBackend` overrides it to report which
        candidate auto-detection settled on and which it skipped.
        """
        configured = os.environ.get("AR_CONSULTANT_BACKEND", "").strip()
        if configured and configured.lower() != "auto":
            return f"explicit AR_CONSULTANT_BACKEND={configured}"
        return f"backend {self.name}"

    def operator_hint(self, classification: str) -> str:
        """One sentence telling an operator what to check for a failure.

        Names the environment variable or secret file that governs this
        backend — never a secret's value. Subclasses override with the
        variables specific to them; the default is backend-agnostic.

        Args:
            classification: The :mod:`inference_diagnosis` classification.

        Returns:
            A single sentence of operator guidance.
        """
        return ("Confirm an inference backend is configured and reachable: set "
                "AR_CONSULTANT_BACKEND or start a local model "
                "(see tools/mcp/consultant/README.md).")

    def probe_diagnosis(self) -> dict:
        """Probe the backend now and report the outcome, classified.

        Unlike :attr:`available` this is not cached — it is the health tool's
        on-demand check. The default reports what the cached probe says, with
        no finer classification; subclasses that contact a server override it
        to classify a live failure.

        Returns:
            ``{available, backend, endpoint, selection, classification,
            http_status, detail, operator_hint}``.
        """
        available = self.available
        classification = None if available else UNREACHABLE
        return self._diagnosis(available, classification, None, "")

    def _diagnosis(self, available: bool, classification: Optional[str],
                   http_status: Optional[int], detail: str) -> dict:
        """Assemble a probe/diagnosis dict with this backend's identity."""
        return {
            "available": available,
            "backend": self.name,
            "endpoint": self.endpoint,
            "selection": self.selection,
            "classification": classification,
            "http_status": http_status,
            "detail": detail,
            "operator_hint": "" if available else self.operator_hint(
                classification or UNREACHABLE),
        }

    def _cause(self, classification: str, http_status: Optional[int],
               detail: str) -> dict:
        """The structured ``cause`` attached to a degraded :class:`Synthesis`."""
        return {
            "backend": self.name,
            "backend_selection": self.selection,
            "endpoint": self.endpoint,
            "classification": classification,
            "http_status": http_status,
            "detail": detail,
            "operator_hint": self.operator_hint(classification),
        }

    def _note_success(self) -> None:
        """Record that synthesis just succeeded, for the health tool."""
        self._last_success_at = datetime.now(timezone.utc).isoformat()

    @property
    def last_success_at(self) -> Optional[str]:
        """ISO-8601 UTC time of the last successful synthesis, or ``None``.

        Process-local: it reflects this server's own history since start, not a
        durable record.
        """
        return self._last_success_at

    @abstractmethod
    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        """Generate a completion.

        Args:
            prompt: The user/assistant prompt.
            system: Optional system prompt override.
            max_tokens: Maximum tokens to generate.
            temperature: Sampling temperature.

        Returns:
            The generated text.

        Raises:
            InferenceUnavailable: The model could not be reached.
        """

    @property
    @abstractmethod
    def name(self) -> str:
        """Human-readable backend name."""

    @staticmethod
    def chat_messages(prompt: str, system: Optional[str] = None) -> list:
        """Build the chat message list every chat-style backend sends.

        Args:
            prompt: The user prompt.
            system: Optional system prompt, sent first when present.

        Returns:
            A list of ``{"role", "content"}`` dicts.
        """
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        return messages

    def _probe(self) -> bool:
        """Check whether the model is reachable right now.

        Subclasses override this with a real liveness check. It is called
        at most once per health TTL, never on the request path.
        """
        return True

    @property
    def available(self) -> bool:
        """Whether this backend is ready to serve requests.

        Re-probed when the cached result is older than the health TTL, so a
        backend that dies is reported as down and one that recovers is
        reported as up. Caching the first probe forever (the previous
        behaviour) made this property report process-start state and
        contradict what ``generate`` actually did.
        """
        return self._health.healthy

    def invalidate_availability(self) -> None:
        """Force the next :attr:`available` read to re-probe.

        Called after a failed request so the recorded health reflects the
        failure immediately rather than at the next TTL expiry.
        """
        self._health.invalidate()

    def synthesize(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> Synthesis:
        """Generate text, reporting failure as a value rather than raising.

        This is the entry point for callers that have useful results to
        return even when the model is gone — the whole retrieval pipeline
        of a consult or recall survives a dead backend, and only the
        summary is lost.

        Returns:
            A :class:`Synthesis`; check :attr:`Synthesis.degraded`.
        """
        try:
            text = self.generate(
                prompt, system=system, max_tokens=max_tokens,
                temperature=temperature,
            )
        except InferenceUnavailable as e:
            self.invalidate_availability()
            log.warning("Synthesis degraded: %s", e)
            return Synthesis(
                None, self.name, e.detail,
                cause=self._cause(e.classification, e.http_status, e.detail))

        if not text or not text.strip():
            detail = "model returned an empty response"
            return Synthesis(
                None, self.name, detail,
                cause=self._cause(MALFORMED_RESPONSE, None, detail))
        self._note_success()
        return Synthesis(text, self.name)

    def consult(
        self,
        question: str,
        evidence="",
        extra_context: Optional[str] = None,
        history: Optional[str] = None,
    ) -> Synthesis:
        """Answer a question from an evidence list.

        Lives on the backend for the same reason :meth:`reformulate` does:
        both MCP servers answer questions over one documentation corpus, and a
        second copy of this prompt would let the two give different answers to
        the same question.

        Args:
            question: What the caller asked.
            evidence: The evidence list (see :func:`build_consult_prompt`).
            extra_context: Anything the caller supplied alongside the
                question, such as a code snippet or an error message.
            history: Condensed earlier turns, for a conversation follow-up.

        Returns:
            A :class:`Synthesis`; check :attr:`Synthesis.degraded`.
        """
        return self.synthesize(
            build_consult_prompt(
                question, evidence, extra_context, history=history,
            ),
            system=SYSTEM_PROMPT,
        )

    def reformulate(
        self,
        content: str,
        doc_context: str = "",
        max_tokens: int = 512,
    ) -> Synthesis:
        """Rewrite an agent-authored note to match project terminology.

        Lives on the backend rather than in either MCP server because both
        the Consultant's ``remember`` and ar-manager's ``memory_store`` write
        to the same corpus and must produce the same rewrite for the same
        note; a second copy of the prompt would let the two drift apart.

        Degradation is reported, never substituted: a caller must not write
        a backend-down passthrough dump into the corpus as if it were the
        author's note (``MemoryStore.is_passthrough_dump`` rejects that shape
        at the store, but the caller should not get that far). Check
        :attr:`Synthesis.degraded` and fall back to the original text.

        Args:
            content: The note as its author wrote it.
            doc_context: Optional documentation excerpt to ground terminology.
            max_tokens: Generation cap; a reformulation is a few sentences.

        Returns:
            A :class:`Synthesis` holding the rewrite, or a degraded one.
        """
        parts = []
        if doc_context:
            parts.append(f"## Relevant Documentation\n\n{doc_context}")
        parts.append(f"## Agent Note (Raw)\n\n{content}")
        parts.append(
            "## Task\n\n"
            "Reformulate the agent's note to be consistent with AR project "
            "terminology and documentation. Preserve the original intent but:\n"
            "- Use correct class names, method names, and module names\n"
            "- Add relevant context from the documentation\n"
            "- Fix any inaccuracies based on the documentation\n"
            "- Keep it concise (1-3 sentences)\n\n"
            "Return ONLY the reformulated note, nothing else."
        )

        synthesis = self.synthesize(
            "\n\n".join(parts), system=REFORMULATE_SYSTEM_PROMPT,
            max_tokens=max_tokens,
        )
        if synthesis.degraded:
            return synthesis

        # Models habitually wrap a single-value answer in quotes.
        return Synthesis(synthesis.text.strip().strip('"').strip("'"), self.name)


# ---------------------------------------------------------------------------
# Ollama backend
# ---------------------------------------------------------------------------

class OllamaBackend(InferenceBackend):
    """Inference via the Ollama HTTP API.

    Ollama must be running locally (``ollama serve``) and the model must
    be pulled (``ollama pull <model>``).
    """

    DEFAULT_MODEL = "qwen2.5-coder:32b-instruct-q4_K_M"
    DEFAULT_BASE_URL = "http://localhost:11434"

    def __init__(
        self,
        model: Optional[str] = None,
        base_url: Optional[str] = None,
    ):
        self.model = model or os.environ.get(
            "AR_CONSULTANT_MODEL", self.DEFAULT_MODEL
        )
        self.base_url = base_url or os.environ.get(
            "AR_CONSULTANT_OLLAMA_URL", self.DEFAULT_BASE_URL
        )
        super().__init__()

    @property
    def name(self) -> str:
        return f"ollama ({self.model})"

    @property
    def endpoint(self) -> str:
        return endpoint_of(self.base_url)

    def operator_hint(self, classification: str) -> str:
        return ("Confirm Ollama is running (``ollama serve``) at the host named "
                "by AR_CONSULTANT_OLLAMA_URL and that the model named by "
                "AR_CONSULTANT_MODEL has been pulled.")

    def probe_diagnosis(self) -> dict:
        def _accept(data: dict):
            models = [m.get("name", "") for m in data.get("models", [])]
            base = self.model.split(":")[0]
            if any(base in m for m in models):
                return None
            return (NOT_CONFIGURED,
                    f"model '{self.model}' is not pulled on the Ollama server")

        probe = probe_http_get(f"{self.base_url}/api/tags", timeout=3,
                               accept=_accept)
        return self._diagnosis(probe["available"], probe["classification"],
                               probe["http_status"], probe["detail"])

    def _probe(self) -> bool:
        try:
            req = urllib.request.Request(
                f"{self.base_url}/api/tags",
                method="GET",
            )
            with urllib.request.urlopen(req, timeout=3) as resp:
                data = json.loads(resp.read())
                models = [m.get("name", "") for m in data.get("models", [])]
                # Check if our model (or a prefix of it) is available
                model_base = self.model.split(":")[0]
                found = any(model_base in m for m in models)
                if not found:
                    log.warning(
                        "Ollama is running but model '%s' not found. "
                        "Available: %s. Pull it with: ollama pull %s",
                        self.model,
                        ", ".join(models) or "(none)",
                        self.model,
                    )
                return found
        except (urllib.error.URLError, OSError, TimeoutError, ValueError):
            log.info("Ollama not reachable at %s", self.base_url)
            return False

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        payload = json.dumps({
            "model": self.model,
            "messages": self.chat_messages(prompt, system),
            "stream": False,
            "options": {
                "num_predict": max_tokens,
                "temperature": temperature,
            },
        }).encode("utf-8")

        req = urllib.request.Request(
            f"{self.base_url}/api/chat",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        try:
            # Allow generous timeout for large model generation
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = json.loads(resp.read())
                return data.get("message", {}).get("content", "")
        except (urllib.error.URLError, OSError, TimeoutError, ValueError) as e:
            raise InferenceUnavailable.from_exception(self.name, e) from e


# ---------------------------------------------------------------------------
# OpenAI-compatible chat completions
# ---------------------------------------------------------------------------

class OpenAIChatBackend(InferenceBackend):
    """A backend that speaks the OpenAI ``/chat/completions`` protocol.

    llama.cpp's server and OpenRouter accept the same request and return the
    same response shape; they differ only in where the endpoint lives, how a
    request is authenticated, and which extra request fields they honour.
    Subclasses supply those three things and inherit the exchange itself.
    """

    #: Seconds to wait for a completion before treating the model as down.
    TIMEOUT_SECONDS = 180

    @abstractmethod
    def completions_url(self) -> str:
        """The full URL of the chat completions endpoint."""

    def request_headers(self) -> dict:
        """Headers sent with every request; subclasses add authentication."""
        return {"Content-Type": "application/json"}

    def request_fields(self) -> dict:
        """Backend-specific fields merged into every request body."""
        return {}

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        body = {
            "messages": self.chat_messages(prompt, system),
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
        }
        body.update(self.request_fields())

        req = urllib.request.Request(
            self.completions_url(),
            data=json.dumps(body).encode("utf-8"),
            headers=self.request_headers(),
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=self.TIMEOUT_SECONDS) as resp:
                data = json.loads(resp.read())
        except (urllib.error.URLError, OSError, TimeoutError, ValueError) as e:
            # URLError covers HTTPError (its subclass); from_exception reads the
            # HTTP error body once to recover the provider's message and maps
            # the status (401/403, 402/429, ...) to a classification.
            raise InferenceUnavailable.from_exception(self.name, e) from e

        choices = data.get("choices", [])
        if not choices:
            return ""
        choice = choices[0]
        content = choice.get("message", {}).get("content") or ""
        if not content.strip() and choice.get("finish_reason") == "length":
            # A reasoning model can spend the whole max_tokens budget
            # thinking and return no answer at all.
            log.warning(
                "%s hit max_tokens=%d before producing an answer; reasoning "
                "may be consuming the budget", self.name, max_tokens,
            )
        return content

    @staticmethod
    def error_detail(error: urllib.error.HTTPError) -> str:
        """Extract a provider's error message from an HTTP error response.

        Delegates to :func:`inference_diagnosis.http_error_message`, the single
        implementation shared with the failure classifier.
        """
        return http_error_message(error)


# ---------------------------------------------------------------------------
# llama.cpp backend
# ---------------------------------------------------------------------------

class LlamaCppBackend(OpenAIChatBackend):
    """Inference via a llama.cpp server (llama-server).

    llama.cpp's server exposes an OpenAI-compatible ``/v1/chat/completions``
    endpoint.  The recommended setup for a Linux dev container on a Mac host
    is to run ``llama-server`` on the host (with Metal acceleration) and
    connect from the container via ``host.docker.internal``.

    Host-side setup::

        # Download a GGUF model, e.g.:
        #   hf download Qwen/Qwen2.5-Coder-32B-Instruct-GGUF \\
        #       qwen2.5-coder-32b-instruct-q4_k_m.gguf --local-dir ./models

        llama-server \\
            -m ./models/qwen2.5-coder-32b-instruct-q4_k_m.gguf \\
            --host 0.0.0.0 --port 8080 \\
            -ngl 99 \\          # offload all layers to Metal GPU
            -c 8192 \\          # context window
            --chat-template chatml

    Environment variables:
        AR_CONSULTANT_LLAMACPP_URL  - Server base URL
                                      (default: http://host.docker.internal:8084 in containers,
                                       http://localhost:8084 on the host)
    """

    CONTAINER_BASE_URL = "http://host.docker.internal:8084"
    HOST_BASE_URL = "http://localhost:8084"
    REMOTE_HOST_BASE_URL = "http://mac-studio:8084"

    def __init__(self, base_url: Optional[str] = None):
        self._in_container = (
            os.path.exists("/.dockerenv") or os.path.exists("/run/.containerenv")
        )
        default_url = (
            self.CONTAINER_BASE_URL if self._in_container else self.HOST_BASE_URL
        )
        self._explicit_url = base_url or os.environ.get("AR_CONSULTANT_LLAMACPP_URL")
        self._default_url = default_url
        self.base_url = self._explicit_url or default_url
        super().__init__()

    @property
    def name(self) -> str:
        return f"llamacpp ({self.base_url})"

    @property
    def endpoint(self) -> str:
        return endpoint_of(self.base_url)

    def operator_hint(self, classification: str) -> str:
        return ("Confirm a llama.cpp server (llama-server) is running and that "
                "AR_CONSULTANT_LLAMACPP_URL points at it.")

    def _probe_url_diagnosis(self, url: str) -> dict:
        """Probe one llama.cpp ``/health`` endpoint, classifying the outcome."""
        def _accept(data: dict):
            status = data.get("status", "")
            if status == "ok":
                return None
            return (MALFORMED_RESPONSE,
                    f"llama.cpp server returned status: {status!r}")

        return probe_http_get(f"{url}/health", timeout=5, accept=_accept)

    def probe_diagnosis(self) -> dict:
        probe = self._probe_url_diagnosis(self.base_url)
        # Mirror _probe's fallback: a localhost default that is down tries the
        # remote host, and a recovered URL reclaims base_url.
        if not probe["available"] and not self._explicit_url \
                and not self._in_container:
            for alt in (self._default_url, self.REMOTE_HOST_BASE_URL):
                if alt == self.base_url:
                    continue
                alt_probe = self._probe_url_diagnosis(alt)
                if alt_probe["available"]:
                    self.base_url = alt
                    probe = alt_probe
                    break
        return self._diagnosis(probe["available"], probe["classification"],
                               probe["http_status"], probe["detail"])

    def _check_health(self, url: str) -> bool:
        """Check if a llama.cpp server is reachable and healthy at the given URL."""
        try:
            req = urllib.request.Request(f"{url}/health", method="GET")
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read())
                status = data.get("status", "")
                if status == "ok":
                    return True
                log.warning(
                    "llama.cpp server at %s returned status: %s", url, status,
                )
                return False
        except (urllib.error.URLError, OSError, TimeoutError, ValueError) as e:
            log.info("llama.cpp server not reachable at %s: %s", url, e)
            return False

    def _probe(self) -> bool:
        if self._check_health(self.base_url):
            return True

        # When using the localhost default (no explicit URL, not in a container),
        # try mac-studio as a fallback before giving up. Re-check the default
        # first on every probe so a recovered local server reclaims priority
        # instead of the process staying pinned to the remote host forever.
        if not self._explicit_url and not self._in_container:
            if self.base_url != self._default_url and self._check_health(
                self._default_url
            ):
                self.base_url = self._default_url
                return True

            log.info("Trying fallback host mac-studio...")
            if self._check_health(self.REMOTE_HOST_BASE_URL):
                self.base_url = self.REMOTE_HOST_BASE_URL
                return True

        return False

    def completions_url(self) -> str:
        return f"{self.base_url}/v1/chat/completions"


# ---------------------------------------------------------------------------
# OpenRouter backend
# ---------------------------------------------------------------------------

class OpenRouterBackend(OpenAIChatBackend):
    """Inference via OpenRouter's hosted, pay-per-token API.

    For when no local machine can be spared to run a model. The model is any
    OpenRouter slug, so moving between hosted models — or onto a local
    llama.cpp server running the same open-weights model — is a configuration
    change, not a code change.

    Two request defaults matter for this workload:

    * **Reasoning is off** (``reasoning.enabled = false``). A consult answer is
      a few sentences grounded in retrieved documentation. OpenRouter counts
      reasoning tokens against ``max_tokens``, so a thinking model left at its
      default can spend the whole budget reasoning and return nothing (while
      still being billed).
    * **Providers that retain prompts are excluded.** Prompts carry the memory
      corpus, which holds notes about unpublished work.

    Environment variables:
        AR_CONSULTANT_OPENROUTER_API_KEY_FILE  - JSON secret file holding the
                                                 key as {"openrouter-api-key": ...}
                                                 (preferred; read first)
        AR_CONSULTANT_OPENROUTER_API_KEY       - The API key itself
        AR_CONSULTANT_OPENROUTER_MODEL         - Model slug
                                                 (default: minimax/minimax-m3)
        AR_CONSULTANT_OPENROUTER_REASONING     - Reasoning control: ``none``
                                                 (default) disables reasoning;
                                                 ``high``/``medium``/``low`` set
                                                 ``reasoning.effort``; an empty
                                                 string sends no reasoning field,
                                                 leaving the model's default
        AR_CONSULTANT_OPENROUTER_DATA_COLLECTION - ``deny`` (default) or
                                                 ``allow``; sent as
                                                 ``provider.data_collection``.
                                                 A blank or unrecognised value
                                                 falls back to ``deny`` so the
                                                 privacy restriction is never
                                                 silently dropped
        AR_CONSULTANT_OPENROUTER_URL           - API base URL
                                                 (default: https://openrouter.ai/api/v1)
    """

    #: The reasoning setting that turns reasoning off rather than selecting an
    #: effort level. OpenRouter's documented ``reasoning.effort`` values are
    #: ``high``/``medium``/``low``, so "off" is a sentinel of our own, not one
    #: of them; it is translated to ``{"enabled": false}`` in a request.
    REASONING_OFF = "none"

    DEFAULT_MODEL = "minimax/minimax-m3"
    DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    DEFAULT_REASONING = REASONING_OFF
    DEFAULT_DATA_COLLECTION = "deny"

    #: The values OpenRouter documents for ``provider.data_collection``. Unlike
    #: the reasoning field, a data-collection setting is a privacy control:
    #: prompts carry the memory corpus, so a blank or unrecognised value must
    #: never silently drop the restriction. Anything outside this set is
    #: treated as unset and falls back to the privacy default.
    DATA_COLLECTION_VALUES = ("deny", "allow")

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        base_url: Optional[str] = None,
    ):
        self._api_key = api_key if api_key is not None else self.load_api_key()
        # An empty variable means "unset": compose passes ${VAR:-} through
        # as an empty string when the operator has not chosen a value.
        self.model = (
            model
            or os.environ.get("AR_CONSULTANT_OPENROUTER_MODEL", "").strip()
            or self.DEFAULT_MODEL
        )
        self.base_url = (
            base_url
            or os.environ.get("AR_CONSULTANT_OPENROUTER_URL", "").strip()
            or self.DEFAULT_BASE_URL
        ).rstrip("/")
        self.reasoning = os.environ.get(
            "AR_CONSULTANT_OPENROUTER_REASONING", self.DEFAULT_REASONING
        ).strip()
        self.data_collection = self.resolve_data_collection(
            os.environ.get(
                "AR_CONSULTANT_OPENROUTER_DATA_COLLECTION",
                self.DEFAULT_DATA_COLLECTION,
            ).strip()
        )
        super().__init__()

    #: Name of the secret, and the key that holds it in a secret payload file.
    SECRET_NAME = "openrouter-api-key"

    #: Generic fallback key accepted in a secret payload, matching the
    #: workspace-secret convention's single-key ``{"value": ...}`` form.
    SECRET_VALUE_KEY = "value"

    @classmethod
    def load_api_key(cls) -> str:
        """Read the API key from its secret file, falling back to the environment.

        The file form is what the controller deployment uses, so the key never
        appears in ``docker inspect`` output or a compose file. It follows the
        workspace-secret payload convention (flowtree/docs/operations/PROVIDERS.md):
        a JSON object keyed by the secret name,
        ``{"openrouter-api-key": "sk-or-v1-..."}``, or a ``value``-keyed
        payload, ``{"value": "sk-or-v1-..."}``.
        """
        key_file = os.environ.get("AR_CONSULTANT_OPENROUTER_API_KEY_FILE", "").strip()
        if key_file and os.path.isfile(key_file):
            try:
                with open(key_file) as f:
                    payload = json.load(f)
            except (OSError, ValueError) as e:
                log.warning("Failed to read OpenRouter API key file: %s", e)
            else:
                key = cls.secret_value(payload)
                if key:
                    return key
                log.warning(
                    "OpenRouter API key file holds no %r entry", cls.SECRET_NAME,
                )
        return os.environ.get("AR_CONSULTANT_OPENROUTER_API_KEY", "").strip()

    @classmethod
    def secret_value(cls, payload) -> str:
        """Extract the key from a secret payload, or return an empty string."""
        if not isinstance(payload, dict):
            return ""
        value = payload.get(cls.SECRET_NAME)
        if value is None:
            value = payload.get(cls.SECRET_VALUE_KEY)
        return value.strip() if isinstance(value, str) else ""

    @classmethod
    def resolve_data_collection(cls, setting: str) -> str:
        """Normalise a data-collection setting, keeping the privacy default.

        A blank value (compose passing ``${VAR:-}`` through, or the variable
        set to an empty string) or any value OpenRouter does not document is
        treated as unset and falls back to :attr:`DEFAULT_DATA_COLLECTION`.
        This is deliberately stricter than the reasoning setting: an empty
        reasoning value omits the field to take the model's default, but a
        prompt carries the memory corpus, so an empty or unrecognised
        data-collection value must never drop the restriction that keeps it
        off prompt-retaining providers.

        Args:
            setting: The already-stripped configured value.

        Returns:
            One of :attr:`DATA_COLLECTION_VALUES`.
        """
        if setting in cls.DATA_COLLECTION_VALUES:
            return setting
        if setting:
            log.warning(
                "AR_CONSULTANT_OPENROUTER_DATA_COLLECTION=%r is not one of %s; "
                "keeping the privacy default %r",
                setting, cls.DATA_COLLECTION_VALUES, cls.DEFAULT_DATA_COLLECTION,
            )
        return cls.DEFAULT_DATA_COLLECTION

    @property
    def name(self) -> str:
        return f"openrouter ({self.model})"

    @property
    def endpoint(self) -> str:
        return endpoint_of(self.base_url)

    def operator_hint(self, classification: str) -> str:
        from inference_diagnosis import (
            CREDENTIAL_MISSING as _MISSING,
            CREDENTIAL_REJECTED as _REJECTED,
            QUOTA_EXHAUSTED as _QUOTA,
        )
        if classification == _MISSING:
            return ("Provide the openrouter-api-key secret via "
                    "AR_CONSULTANT_OPENROUTER_API_KEY_FILE (or "
                    "AR_CONSULTANT_OPENROUTER_API_KEY).")
        if classification == _REJECTED:
            return ("OpenRouter rejected the key; rotate the openrouter-api-key "
                    "secret named by AR_CONSULTANT_OPENROUTER_API_KEY_FILE.")
        if classification == _QUOTA:
            return ("The OpenRouter account has no remaining credit; add credit "
                    "or point AR_CONSULTANT_OPENROUTER_API_KEY_FILE at a funded "
                    "key.")
        return ("Confirm network egress to OpenRouter and that "
                "AR_CONSULTANT_OPENROUTER_URL / AR_CONSULTANT_OPENROUTER_MODEL "
                "are correct.")

    def probe_diagnosis(self) -> dict:
        if not self.configured:
            return self._diagnosis(
                False, CREDENTIAL_MISSING, None,
                "no OpenRouter API key is configured")

        def _accept(data: dict):
            remaining = data.get("data", {}).get("limit_remaining")
            if remaining is not None and remaining <= 0:
                from inference_diagnosis import QUOTA_EXHAUSTED as _QUOTA
                return (_QUOTA, "the OpenRouter key has no remaining credit")
            return None

        probe = probe_http_get(f"{self.base_url}/key", timeout=5,
                               accept=_accept, headers=self.request_headers())
        return self._diagnosis(probe["available"], probe["classification"],
                               probe["http_status"], probe["detail"])

    @property
    def configured(self) -> bool:
        """Whether an API key is present at all."""
        return bool(self._api_key)

    def completions_url(self) -> str:
        return f"{self.base_url}/chat/completions"

    def request_headers(self) -> dict:
        headers = super().request_headers()
        headers["Authorization"] = f"Bearer {self._api_key}"
        return headers

    def request_fields(self) -> dict:
        fields = {"model": self.model}
        reasoning = self.reasoning_request()
        if reasoning is not None:
            fields["reasoning"] = reasoning
        if self.data_collection:
            fields["provider"] = {"data_collection": self.data_collection}
        return fields

    def reasoning_request(self):
        """Translate the configured reasoning setting into OpenRouter's field.

        OpenRouter counts reasoning tokens against ``max_tokens``, so a thinking
        model left on its default can spend a consult's whole budget reasoning
        and return an empty (but billed) answer. Reasoning is therefore off by
        default, expressed as ``{"enabled": false}`` — the field OpenRouter
        documents for disabling reasoning. ``effort="none"`` is **not** a
        documented level (the levels are ``high``/``medium``/``low``), so a
        provider could silently ignore it and leave reasoning on.

        Returns:
            ``{"enabled": False}`` when reasoning is off (the default);
            ``{"effort": <level>}`` when an effort level is configured; or
            ``None`` when the setting is empty, which omits the field and
            leaves the model's own default in place.
        """
        if not self.reasoning:
            return None
        if self.reasoning == self.REASONING_OFF:
            return {"enabled": False}
        return {"effort": self.reasoning}

    def _probe(self) -> bool:
        """Check the key against ``GET /key``, which costs no tokens.

        A key with no credit left is reported down: every completion would
        fail with HTTP 402, and an auto-detecting caller should move on to a
        backend that can answer.
        """
        if not self.configured:
            return False
        req = urllib.request.Request(
            f"{self.base_url}/key", headers=self.request_headers(), method="GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read()).get("data", {})
        except (urllib.error.URLError, OSError, TimeoutError, ValueError) as e:
            log.info("OpenRouter not reachable at %s: %s", self.base_url, e)
            return False

        remaining = data.get("limit_remaining")
        if remaining is not None and remaining <= 0:
            log.warning("OpenRouter API key has no credit remaining")
            return False
        return True

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        if not self.configured:
            raise InferenceUnavailable(
                self.name, "no OpenRouter API key is configured",
                classification=CREDENTIAL_MISSING)
        return super().generate(
            prompt, system=system, max_tokens=max_tokens, temperature=temperature,
        )


# ---------------------------------------------------------------------------
# MLX backend
# ---------------------------------------------------------------------------

class MLXBackend(InferenceBackend):
    """Inference via MLX-LM on Apple Silicon.

    Requires mlx-lm to be installed: pip install mlx-lm
    """

    DEFAULT_MODEL = "mlx-community/Qwen2.5-Coder-32B-Instruct-4bit"

    def __init__(self, model_path: Optional[str] = None):
        self._model_path = model_path or os.environ.get(
            "AR_CONSULTANT_MLX_MODEL", self.DEFAULT_MODEL
        )
        self._model = None
        self._tokenizer = None
        super().__init__()

    def _load(self):
        """Lazy-load the model on first use.

        Raises:
            InferenceUnavailable: The model could not be loaded.
        """
        if self._model is not None:
            return
        try:
            from mlx_lm import load
            self._model, self._tokenizer = load(self._model_path)
        except Exception as e:
            log.error("Failed to load MLX model '%s': %s", self._model_path, e)
            raise InferenceUnavailable(
                self.name, f"model load failed: {e}",
                classification=NOT_CONFIGURED) from e

    @property
    def name(self) -> str:
        return f"mlx ({self._model_path})"

    def operator_hint(self, classification: str) -> str:
        return ("Install mlx-lm (Apple Silicon only) and set "
                "AR_CONSULTANT_MLX_MODEL, or select another backend with "
                "AR_CONSULTANT_BACKEND.")

    def probe_diagnosis(self) -> dict:
        available = self._probe()
        return self._diagnosis(
            available, None if available else NOT_CONFIGURED, None,
            "" if available else "mlx-lm is not installed")

    def _probe(self) -> bool:
        try:
            import mlx_lm  # noqa: F401
            return True
        except ImportError:
            log.info("mlx-lm not installed; MLX backend unavailable")
            return False

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        self._load()
        from mlx_lm import generate as mlx_generate

        messages = self.chat_messages(prompt, system)

        # Apply chat template if available
        if hasattr(self._tokenizer, "apply_chat_template"):
            formatted = self._tokenizer.apply_chat_template(
                messages, tokenize=False, add_generation_prompt=True,
            )
        else:
            # Fallback: simple concatenation
            parts = []
            for m in messages:
                parts.append(f"<|{m['role']}|>\n{m['content']}")
            parts.append("<|assistant|>\n")
            formatted = "\n".join(parts)

        return mlx_generate(
            self._model,
            self._tokenizer,
            prompt=formatted,
            max_tokens=max_tokens,
            temp=temperature,
        )


# ---------------------------------------------------------------------------
# Passthrough backend (no model)
# ---------------------------------------------------------------------------

class PassthroughBackend(InferenceBackend):
    """Fallback when no model is available.

    :meth:`generate` returns the prompt content directly, prefixed with a
    notice that no model is running, which is useful for exercising the
    retrieval pipeline without a model.

    That banner is *not* an answer, so :attr:`available` is False and
    :meth:`synthesize` reports a degraded result. Callers that route
    through ``synthesize`` therefore never mistake a raw context dump for
    synthesized text — the failure mode that put 36 such dumps into the
    memory corpus.
    """

    #: Leader emitted by :meth:`generate`. ``tools/mcp/memory/store.py``
    #: matches on this to reject dumps that predate structured degradation.
    BANNER = "[Consultant model not available. Returning raw context.]"

    @property
    def name(self) -> str:
        return "passthrough (no model)"

    def operator_hint(self, classification: str) -> str:
        return ("No inference model is configured or reachable; set "
                "AR_CONSULTANT_BACKEND or start a local llama.cpp / Ollama "
                "server (see tools/mcp/consultant/README.md).")

    def probe_diagnosis(self) -> dict:
        return self._diagnosis(
            False, NOT_CONFIGURED, None,
            "no inference model is configured or reachable")

    def _probe(self) -> bool:
        return False

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        return self.BANNER + "\n\n" + prompt

    def synthesize(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> Synthesis:
        detail = "no inference model is configured or reachable"
        return Synthesis(
            None, self.name, detail,
            cause=self._cause(NOT_CONFIGURED, None, detail),
        )


# ---------------------------------------------------------------------------
# Auto-detecting backend
# ---------------------------------------------------------------------------

class AutoBackend(InferenceBackend):
    """Delegates to the first reachable backend, re-resolving as health changes.

    Auto-detection is not a one-time decision. A llama.cpp server that is
    down when an MCP server starts and comes up minutes later has to be
    picked up without restarting every process holding a backend handle,
    and a server that dies mid-session has to stop being addressed. This
    backend re-runs detection whenever its cached choice goes stale,
    falling back to :class:`PassthroughBackend` only for as long as nothing
    better answers a probe.
    """

    def __init__(self, candidates: Optional[list] = None):
        self._candidates = (
            candidates if candidates is not None
            else [LlamaCppBackend(), OllamaBackend(), MLXBackend(),
                  OpenRouterBackend()]
        )
        self._fallback = PassthroughBackend()
        self._delegate: InferenceBackend = self._fallback
        self._resolved_at: float = 0.0
        super().__init__()

    @property
    def delegate(self) -> InferenceBackend:
        """The backend currently serving requests, re-resolved when stale."""
        now = time.monotonic()
        if self._resolved_at and (now - self._resolved_at) < health_ttl():
            return self._delegate

        for candidate in self._candidates:
            if candidate.available:
                if candidate is not self._delegate:
                    log.info("Using %s backend", candidate.name)
                self._delegate = candidate
                self._resolved_at = now
                return self._delegate

        if self._delegate is not self._fallback:
            log.warning(
                "No LLM backend is reachable; degrading until one returns."
            )
        self._delegate = self._fallback
        self._resolved_at = now
        return self._delegate

    @property
    def name(self) -> str:
        return self.delegate.name

    @property
    def endpoint(self) -> str:
        return self.delegate.endpoint

    def operator_hint(self, classification: str) -> str:
        return self.delegate.operator_hint(classification)

    @property
    def selection(self) -> str:
        """Which candidate auto-detection settled on, and which it skipped.

        Reads the currently resolved delegate (cached), so it is cheap enough
        to compute on the degraded path. Auto-detection takes the first
        reachable candidate, so every candidate before the chosen one was
        skipped as unavailable; when nothing is reachable the fallback answers
        and all candidates were skipped.
        """
        delegate = self.delegate
        order = [c.name for c in self._candidates]
        if isinstance(delegate, PassthroughBackend):
            return ("auto-detection reached no backend; every candidate was "
                    "unavailable, in order: " + ", ".join(order))
        skipped = []
        for candidate in self._candidates:
            if candidate is delegate:
                break
            skipped.append(candidate.name)
        summary = f"auto-detected {delegate.name}"
        if skipped:
            summary += "; skipped unavailable: " + ", ".join(skipped)
        return summary

    def probe_diagnosis(self) -> dict:
        """Probe every candidate now, reporting the chosen one and the trace.

        This is the health tool's full scan: each candidate is probed afresh
        (not the cached delegate), so the report reflects the present. The
        first available candidate is the one that would serve requests; its
        diagnosis is returned, with a ``candidates`` list giving every
        candidate's own probe so a down backend can be told apart from a
        misconfigured one.
        """
        candidate_probes = [c.probe_diagnosis() for c in self._candidates]
        chosen = next(
            (p for p in candidate_probes if p.get("available")), None)
        if chosen is not None:
            result = dict(chosen)
        else:
            result = self._diagnosis(
                False, NOT_CONFIGURED, None,
                "no candidate backend is reachable")
        result["selection"] = self.selection
        result["candidates"] = candidate_probes
        return result

    def _probe(self) -> bool:
        return not isinstance(self.delegate, PassthroughBackend)

    def invalidate_availability(self) -> None:
        """Drop both the resolved delegate and every candidate's health."""
        super().invalidate_availability()
        self._resolved_at = 0.0
        for candidate in self._candidates:
            candidate.invalidate_availability()

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        return self.delegate.generate(
            prompt, system=system, max_tokens=max_tokens,
            temperature=temperature,
        )

    def synthesize(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> Synthesis:
        delegate = self.delegate
        result = delegate.synthesize(
            prompt, system=system, max_tokens=max_tokens,
            temperature=temperature,
        )
        if result.degraded:
            # Restate the selection in terms of auto-detection (which candidate
            # was chosen and which skipped) rather than the delegate's own,
            # env-based account, so the cause names the real reason this backend
            # is the one that failed. The classification, endpoint and hint are
            # the delegate's — they describe the actual failure.
            cause = dict(result.cause or {})
            cause["backend_selection"] = self.selection
            result = Synthesis(None, result.backend, result.reason, cause=cause)
            if delegate is not self._fallback:
                # A real backend just failed a real request, which is stronger
                # evidence than its cached probe. Re-resolve so the next call
                # can reach a different backend that is actually up.
                #
                # Not done when the fallback answered: nothing was attempted, so
                # there is no new evidence, and invalidating would re-probe
                # every candidate on every call for as long as the outage lasts.
                self.invalidate_availability()
        else:
            self._note_success()
        return result


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------

def create_backend(backend_name: Optional[str] = None) -> InferenceBackend:
    """Create an inference backend, falling back gracefully.

    Auto-detection order:
      1. llama.cpp server (best for Linux container + Mac host setup)
      2. Ollama
      3. MLX (Apple Silicon, native macOS only)
      4. OpenRouter (only when an API key is configured; paid, so local
         models are always preferred when one is up)
      5. Passthrough (no model)

    A backend is always returned. Detection failure is not an error here —
    it is reported per-request through :meth:`InferenceBackend.synthesize`,
    so a consumer that starts while every model is down still serves
    retrieval and starts synthesizing again once one comes up.

    Args:
        backend_name: One of "llamacpp", "ollama", "mlx", "openrouter",
                      "passthrough", or None / "auto" for auto-detection.

    Returns:
        An InferenceBackend instance.
    """
    name = backend_name or os.environ.get("AR_CONSULTANT_BACKEND", "auto")

    if name == "llamacpp":
        return LlamaCppBackend()
    elif name == "ollama":
        return OllamaBackend()
    elif name == "mlx":
        return MLXBackend()
    elif name == "openrouter":
        return OpenRouterBackend()
    elif name == "passthrough":
        return PassthroughBackend()
    elif name == "auto":
        # Ordered llamacpp first (Linux container -> Mac host is the primary
        # setup), then Ollama, then MLX, then paid OpenRouter. AutoBackend re-runs this selection
        # whenever its choice goes stale.
        return AutoBackend()
    else:
        raise ValueError(
            f"Unknown backend: {name!r}. "
            f"Supported: 'llamacpp', 'ollama', 'mlx', 'openrouter', "
            f"'passthrough', 'auto'"
        )
