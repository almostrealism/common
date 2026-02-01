"""
Memory integration for the AR Consultant.

Wraps the ar-memory MemoryStore to provide direct access to the memory
system without going through MCP. Also adds the dual-storage capability
(raw + reformulated) used by the Consultant's ``remember`` tool.
"""

import json
import os
import sys
from pathlib import Path
from typing import Optional

# Allow importing from the ar-memory package
_MEMORY_DIR = Path(__file__).parent.parent / "memory"
if str(_MEMORY_DIR) not in sys.path:
    sys.path.insert(0, str(_MEMORY_DIR))

from embedder import create_embedder, Embedder
from store import MemoryStore


# Default data directory (shared with ar-memory so they see the same data)
_DEFAULT_DATA_DIR = str(_MEMORY_DIR / "data")


class MemoryClient:
    """High-level interface to the AR memory store.

    Uses the same SQLite + FAISS backend as ar-memory, sharing the same
    data directory by default so memories are visible from both servers.
    """

    def __init__(
        self,
        data_dir: Optional[str] = None,
        backend: str = "fastembed",
        model: Optional[str] = None,
        cache_dir: Optional[str] = None,
    ):
        data_dir = data_dir or os.environ.get("AR_MEMORY_DATA_DIR", _DEFAULT_DATA_DIR)
        backend = os.environ.get("AR_MEMORY_BACKEND", backend)
        model = os.environ.get("AR_MEMORY_MODEL", model)
        cache_dir = os.environ.get("AR_MEMORY_CACHE_DIR", cache_dir)

        self._embedder = create_embedder(
            backend=backend,
            model=model if model else None,
            cache_dir=cache_dir if cache_dir else None,
        )
        self._store = MemoryStore(embedder=self._embedder, data_dir=data_dir)

    def search(
        self,
        query: str,
        namespace: str = "default",
        limit: int = 5,
        tag: Optional[str] = None,
    ) -> list[dict]:
        """Search memories by semantic similarity.

        Args:
            query: Natural language search query.
            namespace: Namespace to search within.
            limit: Maximum results.
            tag: Optional tag filter.

        Returns:
            Ranked list of memory entries with similarity scores.
        """
        return self._store.search(query=query, namespace=namespace, limit=limit, tag=tag)

    def store(
        self,
        content: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
    ) -> dict:
        """Store a memory entry.

        Args:
            content: Text content to store.
            namespace: Logical grouping.
            tags: Optional tags for filtering.
            source: Optional source identifier.

        Returns:
            The created entry.
        """
        return self._store.store(
            content=content, namespace=namespace, tags=tags, source=source,
        )

    def store_dual(
        self,
        original: str,
        reformulated: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
    ) -> dict:
        """Store a memory with both original and reformulated text.

        The reformulated text is used as the primary content (for search
        indexing), and the original text is preserved in a JSON wrapper
        so both versions can be recovered.

        Args:
            original: The agent's raw text.
            reformulated: The Consultant-edited version.
            namespace: Logical grouping.
            tags: Optional tags.
            source: Optional source identifier.

        Returns:
            The created entry with both texts accessible.
        """
        # Embed on the reformulated text (better search quality) but
        # store a JSON payload that includes both versions.
        payload = json.dumps({
            "reformulated": reformulated,
            "original": original,
        })

        # The content stored in SQLite (and indexed in FAISS via embedding
        # of the full payload) uses the reformulated text as-is so that
        # semantic search finds it naturally.  We store the dual payload
        # in the source field alongside the primary content.
        entry = self._store.store(
            content=reformulated,
            namespace=namespace,
            tags=tags,
            source=json.dumps({"original": original, "user_source": source}),
        )

        # Augment the returned dict with both versions for the caller
        entry["original"] = original
        entry["reformulated"] = reformulated
        return entry

    def list_entries(
        self,
        namespace: str = "default",
        tag: Optional[str] = None,
        limit: int = 20,
        offset: int = 0,
    ) -> list[dict]:
        """List memory entries, newest first."""
        return self._store.list_entries(
            namespace=namespace, tag=tag, limit=limit, offset=offset,
        )
