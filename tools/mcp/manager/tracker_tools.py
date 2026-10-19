"""
Task-tracker tools for the AR Manager MCP server.

Split out of ``server.py``, which had grown past the point where any of it
could be found.

Two conventions make the split safe, and both matter more than they look:

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

Every caller sees the same tools; what a call may do is decided here, from who
is calling:

* Reads are limited to the caller's workspaces. A tracker project is owned by
  one workspace and its releases and tasks belong to it; a caller limited to
  some workspaces never sees anything else.
* Anyone may create a task. An agent job that does not hold the ``planner``
  role files it into the backlog: the tracker forces the stage, priority and
  workstream, and the task's provenance is the filing job, taken from its
  token.
* An agent job whose workstream holds the ``planner`` role may set every field
  of a new task, edit any task in its workspace, and create releases.
* Any other agent job may edit only the tasks linked to its own workstream —
  the task its branch was given — and may not move them out of its hands.
* A caller with no job binding (a person) is limited only by its workspaces.
  Deleting, and changing projects and releases, are left to people: those
  tools are not granted to agents (``McpConfigBuilder``), and refuse an
  agent job's token here too.
"""

from typing import Optional
from urllib.parse import urlencode

import server
from server import mcp


# The source prefix of a task filed by an agent job. The full source is taken
# from the calling job's token, never from input: "follow-up:<workstream>/<job>".
# Duplicated from the tracker store (tools/tracker/store.py) across the service
# boundary.
FOLLOW_UP_SOURCE_PREFIX = "follow-up:"

# The source of a task a person wrote. An agent may never claim it.
_PERSON_SOURCE = "person"

# Length ceilings for caller-supplied task text, checked with
# server._check_length before anything is written.
_TITLE_MAX = 500
_DESCRIPTION_MAX = 10_000

# The priority sentinel meaning "leave unchanged" on update; zero is a real
# priority (medium), so it cannot be the sentinel.
_PRIORITY_UNCHANGED = -999


def _caller_job() -> tuple:
    """Return the ``(workstream_id, job_id)`` the request's token is bound to,
    or ``(None, None)`` for a caller that is not an agent job."""
    return server._get_token_workstream_id(), server._get_token_job_id()


def _require_person(tool: str) -> None:
    """Raise :class:`PermissionError` when an agent job calls a tool left to
    people.

    The Claude Code harness never offers these tools to an agent, but a harness
    that filters by server rather than by tool would, so the refusal is made
    here as well.
    """
    if server._get_token_workstream_id():
        raise PermissionError(
            f"{tool} is left to people: agent jobs may read tasks, create tasks and"
            " edit the ones they hold, but not delete them or reorganise projects"
            " and releases.")


def _job_provenance(workstream_id: str, job_id: str) -> str:
    """Return the provenance source of a task filed by the given job."""
    return f"{FOLLOW_UP_SOURCE_PREFIX}{workstream_id}/{job_id}"


def _with_workspace_limit(params: dict) -> dict:
    """Add the caller's workspace limit to a tracker list query, if it has one."""
    limit = server._tracker_workspace_limit()
    if limit is not None:
        params["workspace_id"] = limit
    return params


def _query(path: str, params: dict) -> str:
    """Return *path* with *params* as its query string, if there are any."""
    return path + ("?" + urlencode(params) if params else "")


def _fetch(path: str, key: str, kind: str):
    """Fetch one tracker record and check the caller may see it.

    Returns:
        A ``(record, None)`` tuple, or ``(None, error_response)`` when the
        tracker could not return it.

    Raises:
        PermissionError: the record belongs to a workspace the caller may not
            use.
    """
    result = server._tracker_get(path)
    if not result.get("ok"):
        return None, result
    record = result.get(key) or {}
    server._require_tracker_record_in_scope(record, kind)
    return record, None


def _require_in_scope(path: str, key: str, kind: str) -> Optional[dict]:
    """Check a record the caller names is in one of its workspaces.

    A caller not limited to a workspace may name any record, so nothing is
    fetched for it; the write that follows reports a record that does not
    exist.

    Returns:
        None when the record may be used, or the tracker's error response.

    Raises:
        PermissionError: the record belongs to another workspace.
    """
    if not server._get_workspace_scopes():
        return None
    _, error = _fetch(path, key, kind)
    return error


