"""HTTP REST API for ar-tracker.

Provides Starlette routes for CRUD on projects, releases, and tasks,
plus full-text search and bulk import.
"""

import json
import logging
from typing import Optional

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from store import UNSET

log = logging.getLogger(__name__)

# Allowed status values
_VALID_STATUSES = {"open", "closed"}

# Priority is a signed integer; database CHECK constraint mirrors this range.
_MIN_PRIORITY = -2
_MAX_PRIORITY = 2


def _validate_priority(value: object) -> tuple:
    """Coerce and range-check a priority value from a request body.

    Args:
        value: Raw value from the JSON body (int, str, or None).

    Returns:
        A (priority_int, error_response) tuple. On success ``error_response``
        is None. On failure ``priority_int`` is None and ``error_response``
        is a 400 JSONResponse ready to return.
    """
    if isinstance(value, bool):
        return None, JSONResponse(
            {"ok": False, "error": "priority must be an integer in [-2, 2]"},
            status_code=400,
        )
    try:
        priority = int(value)
    except (TypeError, ValueError):
        return None, JSONResponse(
            {"ok": False, "error": "priority must be an integer in [-2, 2]"},
            status_code=400,
        )
    if priority < _MIN_PRIORITY or priority > _MAX_PRIORITY:
        return None, JSONResponse(
            {"ok": False, "error": "priority must be an integer in [-2, 2]"},
            status_code=400,
        )
    return priority, None


