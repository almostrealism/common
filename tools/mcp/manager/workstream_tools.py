"""
Workstream tools for the AR Manager MCP server.

The largest of the tool groups split out of ``server.py``. The tools are
unchanged; only their address is. See ``tracker_tools`` for the conventions —
the ``_tools`` suffix that makes the module visible to tool discovery, and
reaching anything defined in ``server`` through the module rather than by
import, so the suite's several hundred patches still apply.

Helpers and constants stay in ``server.py``. ``_archive_many`` in particular
calls back into ``workstream_archive`` and ``workstream_unarchive``, which are
re-exported into that module, so the cycle resolves at call time rather than
at import.
"""

import logging
from typing import Optional
from urllib.parse import quote, urlencode

import github_api
import repo_config
import server
from memory_text import prefers_reformulated, present
from server import mcp


# Silence after which an active job is worth flagging to the operator. Well
# below the stuck-job scanner's termination threshold on purpose: the point is
# to surface a stall while someone can still look at it, not to duplicate the
# scanner's verdict.
_STALLED_WARNING_SECONDS = 6 * 3600


@mcp.tool()
def workstream_list(
    include_archived: bool = False,
    workspace_id: str = "",
    repo_url: str = "",
    dispatch_capable: Optional[bool] = None,
    archived: Optional[bool] = None,
    include_status: bool = False,
    include_pull_request: bool = False,
    include_pull_request_state: bool = False,
    include_lifecycle: bool = False,
    idle_days: int = 14,
    lifecycle: str = "",
) -> dict:
    """List all registered workstreams with their configuration and capabilities.

    Each workstream entry includes:
    - workstreamId: unique identifier for API calls
    - channelName: associated Slack channel (if any)
    - defaultBranch: the git branch agents commit to
    - baseBranch: the base branch for new branch creation
    - repoUrl: the git repository URL
    - hasPlanningDocument: whether a plan doc is configured
    - pipelineCapable: whether Tier 2 pipeline tools will work
    - dependentRepos: list of additional repo URLs cloned alongside the
      primary repo (omitted if none configured)
    - archived: ``true`` when the workstream has been archived (only
      present when ``include_archived=True``; otherwise such entries
      are filtered out entirely)
    - kind: ``"feature"`` (default), ``"orchestrator"``, or
      ``"standing"``. Omitted when the workstream is a feature
      workstream so the common case adds no noise.

    Use this to discover workstreams and determine which tools are
    available for each one.

    The filters are applied by the controller, so a question like "which
    workstreams on this repository have dispatch enabled?" is one call rather
    than a listing plus a client-side scan.

    Args:
        include_archived: When ``False`` (default) archived workstreams
            are omitted from the response. Set ``True`` to include them;
            each archived entry carries ``archived=true``.
        workspace_id: Only workstreams in this workspace. Distinct from the
            scope filter the caller's token already imposes — this is an
            explicit narrowing a multi-workspace caller can ask for.
        repo_url: Only workstreams on this repository. Matched on the
            repository identity, so the ``git@``/``https``/``.git`` spellings
            of one repository are equivalent.
        dispatch_capable: Only workstreams whose dispatch flag matches.
        archived: Explicit archived selector — ``True`` for archived only,
            ``False`` for live only. Supersedes ``include_archived`` when
            given; that parameter is the older, coarser form.
        include_status: When ``True``, each entry also carries ``lastJobId``,
            ``lastJobStatus`` (``SUCCESS`` / ``FAILED`` / ``CANCELLED`` /
            ``DEGRADED`` / ``STARTED``), ``lastJobAt`` (ISO-8601) plus the
            additional ``lastJobStartedAt`` and ``lastJobFinishedAt``
            fields. ``lastJobAt`` and the two new fields all come from
            the persisted ``job_timing`` row rather than the
            controller's read-time instant, so two listings issued
            seconds apart return byte-identical values. Off by default
            because it costs one job-history read per workstream returned;
            narrow with the filters above before turning it on.
        include_pull_request: When ``True``, each entry also carries
            ``pullRequest`` as ``{"url": ..., "number": ...}``, read from
            the most recent job that recorded one. Absent when no recent
            job did. Same per-workstream cost as ``include_status``, and
            served from the same read when both are requested.
        include_pull_request_state: When ``True``, each entry also carries
            ``pullRequestState`` (the GitHub-derived state for the branch's
            most recently updated PR — ``open`` / ``closed`` / ``merged``
            plus ``mergedAt`` / ``closedAt`` / ``number`` / ``url``) and
            ``prCount`` (the total number of PRs the branch has had across
            all states). The lookup is coalesced by repository so a
            multi-row list that lives on two repositories issues two
            GitHub calls, not one per workstream. Cached for 60 s by the
            controller. Off by default because the GitHub call is the
            first thing a slow listing can pay for.
        include_lifecycle: When ``True``, each entry carries ``lifecycle``
            and ``lifecycleReason``. ``lifecycle`` is one of
            ``"standing"`` / ``"orchestrator"`` / ``"active"`` /
            ``"merged"`` / ``"abandoned"`` / ``"idle"`` / ``"unknown"``.
            Standing and orchestrator workstreams are classified from
            their ``kind`` and never report ``merged``. Requires the same
            GitHub lookup as ``include_pull_request_state``; the cost
            is opt-in.
        idle_days: Window (in days) used by the ``lifecycle`` classifier
            for the idle-window comparisons. Defaults to 14. Only
            consulted when ``include_lifecycle=True``.
        lifecycle: Exact-match filter on the classification. ``"merged"``
            returns only workstreams whose branch has a merged PR and
            no job in the last ``idle_days``; ``"active"`` returns
            workstreams with an open PR; ``"idle"`` returns workstreams
            with no PR and no recent job; ``"standing"`` and
            ``"orchestrator"`` return workstreams with that explicit
            ``kind``. Applied server-side after enrichment. Setting this
            implies ``include_lifecycle=True`` — the classification the
            filter matches against does not exist otherwise.

    Returns:
        Dictionary with list of workstream summaries.
    """
    server._require_scope("read")
    err = server._check_short_strings(
        workspace_id=workspace_id, repo_url=repo_url, lifecycle=lifecycle,
    )
    if err:
        return err
    server._audit("workstream_list", include_archived=include_archived,
                  workspace_id=workspace_id, repo_url=repo_url,
                  lifecycle=lifecycle)

    params = {}
    if include_archived:
        params["includeArchived"] = "true"
    if workspace_id:
        params["workspaceId"] = workspace_id
    if repo_url:
        params["repoUrl"] = repo_url
    if dispatch_capable is not None:
        params["dispatchCapable"] = "true" if dispatch_capable else "false"
    if archived is not None:
        params["archived"] = "true" if archived else "false"
    if include_status:
        params["includeStatus"] = "true"
    if include_pull_request:
        params["includePullRequest"] = "true"
    if include_pull_request_state:
        params["includePullRequestState"] = "true"
    if include_lifecycle or lifecycle:
        params["includeLifecycle"] = "true"
    if idle_days != 14:
        params["idleDays"] = str(int(idle_days))
    if lifecycle:
        params["lifecycle"] = lifecycle

    path = "/api/workstreams"
    if params:
        path += "?" + urlencode(params)
    result = server._controller_get(path)

    if isinstance(result, list):
        entries = server._filter_workstreams_by_scope(result)
        return {
            "ok": True,
            "workstreams": entries,
            "count": len(entries),
            "next_steps": [
                "Use workstream_get_status with a workstreamId to see job statistics",
                "Use workstream_submit_task to submit a coding task to an agent",
                "Workstreams with pipelineCapable=true support project_* tools",
            ],
        }

    # Error from controller
    result.setdefault("next_steps", [
        "Check controller_health to verify the controller is running",
    ])
    return result

