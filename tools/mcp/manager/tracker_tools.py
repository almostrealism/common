"""
Task-tracker tools for the AR Manager MCP server.

Split out of ``server.py``, which had grown past the point where any of it
could be found. The tools are unchanged; only their address is.

Two conventions make the move safe, and both matter more than they look:

* The module name ends in ``_tools``. ``McpToolDiscovery.locateManagerSources``
  finds tool modules by that suffix, and the Java guards that check every tool
  is classified read whatever it returns. A module named otherwise would leave
  its tools undiscovered, and the guard asserting each one is either granted or
  excluded would pass by having nothing to check.

* Helpers are reached as ``server.<name>(...)`` rather than imported. The test
  suite patches them on the server module — ``patch.object(server,
  "_tracker_get")`` and friends, several hundred times — and an import-time
  binding here would keep pointing at the original while the tests patched a
  name nothing called.
"""

from urllib.parse import quote, urlencode

import server
from server import mcp


@mcp.tool()
def tracker_list_projects() -> dict:
    """List all tracker projects.

    Returns all projects with their IDs and names. Projects are globally
    visible — no workspace scope restriction.

    Returns:
        dict with ok=True and a list of projects.
    """
    server._require_scope("read")
    server._audit("tracker_list_projects")
    return server._tracker_get("/v1/projects")


@mcp.tool()
def tracker_create_project(name: str) -> dict:
    """Create a new tracker project.

    Args:
        name: Human-readable project name (e.g., "Renderer").

    Returns:
        dict with ok=True and the created project record.
    """
    server._require_scope("write")
    server._audit("tracker_create_project", name=name)
    return server._tracker_post("/v1/projects", {"name": name})


@mcp.tool()
def tracker_update_project(project_id: str, name: str) -> dict:
    """Update an existing tracker project.

    Args:
        project_id: UUID of the project to update.
        name: New name for the project.

    Returns:
        dict with ok=True and the updated project record.
    """
    server._require_scope("write")
    server._audit("tracker_update_project", project_id=project_id)
    return server._tracker_put(f"/v1/projects/{project_id}", {"name": name})


@mcp.tool()
def tracker_delete_project(project_id: str) -> dict:
    """Delete a tracker project.

    Deleting a project sets project_id to NULL on any associated tasks
    and releases (ON DELETE SET NULL). Tasks are not deleted.

    Args:
        project_id: UUID of the project to delete.

    Returns:
        dict with ok=True on success.
    """
    server._require_scope("write")
    server._audit("tracker_delete_project", project_id=project_id)
    return server._tracker_delete(f"/v1/projects/{project_id}")


@mcp.tool()
def tracker_list_releases(project_id: str = "") -> dict:
    """List tracker releases, optionally filtered by project.

    Args:
        project_id: Optional UUID to filter releases to a specific project.
            Omit to list all releases.

    Returns:
        dict with ok=True and a list of releases.
    """
    server._require_scope("read")
    server._audit("tracker_list_releases", project_id=project_id)
    path = "/v1/releases"
    if project_id:
        path += "?" + urlencode({"project_id": project_id})
    return server._tracker_get(path)


@mcp.tool()
def tracker_create_release(name: str, project_id: str = "") -> dict:
    """Create a new tracker release.

    Args:
        name: Release name (e.g., "Renderer 0.38").
        project_id: Optional UUID of the associated project.

    Returns:
        dict with ok=True and the created release record.
    """
    server._require_scope("write")
    server._audit("tracker_create_release", name=name, project_id=project_id)
    payload: dict = {"name": name}
    if project_id:
        payload["project_id"] = project_id
    return server._tracker_post("/v1/releases", payload)


@mcp.tool()
def tracker_update_release(
    release_id: str,
    name: str = "",
    project_id: str = "",
) -> dict:
    """Update an existing tracker release.

    Only fields with non-empty values are updated. Pass an empty string
    to leave a field unchanged.

    To clear the project association, pass the literal string "null" for
    project_id. This sends JSON null to the tracker, which clears the FK.

    Args:
        release_id: UUID of the release to update.
        name: New name for the release. Omit to leave unchanged.
        project_id: New project UUID. Omit to leave unchanged. Pass
            "null" to clear the project association.

    Returns:
        dict with ok=True and the updated release record.
    """
    server._require_scope("write")
    server._audit("tracker_update_release", release_id=release_id)
    payload: dict = {}
    if name:
        payload["name"] = name
    if project_id == "null":
        payload["project_id"] = None
    elif project_id:
        payload["project_id"] = project_id
    return server._tracker_put(f"/v1/releases/{release_id}", payload)


