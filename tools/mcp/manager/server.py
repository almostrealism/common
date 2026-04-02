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
    AR_MANAGER_GITHUB_TOKEN - GitHub PAT for Tier 2 operations
                              (falls back to GITHUB_TOKEN)
    AR_MANAGER_TOKEN_FILE   - Path to bearer token config file
                              (default: ~/.config/ar/manager-tokens.json)
    AR_MANAGER_TOKENS       - JSON string of token config (overrides file)
    AR_MEMORY_URL           - ar-memory HTTP server URL (auto-discovered if not set)
    MCP_TRANSPORT           - Transport: stdio (default), http, or sse
    MCP_PORT                - Port for http/sse transport (default: 8010)
"""

import base64
import contextvars
import hmac
import json
import logging
import os
import re
import sys
import threading
import time
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

# Rate limit: requests per minute per token/IP (configurable)
RATE_LIMIT = int(os.environ.get("AR_MANAGER_RATE_LIMIT", "60"))

def _load_shared_secret() -> str:
    """Load the shared secret from file or environment variable."""
    secret_file = os.environ.get("AR_MANAGER_SHARED_SECRET_FILE", "").strip()
    if secret_file and os.path.isfile(secret_file):
        try:
            with open(secret_file) as f:
                return f.read().strip()
        except OSError as e:
            print(f"ar-manager: WARNING: Failed to read shared secret file: {e}",
                  file=sys.stderr)
    return os.environ.get("AR_MANAGER_SHARED_SECRET", "").strip()

SHARED_SECRET = _load_shared_secret()

# Input length limits
MAX_PROMPT_LEN = 50_000
MAX_CONTENT_LEN = 100_000
MAX_SHORT_STRING_LEN = 1_000

# Audit logger — writes to stderr alongside normal diagnostics
audit_log = logging.getLogger("ar-manager.audit")
audit_log.setLevel(logging.INFO)
if not audit_log.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    audit_log.addHandler(_handler)

# Paths that are never valid targets for project_commit_plan
_SENSITIVE_PATH_PREFIXES = (".github/workflows/", ".github/actions/")

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
print(
    f"ar-manager: GITHUB_TOKEN={'<set>' if GITHUB_TOKEN else '<not set>'}",
    file=sys.stderr,
)
print(f"ar-manager: AR_MANAGER_SHARED_SECRET={'<set>' if SHARED_SECRET else '<not set>'}",
      file=sys.stderr)

# ---------------------------------------------------------------------------
# Bearer token authentication
# ---------------------------------------------------------------------------

# Per-request scope storage.  Primary: contextvars (works with asyncio).
# Fallback: threading.local (works if contextvars don't propagate into
# FastMCP's sync-tool thread pool).
_request_scopes: contextvars.ContextVar[Optional[list]] = contextvars.ContextVar(
    "_request_scopes", default=None
)
_request_token_label: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_request_token_label", default=None
)
_thread_local = threading.local()


def _get_scopes() -> Optional[list]:
    """Return the scopes for the current request."""
    scopes = _request_scopes.get(None)
    if scopes is not None:
        return scopes
    return getattr(_thread_local, "scopes", None)


def _get_token_label() -> str:
    """Return the label of the token used for the current request."""
    label = _request_token_label.get(None)
    if label is not None:
        return label
    return getattr(_thread_local, "token_label", "anonymous")


def _set_scopes(scopes: list, label: str = "anonymous") -> None:
    """Store scopes and token label for the current request."""
    _request_scopes.set(scopes)
    _request_token_label.set(label)
    _thread_local.scopes = scopes
    _thread_local.token_label = label


_request_workstream_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_request_workstream_id", default=None
)
_request_job_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_request_job_id", default=None
)

def _get_token_workstream_id() -> Optional[str]:
    ws = _request_workstream_id.get(None)
    if ws is not None:
        return ws
    return getattr(_thread_local, "workstream_id", None)

def _get_token_job_id() -> Optional[str]:
    jid = _request_job_id.get(None)
    if jid is not None:
        return jid
    return getattr(_thread_local, "job_id", None)

def _set_token_context(workstream_id: str, job_id: str) -> None:
    _request_workstream_id.set(workstream_id)
    _request_job_id.set(job_id)
    _thread_local.workstream_id = workstream_id
    _thread_local.job_id = job_id


def _validate_temp_token(token_value: str) -> Optional[tuple[list, str, str, str]]:
    """Validate an HMAC temporary token.

    Token format: armt_tmp_{base64url(hmac)}:{base64url(payload)}
    Payload format: {workstream_id}:{job_id}:{expiry_epoch}

    Returns (scopes, label, workstream_id, job_id) or None if invalid.
    """
    if not SHARED_SECRET:
        return None
    if not token_value.startswith("armt_tmp_"):
        return None

    rest = token_value[len("armt_tmp_"):]
    parts = rest.split(":", 1)
    if len(parts) != 2:
        return None

    token_hmac_b64, payload_b64 = parts
    try:
        token_hmac = base64.urlsafe_b64decode(token_hmac_b64 + "==")
        payload = base64.urlsafe_b64decode(payload_b64 + "==").decode("utf-8")
    except Exception:
        return None

    # Verify HMAC
    expected_hmac = hmac.new(
        SHARED_SECRET.encode("utf-8"),
        payload.encode("utf-8"),
        "sha256"
    ).digest()
    if not hmac.compare_digest(token_hmac, expected_hmac):
        return None

    # Parse payload
    payload_parts = payload.split(":")
    if len(payload_parts) != 3:
        return None

    workstream_id, job_id, expiry_str = payload_parts
    try:
        expiry = int(expiry_str)
    except ValueError:
        return None

    # Check expiry
    if time.time() > expiry:
        return None

    scopes = ["read", "write", "memory"]
    label = f"tmp:{workstream_id}/{job_id}"
    return scopes, label, workstream_id, job_id


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


def _audit(tool_name: str, **params) -> None:
    """Log an audit entry for a tool invocation."""
    label = _get_token_label()
    sanitized = {k: (v[:80] + "...") if isinstance(v, str) and len(v) > 80 else v
                 for k, v in params.items()}
    audit_log.info("tool=%s token=%s params=%s", tool_name, label, sanitized)


def _check_length(value: str, name: str, max_len: int) -> Optional[dict]:
    """Return an error dict if *value* exceeds *max_len*, else None."""
    if len(value) > max_len:
        return {
            "ok": False,
            "error": f"'{name}' exceeds maximum length of {max_len:,} characters "
                     f"(got {len(value):,})",
        }
    return None


def _check_short_strings(**kwargs) -> Optional[dict]:
    """Validate that all provided string kwargs are within MAX_SHORT_STRING_LEN."""
    for name, value in kwargs.items():
        if isinstance(value, str) and len(value) > MAX_SHORT_STRING_LEN:
            return {
                "ok": False,
                "error": f"'{name}' exceeds maximum length of {MAX_SHORT_STRING_LEN:,} "
                         f"characters (got {len(value):,})",
            }
    return None


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

    # Paths that bypass authentication (health checks, OAuth endpoints)
    AUTH_EXEMPT_PATHS = {
        "/_health",
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource",
        "/oauth/register",
        "/oauth/authorize",
        "/oauth/token",
    }

    def __init__(self, app, tokens: list):
        self.app = app
        # Build a lookup: token value -> (scopes, label)
        self.token_entries = []
        for t in tokens:
            value = t.get("value", "")
            scopes = t.get("scopes", [])
            label = t.get("label", "unlabeled")
            if value:
                self.token_entries.append((value, scopes, label))

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            # Allow auth-exempt paths (health checks)
            path = scope.get("path", "")
            if path in self.AUTH_EXEMPT_PATHS:
                await self.app(scope, receive, send)
                return

            headers = dict(scope.get("headers", []))
            auth = headers.get(b"authorization", b"").decode("utf-8", errors="replace")

            if auth.startswith("Bearer "):
                token_value = auth[7:].strip()
                # Timing-safe comparison: iterate all entries to prevent
                # timing side-channels that reveal token existence
                matched_scopes = None
                matched_label = None
                for stored_value, scopes, label in self.token_entries:
                    if hmac.compare_digest(
                        token_value.encode("utf-8"),
                        stored_value.encode("utf-8"),
                    ):
                        matched_scopes = scopes
                        matched_label = label
                        break

                if matched_scopes is not None:
                    _set_scopes(matched_scopes, matched_label)
                    await self.app(scope, receive, send)
                    return

                # Try HMAC temporary token
                temp_result = _validate_temp_token(token_value)
                if temp_result is not None:
                    scopes, label, ws_id, job_id = temp_result
                    _set_scopes(scopes, label)
                    _set_token_context(ws_id, job_id)
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


class RateLimitMiddleware:
    """ASGI middleware implementing a per-client sliding-window rate limiter.

    The client key is the raw Bearer token (before auth validation) or the
    source IP for unauthenticated requests. Applied before auth so that
    brute-force token guessing is also rate-limited.
    """

    def __init__(self, app, requests_per_minute: int = 60):
        self.app = app
        self.rpm = requests_per_minute
        self.window = 60.0  # seconds
        self._buckets: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def _client_key(self, scope) -> str:
        """Extract a rate-limit key from the ASGI scope."""
        headers = dict(scope.get("headers", []))
        auth = headers.get(b"authorization", b"").decode("utf-8", errors="replace")
        if auth.startswith("Bearer "):
            return f"token:{auth[7:].strip()[:16]}"
        # Fall back to source IP
        client = scope.get("client")
        if client:
            return f"ip:{client[0]}"
        return "unknown"

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        key = self._client_key(scope)
        now = time.monotonic()
        with self._lock:
            timestamps = self._buckets.get(key, [])
            # Evict expired entries
            cutoff = now - self.window
            timestamps = [t for t in timestamps if t > cutoff]
            if len(timestamps) >= self.rpm:
                self._buckets[key] = timestamps
                retry_after = int(self.window - (now - timestamps[0])) + 1
                await send({
                    "type": "http.response.start",
                    "status": 429,
                    "headers": [
                        [b"content-type", b"application/json"],
                        [b"retry-after", str(retry_after).encode()],
                    ],
                })
                await send({
                    "type": "http.response.body",
                    "body": b'{"error":"Rate limit exceeded. Try again later."}',
                })
                return
            timestamps.append(now)
            self._buckets[key] = timestamps

        await self.app(scope, receive, send)


class HealthMiddleware:
    """ASGI middleware that handles ``/_health`` before the wrapped app.

    Returns HTTP 200 for ``/_health`` requests so Docker/load-balancer
    health checks work without authentication.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope.get("path") == "/_health":
            await send({
                "type": "http.response.start",
                "status": 200,
                "headers": [[b"content-type", b"application/json"]],
            })
            await send({
                "type": "http.response.body",
                "body": b'{"status":"ok"}',
            })
            return
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
        logging.getLogger("ar-manager").error(
            "Controller GET %s: HTTP %d: %s", path, e.code, body[:500])
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Controller returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}
    except Exception as e:
        logging.getLogger("ar-manager").error("Controller GET %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting controller"}


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
        logging.getLogger("ar-manager").error(
            "Controller POST %s: HTTP %d: %s", path, e.code, body[:500])
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Controller returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}
    except Exception as e:
        logging.getLogger("ar-manager").error("Controller POST %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting controller"}


