#!/usr/bin/env python3
"""
AR Manager MCP Server

Internet-facing MCP endpoint for managing FlowTree workstreams, submitting
coding tasks, triggering project workflows, and accessing agent memories.
Designed for naive clients (Claude mobile, other AI agents) that have no
repo checkout or CLAUDE.md context.

Architecture:
    - Tier 1 tools (universal): Delegate to FlowTree controller REST API
    - Tier 2 tools (pipeline): Call GitHub API directly for workflow dispatch
      and file commits
    - Tier 3 tools (memory): Access ar-memory HTTP service with LLM synthesis

Configuration via environment variables:
    AR_CONTROLLER_URL       - FlowTree controller base URL
                              (default: http://localhost:7780)
    AR_MANAGER_TOKEN_FILE   - Path to bearer token config file
                              (default: ~/.config/ar/manager-tokens.json)
    AR_MANAGER_TOKENS       - JSON string of token config (overrides file)
    AR_MEMORY_URL           - ar-memory HTTP server URL (auto-discovered if not set)
    MCP_TRANSPORT           - Transport: http (default) or sse. stdio is
                              refused; ar-manager runs only as an authenticated
                              HTTP server (no tokenless / stdio mode).
    MCP_PORT                - Port for http/sse transport (default: 8010)

GitHub authentication: ar-manager never holds a GitHub token itself. Every
GitHub API call routes through the FlowTree controller's ``/api/github/proxy``
endpoint, which resolves the per-org PAT from ``workstreams.yaml``. The
controller is reachable only on the private network and trusts ar-manager's
assertion of which org to use; ar-manager enforces the security model by
verifying the caller's ar-manager token is authorised for that org before
forwarding the request.
"""

import base64
import binascii
import contextvars
import hmac
import json
import logging
import os
import re
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

# ---------------------------------------------------------------------------
# Configuration — see config.py for all constants and environment-variable reads
# ---------------------------------------------------------------------------

from config import (
    CONTROLLER_URL,
    TOKEN_FILE,
    RATE_LIMIT,
    SHARED_SECRET,
    MAX_PROMPT_LEN,
    MAX_CONTENT_LEN,
    MAX_SHORT_STRING_LEN,
    WORKSPACE_CACHE_TTL,
    _SENSITIVE_PATH_PREFIXES,
    audit_log,
)

# ---------------------------------------------------------------------------
# Shared libraries (memory + inference)
# ---------------------------------------------------------------------------

