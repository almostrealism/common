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
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

WORKSTREAM_URL = os.environ.get("AR_WORKSTREAM_URL", "")

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
    return _post_message(text)


@mcp.tool()
def slack_get_stats(period: str = "weekly") -> dict:
    """
    Get job timing statistics for the current workstream.

    Returns aggregated stats for this week and last week, including
    job counts, total time, cost, and turns.

    Args:
        period: The reporting period (default: "weekly").

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

    query = urlencode({"workstream": workstream_id, "period": period})
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
