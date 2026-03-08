#!/usr/bin/env python3
"""
AR Slack MCP Server

Provides tools for Claude Code agents to send messages back to Slack
via the controller's workstream API.

Configuration via environment variables:
    AR_WORKSTREAM_URL - The workstream URL provided by the controller.
                        Messages are POSTed to {url}/messages.
                        Format: http://controller/api/workstreams/{id}
                        or:     http://controller/api/workstreams/{id}/jobs/{jobId}
"""

import json
import logging
import os
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
_memory_init_attempted = False


def _get_memory_client():
    """Lazy-initialize the memory client for storing sent messages."""
    global _memory_client, _memory_init_attempted
    if _memory_client is not None:
        return _memory_client
    if _memory_init_attempted:
        return None
    _memory_init_attempted = True
    try:
        from memory_http_client import MemoryHTTPClient
        client = MemoryHTTPClient()
        if client.available:
            _memory_client = client
            print("ar-slack: memory client connected", file=sys.stderr)
            return client
        print("ar-slack: memory server not available, message archiving disabled", file=sys.stderr)
    except Exception as e:
        print(f"ar-slack: memory client init failed: {e}", file=sys.stderr)
    return None


def _derive_branch_context() -> tuple[str, str]:
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
                    return ws.get("repoUrl", ""), ws.get("defaultBranch", "")
        return "", ""
    except Exception as e:
        print(f"ar-slack: failed to derive branch context: {e}", file=sys.stderr)
        return "", ""


_cached_branch_context = None


def _get_branch_context() -> tuple[str, str]:
    """Return cached (repo_url, branch) for the current workstream."""
    global _cached_branch_context
    if _cached_branch_context is None:
        _cached_branch_context = _derive_branch_context()
    return _cached_branch_context


def _store_message_as_memory(text: str) -> None:
    """Store the sent message in the 'messages' namespace for archival."""
    client = _get_memory_client()
    if client is None:
        return
    try:
        repo_url, branch = _get_branch_context()
        if not repo_url or not branch:
            print("ar-slack: skipping message archival (no branch context)", file=sys.stderr)
            return
        client.store(
            content=text,
            repo_url=repo_url,
            branch=branch,
            namespace="messages",
            tags=["slack-message"],
            source="ar-slack",
        )
    except Exception as e:
        print(f"ar-slack: failed to archive message: {e}", file=sys.stderr)

# Log startup configuration to stderr for diagnostics
print(f"ar-slack: AR_WORKSTREAM_URL={'<not set>' if not WORKSTREAM_URL else WORKSTREAM_URL}",
      file=sys.stderr)

mcp = FastMCP("ar-slack")


def _post_message(text: str) -> dict:
    """POST a message to the workstream's /messages endpoint."""
    if not WORKSTREAM_URL:
        return {"ok": False, "error": "AR_WORKSTREAM_URL not set"}

    url = WORKSTREAM_URL.rstrip("/") + "/messages"
    data = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=data, headers={"Content-Type": "application/json; charset=utf-8"})

    print(f"ar-slack: POST {url}", file=sys.stderr)

    try:
        with urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            result = json.loads(body) if body else {"ok": True}
            print(f"ar-slack: response: {result}", file=sys.stderr)
            return result
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"ar-slack: HTTP error {e.code}: {body[:200]}", file=sys.stderr)
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"HTTP {e.code}: {body[:200]}"}
    except URLError as e:
        print(f"ar-slack: Connection failed to {url}: {e.reason}", file=sys.stderr)
        return {"ok": False, "error": f"Connection failed to {url}: {e.reason}"}
    except Exception as e:
        print(f"ar-slack: Unexpected error: {e}", file=sys.stderr)
        return {"ok": False, "error": str(e)}


@mcp.tool()
def slack_send_message(text: str) -> dict:
    """
    Send a message to a Slack channel.

    Use this tool to report status updates, results, or errors back to
    the user who initiated this task via Slack.

    Args:
        text: The message text to send (supports Slack mrkdwn formatting).

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    result = _post_message(text)
    if result.get("ok"):
        _store_message_as_memory(text)
    return result


@mcp.tool()
def slack_get_stats(period: str = "weekly", scope: str = "workstream") -> dict:
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

    print(f"ar-slack: GET {url}", file=sys.stderr)

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
