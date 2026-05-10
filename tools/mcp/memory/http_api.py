"""
HTTP REST API for the ar-memory server.

Provides FastAPI routes for storing, searching, and managing memories.
This module is used by server.py to add HTTP endpoints alongside or
instead of the MCP transport.
"""

import json
import logging
from typing import Optional

from starlette.applications import Starlette
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

log = logging.getLogger(__name__)


def create_http_app(store, auth_token: Optional[str] = None) -> Starlette:
    """Create a Starlette HTTP application with REST endpoints for memory operations.

    Args:
        store: A MemoryStore instance.
        auth_token: Optional bearer token for authentication.

    Returns:
        A Starlette application.
    """

    async def _check_auth(request: Request) -> Optional[JSONResponse]:
        """Validate bearer token if authentication is configured."""
        if not auth_token:
            return None
        header = request.headers.get("authorization", "")
        if header.startswith("Bearer ") and header[7:].strip() == auth_token:
            return None
        return JSONResponse(
            {"ok": False, "error": "Unauthorized: valid Bearer token required"},
            status_code=401,
            headers={"WWW-Authenticate": 'Bearer realm="ar-memory"'},
        )

    async def health(request: Request) -> JSONResponse:
        """GET /api/health - Server health check."""
        # Count entries per namespace
        cursor = store._conn.execute(
            "SELECT namespace, COUNT(*) as cnt FROM entries GROUP BY namespace"
        )
        namespace_counts = {row[0]: row[1] for row in cursor.fetchall()}
        total = sum(namespace_counts.values())

        return JSONResponse({
            "ok": True,
            "version": "1.0.0",
            "namespaces": list(namespace_counts.keys()),
            "namespace_counts": namespace_counts,
            "total_entries": total,
        })

    async def memory_store_endpoint(request: Request) -> JSONResponse:
        """POST /api/memory/store - Store a memory entry."""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err

        try:
            body = await request.json()
        except json.JSONDecodeError:
            return JSONResponse(
                {"ok": False, "error": "Invalid JSON body"},
                status_code=400,
            )

        content = body.get("content")
        repo_url = body.get("repo_url")
        branch = body.get("branch")

        if not content:
            return JSONResponse(
                {"ok": False, "error": "content is required"},
                status_code=400,
            )
        if not repo_url:
            return JSONResponse(
                {"ok": False, "error": "repo_url is required"},
                status_code=400,
            )
        if not branch:
            return JSONResponse(
                {"ok": False, "error": "branch is required"},
                status_code=400,
            )

        entry = store.store(
            content=content,
            namespace=body.get("namespace", "default"),
            tags=body.get("tags"),
            source=body.get("source"),
            repo_url=repo_url,
            branch=branch,
        )
        return JSONResponse(entry, status_code=201)

    async def memory_search_endpoint(request: Request) -> JSONResponse:
        """POST /api/memory/search - Semantic search."""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err

        try:
            body = await request.json()
        except json.JSONDecodeError:
            return JSONResponse(
                {"ok": False, "error": "Invalid JSON body"},
                status_code=400,
            )

        query = body.get("query")
        if not query:
            return JSONResponse(
                {"ok": False, "error": "query is required"},
                status_code=400,
            )

        results = store.search(
            query=query,
            namespace=body.get("namespace", "default"),
            limit=body.get("limit", 5),
            tag=body.get("tag"),
            repo_url=body.get("repo_url"),
            branch=body.get("branch"),
        )
        return JSONResponse({"results": results, "count": len(results)})

    async def memory_branch_endpoint(request: Request) -> JSONResponse:
        """POST /api/memory/branch - Branch context lookup."""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err

        try:
            body = await request.json()
        except json.JSONDecodeError:
            return JSONResponse(
                {"ok": False, "error": "Invalid JSON body"},
                status_code=400,
            )

        repo_url = body.get("repo_url", "")
        branch = body.get("branch", "")
        if not repo_url or not branch:
            return JSONResponse(
                {"ok": False, "error": "repo_url and branch are required"},
                status_code=400,
            )

        # Default is the "default" namespace for backwards compatibility, but
        # callers can pass ``None`` / ``""`` to get memories across every
        # namespace sorted by recency.
        namespace = body.get("namespace", "default")
        if namespace == "":
            namespace = None
        limit = body.get("limit", 20)

        results = store.search_by_branch(
            repo_url=repo_url,
            branch=branch,
            namespace=namespace,
            limit=limit,
        )
        return JSONResponse({"results": results, "count": len(results)})

    async def memory_delete_endpoint(request: Request) -> JSONResponse:
        """DELETE /api/memory/{entry_id} - Delete by ID."""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err

        entry_id = request.path_params["entry_id"]
        namespace = request.query_params.get("namespace", "default")

        result = store.delete(entry_id=entry_id, namespace=namespace)
        status = 200 if result.get("deleted") else 404
        return JSONResponse(result, status_code=status)

    async def memory_list_endpoint(request: Request) -> JSONResponse:
        """GET /api/memory/list - List entries."""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err

        namespace = request.query_params.get("namespace", "default")
        tag = request.query_params.get("tag")
        limit = int(request.query_params.get("limit", "20"))
        offset = int(request.query_params.get("offset", "0"))

        results = store.list_entries(
            namespace=namespace, tag=tag, limit=limit, offset=offset,
        )
        return JSONResponse({"entries": results, "count": len(results)})

    async def memory_import_endpoint(request: Request) -> JSONResponse:
        """POST /api/memory/import - Bulk import entries."""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err

        try:
            body = await request.json()
        except json.JSONDecodeError:
            return JSONResponse(
                {"ok": False, "error": "Invalid JSON body"},
                status_code=400,
            )

        entries = body.get("entries", [])
        dedup_strategy = body.get("dedup_strategy", "skip")

        imported = 0
        skipped = 0
        errors = []

        for i, entry in enumerate(entries):
            try:
                content = entry.get("content", "")
                if not content:
                    errors.append({"index": i, "error": "empty content"})
                    continue

                repo_url = entry.get("repo_url", "")
                branch = entry.get("branch", "")

                if not repo_url or not branch:
                    errors.append({"index": i, "error": "missing repo_url or branch"})
                    continue

                # Check for duplicates by content hash
                if dedup_strategy == "skip":
                    existing = store.search(
                        query=content,
                        namespace=entry.get("namespace", "default"),
                        limit=1,
                        repo_url=repo_url,
                        branch=branch,
                    )
                    if existing and existing[0].get("score", 999) < 0.01:
                        skipped += 1
                        continue

                store.store(
                    content=content,
                    namespace=entry.get("namespace", "default"),
                    tags=entry.get("tags"),
                    source=entry.get("source"),
                    repo_url=repo_url,
                    branch=branch,
                )
                imported += 1
            except Exception as e:
                errors.append({"index": i, "error": str(e)})

        return JSONResponse({
            "ok": True,
            "imported": imported,
            "skipped": skipped,
            "errors": errors,
        })

    routes = [
        Route("/api/health", health, methods=["GET"]),
        Route("/api/memory/store", memory_store_endpoint, methods=["POST"]),
        Route("/api/memory/search", memory_search_endpoint, methods=["POST"]),
        Route("/api/memory/branch", memory_branch_endpoint, methods=["POST"]),
        Route("/api/memory/{entry_id}", memory_delete_endpoint, methods=["DELETE"]),
        Route("/api/memory/list", memory_list_endpoint, methods=["GET"]),
        Route("/api/memory/import", memory_import_endpoint, methods=["POST"]),
    ]

    app = Starlette(routes=routes)

    # Add CORS middleware for browser-based clients
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["*"],
    )

    return app