@mcp.tool()
def tracker_delete_release(release_id: str) -> dict:
    """Delete a tracker release.

    Deleting a release sets release_id to NULL on any associated tasks
    (ON DELETE SET NULL). Tasks are not deleted.

    Args:
        release_id: UUID of the release to delete.

    Returns:
        dict with ok=True on success.
    """
    server._require_scope("write")
    server._audit("tracker_delete_release", release_id=release_id)
    return server._tracker_delete(f"/v1/releases/{release_id}")


@mcp.tool()
def tracker_create_task(
    title: str,
    description: str = "",
    project_id: str = "",
    release_id: str = "",
    workstream_id: str = "",
    status: str = "open",
    priority: int = 0,
) -> dict:
    """Create a new tracker task.

    Args:
        title: Short, descriptive task title.
        description: Optional longer description. Markdown supported.
        project_id: UUID of the project this task belongs to. Strongly
            recommended — tasks without a project are harder to organize.
        release_id: Optional UUID of the target release.
        workstream_id: Optional FlowTree workstream ID to link this task
            to an active coding workstream.
        status: Task status. Either "open" (default) or "closed".
        priority: Signed integer in the range [-2, 2]. Defaults to 0.
            -2 = Lowest, -1 = Low, 0 = Medium, 1 = High, 2 = Highest.

    Returns:
        dict with ok=True and the created task record.
    """
    server._require_scope("write")
    if workstream_id:
        server._require_workstream_in_scope(workstream_id)
    server._audit("tracker_create_task", project_id=project_id, workstream_id=workstream_id)
    payload: dict = {"title": title, "status": status, "priority": priority}
    if description:
        payload["description"] = description
    if project_id:
        payload["project_id"] = project_id
    if release_id:
        payload["release_id"] = release_id
    if workstream_id:
        payload["workstream_id"] = workstream_id
    return server._tracker_post("/v1/tasks", payload)


@mcp.tool()
def tracker_get_task(task_id: str) -> dict:
    """Get a single tracker task by ID.

    The full task record is returned. Workspace scoping is enforced:
    if the task is linked to a workstream outside the caller's scope,
    a PermissionError is raised (same behaviour as other scoped reads).

    Args:
        task_id: UUID of the task to retrieve.

    Returns:
        dict with ok=True and the task record.
    """
    server._require_scope("read")
    server._audit("tracker_get_task", task_id=task_id)
    result = server._tracker_get(f"/v1/tasks/{task_id}")
    if not result.get("ok"):
        return result
    ws = (result.get("task") or {}).get("workstream_id", "")
    if ws:
        server._require_workstream_in_scope(ws)
    elif server._get_workspace_scopes():
        # Scoped callers (agents) may only retrieve tasks attached to a
        # workstream in their workspace. A task with no workstream_id
        # is project-level and not workspace-bound, so we cannot prove
        # it belongs to the caller's workspace — deny it rather than
        # leak a task that may be from any project.
        raise PermissionError(
            "Task is not attached to any workstream and cannot be "
            "retrieved by a scoped caller. Tasks must be linked to a "
            "workstream in your workspace to be visible to an agent.")
    return result


