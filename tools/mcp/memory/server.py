#!/usr/bin/env python3
"""
AR Memory MCP Server

Provides semantic memory storage and retrieval for AI agents working on
the Almost Realism codebase. Entries are persisted in SQLite with FAISS
vector indices for similarity search.
"""

import os
import sys
from typing import Optional

# Allow running as a script (not just as a module)
sys.path.insert(0, os.path.dirname(__file__))

from mcp.server.fastmcp import FastMCP

from embedder import create_embedder
from store import MemoryStore

# Configuration via environment variables
MEMORY_DATA_DIR = os.environ.get(
    "AR_MEMORY_DATA_DIR",
    os.path.join(os.path.dirname(__file__), "data"),
)
EMBEDDING_BACKEND = os.environ.get("AR_MEMORY_BACKEND", "fastembed")
EMBEDDING_MODEL = os.environ.get("AR_MEMORY_MODEL", None)
EMBEDDING_CACHE_DIR = os.environ.get("AR_MEMORY_CACHE_DIR", None)

# Initialize embedding model and memory store
embedder = create_embedder(
    backend=EMBEDDING_BACKEND,
    model=EMBEDDING_MODEL if EMBEDDING_MODEL else None,
    cache_dir=EMBEDDING_CACHE_DIR if EMBEDDING_CACHE_DIR else None,
)
store = MemoryStore(embedder=embedder, data_dir=MEMORY_DATA_DIR)

# Initialize MCP server
mcp = FastMCP("ar-memory")


@mcp.tool()
def memory_store(
    content: str,
    namespace: str = "default",
    tags: Optional[list[str]] = None,
    source: Optional[str] = None,
) -> dict:
    """
    Store a memory entry with semantic embedding for later retrieval.

    Args:
        content: The text content to store.
        namespace: Logical grouping (e.g., "decisions", "bugs", "context").
        tags: Optional tags for categorical filtering.
        source: Optional source identifier (e.g., file path, PR number).

    Returns:
        The created entry with its ID and metadata.
    """
    return store.store(content=content, namespace=namespace, tags=tags, source=source)


@mcp.tool()
def memory_search(
    query: str,
    namespace: str = "default",
    limit: int = 5,
    tag: Optional[str] = None,
) -> list[dict]:
    """
    Search memory entries by semantic similarity to the query.

    Args:
        query: Natural language search query.
        namespace: Namespace to search within.
        limit: Maximum number of results.
        tag: Optional tag to filter results.

    Returns:
        Ranked list of matching entries with similarity scores.
    """
    return store.search(query=query, namespace=namespace, limit=limit, tag=tag)


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


if __name__ == "__main__":
    mcp.run()
