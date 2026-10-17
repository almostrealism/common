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

_workspace_map_cache: dict = {
    "map": None, "org_map": None, "fetched": 0.0, "fetch_ok_ever": False,
}
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


def _refresh_workspace_map() -> Optional[tuple]:
    """Fetch the workstream list from the controller and return fresh
    ``(workstream_id → workspace_id, org_name → set(workspace_ids))`` maps.

    Returns ``None`` when the controller could not be queried successfully —
    an unreachable controller or an HTTP/parse error surfaces from
    :func:`~controller_client._controller_get` as an ``{"ok": False, ...}``
    dict, which carries no ``"workstreams"`` list. This is distinct from a
    successful fetch that legitimately returns zero workstreams (a plain
    list, or a dict with an empty ``"workstreams"`` list). Callers must not
    treat the two the same: a fetch failure means "unknown state," not
    "no workspaces exist," and must not overwrite a previously-good cache
    with an empty one (see :func:`_get_cached_maps`).

    Archived workstreams are included (``includeArchived=true``). The map
    answers "which workspace owns this workstream," and an archived
    workstream still belongs to its workspace. Resolving ownership for
    archived rows is what lets the read tools (``workstream_context``,
    ``workstream_get_job``, ``workstream_get_status``) see an archived
    round at all; whether an *operation* is permitted on an archived
    workstream is a separate question answered by the write tools, which
    refuse it (see :func:`_archived_workstream_error`). The dispatch-capable
    map is built from a live-only fetch on purpose — dispatch is a property
    of a workstream that can still accept work.
    """
    import sys as _sys
    _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
    result = _cget("/api/workstreams?includeArchived=true")
    if isinstance(result, list):
        entries = result
    elif isinstance(result, dict) and isinstance(result.get("workstreams"), list):
        entries = result["workstreams"]
    else:
        return None
    return _build_maps_from_workstreams(entries)


def _get_cached_maps(workstream_id: str = "", org: str = "") -> tuple:
    """Return ``(workstream_map, org_map)`` from the cache, refreshing if
    the cache is older than :data:`~config.WORKSPACE_CACHE_TTL` or if an
    expected key is missing.

    The lock is held only around cache reads and writes; the network fetch
    happens outside the lock so concurrent callers do not serialize behind a
    single slow I/O (double-checked-locking pattern).

    When the refresh fails (controller unreachable or error response), the
    previously-cached maps are returned unchanged instead of being clobbered
    by an empty result — a fetch failure must never look identical to "the
    controller confirmed there are no workspaces." If there is no prior
    cache at all (cold start with an immediate failure), empty maps are
    returned so callers get plain dicts rather than ``None``;
    :func:`_is_multi_workspace_mode` consults
    :func:`_has_ever_fetched_workspace_map` separately to fail closed in
    that cold-start case.
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
    refreshed = _refresh_workspace_map()
    if refreshed is None:
        return (ws_map if ws_map is not None else {},
                org_map if org_map is not None else {})
    new_ws_map, new_org_map = refreshed
    new_fetched = time.monotonic()
    with _workspace_map_lock:
        _workspace_map_cache["map"] = new_ws_map
        _workspace_map_cache["org_map"] = new_org_map
        _workspace_map_cache["fetched"] = new_fetched
        _workspace_map_cache["fetch_ok_ever"] = True
    return new_ws_map, new_org_map


def _has_ever_fetched_workspace_map() -> bool:
    """Return ``True`` once ``/api/workstreams`` has been queried
    successfully at least once (even if it returned zero workstreams).

    Used by :func:`_is_multi_workspace_mode` to distinguish "we confirmed
    there are no workspace-bound workstreams" (an accurate empty state,
    safe to read as single-workspace/legacy mode) from "we have never
    successfully reached the controller" (an unknown state that must fail
    closed rather than default to the permissive legacy reading).
    """
    with _workspace_map_lock:
        return bool(_workspace_map_cache.get("fetch_ok_ever"))


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

    Fails closed: if the controller has never been queried successfully
    (unreachable since startup, or every attempt so far has errored), this
    returns ``True`` even though the cached map is empty, so that a
    temp-token request whose workstream cannot be resolved is rejected
    rather than silently treated as unscoped. Once a real fetch succeeds —
    even one that legitimately finds zero workspace-bound workstreams — the
    accurate empty-map reading (``False``, legacy single-workspace mode)
    takes over.
    """
    ws_map, _ = _get_cached_maps()
    if not _has_ever_fetched_workspace_map():
        return True
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


def _fetch_workstream_entries() -> list:
    """Fetch the controller's workstream list for a capability check.

    Returns an empty list when the controller is unreachable or returns an
    unexpected payload. Every capability built on it therefore fails closed:
    an ar-manager that cannot reach its controller grants nothing.
    """
    try:
        import sys as _sys
        _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
        result = _cget("/api/workstreams")
    except Exception:
        return []
    if isinstance(result, list):
        entries = result
    elif isinstance(result, dict):
        entries = result.get("workstreams", [])
    else:
        return []
    return entries if isinstance(entries, list) else []