@mcp.tool()
def tracker_list_tasks(
    project_id: str = "",
    release_id: str = "",
    workstream_id: str = "",
    status: str = "",
    sort: str = "",
    order: str = "",
    limit: int = 50,
    offset: int = 0,
    fields: str = "full",
) -> dict:
    """List tracker tasks with optional filtering.

    When scanning a large backlog (200+ tasks), pass fields="headlines" to
    receive only the compact task projection (id, title, priority, status,
    project_id, release_id, workstream_id, created_at, updated_at) without
    the description field. This is significantly cheaper for callers that
    only need to triage or count tasks. Use tracker_get_task to fetch the
    full record for a specific task by ID.

    Args:
        project_id: Filter to tasks in this project (UUID).
        release_id: Filter to tasks in this release (UUID).
        workstream_id: Filter to tasks linked to this workstream ID.
            Enforces workspace scope — scoped tokens may only query
            workstreams within their scope.
        status: Filter by status: "open" or "closed". Omit for all.
        sort: Sort column: "created_at" (default), "updated_at", or
            "priority". Pass "" to use the default.
        order: Sort order: "desc" (default) or "asc". Pass "" to use
            the default.
        limit: Maximum number of tasks to return. Defaults to 50, max 200.
        offset: Pagination offset. Defaults to 0.
        fields: Projection mode. "full" (default) returns all fields
            including description. "headlines" omits description.

    Returns:
        dict with ok=True, a list of tasks, and pagination info
        (total, limit, offset).
    """
    server._require_scope("read")
    if workstream_id:
        server._require_workstream_in_scope(workstream_id)
    server._audit("tracker_list_tasks", project_id=project_id,
           release_id=release_id, workstream_id=workstream_id)
    raw: dict = {}
    if project_id:
        raw["project_id"] = project_id
    if release_id:
        raw["release_id"] = release_id
    if workstream_id:
        raw["workstream_id"] = workstream_id
    if status:
        raw["status"] = status
    if sort:
        raw["sort"] = sort
    if order:
        raw["order"] = order
    if limit != 50:
        raw["limit"] = limit
    if offset:
        raw["offset"] = offset
    if fields and fields != "full":
        raw["fields"] = fields
    qs = ("?" + urlencode(raw)) if raw else ""
    result = server._tracker_get(f"/v1/tasks{qs}")
    # When the caller did not specify a workstream_id, the tracker may
    # return tasks linked to workstreams outside the caller's workspace.
    # Filter them out for scoped callers so an agent can only see tasks
    # attached to a workstream in its own workspace. When workstream_id
    # was supplied, _require_workstream_in_scope above already rejected
    # the call if the workstream was out of scope, so no filtering is
    # needed in that branch.
    if result.get("ok") and not workstream_id:
        tasks = result.get("tasks") or []
        filtered_tasks = server._filter_tasks_by_scope(tasks)
        result["tasks"] = filtered_tasks
        result["total"] = len(filtered_tasks)
        if "count" in result:
            result["count"] = len(filtered_tasks)
    return result


@mcp.tool()
def tracker_update_task(
    task_id: str,
    title: str = "",
    description: str = "",
    status: str = "",
    priority: int = -999,
    project_id: str = "",
    release_id: str = "",
    workstream_id: str = "",
) -> dict:
    """Update an existing tracker task.

    Only fields with non-empty values are updated. Pass an empty string
    to leave a field unchanged.

    To clear an optional field (e.g., remove the release association),
    pass the literal string "null" for that parameter.

    Args:
        task_id: UUID of the task to update.
        title: New title. Omit to leave unchanged.
        description: New description. Omit to leave unchanged.
        status: New status: "open" or "closed". Omit to leave unchanged.
        priority: New priority in the range [-2, 2]. Defaults to the
            sentinel value -999 meaning "leave unchanged" (zero is a
            valid priority — Medium — and cannot be used as the
            sentinel).
        project_id: New project UUID. Omit to leave unchanged. Pass
            "null" to clear.
        release_id: New release UUID. Omit to leave unchanged. Pass
            "null" to clear.
        workstream_id: New workstream ID. Omit to leave unchanged. Pass
            "null" to clear.

    Returns:
        dict with ok=True and the updated task record.
    """
    server._require_scope("write")
    current = server._tracker_get(f"/v1/tasks/{task_id}")
    if not current.get("ok"):
        return current
    current_ws = (current.get("task") or {}).get("workstream_id", "")
    if current_ws:
        server._require_workstream_in_scope(current_ws)
    if workstream_id and workstream_id != "null":
        server._require_workstream_in_scope(workstream_id)
    server._audit("tracker_update_task", task_id=task_id)
    payload: dict = {}
    if title:
        payload["title"] = title
    if description:
        payload["description"] = description
    if status:
        payload["status"] = status
    if priority != -999:
        payload["priority"] = priority
    for field, val in [("project_id", project_id),
                       ("release_id", release_id),
                       ("workstream_id", workstream_id)]:
        if val == "null":
            payload[field] = None
        elif val:
            payload[field] = val
    return server._tracker_put(f"/v1/tasks/{task_id}", payload)