def create_http_app(store, auth_token: Optional[str] = None) -> Starlette:
    """Create the Starlette application for ar-tracker.

    Args:
        store: A TrackerStore instance.
        auth_token: Optional bearer token. If None, the API is open.

    Returns:
        A Starlette application.
    """

    async def _check_auth(request: Request) -> Optional[JSONResponse]:
        if not auth_token:
            return None
        header = request.headers.get("authorization", "")
        if header.startswith("Bearer ") and header[7:].strip() == auth_token:
            return None
        return JSONResponse(
            {"ok": False, "error": "Unauthorized: valid Bearer token required"},
            status_code=401,
            headers={"WWW-Authenticate": 'Bearer realm="ar-tracker"'},
        )

    async def _json_body(request: Request):
        try:
            return await request.json(), None
        except (json.JSONDecodeError, ValueError):
            return None, JSONResponse(
                {"ok": False, "error": "Invalid JSON body"}, status_code=400
            )

    # ------------------------------------------------------------------
    # Health
    # ------------------------------------------------------------------

    async def health(request: Request) -> JSONResponse:
        """GET /api/health"""
        return JSONResponse({
            "ok": True,
            "counts": store.counts(),
        })

    # ------------------------------------------------------------------
    # Projects
    # ------------------------------------------------------------------

    async def list_projects(request: Request) -> JSONResponse:
        """GET /v1/projects"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        return JSONResponse({"ok": True, "projects": store.list_projects()})

    async def create_project(request: Request) -> JSONResponse:
        """POST /v1/projects"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        name = (body.get("name") or "").strip()
        if not name:
            return JSONResponse(
                {"ok": False, "error": "name is required"}, status_code=400
            )
        project = store.create_project(name)
        return JSONResponse({"ok": True, "project": project}, status_code=201)

    async def get_project(request: Request) -> JSONResponse:
        """GET /v1/projects/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        project = store.get_project(request.path_params["id"])
        if not project:
            return JSONResponse(
                {"ok": False, "error": "Project not found"}, status_code=404
            )
        return JSONResponse({"ok": True, "project": project})

    async def update_project(request: Request) -> JSONResponse:
        """PUT /v1/projects/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        name = (body.get("name") or "").strip()
        if not name:
            return JSONResponse(
                {"ok": False, "error": "name is required"}, status_code=400
            )
        project = store.update_project(request.path_params["id"], name)
        if not project:
            return JSONResponse(
                {"ok": False, "error": "Project not found"}, status_code=404
            )
        return JSONResponse({"ok": True, "project": project})

    async def delete_project(request: Request) -> JSONResponse:
        """DELETE /v1/projects/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        deleted = store.delete_project(request.path_params["id"])
        if not deleted:
            return JSONResponse(
                {"ok": False, "error": "Project not found"}, status_code=404
            )
        return JSONResponse({"ok": True})

    # ------------------------------------------------------------------
    # Releases
    # ------------------------------------------------------------------

    async def list_releases(request: Request) -> JSONResponse:
        """GET /v1/releases"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        project_id = request.query_params.get("project_id")
        return JSONResponse({"ok": True, "releases": store.list_releases(project_id)})

    async def create_release(request: Request) -> JSONResponse:
        """POST /v1/releases"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        name = (body.get("name") or "").strip()
        if not name:
            return JSONResponse(
                {"ok": False, "error": "name is required"}, status_code=400
            )
        release = store.create_release(name, body.get("project_id") or None)
        return JSONResponse({"ok": True, "release": release}, status_code=201)

    async def get_release(request: Request) -> JSONResponse:
        """GET /v1/releases/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        release = store.get_release(request.path_params["id"])
        if not release:
            return JSONResponse(
                {"ok": False, "error": "Release not found"}, status_code=404
            )
        return JSONResponse({"ok": True, "release": release})

    async def update_release(request: Request) -> JSONResponse:
        """PUT /v1/releases/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        # Validate name: if provided it must be non-empty after stripping.
        if "name" in body:
            name_raw = body["name"]
            if not isinstance(name_raw, str) or not name_raw.strip():
                return JSONResponse(
                    {"ok": False, "error": "name cannot be empty"}, status_code=400
                )
            name = name_raw.strip()
        else:
            name = UNSET
        # project_id: absent → leave unchanged; null → clear; non-empty string → set.
        # An explicitly provided empty string is rejected to avoid FK IntegrityError.
        if "project_id" in body:
            pid_raw = body["project_id"]
            if pid_raw is None:
                project_id = None
            elif isinstance(pid_raw, str) and pid_raw.strip():
                project_id = pid_raw
            else:
                return JSONResponse(
                    {"ok": False,
                     "error": "project_id must be a non-empty UUID string or null"},
                    status_code=400,
                )
        else:
            project_id = UNSET
        release = store.update_release(request.path_params["id"], name, project_id)
        if not release:
            return JSONResponse(
                {"ok": False, "error": "Release not found"}, status_code=404
            )
        return JSONResponse({"ok": True, "release": release})

    async def delete_release(request: Request) -> JSONResponse:
        """DELETE /v1/releases/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        deleted = store.delete_release(request.path_params["id"])
        if not deleted:
            return JSONResponse(
                {"ok": False, "error": "Release not found"}, status_code=404
            )
        return JSONResponse({"ok": True})

    # ------------------------------------------------------------------
    # Tasks
    # ------------------------------------------------------------------

    async def list_tasks(request: Request) -> JSONResponse:
        """GET /v1/tasks

        Accepts an optional ``fields`` query parameter:
        - ``full`` (default) — returns all fields including description.
        - ``headlines`` — omits description; use when scanning large backlogs.
        """
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        qp = request.query_params
        try:
            limit = min(int(qp.get("limit", 50)), 200)
            offset = int(qp.get("offset", 0))
        except ValueError:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be integers"},
                status_code=400,
            )
        if limit < 0 or offset < 0:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be non-negative"},
                status_code=400,
            )
        status = qp.get("status")
        if status and status not in _VALID_STATUSES:
            return JSONResponse(
                {"ok": False, "error": f"status must be one of: {sorted(_VALID_STATUSES)}"},
                status_code=400,
            )
        fields = qp.get("fields", "full")
        if fields not in ("full", "headlines"):
            return JSONResponse(
                {"ok": False, "error": "fields must be 'full' or 'headlines'"},
                status_code=400,
            )
        result = store.list_tasks(
            project_id=qp.get("project_id") or None,
            release_id=qp.get("release_id") or None,
            workstream_id=qp.get("workstream_id") or None,
            status=status or None,
            limit=limit,
            offset=offset,
            sort=qp.get("sort", "created_at"),
            order=qp.get("order", "desc"),
            headlines_only=(fields == "headlines"),
        )
        return JSONResponse({"ok": True, **result})

    async def create_task(request: Request) -> JSONResponse:
        """POST /v1/tasks"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        title = (body.get("title") or "").strip()
        if not title:
            return JSONResponse(
                {"ok": False, "error": "title is required"}, status_code=400
            )
        status = body.get("status", "open")
        if status not in _VALID_STATUSES:
            return JSONResponse(
                {"ok": False, "error": f"status must be one of: {sorted(_VALID_STATUSES)}"},
                status_code=400,
            )
        priority = 0
        if "priority" in body:
            priority, err_resp = _validate_priority(body["priority"])
            if err_resp:
                return err_resp
        task = store.create_task(
            title=title,
            description=body.get("description") or None,
            status=status,
            priority=priority,
            project_id=body.get("project_id") or None,
            release_id=body.get("release_id") or None,
            workstream_id=body.get("workstream_id") or None,
        )
        return JSONResponse({"ok": True, "task": task}, status_code=201)

    async def get_task(request: Request) -> JSONResponse:
        """GET /v1/tasks/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        task = store.get_task(request.path_params["id"])
        if not task:
            return JSONResponse(
                {"ok": False, "error": "Task not found"}, status_code=404
            )
        return JSONResponse({"ok": True, "task": task})

    async def update_task(request: Request) -> JSONResponse:
        """PUT /v1/tasks/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        task_id = request.path_params["id"]

        existing = store.get_task(task_id)
        if not existing:
            return JSONResponse(
                {"ok": False, "error": "Task not found"}, status_code=404
            )

        status = body.get("status")
        if status is not None and status not in _VALID_STATUSES:
            return JSONResponse(
                {"ok": False, "error": f"status must be one of: {sorted(_VALID_STATUSES)}"},
                status_code=400,
            )

        if "title" in body and not (body.get("title") or "").strip():
            return JSONResponse(
                {"ok": False, "error": "title cannot be empty"}, status_code=400
            )

        priority_field: object = UNSET
        if "priority" in body:
            priority_value, err_resp = _validate_priority(body["priority"])
            if err_resp:
                return err_resp
            priority_field = priority_value

        def _field(key: str, coerce=None):
            """Return UNSET if key absent from body, else body value (possibly None)."""
            if key not in body:
                return UNSET
            val = body[key]
            if val is None:
                return None
            return coerce(val) if coerce else val

        task = store.update_task(
            task_id=task_id,
            title=_field("title", coerce=lambda v: v.strip()),
            description=_field("description"),
            status=status if status is not None else UNSET,
            priority=priority_field,
            project_id=_field("project_id"),
            release_id=_field("release_id"),
            workstream_id=_field("workstream_id"),
        )
        return JSONResponse({"ok": True, "task": task})

    async def delete_task(request: Request) -> JSONResponse:
        """DELETE /v1/tasks/{id}"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        deleted = store.delete_task(request.path_params["id"])
        if not deleted:
            return JSONResponse(
                {"ok": False, "error": "Task not found"}, status_code=404
            )
        return JSONResponse({"ok": True})

    # ------------------------------------------------------------------
    # Search
    # ------------------------------------------------------------------

    async def search_tasks(request: Request) -> JSONResponse:
        """GET /v1/search/tasks

        Accepts an optional ``fields`` query parameter:
        - ``full`` (default) — returns all fields including description.
        - ``headlines`` — omits description; use when scanning large backlogs.
        """
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        qp = request.query_params
        query = (qp.get("q") or "").strip()
        if not query:
            return JSONResponse(
                {"ok": False, "error": "q (search query) is required"}, status_code=400
            )
        try:
            limit = min(int(qp.get("limit", 20)), 100)
            offset = int(qp.get("offset", 0))
        except ValueError:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be integers"},
                status_code=400,
            )
        if limit < 0 or offset < 0:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be non-negative"},
                status_code=400,
            )
        fields = qp.get("fields", "full")
        if fields not in ("full", "headlines"):
            return JSONResponse(
                {"ok": False, "error": "fields must be 'full' or 'headlines'"},
                status_code=400,
            )
        result = store.search_tasks(
            query=query,
            project_id=qp.get("project_id") or None,
            status=qp.get("status") or None,
            limit=limit,
            offset=offset,
            headlines_only=(fields == "headlines"),
        )
        return JSONResponse({"ok": True, **result})

    # ------------------------------------------------------------------
    # Project summary
    # ------------------------------------------------------------------

    async def project_summary(request: Request) -> JSONResponse:
        """GET /v1/projects/{id}/summary"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        summary = store.project_summary(request.path_params["id"])
        if summary is None:
            return JSONResponse(
                {"ok": False, "error": "Project not found"}, status_code=404
            )
        return JSONResponse({"ok": True, "summary": summary})

    # ------------------------------------------------------------------
    # Bulk import
    # ------------------------------------------------------------------

    async def bulk_import(request: Request) -> JSONResponse:
        """POST /v1/import"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        body, err = await _json_body(request)
        if err:
            return err
        projects = body.get("projects") or []
        releases = body.get("releases") or []
        tasks = body.get("tasks") or []
        result = store.bulk_import(projects, releases, tasks)
        if "error" in result:
            return JSONResponse({"ok": False, "error": result["error"]}, status_code=400)
        return JSONResponse({"ok": True, **result})

    # ------------------------------------------------------------------
    # Sub-resource shortcuts
    # ------------------------------------------------------------------

    async def project_tasks(request: Request) -> JSONResponse:
        """GET /v1/projects/{id}/tasks"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        qp = request.query_params
        try:
            limit = min(int(qp.get("limit", 50)), 200)
            offset = int(qp.get("offset", 0))
        except ValueError:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be integers"},
                status_code=400,
            )
        result = store.list_tasks(
            project_id=request.path_params["id"],
            limit=limit,
            offset=offset,
        )
        return JSONResponse({"ok": True, **result})

    async def release_tasks(request: Request) -> JSONResponse:
        """GET /v1/releases/{id}/tasks"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        qp = request.query_params
        try:
            limit = min(int(qp.get("limit", 50)), 200)
            offset = int(qp.get("offset", 0))
        except ValueError:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be integers"},
                status_code=400,
            )
        result = store.list_tasks(
            release_id=request.path_params["id"],
            limit=limit,
            offset=offset,
        )
        return JSONResponse({"ok": True, **result})

    async def workstream_tasks(request: Request) -> JSONResponse:
        """GET /v1/workstreams/{id}/tasks"""
        auth_err = await _check_auth(request)
        if auth_err:
            return auth_err
        qp = request.query_params
        try:
            limit = min(int(qp.get("limit", 50)), 200)
            offset = int(qp.get("offset", 0))
        except ValueError:
            return JSONResponse(
                {"ok": False, "error": "limit and offset must be integers"},
                status_code=400,
            )
        result = store.list_tasks(
            workstream_id=request.path_params["id"],
            limit=limit,
            offset=offset,
        )
        return JSONResponse({"ok": True, **result})

    routes = [
        Route("/api/health", health),
        Route("/v1/projects", list_projects, methods=["GET"]),
        Route("/v1/projects", create_project, methods=["POST"]),
        Route("/v1/projects/{id}", get_project, methods=["GET"]),
        Route("/v1/projects/{id}", update_project, methods=["PUT"]),
        Route("/v1/projects/{id}", delete_project, methods=["DELETE"]),
        Route("/v1/projects/{id}/summary", project_summary, methods=["GET"]),
        Route("/v1/projects/{id}/tasks", project_tasks, methods=["GET"]),
        Route("/v1/releases", list_releases, methods=["GET"]),
        Route("/v1/releases", create_release, methods=["POST"]),
        Route("/v1/releases/{id}", get_release, methods=["GET"]),
        Route("/v1/releases/{id}", update_release, methods=["PUT"]),
        Route("/v1/releases/{id}", delete_release, methods=["DELETE"]),
        Route("/v1/releases/{id}/tasks", release_tasks, methods=["GET"]),
        Route("/v1/tasks", list_tasks, methods=["GET"]),
        Route("/v1/tasks", create_task, methods=["POST"]),
        Route("/v1/tasks/{id}", get_task, methods=["GET"]),
        Route("/v1/tasks/{id}", update_task, methods=["PUT"]),
        Route("/v1/tasks/{id}", delete_task, methods=["DELETE"]),
        Route("/v1/workstreams/{id}/tasks", workstream_tasks, methods=["GET"]),
        Route("/v1/search/tasks", search_tasks, methods=["GET"]),
        Route("/v1/import", bulk_import, methods=["POST"]),
    ]

    return Starlette(routes=routes)