@mcp.tool()
def workstream_get_status(workstream_id: str, period: str = "weekly",
                          include_active_jobs: bool = True) -> dict:
    """Get aggregate job statistics for a workstream.

    Shows job counts, total time, cost, and turns for this week and last week.
    For per-job details use workstream_context.

    Args:
        workstream_id: The workstream identifier (from workstream_list).
        period: Reporting period. The controller currently supports only
            ``"weekly"`` — any other value is rejected up front. Defaults
            to ``"weekly"``.
        include_active_jobs: When ``True`` (the default), the response also
            carries ``active_jobs`` — the workstream's currently-running jobs
            with their age and time since last liveness signal — and, when any
            of them has been silent too long, an ``active_jobs_warning``
            naming the job. This is how a stalled job becomes visible before
            the stuck-job scanner reaches it. Set ``False`` to skip the extra
            controller read.

    Returns:
        Dictionary with thisWeek and lastWeek aggregate stats (jobCount,
        successCount, failedCount, totalCostUsd, totalTurns, etc.). Each
        week's per-workstream stats also include a ``costByRunner`` object
        mapping runner name to summed USD cost for the window, so the split
        between (for example) claude and opencode spend is visible alongside
        the ``totalCostUsd`` aggregate.
    """
    server._require_scope("read")
    err = server._check_short_strings(workstream_id=workstream_id, period=period)
    if err:
        return err
    if period != "weekly":
        return {
            "ok": False,
            "error": (f"Unsupported period '{period}'. The controller "
                      "currently supports only 'weekly'."),
            "next_steps": [
                "Call workstream_get_status without the period argument",
                "Or pass period='weekly' explicitly",
            ],
        }
    server._audit("workstream_get_status", workstream_id=workstream_id)
    server._require_workstream_in_scope(workstream_id)
    params = urlencode({"workstream": workstream_id, "period": period})
    result = server._controller_get(f"/api/stats?{params}")
    result["workstream_id"] = workstream_id

    if include_active_jobs:
        active = server._controller_get(
            f"/api/workstreams/{workstream_id}/jobs/active")
        if isinstance(active, list):
            result["active_jobs"] = active
            stalled = [j for j in active
                       if isinstance(j, dict)
                       and j.get("sinceHeartbeatSeconds", 0) >= _STALLED_WARNING_SECONDS]
            if stalled:
                names = ", ".join(str(j.get("jobId")) for j in stalled)
                result["active_jobs_warning"] = (
                    f"{len(stalled)} job(s) have not reported progress in over "
                    f"{_STALLED_WARNING_SECONDS // 3600}h: {names}. A job that "
                    "stays silent is failed by the controller's stuck-job "
                    "scanner so anything waiting on it is released."
                )

    result.setdefault("next_steps", [
        "Use workstream_submit_task to submit a new coding task",
        "Use workstream_context to see branch memories and job history",
    ])
    return result

