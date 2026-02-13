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


if __name__ == "__main__":
    mcp.run()