@mcp.tool()
def tracker_delete_task(task_id: str) -> dict:
    """Delete a tracker task permanently.

    Args:
        task_id: UUID of the task to delete.

    Returns:
        dict with ok=True on success.
    """
    server._require_scope("write")
    current = server._tracker_get(f"/v1/tasks/{task_id}")
    if not current.get("ok"):
        return current
    ws = (current.get("task") or {}).get("workstream_id", "")
    if ws:
        server._require_workstream_in_scope(ws)
    server._audit("tracker_delete_task", task_id=task_id)
    return server._tracker_delete(f"/v1/tasks/{task_id}")


@mcp.tool()
def tracker_search_tasks(
    query: str,
    project_id: str = "",
    status: str = "",
    limit: int = 20,
    offset: int = 0,
    fields: str = "full",
) -> dict:
    """Full-text search over tracker task titles and descriptions.

    Uses SQLite FTS5 for efficient full-text search. Supports
    phrase queries ("exact phrase"), NOT, AND, OR operators.

    Pass fields="headlines" to receive a compact projection (no description)
    when scanning many search results. Use tracker_get_task to retrieve the
    full record for any result by ID.

    Args:
        query: Search string. Supports FTS5 query syntax.
        project_id: Optional UUID to restrict search to one project.
        status: Optional status filter: "open" or "closed".
        limit: Maximum results to return. Defaults to 20, max 100.
        offset: Pagination offset. Defaults to 0.
        fields: Projection mode. "full" (default) returns all fields
            including description. "headlines" omits description.

    Returns:
        dict with ok=True, a list of matching tasks, and pagination info.
    """
    server._require_scope("read")
    server._audit("tracker_search_tasks", query=query, project_id=project_id)
    raw: dict = {"q": query}
    if project_id:
        raw["project_id"] = project_id
    if status:
        raw["status"] = status
    if limit != 20:
        raw["limit"] = limit
    if offset:
        raw["offset"] = offset
    if fields and fields != "full":
        raw["fields"] = fields
    result = server._tracker_get("/v1/search/tasks?" + urlencode(raw))
    # Search has no workstream_id parameter, so for scoped callers we
    # always have to filter results: the underlying tracker can return
    # tasks attached to any workstream in the project. An agent caller
    # must not see tasks belonging to other workspaces.
    if result.get("ok"):
        tasks = result.get("tasks") or []
        filtered_tasks = server._filter_tasks_by_scope(tasks)
        filtered_total = len(filtered_tasks)
        result["tasks"] = filtered_tasks
        if "total" in result:
            result["unfiltered_total"] = result["total"]
        result["filtered_total"] = filtered_total
        result["total"] = filtered_total
        if "count" in result:
            result["count"] = filtered_total
    return result


@mcp.tool()
def tracker_project_summary(project_id: str) -> dict:
    """Return aggregate task counts for a tracker project in one call.

    Use this to answer "what's the shape of project X?" without fetching
    all task rows. Returns counts grouped by status, priority, release, and
    workstream. This is cheaper than calling tracker_list_tasks when you
    only need summary metrics.

    Workspace scoping: the by_workstream breakdown is filtered to only
    include workstreams accessible to the caller's token. Workstreams
    outside scope are silently omitted from by_workstream. Note that
    total_tasks and other aggregates (by_status, by_priority, by_release)
    are computed over all tasks in the project regardless of scope, so
    by_workstream task counts may not sum to total_tasks for scoped callers.

    Args:
        project_id: UUID of the project to summarise.

    Returns:
        dict with ok=True and a summary containing:
        - total_tasks: total task count for the project.
        - by_status: {"open": N, "closed": N} (only keys with count > 0).
        - by_priority: {-2: N, ..., 2: N} (only keys with count > 0).
        - by_release: list of {release_id, release_name, task_count,
          open_count} for each release in the project, plus one entry
          with release_id=null for tasks with no release.
        - by_workstream: list of {workstream_id, task_count, open_count}
          for each workstream linked to this project, plus one entry with
          workstream_id=null for tasks with no workstream.
    """
    server._require_scope("read")
    server._audit("tracker_project_summary", project_id=project_id)
    result = server._tracker_get(f"/v1/projects/{project_id}/summary")
    if not result.get("ok"):
        return result
    # Filter by_workstream to only include in-scope workstreams.
    summary = result.get("summary") or {}
    by_ws = summary.get("by_workstream") or []
    filtered_ws = []
    for entry in by_ws:
        ws_id = entry.get("workstream_id")
        if ws_id is None:
            # Tasks with no workstream are always included.
            filtered_ws.append(entry)
            continue
        try:
            server._require_workstream_in_scope(ws_id)
            filtered_ws.append(entry)
        except PermissionError:
            pass
    summary["by_workstream"] = filtered_ws
    return result


