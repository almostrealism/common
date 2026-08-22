"""
Workstream registration and configuration for the AR Manager MCP server.

These two share a payload contract — the controller reads the same fields
from both — which is why they sit together and apart from the lifecycle
tools.

Split from ``workstream_tools`` for length; the conventions are the same.
"""

from typing import Optional
from urllib.parse import quote

import server
from server import mcp



@mcp.tool()
def workstream_register(
    default_branch: str,
    base_branch: str = "master",
    repo_url: str = "",
    planning_document: str = "",
    channel_name: str = "",
    required_labels: str = "",
    dependent_repos: str = "",
    completion_listeners: str = "",
    workspace_id: str = "",
    plan_content: str = "",
    plan_instructions: str = "",
    plan_path: str = "",
    plan_commit_message: str = "",
    default_phase_config: str = "",
    phase_configs: str = "",
    dispatch_capable: bool = False,
    default_use_tmux: bool = False,
    slack_workspace_id: str = "",
    # Removed legacy config parameters — see _reject_removed_config_params.
    # Untyped so they stay out of the declared tool schema while still being
    # captured here for a clear rejection error.
    model="",
    effort="",
    default_runner="",
    runners="",
) -> dict:
    """Register a new workstream for a branch/repo combination.

    A workstream represents a body of work (feature, project, bug fix)
    with its own git branch, configuration, and Slack channel. Agents
    are assigned to workstreams to receive tasks.

    If a workstream already exists for the same branch and repo, the
    existing workstream is returned instead of creating a duplicate.

    Args:
        default_branch: The git branch agents will commit to (required).
        base_branch: The base branch for new branch creation (default: "master").
        repo_url: Git repository URL for automatic checkout.
        planning_document: Path to a planning document for broader context.
        channel_name: Slack channel name to create (optional).
        required_labels: Node labels that all jobs in this workstream must
            match by default. Accepts either comma-separated key:value pairs
            (e.g., "platform:macos,gpu:true") or a JSON object (e.g.,
            '{"platform": "macos", "gpu": "true"}'). Job-level labels always
            override these workstream-level defaults.
        dependent_repos: Comma-separated list of git clone URLs for additional
            repositories that agents will clone alongside the primary repo
            (e.g., "https://github.com/org/lib.git,https://github.com/org/tools.git").
            Also accepts a JSON array string. Dependent repos follow the same
            branch lifecycle as the primary repo (create/checkout/pull/commit/push).
        completion_listeners: Comma-separated list of workstream IDs that
            should be woken up automatically when a job on this workstream
            reaches a terminal status. Also accepts a JSON array string.
            The listener graph is checked for cycles at config time; a
            registration that would create a cycle (including a
            self-listing) is rejected with a 400. The feature ships
            inert: a workstream with no listeners configured spawns no
            wake-up jobs. Wake-up generation is gated by the
            controller's automated-jobs gate, which is the kill switch.
        workspace_id: Workspace ID (operator-chosen identifier) to
            register this workstream under. When omitted, unscoped
            (superadmin) tokens allow the controller to derive the
            target workspace from the GitHub org in ``repo_url``.
            Callers using tokens scoped to specific workspaces must
            pass this parameter explicitly.
        slack_workspace_id: Deprecated alias for ``workspace_id``;
            accepted for backward compatibility with older callers.
        plan_content: Literal markdown content of a planning document to
            commit directly to the new workstream's branch immediately after
            registration. Mutually exclusive with ``plan_instructions``.
            Attempts a direct commit via the GitHub Contents API; if the
            commit fails (permissions, protected branch, etc.) the workstream
            registration itself still succeeds and the response's ``plan``
            field contains ``mode="failed"`` with ``fallback_instructions``.
        plan_instructions: Natural-language specification of what the plan
            document should describe. When provided, a coding job is
            submitted to the newly-registered workstream with a prompt that
            asks the agent to write and commit the plan document. Mutually
            exclusive with ``plan_content``.
        plan_path: File path for the plan document in the repo. Optional —
            if omitted, the controller auto-generates a path under
            ``docs/plans/``. Used by both the direct-commit and job-submit
            paths. Also becomes the workstream's ``planningDocument`` when
            ``planning_document`` is not given, so ``project_read_plan``
            works without a follow-up config call; an explicit
            ``planning_document`` always wins.
        plan_commit_message: Git commit message for the direct-commit path.
            Ignored when ``plan_instructions`` is used. Auto-generated if
            omitted.
        default_phase_config: Workstream-level default configuration as a
            JSON object with optional ``runner`` / ``model`` / ``effort`` /
            ``provider`` keys, applied to every job and phase that does not
            override it. Use ``agent_options`` to discover available runner
            names. Example::

                '{"runner": "opencode", "model": "qwen3-coder:exacto",
                  "effort": "medium", "provider": "openrouter"}'

        phase_configs: Workstream-level per-phase overrides as a JSON object
            whose keys are phase wire names and whose values are
            ``{runner, model, effort, provider}`` objects (all keys optional).
            Each named phase overrides ``default_phase_config`` field-by-field.
            Example::

                '{"review": {"runner": "claude"},
                  "deduplication": {"runner": "opencode"}}'

        dispatch_capable: When ``True``, agents running on this workstream
            are granted access to the dispatch / orchestration MCP tools
            (currently ``workstream_register`` and
            ``workstream_update_config``). The flag is required to set up
            orchestrator workstreams that register or update child
            workstreams. Defaults to ``False`` — most workstreams do not
            need this power. Granting dispatch is bounded by the
            ``acceptAutomatedJobs`` kill switch and the completion-listener
            ceilings, so the flag is the gate but not the only safety
            mechanism. Operators should enable it only on workstreams
            that genuinely orchestrate.
        default_use_tmux: When ``True``, coding-agent jobs on this workstream
            launch the agent subprocess inside a tmux session (a real
            controlling tty) by default. The per-job ``use_tmux`` flag
            still wins on a per-job basis, so a particular job submission
            can override the workstream default. Defaults to ``False`` —
            opt in explicitly. The runner additionally honours the
            ``AR_AGENT_USE_TMUX`` environment variable as an independent
            enable, which is unaffected by this flag.

        model: REMOVED. The legacy ``model`` parameter is no longer accepted;
            passing it fails with a 400-style error. Use
            ``default_phase_config`` or ``phase_configs`` to set models.
        effort: REMOVED. The legacy ``effort`` parameter is no longer
            accepted. Use ``default_phase_config`` or ``phase_configs``.
        default_runner: REMOVED. The legacy ``default_runner`` parameter is no
            longer accepted. Use ``default_phase_config='{"runner": "..."}'``.
        runners: REMOVED. The legacy ``runners`` map is no longer accepted.
            Use ``phase_configs`` (per-phase) or ``default_phase_config``.

    Returns:
        Dictionary with workstreamId and channel info on success. When
        ``plan_content`` or ``plan_instructions`` is supplied, also includes
        a ``plan`` field with:
        - ``mode``: ``"committed"``, ``"submitted"``, or ``"failed"``.
        - ``path``: the plan document path (when available).
        - ``commit_sha``: only when ``mode=="committed"``.
        - ``job_id``: only when ``mode=="submitted"``.
        - ``error`` and ``fallback_instructions``: only when ``mode=="failed"``.
    """
    server._require_scope("write")
    # Controller-side enforcement: a job-scoped agent on a workstream
    # that is not dispatch-capable cannot register workstreams. The
    # harness-CSV half of the contract is in
    # ``McpConfigBuilder.buildAllowedTools``; this is the backstop
    # for the opencode harness's per-SERVER filtering (see
    # ``OpencodeConfigBuilder.translateAllowlist``). Admin / operator
    # callers (no caller workstream bound) are always permitted.
    server._require_dispatch_capable()
    # slack_workspace_id is the legacy name; the new canonical name is
    # workspace_id. Accept either, preferring the new name.
    if not workspace_id and slack_workspace_id:
        server.audit_log.debug("workstream_register: slack_workspace_id is a "
                        "deprecated alias for workspace_id")
        workspace_id = slack_workspace_id
    err = server._reject_removed_config_params(
        model=model, effort=effort, default_runner=default_runner, runners=runners)
    if err:
        return err
    err = server._check_short_strings(
        default_branch=default_branch, base_branch=base_branch,
        repo_url=repo_url, planning_document=planning_document,
        channel_name=channel_name, workspace_id=workspace_id,
        plan_path=plan_path, plan_commit_message=plan_commit_message,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = server._parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = server._parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    # plan_content and plan_instructions describe two different follow-up
    # actions; the caller must pick one. Reject ambiguous requests up front.
    if plan_content and plan_instructions:
        return {
            "ok": False,
            "error": "plan_content and plan_instructions are mutually exclusive",
        }
    err = server._check_length(plan_content, "plan_content", server.MAX_CONTENT_LEN)
    if err:
        return err
    err = server._check_length(plan_instructions, "plan_instructions", server.MAX_CONTENT_LEN)
    if err:
        return err
    # Scope enforcement: scoped callers must name a workspace they own.
    # An explicit workspace_id wins. Otherwise we refuse rather than
    # rely on the controller's repoUrl-derivation path, because allowing
    # the caller to rely on controller-side derivation would open a
    # scope-bypass if repoUrl is omitted or spoofed.
    if server._get_workspace_scopes():
        if workspace_id:
            server._require_workspace(workspace_id)
        else:
            raise PermissionError(
                "Scoped tokens must pass workspace_id when registering "
                "a workstream — repoUrl-based derivation is only available "
                "to unscoped (superadmin) tokens."
            )
    server._audit("workstream_register", default_branch=default_branch,
           workspace_id=workspace_id)

    payload = {"defaultBranch": default_branch}
    if base_branch:
        payload["baseBranch"] = base_branch
    if repo_url:
        payload["repoUrl"] = repo_url
    if planning_document:
        payload["planningDocument"] = planning_document
    elif plan_path:
        # A caller who names the file the plan job will write has told us
        # where the planning document lives. Not recording it left the
        # workstream with no planningDocument, so project_read_plan failed
        # against a document that demonstrably existed until a second
        # workstream_update_config call was made to say so.
        payload["planningDocument"] = plan_path
    if channel_name:
        payload["channelName"] = channel_name
    if workspace_id:
        # Send both names: the controller accepts either, and the legacy
        # field name is kept so older controllers without the rename
        # continue to honour the registration.
        payload["workspaceId"] = workspace_id
        payload["slackWorkspaceId"] = workspace_id
    if required_labels:
        labels_map = server._parse_required_labels(required_labels)
        if labels_map:
            payload["requiredLabels"] = labels_map
    if dependent_repos:
        repos_list = server._parse_dependent_repos(dependent_repos)
        if repos_list:
            payload["dependentRepos"] = repos_list
    if completion_listeners:
        listeners_list = server._parse_completion_listeners(completion_listeners)
        if listeners_list:
            payload["completionListeners"] = listeners_list
    if parsed_default_phase_config:
        payload["defaultPhaseConfig"] = parsed_default_phase_config
    if parsed_phase_configs:
        payload["phaseConfigs"] = parsed_phase_configs
    # dispatchCapable is forwarded unconditionally because it is a
    # boolean; omitting the field on the controller side would default
    # to false, but only if the field is missing. Forwarding the
    # boolean directly is simpler and the controller's extractBoolean
    # helper handles a missing field identically.
    payload["dispatchCapable"] = bool(dispatch_capable)
    # defaultUseTmux follows the same unconditional-forward pattern as
    # dispatchCapable: a boolean forwarded verbatim so the controller
    # sees the operator's intent regardless of whether the field
    # appears in the body. The workstream-level default takes effect on
    # every job on this workstream that does not set the per-job
    # use_tmux flag explicitly.
    payload["defaultUseTmux"] = bool(default_use_tmux)

    result = server._controller_post("/api/workstreams", payload)

    if result.get("ok"):
        ws_id = result.get("workstreamId", "")
        steps = [
            f"Workstream '{ws_id}' is ready",
        ]
        if not repo_url:
            steps.append(
                "Consider using workstream_update_config to set repo_url "
                "for pipeline capabilities"
            )

        # Follow-up: plan_content → direct commit, plan_instructions → submit a job.
        # Registration success is already locked in above; any failure below is
        # surfaced in result["plan"] without rolling back the registration, so
        # the caller can decide whether to retry or fall back.
        if plan_content:
            result["plan"] = server._attempt_plan_commit(
                ws_id, plan_content, plan_path, plan_commit_message)
            if result["plan"].get("mode") == "committed":
                steps.append(
                    f"Plan committed at {result['plan'].get('path')}")
            else:
                steps.append(
                    "Plan commit failed — see result.plan.fallback_instructions")
        elif plan_instructions:
            result["plan"] = server._attempt_plan_writing_job(
                ws_id, plan_instructions, plan_path)
            if result["plan"].get("mode") == "submitted":
                steps.append(
                    f"Plan-writing job submitted: {result['plan'].get('job_id')}")
            else:
                steps.append(
                    "Plan job submission failed — see result.plan.fallback_instructions")
        else:
            steps.append(
                "Use workstream_submit_task to send a coding task to this workstream")

        result["next_steps"] = steps
    else:
        result.setdefault("next_steps", [
            "Check controller_health to verify the controller is running",
        ])

    return result

@mcp.tool()
def workstream_update_config(
    workstream_id: str,
    default_branch: str = "",
    base_branch: str = "",
    repo_url: str = "",
    planning_document: str = "",
    channel_name: str = "",
    required_labels: str = "",
    dependent_repos: str = "",
    completion_listeners: str = "",
    default_phase_config: str = "",
    phase_configs: str = "",
    dispatch_capable: Optional[bool] = None,
    default_use_tmux: Optional[bool] = None,
    # Removed legacy config parameters — see _reject_removed_config_params.
    # Untyped so they stay out of the declared tool schema while still being
    # captured here for a clear rejection error.
    model="",
    effort="",
    default_runner="",
    runners="",
) -> dict:
    """Update configuration for an existing workstream.

    Only the fields you provide will be updated; others remain unchanged.
    Use this to enable pipeline capabilities by setting repo_url, or to
    update the planning document path.

    Args:
        workstream_id: The workstream to update (from workstream_list).
        default_branch: New git branch for agent commits.
        base_branch: New base branch for branch creation.
        repo_url: Git repository URL (enables pipeline tools).
        planning_document: Path to planning document.
        channel_name: New Slack channel name.
        required_labels: Node labels that all jobs in this workstream must
            match by default. Accepts either comma-separated key:value pairs
            (e.g., "platform:macos,gpu:true") or a JSON object (e.g.,
            '{"platform": "macos", "gpu": "true"}'). Job-level labels always
            override these workstream-level defaults.
        dependent_repos: Comma-separated list of git clone URLs for additional
            repositories that agents should clone alongside the primary repo
            (e.g., "https://github.com/org/lib.git,https://github.com/org/tools.git").
            Also accepts a JSON array string. Dependent repos follow the same
            branch lifecycle as the primary repo (create/checkout/pull/commit/push).
        default_phase_config: New workstream-level default configuration as a
            JSON object with optional ``runner`` / ``model`` / ``effort`` /
            ``provider`` keys. Pass ``'{}'`` to clear the stored default
            (all phases will then fall through to the workspace or controller
            default). Empty string leaves it unchanged. Use ``agent_options``
            to discover available runner names. Example::

                '{"runner": "opencode", "model": "qwen3-coder:exacto",
                  "effort": "medium", "provider": "openrouter"}'

        phase_configs: New workstream-level per-phase overrides as a JSON
            object whose keys are phase wire names and whose values are
            ``{runner, model, effort, provider}`` objects (all keys optional).
            Pass ``'{}'`` to clear all per-phase overrides. Set a phase value
            to ``null`` (e.g. ``'{"review": null}'``) to clear just that
            phase's override. Empty string leaves the per-phase map unchanged.
            Each named phase overrides ``default_phase_config`` field-by-field.
        dispatch_capable: When ``True``, agents running on this workstream
            are granted access to the dispatch / orchestration MCP tools
            (``workstream_register`` and ``workstream_update_config``).
            The flag is forwarded to the controller only when explicitly
            passed — passing ``False`` is an explicit revoke; omitting
            the parameter entirely leaves the existing controller value
            unchanged (presence-signal semantics, same pattern as
            ``completion_listeners``). Defaults to ``None`` (no change).
        default_use_tmux: When ``True``, coding-agent jobs on this workstream
            launch the agent subprocess inside a tmux session (a real
            controlling tty) by default. The per-job ``use_tmux`` flag
            still wins on a per-job basis. Same presence-signal
            semantics as ``dispatch_capable``: omitted leaves the
            workstream's existing default unchanged; an explicit
            ``False`` clears the opt-in. Defaults to ``None``
            (no change).
        model: REMOVED. The legacy ``model`` parameter is no longer accepted;
            passing it fails with a 400-style error. Use
            ``default_phase_config`` or ``phase_configs`` to set models.
        effort: REMOVED. The legacy ``effort`` parameter is no longer
            accepted. Use ``default_phase_config`` or ``phase_configs``.
        default_runner: REMOVED. The legacy ``default_runner`` parameter is no
            longer accepted. Use ``default_phase_config='{"runner": "..."}'``.
        runners: REMOVED. The legacy ``runners`` map is no longer accepted.
            Use ``phase_configs`` (per-phase) or ``default_phase_config``.

    Returns:
        Dictionary confirming the update.
    """
    server._require_scope("write")
    # Controller-side enforcement: a job-scoped agent on a workstream
    # that is not dispatch-capable cannot update workstreams. The
    # harness-CSV half of the contract is in
    # ``McpConfigBuilder.buildAllowedTools``; this is the backstop
    # for the opencode harness's per-SERVER filtering. Admin / operator
    # callers (no caller workstream bound) are always permitted.
    server._require_dispatch_capable()
    err = server._reject_removed_config_params(
        model=model, effort=effort, default_runner=default_runner, runners=runners)
    if err:
        return err
    err = server._check_short_strings(
        workstream_id=workstream_id, default_branch=default_branch,
        base_branch=base_branch, repo_url=repo_url,
        planning_document=planning_document, channel_name=channel_name,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = server._parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = server._parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    server._require_workstream_in_scope(workstream_id)
    server._audit("workstream_update_config", workstream_id=workstream_id)

    payload = {}
    if default_branch:
        payload["defaultBranch"] = default_branch
    if base_branch:
        payload["baseBranch"] = base_branch
    if repo_url:
        payload["repoUrl"] = repo_url
    if planning_document:
        payload["planningDocument"] = planning_document
    if channel_name:
        payload["channelName"] = channel_name
    if required_labels:
        labels_map = server._parse_required_labels(required_labels)
        if labels_map:
            payload["requiredLabels"] = labels_map
    if dependent_repos:
        repos_list = server._parse_dependent_repos(dependent_repos)
        if repos_list:
            payload["dependentRepos"] = repos_list
    # completion_listeners is treated as a presence signal: an empty
    # value means "do not change," while a populated value (even one
    # that parses to an empty list) means "clear the listener list."
    # We forward an empty list explicitly when the field is present
    # so the controller can distinguish "no change" (omitted) from
    # "set to empty" (passed).
    if completion_listeners:
        listeners_list = server._parse_completion_listeners(completion_listeners)
        payload["completionListeners"] = listeners_list
    # Use `is not None` so that an empty-dict clear signal ({}) is forwarded.
    if parsed_default_phase_config is not None:
        payload["defaultPhaseConfig"] = parsed_default_phase_config
    if parsed_phase_configs is not None:
        payload["phaseConfigs"] = parsed_phase_configs
    # dispatch_capable uses an Optional default so a caller that does
    # not pass the field ("no change") is distinguishable from a caller
    # that explicitly passes ``False`` ("revoke"). The controller's
    # ``JsonFieldExtractor.hasField`` check on the body side mirrors
    # this presence signal: when the field is omitted the workstream's
    # existing value is preserved; when the field is present the body
    # value (true or false) wins.
    if dispatch_capable is not None:
        payload["dispatchCapable"] = bool(dispatch_capable)
    # default_use_tmux follows the same Optional-presence pattern as
    # dispatch_capable: omitted = no change, ``False`` = clear the
    # workstream-level tmux opt-in, ``True`` = opt the workstream in.
    if default_use_tmux is not None:
        payload["defaultUseTmux"] = bool(default_use_tmux)

    if not payload:
        return {
            "ok": False,
            "error": "No fields to update. Provide at least one field.",
            "next_steps": [
                "Specify fields to update: default_branch, base_branch, "
                "repo_url, planning_document, channel_name, required_labels, "
                "dependent_repos, default_phase_config, or phase_configs",
            ],
        }

    result = server._controller_post(
        f"/api/workstreams/{quote(workstream_id, safe='')}/update",
        payload,
    )

    if result.get("ok"):
        result["next_steps"] = [
            "Use workstream_list to verify the updated configuration",
        ]
        if repo_url:
            result["next_steps"].append(
                "With repo_url set, pipeline tools (project_*) are now available"
            )
    else:
        result.setdefault("next_steps", [
            "Use workstream_list to verify the workstream_id is correct",
        ])

    return result