_COMMON_DIR = os.path.join(os.path.dirname(__file__), "..", "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)

_memory_client = None
_memory_init_failed = False
_llm_backend = None
_init_lock = threading.Lock()


def _get_memory_client():
    """Lazy-initialize the MemoryHTTPClient with graceful degradation."""
    global _memory_client, _memory_init_failed
    if _memory_client is not None:
        return _memory_client
    if _memory_init_failed:
        return None
    with _init_lock:
        if _memory_client is not None:
            return _memory_client
        if _memory_init_failed:
            return None
        try:
            from memory_http_client import MemoryHTTPClient
            _memory_client = MemoryHTTPClient()
            print(f"ar-manager: Connected to ar-memory at {_memory_client.base_url}",
                  file=sys.stderr)
            return _memory_client
        except (ConnectionError, ImportError) as e:
            print(f"ar-manager: ar-memory not available: {e}. Memory tools disabled.",
                  file=sys.stderr)
            _memory_init_failed = True
            return None


def _get_llm():
    """Lazy-initialize the LLM inference backend."""
    global _llm_backend
    if _llm_backend is not None:
        return _llm_backend
    with _init_lock:
        if _llm_backend is not None:
            return _llm_backend
        try:
            from inference import create_backend
            _llm_backend = create_backend()
            print(f"ar-manager: LLM backend: {_llm_backend.name}", file=sys.stderr)
            return _llm_backend
        except ImportError as e:
            print(f"ar-manager: LLM inference not available: {e}", file=sys.stderr)
            return None


# Log startup configuration to stderr for diagnostics
print(f"ar-manager: AR_CONTROLLER_URL={CONTROLLER_URL}", file=sys.stderr)
print(f"ar-manager: AR_MANAGER_SHARED_SECRET={'<set>' if SHARED_SECRET else '<not set>'}",
      file=sys.stderr)

# ---------------------------------------------------------------------------
# Authentication, scoping, and ASGI middleware — see auth.py
# ---------------------------------------------------------------------------

from auth import (
    _request_scopes,
    _request_token_label,
    _request_workstream_id,
    _request_job_id,
    _request_workspace_scopes,
    _thread_local,
    _get_scopes,
    _get_token_label,
    _set_scopes,
    _get_workspace_scopes,
    _set_workspace_scopes,
    _is_workspace_allowed,
    _require_workspace,
    _decode_current_request_token_full,
    _decode_current_request_token,
    _get_token_workstream_id,
    _get_token_job_id,
    _set_token_context,
    _validate_temp_token,
    _mint_temp_token,
    _require_scope,
    _audit,
    _check_length,
    _check_short_strings,
    _load_tokens,
    BearerAuthMiddleware,
    RateLimitMiddleware,
    HealthMiddleware,
)

# ---------------------------------------------------------------------------
# Controller and tracker HTTP helpers — see controller_client.py / tracker_client.py
# ---------------------------------------------------------------------------

from controller_client import _controller_get, _controller_post
from tracker_client import (
    _tracker_headers,
    _tracker_get,
    _tracker_post,
    _tracker_put,
    _tracker_delete,
)



# ---------------------------------------------------------------------------
# Workspace scope resolution — see workspace_map.py
# ---------------------------------------------------------------------------

from workspace_map import (
    _workspace_map_cache,
    _workspace_map_lock,
    _build_maps_from_workstreams,
    _refresh_workspace_map,
    _get_cached_maps,
    _workspace_for_workstream,
    _is_multi_workspace_mode,
    _workspaces_for_org,
    _require_org_in_scope,
    _require_workstream_in_scope,
    _dispatch_capable_cache,
    _dispatch_capable_lock,
    _refresh_dispatch_capable_ids,
    _get_dispatch_capable_ids,
    _require_dispatch_capable,
    _filter_workstreams_by_scope,
    _filter_tasks_by_scope,
    _pipeline_error,
    _find_workstream,
)

# ---------------------------------------------------------------------------
# GitHub API helpers — delegated to github_api.py to reduce file size
# ---------------------------------------------------------------------------

import github_api  # noqa: E402

# Re-export so existing call sites (pipeline tools, memory tools, tests) work unchanged.
_github_request = github_api._github_request
_github_graphql_request = github_api._github_graphql_request
_set_github_org = github_api._set_github_org
_extract_owner_repo = github_api._extract_owner_repo
_current_github_org = github_api._current_github_org
_resolve_github_repo = github_api._resolve_github_repo

# Now that _find_workstream is defined, configure the GitHub API module.
# ar-manager deliberately does not hold a GitHub token; all requests route
# through the controller's proxy, which resolves the per-org PAT from
# workstreams.yaml.
github_api.configure(
    controller_url=CONTROLLER_URL,
    find_workstream=_find_workstream,
    get_token_workstream_id=_get_token_workstream_id,
)


# ---------------------------------------------------------------------------
# MCP server and tool definitions
# TODO(review): server.py is 5578 lines — still well above the 1500-line soft limit after
# the prior session's extraction of auth/config/controller_client/tracker_client/workspace_map.
# Next extraction targets: github_tools.py (~900 lines), tracker_tools.py (~300 lines),
# workstream_tools.py (~600 lines), memory_tools.py (~300 lines). KEY CONSTRAINT:
# McpToolDiscoveryTest.java scans server.py for @mcp.tool() — the scanner must be updated
# (or taught to scan multiple files) before tool functions can move. See review-followup
# memory workstream:98600d20-225d-488c-ad3b-cfa4a1e547aa for the full analysis.
# ---------------------------------------------------------------------------

from mcp.server.fastmcp import FastMCP

mcp = FastMCP("ar-manager")


# -- Tier 1: Universal tools -----------------------------------------------

@mcp.tool()
def controller_health() -> dict:
    """Check whether the FlowTree controller is alive and responding.

    Use this as a first step to verify connectivity before calling
    other tools. No authentication scope required.

    The response includes a ``server_time`` field containing the
    controller's current UTC time in ISO-8601 format
    (e.g. ``"2026-05-11T18:23:45.123456789Z"``). This is useful for
    verifying which deployment is running and for diagnosing clock
    drift between the controller host and other systems.

    Returns:
        Dictionary with controller status, version info, and
        ``server_time`` (ISO-8601 UTC timestamp from the controller).
    """
    _require_scope("read")
    _audit("controller_health")
    result = _controller_get("/api/health")
    result["next_steps"] = [
        "Use workstream_list to see available workstreams",
    ]
    return result


@mcp.tool()
def agent_options() -> dict:
    """Return available agent runners, phases, model names, and the default runner.

    Queries the controller's ``/api/agents`` endpoint and returns the
    complete set of options needed to configure per-workstream or per-job
    runner selection.

    Use this tool before calling ``workstream_register``,
    ``workstream_update_config``, or ``workstream_submit_task`` with
    ``runners`` or ``default_runner`` parameters to discover valid runner
    names and phase wire names.

    Returns:
        Dictionary with:
        - ``ok``: ``True`` on a successful read.
        - ``runners``: list of runner objects, each with ``name`` and
          ``capabilities`` (boolean flags + ``supportedModels`` list).
        - ``phases``: list of phase objects, each with ``name`` (the phase
          wire identifier) and ``description``. The ``name`` values are
          the valid keys for the ``runners`` JSON object accepted by
          submit/register/update tools.
        - ``models``: list of accepted model identifiers (aliases and full
          IDs) that may be passed as the ``model`` parameter.
        - ``defaultRunner``: the built-in fallback runner name (``"claude"``).
    """
    _require_scope("read")
    _audit("agent_options")
    return _controller_get("/api/agents")


@mcp.tool()
def controller_update_config(
    accept_automated_jobs: str = "",
) -> dict:
    """Get or update the FlowTree controller's runtime configuration.

    Currently supports toggling whether automated job submissions (e.g.,
    from CI pipelines) are accepted. When automated jobs are disabled,
    submissions with ``automated: true`` in the payload are rejected.
    This prevents infinite loops where CI submits work to an agent which
    then triggers CI again.

    Call with no arguments to read the current setting. Provide
    ``accept_automated_jobs`` to change it.

    Args:
        accept_automated_jobs: Set to ``true`` to accept automated job
            submissions (the default) or ``false`` to reject them.
            Leave empty to read the current setting.

    Returns:
        Dictionary with the current ``acceptAutomatedJobs`` setting.
    """
    _require_scope("write")
    # Global controller config is superadmin-only: workspace-scoped tokens
    # cannot flip this switch because its effect is global across workspaces.
    if _get_workspace_scopes():
        raise PermissionError(
            "controller_update_config requires an unscoped (superadmin) token"
        )
    _audit("controller_update_config", accept_automated_jobs=accept_automated_jobs)

    if accept_automated_jobs:
        accept = accept_automated_jobs.lower() == "true"
        result = _controller_post(
            "/api/config/accept-automated-jobs",
            {"accept": accept},
        )
    else:
        result = _controller_get("/api/config/accept-automated-jobs")

    result.setdefault("next_steps", [
        "Use workstream_submit_task to submit a coding task",
        "Use controller_health to check controller status",
    ])
    return result


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
    _require_scope("read")
    _audit("workstream_list", include_archived=include_archived)
    path = "/api/workstreams"
    if include_archived:
        path += "?includeArchived=true"
    result = _controller_get(path)

    if isinstance(result, list):
        entries = _filter_workstreams_by_scope(result)
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
    _require_scope("read")
    err = _check_short_strings(workstream_id=workstream_id, period=period)
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
    _audit("workstream_get_status", workstream_id=workstream_id)
    _require_workstream_in_scope(workstream_id)
    params = urlencode({"workstream": workstream_id, "period": period})
    result = _controller_get(f"/api/stats?{params}")
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
    _require_scope("read")
    err = _check_short_strings(job_id=job_id)
    if err:
        return err
    _audit("workstream_get_job", job_id=job_id)
    result = _controller_get(f"/api/jobs/{job_id}")
    # Scope check: a scoped token may only see jobs belonging to a workstream
    # in its workspace scope. The job event does not itself carry a workspace
    # ID, so we resolve via the workstream → workspace mapping. Unknown jobs
    # are returned unchanged for unscoped callers and suppressed as 404 for
    # scoped callers to avoid leaking existence.
    if _get_workspace_scopes():
        ws_id = result.get("workstreamId") if isinstance(result, dict) else None
        if not ws_id or not _is_workspace_allowed(_workspace_for_workstream(ws_id)):
            return {"ok": False, "error": "Job not found"}
    return result


# Phase configuration parsing, validation, and clearing semantics are in
# phase_config.py — see that module for implementation details and the
# clearing-semantics contract (empty dict vs None return values).
from phase_config import (  # noqa: E402
    VALID_EFFORT_LEVELS,
    _KNOWN_PHASE_WIRE_NAMES,
    _KNOWN_RUNNER_NAMES,
    _parse_default_phase_config_json,
    _parse_phase_configs_json,
    _validate_phase_config_field,
    _REMOVED_CONFIG_PARAM_HINT,
    _reject_removed_config_params,
)


def _parse_required_labels(required_labels: str) -> dict:
    """Parse a labels specification into a labels dict.

    Accepts two input shapes:

    - Comma-separated ``key:value`` pairs (the documented CSV form), e.g.
      ``platform:macos,gpu:true``.
    - A JSON object string, e.g. ``{"platform": "macos", "gpu": "true"}``.

    A leading ``{`` is treated as JSON-object intent: the value is parsed with
    the JSON decoder and never falls through to the CSV splitter. That fallthrough
    is exactly what corrupts ``workstreams.yaml`` — the splitter would break a
    JSON string on its first colon into a mangled single-entry map such as
    ``{'{"platform"': '"macos"}'}``. Malformed JSON (or a non-object) therefore
    yields an empty map rather than a mangled one. Non-string JSON values are
    coerced to strings.

    Only pairs with a non-empty key and non-empty value are included. Pairs
    missing a colon or with an empty key/value are silently ignored.
    """
    stripped = required_labels.strip()
    if stripped.startswith("{"):
        import json as _json
        try:
            parsed = _json.loads(stripped)
        except ValueError:
            return {}
        if not isinstance(parsed, dict):
            return {}
        result = {}
        for key, value in parsed.items():
            key_str = str(key).strip()
            if isinstance(value, bool):
                value_str = "true" if value else "false"
            else:
                value_str = str(value).strip()
            if key_str and value_str:
                result[key_str] = value_str
        return result
    result = {}
    for pair in stripped.split(","):
        parts = pair.strip().split(":", 1)
        if len(parts) == 2 and parts[0].strip() and parts[1].strip():
            result[parts[0].strip()] = parts[1].strip()
    return result


def _parse_dependent_repos(dependent_repos: str) -> list:
    """Parse a comma-separated list of repo URLs into a Python list.

    Also accepts a JSON array string (e.g. '["url1","url2"]').
    Empty entries are dropped. Returns an empty list if the input is empty.
    """
    if not dependent_repos:
        return []
    stripped = dependent_repos.strip()
    if stripped.startswith("["):
        import json as _json
        try:
            parsed = _json.loads(stripped)
            if isinstance(parsed, list):
                return [str(r).strip() for r in parsed if str(r).strip()]
        except ValueError:
            pass
    return [r.strip() for r in stripped.split(",") if r.strip()]


def _parse_completion_listeners(completion_listeners) -> list:
    """Parse the completion_listeners parameter into a list of workstream IDs.

    Accepts the same shapes as dependent_repos: a comma-separated string
    (e.g. ``"ws-orchestrator"``) or a JSON array string
    (e.g. ``'["ws-orchestrator"]'``). Empty entries are dropped so a
    stray ``",  ,"`` does not become a phantom listener. Returns an
    empty list when the input is empty or ``None``; an empty list is
    the inert default and produces no wake-up fan-out.
    """
    if completion_listeners is None:
        return []
    if isinstance(completion_listeners, list):
        return [str(s).strip() for s in completion_listeners if str(s).strip()]
    if not isinstance(completion_listeners, str):
        completion_listeners = str(completion_listeners)
    stripped = completion_listeners.strip()
    if not stripped:
        return []
    if stripped.startswith("["):
        import json as _json
        try:
            parsed = _json.loads(stripped)
            if isinstance(parsed, list):
                return [str(s).strip() for s in parsed if str(s).strip()]
        except ValueError:
            pass
    return [s.strip() for s in stripped.split(",") if s.strip()]


def _parse_activities_param(include_activities) -> str:
    """Normalize include_activities to a comma-separated string.

    Accepts a native Python list, a JSON-array string, or a plain comma-separated
    string.  Returns a normalised comma-separated string (e.g. ``"primary"``).
    """
    if isinstance(include_activities, list):
        joined = ",".join(str(v).strip() for v in include_activities if str(v).strip())
        return joined or "primary"
    if not include_activities:
        return "primary"
    stripped = include_activities.strip()
    if stripped.startswith("["):
        import json as _json
        try:
            parsed = _json.loads(stripped)
            if isinstance(parsed, list):
                joined = ",".join(str(v).strip() for v in parsed if str(v).strip())
                return joined or "primary"
        except ValueError:
            pass
    return stripped or "primary"


# ---------------------------------------------------------------------------
# Commit-language linter for workstream_submit_task
# ---------------------------------------------------------------------------

# Each entry is (compiled_pattern, human_readable_reason).  Patterns are
# checked case-insensitively against each line of the submitted prompt.
# re is already imported at the top of this file.
_COMMIT_SEQUENCING_PATTERNS = [
    (re.compile(r"\bcommit\s+\d+\b", re.IGNORECASE),
     "commit-number phrase (e.g. \"Commit 1\", \"commit 2\")"),
    (re.compile(r"\bfirst\s+commit\b", re.IGNORECASE),
     '"first commit" phrase'),
    (re.compile(r"\bnext\s+commit\b", re.IGNORECASE),
     '"next commit" phrase'),
    (re.compile(r"\bfinal\s+commit\b", re.IGNORECASE),
     '"final commit" phrase'),
    (re.compile(r"\bas\s+(?:its\s+own|separate|individual)\s+commits?\b", re.IGNORECASE),
     '"as separate/individual commits" phrase'),
    (re.compile(r"\b(?:in|across|over)\s+\d+\s+commits?\b", re.IGNORECASE),
     '"in/across/over N commits" phrase'),
    (re.compile(
        r"\b(?:your|the)\s+commit\s+message\s+(?:should|will|must)\b", re.IGNORECASE),
     '"commit message should/will/must" phrase'),
    (re.compile(
        r"\bcommit\s+(?:this|that|each|the)\s+(?:as|with|before)\b", re.IGNORECASE),
     '"commit this/that/each/the as/with/before" phrase'),
    (re.compile(
        r"\bcommit\s+(?:between|after|before)\s+(?:each|every)\b", re.IGNORECASE),
     '"commit between/after/before each/every" phrase'),
]

# Minimum prompt length below which the linter is skipped (false-positive
# ratio is too high on very short strings and they almost never contain the
# multi-word phrases we are looking for).
_COMMIT_LINTER_MIN_LEN = 50


def _lint_prompt_for_commit_sequencing(prompt: str) -> list:
    """Scan ``prompt`` for forbidden commit-sequencing phrases.

    Returns a list of ``(line_number, snippet, reason)`` tuples — one per
    matched line (first matching pattern wins per line).  Returns an empty
    list when the prompt is short (< 50 chars) or contains no matches.

    This function is pure (no I/O) and intentionally best-effort: it may
    produce false positives for prompts that quote commit messages or use
    the word "commit" in an unrelated context.  Callers that need to bypass
    the check should pass ``allow_commit_language=True`` to
    ``workstream_submit_task``.
    """
    if len(prompt) < _COMMIT_LINTER_MIN_LEN:
        return []
    violations = []
    for lineno, line in enumerate(prompt.splitlines(), 1):
        for pattern, reason in _COMMIT_SEQUENCING_PATTERNS:
            if pattern.search(line):
                snippet = line.strip()[:120]
                violations.append((lineno, snippet, reason))
                break  # one violation entry per line, first pattern wins
    return violations


@mcp.tool()
def workstream_submit_task(
    prompt: str = "",
    job_type: str = "",
    command: str = "",
    workstream_id: str = "",
    target_branch: str = "",
    repo_url: str = "",
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
            ``target_branch`` is unique across all workstreams.
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
    _require_scope("submit")
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
    err = _check_length(command, "command", MAX_PROMPT_LEN)
    if err:
        return err
    err = _check_length(prompt, "prompt", MAX_PROMPT_LEN)
    if err:
        return err
    err = _check_length(post_completion_command, "post_completion_command", MAX_PROMPT_LEN)
    if err:
        return err
    err = _reject_removed_config_params(
        model=model, effort=effort, default_runner=default_runner, runners=runners)
    if err:
        return err
    err = _check_short_strings(
        workstream_id=workstream_id, target_branch=target_branch,
        repo_url=repo_url, description=description, started_after=started_after,
        deduplication_mode=deduplication_mode,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = _parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = _parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    # Commit-language linter -- rejects prompts that imply the agent controls
    # git commits (e.g. "Commit 1: do X, Commit 2: do Y").  Bypassed when the
    # caller explicitly opts out via allow_commit_language=True.
    if not allow_commit_language:
        linter_hits = _lint_prompt_for_commit_sequencing(prompt)
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
    caller_workstream_id = _get_token_workstream_id()
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

    _require_workstream_in_scope(workstream_id)
    _audit("workstream_submit_task", workstream_id=workstream_id,
           target_branch=target_branch, repo_url=repo_url,
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
        labels_dict = _parse_required_labels(required_labels)
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

    result = _controller_post("/api/submit", payload)

    if result.get("ok"):
        job_id = result.get("jobId", "")
        ws_id = result.get("workstreamId", workstream_id)
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
            paths.
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
    _require_scope("write")
    # Controller-side enforcement: a job-scoped agent on a workstream
    # that is not dispatch-capable cannot register workstreams. The
    # harness-CSV half of the contract is in
    # ``McpConfigBuilder.buildAllowedTools``; this is the backstop
    # for the opencode harness's per-SERVER filtering (see
    # ``OpencodeConfigBuilder.translateAllowlist``). Admin / operator
    # callers (no caller workstream bound) are always permitted.
    _require_dispatch_capable()
    # slack_workspace_id is the legacy name; the new canonical name is
    # workspace_id. Accept either, preferring the new name.
    if not workspace_id and slack_workspace_id:
        audit_log.debug("workstream_register: slack_workspace_id is a "
                        "deprecated alias for workspace_id")
        workspace_id = slack_workspace_id
    err = _reject_removed_config_params(
        model=model, effort=effort, default_runner=default_runner, runners=runners)
    if err:
        return err
    err = _check_short_strings(
        default_branch=default_branch, base_branch=base_branch,
        repo_url=repo_url, planning_document=planning_document,
        channel_name=channel_name, workspace_id=workspace_id,
        plan_path=plan_path, plan_commit_message=plan_commit_message,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = _parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = _parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    # plan_content and plan_instructions describe two different follow-up
    # actions; the caller must pick one. Reject ambiguous requests up front.
    if plan_content and plan_instructions:
        return {
            "ok": False,
            "error": "plan_content and plan_instructions are mutually exclusive",
        }
    err = _check_length(plan_content, "plan_content", MAX_CONTENT_LEN)
    if err:
        return err
    err = _check_length(plan_instructions, "plan_instructions", MAX_CONTENT_LEN)
    if err:
        return err
    # Scope enforcement: scoped callers must name a workspace they own.
    # An explicit workspace_id wins. Otherwise we refuse rather than
    # rely on the controller's repoUrl-derivation path, because allowing
    # the caller to rely on controller-side derivation would open a
    # scope-bypass if repoUrl is omitted or spoofed.
    if _get_workspace_scopes():
        if workspace_id:
            _require_workspace(workspace_id)
        else:
            raise PermissionError(
                "Scoped tokens must pass workspace_id when registering "
                "a workstream — repoUrl-based derivation is only available "
                "to unscoped (superadmin) tokens."
            )
    _audit("workstream_register", default_branch=default_branch,
           workspace_id=workspace_id)

    payload = {"defaultBranch": default_branch}
    if base_branch:
        payload["baseBranch"] = base_branch
    if repo_url:
        payload["repoUrl"] = repo_url
    if planning_document:
        payload["planningDocument"] = planning_document
    if channel_name:
        payload["channelName"] = channel_name
    if workspace_id:
        # Send both names: the controller accepts either, and the legacy
        # field name is kept so older controllers without the rename
        # continue to honour the registration.
        payload["workspaceId"] = workspace_id
        payload["slackWorkspaceId"] = workspace_id
    if required_labels:
        labels_map = _parse_required_labels(required_labels)
        if labels_map:
            payload["requiredLabels"] = labels_map
    if dependent_repos:
        repos_list = _parse_dependent_repos(dependent_repos)
        if repos_list:
            payload["dependentRepos"] = repos_list
    if completion_listeners:
        listeners_list = _parse_completion_listeners(completion_listeners)
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

    result = _controller_post("/api/workstreams", payload)

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
            result["plan"] = _attempt_plan_commit(
                ws_id, plan_content, plan_path, plan_commit_message)
            if result["plan"].get("mode") == "committed":
                steps.append(
                    f"Plan committed at {result['plan'].get('path')}")
            else:
                steps.append(
                    "Plan commit failed — see result.plan.fallback_instructions")
        elif plan_instructions:
            result["plan"] = _attempt_plan_writing_job(
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


def _attempt_plan_commit(workstream_id: str, content: str, path: str,
                         commit_message: str) -> dict:
    """Attempt an immediate plan-document commit for a just-registered
    workstream via the existing :func:`project_commit_plan` tool.

    Wraps the call in a try-block so any failure (missing pipeline scope,
    GitHub permission denied, branch protection, network) is reported
    structurally via the ``mode="failed"`` shape. Registration itself
    remains successful in the caller regardless.
    """
    try:
        commit_result = project_commit_plan(
            workstream_id=workstream_id,
            content=content,
            path=path or "",
            branch="",
            commit_message=commit_message or "",
        )
    except PermissionError as e:
        return _plan_failed("insufficient_scope", str(e),
                            "Direct plan commits require the 'pipeline' scope. "
                            "Use workstream_submit_task with a prompt asking the "
                            "agent to write the plan document, or ask the operator "
                            "for a token with pipeline scope.")
    except Exception as e:  # defensive — any unexpected error
        return _plan_failed("internal_error", str(e),
                            "An unexpected error occurred. The workstream is "
                            "registered; retry via project_commit_plan or "
                            "workstream_submit_task.")

    if commit_result.get("ok"):
        return {
            "mode": "committed",
            "path": commit_result.get("path"),
            "branch": commit_result.get("branch"),
            "commit_sha": commit_result.get("commit_sha"),
            "repo": commit_result.get("repo"),
        }

    return _plan_failed(
        "commit_rejected",
        commit_result.get("error", "Unknown commit failure"),
        "The GitHub API rejected the direct commit — most commonly this means "
        "the token does not have 'contents:write' on this repo, the branch is "
        "protected, or the repo_url is misconfigured. The workstream is "
        "registered; call workstream_submit_task with a prompt asking the agent "
        "to write and commit the plan document instead.")


def _attempt_plan_writing_job(workstream_id: str, instructions: str,
                              path: str) -> dict:
    """Attempt to submit a job that writes a plan document based on natural-
    language instructions, for a just-registered workstream.

    The prompt nudges the agent toward committing the plan file at a known
    path so downstream tools (like ``project_read_plan``) can find it
    without additional configuration.
    """
    target_path = path or "docs/plans/<slug>.md (choose an appropriate filename)"
    prompt = (
        "Write a planning document for this workstream at the target path. "
        "Path: " + target_path + "\n\n"
        "The document should describe, in the style of other documents under "
        "docs/plans/, the following intent supplied by the operator:\n\n"
        "--- BEGIN INSTRUCTIONS ---\n"
        + instructions +
        "\n--- END INSTRUCTIONS ---\n\n"
        "Write the file and leave it uncommitted — the harness will commit it "
        "after you finish. Do not run `git commit` yourself, and do not make "
        "any other code changes in this session."
    )
    try:
        submit_result = workstream_submit_task(
            workstream_id=workstream_id,
            prompt=prompt,
            description="Write planning document",
        )
    except PermissionError as e:
        return _plan_failed("insufficient_scope", str(e),
                            "Submitting a plan-writing job requires the 'write' "
                            "scope. Ask the operator for a token with write scope.")
    except Exception as e:  # defensive
        return _plan_failed("internal_error", str(e),
                            "An unexpected error occurred while submitting the "
                            "plan-writing job. The workstream is registered; "
                            "retry via workstream_submit_task.")

    if submit_result.get("ok"):
        return {
            "mode": "submitted",
            "job_id": submit_result.get("jobId"),
            "path_hint": path or None,
        }

    return _plan_failed(
        "submit_rejected",
        submit_result.get("error", "Unknown submit failure"),
        "The controller rejected the task submission — usually because no "
        "agents are connected. The workstream is registered; retry "
        "workstream_submit_task once an agent is available.")


def _plan_failed(reason: str, error: str, fallback_instructions: str) -> dict:
    """Build the structured failure payload attached to
    :func:`workstream_register`'s ``plan`` field. Kept as a helper so the
    two follow-up paths return the same shape."""
    return {
        "mode": "failed",
        "reason": reason,
        "error": error,
        "fallback_instructions": fallback_instructions,
    }


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
    _require_scope("write")
    # Controller-side enforcement: a job-scoped agent on a workstream
    # that is not dispatch-capable cannot update workstreams. The
    # harness-CSV half of the contract is in
    # ``McpConfigBuilder.buildAllowedTools``; this is the backstop
    # for the opencode harness's per-SERVER filtering. Admin / operator
    # callers (no caller workstream bound) are always permitted.
    _require_dispatch_capable()
    err = _reject_removed_config_params(
        model=model, effort=effort, default_runner=default_runner, runners=runners)
    if err:
        return err
    err = _check_short_strings(
        workstream_id=workstream_id, default_branch=default_branch,
        base_branch=base_branch, repo_url=repo_url,
        planning_document=planning_document, channel_name=channel_name,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = _parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = _parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    _require_workstream_in_scope(workstream_id)
    _audit("workstream_update_config", workstream_id=workstream_id)

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
        labels_map = _parse_required_labels(required_labels)
        if labels_map:
            payload["requiredLabels"] = labels_map
    if dependent_repos:
        repos_list = _parse_dependent_repos(dependent_repos)
        if repos_list:
            payload["dependentRepos"] = repos_list
    # completion_listeners is treated as a presence signal: an empty
    # value means "do not change," while a populated value (even one
    # that parses to an empty list) means "clear the listener list."
    # We forward an empty list explicitly when the field is present
    # so the controller can distinguish "no change" (omitted) from
    # "set to empty" (passed).
    if completion_listeners:
        listeners_list = _parse_completion_listeners(completion_listeners)
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

    result = _controller_post(
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


# Sentinel for "argument not supplied" on workspace_update_config
# parameters whose empty-string value carries meaning distinct from
# absence (e.g. ``slack_team_id=""`` explicitly clears the Slack
# connection, while omitting ``slack_team_id`` leaves it unchanged).
_WORKSPACE_UNSET = "\0__workspace_unset__\0"


@mcp.tool()
def workspace_update_config(
    workspace_id: str = "",
    default_phase_config: str = "",
    phase_configs: str = "",
    name: str = "",
    default_channel: str = "",
    new_id: str = "",
    slack_team_id: str = _WORKSPACE_UNSET,
    slack_workspace_id: str = "",
    # Removed legacy config parameters — see _reject_removed_config_params.
    # Untyped so they stay out of the declared tool schema while still being
    # captured here for a clear rejection error.
    default_runner="",
    runners="",
) -> dict:
    """Update workspace-level configuration on a workspace entry.

    A workspace is the operator's organisational unit. Its ``id`` is
    operator-chosen and independent of any Slack team ID; when a Slack
    connection is configured the team ID lives on the ``slackTeamId``
    field. This tool can rename a workspace, retarget its Slack
    connection, and update non-credential operational fields.

    Only the fields you supply are written; an empty string leaves the
    corresponding field unchanged (except ``slack_team_id``, where an
    explicit empty string clears the Slack connection — see below).
    Changes are persisted back to the workstreams YAML so they survive a
    controller restart.

    For security, the following workspace fields are **NOT** settable via
    this tool and must be edited in the YAML directly:

    * ``tokensFile``, ``botToken``, ``appToken`` — Slack credentials.
    * ``githubOrgs`` — controls which GitHub orgs the workspace can
      issue tokens for.
    * ``channelOwnerUserId`` / ``channelOwnerUserIds`` — administrative
      auto-invite ownership.

    Args:
        workspace_id: Operator-chosen workspace identifier (e.g.
            ``"almostrealism"``) of the workspace to update. For
            workspaces migrated from a legacy ``slackWorkspaces:`` YAML
            entry this is the Slack team ID until the workspace is
            renamed via ``new_id``. Required.
        default_phase_config: New workspace-level default configuration as a
            JSON object with optional ``runner`` / ``model`` / ``effort`` /
            ``provider`` keys. Pass ``'{}'`` to clear the stored default.
            Empty string leaves it unchanged. Applied to workstreams in this
            workspace when neither the workstream nor the per-job override
            sets a value. Use ``agent_options`` to discover valid runner
            names. Example::

                '{"runner": "opencode", "model": "qwen3-coder:exacto",
                  "effort": "medium", "provider": "openrouter"}'

        phase_configs: New workspace-level per-phase overrides as a JSON
            object whose keys are phase wire names and whose values are
            ``{runner, model, effort, provider}`` objects (all keys optional).
            Pass ``'{}'`` to clear all per-phase overrides. Set a phase value
            to ``null`` (e.g. ``'{"review": null}'``) to clear just that
            phase's override. Empty string leaves the per-phase map unchanged.
            Each named phase overrides ``default_phase_config`` field-by-field.
        default_runner: REMOVED. The legacy ``default_runner`` parameter is no
            longer accepted; passing it fails with a 400-style error. Use
            ``default_phase_config='{"runner": "..."}'``.
        runners: REMOVED. The legacy ``runners`` map is no longer accepted.
            Use ``phase_configs`` (per-phase) or ``default_phase_config``.
        name: New human-readable workspace label (used in logs and
            diagnostics). Low-risk operational field.
        default_channel: New fallback Slack channel ID for messages
            published in workstreams that have no channel of their own
            resolved. Low-risk operational field.
        new_id: Rename the workspace to this new operator-chosen ID.
            Every workstream that referenced the old ID is rewritten to
            the new ID atomically. Use this to migrate a workspace from
            its initial Slack-team-ID-as-ID form to a friendlier name
            (e.g. ``workspace_update_config(workspace_id="T0123456789",
            new_id="almostrealism")``). Empty string leaves the ID
            unchanged.
        slack_team_id: Set or clear the Slack team ID this workspace
            routes messages to. Pass a non-empty value to (re)bind the
            workspace to that Slack team; pass an explicit empty string
            (``""``) to clear the Slack connection so channel/notifier
            operations skip cleanly. Omit the argument entirely to leave
            the existing value unchanged.
        slack_workspace_id: Deprecated alias for ``workspace_id``.
            Accepted for backward compatibility with older callers.

    Returns:
        dict with ``ok=True`` and the updated workspace fields, or
        ``ok=False`` with an error.
    """
    _require_scope("write")
    # Resolve the workspace identifier, accepting the legacy alias.
    if not workspace_id and slack_workspace_id:
        audit_log.debug("workspace_update_config: slack_workspace_id is a "
                        "deprecated alias for workspace_id")
        workspace_id = slack_workspace_id
    if not workspace_id:
        return {
            "ok": False,
            "error": "workspace_id is required",
            "next_steps": [
                "Pass workspace_id (the operator-chosen workspace ID)",
            ],
        }
    err = _reject_removed_config_params(
        default_runner=default_runner, runners=runners)
    if err:
        return err
    slack_team_id_provided = slack_team_id != _WORKSPACE_UNSET
    if not slack_team_id_provided:
        slack_team_id = ""
    err = _check_short_strings(
        workspace_id=workspace_id,
        name=name,
        default_channel=default_channel,
        new_id=new_id,
        slack_team_id=slack_team_id,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = _parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = _parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    _audit("workspace_update_config", workspace_id=workspace_id)

    payload = {}
    if name:
        payload["name"] = name
    if default_channel:
        payload["defaultChannel"] = default_channel
    if new_id and new_id != workspace_id:
        payload["newId"] = new_id
    if slack_team_id_provided:
        # Empty string clears; non-empty (re)binds. Either case is a write.
        payload["slackTeamId"] = slack_team_id
    # Use `is not None` so that an empty-dict clear signal ({}) is forwarded.
    if parsed_default_phase_config is not None:
        payload["defaultPhaseConfig"] = parsed_default_phase_config
    if parsed_phase_configs is not None:
        payload["phaseConfigs"] = parsed_phase_configs

    if not payload:
        return {
            "ok": False,
            "error": "No fields to update. Provide at least one field.",
            "next_steps": [
                "Specify fields to update: default_phase_config, "
                "phase_configs, name, default_channel, "
                "new_id, or slack_team_id",
            ],
        }

    result = _controller_post(
        f"/api/workspaces/{quote(workspace_id, safe='')}/config",
        payload,
    )

    if result.get("ok"):
        result["next_steps"] = [
            "Use workstream_list to verify workstreams now reflect the "
            "updated workspace defaults",
        ]
    else:
        result.setdefault("next_steps", [
            "Use workstream_list to confirm the workspace_id is correct",
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
    _require_scope("write")
    err = _check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    _require_workstream_in_scope(workstream_id)
    _audit("workstream_archive", workstream_id=workstream_id,
           archive_slack_channel=archive_slack_channel)
    return _controller_post(
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
    _require_scope("write")
    err = _check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    _require_workstream_in_scope(workstream_id)
    _audit("workstream_unarchive", workstream_id=workstream_id)
    return _controller_post(
        f"/api/workstreams/{quote(workstream_id, safe='')}/unarchive",
        {},
    )


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
    _require_scope("write")
    err = _check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    _require_workstream_in_scope(workstream_id)
    _audit("workstream_delete", workstream_id=workstream_id, force=force)

    # The controller delete runs first so that a rejection (active jobs,
    # archive-first requirement, unknown workstream) leaves the tracker
    # linkage intact. Only on a successful delete do we clear tracker
    # rows — that matches the ON DELETE SET NULL semantics described in
    # the docstring and is reversible only by re-linking.
    result = _controller_post(
        f"/api/workstreams/{quote(workstream_id, safe='')}/delete",
        {"force": force},
    )
    if not result.get("ok"):
        return result

    # /v1/tasks caps `limit` at 200, so a workstream with more than 200
    # linked tasks needs paging. After we PUT workstream_id=None on a task
    # it no longer matches the filter, so we just keep re-querying until the
    # filter returns no tasks. The MAX_TRACKER_CLEAR_BATCHES cap prevents an
    # unexpected server response (e.g. failed updates) from spinning forever.
    cleared = 0
    seen_ids = set()
    MAX_TRACKER_CLEAR_BATCHES = 200
    tracker_warning = None
    for _ in range(MAX_TRACKER_CLEAR_BATCHES):
        tasks_result = _tracker_get(
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
            update = _tracker_put(f"/v1/tasks/{task_id}",
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


# -- Tier 2: Pipeline-capable workstreams only ------------------------------

@mcp.tool()
def project_create_branch(
    workstream_id: str = "",
    repo_url: str = "",
    plan_title: str = "",
    plan_content: str = "",
) -> dict:
    """Create a planning branch and dispatch the project-manager workflow.

    This triggers the project-manager GitHub Actions workflow, which will:
    1. Create a timestamped branch (e.g., project/plan-20260301-title)
    2. Optionally commit a plan document
    3. Register a new workstream for the branch
    4. Submit a planning agent to refine the plan

    The branch name is determined by the workflow (based on the current
    date and plan title), so it cannot be returned immediately. Use
    workstream_list after the workflow completes to see the new workstream.

    The repository is resolved in priority order:
    1. If ``repo_url`` is provided, use it directly.
    2. If ``workstream_id`` is provided, resolve from the workstream config.
    3. If neither is provided, default to ``almostrealism/common`` on master.

    Args:
        workstream_id: Optional source workstream (from workstream_list).
        repo_url: Optional repository URL (HTTPS or SSH). Overrides workstream.
        plan_title: Short title for the plan branch (used in branch name).
        plan_content: Optional markdown content for the initial plan document.

    Returns:
        Dictionary confirming the workflow was dispatched.
    """
    _require_scope("pipeline")
    err = _check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url, plan_title=plan_title,
    )
    if err:
        return err
    if plan_content:
        err = _check_length(plan_content, "plan_content", MAX_CONTENT_LEN)
        if err:
            return err
    _require_workstream_in_scope(workstream_id)
    _audit("project_create_branch", workstream_id=workstream_id,
           repo_url=repo_url, plan_title=plan_title)

    # Resolve repository URL and base branch
    effective_repo = None
    effective_base = "master"

    if repo_url:
        effective_repo = repo_url
    elif workstream_id:
        ws = _find_workstream(workstream_id)
        if ws is None:
            return {
                "ok": False,
                "error": f"Workstream '{workstream_id}' not found",
                "next_steps": ["Use workstream_list to find valid workstream IDs"],
            }
        _set_github_org(ws)
        effective_repo = ws.get("repoUrl")
        effective_base = ws.get("baseBranch", "master")

    if not effective_repo:
        effective_repo = "https://github.com/almostrealism/common"

    owner_repo = _extract_owner_repo(effective_repo)
    if not owner_repo:
        return {
            "ok": False,
            "error": f"Cannot parse owner/repo from: {effective_repo}",
            "next_steps": ["Provide a valid repo_url (HTTPS or SSH format)"],
        }

    owner, repo = owner_repo
    inputs = {}
    if plan_title:
        inputs["plan_title"] = plan_title
    if plan_content:
        inputs["plan_content"] = plan_content

    result = _github_request(
        "POST",
        f"/repos/{owner}/{repo}/actions/workflows/project-manager.yaml/dispatches",
        {"ref": effective_base, "inputs": inputs},
    )

    if result.get("ok") or result.get("status") == 204:
        return {
            "ok": True,
            "triggered": True,
            "repo": f"{owner}/{repo}",
            "next_steps": [
                "The workflow will create a new branch and register a workstream",
                "Wait 1-2 minutes, then call workstream_list to find the new workstream",
                "The workflow creates a branch named like 'project/plan-YYYYMMDD-title'",
            ],
        }

    result.setdefault("next_steps", [
        "Check that the project-manager.yaml workflow exists in the repository",
        "Verify the GitHub token has 'actions:write' permission",
    ])
    return result


@mcp.tool()
def project_verify_branch(
    workstream_id: str,
    branch: str = "",
    plan_file: str = "",
) -> dict:
    """Dispatch the verify-completion workflow to validate branch work.

    This triggers a GitHub Actions workflow that checks whether the work
    on a branch meets the criteria defined in the planning document.

    Args:
        workstream_id: Workstream to verify (from workstream_list).
        branch: Branch to verify (default: workstream's defaultBranch).
        plan_file: Path to plan file for verification criteria (optional).

    Returns:
        Dictionary confirming the workflow was dispatched.
    """
    _require_scope("pipeline")
    err = _check_short_strings(
        workstream_id=workstream_id, branch=branch, plan_file=plan_file,
    )
    if err:
        return err
    _require_workstream_in_scope(workstream_id)
    _audit("project_verify_branch", workstream_id=workstream_id, branch=branch)

    ws = _find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    _set_github_org(ws)

    repo_url = ws.get("repoUrl")
    if not repo_url:
        return _pipeline_error(workstream_id, "repo_url is not configured")

    owner_repo = _extract_owner_repo(repo_url)
    if not owner_repo:
        return {
            "ok": False,
            "error": f"Cannot parse owner/repo from: {repo_url}",
            "next_steps": ["Use workstream_update_config to fix repo_url"],
        }

    owner, repo = owner_repo
    effective_branch = branch or ws.get("defaultBranch", "")
    if not effective_branch:
        return {
            "ok": False,
            "error": "No branch specified and workstream has no defaultBranch",
            "next_steps": ["Provide the branch parameter explicitly"],
        }

    inputs = {}
    if plan_file:
        inputs["plan_file"] = plan_file

    # The workflow uses github.ref_name as the branch, so we dispatch
    # on the target branch (not baseBranch). Inputs only has plan_file.
    result = _github_request(
        "POST",
        f"/repos/{owner}/{repo}/actions/workflows/verify-completion.yaml/dispatches",
        {"ref": effective_branch, "inputs": inputs},
    )

    if result.get("ok") or result.get("status") == 204:
        return {
            "ok": True,
            "triggered": True,
            "repo": f"{owner}/{repo}",
            "branch": effective_branch,
            "next_steps": [
                f"The verification workflow is running on branch '{effective_branch}'",
                "Check GitHub Actions in the repository for workflow results",
                "Use workstream_get_status to see if the agent completes follow-up tasks",
            ],
        }

    result.setdefault("next_steps", [
        "Check that the verify-completion.yaml workflow exists in the repository",
        "Verify the GitHub token has 'actions:write' permission",
    ])
    return result


@mcp.tool()
def project_commit_plan(
    workstream_id: str,
    content: str,
    path: str = "",
    branch: str = "",
    commit_message: str = "",
) -> dict:
    """Commit a plan document to a branch via the GitHub Contents API.

    This creates or updates a file directly on GitHub without needing a
    local clone. Useful for creating planning documents that agents will
    reference during their work.

    If no path is provided, one is auto-generated as:
    ``docs/plans/PLAN-YYYYMMDD-<slug>.md``

    Args:
        workstream_id: Workstream with repo_url (from workstream_list).
        content: The markdown content of the plan document.
        path: File path in the repository (auto-generated if omitted).
        branch: Branch to commit to (default: workstream's defaultBranch).
        commit_message: Git commit message (auto-generated if omitted).

    Returns:
        Dictionary with commit SHA and file path on success.
    """
    _require_scope("pipeline")
    err = _check_length(content, "content", MAX_CONTENT_LEN)
    if err:
        return err
    err = _check_short_strings(
        workstream_id=workstream_id, path=path, branch=branch,
        commit_message=commit_message,
    )
    if err:
        return err
    _require_workstream_in_scope(workstream_id)
    _audit("project_commit_plan", workstream_id=workstream_id, path=path, branch=branch)

    ws = _find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    _set_github_org(ws)

    repo_url = ws.get("repoUrl")
    if not repo_url:
        return _pipeline_error(workstream_id, "repo_url is not configured")

    owner_repo = _extract_owner_repo(repo_url)
    if not owner_repo:
        return {
            "ok": False,
            "error": f"Cannot parse owner/repo from: {repo_url}",
            "next_steps": ["Use workstream_update_config to fix repo_url"],
        }

    owner, repo = owner_repo
    effective_branch = branch or ws.get("defaultBranch", "")
    if not effective_branch:
        return {
            "ok": False,
            "error": "No branch specified and workstream has no defaultBranch",
            "next_steps": ["Provide the branch parameter explicitly"],
        }

    # Auto-generate path if not provided
    if not path:
        date_str = datetime.now(timezone.utc).strftime("%Y%m%d")
        slug = (
            effective_branch
            .replace("/", "-")
            .replace("_", "-")
            .lower()[:40]
        )
        path = f"docs/plans/PLAN-{date_str}-{slug}.md"

    # Path traversal protection
    normalized = os.path.normpath(path)
    if normalized.startswith("..") or "/../" in path or path.startswith("/"):
        return {
            "ok": False,
            "error": "Invalid path: must be a relative path without '..' segments",
        }
    for prefix in _SENSITIVE_PATH_PREFIXES:
        if normalized.startswith(prefix) or path.startswith(prefix):
            return {
                "ok": False,
                "error": f"Invalid path: cannot target '{prefix}' directory",
            }

    if not commit_message:
        commit_message = f"Add plan document: {path}"

    # Check if file already exists (need current SHA for updates)
    existing = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}?ref={quote(effective_branch, safe='/')}",
    )
    existing_sha = existing.get("sha")

    # Commit the file via Contents API (PUT)
    encoded_content = base64.b64encode(content.encode("utf-8")).decode("ascii")
    payload = {
        "message": commit_message,
        "content": encoded_content,
        "branch": effective_branch,
    }
    if existing_sha:
        payload["sha"] = existing_sha

    result = _github_request(
        "PUT",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}",
        payload,
    )

    if result.get("content"):
        commit_sha = result.get("commit", {}).get("sha", "")
        return {
            "ok": True,
            "path": path,
            "branch": effective_branch,
            "commit_sha": commit_sha,
            "repo": f"{owner}/{repo}",
            "next_steps": [
                f"Plan committed to {path} on branch '{effective_branch}'",
                "Use workstream_update_config to set planning_document if not already set",
                "Use workstream_submit_task to send an agent to work on the plan",
            ],
        }

    if not result.get("ok", True):
        result.setdefault("next_steps", [
            "Verify the branch exists on GitHub",
            "Check the GitHub token has 'contents:write' permission",
        ])
    return result


@mcp.tool()
def project_read_plan(
    workstream_id: str,
    path: str = "",
    branch: str = "",
) -> dict:
    """Read the planning document for a workstream (delegates to github_read_file).

    Looks up the workstream's configured ``planningDocument`` path and
    delegates to :func:`github_read_file` to fetch its content. The
    planning document path must be set via ``workstream_update_config``.

    Args:
        workstream_id: Workstream to read from (from workstream_list).
        path: Override for the planning document path. When omitted, the
            workstream's configured ``planningDocument`` path is used.
        branch: Branch to read from. Defaults to the workstream's
            ``defaultBranch``.

    Returns:
        Dictionary with file content, path, branch, sha, and repo.
    """
    _require_scope("github")
    err = _check_short_strings(
        workstream_id=workstream_id, path=path, branch=branch,
    )
    if err:
        return err
    _require_workstream_in_scope(workstream_id)
    _audit("project_read_plan", workstream_id=workstream_id, path=path, branch=branch)

    ws = _find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    effective_path = path or ws.get("planningDocument", "")
    if not effective_path:
        return {
            "ok": False,
            "error": "No planning document path configured for this workstream",
            "next_steps": [
                "Provide the path parameter explicitly",
                "Use workstream_update_config to set planning_document",
            ],
        }

    repo_url = ws.get("repoUrl", "")
    if not repo_url:
        return {
            "ok": False,
            "error": "No repository URL configured for this workstream",
            "next_steps": [
                "Use workstream_update_config to set repo_url",
            ],
        }

    effective_branch = branch or ws.get("defaultBranch", "")
    if not effective_branch:
        return {
            "ok": False,
            "error": "No branch configured for this workstream",
            "next_steps": [
                "Pass branch explicitly when calling project_read_plan",
                "Use workstream_update_config to set default_branch",
            ],
        }

    result = github_read_file(
        path=effective_path,
        repo_url=repo_url,
        branch=effective_branch,
        workstream_id=workstream_id,
    )

    if result.get("ok"):
        # Expose branch alongside ref for backward compatibility
        result.setdefault("branch", result.get("ref", effective_branch))
        result["next_steps"] = [
            "Use project_commit_plan to update this document",
            "Use workstream_submit_task to send an agent to work on the plan",
        ]
    else:
        result.setdefault("next_steps", [
            f"Verify the file exists at '{effective_path}'",
            "Use project_commit_plan to create the planning document first",
        ])
    return result


# -- Tier 3: Memory tools ---------------------------------------------------


def _resolve_branch_context(
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    require_branch: bool = True,
) -> tuple[str, str, Optional[dict]]:
    """Resolve repo_url and branch from workstream_id if needed.

    When neither ``workstream_id`` nor ``repo_url`` is supplied, falls back
    to the workstream identified by the in-flight request's HMAC temp token
    (see :func:`_get_token_workstream_id`). This lets job-scoped callers
    (Claude Code / opencode agents) invoke branch-aware tools like
    :func:`memory_store` with only the content payload — the workstream the
    job is bound to is inferred from the bearer.

    Args:
        workstream_id: Workstream to look up repo/branch from.
        repo_url: Explicit repository URL.
        branch: Explicit branch name.
        require_branch: If False, only repo_url is required (branch may
            be empty).  Defaults to True for backward compatibility.

    Returns:
        (repo_url, branch, error_dict_or_None)
    """
    if repo_url and (branch or not require_branch):
        return (repo_url, branch, None)

    if workstream_id:
        ws = _find_workstream(workstream_id)
        if ws is None:
            return ("", "", {
                "ok": False,
                "error": f"Workstream '{workstream_id}' not found",
                "next_steps": ["Use workstream_list to find valid workstream IDs"],
            })
        repo_url = repo_url or ws.get("repoUrl", "")
        branch = branch or ws.get("defaultBranch", "")

    # Token-context fallback: when neither the workstream_id arg nor an
    # explicit repo_url was provided, try the workstream bound to the
    # in-flight request's HMAC temp token. This used to fire only for
    # ``require_branch=False`` callers; extending it to the strict
    # ``require_branch=True`` path means job-scoped tools like
    # :func:`memory_store` can be called with just the content payload —
    # the workstream the job runs on supplies the repo/branch automatically
    # via the temp token's payload. Explicit ``workstream_id`` /
    # ``repo_url`` arguments still win when provided (override path).
    if not repo_url and not workstream_id:
        token_ws_id = _get_token_workstream_id()
        if token_ws_id:
            ws = _find_workstream(token_ws_id)
            if ws:
                repo_url = repo_url or ws.get("repoUrl", "")
                branch = branch or ws.get("defaultBranch", "")

    missing = []
    if not repo_url:
        missing.append("repo_url")
    if require_branch and not branch:
        missing.append("branch")
    if missing:
        if require_branch:
            next_steps = [
                "Provide repo_url and branch directly, or",
                "Provide workstream_id to resolve them from the workstream config, or",
                "Call from a job session whose HMAC token resolves to a"
                " registered workstream",
            ]
        else:
            next_steps = [
                "Provide repo_url directly, or",
                "Provide workstream_id to resolve the repo URL from the"
                " workstream config, or",
                "Call from a job session whose HMAC token resolves to a"
                " registered workstream",
            ]
        return ("", "", {
            "ok": False,
            "error": f"Either ({' + '.join(missing)}) or workstream_id is required",
            "next_steps": next_steps,
        })

    return (repo_url, branch, None)


@mcp.tool()
def memory_recall(
    query: str,
    namespace: str = "default",
    limit: int = 5,
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
    include_messages: bool = False,
    scope: str = "repo",
) -> dict:
    """Search agent memories with optional LLM synthesis.

    Retrieves semantically similar memories from the ar-memory server.
    If an LLM backend is available, provides a synthesized summary.
    Can resolve repo_url/branch from workstream_id if provided.

    By default, results are scoped to the current repository to avoid
    returning unrelated memories from other projects.

    Args:
        query: Natural language search query.
        namespace: Memory namespace to search.
        limit: Maximum number of memories to retrieve.
        repo_url: Optional repository URL filter.
        branch: Optional branch name filter.
        workstream_id: Optional workstream to resolve repo/branch from.
        include_messages: If true, also search the "messages" namespace
            and merge results. Defaults to false.
        scope: Search scope — ``repo`` (default) searches the current
            repository across all branches; ``branch`` narrows to the
            current branch within the repo; ``all`` searches all repos.

    Returns:
        Dictionary with memories and optional summary.
    """
    _require_scope("memory-read")
    if scope not in ("repo", "branch", "all"):
        return {
            "ok": False,
            "error": f"Invalid scope '{scope}'. Must be 'repo', 'branch', or 'all'.",
        }
    err = _check_short_strings(
        query=query, namespace=namespace, repo_url=repo_url,
        branch=branch, workstream_id=workstream_id,
    )
    if err:
        return err
    _audit("memory_recall", query=query, namespace=namespace, scope=scope)

    client = _get_memory_client()
    if client is None:
        return {
            "ok": False,
            "error": "ar-memory server unavailable",
            "next_steps": [
                "Start ar-memory: python tools/mcp/memory/server.py --http-only",
                "Or set AR_MEMORY_URL to point to a running instance",
            ],
        }

    # Resolve context based on scope
    effective_repo = repo_url
    effective_branch = branch

    if scope == "all" and not repo_url and not workstream_id:
        # Explicitly requested: search everything, no filtering
        effective_repo = ""
        effective_branch = ""
    elif scope == "branch":
        # Need both repo and branch — use the strict resolver
        if workstream_id or not (repo_url and branch):
            effective_repo, effective_branch, err = _resolve_branch_context(
                workstream_id=workstream_id, repo_url=repo_url, branch=branch,
                require_branch=True,
            )
            if err:
                return err
    else:
        # scope == "repo" (default) — need at least repo_url
        if workstream_id or not repo_url:
            effective_repo, effective_branch, err = _resolve_branch_context(
                workstream_id=workstream_id, repo_url=repo_url, branch=branch,
                require_branch=False,
            )
            if err:
                return err
        # For repo scope, don't filter by branch unless explicitly provided
        if scope == "repo" and not branch:
            effective_branch = ""

    try:
        memories = client.search(
            query=query,
            namespace=namespace,
            limit=limit,
            repo_url=effective_repo or None,
            branch=effective_branch or None,
        )
    except ConnectionError as e:
        return {"ok": False, "error": f"Memory search failed: {e}"}

    # Messages are a non-semantic namespace: retrieved by branch/recency, not
    # embedded in the FAISS index (see MemoryStore NON_SEMANTIC_NAMESPACES).
    # When both repo and branch are known, merge the most recent messages in
    # by recency. They are appended after the semantic results and capped, so
    # they never displace a primary (semantically ranked) hit. Messages are
    # most completely retrieved via workstream_context.
    if (include_messages and namespace != "messages"
            and effective_repo and effective_branch):
        try:
            msg_memories = client.search_by_branch(
                repo_url=effective_repo,
                branch=effective_branch,
                namespace="messages",
                limit=limit,
            )
            if msg_memories:
                memories = (memories + msg_memories)[:limit]
        except ConnectionError:
            pass  # Non-critical: proceed without messages

    if not memories:
        return {
            "ok": True,
            "summary": f"No memories found for '{query}' in namespace '{namespace}'.",
            "memories": [],
        }

    # Attempt LLM synthesis
    summary = None
    llm = _get_llm()
    if llm and llm.available:
        try:
            from inference import SYSTEM_PROMPT

            mem_text = ""
            for i, m in enumerate(memories, 1):
                score = m.get("score", "?")
                mem_text += f"### Memory {i} (similarity: {score})\n{m.get('content', '')}\n\n"

            prompt = (
                f"## Retrieved Memories\n\n{mem_text}\n\n"
                f"## Task\n\nThe user searched for: \"{query}\"\n\n"
                "Summarize the retrieved memories. Highlight key findings and "
                "any decisions or progress notes. Be concise (2-4 sentences)."
            )
            summary = llm.generate(prompt, system=SYSTEM_PROMPT)
        except Exception as e:
            summary = f"(LLM synthesis failed: {e})"

    result = {
        "ok": True,
        "memories": [
            {
                "id": m.get("id"),
                "content": m.get("content"),
                "score": m.get("score"),
                "tags": m.get("tags"),
                "created_at": m.get("created_at"),
                "repo_url": m.get("repo_url"),
                "branch": m.get("branch"),
            }
            for m in memories
        ],
        "count": len(memories),
        "next_steps": [
            "Use workstream_context for a full branch history",
            "Use memory_store to add new memories",
        ],
    }

    if summary:
        result["summary"] = summary

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
        include_activities: Activity filter — accepts a Python list of strings,
            a JSON-array string (``["deduplication","primary"]``), or a plain
            comma-separated string.  Defaults to ``"primary"``, which returns
            only messages with no activity tag (primary work) or with the
            explicit ``activity:primary`` tag — both are treated as primary.
            Audit-phase messages (e.g. ``activity:deduplication``) are hidden
            by default.  Pass ``"all"`` to see every message, or a specific
            activity name (e.g. ``"deduplication"``) to see that phase plus
            primary/untagged messages.

    Returns:
        Dictionary with branch memories, optionally commits, and optionally
        a compact jobs timeline. When commits are included, the response also
        contains ``total_commits`` (the full number of commits on the branch)
        and ``initial_commit_sha`` (the first commit on the branch relative
        to the base).
    """
    _require_scope("memory-read")
    err = _check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url,
        branch=branch, namespace=namespace,
    )
    if err:
        return err
    _audit("workstream_context", workstream_id=workstream_id, branch=branch)

    effective_repo, effective_branch, err = _resolve_branch_context(
        workstream_id=workstream_id, repo_url=repo_url, branch=branch,
    )
    if err:
        return err

    client = _get_memory_client()
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
    if namespace and include_messages and namespace != "messages":
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
    effective_include = _parse_activities_param(include_activities)
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

    # Fetch commit history from GitHub Compare API if requested
    commits = None
    commit_error = None
    total_commits = 0
    all_commits = []
    if include_commits and effective_repo:
        owner_repo = _extract_owner_repo(effective_repo)
        if owner_repo:
            owner, repo = owner_repo
            # Determine the base branch from the workstream if available
            ws = _find_workstream(workstream_id) if workstream_id else None
            base = ws.get("baseBranch", "master") if ws else "master"

            # Set GitHub org context so the proxy uses the correct per-org token
            if ws:
                _set_github_org(ws)
            elif owner:
                _current_github_org.set(owner)

            try:
                compare = _github_request(
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
        pr_owner_repo = _extract_owner_repo(effective_repo)
        if pr_owner_repo:
            pr_owner, pr_repo = pr_owner_repo
            # Set GitHub org context so the proxy uses the correct per-org token
            ws = _find_workstream(workstream_id) if workstream_id else None
            if ws:
                _set_github_org(ws)
            elif pr_owner:
                _current_github_org.set(pr_owner)

            try:
                pr_lookup = _find_recent_pr_by_branch(pr_owner, pr_repo, effective_branch)
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
            jobs_result = _controller_get(
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


@mcp.tool()
def memory_store(
    content: str,
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    namespace: str = "default",
    tags: Optional[list[str]] = None,
    source: Optional[str] = None,
) -> dict:
    """Store a memory from an external client.

    Either ``workstream_id`` or (``repo_url`` + ``branch``) is required to
    identify the branch context for the memory.  When neither is supplied,
    the workstream bound to the in-flight request's HMAC temp token is
    used — so a job-scoped agent call with only ``content`` succeeds and
    stores the memory against the job's workstream branch automatically.

    Args:
        content: The text content to store.
        workstream_id: Resolves to repo_url/branch via workstream config.
        repo_url: Repository URL.
        branch: Branch name.
        namespace: Logical grouping.
        tags: Optional tags for categorization.
        source: Optional source identifier.

    Returns:
        Dictionary with the created entry.
    """
    _require_scope("memory-write")
    err = _check_length(content, "content", MAX_PROMPT_LEN)
    if err:
        return err
    err = _check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url,
        branch=branch, namespace=namespace,
    )
    if err:
        return err
    _audit("memory_store", namespace=namespace, content_len=len(content))

    effective_repo, effective_branch, err = _resolve_branch_context(
        workstream_id=workstream_id, repo_url=repo_url, branch=branch,
    )
    if err:
        return err

    client = _get_memory_client()
    if client is None:
        return {
            "ok": False,
            "error": "ar-memory server unavailable",
            "next_steps": [
                "Start ar-memory: python tools/mcp/memory/server.py --http-only",
            ],
        }

    try:
        entry = client.store(
            content=content,
            repo_url=effective_repo,
            branch=effective_branch,
            namespace=namespace,
            tags=tags,
            source=source,
        )
    except ConnectionError as e:
        return {"ok": False, "error": f"Memory store failed: {e}"}

    entry["ok"] = True
    entry["next_steps"] = [
        "Use memory_recall to search for this and other memories",
        "Use workstream_context to see all memories for this branch",
    ]
    return entry


# ---------------------------------------------------------------------------
# Messaging tools
# ---------------------------------------------------------------------------


@mcp.tool()
def send_message(
    text: str,
    workstream_id: str = "",
    job_id: str = "",
    activity: str = "",
) -> dict:
    """Send a message for archival and optional notification.

    Messages are stored in the memory database by the controller and
    optionally forwarded to a notification channel.  Use this tool to
    report status updates, results, or errors back to the user who
    initiated this task.

    ``workstream_id`` and ``job_id`` are both optional. In a job session
    (Claude Code or opencode launched by the controller) the in-flight
    request's HMAC temp token already binds the call to a specific
    workstream and job, and both are derived from the bearer
    automatically — so a call with just ``{text, activity}`` posts to the
    correct workstream thread. Explicit values, when supplied, override
    the token-derived ones (the override path is preserved for
    operator/admin callers that hold a static bearer with no
    workstream binding).

    Args:
        text: The message text to send.
        workstream_id: Workstream to send the message to.  Defaults to
            the workstream resolved from the in-flight request's HMAC
            temp token.  Only required when no resolvable token context
            exists (e.g. a static-token admin call).
        job_id: Job to thread the message under.  Defaults to the job
            resolved from the in-flight request's HMAC temp token.  When
            absent the message lands at the top of the workstream's
            channel rather than inside a job thread.
        activity: Optional tag identifying the phase or activity this
            message belongs to (e.g. ``"deduplication"``,
            ``"organizational_placement"``,
            ``"maven_dependency_protection"``).  Defaults to empty
            (primary work).  When the environment variable
            ``AR_AGENT_ACTIVITY`` is set and ``activity`` is not
            supplied, the env var value is used automatically so that
            correction-session agents do not need to pass it explicitly.

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    _require_scope("write")

    # Resolve (workstream, job) from explicit args first, then from the
    # in-flight HTTP request's bearer, then from the auth-middleware's
    # ContextVar/thread-local. Emit a structured diagnostic *before* the
    # routing decision so a production failure (e.g. opencode-driven
    # phase posting top-of-channel instead of in the job's thread) leaves
    # enough evidence in the controller log to pinpoint which source
    # supplied the empty job_id without further speculation. The
    # diagnostic does not echo any token body, only the four-way
    # provenance and the decode reason. See
    # :func:`_decode_current_request_token_full` for the reason vocabulary.
    per_req_ws, per_req_job, per_req_label, per_req_reason = (
        _decode_current_request_token_full())
    ctx_ws = _request_workstream_id.get(None)
    ctx_job = _request_job_id.get(None)
    tl_ws = getattr(_thread_local, "workstream_id", None)
    tl_job = getattr(_thread_local, "job_id", None)

    # Reuse the already-decoded per_req_ws/per_req_job and the already-read
    # ctx_* / tl_* values rather than calling _get_token_workstream_id() /
    # _get_token_job_id(), which would each invoke _decode_current_request_token_full()
    # a second time. The resolution order is identical: explicit arg wins, then
    # per-request bearer, then ContextVar, then thread-local.
    effective_ws = workstream_id or per_req_ws or ctx_ws or tl_ws or ""
    effective_job = job_id or per_req_job or ctx_job or tl_job or ""
    effective_activity = (activity or os.environ.get("AR_AGENT_ACTIVITY", "")).strip()

    if effective_ws and not effective_job:
        # The exact production failure mode: a workstream is resolved
        # but the job_id binding has been lost, so the controller URL
        # falls back to the workstream-level /messages endpoint and
        # the message lands at the top of the Slack channel rather
        # than inside the job's thread. Surface every source we
        # examined so a single log line says which one failed.
        audit_log.warning(
            "send_message_missing_job_id "
            "explicit_workstream_id=%s explicit_job_id=%s "
            "per_request_workstream_id=%s per_request_job_id=%s "
            "per_request_label=%s per_request_decode_reason=%s "
            "contextvar_workstream_id=%s contextvar_job_id=%s "
            "thread_local_workstream_id=%s thread_local_job_id=%s "
            "effective_workstream_id=%s effective_job_id=%s "
            "activity=%s",
            workstream_id or "", job_id or "",
            per_req_ws or "", per_req_job or "",
            per_req_label or "", per_req_reason,
            ctx_ws or "", ctx_job or "",
            tl_ws or "", tl_job or "",
            effective_ws, effective_job,
            effective_activity or "")
    else:
        audit_log.info(
            "send_message_resolved "
            "explicit_workstream_id=%s explicit_job_id=%s "
            "per_request_workstream_id=%s per_request_job_id=%s "
            "per_request_decode_reason=%s "
            "contextvar_workstream_id=%s contextvar_job_id=%s "
            "thread_local_workstream_id=%s thread_local_job_id=%s "
            "effective_workstream_id=%s effective_job_id=%s "
            "activity=%s",
            workstream_id or "", job_id or "",
            per_req_ws or "", per_req_job or "",
            per_req_reason,
            ctx_ws or "", ctx_job or "",
            tl_ws or "", tl_job or "",
            effective_ws, effective_job,
            effective_activity or "")

    if not effective_ws:
        return {
            "ok": False,
            "error": (
                "workstream_id could not be resolved. Pass workstream_id"
                " explicitly, or call from a job session whose HMAC"
                " temp token resolves to a registered workstream."
            ),
            "next_steps": [
                "Use workstream_list to find the workstream ID and pass"
                " workstream_id=<id>",
                "If calling from a job session, verify the bearer token"
                " is an armt_tmp_ HMAC token (a static admin bearer"
                " carries no workstream binding)",
            ],
        }

    if not effective_job and not workstream_id and not job_id:
        # The caller asked for automatic job-thread routing (passed
        # neither workstream_id nor job_id explicitly) but every
        # resolution path returned an empty job_id. Two scenarios:
        #
        #   1. The caller is a static-token admin whose bearer carries
        #      no workstream/job binding at all — ``per_req_reason`` is
        #      ``not_temp_token`` / ``no_auth_header`` / ``non_bearer_scheme``
        #      and they did not pass an explicit workstream_id. In that
        #      case the system cannot tell where to thread, but it also
        #      has no expectation of threading. Fail loudly with a
        #      clear "pass workstream_id" instruction so the caller
        #      knows how to recover — silently posting to the channel
        #      top-level is the silent-degradation the prior
        #      ``send_message_missing_job_id`` warning logged but
        #      swallowed.
        #
        #   2. The caller is a job session whose temp token should have
        #      resolved a job (and the agent expects threading), but
        #      resolution failed — the FastMCP stateful-transport
        #      request-propagation hazard the per-request decoder
        #      exists to handle, or a token-issuance problem upstream.
        #      This is the production bug the loud-fail guards
        #      against: previously the tool would post at the channel
        #      top-level and return success, leaving the agent to
        #      assume the message threaded. Returning an explicit
        #      ``ok=false`` with named next-steps gives the agent a
        #      recovery path (``workstream_id`` + ``job_id`` explicit)
        #      and surfaces the failure to the operator via the
        #      ``send_message_unthreaded`` audit line.
        #
        # A caller who genuinely wants workstream top-level posting
        # must pass ``workstream_id`` explicitly; the default-empty
        # ``workstream_id`` is the "I expect auto-resolution" signal.
        audit_log.error(
            "send_message_unthreaded "
            "explicit_workstream_id=%s explicit_job_id=%s "
            "per_request_workstream_id=%s per_request_job_id=%s "
            "per_request_label=%s per_request_decode_reason=%s "
            "contextvar_workstream_id=%s contextvar_job_id=%s "
            "thread_local_workstream_id=%s thread_local_job_id=%s "
            "effective_workstream_id=%s effective_job_id=%s "
            "activity=%s",
            workstream_id or "", job_id or "",
            per_req_ws or "", per_req_job or "",
            per_req_label or "", per_req_reason,
            ctx_ws or "", ctx_job or "",
            tl_ws or "", tl_job or "",
            effective_ws, effective_job,
            effective_activity or "")
        return {
            "ok": False,
            "error": (
                "send_message could not resolve a job_id. The tool"
                " advertises job_id as optional because it auto-resolves"
                " from the in-flight request's HMAC temp token, but the"
                " resolution failed and the call did not supply explicit"
                " workstream_id/job_id. Posting at the workstream top"
                " level silently would be deceptive; the message has"
                " NOT been sent. Pass workstream_id AND job_id"
                " explicitly (e.g. workstream_id=<id>, job_id=<id>),"
                " or fix the bearer so the per-request decode can"
                " resolve them."
            ),
            "per_request_decode_reason": per_req_reason,
            "next_steps": [
                "Pass workstream_id and job_id explicitly in the call",
                "If calling from a job session, verify the bearer is"
                " an armt_tmp_ HMAC temp token and that it is being"
                " sent on the request (the controller log line"
                " 'temp_token_request' is written by the auth"
                " middleware on every authenticated request — if it"
                " is absent for the failing call, the bearer is not"
                " reaching the server)",
                "If the bearer IS a temp token, the per-request"
                " decode may have hit a transport-level issue. Pass"
                " workstream_id and job_id explicitly as a safe"
                " fallback until the upstream transport is fixed.",
            ],
        }

    if effective_activity:
        err = _check_length(effective_activity, "activity", MAX_SHORT_STRING_LEN)
        if err:
            return err

    _require_workstream_in_scope(effective_ws)
    _audit("send_message", workstream_id=effective_ws, job_id=effective_job,
           activity=effective_activity, text=text[:80])

    err = _check_length(text, "text", MAX_CONTENT_LEN)
    if err:
        return err

    # Build the controller path
    path = f"/api/workstreams/{quote(effective_ws, safe='')}"
    if effective_job:
        path += f"/jobs/{quote(effective_job, safe='')}"
    path += "/messages"

    body: dict = {"text": text}
    if effective_activity:
        body["activity"] = effective_activity
    return _controller_post(path, body)


# ---------------------------------------------------------------------------
# GitHub PR tools
# ---------------------------------------------------------------------------


def _find_open_pr_by_branch(owner: str, repo: str, branch: str) -> dict:
    """Look up the first open pull request for ``branch`` on ``owner/repo``.

    Returns a dict with ``ok=True`` and ``pr`` (the raw GitHub PR object)
    on success, ``ok=True`` with ``found=False`` when no open PR exists
    for the branch, or an ``ok=False`` error dict when the GitHub call
    fails or returns an unexpected payload. Centralising this lookup
    avoids drift between tools that need to resolve a PR by branch
    (e.g. ``github_pr_find``, ``github_request_copilot_review``,
    ``github_pr_check_status``).
    """
    head = f"{owner}:{branch}"
    pr_list = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&state=open",
    )
    if isinstance(pr_list, dict) and pr_list.get("ok") is False:
        return pr_list
    if not isinstance(pr_list, list):
        return {
            "ok": False,
            "error": "Unexpected response listing pull requests",
        }
    if not pr_list:
        return {"ok": True, "found": False, "branch": branch}
    return {"ok": True, "found": True, "pr": pr_list[0], "branch": branch}


def _find_recent_pr_by_branch(owner: str, repo: str, branch: str) -> dict:
    """Look up the most recent pull request for ``branch`` on ``owner/repo``.

    Unlike ``_find_open_pr_by_branch``, this searches across all PR states
    (open, closed, merged) using the GitHub Pulls list API with ``state=all``
    and returns the most recently updated PR for the branch. Returns a dict
    with ``ok=True`` and ``pr`` (the raw GitHub PR object) on success,
    ``ok=True`` with ``found=False`` when no PR exists for the branch, or
    an ``ok=False`` error dict when the GitHub call fails.

    Args:
        owner: GitHub org (owner).
        repo: Repository name.
        branch: Branch name to search for.

    Returns:
        Dict with ``ok=True``, ``found=True``, ``pr`` (raw GitHub PR object),
        and ``branch`` on success; ``ok=True``, ``found=False`` when no PR
        exists; or ``ok=False`` with error message on failure.
    """
    head = f"{owner}:{branch}"
    pr_list = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&state=all&sort=updated&direction=desc&per_page=1",
    )
    if isinstance(pr_list, dict) and pr_list.get("ok") is False:
        return pr_list
    if not isinstance(pr_list, list):
        return {
            "ok": False,
            "error": "Unexpected response listing pull requests",
        }
    if not pr_list:
        return {"ok": True, "found": False, "branch": branch}
    return {"ok": True, "found": True, "pr": pr_list[0], "branch": branch}


@mcp.tool()
def github_pr_find(
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Find an open pull request for a branch.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch to search for. Defaults to workstream's defaultBranch.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. When set, bypasses workstream resolution — useful
            when no workstream exists for the repo. Scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        PR details if found, or error.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, effective_branch, err = _resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    _audit("github_pr_find", workstream_id=workstream_id, branch=effective_branch)

    lookup = _find_open_pr_by_branch(owner, repo, effective_branch)
    if not lookup.get("ok"):
        return lookup
    if not lookup.get("found"):
        return {"ok": True, "found": False, "branch": effective_branch}
    pr = lookup["pr"]
    return {
        "ok": True,
        "found": True,
        "number": pr.get("number"),
        "title": pr.get("title"),
        "url": pr.get("html_url"),
        "state": pr.get("state"),
        "branch": effective_branch,
    }


@mcp.tool()
def github_pr_review_comments(
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Get code review comments on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        List of review comments.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, _, err = _resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    _audit("github_pr_review_comments", pr_number=pr_number)

    # Fetch unresolved review threads via GraphQL (paginated).
    # The REST /pulls/{pr}/comments endpoint caps at 30 per page and does not
    # expose thread-level resolution state, so we use the GraphQL API instead.
    REVIEW_THREADS_QUERY = """
    query($owner: String!, $repo: String!, $pr: Int!, $cursor: String) {
      repository(owner: $owner, name: $repo) {
        pullRequest(number: $pr) {
          reviewThreads(first: 100, after: $cursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              isResolved
              comments(first: 50) {
                nodes {
                  databaseId
                  path
                  line
                  originalLine
                  body
                  author { login }
                  createdAt
                }
              }
            }
          }
        }
      }
    }
    """

    all_comments = []
    cursor = None
    while True:
        variables = {"owner": owner, "repo": repo, "pr": pr_number, "cursor": cursor}
        result = _github_graphql_request(REVIEW_THREADS_QUERY, variables)

        if isinstance(result, dict) and not result.get("ok", True) is False:
            if "errors" in result:
                return {"ok": False, "error": result["errors"][0].get("message", "GraphQL error")}
        if not isinstance(result, dict) or "data" not in result:
            return result if isinstance(result, dict) else {"ok": False, "error": "Unexpected response from GitHub GraphQL"}

        pr_data = (result.get("data") or {}).get("repository", {}).get("pullRequest") or {}
        threads_connection = pr_data.get("reviewThreads", {})
        threads = threads_connection.get("nodes", [])

        for thread in threads:
            if thread.get("isResolved"):
                continue
            for c in thread.get("comments", {}).get("nodes", []):
                all_comments.append({
                    "id": c.get("databaseId"),
                    "path": c.get("path"),
                    "line": c.get("line") or c.get("originalLine"),
                    "body": c.get("body"),
                    "user": (c.get("author") or {}).get("login"),
                    "created_at": c.get("createdAt"),
                    "in_reply_to_id": None,
                })

        page_info = threads_connection.get("pageInfo", {})
        if not page_info.get("hasNextPage"):
            break
        cursor = page_info.get("endCursor")

    all_comments.sort(key=lambda c: c.get("created_at") or "", reverse=True)
    top_comments = all_comments[:50]
    return {"ok": True, "comments": top_comments, "count": len(top_comments)}


@mcp.tool()
def github_pr_conversation(
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Get the conversation (issue comments) on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        List of conversation comments.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, _, err = _resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    _audit("github_pr_conversation", pr_number=pr_number)

    result = _github_request("GET", f"/repos/{owner}/{repo}/issues/{pr_number}/comments")
    if isinstance(result, list):
        comments = []
        for c in result:
            comments.append({
                "id": c.get("id"),
                "body": c.get("body"),
                "user": c.get("user", {}).get("login"),
                "created_at": c.get("created_at"),
            })
        return {"ok": True, "comments": comments, "count": len(comments)}
    return result


@mcp.tool()
def github_pr_reply(
    comment_id: int,
    body: str,
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Reply to a pull request review comment.

    Args:
        comment_id: The ID of the review comment to reply to.
        body: The reply text.
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        The created reply.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, _, err = _resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    _audit("github_pr_reply", comment_id=comment_id, pr_number=pr_number)

    result = _github_request(
        "POST",
        f"/repos/{owner}/{repo}/pulls/{pr_number}/comments/{comment_id}/replies",
        {"body": body},
    )
    if result.get("id"):
        return {"ok": True, "id": result["id"]}
    return result


@mcp.tool()
def github_list_open_prs(
    workstream_id: str = "",
    base: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """List open pull requests.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Filter by base branch (e.g., "master"). If empty, lists all open PRs.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        List of open PRs.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, _, err = _resolve_github_repo(
        workstream_id=workstream_id, owner=org, repo=repo)
    if err:
        return err

    _audit("github_list_open_prs", base=base)

    path = f"/repos/{owner}/{repo}/pulls?state=open"
    if base:
        path += f"&base={quote(base, safe='/')}"

    result = _github_request("GET", path)
    if isinstance(result, list):
        prs = []
        for pr in result:
            prs.append({
                "number": pr.get("number"),
                "title": pr.get("title"),
                "url": pr.get("html_url"),
                "head": pr.get("head", {}).get("ref"),
                "base": pr.get("base", {}).get("ref"),
                "user": pr.get("user", {}).get("login"),
                "created_at": pr.get("created_at"),
            })
        return {"ok": True, "prs": prs, "count": len(prs)}
    return result


@mcp.tool()
def github_create_pr(
    title: str,
    body: str,
    workstream_id: str = "",
    base: str = "",
    head: str = "",
    request_copilot_review: bool = False,
    org: str = "",
    repo: str = "",
) -> dict:
    """Create a pull request.

    Args:
        title: PR title.
        body: PR description.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Base branch (default: workstream's baseBranch or "master").
        head: Head branch (default: workstream's defaultBranch).
        request_copilot_review: If true, automatically request a Copilot review
            after creating the PR.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        The created PR details, including copilot_review_requested if applicable.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, default_branch, err = _resolve_github_repo(
        workstream_id=workstream_id, owner=org, repo=repo)
    if err:
        return err

    effective_ws = workstream_id or _get_token_workstream_id() or ""
    ws = _find_workstream(effective_ws) if effective_ws else None
    effective_base = base or (ws.get("baseBranch", "master") if ws else "master")
    effective_head = head or default_branch

    if not effective_head:
        return {"ok": False, "error": "head branch is required"}

    _audit("github_create_pr", title=title, base=effective_base, head=effective_head)

    result = _github_request("POST", f"/repos/{owner}/{repo}/pulls", {
        "title": title,
        "body": body,
        "base": effective_base,
        "head": effective_head,
    })

    if result.get("number"):
        pr_number = result["number"]
        response = {
            "ok": True,
            "number": pr_number,
            "url": result.get("html_url"),
            "title": result.get("title"),
        }

        if request_copilot_review:
            copilot_result = _request_copilot_review(owner, repo, pr_number)
            response["copilot_review_requested"] = copilot_result.get("ok", False)
            if not copilot_result.get("ok"):
                response["copilot_review_error"] = copilot_result.get("error")

        return response
    return result


def _dismiss_copilot_review(owner: str, repo: str, pr_number: int) -> dict:
    """Dismiss the most recent dismissible Copilot review on a pull request.

    Looks for reviews from the copilot bot (login contains 'copilot') and
    dismisses the most recent one that is in APPROVED or CHANGES_REQUESTED
    state, which allows a fresh review to be requested.

    Args:
        owner: Repository owner.
        repo: Repository name.
        pr_number: Pull request number.

    Returns:
        dict with ok=True if a review was dismissed, ok=False otherwise.
    """
    reviews = _github_request("GET", f"/repos/{owner}/{repo}/pulls/{pr_number}/reviews")
    if not isinstance(reviews, list):
        if isinstance(reviews, dict):
            return reviews
        return {"ok": False, "error": "Failed to list reviews"}

    dismissible = [
        r for r in reviews
        if isinstance(r.get("user"), dict)
        and _is_copilot_login(r.get("user", {}).get("login", ""))
        and r.get("state") in ("APPROVED", "CHANGES_REQUESTED")
    ]

    if not dismissible:
        return {"ok": False, "error": "No dismissible Copilot reviews found"}

    most_recent = max(dismissible, key=lambda r: r.get("submitted_at", ""))
    review_id = most_recent["id"]
    result = _github_request(
        "PUT",
        f"/repos/{owner}/{repo}/pulls/{pr_number}/reviews/{review_id}/dismissals",
        {"message": "Dismissing prior Copilot review to allow re-review"},
    )
    if isinstance(result, dict) and result.get("id"):
        return {"ok": True}
    if isinstance(result, dict) and "ok" in result:
        return result
    return {"ok": False, "error": str(result)}


COPILOT_REVIEWER_LOGIN = "copilot"


def _is_copilot_login(login: str) -> bool:
    """Returns True if ``login`` identifies a GitHub Copilot account.

    GitHub exposes Copilot under multiple login strings depending on the
    endpoint, empirically observed on this repo:

    - ``"copilot-pull-request-reviewer"`` — the bot login returned by the
      GraphQL ``suggestedActors`` query and used by the
      ``requestReviews`` mutation.
    - ``"copilot-pull-request-reviewer[bot]"`` — the ``user.login`` on
      review objects returned by ``GET /pulls/N/reviews``.
    - ``"Copilot"`` — the ``user.login`` on review-comment objects
      returned by ``GET /pulls/N/comments``.

    Matching all forms with a case-insensitive substring check on
    ``"copilot"`` is safe because it does not collide with any real user
    or team slug that could legitimately request a review (GitHub reserves
    the ``copilot`` name).
    """
    if not isinstance(login, str):
        return False
    return COPILOT_REVIEWER_LOGIN in login.lower()


# GraphQL query used to discover the PR node ID and the Copilot bot's ID
# in a single round-trip. ``suggestedActors`` with ``CAN_BE_ASSIGNED``
# returns Copilot when the repository has Copilot code review enabled.
_COPILOT_LOOKUP_QUERY = """
query CopilotLookup($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) { id }
    suggestedActors(first: 100, capabilities: [CAN_BE_ASSIGNED]) {
      nodes {
        __typename
        login
        ... on Bot { id }
        ... on User { id }
      }
    }
  }
}
"""


# NOTE: The GraphQL requestReviews mutation with userIds was the prior approach,
# but it fails for Bot node IDs because userIds only accepts User-type nodes.
# Passing a Bot node ID (e.g. BOT_kgDOC9w8XQ) raises:
#   "Could not resolve to User node with the global id of 'BOT_...'."
# The REST endpoint uses string logins instead, which works for Bot accounts.
# The constant below is preserved as documentation of the failure mode.
_REQUEST_REVIEWS_MUTATION = """
mutation RequestCopilotReview($pullRequestId: ID!, $userIds: [ID!]!) {
  requestReviews(input: {pullRequestId: $pullRequestId, userIds: $userIds, union: true}) {
    pullRequest {
      reviewRequests(first: 100) {
        nodes {
          requestedReviewer {
            __typename
            ... on Bot { login }
            ... on User { login }
          }
        }
      }
    }
  }
}
"""


def _graphql_error_message(result: dict) -> str:
    """Return the first GraphQL error message from a response, or ''."""
    errors = result.get("errors") if isinstance(result, dict) else None
    if isinstance(errors, list) and errors:
        first = errors[0] or {}
        if isinstance(first, dict):
            return first.get("message", "") or ""
    return ""


def _is_already_reviewed_error(message: str) -> bool:
    """Detect the GraphQL error GitHub returns when Copilot has already
    reviewed the PR and a fresh review must be requested by dismissing
    the prior one first.
    """
    if not isinstance(message, str) or not message:
        return False
    lowered = message.lower()
    return (
        "already" in lowered
        or "cannot be requested" in lowered
        or "duplicate" in lowered
    )


def _lookup_copilot_review_targets(owner: str, repo: str,
                                   pr_number: int) -> dict:
    """Discover the PR's GraphQL node ID and Copilot's bot ID.

    Returns a dict with ``ok=True`` and ``pr_id``/``bot_id`` keys on
    success. Returns an ``ok=False`` dict on transport errors, on a
    missing PR, or when Copilot is not in the suggested-actors list
    (which means Copilot code review is not enabled for the repo).
    """
    variables = {"owner": owner, "name": repo, "number": pr_number}
    result = _github_graphql_request(_COPILOT_LOOKUP_QUERY, variables)

    if isinstance(result, dict) and result.get("ok") is False:
        return result
    if not isinstance(result, dict) or "data" not in result:
        message = _graphql_error_message(result) if isinstance(result, dict) else ""
        return {"ok": False,
                "error": message or "Unexpected response from GitHub GraphQL"}
    if "errors" in result:
        return {"ok": False,
                "error": f"GraphQL error: {_graphql_error_message(result)}"}

    repo_data = (result.get("data") or {}).get("repository") or {}
    pr = repo_data.get("pullRequest") or {}
    pr_id = pr.get("id")
    if not pr_id:
        return {"ok": False, "error": f"PR #{pr_number} not found"}

    actors = ((repo_data.get("suggestedActors") or {}).get("nodes") or [])
    bot_id = None
    bot_login = None
    for actor in actors:
        if not isinstance(actor, dict):
            continue
        if actor.get("__typename") != "Bot":
            continue
        if _is_copilot_login(actor.get("login", "")):
            bot_id = actor.get("id")
            bot_login = actor.get("login")
            break

    if not bot_id:
        return {"ok": False, "error": (
            "Copilot is not available as a reviewer for this repository. "
            "Enable Copilot code review in the repository settings.")}

    return {"ok": True, "pr_id": pr_id, "bot_id": bot_id, "bot_login": bot_login}


def _request_reviews_via_rest(owner: str, repo: str, pr_number: int,
                              bot_login: str) -> dict:
    """Request a review via the REST endpoint using the bot's login string.

    The GraphQL ``requestReviews`` mutation's ``userIds`` field only accepts
    User-type node IDs.  Passing a Bot node ID (e.g. ``BOT_kgDOC9w8XQ``)
    raises "Could not resolve to User node with the global id of 'BOT_...'".
    The REST endpoint uses string logins instead of typed GraphQL node IDs
    and correctly handles Bot accounts such as
    ``"copilot-pull-request-reviewer"``.

    Args:
        owner: Repository owner.
        repo: Repository name.
        pr_number: Pull request number.
        bot_login: The bot's GitHub login string (e.g.
            ``"copilot-pull-request-reviewer"``), obtained from the
            CopilotLookup query's ``suggestedActors`` connection.

    Returns:
        The PR object dict (includes ``requested_reviewers``) on success,
        or an ``ok=False`` error dict on failure.
    """
    return _github_request(
        "POST",
        f"/repos/{owner}/{repo}/pulls/{pr_number}/requested_reviewers",
        {"reviewers": [bot_login]},
    )


def _copilot_in_rest_response(rest_response: dict) -> bool:
    """True when a REST requested_reviewers response includes the Copilot bot.

    Checks the ``requested_reviewers`` array in the PR object returned by
    ``POST /repos/{owner}/{repo}/pulls/{n}/requested_reviewers``.  Each
    entry has a ``login`` field; this helper accepts all login forms that
    ``_is_copilot_login`` recognises (e.g.
    ``"copilot-pull-request-reviewer"``, ``"Copilot"``,
    ``"copilot-pull-request-reviewer[bot]"``).

    Never trusts a 2xx status alone — always checks the reviewer list so
    silent no-ops are detected.
    """
    if not isinstance(rest_response, dict):
        return False
    for reviewer in (rest_response.get("requested_reviewers") or []):
        if isinstance(reviewer, dict) and _is_copilot_login(reviewer.get("login", "")):
            return True
    return False


def _request_copilot_review(owner: str, repo: str, pr_number: int) -> dict:
    """Request a GitHub Copilot review on a pull request.

    Uses the REST ``POST /repos/{owner}/{repo}/pulls/{n}/requested_reviewers``
    endpoint with the bot's login string obtained from the GraphQL
    ``suggestedActors`` query.

    The GraphQL ``requestReviews`` mutation was the prior approach, but it
    fails for Bot accounts: ``userIds`` only accepts User-type node IDs.
    Passing a Bot node ID (``BOT_kgDOC9w8XQ``) raises "Could not resolve
    to User node with the global id of 'BOT_...'".  The REST endpoint uses
    string logins and handles Bot accounts correctly.

    If Copilot has already reviewed the PR, the prior review is dismissed
    and the request is retried, making this call idempotent.

    Verifies success by checking that the bot appears in the post-request
    ``requested_reviewers`` list — never trusts a 2xx status alone.

    NOTE: These tests use mocks; the only end-to-end verification is to
    manually call the tool against a real PR and confirm Copilot appears
    in the requested_reviewers list.

    Args:
        owner: Repository owner.
        repo: Repository name.
        pr_number: Pull request number.

    Returns:
        dict with ok=True on success or ok=False with error details.
    """
    lookup = _lookup_copilot_review_targets(owner, repo, pr_number)
    if not lookup.get("ok"):
        return lookup

    bot_login = lookup["bot_login"]

    result = _request_reviews_via_rest(owner, repo, pr_number, bot_login)

    if _copilot_in_rest_response(result):
        return {"ok": True}

    error_message = result.get("error", "") if isinstance(result, dict) else ""

    # If the REST API reported "already reviewed" (or "cannot be requested"),
    # dismiss the prior Copilot review and retry once.
    if _is_already_reviewed_error(error_message):
        dismiss = _dismiss_copilot_review(owner, repo, pr_number)
        if not dismiss.get("ok"):
            return {"ok": False,
                    "error": f"Review request failed: {error_message}"}
        retry = _request_reviews_via_rest(owner, repo, pr_number, bot_login)
        if _copilot_in_rest_response(retry):
            return {"ok": True}
        retry_error = retry.get("error", "") if isinstance(retry, dict) else ""
        if retry_error:
            return {"ok": False,
                    "error": f"Retry did not add Copilot: {retry_error}"}
        return {"ok": False,
                "error": f"Retry did not add Copilot as a reviewer. Response: {retry}"}

    if error_message:
        return {"ok": False, "error": f"Review request failed: {error_message}"}

    if isinstance(result, dict) and result.get("ok") is False:
        return result

    # Request returned a 2xx-like shape but the bot is not present in
    # requested_reviewers — the request silently no-op'd.
    return {
        "ok": False,
        "error": (
            "Copilot was not added as a reviewer. The REST API call "
            "returned no errors but the post-request requested_reviewers "
            "does not include the Copilot bot. Check that Copilot code "
            "review is enabled for this repository."),
    }


@mcp.tool()
def github_request_copilot_review(
    pr_number: int = 0,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Request a GitHub Copilot automated code review on a pull request.

    Uses the REST ``POST /repos/{owner}/{repo}/pulls/{n}/requested_reviewers``
    endpoint with the bot's login string (``copilot-pull-request-reviewer``).
    The GraphQL ``requestReviews`` mutation cannot be used because its
    ``userIds`` field only accepts User-type node IDs — Copilot is a Bot
    and its node ID (``BOT_kgDOC9w8XQ``) is rejected with "Could not
    resolve to User node with the global id of 'BOT_...'.".

    Args:
        pr_number: Pull request number. If omitted, the open PR for the
            workstream/branch is looked up automatically.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint used to find the PR when pr_number is not given.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ok=True on success or ok=False with error details.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    # Direct addressing (org+repo) supplies no branch of its own. When the
    # caller also omits pr_number we'd fall through to a PR lookup with an
    # empty head filter, producing a misleading "No open PR found for
    # branch ''" error. Require an explicit branch (or pr_number) in that
    # case before even resolving the repo.
    if (org and repo) and not pr_number and not branch:
        return {
            "ok": False,
            "error": ("branch is required when using direct org/repo addressing "
                      "without a pr_number"),
            "next_steps": [
                "Pass branch=<feature-branch> so the open PR can be looked up",
                "Or pass pr_number=<number> to address the PR directly",
            ],
        }
    owner, repo, effective_branch, err = _resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    effective_pr = pr_number
    if not effective_pr:
        if not effective_branch:
            return {
                "ok": False,
                "error": "no branch available to locate an open PR",
                "next_steps": [
                    "Pass pr_number explicitly, or supply a branch / workstream_id "
                    "with a defaultBranch so the open PR can be looked up",
                ],
            }
        # Look up the open PR for the branch.
        lookup = _find_open_pr_by_branch(owner, repo, effective_branch)
        if not lookup.get("ok"):
            return lookup
        if not lookup.get("found"):
            return {"ok": False, "error": f"No open PR found for branch '{effective_branch}'"}
        effective_pr = lookup["pr"].get("number")

    _audit("github_request_copilot_review", pr_number=effective_pr)

    return _request_copilot_review(owner, repo, effective_pr)


# ---------------------------------------------------------------------------
# GitHub file and pipeline tools
# ---------------------------------------------------------------------------

# Size limit for github_read_file — reject files over this threshold.
_GITHUB_READ_FILE_SIZE_LIMIT = 1_048_576  # 1 MB


@mcp.tool()
def github_read_file(
    path: str,
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    ref: str = "",
) -> dict:
    """Read any file from a GitHub repository.

    Fetches file content via the GitHub Contents API, routed through the
    FlowTree controller proxy for authentication. The repository is
    resolved from the workstream or supplied explicitly via ``repo_url``.

    Returns the file content as text. Binary files that cannot be decoded
    as UTF-8 are rejected with a clear error. Files larger than 1 MB are
    rejected to prevent accidental large pulls — use grep tools or read
    specific line ranges for large files.

    Args:
        path: File path within the repository (e.g. ``docs/README.md``).
        workstream_id: Workstream to resolve the repository from. When
            both ``workstream_id`` and ``repo_url`` are empty, the
            resolver falls back to local-git detection (useful when the
            server runs against a developer checkout).
        repo_url: Explicit GitHub repository URL (e.g.
            ``https://github.com/owner/repo``). Overrides workstream
            resolution when provided. Scoped tokens are checked against
            the parsed owner via the workspace scope gate.
        branch: Branch to read from. Defaults to the workstream's
            ``defaultBranch`` when available, otherwise the repo's
            default branch. Ignored when ``ref`` is provided.
        ref: Git ref to read at (branch, tag, or commit SHA). Takes
            precedence over ``branch`` when both are provided.

    Returns:
        Dictionary with file content, path, ref, sha, and repo.
    """
    _require_scope("github")
    err = _check_short_strings(
        path=path, workstream_id=workstream_id, branch=branch, ref=ref,
    )
    if err:
        return err
    if not path:
        return {"ok": False, "error": "path is required"}

    _audit("github_read_file", path=path, workstream_id=workstream_id,
           branch=branch, ref=ref)

    # Resolve owner/repo
    if repo_url:
        owner_repo = _extract_owner_repo(repo_url)
        if not owner_repo:
            return {"ok": False, "error": f"Cannot parse owner/repo from: {repo_url}"}
        owner, repo = owner_repo
        _require_org_in_scope(owner)
        _current_github_org.set(owner)
        effective_branch = branch
    else:
        owner, repo, effective_branch, err = _resolve_github_repo(
            workstream_id=workstream_id, branch=branch,
        )
        if err:
            return err

    effective_ref = ref or effective_branch
    ref_suffix = f"?ref={quote(effective_ref, safe='')}" if effective_ref else ""

    result = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}{ref_suffix}",
    )

    if isinstance(result, dict) and result.get("ok") is False:
        result.setdefault("next_steps", [
            f"Verify the file exists at '{path}' on the specified ref/branch",
            "Check the repo_url or workstream_id is correct",
        ])
        return result

    # The GitHub Contents API returns a JSON array (not a dict) when the
    # supplied path refers to a directory. Surface this explicitly rather
    # than reporting a misleading "Unexpected response".
    if isinstance(result, list):
        return {
            "ok": False,
            "error": (
                f"Path '{path}' refers to a directory, not a file"
            ),
            "repo": f"{owner}/{repo}",
            "next_steps": [
                "Pass a specific file path within the directory",
                "Use git/grep tools to enumerate directory contents",
            ],
        }

    if not isinstance(result, dict):
        return {
            "ok": False,
            "error": "Unexpected response from GitHub Contents API",
        }

    # Enforce size limit before decoding
    file_size = result.get("size", 0)
    if file_size > _GITHUB_READ_FILE_SIZE_LIMIT:
        return {
            "ok": False,
            "error": (
                f"File '{path}' is {file_size:,} bytes, which exceeds the 1 MB "
                "limit. Use grep tools or read specific line ranges instead."
            ),
            "size": file_size,
            "repo": f"{owner}/{repo}",
        }

    # Decode content
    content_b64 = result.get("content", "")
    encoding = result.get("encoding", "")
    if encoding == "base64" and content_b64:
        # GitHub wraps base64 output in newlines; strip them before decoding.
        try:
            raw_bytes = base64.b64decode(content_b64.replace("\n", ""))
        except (binascii.Error, ValueError) as exc:
            return {
                "ok": False,
                "error": (
                    f"Failed to decode base64 content for '{path}': {exc}"
                ),
                "repo": f"{owner}/{repo}",
            }
        try:
            content = raw_bytes.decode("utf-8")
        except UnicodeDecodeError:
            return {
                "ok": False,
                "error": (
                    f"File '{path}' appears to be binary and cannot be returned "
                    "as text. Fetch it directly from the repository instead."
                ),
                "size": file_size,
                "repo": f"{owner}/{repo}",
            }
    else:
        content = content_b64

    return {
        "ok": True,
        "path": result.get("path", path),
        "repo": f"{owner}/{repo}",
        "ref": effective_ref or "(default branch)",
        "sha": result.get("sha", ""),
        "size": file_size,
        "content": content,
    }


@mcp.tool()
def github_pr_check_status(
    pr_number: int = 0,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Check CI pipeline status for a pull request.

    Fetches the PR's current HEAD commit SHA, then retrieves workflow runs
    and check runs for that exact commit. This answers whether the CI
    pipeline has run for the latest commit and whether it passed.

    The ``pipeline_current`` flag in the response indicates whether at
    least one workflow run targets the PR's HEAD commit SHA — if False,
    the run results shown are for an older commit.

    Args:
        pr_number: Pull request number. If omitted, the open PR for the
            workstream/branch is looked up automatically.
        workstream_id: Workstream to resolve repo from. Defaults to token
            context.
        branch: Branch hint used to find the PR when pr_number is not
            given. Defaults to the workstream's defaultBranch.
        org: GitHub org (owner) to address directly. Must be passed
            together with ``repo``. Bypasses workstream resolution;
            scoped tokens are checked against this org via the workspace
            scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        Dictionary with pr_number, head_sha, pipeline_current flag,
        overall_status, workflow_runs list, and check_runs list. Failed
        check runs include html_url and details_url for log access.
    """
    _require_scope("github")
    if org and repo:
        _require_org_in_scope(org)
    owner, repo, effective_branch, err = _resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo,
    )
    if err:
        return err

    _audit("github_pr_check_status", pr_number=pr_number,
           workstream_id=workstream_id, branch=effective_branch)

    # Resolve PR number and head SHA
    effective_pr = pr_number
    head_sha = ""
    pr_branch = effective_branch

    if effective_pr:
        pr_data = _github_request("GET", f"/repos/{owner}/{repo}/pulls/{effective_pr}")
        if isinstance(pr_data, dict) and pr_data.get("ok") is False:
            return pr_data
        if isinstance(pr_data, dict):
            head_sha = pr_data.get("head", {}).get("sha", "")
            pr_branch = pr_data.get("head", {}).get("ref", effective_branch)
    else:
        if not effective_branch:
            return {
                "ok": False,
                "error": "pr_number or branch is required to look up the PR",
                "next_steps": [
                    "Pass pr_number explicitly",
                    "Or supply workstream_id/branch so the open PR can be found",
                ],
            }
        lookup = _find_open_pr_by_branch(owner, repo, effective_branch)
        if not lookup.get("ok"):
            return lookup
        if not lookup.get("found"):
            return {
                "ok": False,
                "error": f"No open PR found for branch '{effective_branch}'",
                "next_steps": ["Pass pr_number explicitly if the PR is closed"],
            }
        pr = lookup["pr"]
        effective_pr = pr.get("number")
        head_sha = pr.get("head", {}).get("sha", "")
        pr_branch = pr.get("head", {}).get("ref", effective_branch)

    if not head_sha:
        return {"ok": False, "error": "Could not determine PR head commit SHA"}

    # Fetch workflow runs for the head SHA
    runs_result = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/actions/runs?head_sha={quote(head_sha, safe='')}",
    )

    if isinstance(runs_result, dict) and runs_result.get("ok") is False:
        return runs_result

    workflow_runs = []
    pipeline_current = False

    if isinstance(runs_result, dict):
        for run in runs_result.get("workflow_runs", []):
            if run.get("head_sha") == head_sha:
                pipeline_current = True
            workflow_runs.append({
                "run_id": run.get("id"),
                "name": run.get("name", ""),
                "status": run.get("status", ""),
                "conclusion": run.get("conclusion"),
                "head_sha": run.get("head_sha", ""),
                "created_at": run.get("created_at", ""),
                "updated_at": run.get("updated_at", ""),
                "html_url": run.get("html_url", ""),
            })

    # Fetch check runs for the head SHA
    check_result = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/commits/{head_sha}/check-runs",
    )

    if isinstance(check_result, dict) and check_result.get("ok") is False:
        return {
            "ok": False,
            "error": "Failed to fetch check runs",
            "check_runs_error": check_result,
        }

    check_runs = []
    if isinstance(check_result, dict):
        for check in check_result.get("check_runs", []):
            # check_runs are scoped to head_sha by the URL, so any returned
            # entry is also evidence that the pipeline targets the current
            # commit. workflow_runs and check_runs come from independent
            # GitHub endpoints — relying on workflow_runs alone misses cases
            # where check_runs is populated but workflow_runs is empty.
            pipeline_current = True
            check_info = {
                "id": check.get("id"),
                "name": check.get("name", ""),
                "status": check.get("status", ""),
                "conclusion": check.get("conclusion"),
                "html_url": check.get("html_url", ""),
                "started_at": check.get("started_at"),
                "completed_at": check.get("completed_at"),
            }
            if check.get("conclusion") == "failure":
                check_info["details_url"] = check.get("details_url", "")
            check_runs.append(check_info)

    # Derive overall status
    if not workflow_runs and not check_runs:
        overall = "no_runs"
    elif workflow_runs and not pipeline_current:
        overall = "stale"
    else:
        # An in-progress check (status != "completed") means CI is still
        # running even if other checks have already concluded — report
        # "pending" rather than letting completed conclusions decide.
        has_incomplete_checks = any(
            r.get("status") != "completed" for r in check_runs
        )
        conclusions = [r["conclusion"] for r in check_runs if r.get("conclusion")]
        if has_incomplete_checks:
            overall = "pending"
        elif not conclusions:
            overall = "pending"
        elif any(c == "failure" for c in conclusions):
            overall = "failure"
        elif all(c in ("success", "skipped", "neutral") for c in conclusions):
            overall = "success"
        else:
            overall = "mixed"

    next_steps: list = []
    if overall == "no_runs":
        next_steps = [
            "No workflow runs found; the pipeline may not be configured or "
            "hasn't triggered yet",
        ]
    elif overall == "stale":
        next_steps = [
            "The latest workflow run targets an older commit; push a new "
            "commit or manually re-run CI to update the status",
        ]
    elif overall == "failure":
        next_steps = [
            "Review failed check runs above for error details",
            "Use the html_url or details_url links to view full logs",
        ]
    elif overall == "success":
        next_steps = ["All checks passed; the PR is ready to review or merge"]
    elif overall == "pending":
        next_steps = ["CI is still running; check back later"]

    return {
        "ok": True,
        "pr_number": effective_pr,
        "repo": f"{owner}/{repo}",
        "head_sha": head_sha,
        "branch": pr_branch,
        "pipeline_current": pipeline_current,
        "overall_status": overall,
        "workflow_runs": workflow_runs,
        "check_runs": check_runs,
        "next_steps": next_steps,
    }


# Tracker HTTP helpers are in tracker_client.py (imported above).

# -- Tracker tools -----------------------------------------------------------

@mcp.tool()
def tracker_list_projects() -> dict:
    """List all tracker projects.

    Returns all projects with their IDs and names. Projects are globally
    visible — no workspace scope restriction.

    Returns:
        dict with ok=True and a list of projects.
    """
    _require_scope("read")
    _audit("tracker_list_projects")
    return _tracker_get("/v1/projects")


@mcp.tool()
def tracker_create_project(name: str) -> dict:
    """Create a new tracker project.

    Args:
        name: Human-readable project name (e.g., "Rings").

    Returns:
        dict with ok=True and the created project record.
    """
    _require_scope("write")
    _audit("tracker_create_project", name=name)
    return _tracker_post("/v1/projects", {"name": name})


@mcp.tool()
def tracker_update_project(project_id: str, name: str) -> dict:
    """Update an existing tracker project.

    Args:
        project_id: UUID of the project to update.
        name: New name for the project.

    Returns:
        dict with ok=True and the updated project record.
    """
    _require_scope("write")
    _audit("tracker_update_project", project_id=project_id)
    return _tracker_put(f"/v1/projects/{project_id}", {"name": name})


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
    _require_scope("write")
    _audit("tracker_delete_project", project_id=project_id)
    return _tracker_delete(f"/v1/projects/{project_id}")


@mcp.tool()
def tracker_list_releases(project_id: str = "") -> dict:
    """List tracker releases, optionally filtered by project.

    Args:
        project_id: Optional UUID to filter releases to a specific project.
            Omit to list all releases.

    Returns:
        dict with ok=True and a list of releases.
    """
    _require_scope("read")
    _audit("tracker_list_releases", project_id=project_id)
    path = "/v1/releases"
    if project_id:
        path += "?" + urlencode({"project_id": project_id})
    return _tracker_get(path)


@mcp.tool()
def tracker_create_release(name: str, project_id: str = "") -> dict:
    """Create a new tracker release.

    Args:
        name: Release name (e.g., "Rings 0.38").
        project_id: Optional UUID of the associated project.

    Returns:
        dict with ok=True and the created release record.
    """
    _require_scope("write")
    _audit("tracker_create_release", name=name, project_id=project_id)
    payload: dict = {"name": name}
    if project_id:
        payload["project_id"] = project_id
    return _tracker_post("/v1/releases", payload)


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
    _require_scope("write")
    _audit("tracker_update_release", release_id=release_id)
    payload: dict = {}
    if name:
        payload["name"] = name
    if project_id == "null":
        payload["project_id"] = None
    elif project_id:
        payload["project_id"] = project_id
    return _tracker_put(f"/v1/releases/{release_id}", payload)


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
    _require_scope("write")
    _audit("tracker_delete_release", release_id=release_id)
    return _tracker_delete(f"/v1/releases/{release_id}")


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
    _require_scope("write")
    if workstream_id:
        _require_workstream_in_scope(workstream_id)
    _audit("tracker_create_task", project_id=project_id, workstream_id=workstream_id)
    payload: dict = {"title": title, "status": status, "priority": priority}
    if description:
        payload["description"] = description
    if project_id:
        payload["project_id"] = project_id
    if release_id:
        payload["release_id"] = release_id
    if workstream_id:
        payload["workstream_id"] = workstream_id
    return _tracker_post("/v1/tasks", payload)


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
    _require_scope("read")
    _audit("tracker_get_task", task_id=task_id)
    result = _tracker_get(f"/v1/tasks/{task_id}")
    if not result.get("ok"):
        return result
    ws = (result.get("task") or {}).get("workstream_id", "")
    if ws:
        _require_workstream_in_scope(ws)
    elif _get_workspace_scopes():
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
    _require_scope("read")
    if workstream_id:
        _require_workstream_in_scope(workstream_id)
    _audit("tracker_list_tasks", project_id=project_id,
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
    result = _tracker_get(f"/v1/tasks{qs}")
    # When the caller did not specify a workstream_id, the tracker may
    # return tasks linked to workstreams outside the caller's workspace.
    # Filter them out for scoped callers so an agent can only see tasks
    # attached to a workstream in its own workspace. When workstream_id
    # was supplied, _require_workstream_in_scope above already rejected
    # the call if the workstream was out of scope, so no filtering is
    # needed in that branch.
    if result.get("ok") and not workstream_id:
        tasks = result.get("tasks") or []
        filtered_tasks = _filter_tasks_by_scope(tasks)
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
    _require_scope("write")
    current = _tracker_get(f"/v1/tasks/{task_id}")
    if not current.get("ok"):
        return current
    current_ws = (current.get("task") or {}).get("workstream_id", "")
    if current_ws:
        _require_workstream_in_scope(current_ws)
    if workstream_id and workstream_id != "null":
        _require_workstream_in_scope(workstream_id)
    _audit("tracker_update_task", task_id=task_id)
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
    return _tracker_put(f"/v1/tasks/{task_id}", payload)


@mcp.tool()
def tracker_delete_task(task_id: str) -> dict:
    """Delete a tracker task permanently.

    Args:
        task_id: UUID of the task to delete.

    Returns:
        dict with ok=True on success.
    """
    _require_scope("write")
    current = _tracker_get(f"/v1/tasks/{task_id}")
    if not current.get("ok"):
        return current
    ws = (current.get("task") or {}).get("workstream_id", "")
    if ws:
        _require_workstream_in_scope(ws)
    _audit("tracker_delete_task", task_id=task_id)
    return _tracker_delete(f"/v1/tasks/{task_id}")


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
    _require_scope("read")
    _audit("tracker_search_tasks", query=query, project_id=project_id)
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
    result = _tracker_get("/v1/search/tasks?" + urlencode(raw))
    # Search has no workstream_id parameter, so for scoped callers we
    # always have to filter results: the underlying tracker can return
    # tasks attached to any workstream in the project. An agent caller
    # must not see tasks belonging to other workspaces.
    if result.get("ok"):
        tasks = result.get("tasks") or []
        filtered_tasks = _filter_tasks_by_scope(tasks)
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
    _require_scope("read")
    _audit("tracker_project_summary", project_id=project_id)
    result = _tracker_get(f"/v1/projects/{project_id}/summary")
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
            _require_workstream_in_scope(ws_id)
            filtered_ws.append(entry)
        except PermissionError:
            pass
    summary["by_workstream"] = filtered_ws
    return result


# ---------------------------------------------------------------------------
# Workspace secrets tools
# ---------------------------------------------------------------------------


@mcp.tool()
def workspace_secret_list_names(
    workstream_id: str,
) -> dict:
    """List the names of secrets accessible to the calling workstream's workspace.

    Returns only names — no payload values. Useful for an agent to discover
    what secrets are available before calling workspace_secret_render_file.

    Args:
        workstream_id: The workstream whose workspace's secrets to list.

    Returns:
        dict with ok=True and names list, or ok=False with error.
    """
    _require_scope("read")
    _require_workstream_in_scope(workstream_id)
    _audit("workspace_secret_list_names", workstream_id=workstream_id)

    if not SHARED_SECRET:
        return {"ok": False, "error": "Shared secret not configured on ar-manager"}

    # The controller's workstream-scoped endpoints require a Bearer token in
    # the armt_tmp_ family. SHARED_SECRET (admin) is rejected here, so mint a
    # short-lived workstream token using the same shared secret.
    temp_token = _mint_temp_token(workstream_id)
    if temp_token is None:
        return {"ok": False, "error": "Unable to mint workstream token"}

    path = f"/api/secrets?workstream_id={quote(workstream_id, safe='')}"
    resp = _controller_get(path, auth_token=temp_token)
    if resp.get("ok") is False:
        return {"ok": False, "error": resp.get("error", "controller error")}
    return {"ok": True, "names": resp.get("names", [])}


@mcp.tool()
def workspace_secret_render_file(
    workstream_id: str,
    secret_name: str,
    template: str,
    output_path: str,
    mode: str = "0600",
) -> dict:
    """Fetch a workspace secret and render it into a file using a template.

    The agent supplies a template with {{key}} placeholders. The secret
    payload is fetched from the controller (the agent never sees the raw
    values), all placeholders are substituted, and the result is written
    to output_path with the specified permissions. The rendered content is
    never returned to the agent.

    Template placeholders use {{key}} syntax (double curly braces). Every
    {{key}} in the template must exist in the secret payload — unresolved
    placeholders cause an error and no file is written. Extra keys in the
    payload that are not referenced in the template are silently ignored.

    Example usage for AWS credentials:

        template = \"\"\"[default]
    aws_access_key_id = {{access_key_id}}
    aws_secret_access_key = {{secret_access_key}}
    region = {{region}}
    \"\"\"
        workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="aws-prod",
            template=template,
            output_path="~/.aws/credentials",
        )

    After this call the agent can run AWS CLI commands without ever having
    seen the credential values.

    Args:
        workstream_id: The workstream whose workspace owns the secret.
        secret_name: Name of the secret to fetch.
        template: Template string with {{key}} placeholders.
        output_path: Destination file path (~ is expanded).
        mode: Octal file permissions string, e.g. "0600" (default).

    Returns:
        dict with ok=True and output_path on success, or ok=False with
        error. The rendered content is never included in the response.
    """
    _require_scope("read")
    _require_workstream_in_scope(workstream_id)
    # Deliberately omit template from audit log — it may contain partial secrets
    # or structural hints. Log only identifying metadata.
    _audit(
        "workspace_secret_render_file",
        workstream_id=workstream_id,
        secret_name=secret_name,
        output_path=output_path,
        mode=mode,
    )

    if not SHARED_SECRET:
        return {"ok": False, "error": "Shared secret not configured on ar-manager"}

    # Validate mode before doing any I/O. int(s, 8) accepts negative numbers
    # and silently parses values outside the POSIX permission range, so check
    # the string shape and the resulting value explicitly.
    if not isinstance(mode, str) or not mode:
        return {"ok": False, "error": "mode must be a non-empty octal string"}
    mode_str = mode[1:] if mode.startswith("0") and len(mode) > 1 else mode
    if not mode_str or any(c not in "01234567" for c in mode_str):
        return {
            "ok": False,
            "error": f"mode must be octal digits 0-7 (got {mode!r})",
        }
    try:
        file_mode = int(mode, 8)
    except ValueError:
        return {"ok": False, "error": f"Invalid octal mode: {mode!r}"}
    if file_mode < 0 or file_mode > 0o777:
        return {
            "ok": False,
            "error": f"mode out of range — must be 0-0777 (got {mode!r})",
        }

    # The controller's retrieve endpoint requires a workstream-scoped temp
    # token; the admin shared secret is rejected. Mint a short-lived token.
    temp_token = _mint_temp_token(workstream_id)
    if temp_token is None:
        return {"ok": False, "error": "Unable to mint workstream token"}

    # Fetch secret payload from controller
    path = (f"/api/secrets/{quote(secret_name, safe='')}"
            f"?workstream_id={quote(workstream_id, safe='')}")
    resp = _controller_get(path, auth_token=temp_token)
    if resp.get("ok") is False:
        return {"ok": False, "error": resp.get("error", "controller error")}

    payload = resp.get("payload", {})

    # Strict placeholder resolution — every {{key}} must be present in payload
    placeholders = re.findall(r"\{\{(\w+)\}\}", template)
    missing = [p for p in placeholders if p not in payload]
    if missing:
        return {
            "ok": False,
            "error": (
                f"Template references unknown keys: {missing}. "
                f"Available keys: {sorted(payload.keys())}"
            ),
        }

    rendered = template
    for key in placeholders:
        rendered = rendered.replace(f"{{{{{key}}}}}", payload[key])

    # Atomic write: write the rendered content to a sibling temp file, fsync
    # it, set its permissions, then os.replace() onto the destination. This
    # avoids leaving a partial / empty credentials file on failure and avoids
    # races where another reader could see the file mid-write.
    expanded = os.path.expanduser(output_path)
    parent = os.path.dirname(expanded) or "."
    os.makedirs(parent, exist_ok=True)
    rendered_bytes = rendered.encode("utf-8")
    tmp_fd, tmp_path = tempfile.mkstemp(
        prefix=os.path.basename(expanded) + ".",
        suffix=".tmp",
        dir=parent,
    )
    try:
        with os.fdopen(tmp_fd, "wb") as tmp_fh:
            tmp_fh.write(rendered_bytes)
            tmp_fh.flush()
            os.fsync(tmp_fh.fileno())
        os.chmod(tmp_path, file_mode)
        os.replace(tmp_path, expanded)
    except Exception:
        # Clean up the orphan temp file on failure; never let it linger with
        # rendered secret content on disk.
        try:
            os.remove(tmp_path)
        except OSError:
            pass
        raise

    audit_log.info(
        "tool=workspace_secret_render_file secret_name=%s workstream_id=%s "
        "output_path=%s result=OK",
        secret_name, workstream_id, expanded,
    )
    return {"ok": True, "output_path": expanded}


# ---------------------------------------------------------------------------
# Server startup
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    # Default to http: ar-manager only runs as an authenticated HTTP/SSE
    # server, so http is the sole sensible default. An explicit
    # MCP_TRANSPORT=stdio (or anything else) is still rejected below.
    transport = os.environ.get("MCP_TRANSPORT", "http")
    tokens = _load_tokens()

    # ar-manager runs ONLY as an authenticated HTTP/SSE server. Both the
    # stdio transport and the former tokenless ("no-auth") mode are refused:
    # a request with no bearer token is indistinguishable from any other, so
    # the job / workspace / permission context an ar-manager token carries
    # would be silently discarded. Refuse to start rather than serve in a
    # mode where that context can be lost.
    if transport not in ("http", "sse"):
        print(
            f"ar-manager: FATAL: unsupported MCP_TRANSPORT={transport!r}. "
            "ar-manager runs only as an authenticated HTTP server; set "
            "MCP_TRANSPORT=http (or sse). Point interactive MCP clients at "
            "the public HTTPS endpoint instead of launching this file over "
            "stdio.",
            file=sys.stderr,
        )
        sys.exit(1)

    if not tokens:
        print(
            "ar-manager: FATAL: no auth tokens configured. Set "
            "AR_MANAGER_TOKENS or AR_MANAGER_TOKEN_FILE (see the README); "
            "ar-manager refuses to serve without authentication.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"ar-manager: Auth enabled with {len(tokens)} token(s)",
          file=sys.stderr)

    port = int(os.environ.get("MCP_PORT", "8010"))

    # Wrap the MCP app with auth + rate-limiting middleware.
    # Serve MCP at "/" — Claude mobile ignores the path component and always
    # sends requests to the root.
    mcp.settings.streamable_http_path = "/"
    # Run the streamable-HTTP transport STATELESS so a client is not
    # required to echo the ``mcp-session-id`` from ``initialize`` on
    # follow-up requests. The default stateful transport rejects any
    # follow-up that omits the session id with 400 "Missing session
    # ID"; OpenAI's MCP client (ChatGPT) does not resend the id, so
    # its first post-initialize call fails and the OpenAI gateway
    # surfaces a 502. Stateless mode is safe here: every tool call
    # decodes its bearer from the request's own Authorization header
    # (see BearerAuthMiddleware) rather than from session-bound
    # context, and the tools are independent request/response RPCs
    # with no server-initiated streaming, so no per-session state is
    # lost. It is also strictly more lenient for every other client.
    mcp.settings.stateless_http = True
    # Disable DNS rebinding protection — the server runs behind a
    # TLS-terminating reverse proxy (Tailscale Funnel) where the
    # Host header is the public DNS name, not localhost.
    from mcp.server.transport_security import TransportSecuritySettings
    mcp.settings.transport_security = TransportSecuritySettings(
        enable_dns_rebinding_protection=False,
    )
    try:
        app = mcp.streamable_http_app()
    except AttributeError:
        # CRITICAL: If streamable_http_app() is unavailable we cannot
        # apply auth middleware. Refuse to start rather than silently
        # running without authentication.
        print(
            "ar-manager: FATAL: Cannot apply auth middleware — "
            "streamable_http_app() not available in this MCP version. "
            "Upgrade the mcp package.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Middleware order (outermost first):
    #   Health -> RateLimit -> OAuth -> BearerAuth -> app
    # OAuth sits outside BearerAuth so its endpoints (metadata,
    # registration, authorize, token) are accessible without an
    # existing bearer token.
    from oauth import OAuthMiddleware
    issuer_url = os.environ.get("AR_MANAGER_ISSUER_URL")
    oauth_state_file = os.environ.get("AR_MANAGER_OAUTH_STATE_FILE")
    app = BearerAuthMiddleware(app, tokens, issuer_url=issuer_url)
    app = OAuthMiddleware(app, tokens, issuer_url=issuer_url,
                          state_file=oauth_state_file)
    app = RateLimitMiddleware(app, requests_per_minute=RATE_LIMIT)
    app = HealthMiddleware(app)

    # Warn if binding publicly without TLS
    print(f"ar-manager: Starting with auth on port {port}", file=sys.stderr)
    print(
        "ar-manager: WARNING: Listening on 0.0.0.0 without TLS. "
        "Bearer tokens will be transmitted in cleartext. "
        "Use a TLS-terminating reverse proxy for public deployments.",
        file=sys.stderr,
    )

    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=port)
