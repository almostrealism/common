"""
Workspace-to-workstream resolution, scoped filtering, and dispatch gating.

This module maintains a short-lived in-process cache of the controller's
workstream list and exposes helpers that map workstream IDs to Slack workspace
IDs, check whether the current request's token is scoped to a given workspace,
and decide whether a workstream is dispatch-capable.

Cache design
------------
Both :data:`_workspace_map_cache` and :data:`_dispatch_capable_cache` hold the
last fetch from ``/api/workstreams``.  They refresh when the cached result is
older than :data:`~config.WORKSPACE_CACHE_TTL` seconds or when an expected key
is absent (handles a just-registered workstream).  The network fetch happens
*outside* the lock; only the dict write is protected, so concurrent callers do
not serialize behind a single slow I/O.

Dependency notes
----------------
This module imports from :mod:`auth` at the module level.  :mod:`auth` in turn
imports from this module inside :meth:`BearerAuthMiddleware.__call__` — a
deferred import to break the cycle.  The static import direction at load time
is ``workspace_map → auth``, which is acyclic.

``_extract_owner_repo`` is imported from :mod:`github_api` at module level.
:mod:`github_api` never imports :mod:`workspace_map`, so there is no cycle.

Extracted from server.py to keep individual modules manageable.
"""

import threading
import time
from typing import Optional

from config import WORKSPACE_CACHE_TTL
from controller_client import _controller_get
from auth import (
    _get_workspace_scopes,
    _is_workspace_allowed,
    _require_workspace,
    _get_token_workstream_id,
)
from github_api import _extract_owner_repo

# ---------------------------------------------------------------------------
# Workspace-map cache
# ---------------------------------------------------------------------------
# Short-lived cache of the workstream → workspace mapping. Each entry holds
# the mapping plus its fetch timestamp. Refreshed whenever the last fetch is
# older than WORKSPACE_CACHE_TTL seconds. Controller is local so hitting
# /api/workstreams is cheap, but refetching on every tool invocation adds
# avoidable latency to short read tools.

_workspace_map_cache: dict = {"map": None, "org_map": None, "fetched": 0.0}
_workspace_map_lock = threading.Lock()


def _build_maps_from_workstreams(entries: list) -> tuple:
    """Build ``(workstream_id → workspace_id, org_name → set(workspace_ids))``
    from a workstream list.

    Workstreams whose ``repoUrl`` cannot be parsed as a GitHub URL contribute
    nothing to the org map.  An org may appear in multiple workspaces; the org
    map tracks the full set so ambiguity can be detected by
    :func:`_require_org_in_scope` rather than silently resolved.
    """
    ws_map: dict = {}
    org_map: dict = {}
    if not isinstance(entries, list):
        return ws_map, org_map
    for ws in entries:
        if not isinstance(ws, dict):
            continue
        wid = ws.get("workstreamId")
        # Prefer the new workspaceId field; fall back to the legacy alias
        # so we stay compatible with older controllers that still emit
        # only slackWorkspaceId on the workstream list.
        workspace_id = ws.get("workspaceId") or ws.get("slackWorkspaceId")
        if wid:
            ws_map[wid] = workspace_id
        org = _extract_owner_repo(ws.get("repoUrl") or "")
        if org and workspace_id:
            org_map.setdefault(org[0], set()).add(workspace_id)
    return ws_map, org_map


def _refresh_workspace_map() -> tuple:
    """Fetch the workstream list from the controller and return fresh
    ``(workstream_id → workspace_id, org_name → set(workspace_ids))`` maps.
    """
    import sys as _sys
    _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
    result = _cget("/api/workstreams")
    entries = result if isinstance(result, list) else result.get("workstreams", [])
    return _build_maps_from_workstreams(entries)


def _get_cached_maps(workstream_id: str = "", org: str = "") -> tuple:
    """Return ``(workstream_map, org_map)`` from the cache, refreshing if
    the cache is older than :data:`~config.WORKSPACE_CACHE_TTL` or if an
    expected key is missing.

    The lock is held only around cache reads and writes; the network fetch
    happens outside the lock so concurrent callers do not serialize behind a
    single slow I/O (double-checked-locking pattern).
    """
    now = time.monotonic()
    with _workspace_map_lock:
        ws_map = _workspace_map_cache.get("map")
        org_map = _workspace_map_cache.get("org_map")
        fetched = _workspace_map_cache.get("fetched", 0.0)
        fresh = (ws_map is not None and org_map is not None
                 and (now - fetched) <= WORKSPACE_CACHE_TTL)
    needs_refresh = not fresh
    if fresh:
        if workstream_id and workstream_id not in ws_map:
            needs_refresh = True
        elif org and org not in org_map:
            needs_refresh = True
    if not needs_refresh:
        return ws_map, org_map
    new_ws_map, new_org_map = _refresh_workspace_map()
    new_fetched = time.monotonic()
    with _workspace_map_lock:
        _workspace_map_cache["map"] = new_ws_map
        _workspace_map_cache["org_map"] = new_org_map
        _workspace_map_cache["fetched"] = new_fetched
    return new_ws_map, new_org_map