# ---------------------------------------------------------------------------
# GitHub API helpers — delegated to github_api.py to reduce file size
# ---------------------------------------------------------------------------

import github_api  # noqa: E402

# Re-export so existing call sites (pipeline tools, memory tools, tests) work unchanged.
# configure() is called below after _find_workstream is defined.
_github_request = github_api._github_request
_github_graphql_request = github_api._github_graphql_request
_set_github_org = github_api._set_github_org
_extract_owner_repo = github_api._extract_owner_repo
_current_github_org = github_api._current_github_org
_resolve_github_repo = github_api._resolve_github_repo


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


# Now that _find_workstream is defined, configure the GitHub API module
github_api.configure(
    controller_url=CONTROLLER_URL,
    github_token=GITHUB_TOKEN,
    find_workstream=_find_workstream,
    get_token_workstream_id=_get_token_workstream_id,
)


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
    _audit("controller_health")
    result = _controller_get("/api/health")
    result["next_steps"] = [
        "Use workstream_list to see available workstreams",
    ]
    return result


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
    _audit("workstream_list")
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
    err = _check_short_strings(workstream_id=workstream_id, period=period)
    if err:
        return err
    _audit("workstream_get_status", workstream_id=workstream_id)
    params = urlencode({"workstream": workstream_id, "period": period})
    result = _controller_get(f"/api/stats?{params}")
    result["workstream_id"] = workstream_id
    result.setdefault("next_steps", [
        "Use workstream_submit_task to submit a new coding task",
        "Use workstream_list to see all workstreams",
    ])
    return result


