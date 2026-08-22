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
    AR_MEMORY_REFORMULATED  - "1" to show reformulated memory text by default
                              (beta; off by default, originals are shown)
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

# Memory entries store the author's text and the Consultant's rewrite of it
# in one row; memory_text resolves which one a response shows. It has no
# heavy dependencies, so it is imported eagerly unlike the memory/LLM clients.
from memory_text import prefers_reformulated, present, projected

_memory_client = None
_memory_init_failed = False
_llm_backend = None
_docs_retriever = None
_docs_init_failed = False
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


def _get_docs():
    """Lazy-initialize the documentation retriever.

    The corpus is baked into the container image (see ``AR_DOCS_DIR`` in the
    Dockerfile). When it is absent — a source checkout without docs, or an
    older image — retrieval is skipped rather than failed: documentation
    grounding enriches a memory answer, it is not what the caller asked for.
    """
    global _docs_retriever, _docs_init_failed
    if _docs_retriever is not None:
        return _docs_retriever
    if _docs_init_failed:
        return None
    with _init_lock:
        if _docs_retriever is not None:
            return _docs_retriever
        if _docs_init_failed:
            return None
        try:
            from docs_retriever import DocsRetriever
            retriever = DocsRetriever()
            if not retriever.docs_dir.is_dir():
                raise FileNotFoundError(f"no docs corpus at {retriever.docs_dir}")
            _docs_retriever = retriever
            print(f"ar-manager: docs corpus at {retriever.docs_dir}",
                  file=sys.stderr)
            return _docs_retriever
        except (ImportError, OSError) as e:
            print(f"ar-manager: documentation retrieval unavailable: {e}",
                  file=sys.stderr)
            _docs_init_failed = True
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
import repo_config  # noqa: E402

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
# Verbs that make a commit reference a READ rather than an instruction to
# produce one. "diff commit 123 against its parent" names an existing commit;
# "Commit 1: add the parser" plans a new one. The bare commit-number pattern
# below cannot tell them apart on its own, and rejecting the read case sent
# operators hunting for the allow_commit_language escape hatch.
# Kept deliberately narrow. Generic words that merely often appear near a
# commit reference — "before", "after", "since", "check" — would exempt
# instructions too ("commit this before running the tests"), turning a
# narrowed heuristic into a disabled one. Only verbs that make the commit the
# OBJECT OF AN INSPECTION belong here.
_COMMIT_READ_CONTEXT = re.compile(
    r"\b(?:diff|compare|revert|reverting|inspect|examine|review|reviewing|"
    r"cherry-?pick|analyse|analyze|look\s+at|refer\s+to|based\s+on)\b",
    re.IGNORECASE)

