#!/usr/bin/env python3
"""
AR Messages MCP Server

Provides tools for Claude Code agents to send messages.  Messages are
always stored in the memory database for archival and traceability.
When a workstream URL is configured, the message is also forwarded to
the controller for notification delivery (e.g., Slack).

Configuration via environment variables:
    AR_WORKSTREAM_URL - The workstream URL provided by the controller.
                        Messages are POSTed to {url}/messages.
                        Format: http://controller/api/workstreams/{id}
                        or:     http://controller/api/workstreams/{id}/jobs/{jobId}
"""

import json
import logging
import os
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

log = logging.getLogger(__name__)

WORKSTREAM_URL = os.environ.get("AR_WORKSTREAM_URL", "")

# ---------------------------------------------------------------------------
# Memory client for storing sent messages
# ---------------------------------------------------------------------------

_COMMON_DIR = os.path.join(os.path.dirname(__file__), "..", "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)

_memory_client = None


def _get_memory_client():
    """Get or initialize the memory client.

    Retries on each call until a connection succeeds, then caches.
    """
    global _memory_client
    if _memory_client is not None:
        return _memory_client
    try:
        from memory_http_client import MemoryHTTPClient
        client = MemoryHTTPClient()
        if client.available:
            _memory_client = client
            print("ar-messages: memory client connected", file=sys.stderr)
            return client
        print("ar-messages: memory server not available", file=sys.stderr)
    except Exception as e:
        print(f"ar-messages: memory client init failed: {e}", file=sys.stderr)
    return None


def _derive_branch_context_from_controller() -> tuple[str, str]:
    """Derive repo_url and branch from the workstream URL via the controller."""
    if not WORKSTREAM_URL:
        return "", ""
    try:
        parts = WORKSTREAM_URL.split("/api/workstreams/")
        if len(parts) < 2:
            return "", ""
        base_url = parts[0]
        workstream_id = parts[1].split("/")[0]
        url = f"{base_url}/api/workstreams"
        req = Request(url, headers={"Accept": "application/json"})
        with urlopen(req, timeout=5) as resp:
            workstreams = json.loads(resp.read().decode("utf-8"))
            for ws in workstreams:
                if ws.get("workstreamId") == workstream_id:
                    repo_url = ws.get("repoUrl", "")
                    branch = ws.get("defaultBranch", "")
                    if repo_url and branch:
                        return repo_url, branch
                    print(f"ar-messages: workstream {workstream_id} found but "
                          f"repoUrl={'set' if repo_url else 'MISSING'}, "
                          f"defaultBranch={'set' if branch else 'MISSING'}",
                          file=sys.stderr)
                    return repo_url, branch
        print(f"ar-messages: workstream {workstream_id} not found in "
              f"{len(workstreams)} workstreams", file=sys.stderr)
        return "", ""
    except Exception as e:
        print(f"ar-messages: controller branch context failed: {e}", file=sys.stderr)
        return "", ""


def _derive_branch_context_from_git() -> tuple[str, str]:
    """Derive repo_url and branch from the local git repository."""
    try:
        repo_url = subprocess.run(
            ["git", "config", "--get", "remote.origin.url"],
            capture_output=True, text=True, timeout=5
        ).stdout.strip()
        branch = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5
        ).stdout.strip()
        if repo_url and branch:
            return repo_url, branch
    except Exception as e:
        print(f"ar-messages: git branch context failed: {e}", file=sys.stderr)
    return "", ""


_cached_branch_context = None


def _get_branch_context() -> tuple[str, str]:
    """Return (repo_url, branch) for the current context.

    Tries the controller first (when a workstream URL is set), then
    falls back to the local git repo.  Caches on first success; retries
    on every call until a non-empty result is obtained.
    """
    global _cached_branch_context
    if _cached_branch_context is not None:
        return _cached_branch_context

    # Try controller first, then git
    repo_url, branch = _derive_branch_context_from_controller()
    if not repo_url or not branch:
        repo_url, branch = _derive_branch_context_from_git()

    if repo_url and branch:
        _cached_branch_context = (repo_url, branch)
        print(f"ar-messages: branch context: {branch} @ {repo_url}", file=sys.stderr)
    else:
        # Don't cache failure — retry next time
        print("ar-messages: WARNING: could not determine branch context", file=sys.stderr)

    return repo_url, branch


def _store_message(text: str) -> tuple[bool, str]:
    """Store the message in the 'messages' namespace.

    Returns (True, "") on success, or (False, reason) on failure.
    """
    client = _get_memory_client()
    if client is None:
        msg = "memory server unavailable"
        print(f"ar-messages: ERROR: cannot store message ({msg})", file=sys.stderr)
        return False, msg
    try:
        repo_url, branch = _get_branch_context()
        if not repo_url or not branch:
            msg = "could not determine branch context"
            print(f"ar-messages: ERROR: cannot store message ({msg})", file=sys.stderr)
            return False, msg
        client.store(
            content=text,
            repo_url=repo_url,
            branch=branch,
            namespace="messages",
            tags=["message"],
            source="ar-messages",
        )
        return True, ""
    except Exception as e:
        msg = str(e)
        print(f"ar-messages: ERROR: failed to store message: {msg}", file=sys.stderr)
        return False, msg

