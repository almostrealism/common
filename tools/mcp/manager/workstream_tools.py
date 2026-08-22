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


@mcp.tool()
def workstream_list(include_archived: bool = False) -> dict:
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

    Use this to discover workstreams and determine which tools are
    available for each one.

    Args:
        include_archived: When ``False`` (default) archived workstreams
            are omitted from the response. Set ``True`` to include them;
            each archived entry carries ``archived=true``.

    Returns:
        Dictionary with list of workstream summaries.
    """
    server._require_scope("read")
    server._audit("workstream_list", include_archived=include_archived)
    path = "/api/workstreams"
    if include_archived:
        path += "?includeArchived=true"
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
def workstream_get_status(workstream_id: str, period: str = "weekly") -> dict:
    """Get aggregate job statistics for a workstream.

    Shows job counts, total time, cost, and turns for this week and last week.
    For per-job details use workstream_context.

    Args:
        workstream_id: The workstream identifier (from workstream_list).
        period: Reporting period. The controller currently supports only
            ``"weekly"`` — any other value is rejected up front. Defaults
            to ``"weekly"``.

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
def workstream_submit_task(
    prompt: str = "",
    job_type: str = "",
    command: str = "",
    workstream_id: str = "",
    target_branch: str = "",
    repo_url: str = "",
    create_workstream_if_missing: bool = False,
    description: str = "",
    max_turns: int = 0,
    max_budget_usd: float = 0.0,
    protect_test_files: bool = False,
    enforce_changes: bool = False,
    started_after: str = "",
    required_labels: str = "",
    deduplication_mode: str = "",
    max_deduplication_passes: int = 0,
    organizational_placement_enabled: bool = False,
    retrospective_enabled: bool = False,
    falsification_enabled: bool = False,
    use_tmux: Optional[bool] = None,
    sensitive_file_protection_enabled: bool = True,
    review_enabled: bool = True,
    max_review_passes: int = 0,
    post_completion_command: str = "",
    post_completion_timeout_seconds: int = 0,
    max_post_completion_passes: int = 0,
    delay_seconds: int = 0,
    default_phase_config: str = "",
    phase_configs: str = "",
    allow_commit_language: bool = False,
    # Removed legacy config parameters (model / effort / default_runner /
    # runners). Declared without type hints so they stay out of the tool's
    # declared parameter schema while still being captured here for a clear
    # rejection error — see _reject_removed_config_params.
    model="",
    effort="",
    default_runner="",
    runners="",
) -> dict:
    """Submit a coding task to a FlowTree agent.

    The task prompt is sent to an available agent, which will execute it
    using Claude Code. The agent inherits the workstream's configuration
    (branch, repo, environment, allowed tools).

    Two job types are supported:
    - The default coding-agent job runs Claude Code against the repository
      using ``prompt``.
    - A shell-command job (``job_type="shell"`` or simply providing
      ``command``) clones the repository (and optionally checks out a
      branch), runs a single shell command in that working directory, and
      publishes the command's stdout/stderr and exit code as a workstream
      message. It never commits or pushes, so it is suited to read-only
      commands such as running tests, builds, or inspection commands.

    You can resolve the target workstream by either:
    - Providing workstream_id explicitly
    - Providing target_branch (matched against registered workstreams)

    Args:
        prompt: The task description for the coding agent. Required for the
            default coding-agent job; ignored for a shell-command job. Be
            specific about what files to change, what behavior to implement,
            and any constraints.
        job_type: The job type to submit. Empty (default) or "coding" submits
            a coding-agent job; "shell" submits a shell-command job that runs
            ``command``. Providing ``command`` implies "shell".
        command: The shell command to run for a shell-command job. Required
            when job_type="shell" (or when used to imply a shell job); ignored
            for a coding-agent job.
        workstream_id: Explicit workstream to submit to (from workstream_list).
        target_branch: Git branch to resolve workstream by (alternative to
            workstream_id). Must be paired with ``repo_url`` when more than
            one registered workstream uses the same default branch on
            different repositories — otherwise the controller rejects the
            submission as ambiguous.
        repo_url: Repository URL used to disambiguate ``target_branch`` when
            several workstreams share the same branch name across different
            repositories. Optional when ``workstream_id`` is given or when
            ``target_branch`` is unique across all workstreams. Required with
            ``create_workstream_if_missing``. The form does not matter for
            lookup (SSH, HTTPS, and suffix-less URLs for one repository all
            match), but a workstream created from it is cloned from it, so
            pass the URL the agent can clone.
        create_workstream_if_missing: Register a workstream for
            ``target_branch`` and ``repo_url`` when none exists, instead of
            rejecting the submission. Use it for automated submissions on
            branches nobody registered by hand — a CI auto-resolve job, for
            instance. The result reports ``workstreamCreated`` when a
            workstream was created.
        description: Short human-readable description of the task (shown
            in Slack notifications).
        max_turns: Maximum Claude Code turns (0 = use workstream default).
        max_budget_usd: Maximum cost in USD (0 = use workstream default).
        protect_test_files: If true, prevent the agent from modifying test files.
        enforce_changes: If true, require the agent to produce code changes.
        started_after: Epoch milliseconds timestamp. If a newer job already
            exists on the workstream, the submission is skipped and the
            response includes ``skipped: true``. Used by CI pipelines to
            avoid stale auto-resolve jobs colliding with explicit submissions.
        required_labels: Node labels required to execute this job. Accepts
            either comma-separated key:value pairs (e.g.,
            "platform:macos,gpu:true") or a JSON object (e.g.,
            '{"platform": "macos", "gpu": "true"}'). Only Nodes with matching
            labels will execute the job.
        deduplication_mode: Post-work deduplication behaviour. Disabled by
            default (empty string leaves the server default of "none" in
            effect). Pass ``"local"`` to run an inline Claude Code session
            that removes duplicate methods before committing — safe for
            iterative testing, no extra jobs spawned. Use ``"spawn"`` to
            submit a separate follow-up job to the same workstream after
            committing (requires workstream URL). Recommended for final
            pre-merge cleanup: ``deduplication_mode="local"``.
        max_deduplication_passes: Maximum number of deduplication correction
            sessions per job. 0 (default) uses the server-side default of 2.
            Each pass runs a full agent session which adds time and cost; the
            cap lets you trade some thoroughness for predictable cost across
            multi-job workstreams where the audit re-runs from scratch on each
            job. Set to 1 for trivial follow-up jobs unlikely to introduce
            duplication. Set higher (e.g. 5) for first-time large feature work
            where thoroughness matters. Has no effect when deduplication is
            disabled.
        organizational_placement_enabled: When ``True``, activates the
            organizational placement rule after the primary phase. The agent
            is prompted to verify that any new files are placed at the correct
            level of the module hierarchy. Disabled by default to keep routine
            exploratory jobs cheaper. Enable for final pre-merge cleanup jobs
            where placement correctness matters.
        retrospective_enabled: When ``True``, activates the retrospective phase
            after all other phases. A separate agent session analyzes the
            primary phase transcript for tool-use and context-efficiency
            improvement opportunities, emitting findings as memories. The
            phase produces no code changes. Disabled by default. The
            recommended default model for this phase is ``claude-sonnet-4-7``
            or stronger, since analyzing a transcript benefits from strong
            reasoning. Configure via
            ``phase_configs='{"retrospective":{"model":"claude-sonnet-4-7"}}'``.
        falsification_enabled: When ``True``, activates the falsification phase
            after the primary session and before the enforcement rules. A
            separate agent session extracts the primary attempt's load-bearing
            behavioural claims and the captured evidence bearing on each; the
            controller then settles each claim mechanically and BOUNCES the job
            back to a fresh primary run when captured evidence refutes a claim
            (bounded by a bounce budget). v1 settles claims only from captured
            artifacts and source — it does not emit probes, so a claim that can
            only be settled by running something on an unavailable configuration
            is reported UNSETTLED rather than confirmed. Disabled by default.
            Configure the analysis model via
            ``phase_configs='{"falsification":{"model":"claude-sonnet-4-7"}}'``.
        use_tmux: Per-job override for tmux-backed launch, using presence
            semantics. When ``True``, the agent subprocess is launched inside a
            tmux session (a real controlling tty) instead of as a direct child
            process; when ``False``, it is forced to a direct launch even if the
            workstream's ``default_use_tmux`` is on. Leave unset (``None``, the
            default) to inherit the workstream default and the
            ``AR_AGENT_USE_TMUX`` environment variable. Because the value is
            only forwarded when explicitly set, an explicit ``False`` reaches
            the controller and overrides the workstream default. Falls back to a
            direct launch with a warning if ``tmux`` is not on the node's PATH.
        sensitive_file_protection_enabled: When ``True`` (the default), the
            per-job sensitive-file protections are active: harness-side
            test-file / CI-file staging is blocked, the ``TestHidingAudit``
            runs as part of ``validateChanges``, and no bypass trailer is
            appended to the commit message. Set to ``False`` ONLY for an
            operator-authorized job that legitimately needs to modify
            protected files (e.g. a job that intentionally edits a base-branch
            test or updates a policy validator). When ``False``, the controller
            pre-signs a per-job HMAC bypass token using ``AR_AGENT_BYPASS_SECRET``;
            the harness appends a ``Sensitive-File-Bypass`` trailer to the
            commit message after stripping any agent-supplied instance, and CI
            verifies the signature with the same secret. The agent cannot
            forge or substitute the bypass because it does not have access to
            the signing secret. This flag is operator-controlled at job
            submission time and is NEVER settable by the agent itself.
        review_enabled: When ``True`` (the default), a second-pass review
            session runs after the primary phase. The reviewer is told to
            make surgical fixes only when unambiguous and to defer
            anything substantial via a ``review-followup`` memory plus an
            inline ``TODO(review):`` code comment. Route the review phase
            to a cheaper runner (e.g. opencode against a local model)
            with ``phase_configs='{"review":{"runner":"opencode"}}'``. Set
            to ``False`` to skip the review phase entirely for this job.
        max_review_passes: Maximum number of review correction sessions per
            job. 0 (default) uses the server-side default of 1. The review
            rule is single-pass by design; raising this only matters if
            you want the same reviewer to see its own changes on a second
            pass, which is rarely useful.
        post_completion_command: Shell command run after the agent declares its
            work done. If the command exits non-zero, the agent receives a
            correction session showing the output and is asked to fix the
            failure. The loop continues until the command exits zero or max
            retries is exhausted. Examples:

            - Run a single test class:
              ``"mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest"``
            - Run a pytest file:
              ``"cd tools/mcp/manager && pytest tests/test_secrets.py"``
            - Run a custom script: ``"bash scripts/verify-foo.sh"``

            The command runs on the agent's host with the agent's privileges.
            It is NOT sandboxed — treat it like any other trusted instruction.
            Empty string (default) disables the feature.
        post_completion_timeout_seconds: Maximum seconds to wait for the
            post-completion command before killing it and treating the run as a
            failure. 0 (default) uses the server-side default of 1800 seconds
            (30 minutes).
        max_post_completion_passes: Maximum number of post-completion correction
            sessions per job. 0 (default) uses the server-side default of 3.
            Each pass runs a full agent session; without a cap a single flaky
            gate command can exhaust the entire context budget. Set to 1 for
            commands that should not be retried at all. Set higher (e.g. 5) when
            the gate is known to be flaky but eventually converges. Has no
            effect when ``post_completion_command`` is empty.
        delay_seconds: Number of seconds to wait before making the job visible
            to workers. The job is accepted immediately (and a job_id returned)
            but stays in a pending state until the delay elapses. Workers will
            not pick it up until then. Cancellation works normally during the
            pending period. 0 (default) means dispatch immediately.
        default_phase_config: Per-phase default configuration as a JSON object
            string with optional ``runner`` / ``model`` / ``effort`` /
            ``provider`` keys. Applies to every phase that does not have a
            dedicated entry in ``phase_configs``. Sets the job-level default
            ``PhaseConfig``. Empty (default) inherits the workstream-level
            default. Example::

                '{"runner": "opencode", "model": "qwen3-coder:exacto",
                  "effort": "medium", "provider": "openrouter"}'

        phase_configs: Per-phase configuration overrides as a JSON object
            whose keys are phase wire names (``"primary"``, ``"enforce-changes"``,
            ``"review"``, ``"deduplication"``, ``"organizational-placement"``,
            ``"maven-dependency-protection"``, ``"post-completion"``,
            ``"commit-message"``, ``"git-tampering-restart"``,
            ``"push-conflict-resolution"``, ``"retrospective"``,
            ``"falsification"``) and whose values are
            ``{runner, model, effort, provider}`` objects (all keys optional).
            Each named phase overrides ``default_phase_config``
            field-by-field. Example::

                '{"review": {"model": "claude-opus-4-7", "effort": "high"},
                  "commit-message": {"runner": "opencode"}}'

            Empty (default) inherits the workstream-level configuration.
            Unknown phase names are rejected client-side with a clear error.
        model: REMOVED. The legacy ``model`` parameter is no longer accepted;
            passing it fails with a 400-style error. Use
            ``default_phase_config`` or ``phase_configs`` to set models.
        effort: REMOVED. The legacy ``effort`` parameter is no longer
            accepted. Use ``default_phase_config`` or ``phase_configs``.
        default_runner: REMOVED. The legacy ``default_runner`` parameter is no
            longer accepted. Use ``default_phase_config='{"runner": "..."}'``.
        runners: REMOVED. The legacy ``runners`` map is no longer accepted.
            Use ``phase_configs`` (per-phase) or ``default_phase_config``.
        allow_commit_language: Escape hatch for the commit-language linter.
            By default (``False``), the prompt is scanned for phrases that
            imply the agent controls git commits (e.g. "Commit 1: do X",
            "commit this before starting") and the submission is rejected if
            any are found. Set to ``True`` only when the prompt legitimately
            contains such language (e.g. quoting an existing commit message or
            convention) and restructuring is not feasible. Most callers should
            restructure the prompt instead of opting out.

    Returns:
        Dictionary with job_id and workstream_id on success.
    """
    server._require_scope("submit")
    if job_type not in ("", "coding", "shell"):
        return {"ok": False,
                "error": f"Unknown job_type '{job_type}'; expected 'coding' or 'shell'"}
    shell_job = job_type == "shell" or bool(command)
    if shell_job:
        if not command:
            return {"ok": False,
                    "error": "command is required when job_type='shell'"}
        # A shell job ignores the prompt; blank it so the downstream
        # commit-language linter never fires on a value that is not used.
        prompt = ""
    elif not prompt:
        return {"ok": False, "error": "prompt is required"}
    err = server._check_length(command, "command", server.MAX_PROMPT_LEN)
    if err:
        return err
    err = server._check_length(prompt, "prompt", server.MAX_PROMPT_LEN)
    if err:
        return err
    err = server._check_length(post_completion_command, "post_completion_command", server.MAX_PROMPT_LEN)
    if err:
        return err
    err = server._reject_removed_config_params(
        model=model, effort=effort, default_runner=default_runner, runners=runners)
    if err:
        return err
    err = server._check_short_strings(
        workstream_id=workstream_id, target_branch=target_branch,
        repo_url=repo_url, description=description, started_after=started_after,
        deduplication_mode=deduplication_mode,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = server._parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = server._parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    # Commit-language linter -- rejects prompts that imply the agent controls
    # git commits (e.g. "Commit 1: do X, Commit 2: do Y").  Bypassed when the
    # caller explicitly opts out via allow_commit_language=True.
    if not allow_commit_language:
        linter_hits = server._lint_prompt_for_commit_sequencing(prompt)
        if linter_hits:
            lines = []
            for lineno, snippet, reason in linter_hits:
                lines.append(
                    "  Line {}: {}\n    > {}".format(lineno, reason, snippet))
            return {
                "ok": False,
                "error": (
                    "Prompt contains language implying the agent controls git commits. "
                    "The agent edits the working tree; the harness commits whatever's "
                    "there in a single commit at session end. The agent cannot sequence "
                    "commits, name them, or split work across multiple commits.\n\n"
                    "Forbidden phrases found:\n"
                    + "\n".join(lines)
                    + "\n\nRewrite the prompt to describe the final state of the working "
                    "tree, not a sequence of commits. If you are quoting a commit message "
                    "convention or have a legitimate use of this language, pass "
                    "allow_commit_language=True to bypass this check."
                ),
            }
    # Self-submission protection. When this tool is called from inside a
    # running ClaudeCodeJob (the agent has been issued a temporary token
    # whose payload binds it to a specific workstream), the agent must
    # never submit a job to its own workstream — two agent sessions on
    # the same git branch produce immediate commit collisions.
    #
    # The only safe place to enforce this is upfront, before the
    # controller submits the job. For target_branch resolution we'd need
    # to wait for the controller's response, by which point the job is
    # already running. Therefore agent callers are required to pass
    # workstream_id explicitly so the collision check is local.
    #
    # This check runs before _require_workstream_in_scope so the agent
    # gets a self-explanatory error rather than a generic permission
    # failure when workstream_id is empty.
    caller_workstream_id = server._get_token_workstream_id()
    if caller_workstream_id:
        if not workstream_id:
            return {
                "ok": False,
                "error": (
                    "workstream_id is required when workstream_submit_task "
                    "is called from inside a coding agent. target_branch "
                    "alone cannot be resolved here because the controller "
                    "would submit the job before a self-collision could "
                    "be detected. Call workstream_list to find another "
                    "workstream in your workspace and pass its workstream_id "
                    "explicitly."
                ),
                "next_steps": [
                    "Call workstream_list to enumerate workstreams in your workspace",
                    "Pick the workstream you intend to delegate work to",
                    "Re-call workstream_submit_task with that workstream_id",
                ],
            }
        if workstream_id == caller_workstream_id:
            return {
                "ok": False,
                "error": (
                    "Cannot submit a task to the calling workstream itself "
                    f"('{workstream_id}'). The current Claude Code session "
                    "is already running on this workstream's branch, so "
                    "submitting another job to it would cause two agents "
                    "to commit to the same branch concurrently and produce "
                    "immediate git collisions.\n\n"
                    "Jobs CAN be submitted to any OTHER workstream in the "
                    "same workspace — call workstream_list to see them. "
                    "Jobs CANNOT be submitted to the current workstream.\n\n"
                    "If a user has asked you to submit work for the current "
                    "workstream, this is almost certainly a misunderstanding: "
                    "they likely intended for the work to be done directly "
                    "in this Claude Code session, not delegated to a "
                    "separate job. Tell the user that work targeting the "
                    "current workstream should be performed in the running "
                    "session, then proceed with the work yourself."
                ),
                "next_steps": [
                    "Do the requested work directly in this session (no submission needed)",
                    "Or, if the user genuinely meant a different workstream, call workstream_list and submit using that workstream_id",
                ],
            }

    server._require_workstream_in_scope(workstream_id)
    server._audit("workstream_submit_task", workstream_id=workstream_id,
           target_branch=target_branch, repo_url=repo_url,
           create_workstream_if_missing=create_workstream_if_missing,
           prompt_len=len(prompt))

    # In-flight agent guard for sensitive-file protection. When this
    # tool is called from inside a running agent session (the caller
    # has a workstream-bound armt_tmp_ HMAC token), the agent must never
    # be allowed to forward `sensitiveFileProtectionEnabled=False` to
    # the controller. Doing so would cause the controller to compute a
    # controller-signed bypass HMAC for the new job, and the resulting
    # job's commit would be allowed to modify normally-protected files
    # (test files, CI/workflow files) on its target workstream. The
    # sensitive-file protection flag is therefore operator-only: only a
    # bearer without a workstream binding (an admin/operator) may opt
    # out. The default for `sensitive_file_protection_enabled` is True
    # anyway, so this is a no-op for callers that leave it at the
    # default. The check is placed BEFORE the payload is built so a
    # rejected call is never forwarded to the controller at all.
    if caller_workstream_id and not sensitive_file_protection_enabled:
        return {
            "ok": False,
            "error": (
                "sensitive_file_protection_enabled=False is not settable by an "
                "in-flight coding agent. The current Claude Code session is "
                f"bound to workstream '{caller_workstream_id}' via a workstream-"
                "scoped HMAC token; opting out of sensitive-file protection "
                "for a delegated job would let the agent self-authorise a "
                "controller-signed bypass HMAC for that other workstream. The "
                "flag is operator-only. Leave it at the default (True) and "
                "re-submit, or have an operator with admin scope disable the "
                "protection explicitly for the target workstream."
            ),
            "next_steps": [
                "Leave sensitive_file_protection_enabled at its default (True) and re-submit",
                "Or ask an operator to disable the protection out-of-band for the target workstream",
            ],
        }

    if shell_job:
        payload = {"jobType": "shell", "command": command}
    else:
        payload = {"prompt": prompt}
    if workstream_id:
        payload["workstreamId"] = workstream_id
    if target_branch:
        payload["targetBranch"] = target_branch
    if repo_url:
        payload["repoUrl"] = repo_url
    if create_workstream_if_missing:
        payload["createWorkstreamIfMissing"] = True
    if description:
        payload["description"] = description
    if max_turns > 0:
        payload["maxTurns"] = max_turns
    if max_budget_usd > 0:
        payload["maxBudgetUsd"] = max_budget_usd
    if protect_test_files:
        payload["protectTestFiles"] = True
    if enforce_changes:
        payload["enforceChanges"] = True
    if started_after:
        payload["startedAfter"] = started_after
    if required_labels:
        labels_dict = server._parse_required_labels(required_labels)
        if labels_dict:
            payload["requiredLabels"] = labels_dict
    if deduplication_mode:
        payload["deduplicationMode"] = deduplication_mode
    if max_deduplication_passes > 0:
        payload["maxDeduplicationPasses"] = max_deduplication_passes
    if organizational_placement_enabled:
        payload["enforceOrganizationalPlacement"] = True
    if retrospective_enabled:
        payload["retrospectiveEnabled"] = True
    if falsification_enabled:
        payload["falsificationEnabled"] = True
    # Presence semantics: forward useTmux only when explicitly set so an
    # explicit False reaches the controller and overrides the workstream
    # default (the controller distinguishes absent from false via hasField).
    if use_tmux is not None:
        payload["useTmux"] = bool(use_tmux)
    # sensitiveFileProtectionEnabled defaults to TRUE; forward only when the
    # operator has explicitly disabled it. Mirrors the inverted semantics of
    # the other activation booleans (which default to false and forward on true).
    # NOTE: the in-flight-agent rejection above ensures this branch is only
    # reached for non-agent callers (admin/operator with no workstream binding),
    # so the controller never mints a bypass HMAC at the request of an agent.
    if not sensitive_file_protection_enabled:
        payload["sensitiveFileProtectionEnabled"] = False
    if not review_enabled:
        payload["reviewEnabled"] = False
    if max_review_passes > 0:
        payload["maxReviewPasses"] = max_review_passes
    if post_completion_command:
        payload["postCompletionCommand"] = post_completion_command
    if post_completion_timeout_seconds > 0:
        payload["postCompletionTimeoutSeconds"] = post_completion_timeout_seconds
    if max_post_completion_passes > 0:
        payload["maxPostCompletionPasses"] = max_post_completion_passes
    if delay_seconds > 0:
        payload["delaySeconds"] = delay_seconds
    # Per-phase configuration. Forwarded under camelCase keys.
    if parsed_default_phase_config:
        payload["defaultPhaseConfig"] = parsed_default_phase_config
    if parsed_phase_configs:
        payload["phaseConfigs"] = parsed_phase_configs

    result = server._controller_post("/api/submit", payload)

    if result.get("ok"):
        job_id = result.get("jobId", "")
        ws_id = result.get("workstreamId", workstream_id)
        if result.get("workstreamCreated"):
            result["created_workstream"] = ws_id
        result["next_steps"] = [
            f"Use workstream_get_status with workstream_id='{ws_id}' to check progress",
            "The agent will push commits to the configured branch",
            "Use workstream_list to see all workstreams and branch info",
        ]
    else:
        result.setdefault("next_steps", [
            "Use workstream_list to find available workstreams and their IDs",
            "Ensure at least one agent is connected (check controller_health)",
        ])

    return result

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
