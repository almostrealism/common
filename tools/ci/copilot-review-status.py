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


def parse_review_pages(raw):
    """Parse the reviews from ``gh api --paginate`` output.

    Without ``--slurp``, ``gh api --paginate`` writes one JSON array per page,
    back to back, so a PR with more reviews than one page holds is not a single
    JSON document. Every page is decoded and the arrays are concatenated, so a
    quota notice on a later page is still seen.

    Args:
        raw: The text read from stdin.

    Returns:
        The reviews from every page, or an empty list when the text is empty,
        malformed, or not a sequence of arrays — a malformed or error response
        must not be read as a dark Copilot, so it defaults to available.
    """
    decoder = json.JSONDecoder()
    reviews = []
    index = 0
    while True:
        while index < len(raw) and raw[index].isspace():
            index += 1
        if index >= len(raw):
            return reviews
        try:
            page, index = decoder.raw_decode(raw, index)
        except json.JSONDecodeError:
            return []
        if not isinstance(page, list):
            return []
        reviews.extend(page)


def main():
    available = copilot_available(parse_review_pages(sys.stdin.read()))
    print("copilot_available=" + ("true" if available else "false"))


if __name__ == "__main__":
    main()