# Log startup configuration to stderr for diagnostics
print(f"ar-messages: AR_WORKSTREAM_URL={'<not set>' if not WORKSTREAM_URL else WORKSTREAM_URL}",
      file=sys.stderr)

mcp = FastMCP("ar-messages")


def _notify_channel(text: str) -> dict:
    """Forward a message to the controller for channel notification.

    This is best-effort -- if the controller or notification channel is
    unavailable, the failure is logged but does not affect the caller.
    """
    if not WORKSTREAM_URL:
        return {"ok": False, "error": "AR_WORKSTREAM_URL not set"}

    url = WORKSTREAM_URL.rstrip("/") + "/messages"
    data = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=data, headers={"Content-Type": "application/json; charset=utf-8"})

    print(f"ar-messages: POST {url}", file=sys.stderr)

    try:
        with urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            result = json.loads(body) if body else {"ok": True}
            print(f"ar-messages: notification response: {result}", file=sys.stderr)
            return result
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"ar-messages: notification HTTP error {e.code}: {body[:200]}", file=sys.stderr)
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"HTTP {e.code}: {body[:200]}"}
    except URLError as e:
        print(f"ar-messages: notification failed to {url}: {e.reason}", file=sys.stderr)
        return {"ok": False, "error": f"Connection failed to {url}: {e.reason}"}
    except Exception as e:
        print(f"ar-messages: notification error: {e}", file=sys.stderr)
        return {"ok": False, "error": str(e)}


@mcp.tool()
def send_message(text: str) -> dict:
    """
    Send a message.

    The message is stored in the memory database for archival and
    traceability.  If a notification channel is configured, the message
    is also forwarded there.

    Use this tool to report status updates, results, or errors back to
    the user who initiated this task.

    Args:
        text: The message text to send.

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    # Primary: store in memory database -- this MUST succeed
    stored, storage_error = _store_message(text)

    # Secondary: forward to notification channel (best-effort)
    notification_result = _notify_channel(text)

    if not stored:
        return {"ok": False,
                "error": f"Failed to store message: {storage_error}"}

    result = {"ok": True}
    if not notification_result.get("ok"):
        result["notification_warning"] = notification_result.get(
            "error", notification_result.get("warning", "notification skipped"))
    return result


@mcp.tool()
def get_stats(period: str = "weekly", scope: str = "workstream") -> dict:
    """
    Get job timing statistics.

    Returns aggregated stats for this week and last week, including
    job counts, total time, cost, and turns.

    Args:
        period: The reporting period (default: "weekly").
        scope: "workstream" for current workstream only (default),
               "global" for all workstreams.

    Returns:
        Dictionary with thisWeek and lastWeek stats, or error details.
    """
    if not WORKSTREAM_URL:
        return {"ok": False, "error": "AR_WORKSTREAM_URL not set"}

    # Derive controller base URL and workstream ID from the workstream URL.
    # URL format: http://controller:port/api/workstreams/{id}[/jobs/{jobId}]
    try:
        parts = WORKSTREAM_URL.split("/api/workstreams/")
        if len(parts) < 2:
            return {"ok": False, "error": f"Cannot parse workstream URL: {WORKSTREAM_URL}"}
        base_url = parts[0]
        workstream_id = parts[1].split("/")[0]
        if not workstream_id:
            return {"ok": False, "error": f"Empty workstream ID in URL: {WORKSTREAM_URL}"}
    except (IndexError, ValueError):
        return {"ok": False, "error": f"Cannot parse workstream URL: {WORKSTREAM_URL}"}

    params = {"period": period}
    if scope != "global":
        params["workstream"] = workstream_id

    query = urlencode(params)
    url = f"{base_url}/api/stats?{query}"
    req = Request(url, headers={"Accept": "application/json"})

    print(f"ar-messages: GET {url}", file=sys.stderr)

    try:
        with urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            result = json.loads(body) if body else {}
            return result
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return {"ok": False, "error": f"HTTP {e.code}: {body[:200]}"}
    except URLError as e:
        return {"ok": False, "error": f"Connection failed to {url}: {e.reason}"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


if __name__ == "__main__":
    transport = os.environ.get("MCP_TRANSPORT", "stdio")
    if transport in ("http", "sse"):
        from mcp.server.transport_security import TransportSecuritySettings

        port = int(os.environ.get("MCP_PORT", "8000"))
        mcp.settings.host = "0.0.0.0"
        mcp.settings.port = port
        mcp.settings.transport_security = TransportSecuritySettings(
            enable_dns_rebinding_protection=False,
        )
        mcp.run(transport="streamable-http" if transport == "http" else "sse")
    else:
        mcp.run()
