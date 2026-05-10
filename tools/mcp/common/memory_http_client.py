"""
HTTP client for the ar-memory server.

Provides a high-level interface for storing, searching, and managing
memories via the ar-memory REST API. Used by both ar-consultant and
ar-manager to access the centralized memory service.
"""

import json
import logging
import os
import time
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

log = logging.getLogger(__name__)

# Default port for the ar-memory HTTP service
_DEFAULT_PORT = 8020

# Connection pool timeout for health checks
_HEALTH_TIMEOUT = 3

# Request timeout for normal operations
_REQUEST_TIMEOUT = 15

# Maximum retries for transient failures
_MAX_RETRIES = 2

# Delay between retries (seconds)
_RETRY_DELAY = 0.5


class MemoryHTTPClient:
    """HTTP client for the ar-memory REST API.

    Provides semantic memory storage and retrieval through the centralized
    ar-memory HTTP server. Supports automatic service discovery and retry
    logic for transient failures.

    Args:
        base_url: Explicit server URL. If not provided, auto-discovers
            the server using environment variables and well-known hosts.
    """

    def __init__(self, base_url: Optional[str] = None):
        self._base_url = base_url or _discover_memory_server()
        self._available: Optional[bool] = None

    @property
    def base_url(self) -> str:
        """The resolved base URL of the ar-memory server."""
        return self._base_url

    @property
    def available(self) -> bool:
        """Whether the ar-memory server is reachable."""
        if self._available is not None:
            return self._available
        try:
            self.health()
            self._available = True
        except (ConnectionError, OSError):
            self._available = False
        return self._available

    def store(
        self,
        content: str,
        repo_url: str,
        branch: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
    ) -> dict:
        """Store a memory entry. repo_url and branch are required.

        Args:
            content: The text content to store.
            repo_url: Repository URL (required).
            branch: Branch name (required).
            namespace: Logical grouping (e.g., "decisions", "bugs").
            tags: Optional tags for categorical filtering.
            source: Optional source identifier.

        Returns:
            The created entry with its ID and metadata.
        """
        payload = {
            "content": content,
            "repo_url": repo_url,
            "branch": branch,
            "namespace": namespace,
        }
        if tags:
            payload["tags"] = tags
        if source:
            payload["source"] = source

        return self._post("/api/memory/store", payload)

    def search(
        self,
        query: str,
        namespace: str = "default",
        limit: int = 5,
        tag: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> list[dict]:
        """Semantic search for memory entries.

        Args:
            query: Natural language search query.
            namespace: Namespace to search within.
            limit: Maximum number of results.
            tag: Optional tag filter.
            repo_url: Optional repository URL filter.
            branch: Optional branch name filter.

        Returns:
            Ranked list of matching entries with similarity scores.
        """
        payload: dict = {
            "query": query,
            "namespace": namespace,
            "limit": limit,
        }
        if tag:
            payload["tag"] = tag
        if repo_url:
            payload["repo_url"] = repo_url
        if branch:
            payload["branch"] = branch

        result = self._post("/api/memory/search", payload)
        return result.get("results", result) if isinstance(result, dict) else result

    def search_by_branch(
        self,
        repo_url: str,
        branch: str,
        namespace: Optional[str] = "default",
        limit: int = 20,
    ) -> list[dict]:
        """Non-semantic lookup of memories by branch.

        Args:
            repo_url: Repository URL to match.
            branch: Branch name to match.
            namespace: Namespace to search within. Pass ``None`` or an empty
                string to search across every namespace and receive the
                combined result sorted newest-first.
            limit: Maximum results.

        Returns:
            List of memory entries ordered by creation time (newest first).
        """
        payload = {
            "repo_url": repo_url,
            "branch": branch,
            "namespace": "" if not namespace else namespace,
            "limit": limit,
        }
        result = self._post("/api/memory/branch", payload)
        return result.get("results", result) if isinstance(result, dict) else result

    def delete(self, entry_id: str, namespace: str = "default") -> dict:
        """Delete a memory entry by ID.

        Args:
            entry_id: The UUID of the entry to delete.
            namespace: The namespace the entry belongs to.

        Returns:
            Status of the deletion.
        """
        return self._request(
            "DELETE",
            f"/api/memory/{quote(entry_id, safe='')}?namespace={quote(namespace, safe='')}",
        )

    def list_entries(
        self,
        namespace: str = "default",
        tag: Optional[str] = None,
        limit: int = 20,
        offset: int = 0,
    ) -> list[dict]:
        """List memory entries, newest first.

        Args:
            namespace: Namespace to list from.
            tag: Optional tag to filter by.
            limit: Maximum number of entries.
            offset: Number of entries to skip for pagination.

        Returns:
            List of memory entries ordered by creation time.
        """
        params: dict = {"namespace": namespace, "limit": limit, "offset": offset}
        if tag:
            params["tag"] = tag
        result = self._get(f"/api/memory/list?{urlencode(params)}")
        return result.get("entries", result) if isinstance(result, dict) else result

    def health(self) -> dict:
        """Health check for the ar-memory server.

        Returns:
            Dictionary with server status, version, namespace list, and entry count.

        Raises:
            ConnectionError: If the server is unreachable.
        """
        return self._get("/api/health", timeout=_HEALTH_TIMEOUT)

    def bulk_import(
        self,
        entries: list[dict],
        source: str = "migration",
        dedup_strategy: str = "skip",
    ) -> dict:
        """Import entries in bulk.

        Args:
            entries: List of entry dicts to import.
            source: Source identifier for the import.
            dedup_strategy: "skip", "overwrite", or "rename".

        Returns:
            Dictionary with imported/skipped/error counts.
        """
        payload = {
            "entries": entries,
            "source": source,
            "dedup_strategy": dedup_strategy,
        }
        return self._post("/api/memory/import", payload, timeout=60)

    # -----------------------------------------------------------------
    # Internal HTTP methods
    # -----------------------------------------------------------------

    def _get(self, path: str, timeout: int = _REQUEST_TIMEOUT) -> dict | list:
        """GET a JSON resource from the ar-memory server."""
        return self._request("GET", path, timeout=timeout)

    def _post(self, path: str, payload: dict, timeout: int = _REQUEST_TIMEOUT) -> dict | list:
        """POST a JSON payload to the ar-memory server."""
        return self._request("POST", path, payload=payload, timeout=timeout)

    def _request(
        self,
        method: str,
        path: str,
        payload: Optional[dict] = None,
        timeout: int = _REQUEST_TIMEOUT,
    ) -> dict | list:
        """Make an HTTP request with retry logic.

        Args:
            method: HTTP method.
            path: URL path.
            payload: Optional JSON body.
            timeout: Request timeout in seconds.

        Returns:
            Parsed JSON response.

        Raises:
            ConnectionError: If the server is unreachable after retries.
        """
        url = self._base_url.rstrip("/") + path
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload else None
        headers = {"Accept": "application/json"}
        if data:
            headers["Content-Type"] = "application/json; charset=utf-8"

        # Add auth token if configured
        auth_token = os.environ.get("AR_MEMORY_AUTH_TOKEN", "").strip()
        if auth_token:
            headers["Authorization"] = f"Bearer {auth_token}"

        last_error = None
        for attempt in range(_MAX_RETRIES + 1):
            try:
                req = Request(url, data=data, headers=headers, method=method)
                with urlopen(req, timeout=timeout) as resp:
                    body = resp.read().decode("utf-8")
                    if not body:
                        return {"ok": True}
                    return json.loads(body)
            except HTTPError as e:
                body = e.read().decode("utf-8", errors="replace")
                try:
                    return json.loads(body)
                except json.JSONDecodeError:
                    last_error = ConnectionError(
                        f"ar-memory HTTP {e.code}: {body[:300]}"
                    )
                    # Don't retry client errors (4xx)
                    if 400 <= e.code < 500:
                        raise last_error from e
            except (URLError, OSError, TimeoutError) as e:
                last_error = ConnectionError(
                    f"ar-memory unreachable at {url}: {e}"
                )

            if attempt < _MAX_RETRIES:
                time.sleep(_RETRY_DELAY)

        raise last_error


def _ping(url: str) -> bool:
    """Check if a URL responds to a health check."""
    try:
        req = Request(f"{url}/api/health", method="GET")
        with urlopen(req, timeout=_HEALTH_TIMEOUT) as resp:
            data = json.loads(resp.read())
            return data.get("ok", False)
    except (URLError, OSError, TimeoutError, json.JSONDecodeError):
        return False


def _discover_memory_server() -> str:
    """Auto-discover the ar-memory HTTP server.

    Tries in order:
    1. AR_MEMORY_URL environment variable
    2. Same host as FlowTree controller (AR_CONTROLLER_URL) + port 8020
    3. localhost:8020
    4. mac-studio:8020
    5. Docker host (host.docker.internal:8020) if in a container

    Returns:
        The first responding server URL.

    Raises:
        ConnectionError: If no server is found.
    """
    from urllib.parse import urlparse

    # 1. Explicit environment variable
    explicit_url = os.environ.get("AR_MEMORY_URL", "").strip()
    if explicit_url:
        if _ping(explicit_url):
            log.info("ar-memory found via AR_MEMORY_URL: %s", explicit_url)
            return explicit_url
        log.warning(
            "AR_MEMORY_URL=%s is set but server is not responding", explicit_url
        )

    # 2. Same host as FlowTree controller
    controller_url = os.environ.get("AR_CONTROLLER_URL", "").strip()
    if controller_url:
        host = urlparse(controller_url).hostname
        if host:
            url = f"http://{host}:{_DEFAULT_PORT}"
            if _ping(url):
                log.info("ar-memory found via controller host: %s", url)
                return url

    # 3. Localhost
    localhost_url = f"http://localhost:{_DEFAULT_PORT}"
    if _ping(localhost_url):
        log.info("ar-memory found on localhost: %s", localhost_url)
        return localhost_url

    # 4. Known infrastructure host
    mac_studio_url = f"http://mac-studio:{_DEFAULT_PORT}"
    if _ping(mac_studio_url):
        log.info("ar-memory found on mac-studio: %s", mac_studio_url)
        return mac_studio_url

    # 5. Docker host (container environment)
    in_container = (
        os.path.exists("/.dockerenv") or os.path.exists("/run/.containerenv")
    )
    if in_container:
        docker_url = f"http://host.docker.internal:{_DEFAULT_PORT}"
        if _ping(docker_url):
            log.info("ar-memory found via Docker host: %s", docker_url)
            return docker_url

    raise ConnectionError(
        "ar-memory server not found. Start the server or set AR_MEMORY_URL. "
        f"Tried: {explicit_url or '(not set)'}, "
        f"{controller_url + ' host' if controller_url else '(no controller)'}, "
        f"localhost:{_DEFAULT_PORT}, mac-studio:{_DEFAULT_PORT}"
        + (f", host.docker.internal:{_DEFAULT_PORT}" if in_container else "")
    )
