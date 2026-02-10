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
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

WORKSTREAM_URL = os.environ.get("AR_WORKSTREAM_URL", "")

mcp = FastMCP("ar-slack")


def _post_message(text: str) -> dict:
    """POST a message to the workstream's /messages endpoint."""
    if not WORKSTREAM_URL:
        return {"ok": False, "error": "AR_WORKSTREAM_URL not set"}

    url = WORKSTREAM_URL.rstrip("/") + "/messages"
    data = json.dumps({"text": text}).encode("utf-8")
    req = Request(url, data=data, headers={"Content-Type": "application/json"})

    try:
        with urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"HTTP {e.code}: {body[:200]}"}
    except URLError as e:
        return {"ok": False, "error": f"Connection failed: {e.reason}"}
    except Exception as e:
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


if __name__ == "__main__":
    mcp.run()