# Each entry is (pattern, reason, exemption). A line matching `pattern` is a
# violation unless `exemption` also matches it — which is how a read-only
# reference to an existing commit is distinguished from an instruction to
# create commits. Most patterns need no exemption because their wording is
# already imperative.
_COMMIT_SEQUENCING_PATTERNS = [
    (re.compile(r"\bcommit\s+\d+\b", re.IGNORECASE),
     "commit-number phrase (e.g. \"Commit 1\", \"commit 2\")",
     _COMMIT_READ_CONTEXT),
    (re.compile(r"\bfirst\s+commit\b", re.IGNORECASE),
     '"first commit" phrase', None),
    (re.compile(r"\bnext\s+commit\b", re.IGNORECASE),
     '"next commit" phrase', None),
    (re.compile(r"\bfinal\s+commit\b", re.IGNORECASE),
     '"final commit" phrase', None),
    (re.compile(r"\bas\s+(?:its\s+own|separate|individual)\s+commits?\b", re.IGNORECASE),
     '"as separate/individual commits" phrase', None),
    (re.compile(r"\b(?:in|across|over)\s+\d+\s+commits?\b", re.IGNORECASE),
     '"in/across/over N commits" phrase', None),
    (re.compile(
        r"\b(?:your|the)\s+commit\s+message\s+(?:should|will|must)\b", re.IGNORECASE),
     '"commit message should/will/must" phrase', None),
    (re.compile(
        r"\bcommit\s+(?:this|that|each|the)\s+(?:as|with|before)\b", re.IGNORECASE),
     '"commit this/that/each/the as/with/before" phrase', None),
    (re.compile(
        r"\bcommit\s+(?:between|after|before)\s+(?:each|every)\b", re.IGNORECASE),
     '"commit between/after/before each/every" phrase', None),
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
        for pattern, reason, exemption in _COMMIT_SEQUENCING_PATTERNS:
            if pattern.search(line):
                if exemption is not None and exemption.search(line):
                    continue
                snippet = line.strip()[:120]
                violations.append((lineno, snippet, reason))
                break  # one violation entry per line, first pattern wins
    return violations






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






def _archive_many(workstream_ids, archive_slack_channel: bool,
                  archive: bool) -> dict:
    """Apply archive or unarchive to each id, reporting per-id outcomes.

    Sequential and independent: one workstream that refuses to archive — the
    usual cause being a job still running on it — must not decide the fate of
    the others in the batch. The caller wants to know which moved and which
    did not, which is why a partial failure is still a successful call.

    Args:
        workstream_ids: Ids in any shape :func:`_parse_completion_listeners`
            accepts — a list, a comma-separated string, or a JSON array.
        archive_slack_channel: Passed through to the archive path; ignored
            when unarchiving.
        archive: True to archive, False to unarchive.

    Returns:
        Dictionary with ``results`` (one entry per id, in the order given),
        ``succeeded`` and ``failed`` counts.
    """
    ids = _parse_completion_listeners(workstream_ids)
    if not ids:
        return {
            "ok": False,
            "error": "No workstream ids supplied.",
            "next_steps": ["Pass a list, a comma-separated string, or a JSON array"],
        }

    seen = set()
    results = []
    for workstream_id in ids:
        if workstream_id in seen:
            # A repeated id would otherwise archive once and then report a
            # confusing second outcome for the same workstream.
            continue
        seen.add(workstream_id)

        if archive:
            outcome = workstream_archive(
                workstream_id=workstream_id,
                archive_slack_channel=archive_slack_channel,
            )
        else:
            outcome = workstream_unarchive(workstream_id=workstream_id)

        entry = {"workstream_id": workstream_id,
                 "ok": bool(outcome.get("ok", True))}
        for field in ("error", "archivedAt", "activeJobIds",
                      "slackChannelArchived", "slackChannelArchiveError"):
            if isinstance(outcome, dict) and field in outcome:
                entry[field] = outcome[field]
        results.append(entry)

    succeeded = sum(1 for r in results if r["ok"])
    return {
        "ok": True,
        "results": results,
        "succeeded": succeeded,
        "failed": len(results) - succeeded,
    }








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

    # Resolve repository URL and base branch. The base is left empty here and
    # resolved from GitHub below once owner/repo are known, so a repository
    # whose default branch is not "master" branches from the right place.
    effective_repo = None
    effective_base = ""

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
        effective_base = ws.get("baseBranch", "")

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
    if not effective_base:
        effective_base = github_api.default_branch(owner, repo)

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


def _resolve_scope_context(
    scope: str = "repo",
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
) -> tuple[str, str, Optional[dict]]:
    """Turn a ``scope`` selector into the repo/branch filters it implies.

    The three scopes differ in how much they narrow, not in where the values
    come from — that stays :func:`_resolve_branch_context`. Shared by every
    memory tool that exposes ``scope`` so the selector cannot come to mean
    different things in different tools.

    Args:
        scope: ``repo`` (current repository, every branch), ``branch``
            (one branch of it), or ``all`` (no filtering).
        repo_url: Explicit repository URL.
        branch: Explicit branch name.
        workstream_id: Workstream to resolve repo/branch from.

    Returns:
        ``(repo_url, branch, error_dict_or_None)``. Either filter may be
        empty, which means "do not narrow on this".
    """
    if scope not in ("repo", "branch", "all"):
        return ("", "", {
            "ok": False,
            "error": f"Invalid scope '{scope}'. Must be 'repo', 'branch', or 'all'.",
        })

    if scope == "all" and not repo_url and not workstream_id:
        # Explicitly requested: search everything, no filtering.
        return ("", "", None)

    if scope == "branch":
        # Need both repo and branch — use the strict resolver.
        if workstream_id or not (repo_url and branch):
            return _resolve_branch_context(
                workstream_id=workstream_id, repo_url=repo_url, branch=branch,
                require_branch=True,
            )
        return (repo_url, branch, None)

    # scope == "repo" (or "all" with an explicit repo/workstream) — need at
    # least repo_url, and do not narrow to a branch unless one was given.
    effective_repo, effective_branch = repo_url, branch
    if workstream_id or not repo_url:
        effective_repo, effective_branch, err = _resolve_branch_context(
            workstream_id=workstream_id, repo_url=repo_url, branch=branch,
            require_branch=False,
        )
        if err:
            return ("", "", err)
    if scope == "repo" and not branch:
        effective_branch = ""
    return (effective_repo, effective_branch, None)


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
    reformulated: bool = False,
) -> dict:
    """Search agent memories with optional LLM synthesis.

    Retrieves semantically similar memories from the ar-memory server.
    If an LLM backend is available, provides a synthesized summary.
    Can resolve repo_url/branch from workstream_id if provided.

    Memory text is returned as its author wrote it. Memories stored through
    the Consultant's ``remember`` tool also carry a rewritten version
    ("reformulation"), a beta feature whose quality is still under
    development; ask for it with ``reformulated`` when evaluating the
    rewrite itself.

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
        reformulated: When true, return the Consultant's rewrite of each
            memory instead of the original text, with the original included
            alongside it for comparison. Beta — off by default.

    Returns:
        Dictionary with memories and optional summary. Each memory carries
        ``text_source`` recording which version of the text is shown. When a
        documentation corpus is available the summary is grounded in it too,
        and ``doc_references`` lists the documents consulted.
    """
    _require_scope("memory-read")
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

    effective_repo, effective_branch, err = _resolve_scope_context(
        scope=scope, repo_url=repo_url, branch=branch,
        workstream_id=workstream_id,
    )
    if err:
        return err

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

    memories, notice = present(
        memories,
        reformulated=reformulated or repo_config.repo_setting(
            effective_repo, "preferReformulatedOnRead", prefers_reformulated(),
        ),
    )

    # Ground the summary in documentation as well as memories, so a memory
    # that has gone stale against the current docs can be spotted. Both the
    # corpus and the model are optional: either being absent costs part of
    # the summary, never the memories.
    doc_context = ""
    doc_refs = []
    docs = _get_docs()
    if docs is not None:
        try:
            doc_retrieval = docs.get_context_for_query(query)
            doc_context = doc_retrieval.get("context", "")
            doc_refs = sorted({
                r["file"] for r in doc_retrieval.get("markdown_results", [])
            })
            doc_refs.extend(doc_retrieval.get("html_refs", []))
        except OSError as e:
            logging.getLogger("ar-manager").warning(
                "Documentation retrieval failed for %r: %s", query, e)

    # Attempt LLM synthesis. The memories are the substance of the response
    # and are returned either way; synthesis is a convenience over them.
    summary = None
    degraded_reason = None
    llm = _get_llm()
    if llm is None:
        degraded_reason = "no inference backend could be constructed"
    else:
        try:
            from inference import SYSTEM_PROMPT

            mem_text = ""
            for i, m in enumerate(memories, 1):
                score = m.get("score", "?")
                mem_text += f"### Memory {i} (similarity: {score})\n{m.get('content', '')}\n\n"

            sections = []
            if doc_context:
                sections.append(f"## Relevant Documentation\n\n{doc_context}")
            sections.append(f"## Retrieved Memories\n\n{mem_text}")
            sections.append(
                f"## Task\n\nThe user searched for: \"{query}\"\n\n"
                "Summarize the retrieved memories. Highlight key findings and "
                "any decisions or progress notes. Where the documentation "
                "above contradicts a memory, say so — a memory can be stale. "
                "Be concise (2-4 sentences)."
            )
            prompt = "\n\n".join(sections)
            # synthesize() reports an unreachable model as a value rather
            # than raising, and re-probes health so a recovered backend is
            # picked up without restarting this server.
            synthesis = llm.synthesize(prompt, system=SYSTEM_PROMPT)
            if synthesis.degraded:
                degraded_reason = synthesis.reason
            else:
                summary = synthesis.text
        except Exception as e:
            degraded_reason = f"LLM synthesis failed: {e}"

    result = {
        "ok": True,
        "memories": [
            projected(m, (
                "id", "content", "score", "tags", "created_at",
                "repo_url", "branch",
            ))
            for m in memories
        ],
        "count": len(memories),
        "next_steps": [
            "Use workstream_context for a full branch history",
            "Use memory_store to add new memories",
        ],
    }

    if doc_refs:
        result["doc_references"] = doc_refs

    if summary:
        result["summary"] = summary
    elif degraded_reason:
        result["degraded"] = True
        result["note"] = (
            f"No summary was synthesized ({degraded_reason}). The memories "
            "field is complete and unaffected — memory retrieval does not "
            "depend on the inference backend."
        )
    if notice:
        result["notice"] = notice

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
    reformulate: Optional[bool] = None,
) -> dict:
    """Store a memory from an external client.

    Either ``workstream_id`` or (``repo_url`` + ``branch``) is required to
    identify the branch context for the memory.  When neither is supplied,
    the workstream bound to the in-flight request's HMAC temp token is
    used — so a job-scoped agent call with only ``content`` succeeds and
    stores the memory against the job's workstream branch automatically.

    When reformulation is enabled the note is rewritten to match project
    terminology before storage, and **both** versions are kept: the rewrite
    is what gets embedded and ranked, the text you wrote is preserved
    alongside it and is what retrieval returns by default.

    Reformulation never costs you the memory. If no inference backend is
    reachable, your text is stored unreformulated and the response says so.

    Args:
        content: The text content to store.
        workstream_id: Resolves to repo_url/branch via workstream config.
        repo_url: Repository URL.
        branch: Branch name.
        namespace: Logical grouping.
        tags: Optional tags for categorization.
        source: Optional source identifier.
        reformulate: Whether to rewrite the note before storing. Defaults to
            the repository's ``reformulateOnStore`` setting.

    Returns:
        Dictionary with the created entry. ``reformulated_stored`` reports
        whether a rewrite was actually stored.
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

    want_reformulation = (
        reformulate if reformulate is not None
        else repo_config.repo_setting(effective_repo, "reformulateOnStore")
    )

    rewrite = None
    degraded_reason = None
    if want_reformulation:
        llm = _get_llm()
        if llm is None:
            degraded_reason = "no inference backend could be constructed"
        else:
            synthesis = llm.reformulate(content)
            if synthesis.degraded:
                degraded_reason = synthesis.reason
            else:
                rewrite = synthesis.text

    try:
        if rewrite:
            entry = client.store_dual(
                original=content,
                reformulated=rewrite,
                repo_url=effective_repo,
                branch=effective_branch,
                namespace=namespace,
                tags=tags,
                source=source,
            )
        else:
            # Storing the author's own words is always safe. The refusal the
            # Consultant used to apply here was guarding against writing a
            # backend-down passthrough dump into the corpus — model output,
            # not author text — and MemoryStore.is_passthrough_dump rejects
            # that shape at the store regardless.
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
    entry["reformulated_stored"] = rewrite is not None
    if want_reformulation and degraded_reason:
        entry["degraded"] = True
        entry["note"] = (
            f"Stored your original text unreformulated ({degraded_reason}). "
            "The memory is saved and searchable; only the rewrite is missing."
        )
    entry["next_steps"] = [
        "Use memory_recall to search for this and other memories",
        "Use workstream_context to see all memories for this branch",
    ]
    return entry


@mcp.tool()
def memory_namespaces(
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
    scope: str = "repo",
) -> dict:
    """List every memory namespace with its entry count and latest-write time.

    Use this to discover where memories live and when each namespace was last
    written — for example to find which namespace a recent hand-off note landed
    in, without guessing namespace names and issuing a separate ``memory_recall``
    for each. Namespaces are ordered most-recently-written first, so the
    freshest activity is at the top.

    Args:
        repo_url: Optional repository URL filter.
        branch: Optional branch name filter.
        workstream_id: Optional workstream to resolve repo/branch from.
        scope: Which memories to count — ``repo`` (default) covers the
            current repository across all branches; ``branch`` narrows to
            one branch of it; ``all`` counts every repository in the store.

    Returns:
        Dictionary with ``namespaces`` (a list of
        ``{namespace, count, latest_created_at, latest_id}`` dicts, newest
        first) and ``count`` (the number of namespaces).
    """
    _require_scope("memory-read")
    err = _check_short_strings(
        repo_url=repo_url, branch=branch, workstream_id=workstream_id,
    )
    if err:
        return err
    _audit("memory_namespaces", scope=scope, branch=branch)

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

    effective_repo, effective_branch, err = _resolve_scope_context(
        scope=scope, repo_url=repo_url, branch=branch,
        workstream_id=workstream_id,
    )
    if err:
        return err

    try:
        stats = client.namespace_stats(
            repo_url=effective_repo or None,
            branch=effective_branch or None,
        )
    except ConnectionError as e:
        return {"ok": False, "error": f"Namespace lookup failed: {e}"}

    return {
        "ok": True,
        "namespaces": stats,
        "count": len(stats),
        "next_steps": [
            "Use memory_recall with a namespace from this list to read it",
            "Use workstream_context for the full narrative of a branch",
        ],
    }


@mcp.tool()
def consult(
    question: str,
    context: str = "",
    keywords: Optional[list[str]] = None,
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
) -> dict:
    """Ask a question about the codebase, answered from its documentation.

    Searches the documentation corpus, retrieves related notes from prior
    sessions, and returns an answer grounded in what it found. The corpus
    ships inside this server, so this works from any repository rather than
    only from a checkout.

    A missing inference backend costs the synthesized answer and nothing else:
    ``sources``, ``html_refs`` and ``related_memories`` are the search results
    themselves and are returned either way. Read them directly when
    ``degraded`` is set.

    Args:
        question: The question to ask.
        context: Optional extra context — a code snippet, an error message.
        keywords: Optional search terms, used instead of extracting them from
            the question. Multi-word phrases work far better than individual
            common words: ["Features mixin", "CollectionFeatures"] rather than
            ["Features", "mixin", "default", "interface"], which match too
            many documents to narrow anything.
        repo_url: Repository whose notes to draw on. Defaults to the caller's
            workstream context.
        branch: Optional branch filter for those notes.
        workstream_id: Optional workstream to resolve repo/branch from.

    Returns:
        Dictionary with ``answer`` (or ``note`` when nothing could be
        synthesized), ``sources``, ``html_refs`` and ``related_memories``.
    """
    _require_scope("memory-read")
    err = _check_length(question, "question", MAX_PROMPT_LEN)
    if err:
        return err
    # context is concatenated into the prompt alongside the question, so it
    # carries the same bound; an unbounded snippet would push the retrieved
    # documentation out of the model's window rather than fail outright.
    err = _check_length(context, "context", MAX_PROMPT_LEN)
    if err:
        return err
    err = _check_short_strings(
        repo_url=repo_url, branch=branch, workstream_id=workstream_id,
    )
    if err:
        return err
    _audit("consult", question=question)

    docs = _get_docs()
    if docs is None:
        return {
            "ok": False,
            "error": "No documentation corpus is available to this server.",
            "next_steps": [
                "Confirm AR_DOCS_DIR points at the corpus baked into the image",
                "Use memory_recall if you only need prior notes",
            ],
        }

    from docs_retriever import keyword_guidance
    from memory_text import format_memory_context

    try:
        retrieval = (docs.get_context_for_keywords(keywords) if keywords
                     else docs.get_context_for_query(question))
    except OSError as e:
        return {"ok": False, "error": f"Documentation search failed: {e}"}

    doc_context = retrieval.get("context", "")
    doc_results = retrieval.get("markdown_results", [])
    html_refs = retrieval.get("html_refs", [])

    # Prior notes, scoped to the caller's repository. Their absence is not an
    # error — documentation alone answers most questions.
    memories = []
    client = _get_memory_client()
    if client is not None:
        effective_repo, effective_branch, _ = _resolve_scope_context(
            scope="repo", repo_url=repo_url, branch=branch,
            workstream_id=workstream_id,
        )
        try:
            memories = client.search(
                query=question, namespace="default", limit=3,
                repo_url=effective_repo or None,
                branch=effective_branch or None,
            )
        except ConnectionError as e:
            logging.getLogger("ar-manager").warning(
                "Memory search failed during consult: %s", e)

    memories, _ = present(memories, reformulated=prefers_reformulated())

    result = {
        "ok": True,
        "sources": sorted({r["file"] for r in doc_results}),
        "html_refs": html_refs,
        "related_memories": [
            {"content": m.get("content", ""), "score": m.get("score")}
            for m in memories
        ],
    }

    llm = _get_llm()
    if llm is None:
        synthesis = None
        reason = "no inference backend could be constructed"
    else:
        synthesis = llm.consult(
            question,
            doc_context=doc_context,
            memory_context=format_memory_context(memories),
            extra_context=context or None,
        )
        reason = synthesis.reason if synthesis.degraded else None

    if synthesis is None or synthesis.degraded:
        result["degraded"] = True
        result["note"] = (
            f"No answer was synthesized ({reason}). The sources, html_refs "
            "and related_memories fields were retrieved successfully and hold "
            "the documentation matching this question — read them directly."
            + keyword_guidance(keywords)
        )
        return result

    answer = synthesis.text
    if answer.strip().lower().rstrip(".") == "not documented":
        # The model read the corpus and found nothing. Say so, rather than
        # presenting "not documented" as though it were the answer.
        result["note"] = (
            "No direct answer was synthesized, but the sources and html_refs "
            "fields contain related documentation worth exploring."
            if result["sources"] or html_refs
            else "No documentation found for this query."
        ) + keyword_guidance(keywords)
    else:
        result["answer"] = answer

    return result


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


# GitHub has no `state=merged`: a merged pull request is a closed one that also
# carries `merged_at`. Everything else maps straight onto the wire value.
# Only `merged` needs a full page: it asks GitHub for closed pull requests and
# filters locally, so the merged one may not be the most recent closure. The
# others take the first result, so a single-item page is all they need.
_PR_STATE_QUERIES = {
    "open": "state=open",
    "closed": "state=closed&sort=updated&direction=desc&per_page=1",
    "merged": "state=closed&sort=updated&direction=desc&per_page=100",
    "all": "state=all&sort=updated&direction=desc&per_page=1",
}

PR_STATES = tuple(_PR_STATE_QUERIES)


def _find_pr_by_branch(owner: str, repo: str, branch: str,
                       state: str = "open") -> dict:
    """Look up a pull request for ``branch`` on ``owner/repo``.

    Centralising this avoids drift between the tools that resolve a PR by
    branch (``github_pr_find``, ``github_request_copilot_review``,
    ``github_pr_check_status``, ``workstream_context``), which is why the
    open-only and any-state variants are one function rather than two
    near-identical ones.

    Args:
        owner: GitHub org (owner).
        repo: Repository name.
        branch: Branch to search for.
        state: One of :data:`PR_STATES`. ``closed`` follows GitHub and
            includes merged pull requests; ``merged`` is the subset of those
            that were actually merged rather than abandoned.

    Returns:
        ``ok=True`` with ``found=True`` and ``pr`` (the raw GitHub object) on
        success; ``ok=True`` with ``found=False`` when the branch has no
        matching pull request; ``ok=False`` with an error when the call fails
        or returns something unexpected.
    """
    query = _PR_STATE_QUERIES.get(state)
    if query is None:
        return {
            "ok": False,
            "error": f"Invalid state '{state}'. Must be one of: "
                     + ", ".join(PR_STATES),
        }

    head = f"{owner}:{branch}"
    pr_list = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&{query}",
    )
    if isinstance(pr_list, dict) and pr_list.get("ok") is False:
        return pr_list
    if not isinstance(pr_list, list):
        return {
            "ok": False,
            "error": "Unexpected response listing pull requests",
        }

    if state == "merged":
        pr_list = [pr for pr in pr_list if pr.get("merged_at")]

    if not pr_list:
        return {"ok": True, "found": False, "branch": branch}
    return {"ok": True, "found": True, "pr": pr_list[0], "branch": branch}


def _find_open_pr_by_branch(owner: str, repo: str, branch: str) -> dict:
    """Look up the first open pull request for ``branch``."""
    return _find_pr_by_branch(owner, repo, branch, state="open")


def _find_recent_pr_by_branch(owner: str, repo: str, branch: str) -> dict:
    """Look up the most recently updated pull request for ``branch``,
    whatever its state."""
    return _find_pr_by_branch(owner, repo, branch, state="all")















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




# ---------------------------------------------------------------------------
# GitHub file and pipeline tools
# ---------------------------------------------------------------------------

# Size limit for github_read_file — reject files over this threshold.
_GITHUB_READ_FILE_SIZE_LIMIT = 1_048_576  # 1 MB










# Tracker HTTP helpers are in tracker_client.py (imported above).

# -- Tracker tools -----------------------------------------------------------



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


# ---------------------------------------------------------------------------
# Tool modules
#
# Imported last, after `mcp` and every helper above exist, because the modules
# decorate at import time and call back into this one. The names are re-exported
# so that `server.<tool>` keeps resolving for the test suite.
# ---------------------------------------------------------------------------

from workstream_tools import (  # noqa: E402
    workstream_list,
    workstream_get_status,
    workstream_get_job,
    workstream_submit_task,
    workstream_register,
    workstream_update_config,
    workstream_archive,
    workstream_unarchive,
    workstream_archive_many,
    workstream_unarchive_many,
    workstream_delete,
    workstream_context,
)
from github_tools import (  # noqa: E402
    github_pr_find,
    github_pr_review_comments,
    github_pr_conversation,
    github_pr_reply,
    github_list_open_prs,
    github_create_pr,
    github_request_copilot_review,
    github_read_file,
    github_pr_check_status,
    github_list_workflow_runs,
    github_workflow_run_status,
)
from tracker_tools import (  # noqa: E402
    tracker_list_projects,
    tracker_create_project,
    tracker_update_project,
    tracker_delete_project,
    tracker_list_releases,
    tracker_create_release,
    tracker_update_release,
    tracker_delete_release,
    tracker_create_task,
    tracker_get_task,
    tracker_list_tasks,
    tracker_update_task,
    tracker_delete_task,
    tracker_search_tasks,
    tracker_project_summary,
)