@mcp.tool()
def workstream_get_job(job_id: str) -> dict:
    """**Operational analytics.** Look up a specific job event by its
    job ID — the most recent status event, with cost, duration, PR URL,
    error message, etc.

    Use this when you submitted a job yourself and want to confirm it
    succeeded or inspect its failure detail. It is not a narrative tool
    — for the context around a job (why it was submitted, what the
    agent reported, what other jobs ran on the same branch) call
    ``workstream_context``.

    Args:
        job_id: The job identifier returned by workstream_submit_task.

    Returns:
        Dictionary with jobId, status, description, timestamp, and optional
        fields such as targetBranch, commitHash, pullRequestUrl, errorMessage,
        and costUsd.
    """
    server._require_scope("read")
    err = server._check_short_strings(job_id=job_id)
    if err:
        return err
    server._audit("workstream_get_job", job_id=job_id)
    result = server._controller_get(f"/api/jobs/{job_id}")
    # Scope check: a scoped token may only see jobs belonging to a workstream
    # in its workspace scope. The job event does not itself carry a workspace
    # ID, so we resolve via the workstream → workspace mapping. Unknown jobs
    # are returned unchanged for unscoped callers and suppressed as 404 for
    # scoped callers to avoid leaking existence.
    if server._get_workspace_scopes():
        ws_id = result.get("workstreamId") if isinstance(result, dict) else None
        if not ws_id or not server._is_workspace_allowed(server._workspace_for_workstream(ws_id)):
            return {"ok": False, "error": "Job not found"}
    return result




@mcp.tool()
def workstream_archive(
    workstream_id: str,
    archive_slack_channel: bool = True,
) -> dict:
    """Archive a workstream so it is hidden from default ``workstream_list``
    responses. Archiving is reversible and non-destructive — historical job
    records and memories remain queryable via ``workstream_context`` and
    ``memory_recall`` when ``workstream_id`` is supplied explicitly.

    The Slack channel bound to the workstream (if any) is archived via
    ``conversations.archive`` by default; pass ``archive_slack_channel=False``
    to leave it open. Slack archive failures are reported in the response but
    do not block the workstream archive — the controller treats the
    Slack-side effect as best-effort.

    The call is rejected when one or more jobs on the workstream are still
    active (``STARTED`` status); cancel them explicitly or wait for them to
    complete before archiving. The response carries the active job IDs.

    Args:
        workstream_id: The workstream to archive (from ``workstream_list``).
        archive_slack_channel: When ``True`` (default), also archive the
            bound Slack channel. Slack channels cannot be programmatically
            deleted, only archived; an archived channel after workstream
            archive is the expected end state.

    Returns:
        Dictionary with ``ok``, ``workstreamId``, ``archivedAt``,
        ``slackChannelArchived``, and optionally ``slackChannelArchiveError``.
    """
    server._require_scope("write")
    err = server._check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("workstream_archive", workstream_id=workstream_id,
           archive_slack_channel=archive_slack_channel)
    return server._controller_post(
        f"/api/workstreams/{quote(workstream_id, safe='')}/archive",
        {"archiveSlackChannel": archive_slack_channel},
    )

