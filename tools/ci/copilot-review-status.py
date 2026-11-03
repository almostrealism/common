#!/usr/bin/env python3
"""Decide whether GitHub Copilot is available to review a pull request.

Reads the PR's reviews — the GitHub ``GET /pulls/{n}/reviews`` JSON array — from
stdin and prints ``copilot_available=true`` or ``copilot_available=false`` on
stdout, for a CI step to append to ``$GITHUB_OUTPUT``.

Copilot is treated as *unavailable* when its most recent review on the pull
request is the out-of-quota refusal GitHub posts as a review body ("...the user
who requested the review has insufficient remaining quota."). That refusal is
not a review; detecting it lets ``auto-review`` record the gap in its step
summary and lets ``auto-review-submit`` stop holding its Copilot-wait delay for
a review that will not come.

The detection rule lives in one place —
``tools/mcp/manager/github_api.is_copilot_quota_notice`` — which this script
imports and reuses rather than re-deriving, so the MCP server and the pipeline
agree on what a quota notice is.

Usage:
    gh api "repos/$REPO/pulls/$PR/reviews" | copilot-review-status.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "mcp", "manager"))
import github_api  # noqa: E402


def _is_copilot_login(login):
    """True when a review author login identifies the Copilot reviewer bot.

    A standalone copy of the manager's ``_is_copilot_login`` substring test:
    importing it would pull in the whole MCP server, which this CI script must
    not do. GitHub reserves the ``copilot`` name, so the substring is safe.
    """
    return isinstance(login, str) and "copilot" in login.lower()


def copilot_available(reviews):
    """Return False when Copilot's latest review on the PR is a quota notice.

    Args:
        reviews: The reviews JSON array from the GitHub reviews endpoint.

    Returns:
        True when Copilot has no review or its latest one is a real review;
        False when its latest review is the out-of-quota refusal.
    """
    if not isinstance(reviews, list):
        return True
    return github_api.latest_copilot_quota_notice(
        reviews, _is_copilot_login) is None


def main():
    raw = sys.stdin.read()
    try:
        reviews = json.loads(raw) if raw.strip() else []
    except json.JSONDecodeError:
        # A malformed or error response must not be read as a dark Copilot:
        # default to available (wait, as before) so a transient API error does
        # not suppress a real review that is on its way.
        reviews = []
    available = copilot_available(reviews)
    print("copilot_available=" + ("true" if available else "false"))


if __name__ == "__main__":
    main()
