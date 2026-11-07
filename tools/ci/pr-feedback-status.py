#!/usr/bin/env python3
"""Report whether a pull request has reviewer feedback still unaccounted for.

An automated pass must not be skipped as "own commit", nor reported complete,
while a reviewer finding stands unanswered (the PR #866 incident). This script
is the CI-side reader of that state: it fetches the PR's reviews and review
threads and prints, for a CI step to append to ``$GITHUB_OUTPUT``::

    feedback_outstanding=true|false

and, when findings are outstanding, writes one ``summary <TAB> url`` line per
finding to the path named by ``$FEEDBACK_FINDINGS_FILE`` (if set), so the
submit step can forward them to the controller for the budget-pause ping.

The "is this finding accounted for" rule lives in one place —
``tools/mcp/manager/github_api.pr_outstanding_findings`` — which this script
imports and reuses rather than re-deriving, so the submit gate, the budget ping
and the pass-completion check all agree on what "outstanding" means.

Usage:
    pr-feedback-status.py <reviews.json> <threads-graphql.json>

``reviews.json`` is the body of ``gh api repos/{repo}/pulls/{pr}/reviews
--paginate`` (one JSON array per page, concatenated). ``threads-graphql.json``
is the body of a ``gh api graphql`` query over ``reviewThreads`` (see the CI
step that invokes this script). The script reports ``false`` for a PR that
genuinely has nothing outstanding, so the caller must only trust the output
when the ``gh`` fetches succeeded: when a fetch fails, the CI step leaves the
flag unset, and the controller — unable to tell whether feedback is
outstanding — dispatches rather than skips, the fail-open direction the
incident requires.
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "mcp", "manager"))
import github_api  # noqa: E402

# Reuse the page-concatenating parser the Copilot-status script already uses for
# ``gh api --paginate`` output, so both agree on how paginated reviews decode.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module  # noqa: E402

_copilot_status = import_module("copilot-review-status")
parse_review_pages = _copilot_status.parse_review_pages


def _read(path):
    """Return the text of ``path``, or an empty string when it cannot be read."""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return ""


def normalize_threads(raw):
    """Return review threads from a ``reviewThreads`` GraphQL response.

    Flattens each thread's ``comments.nodes`` into a plain ``comments`` list so
    the shape matches what :func:`github_api.pr_outstanding_findings` expects.
    A malformed document yields an empty list.

    Args:
        raw: The text of the GraphQL response.

    Returns:
        A list of thread dicts, each ``{"isResolved", "comments": [...]}``.
    """
    try:
        document = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return []
    pull = (((document.get("data") or {}).get("repository") or {})
            .get("pullRequest") or {})
    nodes = (pull.get("reviewThreads") or {}).get("nodes") or []
    threads = []
    for node in nodes:
        if not isinstance(node, dict):
            continue
        comments = (node.get("comments") or {}).get("nodes") or []
        threads.append({
            "isResolved": node.get("isResolved"),
            "comments": [c for c in comments if isinstance(c, dict)],
        })
    return threads


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: pr-feedback-status.py <reviews.json> "
                         "<threads-graphql.json>\n")
        print("feedback_outstanding=true")
        return
    reviews = parse_review_pages(_read(sys.argv[1]))
    threads = normalize_threads(_read(sys.argv[2]))
    findings = github_api.pr_outstanding_findings(reviews, threads)
    print("feedback_outstanding=" + ("true" if findings else "false"))

    findings_file = os.environ.get("FEEDBACK_FINDINGS_FILE")
    if findings_file and findings:
        with open(findings_file, "w", encoding="utf-8") as handle:
            for finding in findings:
                summary = (finding.get("summary") or "").replace("\t", " ")
                handle.write(summary + "\t" + (finding.get("url") or "") + "\n")


if __name__ == "__main__":
    main()