def _require_project_in_scope(project_id: str) -> Optional[dict]:
    """Check a project the caller names is in one of its workspaces; see
    :func:`_require_in_scope`."""
    return _require_in_scope(f"/v1/projects/{project_id}", "project", "project")


def _require_release_in_scope(release_id: str) -> Optional[dict]:
    """Check a release the caller names is in one of its workspaces; see
    :func:`_require_in_scope`."""
    return _require_in_scope(f"/v1/releases/{release_id}", "release", "release")


def _require_assignable_workspace(workspace_id: str) -> None:
    """Raise :class:`PermissionError` unless a limited caller is assigning a
    project to one of its own workspaces. An unlimited caller may assign any,
    and no value (or "null") assigns none."""
    if workspace_id and workspace_id != "null":
        server._require_workspace(workspace_id)


def _id_list(value: str) -> list:
    """Split a comma-separated list of ids, dropping blanks."""
    return [v.strip() for v in value.split(",") if v.strip()]


def _planner_only_fields(fields: dict) -> list:
    """Return the names of the fields in *fields* that were given a value."""
    return [name for name, given in fields.items() if given]


def _text_error(title: str, description: str) -> Optional[dict]:
    """Return an error response when task text is too long, or None."""
    return (server._check_length(title, "title", _TITLE_MAX)
            or server._check_length(description, "description", _DESCRIPTION_MAX))


# ---------------------------------------------------------------------------
# Projects
# ---------------------------------------------------------------------------

@mcp.tool()
def tracker_list_projects() -> dict:
    """List the tracker projects of your workspace.

    Each project carries the ``workspace_id`` that owns it; its releases and
    tasks belong to that workspace.

    Returns:
        dict with ok=True and a list of projects.
    """
    server._require_scope("read")
    server._audit("tracker_list_projects")
    return server._tracker_get(_query("/v1/projects", _with_workspace_limit({})))


@mcp.tool()
def tracker_create_project(name: str, workspace_id: str = "") -> dict:
    """Create a new tracker project.

    Args:
        name: Human-readable project name (e.g., "Renderer").
        workspace_id: Workspace that owns the project. Its agents see and work
            on the project's releases and tasks; agents of any other workspace
            never see them. Omit to leave the project owned by no workspace,
            which only callers not limited to a workspace can see.

    Returns:
        dict with ok=True and the created project record.
    """
    server._require_scope("write")
    _require_person("tracker_create_project")
    _require_assignable_workspace(workspace_id)
    server._audit("tracker_create_project", name=name, workspace_id=workspace_id)
    payload: dict = {"name": name}
    if workspace_id:
        payload["workspace_id"] = workspace_id
    return server._tracker_post("/v1/projects", payload)


@mcp.tool()
def tracker_update_project(project_id: str, name: str = "", workspace_id: str = "") -> dict:
    """Update an existing tracker project.

    Only fields with non-empty values are updated.

    Args:
        project_id: UUID of the project to update.
        name: New name for the project. Omit to leave unchanged.
        workspace_id: Workspace that owns the project from now on, which moves
            all its releases and tasks with it. Omit to leave unchanged; pass
            "null" to leave the project owned by no workspace.

    Returns:
        dict with ok=True and the updated project record.
    """
    server._require_scope("write")
    _require_person("tracker_update_project")
    error = _require_project_in_scope(project_id)
    if error:
        return error
    _require_assignable_workspace(workspace_id)
    server._audit("tracker_update_project", project_id=project_id,
                  workspace_id=workspace_id)
    payload: dict = {}
    if name:
        payload["name"] = name
    if workspace_id == "null":
        payload["workspace_id"] = None
    elif workspace_id:
        payload["workspace_id"] = workspace_id
    return server._tracker_put(f"/v1/projects/{project_id}", payload)


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
    _require_person("tracker_delete_project")
    error = _require_project_in_scope(project_id)
    if error:
        return error
    server._audit("tracker_delete_project", project_id=project_id)
    return server._tracker_delete(f"/v1/projects/{project_id}")