@mcp.tool()
def workstream_list_jobs(
    workstream_id: str,
    limit: int = 10,
) -> dict:
    """List recent jobs for a workstream, newest first.

    Returns the most recent job events tracked by the FlowTree controller for
    the specified workstream. Each entry includes status, description,
    timestamps, and any error details.

    Args:
        workstream_id: The workstream identifier (from workstream_list).
        limit: Maximum number of jobs to return (default: 10).

    Returns:
        Dictionary with a ``jobs`` list, each entry containing jobId, status,
        description, timestamp, and optional fields such as targetBranch,
        commitHash, pullRequestUrl, errorMessage, and costUsd.
    """
    _require_scope("read")
    err = _check_short_strings(workstream_id=workstream_id)
    if err:
        return err
    _audit("workstream_list_jobs", workstream_id=workstream_id)
    params = urlencode({"limit": limit})
    result = _controller_get(f"/api/workstreams/{workstream_id}/jobs?{params}")
    if isinstance(result, list):
        return {"ok": True, "workstream_id": workstream_id, "jobs": result}
    return result


@mcp.tool()
def workstream_get_job(job_id: str) -> dict:
    """Look up a specific job event by its job ID.

    Returns the most recent status event for the given job ID. Useful for
    checking whether a previously submitted job succeeded or failed.

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
    return _controller_get(f"/api/jobs/{job_id}")


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
    started_after: str = "",
    required_labels: str = "",
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
        started_after: Epoch milliseconds timestamp. If a newer job already
            exists on the workstream, the submission is skipped and the
            response includes ``skipped: true``. Used by CI pipelines to
            avoid stale auto-resolve jobs colliding with explicit submissions.
        required_labels: Comma-separated key:value pairs specifying Node
            labels required to execute this job (e.g., "platform:macos,gpu:true").
            Only Nodes with matching labels will execute the job.

    Returns:
        Dictionary with job_id and workstream_id on success.
    """
    _require_scope("write")
    err = _check_length(prompt, "prompt", MAX_PROMPT_LEN)
    if err:
        return err
    err = _check_short_strings(
        workstream_id=workstream_id, target_branch=target_branch,
        description=description, started_after=started_after,
    )
    if err:
        return err
    _audit("workstream_submit_task", workstream_id=workstream_id,
           target_branch=target_branch, prompt_len=len(prompt))

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
    if started_after:
        payload["startedAfter"] = started_after
    if required_labels:
        labels_dict = {}
        for pair in required_labels.split(","):
            parts = pair.strip().split(":", 1)
            if len(parts) == 2 and parts[0].strip() and parts[1].strip():
                labels_dict[parts[0].strip()] = parts[1].strip()
        if labels_dict:
            payload["requiredLabels"] = labels_dict

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
    err = _check_short_strings(
        default_branch=default_branch, base_branch=base_branch,
        repo_url=repo_url, planning_document=planning_document,
        channel_name=channel_name,
    )
    if err:
        return err
    _audit("workstream_register", default_branch=default_branch)

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
    err = _check_short_strings(
        workstream_id=workstream_id, default_branch=default_branch,
        base_branch=base_branch, repo_url=repo_url,
        planning_document=planning_document, channel_name=channel_name,
    )
    if err:
        return err
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
    """Read the planning document for a workstream from GitHub.

    Fetches the file content via the GitHub Contents API, routed through
    the FlowTree controller's proxy for authentication. Only works for
    repositories hosted on GitHub.

    If no path is provided, uses the workstream's configured
    ``planningDocument`` path.

    Args:
        workstream_id: Workstream to read from (from workstream_list).
        path: File path in the repository. Defaults to the workstream's
            configured planningDocument.
        branch: Branch to read from (default: workstream's defaultBranch).

    Returns:
        Dictionary with the file content, path, and branch.
    """
    _require_scope("read")
    err = _check_short_strings(
        workstream_id=workstream_id, path=path, branch=branch,
    )
    if err:
        return err
    _audit("project_read_plan", workstream_id=workstream_id, path=path, branch=branch)

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

    effective_path = path or ws.get("planningDocument", "")
    if not effective_path:
        return {
            "ok": False,
            "error": "No planning document path configured for this workstream",
            "next_steps": [
                "Provide the path parameter explicitly",
                "Or use workstream_update_config to set planning_document",
            ],
        }

    ref_param = quote(effective_branch, safe="")
    result = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quote(effective_path, safe='/')}?ref={ref_param}",
    )

    if result.get("ok") is False:
        result.setdefault("next_steps", [
            f"Verify the file exists at '{effective_path}' on branch '{effective_branch}'",
            "Use project_commit_plan to create the planning document first",
        ])
        return result

    content_b64 = result.get("content", "")
    encoding = result.get("encoding", "")
    if encoding == "base64" and content_b64:
        try:
            content = base64.b64decode(content_b64).decode("utf-8")
        except Exception:
            content = content_b64
    else:
        content = content_b64

    return {
        "ok": True,
        "path": effective_path,
        "branch": effective_branch,
        "repo": f"{owner}/{repo}",
        "content": content,
        "sha": result.get("sha", ""),
        "next_steps": [
            "Use project_commit_plan to update this document",
            "Use workstream_submit_task to send an agent to work on the plan",
        ],
    }