def _refresh_dispatch_capable_ids() -> set:
    """Fetch the workstream list from the controller and return the set
    of workstream IDs that have ``dispatchCapable: true``.

    Returns an empty set when the controller is unreachable or returns a
    non-list payload.  The empty set is the fail-closed default: an
    ar-manager that cannot reach its controller refuses to grant dispatch.
    """
    ids = set()
    for ws in _fetch_workstream_entries():
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
        " dispatch_capable=True). Call workstream_introspect to see"
        " which grants are missing — the controller flag and the"
        " harness allowlist are separate, and a denial can come from"
        " either."
    )


# ---------------------------------------------------------------------------
# Tracker capabilities
# ---------------------------------------------------------------------------
# The controller's workstream list carries ``"trackerCapabilities": [...]``
# for a workstream granted any. Each capability unlocks a narrow tracker
# tool: ``planner`` may claim the next ready task of a release, ``steward``
# may list a release and create or update goal-derived tasks. Cached on the
# same TTL as the dispatch flag, and failing closed the same way.

TRACKER_PLANNER = "planner"
TRACKER_STEWARD = "steward"

_tracker_capability_cache: dict = {"map": None, "fetched": 0.0}
_tracker_capability_lock = threading.Lock()


def _refresh_tracker_capabilities() -> dict:
    """Return ``{workstream_id: set(capabilities)}`` from the controller."""
    caps = {}
    for ws in _fetch_workstream_entries():
        if not isinstance(ws, dict):
            continue
        granted = ws.get("trackerCapabilities")
        wid = ws.get("workstreamId")
        if wid and isinstance(granted, list):
            caps[wid] = {c for c in granted if isinstance(c, str)}
    return caps


def _get_tracker_capabilities(workstream_id: str) -> set:
    """Return the tracker capabilities granted to *workstream_id*, refreshing
    the cache when it is older than :data:`~config.WORKSPACE_CACHE_TTL`.
    """
    now = time.monotonic()
    with _tracker_capability_lock:
        caps = _tracker_capability_cache.get("map")
        fresh = (caps is not None
                 and (now - _tracker_capability_cache.get("fetched", 0.0))
                 <= WORKSPACE_CACHE_TTL)
    if not fresh:
        caps = _refresh_tracker_capabilities()
        with _tracker_capability_lock:
            _tracker_capability_cache["map"] = caps
            _tracker_capability_cache["fetched"] = time.monotonic()
    return caps.get(workstream_id, set())


def _require_tracker_capability(capability: str) -> str:
    """Return the calling job's workstream ID, or raise
    :class:`PermissionError` unless that workstream holds *capability*.

    Unlike the dispatch gate, an unscoped caller is not waved through: every
    tool behind this gate acts on behalf of a workstream (a claim links the
    task to it), so a caller must be a job with a workstream binding.
    """
    caller_ws_id = _get_token_workstream_id()
    if not caller_ws_id:
        raise PermissionError(
            "This tracker tool acts for the calling job's workstream, and the"
            " request carries no workstream binding. Call it from an agent job.")
    if capability in _get_tracker_capabilities(caller_ws_id):
        return caller_ws_id
    raise PermissionError(
        "This tracker tool requires the '" + capability + "' tracker capability,"
        " which workstream '" + caller_ws_id + "' does not have. Operators grant"
        " it with workstream_update_config(..., tracker_capabilities=\"planner,steward\").")


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


# Source prefix of a follow-up task a coding agent filed while working a branch.
# It carries no workstream link, so its provenance source
# ("follow-up:<workstream>/<job>") is the only record of which workstream filed
# it. A scoped caller may read back the follow-up tasks its own workspace filed,
# identified by the workstream embedded here. Duplicated from the tracker store
# (tools/tracker/store.py FOLLOW_UP_SOURCE_PREFIX) across the service boundary,
# as GOAL_SOURCE_PREFIX is duplicated in tracker_tools.py.
FOLLOW_UP_SOURCE_PREFIX = "follow-up:"


def _follow_up_task_workspace_allowed(task: dict) -> bool:
    """Return True when *task* is a follow-up task whose filing workstream is in
    the current request's workspace scope.

    A follow-up task has no ``workstream_id`` (filing one would make it a
    claimed task closed when the branch merges), so the workstream that filed it
    is read from its provenance ``source``. Any task that is not a follow-up, or
    whose embedded workstream is out of scope, returns False.
    """
    if not isinstance(task, dict):
        return False
    source = task.get("source") or ""
    if not isinstance(source, str) or not source.startswith(FOLLOW_UP_SOURCE_PREFIX):
        return False
    rest = source[len(FOLLOW_UP_SOURCE_PREFIX):]
    ws_id = rest.split("/", 1)[0]
    if not ws_id:
        return False
    return _is_workspace_allowed(_workspace_for_workstream(ws_id))


