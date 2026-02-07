"""
Embedding backends for the AR Memory MCP server.

Provides an abstract Embedder interface with concrete implementations
for FastEmbed (default) and SentenceTransformers (optional).
"""

from abc import ABC, abstractmethod
from typing import Optional

import numpy as np


class Embedder(ABC):
    """Abstract base class for text embedding backends."""

    @abstractmethod
    def embed(self, text: str) -> np.ndarray:
        """Embed a single text string. Returns a 1-D float32 numpy array."""

    @abstractmethod
    def embed_batch(self, texts: list[str]) -> list[np.ndarray]:
        """Embed a list of texts. Returns a list of 1-D float32 numpy arrays."""

    @property
    @abstractmethod
    def dimension(self) -> int:
        """Dimensionality of the embedding vectors."""


class FastEmbedEmbedder(Embedder):
    """Embedding backend using the fastembed library (ONNX-based, no GPU required)."""

    DEFAULT_MODEL = "BAAI/bge-small-en-v1.5"

    def __init__(self, model: Optional[str] = None, cache_dir: Optional[str] = None):
        from fastembed import TextEmbedding

        self._model_name = model or self.DEFAULT_MODEL
        kwargs = {"model_name": self._model_name}
        if cache_dir:
            kwargs["cache_dir"] = cache_dir
        self._model = TextEmbedding(**kwargs)
        self._dim: Optional[int] = None

    def embed(self, text: str) -> np.ndarray:
        results = list(self._model.embed([text]))
        vec = np.asarray(results[0], dtype=np.float32)
        if self._dim is None:
            self._dim = vec.shape[0]
        return vec

    def embed_batch(self, texts: list[str]) -> list[np.ndarray]:
        results = list(self._model.embed(texts))
        vecs = [np.asarray(v, dtype=np.float32) for v in results]
        if vecs and self._dim is None:
            self._dim = vecs[0].shape[0]
        return vecs

    @property
    def dimension(self) -> int:
        if self._dim is None:
            # Trigger a dummy embedding to discover dimension
            self.embed("hello")
        return self._dim


class SentenceTransformerEmbedder(Embedder):
    """Embedding backend using the sentence-transformers library (PyTorch-based)."""

    DEFAULT_MODEL = "all-MiniLM-L6-v2"

    def __init__(self, model: Optional[str] = None, cache_dir: Optional[str] = None):
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError:
            raise ImportError(
                "sentence-transformers is not installed. "
                "Install it with: pip install sentence-transformers"
            )

        self._model_name = model or self.DEFAULT_MODEL
        kwargs = {}
        if cache_dir:
            kwargs["cache_folder"] = cache_dir
        self._model = SentenceTransformer(self._model_name, **kwargs)
        self._dim = self._model.get_sentence_embedding_dimension()

    def embed(self, text: str) -> np.ndarray:
        return np.asarray(
            self._model.encode(text, convert_to_numpy=True), dtype=np.float32
        )

    def embed_batch(self, texts: list[str]) -> list[np.ndarray]:
        embeddings = self._model.encode(texts, convert_to_numpy=True)
        return [np.asarray(e, dtype=np.float32) for e in embeddings]

    @property
    def dimension(self) -> int:
        return self._dim


def create_embedder(
    backend: str = "fastembed",
    model: Optional[str] = None,
    cache_dir: Optional[str] = None,
) -> Embedder:
    """Factory function to create an Embedder instance.

    Args:
        backend: "fastembed" (default) or "sentence-transformers".
        model: Model name override. If None, uses the backend's default.
        cache_dir: Optional directory for caching downloaded model files.

    Returns:
        An Embedder instance.

    Raises:
        ValueError: If the backend name is not recognized.
    """
    if backend == "fastembed":
        return FastEmbedEmbedder(model=model, cache_dir=cache_dir)
    elif backend == "sentence-transformers":
        return SentenceTransformerEmbedder(model=model, cache_dir=cache_dir)
    else:
        raise ValueError(
            f"Unknown embedding backend: {backend!r}. "
            f"Supported backends: 'fastembed', 'sentence-transformers'"
        )
