"""
Task submission for the AR Manager MCP server.

Separated from the other workstream tools because submission is the one
that dispatches work rather than describing or arranging it, and because
it is large enough on its own to push a combined module past the
file-length cap.

Split from ``workstream_tools`` for length; the conventions are the same.
"""

from typing import Optional

import server
from server import mcp



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
    max_wall_clock_hours: Optional[int] = None,
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
        max_wall_clock_hours: Per-job ceiling on total elapsed wall-clock time,
            in hours, after which the controller stops launching further agent
            sessions for this job. Overrides the workstream's
            ``max_wall_clock_hours``, which in turn overrides the controller
            default of six hours. ``0`` disables the ceiling for this job.
            Leave unset (``None``, the default) to inherit. Must be ``0`` or
            greater — a negative value is rejected rather than forwarded,
            because the controller would read it as a negative duration and
            disable the ceiling without saying so. (``workstream_update_config``
            does accept a negative value, where it means "clear this
            workstream's override"; there is no equivalent meaning per job.)
            This is the ceiling
            that bounds a job which has stopped making progress rather than one
            that is doing too much: turn and dollar ceilings do not advance
            while a worker sits wedged on a network call.
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
    if max_wall_clock_hours is not None and max_wall_clock_hours < 0:
        return {
            "ok": False,
            "error": (
                "max_wall_clock_hours must be 0 or greater; got "
                f"{max_wall_clock_hours}. Use 0 to disable the ceiling for "
                "this job, or omit the parameter to inherit the workstream "
                "default. A negative value would reach the controller as a "
                "negative duration and disable the ceiling silently."
            ),
            "next_steps": [
                "Pass max_wall_clock_hours=0 to run this job without a ceiling",
                "Omit max_wall_clock_hours to inherit the workstream setting",
            ],
        }
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
    # Same presence semantics, and for the same reason: zero is a real value
    # here (it disables the ceiling), so only an explicitly supplied value is
    # forwarded. Omitting it lets the workstream default, then the controller
    # default, apply in turn.
    if max_wall_clock_hours is not None:
        payload["maxWallClockHours"] = int(max_wall_clock_hours)
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