def _filter_tasks_by_scope(tasks: list) -> list:
    """Return only those task-dict entries whose linked workstream is
    permitted by the current request's workspace scope.

    Unscoped callers see all tasks.  A task with no ``workstream_id`` is
    dropped for scoped callers — there is no safe interpretation of
    "this task belongs to your workspace" when no workstream link exists —
    unless it is a follow-up task its own workspace filed, recognised by the
    workstream embedded in its provenance source. Malformed non-list payloads
    return an empty list for scoped callers.
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
            if _follow_up_task_workspace_allowed(t):
                filtered.append(t)
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

    Archived workstreams are included (``includeArchived=true``) so an
    archived round still resolves — its dict carries ``archived=true``,
    which the write tools read to keep it read-only (see
    :func:`_archived_workstream_error`). Without this, addressing an
    archived workstream by id failed as "not found" for every read tool
    that resolves repo/branch through here.

    Enforces the current request's workspace scope as a side effect: a
    scoped caller looking up a workstream in an out-of-scope workspace
    receives ``None`` (the lookup appears to fail, rather than leaking
    existence or 403'ing from every call site independently).

    Returns:
        The workstream dict, or ``None`` if not found or not in scope.
    """
    import sys as _sys
    _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
    result = _cget("/api/workstreams?includeArchived=true")
    if isinstance(result, list):
        for ws in result:
            if ws.get("workstreamId") == workstream_id:
                if _get_workspace_scopes() and not _is_workspace_allowed(
                        ws.get("workspaceId") or ws.get("slackWorkspaceId")):
                    return None
                return ws
    return None


def _find_workstream_by_branch(repo_url: str, branch: str) -> Optional[dict]:
    """Find a workstream by its repository and default branch.

    Archived workstreams are included, so a caller that addresses a branch
    by ``repo_url`` + ``branch`` (rather than by id) can still resolve an
    archived round — the shape a retrospective survey uses for a merged,
    archived QA round it found through ``workstream_list``. This is what
    lets :func:`workstream_context` return a job timeline for the
    repo+branch form, not only when a ``workstream_id`` was supplied.

    Enforces workspace scope exactly as :func:`_find_workstream` does: a
    match owned by an out-of-scope workspace is skipped, so existence is
    not leaked to a scoped caller.

    Returns:
        The first in-scope workstream dict whose ``defaultBranch`` equals
        *branch* on the given repository, or ``None`` when there is none.
    """
    if not repo_url or not branch:
        return None
    import sys as _sys
    _cget = getattr(_sys.modules.get('server'), '_controller_get', None) or _controller_get
    from urllib.parse import urlencode
    path = "/api/workstreams?" + urlencode(
        {"includeArchived": "true", "repoUrl": repo_url})
    result = _cget(path)
    if isinstance(result, list):
        entries = result
    elif isinstance(result, dict) and isinstance(result.get("workstreams"), list):
        entries = result["workstreams"]
    else:
        return None
    for ws in entries:
        if not isinstance(ws, dict) or ws.get("defaultBranch") != branch:
            continue
        if _get_workspace_scopes() and not _is_workspace_allowed(
                ws.get("workspaceId") or ws.get("slackWorkspaceId")):
            continue
        return ws
    return None


def _archived_workstream_error(operation: str, workstream_id: str) -> Optional[dict]:
    """Return an error dict when *workstream_id* names an archived
    workstream, or ``None`` when it is live, unknown, or out of scope.

    Archived workstreams are read-only: their context, memories and job
    history stay queryable, but no job may be submitted to them and their
    configuration may not be changed. The write tools call this before they
    contact the controller so the refusal names archiving as the reason
    rather than surfacing as a generic controller error. Resolving an
    archived workstream's ownership now succeeds (see
    :func:`_find_workstream`), so this explicit guard — not the former
    accidental scope-resolution failure — is what keeps writes off an
    archived workstream.

    An unknown or out-of-scope workstream resolves to ``None`` here so the
    caller's existing not-found / scope handling still runs; this guard
    speaks only to the archived case. Addressing by ``workstream_id`` is the
    path it protects — a caller that resolves a workstream another way (an
    operator submitting by ``target_branch`` with no id) is left to the
    controller's own archived handling.

    Args:
        operation: What the caller was attempting, phrased to complete
            "Cannot <operation>:" — e.g. ``"submit a task"``.
        workstream_id: The workstream the write was aimed at.

    Returns:
        An ``{"ok": False, ...}`` dict naming archiving as the reason, or
        ``None`` when the workstream is not archived (or not resolvable).
    """
    if not workstream_id:
        return None
    import sys as _sys
    _find = getattr(_sys.modules.get('server'), '_find_workstream', None) or _find_workstream
    ws = _find(workstream_id)
    if not ws or not ws.get("archived"):
        return None
    return {
        "ok": False,
        "error": (
            f"Cannot {operation}: workstream '{workstream_id}' is archived. "
            "Archived workstreams are read-only — their context, memories and "
            "job history remain readable, but no new job may be submitted to "
            "them and their configuration may not be changed."
        ),
        "next_steps": [
            f"Unarchive it first with workstream_unarchive('{workstream_id}') to resume work",
            "Or target a live workstream — workstream_list marks which are archived",
        ],
    }