# -- Tier 3: Memory tools ---------------------------------------------------


def _resolve_branch_context(
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
) -> tuple[str, str, Optional[dict]]:
    """Resolve repo_url and branch from workstream_id if needed.

    Returns:
        (repo_url, branch, error_dict_or_None)
    """
    if repo_url and branch:
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

    if not repo_url or not branch:
        return ("", "", {
            "ok": False,
            "error": "Either (repo_url + branch) or workstream_id is required",
            "next_steps": [
                "Provide repo_url and branch directly, or",
                "Provide workstream_id to resolve them from the workstream config",
            ],
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
) -> dict:
    """Search agent memories with optional LLM synthesis.

    Retrieves semantically similar memories from the ar-memory server.
    If an LLM backend is available, provides a synthesized summary.
    Can resolve repo_url/branch from workstream_id if provided.

    Args:
        query: Natural language search query.
        namespace: Memory namespace to search.
        limit: Maximum number of memories to retrieve.
        repo_url: Optional repository URL filter.
        branch: Optional branch name filter.
        workstream_id: Optional workstream to resolve repo/branch from.
        include_messages: If true, also search the "messages" namespace
            and merge results. Defaults to false.

    Returns:
        Dictionary with memories and optional summary.
    """
    _require_scope("memory")
    err = _check_short_strings(
        query=query, namespace=namespace, repo_url=repo_url,
        branch=branch, workstream_id=workstream_id,
    )
    if err:
        return err
    _audit("memory_recall", query=query, namespace=namespace)

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

    # Resolve branch context if filtering requested
    effective_repo = repo_url
    effective_branch = branch
    if workstream_id and (not repo_url or not branch):
        effective_repo, effective_branch, err = _resolve_branch_context(
            workstream_id=workstream_id, repo_url=repo_url, branch=branch,
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

    # Merge results from the "messages" namespace if requested
    if include_messages and namespace != "messages":
        try:
            msg_memories = client.search(
                query=query,
                namespace="messages",
                limit=limit,
                repo_url=effective_repo or None,
                branch=effective_branch or None,
            )
            if msg_memories:
                memories = memories + msg_memories
                memories.sort(key=lambda m: m.get("score", 999))
                memories = memories[:limit]
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
            "Use memory_branch_context for a full branch history",
            "Use memory_store to add new memories",
        ],
    }

    if summary:
        result["summary"] = summary

    return result