def _workspace_for_workstream(workstream_id: str) -> Optional[str]:
    """Return the Slack workspace ID that owns *workstream_id*, or ``None``
    if the workstream is unknown or has no workspace assignment.
    """
    if not workstream_id:
        return None
    ws_map, _ = _get_cached_maps(workstream_id=workstream_id)
    return ws_map.get(workstream_id)


def _is_multi_workspace_mode() -> bool:
    """Return ``True`` when the controller is running in multi-workspace mode.

    Multi-workspace mode is detected when at least one registered workstream
    has a non-null ``slackWorkspaceId``.  Used by the temp-token validator to
    decide whether a workstream whose workspace cannot be resolved should be
    rejected (multi-workspace) or accepted as unscoped (legacy single-workspace).
    """
    ws_map, _ = _get_cached_maps()
    return any(v for v in ws_map.values())


def _workspaces_for_org(org: str) -> set:
    """Return the set of Slack workspace IDs that declare at least one
    workstream on the given GitHub org.  Empty when no registered workstream
    ties that org to any workspace.
    """
    if not org:
        return set()
    _, org_map = _get_cached_maps(org=org)
    return set(org_map.get(org, set()))


def _require_org_in_scope(org: str) -> None:
    """Raise :class:`PermissionError` if the current request's workspace
    scope does not permit operating on the given GitHub org.

    Unscoped callers always pass.  Scoped callers are accepted only when
    the org is unambiguously owned by a single workspace in their scope.
    An org that appears under multiple workspaces is denied for scoped
    callers because the controller's per-org PAT is last-wins; pass a
    ``workstream_id`` instead to disambiguate.
    """
    if not _get_workspace_scopes():
        return
    owners = _workspaces_for_org(org)
    if not owners:
        raise PermissionError(
            f"Token is not scoped to any workspace containing GitHub org '{org}'. "
            "Either pass a workstream_id that belongs to your scope, or ask "
            "the operator to link the org to a workspace via workstreams.yaml.")
    if len(owners) > 1:
        raise PermissionError(
            f"GitHub org '{org}' is registered under multiple Slack workspaces "
            f"({sorted(owners)}). Direct-org addressing is ambiguous for scoped "
            "tokens because the controller's per-org PAT is last-wins; pass a "
            "workstream_id instead so the workspace (and therefore the PAT) is "
            "uniquely determined.")
    (only_workspace,) = owners
    _require_workspace(only_workspace)


def _require_workstream_in_scope(workstream_id: str) -> None:
    """Resolve the workspace owning *workstream_id* and raise
    :class:`PermissionError` if the current request's token does not permit
    it.  No-op when the caller's token is unscoped.
    """
    if not _get_workspace_scopes():
        return
    ws_id = _workspace_for_workstream(workstream_id)
    _require_workspace(ws_id)


# ---------------------------------------------------------------------------
# Dispatch-capable cache
# ---------------------------------------------------------------------------
# Short-lived cache of the per-workstream ``dispatchCapable`` flag.  The
# controller's workstream list emits ``"dispatchCapable": true`` only when
# set, so the cached map holds a small set of IDs rather than a full
# {id: bool} map.  TTL matches WORKSPACE_CACHE_TTL so both caches refresh
# on the same cadence — both come from the same /api/workstreams endpoint.

_dispatch_capable_cache: dict = {"ids": None, "fetched": 0.0}
_dispatch_capable_lock = threading.Lock()


def _refresh_dispatch_capable_ids() -> set:
    """Fetch the workstream list from the controller and return the set
    of workstream IDs that have ``dispatchCapable: true``.

    Returns an empty set when the controller is unreachable or returns a
    non-list payload.  The empty set is the fail-closed default: an
    ar-manager that cannot reach its controller refuses to grant dispatch.
    """
    try:
        import sys as _sys
        _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
        result = _cget("/api/workstreams")
    except Exception:
        return set()
    if isinstance(result, list):
        entries = result
    elif isinstance(result, dict):
        entries = result.get("workstreams", [])
    else:
        return set()
    if not isinstance(entries, list):
        return set()
    ids = set()
    for ws in entries:
        if not isinstance(ws, dict):
            continue
        if ws.get("dispatchCapable") is True:
            wid = ws.get("workstreamId")
            if wid:
                ids.add(wid)
    return ids