@mcp.tool()
def workstream_unarchive(workstream_id: str) -> dict:
    """Clear the archived flag on a previously archived workstream so it
    reappears in default ``workstream_list`` responses.

    The Slack channel, if it was archived alongside the workstream, must be
    unarchived manually from the Slack UI. Slack's ``conversations.unarchive``
    is not invoked automatically because unarchive fires notification spam
    to channel members.

    Args:
        workstream_id: The archived workstream to restore.

    Returns:
        Dictionary with ``ok`` and ``workstreamId``.
    """
    server._require_scope("write")
    err = server._check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("workstream_unarchive", workstream_id=workstream_id)
    return server._controller_post(
        f"/api/workstreams/{quote(workstream_id, safe='')}/unarchive",
        {},
    )

@mcp.tool()
def workstream_archive_many(
    workstream_ids: "list[str] | str" = "",
    archive_slack_channel: bool = True,
) -> dict:
    """Archive several workstreams in one call.

    Each is archived independently and the response reports what happened to
    each, so one workstream that cannot be archived — most often because a
    job is still running on it — does not block the rest. A batch with some
    failures is still ``ok``; read ``results`` to see which.

    Archiving is reversible, which is why it is offered in bulk. Deletion is
    not, and is deliberately available one workstream at a time only.

    Args:
        workstream_ids: The workstreams to archive. Accepts a list, a
            comma-separated string, or a JSON array string. Repeated ids are
            processed once.
        archive_slack_channel: When ``True`` (default), also archive each
            bound Slack channel.

    Returns:
        Dictionary with ``results`` (per-workstream outcomes in the order
        given), ``succeeded`` and ``failed`` counts.
    """
    return server._archive_many(workstream_ids, archive_slack_channel, archive=True)

@mcp.tool()
def workstream_unarchive_many(
    workstream_ids: "list[str] | str" = "",
) -> dict:
    """Restore several archived workstreams in one call.

    The inverse of :func:`workstream_archive_many`, with the same per-id
    reporting: one failure does not decide the batch.

    Args:
        workstream_ids: The workstreams to restore. Accepts a list, a
            comma-separated string, or a JSON array string. Repeated ids are
            processed once.

    Returns:
        Dictionary with ``results`` (per-workstream outcomes in the order
        given), ``succeeded`` and ``failed`` counts.
    """
    return server._archive_many(workstream_ids, False, archive=False)

