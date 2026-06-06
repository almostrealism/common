#!/usr/bin/env python3
"""
AR Memory Server (HTTP service)

Provides semantic memory storage and retrieval for AI agents working on the
Almost Realism codebase. Entries are persisted in SQLite with FAISS vector
indices for similarity search and exposed over a REST API.

This process is the backing service for the centralized ar-memory deployment
that ``ar-consultant`` and ``ar-manager`` reach over HTTP (see
``tools/mcp/common/memory_http_client.py``). Agents do NOT talk to this server
over MCP/stdio — memory is accessed exclusively through ``ar-consultant``
(interactive: ``remember``/``recall``) and ``ar-manager`` (jobs:
``memory_store``/``memory_recall``). The legacy MCP/stdio transport, which bound
a separate host-local SQLite and diverged from the central store, has been
removed so there is a single source of truth.
"""

import argparse
import logging
import os
import sys

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


def _run_http(port: int = HTTP_PORT):
    """Run the HTTP REST API server."""
    from http_api import create_http_app

    app = create_http_app(store, auth_token=AUTH_TOKEN)

    print(f"ar-memory: Starting HTTP server on port {port}", file=sys.stderr)
    print(f"ar-memory: Data directory: {MEMORY_DATA_DIR}", file=sys.stderr)
    print(f"ar-memory: Auth: {'enabled' if AUTH_TOKEN else 'disabled'}", file=sys.stderr)

    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AR Memory HTTP service")
    # --http-only is accepted for backward compatibility with existing launch
    # commands (e.g. the Dockerfile entrypoint). HTTP is now the only mode.
    parser.add_argument(
        "--http-only",
        action="store_true",
        help="Run the HTTP REST API (the only supported mode; kept for compatibility)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=HTTP_PORT,
        help=f"HTTP server port (default: {HTTP_PORT})",
    )
    args = parser.parse_args()

    _run_http(port=args.port)