# ---------------------------------------------------------------------------
# Narrow tools for planning and goal-decomposition agents
# ---------------------------------------------------------------------------
# Agents cannot use the general tracker write tools above. These three give
# the two agent roles in goal-driven release automation exactly what each
# needs, with the limits enforced here rather than left to the prompt:
#
#   planner - tracker_claim_next_task
#   steward - tracker_list_release_tasks, tracker_upsert_goal_task
#
# A workstream holds a role through its trackerCapabilities controller
# setting; see server._require_tracker_capability.

# The source prefix of a task an agent derived from goal documents. Agents may
# only change tasks that carry it; every other task belongs to a person.
GOAL_SOURCE_PREFIX = "goals:"


# The tracker's error message for a release that simply does not exist yet. Any
# other failure (outage, auth, 5xx) is an error to surface, not a signal that
# the release is empty or absent — mistaking one for the other lets a steward
# decompose from a false empty view or create a duplicate after a transient
# failure.
_RELEASE_NOT_FOUND = "Release not found"


def _named_release(project: str, release: str) -> dict:
    """Look up a release by project and release name.

    Returns:
        The tracker response: ``{"ok": True, "release": {...}}``, or an error
        dict (a missing release is ``ok: False`` with a 404 message).
    """
    qs = urlencode({"project": project, "release": release})
    return server._tracker_get(f"/v1/releases/lookup?{qs}")


def _release_missing(resp: dict) -> bool:
    """Return True only for the tracker's explicit release-not-found response.

    Every other unsuccessful response is a real error to propagate rather than a
    release that does not exist.
    """
    return not resp.get("ok") and resp.get("error") == _RELEASE_NOT_FOUND


@mcp.tool()
def tracker_claim_next_task(project: str, release: str) -> dict:
    """Claim the next ready task of a release for this job's workstream.

    Takes the highest-priority task in the release that is marked ready, not
    yet linked to any workstream, and not blocked by an open task, and links
    it to the calling workstream. The claim is atomic: two callers never
    receive the same task.

    Requires the calling workstream to hold the ``planner`` tracker
    capability.

    Args:
        project: Tracker project name (e.g. the repository's project).
        release: Full release name, ``<Project> <version>``.

    Returns:
        dict with ok=True and ``task`` — the claimed task, or None when the
        release has nothing claimable (then there is no work to plan).
    """
    server._require_scope("write")
    workstream_id = server._require_tracker_capability(server.TRACKER_PLANNER)
    server._audit("tracker_claim_next_task", project=project, release=release,
                  workstream_id=workstream_id)
    return server._tracker_post("/v1/claim", {
        "project": project, "release": release, "workstream_id": workstream_id,
    })


@mcp.tool()
def tracker_list_release_tasks(
    project: str,
    release: str,
    fields: str = "full",
    limit: int = 200,
    offset: int = 0,
) -> dict:
    """List every task in one release, whoever it belongs to.

    Unlike tracker_list_tasks, this view is not limited to tasks linked to the
    caller's workspace, so a goal-decomposition agent can see what a release
    already holds before creating anything.

    Requires the calling workstream to hold the ``steward`` tracker
    capability.

    Args:
        project: Tracker project name.
        release: Full release name, ``<Project> <version>``.
        fields: "full" (default) or "headlines" (omits descriptions).
        limit: Maximum tasks to return (max 200).
        offset: Pagination offset.

    Returns:
        dict with ok=True, ``release`` and the tasks with pagination info.
        A release that does not exist yet has no tasks: ``tasks`` is empty.
    """
    server._require_scope("read")
    server._require_tracker_capability(server.TRACKER_STEWARD)
    server._audit("tracker_list_release_tasks", project=project, release=release)
    found = _named_release(project, release)
    if not found.get("ok"):
        if _release_missing(found):
            return {"ok": True, "release": None, "tasks": [], "total": 0,
                    "limit": limit, "offset": offset}
        # A tracker outage or error is not an empty release; surface it so the
        # steward does not decompose from a false empty view.
        return found
    release_id = found["release"]["id"]
    qs = urlencode({"release_id": release_id, "fields": fields,
                    "limit": limit, "offset": offset})
    result = server._tracker_get(f"/v1/tasks?{qs}")
    if result.get("ok"):
        result["release"] = found["release"]
    return result