@mcp.tool()
def workstream_delete(workstream_id: str, force: bool = False) -> dict:
    """Delete a workstream config row permanently.

    Two-step pattern: archive first (``workstream_archive``), then delete.
    Deletion requires the workstream to be archived unless ``force=True``;
    in either case the call is rejected when any job on the workstream is
    still active (``STARTED`` status). Cancellation is always explicit —
    ``force`` only bypasses the archive-first check.

    Side effects:

    - Tracker tasks linked to this workstream have their ``workstream_id``
      cleared (ON DELETE SET NULL semantics applied client-side via the
      ar-tracker API). The tasks themselves are NOT deleted. The count of
      affected tasks is returned as ``deletedTrackerTasks``.
    - The workstream config entry is removed from ``workstreams.yaml``.
    - **Memories are not touched.** Memory rows remain queryable via
      ``memory_recall`` when ``repo_url`` and ``branch`` are supplied
      directly, since the workstream's repo+branch are still recorded on
      each memory row. The workstream-to-repo/branch mapping is gone, so
      ``workstream_context`` with the deleted ID will no longer resolve.
    - The Slack channel is left as-is. If it was archived during the
      ``workstream_archive`` step, it stays archived. Slack channels
      cannot be programmatically deleted.
    - The git branch on origin is NOT touched.

    Args:
        workstream_id: The workstream to delete (from ``workstream_list``).
        force: When ``True``, bypass the archive-first requirement.
            Active-job checks are NOT bypassed.

    Returns:
        Dictionary with ``ok``, ``workstreamId``, and
        ``deletedTrackerTasks`` (the number of tracker tasks whose
        ``workstream_id`` was cleared). If tracker cleanup was interrupted
        by a query or update failure, ``trackerCleanupWarning`` is also
        present with a human-readable description; ``ok`` remains ``True``
        because the workstream itself was deleted successfully.
    """
    server._require_scope("write")
    err = server._check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("workstream_delete", workstream_id=workstream_id, force=force)

    # The controller delete runs first so that a rejection (active jobs,
    # archive-first requirement, unknown workstream) leaves the tracker
    # linkage intact. Only on a successful delete do we clear tracker
    # rows — that matches the ON DELETE SET NULL semantics described in
    # the docstring and is reversible only by re-linking.
    result = server._controller_post(
        f"/api/workstreams/{quote(workstream_id, safe='')}/delete",
        {"force": force},
    )
    if not result.get("ok"):
        return result

    # /v1/tasks caps `limit` at 200, so a workstream with more than 200
    # linked tasks needs paging. After we PUT workstream_id=None on a task
    # it no longer matches the filter, so we just keep re-querying until the
    # filter returns no tasks. The server.MAX_TRACKER_CLEAR_BATCHES cap prevents an
    # unexpected server response (e.g. failed updates) from spinning forever.
    cleared = 0
    seen_ids = set()
    server.MAX_TRACKER_CLEAR_BATCHES = 200
    tracker_warning = None
    for _ in range(server.MAX_TRACKER_CLEAR_BATCHES):
        tasks_result = server._tracker_get(
            f"/v1/tasks?{urlencode({'workstream_id': workstream_id, 'limit': 200, 'fields': 'headlines'})}"
        )
        if not tasks_result.get("ok"):
            tracker_warning = (
                "tracker query failed during cleanup; "
                + str(cleared) + " task(s) unlinked before failure"
            )
            break
        batch = tasks_result.get("tasks") or []
        if not batch:
            break
        progress = False
        new_in_batch = 0
        for task in batch:
            task_id = task.get("id")
            if not task_id or task_id in seen_ids:
                continue
            new_in_batch += 1
            seen_ids.add(task_id)
            update = server._tracker_put(f"/v1/tasks/{task_id}",
                                  {"workstream_id": None})
            if update.get("ok"):
                cleared += 1
                progress = True
        if not progress:
            if new_in_batch > 0:
                # New tasks appeared but none could be updated — stall.
                tracker_warning = (
                    "tracker update stalled; "
                    + str(cleared) + " task(s) unlinked before stall"
                )
            break
    else:
        # Loop exhausted without emptying the task list.
        tracker_warning = (
            "tracker cleanup hit batch limit; "
            + str(cleared) + " task(s) unlinked"
        )
    result["deletedTrackerTasks"] = cleared
    if tracker_warning is not None:
        result["trackerCleanupWarning"] = tracker_warning
    return result

