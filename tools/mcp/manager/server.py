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












# ---------------------------------------------------------------------------
# Messaging tools
# ---------------------------------------------------------------------------




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

from project_tools import (  # noqa: E402
    project_create_branch,
    project_verify_branch,
    project_commit_plan,
    project_read_plan,
)
from memory_tools import (  # noqa: E402
    memory_recall,
    memory_store,
    memory_namespaces,
    consult,
)
from workspace_tools import (  # noqa: E402
    workspace_update_config,
    workspace_secret_list_names,
    workspace_secret_render_file,
)
from messaging_tools import (  # noqa: E402
    send_message,
)
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
