#!/usr/bin/env python3
"""
AR Manager MCP Server

Internet-facing MCP endpoint for managing FlowTree workstreams, submitting
coding tasks, and triggering project workflows. Designed for naive clients
(Claude mobile, other AI agents) that have no repo checkout or CLAUDE.md
context.

Architecture:
    - Tier 1 tools (universal): Delegate to FlowTree controller REST API
    - Tier 2 tools (pipeline): Call GitHub API directly for workflow dispatch
      and file commits

Configuration via environment variables:
    AR_CONTROLLER_URL       - FlowTree controller base URL
                              (default: http://localhost:7780)
    AR_MANAGER_GITHUB_TOKEN - GitHub PAT for Tier 2 operations
                              (falls back to GITHUB_TOKEN)
    AR_MANAGER_TOKEN_FILE   - Path to bearer token config file
                              (default: ~/.config/ar/manager-tokens.json)
    AR_MANAGER_TOKENS       - JSON string of token config (overrides file)
    MCP_TRANSPORT           - Transport: stdio (default), http, or sse
    MCP_PORT                - Port for http/sse transport (default: 8010)
"""

import base64
import contextvars
import json
import os
import sys
import threading
from datetime import datetime, timezone
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

CONTROLLER_URL = os.environ.get("AR_CONTROLLER_URL", "http://localhost:7780")
GITHUB_TOKEN = (
    os.environ.get("AR_MANAGER_GITHUB_TOKEN", "").strip()
    or os.environ.get("GITHUB_TOKEN", "").strip()
)
TOKEN_FILE = os.environ.get(
    "AR_MANAGER_TOKEN_FILE",
    os.path.expanduser("~/.config/ar/manager-tokens.json"),
)

# Log startup configuration to stderr for diagnostics
print(f"ar-manager: AR_CONTROLLER_URL={CONTROLLER_URL}", file=sys.stderr)
print(
    f"ar-manager: GITHUB_TOKEN={'<set>' if GITHUB_TOKEN else '<not set>'}",
    file=sys.stderr,
)

# ---------------------------------------------------------------------------
# Bearer token authentication
# ---------------------------------------------------------------------------

# Per-request scope storage.  Primary: contextvars (works with asyncio).
# Fallback: threading.local (works if contextvars don't propagate into
# FastMCP's sync-tool thread pool).
_request_scopes: contextvars.ContextVar[Optional[list]] = contextvars.ContextVar(
    "_request_scopes", default=None
)
_thread_local = threading.local()


def _get_scopes() -> Optional[list]:
    """Return the scopes for the current request."""
    scopes = _request_scopes.get(None)
    if scopes is not None:
        return scopes
    return getattr(_thread_local, "scopes", None)


def _set_scopes(scopes: list) -> None:
    """Store scopes for the current request in both storage mechanisms."""
    _request_scopes.set(scopes)
    _thread_local.scopes = scopes


def _require_scope(scope: str) -> None:
    """Raise if the current request does not have the required scope.

    In no-auth mode (no tokens configured), all scopes are implicitly granted.
    """
    scopes = _get_scopes()
    if scopes is None:
        # No auth configured — permit everything
        return
    if scope not in scopes:
        raise PermissionError(
            f"Token does not have required scope: {scope}"
        )


def _load_tokens() -> Optional[list]:
    """Load token definitions from env var or file.

    Returns:
        A list of token dicts, or None if no tokens are configured.
    """
    raw = os.environ.get("AR_MANAGER_TOKENS", "").strip()
    if raw:
        try:
            data = json.loads(raw)
            return data.get("tokens", [])
        except json.JSONDecodeError:
            print("ar-manager: WARNING: AR_MANAGER_TOKENS is not valid JSON",
                  file=sys.stderr)
            return None

    if os.path.isfile(TOKEN_FILE):
        try:
            with open(TOKEN_FILE) as f:
                data = json.load(f)
            tokens = data.get("tokens", [])
            print(f"ar-manager: Loaded {len(tokens)} token(s) from {TOKEN_FILE}",
                  file=sys.stderr)
            return tokens
        except (json.JSONDecodeError, OSError) as e:
            print(f"ar-manager: WARNING: Failed to load token file: {e}",
                  file=sys.stderr)
            return None

    return None


