"""
LLM inference backend shared by AR MCP servers.

Supports multiple backends:
  - llamacpp: llama.cpp server (OpenAI-compatible API, recommended for
              Linux containers with the server running on the Mac host)
  - ollama:   Ollama HTTP API
  - mlx:      MLX-LM for Apple Silicon (native macOS only)
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
from typing import Optional

try:
    from .health import HealthCache, health_ttl
except ImportError:
    # Loaded by path (see consultant/inference.py) rather than as a package.
    from health import HealthCache, health_ttl

log = logging.getLogger(__name__)


class InferenceUnavailable(RuntimeError):
    """Raised when a backend cannot reach the model it fronts.

    Backends raise this instead of letting transport errors (``URLError``,
    ``OSError``, ``TimeoutError``) escape, so callers can distinguish "the
    model is down" from a genuine bug in the calling code.
    """

    def __init__(self, backend: str, detail: str):
        super().__init__(f"{backend} unavailable: {detail}")
        self.backend = backend
        self.detail = detail


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
    """

    text: Optional[str]
    backend: str
    reason: Optional[str] = None

    @property
    def degraded(self) -> bool:
        """True when no model output is available."""
        return self.text is None


# ---------------------------------------------------------------------------
# System prompt used across all backends
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """\
You are the Almost Realism Documentation Consultant. You answer questions \
about the AR framework by reading the documentation context provided to you.

Your job is to FIND the answer in the provided documentation and present it \
clearly. The documentation chunks are retrieved by keyword search, so the \
answer may not be in the first chunk — read ALL chunks carefully.

RULES:
1. Read ALL provided documentation chunks before answering. The answer is \
usually there — you just need to find it and connect it to the question.
2. When a chunk shows code examples, method signatures, or class names that \
match what the question asks about, USE that information in your answer.
3. If the documentation says something is NOT supported or does NOT exist, \
say so — that IS an answer.
4. When the documentation contains related but not exact information, \
summarize what IS covered and cite the files.
5. Only respond with "Not documented" when ALL provided chunks are \
completely irrelevant to the question.
6. Be CONCISE — 2-4 sentences. Cite sources like "per file.md:line".
7. NO hedging ("might be", "could be"). State what the documentation says.
8. ALWAYS include a concrete code example if the documentation provides one.
9. Notes from prior sessions are an agent's record of one past task, not \
documentation. A note is evidence only about the classes and files it names. \
Never attribute a note's details to anything it does not mention — that a note \
describes one class holding a flat array says nothing about any other class. \
A note that mentions a concept in passing does not establish the types, field \
layout, or implementation of anything it does not spell out; if the detail \
asked for is not written in the note, it is not known, and "likely" or \
"probably" is not an answer.
10. A question about what a particular source file contains cannot be answered \
from documentation unless a chunk quotes or describes that file. Say the \
documentation does not cover it rather than inferring from the name, the \
module, or how the framework usually works.
11. Cite "per file.md:line" only for a location that states the claim. Never \
attach a line number to something you inferred.
12. A question can take a fact for granted that the documentation never \
establishes — "what does X use arrays for" assumes X uses them. Do not adopt \
the assumption. Check whether the chunks establish it, and if they do not, say \
so plainly instead of answering as though it held.

EXAMPLES:
Doc chunk: "## Entering the Graph\\nUse cp() to wrap a PackedCollection...\\n\
cp(data).multiply(2.0).add(1.0).evaluate()"
Q: "How do I use cp() to chain operations?"
A: "Use `cp()` to wrap a PackedCollection into a CollectionProducer, then \
chain operations: `cp(data).multiply(2.0).add(1.0).evaluate()`. Each \
operation returns a new producer; nothing executes until `evaluate()` \
per collection-producer-operations.md:4."

Doc chunk: "A Features interface is a Java interface whose methods are all \
default... Adding implements CollectionFeatures makes 40+ factory methods \
available."
Q: "What is the Features pattern?"
A: "A Features interface has only `default` methods — adding `implements \
CollectionFeatures` to a class gives it 40+ factory methods like `cp()`, \
`c()`, `scalar()` without inheritance per features-pattern.md:1."
"""


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
            return Synthesis(None, self.name, e.detail)

        if not text or not text.strip():
            return Synthesis(None, self.name, "model returned an empty response")
        return Synthesis(text, self.name)


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
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

        payload = json.dumps({
            "model": self.model,
            "messages": messages,
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
            raise InferenceUnavailable(self.name, str(e)) from e


# ---------------------------------------------------------------------------
# llama.cpp backend
# ---------------------------------------------------------------------------

class LlamaCppBackend(InferenceBackend):
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

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

        payload = json.dumps({
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
        }).encode("utf-8")

        req = urllib.request.Request(
            f"{self.base_url}/v1/chat/completions",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                data = json.loads(resp.read())
                choices = data.get("choices", [])
                if choices:
                    return choices[0].get("message", {}).get("content", "")
                return ""
        except (urllib.error.URLError, OSError, TimeoutError, ValueError) as e:
            raise InferenceUnavailable(self.name, str(e)) from e


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
            raise InferenceUnavailable(self.name, f"model load failed: {e}") from e

    @property
    def name(self) -> str:
        return f"mlx ({self._model_path})"

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

        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

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
        return Synthesis(
            None, self.name,
            "no inference model is configured or reachable",
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
            else [LlamaCppBackend(), OllamaBackend(), MLXBackend()]
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
        if result.degraded and delegate is not self._fallback:
            # A real backend just failed a real request, which is stronger
            # evidence than its cached probe. Re-resolve so the next call
            # can reach a different backend that is actually up.
            #
            # Not done when the fallback answered: nothing was attempted, so
            # there is no new evidence, and invalidating would re-probe every
            # candidate on every call for as long as the outage lasts.
            self.invalidate_availability()
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
      4. Passthrough (no model)

    A backend is always returned. Detection failure is not an error here —
    it is reported per-request through :meth:`InferenceBackend.synthesize`,
    so a consumer that starts while every model is down still serves
    retrieval and starts synthesizing again once one comes up.

    Args:
        backend_name: One of "llamacpp", "ollama", "mlx", "passthrough",
                      or None / "auto" for auto-detection.

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
    elif name == "passthrough":
        return PassthroughBackend()
    elif name == "auto":
        # Ordered llamacpp first (Linux container -> Mac host is the primary
        # setup), then Ollama, then MLX. AutoBackend re-runs this selection
        # whenever its choice goes stale.
        return AutoBackend()
    else:
        raise ValueError(
            f"Unknown backend: {name!r}. "
            f"Supported: 'llamacpp', 'ollama', 'mlx', 'passthrough', 'auto'"
        )