@mcp.tool()
def memory_branch_context(
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    namespace: str = "default",
    limit: int = 20,
    include_messages: bool = True,
    include_commits: bool = True,
    commit_limit: int = 30,
) -> dict:
    """Get all memories and optionally the commit history for a branch.

    Returns memories ordered by creation time (newest first). Can resolve
    repo_url/branch from workstream_id if provided. When ``include_commits``
    is True, fetches the branch's commit list via the GitHub Compare API
    relative to the base branch (default ``master``).

    Args:
        workstream_id: Workstream to resolve repo/branch from.
        repo_url: Repository URL to match.
        branch: Branch name to match.
        namespace: Memory namespace to search.
        limit: Maximum number of entries.
        include_messages: If true (default), also include memories from the
            "messages" namespace. Set to false to exclude Slack messages.
        include_commits: If true (default), include the list of commits on
            the branch relative to its base branch.
        commit_limit: Maximum number of commits to include (default 30).

    Returns:
        Dictionary with branch memories and optionally commits.  When
        commits are included, the response also contains ``total_commits``
        (the full number of commits on the branch) and ``initial_commit_sha``
        (the first commit on the branch relative to the base).
    """
    _require_scope("memory")
    err = _check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url,
        branch=branch, namespace=namespace,
    )
    if err:
        return err
    _audit("memory_branch_context", workstream_id=workstream_id, branch=branch)

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
        memories = client.search_by_branch(
            repo_url=effective_repo,
            branch=effective_branch,
            namespace=namespace,
            limit=limit,
        )
    except ConnectionError as e:
        return {"ok": False, "error": f"Memory branch lookup failed: {e}"}

    # Merge results from the "messages" namespace if requested.
    # Messages are appended AFTER the primary namespace results so that
    # the caller always receives up to ``limit`` memories from the
    # requested namespace.  Messages are capped at ``limit`` as well and
    # interleaved by recency, but they never displace primary memories.
    if include_messages and namespace != "messages":
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

    # Fetch last 3 jobs for this workstream from the controller
    recent_jobs = []
    if workstream_id:
        try:
            jobs_result = _controller_get(f"/api/workstreams/{workstream_id}/jobs?limit=3")
            if isinstance(jobs_result, list):
                recent_jobs = jobs_result
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
        ],
    }
    if commits is not None:
        result["commits"] = commits
        result["commit_count"] = len(commits)
        result["total_commits"] = total_commits
        if all_commits:
            result["initial_commit_sha"] = all_commits[0].get("sha", "")[:10]
    if commit_error is not None:
        result["commit_error"] = commit_error
    if recent_jobs:
        result["recent_jobs"] = recent_jobs

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

    Either workstream_id or (repo_url + branch) is required to identify
    the branch context for the memory.

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
    _require_scope("memory")
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
        "Use memory_branch_context to see all memories for this branch",
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
) -> dict:
    """Send a message for archival and optional notification.

    Messages are stored in the memory database by the controller and
    optionally forwarded to a notification channel.  Use this tool to
    report status updates, results, or errors back to the user who
    initiated this task.

    Args:
        text: The message text to send.
        workstream_id: Workstream to send the message to.  Defaults to
            the workstream from the auth token when available.
        job_id: Job to thread the message under.  Defaults to the job
            from the auth token when available.

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    _require_scope("write")

    effective_ws = workstream_id or _get_token_workstream_id() or ""
    effective_job = job_id or _get_token_job_id() or ""

    if not effective_ws:
        return {"ok": False, "error": "workstream_id is required (pass explicitly or use a job token)"}

    _audit("send_message", workstream_id=effective_ws, job_id=effective_job,
           text=text[:80])

    err = _check_length(text, "text", MAX_CONTENT_LEN)
    if err:
        return err

    # Build the controller path
    path = f"/api/workstreams/{quote(effective_ws, safe='')}"
    if effective_job:
        path += f"/jobs/{quote(effective_job, safe='')}"
    path += "/messages"

    return _controller_post(path, {"text": text})


# ---------------------------------------------------------------------------
# GitHub PR tools
# ---------------------------------------------------------------------------


@mcp.tool()
def github_pr_find(
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Find an open pull request for a branch.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch to search for. Defaults to workstream's defaultBranch.

    Returns:
        PR details if found, or error.
    """
    _require_scope("read")
    owner, repo, effective_branch, err = _resolve_github_repo(workstream_id, branch)
    if err:
        return err

    _audit("github_pr_find", workstream_id=workstream_id, branch=effective_branch)

    head = f"{owner}:{effective_branch}"
    result = _github_request("GET", f"/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&state=open")

    if isinstance(result, list):
        if not result:
            return {"ok": True, "found": False, "branch": effective_branch}
        pr = result[0]
        return {
            "ok": True,
            "found": True,
            "number": pr.get("number"),
            "title": pr.get("title"),
            "url": pr.get("html_url"),
            "state": pr.get("state"),
            "branch": effective_branch,
        }
    return result  # error dict from _github_request


