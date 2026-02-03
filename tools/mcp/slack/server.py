#!/usr/bin/env python3
"""
AR Slack MCP Server

Provides tools for Claude Code agents to send messages back to Slack
channels via the SlackApiEndpoint HTTP server running in the
SlackBotController process.

Configuration via environment variables:
    AR_SLACK_API_URL    - Base URL of the SlackApiEndpoint (default: http://localhost:7780)
    AR_SLACK_CHANNEL_ID - Default Slack channel ID for messages
    AR_SLACK_THREAD_TS  - Default thread timestamp for replies (optional)
"""

import json
import os
import sys
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

# Configuration from environment
SLACK_API_URL = os.environ.get("AR_SLACK_API_URL", "http://localhost:7780")
DEFAULT_CHANNEL_ID = os.environ.get("AR_SLACK_CHANNEL_ID", "")
DEFAULT_THREAD_TS = os.environ.get("AR_SLACK_THREAD_TS", "")

mcp = FastMCP("ar-slack")


def _post(path: str, payload: dict) -> dict:
    """POST JSON to the SlackApiEndpoint and return the parsed response."""
    url = SLACK_API_URL.rstrip("/") + path
    data = json.dumps(payload).encode("utf-8")
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
def slack_send_message(text: str, channel_id: Optional[str] = None) -> dict:
    """
    Send a message to a Slack channel.

    Use this tool to report status updates, results, or errors back to
    the user who initiated this task via Slack.

    Args:
        text: The message text to send (supports Slack mrkdwn formatting).
        channel_id: The Slack channel ID. If omitted, uses the channel
                     that triggered this job (from AR_SLACK_CHANNEL_ID).

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    resolved_channel = channel_id or DEFAULT_CHANNEL_ID
    if not resolved_channel:
        return {"ok": False, "error": "No channel_id provided and AR_SLACK_CHANNEL_ID not set"}

    return _post("/api/slack/message", {
        "channel_id": resolved_channel,
        "text": text,
    })


@mcp.tool()
def slack_send_thread_reply(
    text: str,
    thread_ts: Optional[str] = None,
    channel_id: Optional[str] = None,
) -> dict:
    """
    Reply in a Slack thread.

    Use this to reply in the same thread where the user issued the original
    instruction, keeping the conversation organized.

    Args:
        text: The reply text (supports Slack mrkdwn formatting).
        thread_ts: The thread timestamp to reply to. If omitted, uses
                   the thread from the original message (AR_SLACK_THREAD_TS).
        channel_id: The Slack channel ID. If omitted, uses the default channel.

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    resolved_channel = channel_id or DEFAULT_CHANNEL_ID
    resolved_thread = thread_ts or DEFAULT_THREAD_TS

    if not resolved_channel:
        return {"ok": False, "error": "No channel_id provided and AR_SLACK_CHANNEL_ID not set"}
    if not resolved_thread:
        return {"ok": False, "error": "No thread_ts provided and AR_SLACK_THREAD_TS not set"}

    return _post("/api/slack/thread", {
        "channel_id": resolved_channel,
        "thread_ts": resolved_thread,
        "text": text,
    })


if __name__ == "__main__":
    mcp.run()