def _ensure_release(project: str, release: str) -> dict:
    """Return the named release, creating it in the named project if needed.

    A project is never created: a missing project is an error. The tracker
    performs the lookup and the create as one atomic step, so stewards working
    concurrently on the same release never create duplicates of it and attach
    their tasks to different release ids. Any tracker error, including an
    outage, is returned unchanged.
    """
    result = server._tracker_post("/v1/releases/ensure", {
        "project": project, "release": release,
    })
    if not result.get("ok") and result.get("error") == "Project not found":
        return {"ok": False, "error": f"Tracker project '{project}' does not exist"}
    return result


@mcp.tool()
def tracker_upsert_goal_task(
    project: str,
    release: str,
    title: str,
    source: str,
    description: str = "",
    priority: int = 0,
    blocked_by: str = "",
    task_id: str = "",
) -> dict:
    """Create or update a task derived from goal documents.

    A new task is created ready to be planned, in the named release, which is
    created too if it does not exist yet (the project must exist). An update
    changes the task's title, description, priority, release and blockers;
    its stage and status are left alone, so a task a person declined or
    closed stays that way.

    Only goal-derived tasks can be changed: ``source`` must be
    ``goals:<document>``, and updating a task that does not carry such a
    source — any task a person wrote — is refused by the tracker in the same
    step as the write. Nothing is ever deleted.

    Requires the calling workstream to hold the ``steward`` tracker
    capability.

    Args:
        project: Tracker project name.
        release: Full release name, ``<Project> <version>``.
        title: Short task title.
        source: ``goals:<document path>`` naming the document the task was
            derived from.
        description: Markdown description: what the work is, why the release
            needs it, and how to tell it is done.
        priority: Integer in [-2, 2]; 0 is medium.
        blocked_by: Comma-separated ids of tasks that must close before this
            one is planned, for example the framework task an application
            task depends on. Empty clears the blockers.
        task_id: Id of an existing goal-derived task to update. Omit to
            create a new task.

    Returns:
        dict with ok=True and the created or updated task.
    """
    server._require_scope("write")
    server._require_tracker_capability(server.TRACKER_STEWARD)
    server._audit("tracker_upsert_goal_task", project=project, release=release,
                  task_id=task_id)
    source = source.strip()
    if not source.startswith(GOAL_SOURCE_PREFIX) or len(source) == len(GOAL_SOURCE_PREFIX):
        return {"ok": False,
                "error": f"source must be '{GOAL_SOURCE_PREFIX}<document>' (e.g. goals:docs/GOALS.md)"}
    # Checked here as well as by the tracker because ensuring the release is a
    # write: a request the tracker would refuse must not leave a new, empty
    # release behind.
    if not title.strip():
        return {"ok": False, "error": "title is required"}
    if isinstance(priority, bool) or not isinstance(priority, int) or not -2 <= priority <= 2:
        return {"ok": False, "error": "priority must be an integer in [-2, 2]"}

    target = _ensure_release(project, release)
    if not target.get("ok"):
        return target
    fields = {
        "title": title,
        "description": description or None,
        "priority": priority,
        "project_id": target["release"]["project_id"],
        "release_id": target["release"]["id"],
        "blocked_by": [b.strip() for b in blocked_by.split(",") if b.strip()],
    }

    if not task_id:
        return server._tracker_post("/v1/tasks", dict(
            fields, source=source, stage="ready", status="open"))

    # The tracker checks the stored source and writes in one step, so a task a
    # person takes over between any earlier read and this request is refused.
    return server._tracker_put(f"/v1/tasks/{quote(task_id, safe='')}?only_goal_derived=true",
                               dict(fields, source=source))