class BearerAuthMiddleware:
    """ASGI middleware that validates Bearer tokens before passing requests
    to the wrapped application.

    Unauthenticated requests receive a 401 response. The validated token's
    scopes are stored via ``_set_scopes`` for downstream tool handlers.
    """

    def __init__(self, app, tokens: list):
        self.app = app
        # Build a lookup: token value -> scopes list
        self.token_map = {}
        for t in tokens:
            value = t.get("value", "")
            scopes = t.get("scopes", [])
            if value:
                self.token_map[value] = scopes

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = dict(scope.get("headers", []))
            auth = headers.get(b"authorization", b"").decode("utf-8", errors="replace")

            if auth.startswith("Bearer "):
                token_value = auth[7:].strip()
                if token_value in self.token_map:
                    _set_scopes(self.token_map[token_value])
                    await self.app(scope, receive, send)
                    return

            # Reject: no valid token
            await send({
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    [b"content-type", b"application/json"],
                    [b"www-authenticate", b'Bearer realm="ar-manager"'],
                ],
            })
            await send({
                "type": "http.response.body",
                "body": b'{"error":"Unauthorized: valid Bearer token required"}',
            })
            return

        # Non-HTTP scopes (lifespan, websocket) pass through
        await self.app(scope, receive, send)


# ---------------------------------------------------------------------------
# Controller HTTP helpers
# ---------------------------------------------------------------------------