@mcp.tool()
def github_pr_review_comments(
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Get code review comments on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).

    Returns:
        List of review comments.
    """
    _require_scope("read")
    owner, repo, _, err = _resolve_github_repo(workstream_id, branch)
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
) -> dict:
    """Get the conversation (issue comments) on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).

    Returns:
        List of conversation comments.
    """
    _require_scope("read")
    owner, repo, _, err = _resolve_github_repo(workstream_id, branch)
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
) -> dict:
    """Reply to a pull request review comment.

    Args:
        comment_id: The ID of the review comment to reply to.
        body: The reply text.
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint.

    Returns:
        The created reply.
    """
    _require_scope("write")
    owner, repo, _, err = _resolve_github_repo(workstream_id, branch)
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
) -> dict:
    """List open pull requests.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Filter by base branch (e.g., "master"). If empty, lists all open PRs.

    Returns:
        List of open PRs.
    """
    _require_scope("read")
    owner, repo, _, err = _resolve_github_repo(workstream_id)
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
) -> dict:
    """Create a pull request.

    Args:
        title: PR title.
        body: PR description.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Base branch (default: workstream's baseBranch or "master").
        head: Head branch (default: workstream's defaultBranch).

    Returns:
        The created PR details.
    """
    _require_scope("write")
    owner, repo, default_branch, err = _resolve_github_repo(workstream_id)
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
        return {
            "ok": True,
            "number": result["number"],
            "url": result.get("html_url"),
            "title": result.get("title"),
        }
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
            # Wrap the MCP app with auth + rate-limiting middleware
            # Serve MCP at "/" — Claude mobile ignores the path component
            # and always sends requests to the root.
            mcp.settings.streamable_http_path = "/"
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
                    "Upgrade the mcp package or remove tokens to run without auth.",
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
            app = BearerAuthMiddleware(app, tokens)
            app = OAuthMiddleware(app, tokens, issuer_url=issuer_url)
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
        else:
            from mcp.server.transport_security import TransportSecuritySettings
            mcp.settings.host = "0.0.0.0"
            mcp.settings.port = port
            # DNS rebinding protection is disabled because the server is
            # typically deployed behind a TLS-terminating reverse proxy
            # (Tailscale Funnel, Caddy, nginx) where the Host header does
            # not match localhost.
            mcp.settings.transport_security = TransportSecuritySettings(
                enable_dns_rebinding_protection=False,
            )
            print(f"ar-manager: Starting (no auth) on port {port}", file=sys.stderr)
            mcp.run(transport="streamable-http" if transport == "http" else "sse")
    else:
        mcp.run()
