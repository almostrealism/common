#!/usr/bin/env python3
"""
AR Memory Server

Provides semantic memory storage and retrieval for AI agents working on
the Almost Realism codebase. Entries are persisted in SQLite with FAISS
vector indices for similarity search.

Supports three startup modes:
  --http-only  : HTTP REST API only (target architecture)
  --mcp-only   : MCP stdio transport only (legacy)
  (default)    : Hybrid mode - both MCP and HTTP (migration)
"""

import argparse
import logging
import os
import sys
from typing import Optional

# Allow running as a script (not just as a module)
sys.path.insert(0, os.path.dirname(__file__))

from embedder import create_embedder
from store import MemoryStore

log = logging.getLogger(__name__)

# Configuration via environment variables
MEMORY_DATA_DIR = os.environ.get(
    "AR_MEMORY_DATA_DIR",
    os.path.join(os.path.dirname(__file__), "data"),
)
EMBEDDING_BACKEND = os.environ.get("AR_MEMORY_BACKEND", "fastembed")
EMBEDDING_MODEL = os.environ.get("AR_MEMORY_MODEL", None)
EMBEDDING_CACHE_DIR = os.environ.get("AR_MEMORY_CACHE_DIR", None)
HTTP_PORT = int(os.environ.get("AR_MEMORY_HTTP_PORT", "8020"))
AUTH_TOKEN = os.environ.get("AR_MEMORY_AUTH_TOKEN", "").strip() or None

# Initialize embedding model and memory store
embedder = create_embedder(
    backend=EMBEDDING_BACKEND,
    model=EMBEDDING_MODEL if EMBEDDING_MODEL else None,
    cache_dir=EMBEDDING_CACHE_DIR if EMBEDDING_CACHE_DIR else None,
)
store = MemoryStore(embedder=embedder, data_dir=MEMORY_DATA_DIR)


# ---------------------------------------------------------------------------
# MCP server and tools (used in --mcp-only and hybrid modes)
# ---------------------------------------------------------------------------

def _create_mcp_server():
    """Create and configure the MCP server with tool definitions."""
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("ar-memory")

    @mcp.tool()
    def memory_store(
        content: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> dict:
        """
        Store a memory entry with semantic embedding for later retrieval.

        Args:
            content: The text content to store.
            namespace: Logical grouping (e.g., "decisions", "bugs", "context").
            tags: Optional tags for categorical filtering.
            source: Optional source identifier (e.g., file path, PR number).
            repo_url: Optional repository URL to associate with this memory.
            branch: Optional branch name to associate with this memory.

        Returns:
            The created entry with its ID and metadata.
        """
        return store.store(
            content=content, namespace=namespace, tags=tags, source=source,
            repo_url=repo_url, branch=branch,
        )

    @mcp.tool()
    def memory_search(
        query: str,
        namespace: str = "default",
        limit: int = 5,
        tag: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> list[dict]:
        """
        Search memory entries by semantic similarity to the query.

        Args:
            query: Natural language search query.
            namespace: Namespace to search within.
            limit: Maximum number of results.
            tag: Optional tag to filter results.
            repo_url: Optional repository URL to filter results.
            branch: Optional branch name to filter results.

        Returns:
            Ranked list of matching entries with similarity scores.
        """
        return store.search(
            query=query, namespace=namespace, limit=limit, tag=tag,
            repo_url=repo_url, branch=branch,
        )

    @mcp.tool()
    def memory_delete(
        entry_id: str,
        namespace: str = "default",
    ) -> dict:
        """
        Delete a memory entry by ID.

        Args:
            entry_id: The UUID of the entry to delete.
            namespace: The namespace the entry belongs to.

        Returns:
            Status of the deletion.
        """
        return store.delete(entry_id=entry_id, namespace=namespace)

    @mcp.tool()
    def memory_list(
        namespace: str = "default",
        tag: Optional[str] = None,
        limit: int = 20,
        offset: int = 0,
    ) -> list[dict]:
        """
        List memory entries, newest first.

        Args:
            namespace: Namespace to list from.
            tag: Optional tag to filter by.
            limit: Maximum number of entries.
            offset: Number of entries to skip for pagination.

        Returns:
            List of memory entries ordered by creation time.
        """
        return store.list_entries(namespace=namespace, tag=tag, limit=limit, offset=offset)

    return mcp


def _run_mcp(transport: str = "stdio"):
    """Run the MCP server."""
    mcp = _create_mcp_server()

    if transport in ("http", "sse"):
        from mcp.server.transport_security import TransportSecuritySettings

        port = int(os.environ.get("MCP_PORT", "8000"))
        mcp.settings.host = "0.0.0.0"
        mcp.settings.port = port
        mcp.settings.transport_security = TransportSecuritySettings(
            enable_dns_rebinding_protection=False,
        )
        mcp.run(transport="streamable-http" if transport == "http" else "sse")
    else:
        mcp.run()


def _run_http(port: int = HTTP_PORT):
    """Run the HTTP REST API server."""
    from http_api import create_http_app

    app = create_http_app(store, auth_token=AUTH_TOKEN)

    print(f"ar-memory: Starting HTTP server on port {port}", file=sys.stderr)
    print(f"ar-memory: Data directory: {MEMORY_DATA_DIR}", file=sys.stderr)
    print(f"ar-memory: Auth: {'enabled' if AUTH_TOKEN else 'disabled'}", file=sys.stderr)

    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")


def _run_hybrid(port: int = HTTP_PORT):
    """Run both MCP (stdio) and HTTP in the same process.

    MCP runs in the main thread via stdio, HTTP runs in a background thread.
    """
    import threading
    from http_api import create_http_app

    app = create_http_app(store, auth_token=AUTH_TOKEN)

    print(f"ar-memory: Starting hybrid mode (MCP + HTTP:{port})", file=sys.stderr)
    print(f"ar-memory: Data directory: {MEMORY_DATA_DIR}", file=sys.stderr)

    # Start HTTP server in a daemon thread
    def run_http():
        import uvicorn
        uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")

    http_thread = threading.Thread(target=run_http, daemon=True)
    http_thread.start()

    # Run MCP in the main thread
    _run_mcp("stdio")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AR Memory Server")
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--http-only",
        action="store_true",
        help="Run HTTP REST API only (target architecture)",
    )
    group.add_argument(
        "--mcp-only",
        action="store_true",
        help="Run MCP server only (legacy mode)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=HTTP_PORT,
        help=f"HTTP server port (default: {HTTP_PORT})",
    )
    args = parser.parse_args()

    if args.http_only:
        _run_http(port=args.port)
    elif args.mcp_only:
        transport = os.environ.get("MCP_TRANSPORT", "stdio")
        _run_mcp(transport)
    else:
        # Default: hybrid mode during migration
        # If MCP_TRANSPORT is set to http/sse, run MCP in that mode
        # Otherwise, run hybrid (MCP stdio + HTTP)
        transport = os.environ.get("MCP_TRANSPORT", "stdio")
        if transport in ("http", "sse"):
            # Can't run hybrid with MCP on HTTP, just run MCP
            _run_mcp(transport)
        else:
            _run_hybrid(port=args.port)
