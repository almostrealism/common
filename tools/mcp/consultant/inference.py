"""
LLM inference backend for the AR Consultant.

Supports multiple backends:
  - llamacpp: llama.cpp server (OpenAI-compatible API, recommended for
              Linux containers with the server running on the Mac host)
  - ollama:   Ollama HTTP API
  - mlx:      MLX-LM for Apple Silicon (native macOS only)
  - passthrough: No model; returns retrieved context directly (fallback)

The backend is selected via the AR_CONSULTANT_BACKEND environment variable.
"""

import json
import logging
import os
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from typing import Optional

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# System prompt used across all backends
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """\
You are the Almost Realism Documentation Consultant, an expert on the AR \
framework codebase. Your role is to help coding agents understand and work \
with the AR codebase by answering questions, summarizing documentation, and \
reformulating notes to be consistent with project terminology.

Guidelines:
- Ground every answer in the documentation context provided.
- Use precise class names, method names, and module names from the project.
- When documentation context is insufficient, say so clearly rather than \
  guessing.
- Keep answers concise and actionable. Agents need practical guidance, not \
  essays.
- When reformulating agent notes, preserve the intent but align terminology \
  with the documentation.
- Reference specific files or modules when possible (e.g., "see the hardware \
  module" or "defined in PackedCollection").
"""


# ---------------------------------------------------------------------------
# Abstract backend
# ---------------------------------------------------------------------------

class InferenceBackend(ABC):
    """Abstract interface for LLM inference."""

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
        """

    @property
    @abstractmethod
    def name(self) -> str:
        """Human-readable backend name."""

    @property
    def available(self) -> bool:
        """Whether this backend is ready to serve requests."""
        return True


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
        self._available: Optional[bool] = None

    @property
    def name(self) -> str:
        return f"ollama ({self.model})"

    @property
    def available(self) -> bool:
        if self._available is not None:
            return self._available
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
                self._available = any(
                    model_base in m for m in models
                )
                if not self._available:
                    log.warning(
                        "Ollama is running but model '%s' not found. "
                        "Available: %s. Pull it with: ollama pull %s",
                        self.model,
                        ", ".join(models) or "(none)",
                        self.model,
                    )
        except (urllib.error.URLError, OSError, TimeoutError):
            log.info("Ollama not reachable at %s", self.base_url)
            self._available = False
        return self._available

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

        # Allow generous timeout for large model generation
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read())
            return data.get("message", {}).get("content", "")


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
                                      (default: http://host.docker.internal:8083)
    """

    DEFAULT_BASE_URL = "http://host.docker.internal:8083"

    def __init__(self, base_url: Optional[str] = None):
        self.base_url = base_url or os.environ.get(
            "AR_CONSULTANT_LLAMACPP_URL", self.DEFAULT_BASE_URL
        )
        self._available: Optional[bool] = None

    @property
    def name(self) -> str:
        return f"llamacpp ({self.base_url})"

    @property
    def available(self) -> bool:
        if self._available is not None:
            return self._available
        try:
            req = urllib.request.Request(
                f"{self.base_url}/health",
                method="GET",
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read())
                status = data.get("status", "")
                self._available = status == "ok"
                if not self._available:
                    log.warning(
                        "llama.cpp server at %s returned status: %s",
                        self.base_url, status,
                    )
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            log.info("llama.cpp server not reachable at %s: %s", self.base_url, e)
            self._available = False
        return self._available

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

        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read())
            choices = data.get("choices", [])
            if choices:
                return choices[0].get("message", {}).get("content", "")
            return ""


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
        self._available: Optional[bool] = None

    def _load(self):
        """Lazy-load the model on first use."""
        if self._model is not None:
            return
        try:
            from mlx_lm import load
            self._model, self._tokenizer = load(self._model_path)
        except Exception as e:
            log.error("Failed to load MLX model '%s': %s", self._model_path, e)
            self._available = False
            raise

    @property
    def name(self) -> str:
        return f"mlx ({self._model_path})"

    @property
    def available(self) -> bool:
        if self._available is not None:
            return self._available
        try:
            import mlx_lm  # noqa: F401
            self._available = True
        except ImportError:
            log.info("mlx-lm not installed; MLX backend unavailable")
            self._available = False
        return self._available

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

    Returns the prompt content directly, prefixed with a notice that
    no model is running. Useful for testing the retrieval pipeline
    without a model.
    """

    @property
    def name(self) -> str:
        return "passthrough (no model)"

    def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> str:
        return (
            "[Consultant model not available. Returning raw context.]\n\n"
            + prompt
        )


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
        # Try llamacpp first (Linux container -> Mac host is the primary setup)
        llamacpp = LlamaCppBackend()
        if llamacpp.available:
            log.info("Using llama.cpp backend: %s", llamacpp.name)
            return llamacpp

        ollama = OllamaBackend()
        if ollama.available:
            log.info("Using Ollama backend: %s", ollama.name)
            return ollama

        mlx = MLXBackend()
        if mlx.available:
            log.info("Using MLX backend: %s", mlx.name)
            return mlx

        log.warning(
            "No LLM backend available. Start a llama.cpp server on the "
            "host (llama-server -m model.gguf --host 0.0.0.0 --port 8080) "
            "or set AR_CONSULTANT_BACKEND=passthrough. "
            "Falling back to passthrough mode."
        )
        return PassthroughBackend()
    else:
        raise ValueError(
            f"Unknown backend: {name!r}. "
            f"Supported: 'llamacpp', 'ollama', 'mlx', 'passthrough', 'auto'"
        )