def _get_dispatch_capable_ids() -> set:
    """Return the cached set of dispatch-capable workstream IDs, refreshing
    if the cache is older than :data:`~config.WORKSPACE_CACHE_TTL`.
    """
    now = time.monotonic()
    with _dispatch_capable_lock:
        ids = _dispatch_capable_cache.get("ids")
        fetched = _dispatch_capable_cache.get("fetched", 0.0)
        fresh = (ids is not None
                 and (now - fetched) <= WORKSPACE_CACHE_TTL)
    if fresh:
        return ids
    new_ids = _refresh_dispatch_capable_ids()
    with _dispatch_capable_lock:
        _dispatch_capable_cache["ids"] = new_ids
        _dispatch_capable_cache["fetched"] = time.monotonic()
    return new_ids


def _require_dispatch_capable() -> None:
    """Raise :class:`PermissionError` when the calling workstream is not
    dispatch-capable.

    Unscoped tokens (superadmin) are always permitted.  Job-scoped HMAC
    tokens are denied when the calling workstream's ``dispatchCapable`` flag
    is ``false``.  Callers with neither identity (admin tokens without a
    workstream binding) are also permitted; the check targets the agent path.

    The fail-closed default means a workstream that just opted in may see up
    to :data:`~config.WORKSPACE_CACHE_TTL` seconds of denial before the cache
    refreshes — a deliberate trade for the guarantee that a broken controller
    cannot silently grant dispatch.
    """
    caller_ws_id = _get_token_workstream_id()
    if not caller_ws_id:
        return
    if caller_ws_id in _get_dispatch_capable_ids():
        return
    raise PermissionError(
        "workstream_register / workstream_update_config require the"
        " calling workstream to be dispatch-capable. The current"
        " workstream ('" + caller_ws_id + "') has dispatchCapable=false"
        " in the controller config. Operators enable this flag per"
        " workstream with workstream_update_config(...,"
        " dispatch_capable=True)."
    )


# ---------------------------------------------------------------------------
# Scope-filtered views over the workstream / task lists
# ---------------------------------------------------------------------------

def _filter_workstreams_by_scope(entries: list) -> list:
    """Return only those workstream-dict entries whose workspaceId is
    permitted by the current request's workspace scope.  Unscoped callers
    see everything; scoped callers see only in-scope workstreams.  Accepts
    the legacy ``slackWorkspaceId`` field for backward compatibility.
    """
    scopes = _get_workspace_scopes()
    if not scopes:
        return entries
    filtered = []
    for ws in entries:
        if isinstance(ws, dict):
            wsid = ws.get("workspaceId") or ws.get("slackWorkspaceId")
            if wsid in scopes:
                filtered.append(ws)
    return filtered


def _filter_tasks_by_scope(tasks: list) -> list:
    """Return only those task-dict entries whose linked workstream is
    permitted by the current request's workspace scope.

    Unscoped callers see all tasks.  Tasks with no ``workstream_id`` are
    dropped for scoped callers — there is no safe interpretation of
    "this task belongs to your workspace" when no workstream link exists.
    Malformed non-list payloads return an empty list for scoped callers.
    """
    if not _get_workspace_scopes():
        return tasks
    if not isinstance(tasks, list):
        return []
    filtered = []
    for t in tasks:
        if not isinstance(t, dict):
            continue
        ws_id = t.get("workstream_id") or ""
        if not ws_id:
            continue
        if _is_workspace_allowed(_workspace_for_workstream(ws_id)):
            filtered.append(t)
    return filtered


# ---------------------------------------------------------------------------
# Workstream lookup and capability helpers
# ---------------------------------------------------------------------------

def _pipeline_error(workstream_id: str, missing: str) -> dict:
    """Return a structured error for Tier 2 tools called on
    non-pipeline-capable workstreams.
    """
    return {
        "ok": False,
        "error": f"Workstream '{workstream_id}' does not support pipeline operations",
        "reason": f"Missing: {missing}",
        "suggestion": (
            "Use workstream_update_config to set the missing field, "
            "then use workstream_list to confirm pipeline_capable=true"
        ),
        "next_steps": [
            f"Call workstream_update_config with {missing}",
            "Then call workstream_list to confirm pipeline_capable=true",
        ],
    }


def _find_workstream(workstream_id: str) -> Optional[dict]:
    """Fetch a specific workstream from the controller's list.

    Enforces the current request's workspace scope as a side effect: a
    scoped caller looking up a workstream in an out-of-scope workspace
    receives ``None`` (the lookup appears to fail, rather than leaking
    existence or 403'ing from every call site independently).

    Returns:
        The workstream dict, or ``None`` if not found or not in scope.
    """
    import sys as _sys
    _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
    result = _cget("/api/workstreams")
    if isinstance(result, list):
        for ws in result:
            if ws.get("workstreamId") == workstream_id:
                if _get_workspace_scopes() and not _is_workspace_allowed(
                        ws.get("workspaceId") or ws.get("slackWorkspaceId")):
                    return None
                return ws
    return None