@mcp.tool()
def tracker_project_summary(project_id: str) -> dict:
    """Return aggregate task counts for a tracker project in one call.

    Use this to answer "what's the shape of project X?" without fetching
    all task rows. Returns counts grouped by status, priority, release, and
    workstream. This is cheaper than calling tracker_list_tasks when you
    only need summary metrics.

    Args:
        project_id: UUID of the project to summarise. It must belong to your
            workspace.

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
    error = _require_project_in_scope(project_id)
    if error:
        return error
    return server._tracker_get(f"/v1/projects/{project_id}/summary")


# ---------------------------------------------------------------------------
# Releases
# ---------------------------------------------------------------------------

@mcp.tool()
def tracker_list_releases(project_id: str = "") -> dict:
    """List the tracker releases of your workspace, optionally of one project.

    Args:
        project_id: Optional UUID to filter releases to a specific project.
            Omit to list all releases.

    Returns:
        dict with ok=True and a list of releases.
    """
    server._require_scope("read")
    server._audit("tracker_list_releases", project_id=project_id)
    params: dict = {}
    if project_id:
        params["project_id"] = project_id
    return server._tracker_get(_query("/v1/releases", _with_workspace_limit(params)))


@mcp.tool()
def tracker_create_release(name: str, project_id: str = "") -> dict:
    """Create a new tracker release.

    An agent job may create a release only when its workstream holds the
    ``planner`` role.

    Args:
        name: Release name, ``<Project> <version>`` (e.g., "Renderer 0.38").
        project_id: UUID of the project the release belongs to. Required
            unless you are a person not limited to a workspace: a release with
            no project belongs to no workspace.

    Returns:
        dict with ok=True and the created release record.
    """
    server._require_scope("write")
    workstream_id, _ = _caller_job()
    if workstream_id and not server._is_tracker_planner(workstream_id):
        raise PermissionError(
            "Creating a release requires the 'planner' tracker role, which workstream '"
            + workstream_id + "' does not have. Operators grant it with"
            " workstream_update_config(..., tracker_capabilities=\"planner\").")
    if project_id:
        error = _require_project_in_scope(project_id)
        if error:
            return error
    elif server._get_workspace_scopes():
        return {"ok": False, "error": "project_id is required: a release belongs to "
                                      "the workspace of its project"}
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
    _require_person("tracker_update_release")
    error = _require_release_in_scope(release_id)
    if error:
        return error
    if project_id and project_id != "null":
        error = _require_project_in_scope(project_id)
        if error:
            return error
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
    _require_person("tracker_delete_release")
    error = _require_release_in_scope(release_id)
    if error:
        return error
    server._audit("tracker_delete_release", release_id=release_id)
    return server._tracker_delete(f"/v1/releases/{release_id}")


# ---------------------------------------------------------------------------
# Tasks
# ---------------------------------------------------------------------------

@mcp.tool()
def tracker_create_task(
    title: str,
    description: str = "",
    project_id: str = "",
    release_id: str = "",
    workstream_id: str = "",
    status: str = "open",
    priority: int = 0,
    stage: str = "",
    source: str = "",
    blocked_by: str = "",
) -> dict:
    """Create a new tracker task.

    Anyone may create a task, and you should: when you notice work worth doing
    that is outside what you were asked to do — an improvement, an edge case, a
    refactor — file it here instead of doing it on your branch.

    If you are an agent whose workstream does not hold the ``planner`` role,
    give only the title, description, project and (optionally) release. The
    task goes into the project's backlog, unclaimed and at medium priority, for
    a person to triage, and its provenance is your job. A job may file a
    generous but limited number of tasks.

    A ``planner`` may also set the stage, priority, provenance, workstream
    link and blockers.

    Args:
        title: Short, descriptive task title.
        description: Longer description, in Markdown: what the work is, why it
            matters, and how to tell it is done.
        project_id: UUID of the project the task belongs to (see
            tracker_list_projects). Required for an agent: a task belongs to
            the workspace of its project.
        release_id: Optional UUID of the target release.
        workstream_id: Planner only. FlowTree workstream ID to link the task
            to. A linked task is claimed: its branch's outcome closes it.
        status: Planner only. "open" (default) or "closed".
        priority: Planner only. Integer in [-2, 2]; 0 (medium) is the default.
        stage: Planner only. "backlog" (the default), "ready" (may be claimed
            by a planning round) or "declined".
        source: Planner only. Provenance, e.g. ``goals:<owner>/<repo>/<doc>``
            for a task derived from goal documents. Defaults to your job.
        blocked_by: Planner only. Comma-separated ids of tasks that must close
            before this one can be claimed.

    Returns:
        dict with ok=True and the created ``task`` (its id is ``task.id``).
    """
    server._require_scope("write")
    caller_ws, caller_job = _caller_job()
    error = _text_error(title, description)
    if error:
        return error
    if not title.strip():
        return {"ok": False, "error": "title is required"}
    if project_id:
        error = _require_project_in_scope(project_id)
        if error:
            return error
    elif server._get_workspace_scopes() or caller_ws:
        return {"ok": False, "error": "project_id is required: a task belongs to the "
                                      "workspace of its project (see tracker_list_projects)"}
    if workstream_id:
        server._require_workstream_in_scope(workstream_id)
    server._audit("tracker_create_task", project_id=project_id,
                  workstream_id=workstream_id, caller_workstream_id=caller_ws)

    if caller_ws and not server._is_tracker_planner(caller_ws):
        refused = _planner_only_fields({
            "workstream_id": workstream_id, "status": status != "open",
            "priority": priority != 0, "stage": stage, "source": source,
            "blocked_by": blocked_by})
        if refused:
            return {"ok": False, "error": (
                f"Only a workstream with the 'planner' tracker role may set {refused}. "
                "Give the title, description, project and release; the task goes into "
                "the backlog for a person to triage.")}
        if not caller_job:
            return {"ok": False, "error": "The request's token names no job to record "
                                          "as the task's provenance."}
        payload = {"project_id": project_id, "title": title.strip(),
                   "source": _job_provenance(caller_ws, caller_job),
                   "description": description or None}
        if release_id:
            payload["release_id"] = release_id
        return server._tracker_post("/v1/follow-up-tasks", payload)

    if caller_ws and source.strip() == _PERSON_SOURCE:
        return {"ok": False, "error": "An agent cannot record a task as written by a person."}
    payload: dict = {"title": title, "status": status, "priority": priority}
    for field, value in (("description", description), ("project_id", project_id),
                         ("release_id", release_id), ("workstream_id", workstream_id),
                         ("stage", stage)):
        if value:
            payload[field] = value
    if source.strip():
        payload["source"] = source.strip()
    elif caller_ws and caller_job:
        payload["source"] = _job_provenance(caller_ws, caller_job)
    if blocked_by:
        payload["blocked_by"] = _id_list(blocked_by)
    return server._tracker_post("/v1/tasks", payload)


@mcp.tool()
def tracker_get_task(task_id: str) -> dict:
    """Get a single tracker task by ID.

    The task must belong to your workspace (that is, to a project your
    workspace owns).

    Args:
        task_id: UUID of the task to retrieve.

    Returns:
        dict with ok=True and the task record.
    """
    server._require_scope("read")
    server._audit("tracker_get_task", task_id=task_id)
    result = server._tracker_get(f"/v1/tasks/{task_id}")
    if result.get("ok"):
        server._require_tracker_record_in_scope(result.get("task") or {}, "task")
    return result


@mcp.tool()
def tracker_list_tasks(
    project_id: str = "",
    release_id: str = "",
    workstream_id: str = "",
    status: str = "",
    stage: str = "",
    sort: str = "",
    order: str = "",
    limit: int = 50,
    offset: int = 0,
    fields: str = "full",
) -> dict:
    """List the tracker tasks of your workspace, with optional filtering.

    When scanning a large backlog (200+ tasks), pass fields="headlines" to
    receive only the compact task projection (no description). This is
    significantly cheaper for callers that only need to triage or count
    tasks. Use tracker_get_task to fetch the full record for a specific task
    by ID.

    Args:
        project_id: Filter to tasks in this project (UUID).
        release_id: Filter to tasks in this release (UUID).
        workstream_id: Filter to tasks linked to this workstream ID.
        status: Filter by status: "open" or "closed". Omit for all.
        stage: Filter by stage: "backlog", "ready" or "declined". Omit for all.
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
    server._audit("tracker_list_tasks", project_id=project_id,
                  release_id=release_id, workstream_id=workstream_id)
    params: dict = {}
    for key, value in (("project_id", project_id), ("release_id", release_id),
                       ("workstream_id", workstream_id), ("status", status),
                       ("stage", stage), ("sort", sort), ("order", order)):
        if value:
            params[key] = value
    if limit != 50:
        params["limit"] = limit
    if offset:
        params["offset"] = offset
    if fields and fields != "full":
        params["fields"] = fields
    return server._tracker_get(_query("/v1/tasks", _with_workspace_limit(params)))


