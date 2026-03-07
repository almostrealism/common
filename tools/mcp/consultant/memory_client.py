"""
Memory integration for the AR Consultant.

Wraps the MemoryHTTPClient from common/ to provide access to the centralized
ar-memory HTTP service. Also provides the dual-storage capability (raw +
reformulated) used by the Consultant's ``remember`` tool.

When the ar-memory server is unavailable, methods return empty results
or error dicts rather than raising exceptions, enabling graceful degradation.
"""

import json
import logging
import os
import sys
from typing import Optional

log = logging.getLogger(__name__)

# Allow importing from the common package
_COMMON_DIR = os.path.join(os.path.dirname(__file__), "..", "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)

from memory_http_client import MemoryHTTPClient


class MemoryClient:
    """High-level interface to the AR memory service via HTTP.

    Connects to the centralized ar-memory HTTP server. Provides the same
    interface as the old direct-import client, plus graceful degradation
    when the server is unavailable.
    """

    def __init__(self, base_url: Optional[str] = None):
        self._client: Optional[MemoryHTTPClient] = None
        self._base_url = base_url
        self._init_failed = False

    def _get_client(self) -> Optional[MemoryHTTPClient]:
        """Lazy-initialize the HTTP client with graceful degradation."""
        if self._client is not None:
            return self._client
        if self._init_failed:
            return None
        try:
            self._client = MemoryHTTPClient(base_url=self._base_url)
            log.info("Connected to ar-memory at %s", self._client.base_url)
            return self._client
        except ConnectionError as e:
            log.warning(
                "ar-memory server not available: %s. "
                "Memory features will be disabled. "
                "Start ar-memory with: python tools/mcp/memory/server.py --http-only",
                e,
            )
            self._init_failed = True
            return None

    @property
    def available(self) -> bool:
        """Whether the ar-memory server is reachable."""
        client = self._get_client()
        return client is not None

    def search(
        self,
        query: str,
        namespace: str = "default",
        limit: int = 5,
        tag: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> list[dict]:
        """Search memories by semantic similarity.

        Args:
            query: Natural language search query.
            namespace: Namespace to search within.
            limit: Maximum results.
            tag: Optional tag filter.
            repo_url: Optional repository URL filter.
            branch: Optional branch name filter.

        Returns:
            Ranked list of memory entries with similarity scores.
            Returns empty list if ar-memory is unavailable.
        """
        client = self._get_client()
        if client is None:
            return []
        try:
            return client.search(
                query=query, namespace=namespace, limit=limit, tag=tag,
                repo_url=repo_url, branch=branch,
            )
        except ConnectionError as e:
            log.warning("Memory search failed: %s", e)
            return []

    def search_by_branch(
        self,
        repo_url: str,
        branch: str,
        namespace: str = "default",
        limit: int = 20,
    ) -> list[dict]:
        """List memories for a specific repo and branch, newest first.

        Args:
            repo_url: Repository URL to match.
            branch: Branch name to match.
            namespace: Namespace to search within.
            limit: Maximum results.

        Returns:
            List of memory entries ordered by creation time (newest first).
            Returns empty list if ar-memory is unavailable.
        """
        client = self._get_client()
        if client is None:
            return []
        try:
            return client.search_by_branch(
                repo_url=repo_url, branch=branch, namespace=namespace, limit=limit,
            )
        except ConnectionError as e:
            log.warning("Memory branch search failed: %s", e)
            return []

    def store(
        self,
        content: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> dict:
        """Store a memory entry.

        If repo_url/branch are not provided, attempts to auto-detect them
        from the current git directory.

        Args:
            content: Text content to store.
            namespace: Logical grouping.
            tags: Optional tags for filtering.
            source: Optional source identifier.
            repo_url: Optional repository URL to associate.
            branch: Optional branch name to associate.

        Returns:
            The created entry, or an error dict if storage fails.
        """
        # Auto-detect git context if not provided
        if not repo_url or not branch:
            detected_url, detected_branch = _detect_git_context_safe()
            repo_url = repo_url or detected_url
            branch = branch or detected_branch

        client = self._get_client()
        if client is None:
            return {"error": "ar-memory server unavailable"}
        try:
            return client.store(
                content=content, repo_url=repo_url or "", branch=branch or "",
                namespace=namespace, tags=tags, source=source,
            )
        except ConnectionError as e:
            log.warning("Memory store failed: %s", e)
            return {"error": str(e)}

    def store_dual(
        self,
        original: str,
        reformulated: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
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
            repo_url: Optional repository URL to associate.
            branch: Optional branch name to associate.

        Returns:
            The created entry with both texts accessible.
        """
        # Embed on the reformulated text (better search quality) but
        # store a JSON payload that includes both versions.
        dual_source = json.dumps({"original": original, "user_source": source})

        entry = self.store(
            content=reformulated,
            namespace=namespace,
            tags=tags,
            source=dual_source,
            repo_url=repo_url,
            branch=branch,
        )

        if "error" not in entry:
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
        """List memory entries, newest first.

        Returns:
            List of entries, or empty list if ar-memory is unavailable.
        """
        client = self._get_client()
        if client is None:
            return []
        try:
            return client.list_entries(
                namespace=namespace, tag=tag, limit=limit, offset=offset,
            )
        except ConnectionError as e:
            log.warning("Memory list failed: %s", e)
            return []


def _detect_git_context_safe() -> tuple[str, str]:
    """Auto-detect repo_url and branch from git, returning empty strings on failure."""
    try:
        from git_context import detect_git_context
        return detect_git_context()
    except (ValueError, ImportError, FileNotFoundError) as e:
        log.debug("Git context auto-detection failed: %s", e)
        return ("", "")