def _controller_get(path: str, timeout: int = 10) -> dict:
    """GET a JSON resource from the FlowTree controller.

    Args:
        path: URL path (e.g., ``/api/health``).
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response as a dict.
    """
    url = CONTROLLER_URL.rstrip("/") + path
    req = Request(url, headers={"Accept": "application/json"})
    print(f"ar-manager: GET {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"HTTP {e.code}: {body[:300]}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def _controller_post(path: str, payload: dict, timeout: int = 15) -> dict:
    """POST a JSON payload to the FlowTree controller.

    Args:
        path: URL path (e.g., ``/api/submit``).
        payload: Dictionary to JSON-encode as the request body.
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response as a dict.
    """
    url = CONTROLLER_URL.rstrip("/") + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(
        url,
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    print(f"ar-manager: POST {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"HTTP {e.code}: {body[:300]}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ---------------------------------------------------------------------------
# GitHub API helpers
# ---------------------------------------------------------------------------

def _github_request(method: str, path: str, payload: dict = None,
                    timeout: int = 15) -> dict:
    """Make an authenticated request to the GitHub API.

    Args:
        method: HTTP method (GET, POST, PUT).
        path: API path (e.g., ``/repos/owner/repo/actions/workflows/...``).
        payload: Optional dict to JSON-encode as the request body.
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response, or a status dict for empty responses (204).
    """
    if not GITHUB_TOKEN:
        return {
            "ok": False,
            "error": "GitHub token not configured. Set AR_MANAGER_GITHUB_TOKEN or GITHUB_TOKEN.",
        }

    url = f"https://api.github.com{path}"
    data = json.dumps(payload).encode("utf-8") if payload else None
    req = Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {GITHUB_TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")

    print(f"ar-manager: {method} {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            if not body:
                return {"ok": True, "status": resp.status}
            return json.loads(body)
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            err = json.loads(body)
            return {"ok": False, "error": f"GitHub {e.code}: {err.get('message', body[:300])}"}
        except json.JSONDecodeError:
            return {"ok": False, "error": f"GitHub HTTP {e.code}: {body[:300]}"}
    except URLError as e:
        return {"ok": False, "error": f"GitHub API unreachable: {e.reason}"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def _extract_owner_repo(repo_url: str) -> Optional[tuple]:
    """Extract (owner, repo) from a GitHub URL.

    Handles HTTPS URLs (``https://github.com/owner/repo.git``)
    and SSH URLs (``git@github.com:owner/repo.git``).

    Returns:
        Tuple of (owner, repo) or None if parsing fails.
    """
    if not repo_url:
        return None
    # HTTPS
    if "github.com/" in repo_url:
        parts = repo_url.split("github.com/")[-1]
        parts = parts.rstrip("/").removesuffix(".git")
        segments = parts.split("/")
        if len(segments) >= 2:
            return (segments[0], segments[1])
    # SSH
    if "github.com:" in repo_url:
        parts = repo_url.split("github.com:")[-1]
        parts = parts.rstrip("/").removesuffix(".git")
        segments = parts.split("/")
        if len(segments) >= 2:
            return (segments[0], segments[1])
    return None


# ---------------------------------------------------------------------------
# Capability validation helpers
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

    Returns:
        The workstream dict, or None if not found.
    """
    result = _controller_get("/api/workstreams")
    if isinstance(result, list):
        for ws in result:
            if ws.get("workstreamId") == workstream_id:
                return ws
    return None


# ---------------------------------------------------------------------------
# MCP server and tool definitions
# ---------------------------------------------------------------------------

from mcp.server.fastmcp import FastMCP

mcp = FastMCP("ar-manager")


# -- Tier 1: Universal tools -----------------------------------------------

@mcp.tool()
def controller_health() -> dict:
    """Check whether the FlowTree controller is alive and responding.

    Use this as a first step to verify connectivity before calling
    other tools. No authentication scope required.

    Returns:
        Dictionary with controller status and version info.
    """
    _require_scope("read")
    result = _controller_get("/api/health")
    result["next_steps"] = [
        "Use workstream_list to see available workstreams",
    ]
    return result


@mcp.tool()
def workstream_list() -> dict:
    """List all registered workstreams with their configuration and capabilities.

    Each workstream entry includes:
    - workstreamId: unique identifier for API calls
    - channelName: associated Slack channel (if any)
    - defaultBranch: the git branch agents commit to
    - baseBranch: the base branch for new branch creation
    - repoUrl: the git repository URL
    - hasPlanningDocument: whether a plan doc is configured
    - pipelineCapable: whether Tier 2 pipeline tools will work
    - agentCount: number of connected coding agents

    Use this to discover workstreams and determine which tools are
    available for each one.

    Returns:
        Dictionary with list of workstream summaries.
    """
    _require_scope("read")
    result = _controller_get("/api/workstreams")

    if isinstance(result, list):
        return {
            "ok": True,
            "workstreams": result,
            "count": len(result),
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
    """Get job statistics for a workstream (this week and last week).

    Shows job counts, total time, cost, and turns for the specified
    workstream. Use this to monitor agent productivity and spending.

    Args:
        workstream_id: The workstream identifier (from workstream_list).
        period: Reporting period (default: "weekly").

    Returns:
        Dictionary with thisWeek and lastWeek stats.
    """
    _require_scope("read")
    params = urlencode({"workstream": workstream_id, "period": period})
    result = _controller_get(f"/api/stats?{params}")
    result["workstream_id"] = workstream_id
    result.setdefault("next_steps", [
        "Use workstream_submit_task to submit a new coding task",
        "Use workstream_list to see all workstreams",
    ])
    return result


@mcp.tool()
def workstream_submit_task(
    prompt: str,
    workstream_id: str = "",
    target_branch: str = "",
    description: str = "",
    max_turns: int = 0,
    max_budget_usd: float = 0.0,
    protect_test_files: bool = False,
    enforce_changes: bool = False,
) -> dict:
    """Submit a coding task to a FlowTree agent.

    The task prompt is sent to an available agent, which will execute it
    using Claude Code. The agent inherits the workstream's configuration
    (branch, repo, environment, allowed tools).

    You can resolve the target workstream by either:
    - Providing workstream_id explicitly
    - Providing target_branch (matched against registered workstreams)

    Args:
        prompt: The task description for the coding agent. Be specific
            about what files to change, what behavior to implement, and
            any constraints.
        workstream_id: Explicit workstream to submit to (from workstream_list).
        target_branch: Git branch to resolve workstream by (alternative to
            workstream_id).
        description: Short human-readable description of the task (shown
            in Slack notifications).
        max_turns: Maximum Claude Code turns (0 = use workstream default).
        max_budget_usd: Maximum cost in USD (0 = use workstream default).
        protect_test_files: If true, prevent the agent from modifying test files.
        enforce_changes: If true, require the agent to produce code changes.

    Returns:
        Dictionary with job_id and workstream_id on success.
    """
    _require_scope("write")

    payload = {"prompt": prompt}
    if workstream_id:
        payload["workstreamId"] = workstream_id
    if target_branch:
        payload["targetBranch"] = target_branch
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

    result = _controller_post("/api/submit", payload)

    if result.get("ok"):
        job_id = result.get("jobId", "")
        ws_id = result.get("workstreamId", workstream_id)
        result["next_steps"] = [
            f"Use workstream_get_status with workstream_id='{ws_id}' to check progress",
            "The agent will push commits to the configured branch",
            "Use workstream_list to see agent count and branch info",
        ]
    else:
        result.setdefault("next_steps", [
            "Use workstream_list to find available workstreams and their IDs",
            "Ensure at least one agent is connected (check agentCount in workstream_list)",
        ])

    return result


@mcp.tool()
def workstream_register(
    default_branch: str,
    base_branch: str = "master",
    repo_url: str = "",
    planning_document: str = "",
    channel_name: str = "",
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

    Returns:
        Dictionary with workstreamId and channel info on success.
    """
    _require_scope("write")

    payload = {"defaultBranch": default_branch}
    if base_branch:
        payload["baseBranch"] = base_branch
    if repo_url:
        payload["repoUrl"] = repo_url
    if planning_document:
        payload["planningDocument"] = planning_document
    if channel_name:
        payload["channelName"] = channel_name

    result = _controller_post("/api/workstreams", payload)

    if result.get("ok"):
        ws_id = result.get("workstreamId", "")
        steps = [
            f"Workstream '{ws_id}' is ready",
            "Use workstream_submit_task to send a coding task to this workstream",
        ]
        if not repo_url:
            steps.append(
                "Consider using workstream_update_config to set repo_url "
                "for pipeline capabilities"
            )
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

    Returns:
        Dictionary confirming the update.
    """
    _require_scope("write")

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

    if not payload:
        return {
            "ok": False,
            "error": "No fields to update. Provide at least one field.",
            "next_steps": [
                "Specify fields to update: default_branch, base_branch, "
                "repo_url, planning_document, or channel_name",
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


# -- Tier 2: Pipeline-capable workstreams only ------------------------------

@mcp.tool()
def project_create_branch(
    workstream_id: str,
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

    Args:
        workstream_id: Source workstream with repo_url (from workstream_list).
        plan_title: Short title for the plan branch (used in branch name).
        plan_content: Optional markdown content for the initial plan document.

    Returns:
        Dictionary confirming the workflow was dispatched.
    """
    _require_scope("pipeline")

    ws = _find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

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
    inputs = {}
    if plan_title:
        inputs["plan_title"] = plan_title
    if plan_content:
        inputs["plan_content"] = plan_content

    result = _github_request(
        "POST",
        f"/repos/{owner}/{repo}/actions/workflows/project-manager.yaml/dispatches",
        {"ref": ws.get("baseBranch", "master"), "inputs": inputs},
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

    ws = _find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

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

    inputs = {"branch": effective_branch}
    if plan_file:
        inputs["plan_file"] = plan_file

    result = _github_request(
        "POST",
        f"/repos/{owner}/{repo}/actions/workflows/verify-completion.yaml/dispatches",
        {"ref": ws.get("baseBranch", "master"), "inputs": inputs},
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

    ws = _find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

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

    if not commit_message:
        commit_message = f"Add plan document: {path}"

    # Check if file already exists (need current SHA for updates)
    existing = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}?ref={quote(effective_branch, safe='')}",
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


# ---------------------------------------------------------------------------
# Server startup
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    transport = os.environ.get("MCP_TRANSPORT", "stdio")
    tokens = _load_tokens()

    if tokens is None:
        print("ar-manager: WARNING: No auth tokens configured — running without authentication",
              file=sys.stderr)
    else:
        print(f"ar-manager: Auth enabled with {len(tokens)} token(s)", file=sys.stderr)

    if transport in ("http", "sse"):
        port = int(os.environ.get("MCP_PORT", "8010"))

        if tokens:
            # Wrap the MCP app with auth middleware
            try:
                app = mcp.streamable_http_app()
                app = BearerAuthMiddleware(app, tokens)
                print(f"ar-manager: Starting with auth on port {port}", file=sys.stderr)

                import uvicorn
                uvicorn.run(app, host="0.0.0.0", port=port)
            except AttributeError:
                print(
                    "ar-manager: WARNING: streamable_http_app() not available — "
                    "falling back to mcp.run() without auth wrapping",
                    file=sys.stderr,
                )
                from mcp.server.transport_security import TransportSecuritySettings
                mcp.settings.host = "0.0.0.0"
                mcp.settings.port = port
                mcp.settings.transport_security = TransportSecuritySettings(
                    enable_dns_rebinding_protection=False,
                )
                mcp.run(transport="streamable-http" if transport == "http" else "sse")
        else:
            from mcp.server.transport_security import TransportSecuritySettings
            mcp.settings.host = "0.0.0.0"
            mcp.settings.port = port
            mcp.settings.transport_security = TransportSecuritySettings(
                enable_dns_rebinding_protection=False,
            )
            print(f"ar-manager: Starting (no auth) on port {port}", file=sys.stderr)
            mcp.run(transport="streamable-http" if transport == "http" else "sse")
    else:
        mcp.run()