@mcp.tool()
def tracker_update_task(
    task_id: str,
    title: str = "",
    description: str = "",
    status: str = "",
    priority: int = _PRIORITY_UNCHANGED,
    project_id: str = "",
    release_id: str = "",
    workstream_id: str = "",
    stage: str = "",
    source: str = "",
    blocked_by: str = "",
) -> dict:
    """Update an existing tracker task.

    Only fields with non-empty values are updated. Pass an empty string
    to leave a field unchanged; to clear an optional field (e.g., remove the
    release association), pass the literal string "null".

    An agent whose workstream holds the ``planner`` role may edit any task in
    its workspace. Any other agent may edit only the tasks linked to its own
    workstream — the task its branch was given — and may not change their
    project, workstream link or provenance.

    Args:
        task_id: UUID of the task to update.
        title: New title. Omit to leave unchanged.
        description: New description. Omit to leave unchanged.
        status: New status: "open" or "closed". Omit to leave unchanged.
        priority: New priority in the range [-2, 2]. Defaults to the
            sentinel value -999 meaning "leave unchanged" (zero is a
            valid priority — Medium — and cannot be used as the
            sentinel).
        project_id: New project UUID. Omit to leave unchanged.
        release_id: New release UUID. Omit to leave unchanged. Pass
            "null" to clear.
        workstream_id: New workstream ID. Omit to leave unchanged. Pass
            "null" to clear.
        stage: New readiness stage: "backlog", "ready" (a planning round
            may claim it) or "declined". Omit to leave unchanged. A task
            is claimed only when it is also open, unlinked to a
            workstream, unblocked, and in the release being planned.
        source: New provenance, e.g. ``goals:<owner>/<repo>/<doc>``. Omit to
            leave unchanged.
        blocked_by: Comma-separated ids of the tasks that must close before
            this one can be claimed, replacing the current set. Pass "null"
            to clear.

    Returns:
        dict with ok=True and the updated task record.
    """
    server._require_scope("write")
    task, error = _fetch(f"/v1/tasks/{task_id}", "task", "task")
    if error:
        return error
    error = _text_error(title, description)
    if error:
        return error
    caller_ws, _ = _caller_job()
    if caller_ws and not server._is_tracker_planner(caller_ws):
        if task.get("workstream_id") != caller_ws:
            raise PermissionError(
                "Without the 'planner' tracker role an agent may edit only the tasks "
                "linked to its own workstream, and this task is not linked to '"
                + caller_ws + "'. File new work with tracker_create_task instead.")
        refused = _planner_only_fields({"project_id": project_id,
                                        "workstream_id": workstream_id, "source": source})
        if refused:
            return {"ok": False, "error": (
                f"Only a workstream with the 'planner' tracker role may change {refused}.")}
    if caller_ws and source.strip() == _PERSON_SOURCE:
        return {"ok": False, "error": "An agent cannot record a task as written by a person."}
    if project_id == "null" and server._get_workspace_scopes():
        return {"ok": False, "error": "A task cannot be taken out of every project: it "
                                      "would leave your workspace."}
    if project_id and project_id != "null":
        error = _require_project_in_scope(project_id)
        if error:
            return error
    if workstream_id and workstream_id != "null":
        server._require_workstream_in_scope(workstream_id)
    server._audit("tracker_update_task", task_id=task_id, caller_workstream_id=caller_ws)
    payload: dict = {}
    for field, value in (("title", title), ("description", description),
                         ("status", status), ("stage", stage)):
        if value:
            payload[field] = value
    if priority != _PRIORITY_UNCHANGED:
        payload["priority"] = priority
    if source.strip():
        payload["source"] = source.strip()
    for field, value in (("project_id", project_id), ("release_id", release_id),
                         ("workstream_id", workstream_id)):
        if value == "null":
            payload[field] = None
        elif value:
            payload[field] = value
    if blocked_by == "null":
        payload["blocked_by"] = []
    elif blocked_by:
        payload["blocked_by"] = _id_list(blocked_by)
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
    _require_person("tracker_delete_task")
    _, error = _fetch(f"/v1/tasks/{task_id}", "task", "task")
    if error:
        return error
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
    """Full-text search over the titles and descriptions of your workspace's tasks.

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
    params: dict = {"q": query}
    if project_id:
        params["project_id"] = project_id
    if status:
        params["status"] = status
    if limit != 20:
        params["limit"] = limit
    if offset:
        params["offset"] = offset
    if fields and fields != "full":
        params["fields"] = fields
    return server._tracker_get(_query("/v1/search/tasks", _with_workspace_limit(params)))