@mcp.tool()
def workstream_context(
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    namespace: str = "",
    limit: int = 20,
    include_messages: bool = True,
    include_commits: bool = True,
    commit_limit: int = 30,
    job_limit: int = 20,
    include_activities: "list[str] | str" = "primary",
    include_memories: bool = True,
    reformulated: bool = False,
    max_memories: Optional[int] = None,
    max_activities: Optional[str] = None,
) -> dict:
    """Reconstruct the narrative of a workstream — what agents have been
    thinking about and doing on a branch. This is the primary tool for
    orienting yourself when picking up a workstream, coordinating with
    other agents working on the same branch, or deciding what to do next.

    Returns up to four streams:
      - **memories**: agent-authored notes across every namespace
        (``feedback``, ``project``, ``bugs``, ``messages``, …), sorted
        newest-first. This is the substantive content — what was
        reported, decided, discovered. Always present.
      - **commits**: the commit history of the branch relative to its
        base branch, via the GitHub Compare API. Present when
        ``include_commits`` is true and the repo can be resolved.
      - **jobs**: a compact timeline of job runs on this workstream
        (timestamp, status, description, commit, PR, error). Not the
        full operational record — just enough to situate memories in
        time. Present (possibly as an empty list) whenever
        ``workstream_id`` is supplied and ``job_limit > 0``; omitted
        otherwise.
      - **metadata**: resolved repo_url, branch, namespace. Always present.
      - **pull_request**: metadata about the most recent pull request
        associated with the branch (across all states: open, closed,
        merged). Present when a PR exists; omitted entirely when no PR
        is found or the repo cannot be resolved. Includes ``number``,
        ``title``, ``url``, ``state``, ``created_at``, ``updated_at``,
        ``merged_at``, ``closed_at``, ``author``, ``base_branch``,
        and ``head_branch``.

    Prefer this tool over ``workstream_get_status`` for
    doing-real-work tasks. ``workstream_get_status`` is an operational-
    analytics tool (platform health, cost, turn counts); this one is
    the actual narrative.

    By default (``namespace=""``), memories are returned across every
    namespace on the branch, sorted newest-first. Supply an explicit
    ``namespace`` to filter to one namespace instead.

    Args:
        workstream_id: Workstream to resolve repo/branch/jobs from.
        repo_url: Repository URL to match (when no workstream supplied).
        branch: Branch name to match (when no workstream supplied).
        namespace: Memory namespace to filter to. Defaults to empty,
            which returns entries from every namespace.
        limit: Maximum number of memory entries.
        include_messages: Kept for backwards compatibility. Only takes
            effect when ``namespace`` is explicitly set to a value other
            than ``"messages"``. Ignored in the default all-namespace
            mode because messages are already included.
        include_commits: If true (default), include the commit list.
        commit_limit: Maximum number of commits to include (default 30).
        job_limit: Maximum number of jobs to include in the timeline
            (default 20). Pass 0 to omit jobs entirely.
        include_memories: When false, skip the memory search entirely. The
            memories are the bulk of this response, so a caller that only
            wants the branch's pull request or commit list should turn them
            off rather than receive and discard them.
        max_memories: Not a parameter — any value, including ``0``, is
            rejected with a pointer to ``limit``. Declared only so the
            mistake reports itself instead of being dropped silently by the
            schema layer.
        max_activities: Not a parameter — any value, including ``""``, is
            rejected with a pointer to ``include_activities``, for the same
            reason.
        include_activities: Activity filter — accepts a Python list of strings,
            a JSON-array string (``["deduplication","primary"]``), or a plain
            comma-separated string.  Defaults to ``"primary"``, which returns
            only messages with no activity tag (primary work) or with the
            explicit ``activity:primary`` tag — both are treated as primary.
            Audit-phase messages (e.g. ``activity:deduplication``) are hidden
            by default.  Pass ``"all"`` to see every message, or a specific
            activity name (e.g. ``"deduplication"``) to see that phase plus
            primary/untagged messages.
        reformulated: When true, present the Consultant's rewrite of each
            memory instead of the text its author wrote. Beta — off by
            default, because a narrative reconstructed from rewrites loses
            the specifics it depends on.

    Returns:
        Dictionary with branch memories, optionally commits, and optionally
        a compact jobs timeline. When commits are included, the response also
        contains ``total_commits`` (the full number of commits on the branch)
        and ``initial_commit_sha`` (the first commit on the branch relative
        to the base). Each memory carries ``text_source`` recording which
        version of the text is shown.
    """
    server._require_scope("memory-read")
    # These two names have never been parameters of this tool, but callers
    # reach for them and the schema layer drops unknown keys silently — so the
    # call appeared to succeed while quietly using the defaults. Declaring
    # them makes that a corrective error instead. They are deliberately not
    # aliases: a second permanent name for one concept is worse than being
    # told the right one once.
    #
    # The sentinel is None, and the test is "is not None", so that a falsey
    # value supplied by a caller — max_activities="" or max_memories=0 — is
    # still caught. A truthiness test would wave through exactly the mistaken
    # calls these parameters exist to intercept.
    if max_memories is not None:
        return {
            "ok": False,
            "error": "max_memories is not a parameter of workstream_context; "
                     "use limit instead.",
        }
    if max_activities is not None:
        return {
            "ok": False,
            "error": "max_activities is not a parameter of "
                     "workstream_context; use include_activities instead.",
        }
    err = server._check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url,
        branch=branch, namespace=namespace,
    )
    if err:
        return err
    server._audit("workstream_context", workstream_id=workstream_id, branch=branch)

    effective_repo, effective_branch, err = server._resolve_branch_context(
        workstream_id=workstream_id, repo_url=repo_url, branch=branch,
    )
    if err:
        return err

    client = server._get_memory_client()
    if client is None:
        return {
            "ok": False,
            "error": "ar-memory server unavailable",
            "next_steps": [
                "Start ar-memory: python tools/mcp/memory/server.py --http-only",
            ],
        }

    # ``namespace=""`` (the default) means "all namespaces, newest first".
    # The underlying client+server contract treats an empty/None namespace
    # as a wildcard, so messages are already interleaved with every other
    # namespace by recency — the include_messages flag becomes a no-op
    # in that mode.
    lookup_namespace = namespace if namespace else None

    # The memory payload dominates this response — tens of KB of agent prose
    # for a branch with any history. A caller asking only "what PR is on this
    # branch?" should not have to receive and pay for all of it, so skip the
    # search entirely rather than fetching and discarding.
    memories = []
    if include_memories:
        try:
            memories = client.search_by_branch(
                repo_url=effective_repo,
                branch=effective_branch,
                namespace=lookup_namespace,
                limit=limit,
            )
        except ConnectionError as e:
            return {"ok": False, "error": f"Memory branch lookup failed: {e}"}

    # When the caller narrowed to a specific namespace and also asked for
    # messages, merge in a second stream. Messages are capped at ``limit``
    # and re-sorted by recency; primary memories are not displaced.
    if include_memories and namespace and include_messages and namespace != "messages":
        try:
            msg_memories = client.search_by_branch(
                repo_url=effective_repo,
                branch=effective_branch,
                namespace="messages",
                limit=limit,
            )
            if msg_memories:
                primary = memories[:limit]
                combined = primary + msg_memories
                combined.sort(
                    key=lambda m: m.get("created_at", ""),
                    reverse=True,
                )
                memories = combined
        except ConnectionError:
            pass  # Non-critical: proceed without messages

    # Filter memories by activity.  Each message may carry a tag of the
    # form ``activity:<name>`` (e.g. ``activity:deduplication``).  Memories
    # without any such tag are considered primary work.  The
    # ``include_activities`` parameter controls which activities are shown.
    #
    # Special values:
    #   "all"     — no filtering; return every memory regardless of activity
    #   "primary" — (default) return only primary/untagged and activity:primary
    #   any other — return memories whose activity tag matches that value,
    #               plus primary/untagged memories
    #
    # Multiple values can be comma-separated, e.g. "primary,deduplication".
    # Also accepts a Python list or a JSON-array string via _parse_activities_param.
    effective_include = server._parse_activities_param(include_activities)
    if effective_include != "all":
        allowed = {v.strip() for v in effective_include.split(",") if v.strip()}

        def _activity_allowed(mem: dict) -> bool:
            tags = mem.get("tags") or []
            activity_tags = [t[len("activity:"):] for t in tags if t.startswith("activity:")]
            if not activity_tags or "primary" in activity_tags:
                # No activity tag or explicit activity:primary — primary work, always included
                return True
            return any(a in allowed for a in activity_tags)

        memories = [m for m in memories if _activity_allowed(m)]

    # Resolve which version of each memory's text the narrative shows. This
    # also unwraps the dual-text JSON out of ``source``, so the entries that
    # went through Consultant reformulation look like every other entry.
    memories, notice = present(
        memories,
        reformulated=reformulated or repo_config.repo_setting(
            effective_repo, "preferReformulatedOnRead", prefers_reformulated(),
        ),
    )

    # Fetch commit history from GitHub Compare API if requested
    commits = None
    commit_error = None
    total_commits = 0
    all_commits = []
    if include_commits and effective_repo:
        owner_repo = server._extract_owner_repo(effective_repo)
        if owner_repo:
            owner, repo = owner_repo
            # Determine the base branch from the workstream if available.
            # Without one, ask GitHub what the repository's default branch is
            # rather than assuming "master" — assuming it makes every compare
            # against a "main"-default repo 404 and report no commits.
            ws = server._find_workstream(workstream_id) if workstream_id else None
            base = ((ws or {}).get("baseBranch", "")
                    or github_api.default_branch(owner, repo))

            # Set GitHub org context so the proxy uses the correct per-org token
            if ws:
                server._set_github_org(ws)
            elif owner:
                server._current_github_org.set(owner)

            try:
                compare = server._github_request(
                    "GET",
                    f"/repos/{owner}/{repo}/compare/{base}...{effective_branch}",
                )
                if compare.get("ok") is False:
                    commit_error = compare.get("error", "GitHub API returned an error")
                    logging.getLogger("ar-manager").warning(
                        "Failed to fetch commits for %s...%s: %s",
                        base, effective_branch, commit_error)
                elif "commits" in compare:
                    all_commits = compare.get("commits", [])
                    total_commits = len(all_commits)
                    # Take the most recent commits (Compare API returns
                    # oldest-first, so slice from the end).
                    recent = all_commits[-commit_limit:] if len(all_commits) > commit_limit else all_commits
                    commits = []
                    for c in recent:
                        commit_obj = c.get("commit", {})
                        author_obj = commit_obj.get("author", {})
                        commits.append({
                            "sha": c.get("sha", "")[:10],
                            "author": author_obj.get("name", ""),
                            "date": author_obj.get("date", ""),
                            "message": commit_obj.get("message", "").split("\n")[0],
                        })
                else:
                    commit_error = "GitHub Compare API returned no commits field"
            except Exception as exc:
                commit_error = str(exc)
                logging.getLogger("ar-manager").warning(
                    "Failed to fetch commits for %s...%s: %s",
                    base, effective_branch, exc)
        else:
            commit_error = f"Could not extract owner/repo from URL: {effective_repo}"

    # Fetch the most recent PR for the branch (across all states: open, closed, merged)
    pull_request = None
    pr_error = None
    if effective_repo:
        pr_owner_repo = server._extract_owner_repo(effective_repo)
        if pr_owner_repo:
            pr_owner, pr_repo = pr_owner_repo
            # Set GitHub org context so the proxy uses the correct per-org token
            ws = server._find_workstream(workstream_id) if workstream_id else None
            if ws:
                server._set_github_org(ws)
            elif pr_owner:
                server._current_github_org.set(pr_owner)

            try:
                pr_lookup = server._find_recent_pr_by_branch(pr_owner, pr_repo, effective_branch)
                if pr_lookup.get("ok") and pr_lookup.get("found"):
                    raw_pr = pr_lookup.get("pr", {})
                    author = raw_pr.get("user") or raw_pr.get("author") or {}
                    pull_request = {
                        "number": raw_pr.get("number"),
                        "title": raw_pr.get("title"),
                        "url": raw_pr.get("html_url"),
                        "state": raw_pr.get("state"),
                        "created_at": raw_pr.get("created_at"),
                        "updated_at": raw_pr.get("updated_at"),
                        "merged_at": raw_pr.get("merged_at"),
                        "closed_at": raw_pr.get("closed_at"),
                        "author": author.get("login") if author else None,
                        "base_branch": raw_pr.get("base", {}).get("ref") if isinstance(raw_pr.get("base"), dict) else None,
                        "head_branch": raw_pr.get("head", {}).get("ref") if isinstance(raw_pr.get("head"), dict) else None,
                    }
                elif pr_lookup.get("ok") is False:
                    pr_error = pr_lookup.get("error", "GitHub API error")
            except Exception as exc:
                pr_error = str(exc)
                logging.getLogger("ar-manager").warning(
                    "Failed to fetch PR for %s: %s", effective_branch, exc)

    # Compact jobs timeline: enough fields to situate memories in time and
    # link them to the commits/PR flow, nothing more.
    #
    # Coerce job_limit defensively. MCP tool inputs are not runtime-type-
    # enforced, so a caller could pass a string, a float, or a negative
    # integer. Interpolating that directly into a URL would produce a
    # malformed query; use a validated int and urlencode the query string.
    try:
        safe_job_limit = max(0, int(job_limit))
    except (TypeError, ValueError):
        safe_job_limit = 0
    jobs_timeline = []
    jobs_included = bool(workstream_id) and safe_job_limit > 0
    if jobs_included:
        try:
            params = urlencode({"limit": safe_job_limit})
            jobs_result = server._controller_get(
                f"/api/workstreams/{quote(workstream_id, safe='')}/jobs?{params}")
            if isinstance(jobs_result, list):
                for job in jobs_result:
                    if not isinstance(job, dict):
                        continue
                    compact = {
                        "jobId": job.get("jobId"),
                        "timestamp": job.get("timestamp"),
                        "status": job.get("status"),
                        "description": job.get("description"),
                    }
                    if job.get("commitHash"):
                        compact["commitHash"] = job["commitHash"][:10]
                    if job.get("pullRequestUrl"):
                        compact["pullRequestUrl"] = job["pullRequestUrl"]
                    if job.get("errorMessage"):
                        compact["errorMessage"] = job["errorMessage"]
                    jobs_timeline.append(compact)
        except Exception:
            pass  # Non-critical: proceed without job history

    result = {
        "ok": True,
        "repo_url": effective_repo,
        "branch": effective_branch,
        "namespace": namespace,
        "memories": memories,
        "count": len(memories),
        "next_steps": [
            "Use memory_recall for semantic search within these memories",
            "Use memory_store to add a new memory for this branch",
            "Use project_read_plan to read the planning document",
        ],
    }
    if notice:
        result["notice"] = notice
    # Expose the jobs key unconditionally when the caller requested it —
    # an empty list is a meaningful signal (no jobs on this branch yet),
    # distinct from "the caller opted out with job_limit=0 or passed no
    # workstream_id".
    if jobs_included:
        result["jobs"] = jobs_timeline
    if commits is not None:
        result["commits"] = commits
        result["commit_count"] = len(commits)
        result["total_commits"] = total_commits
        if all_commits:
            result["initial_commit_sha"] = all_commits[0].get("sha", "")[:10]
    if commit_error is not None:
        result["commit_error"] = commit_error
    if pull_request is not None:
        result["pull_request"] = pull_request
    if pr_error is not None:
        result["pr_error"] = pr_error

    return result
