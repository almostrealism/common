"""Pull-request lookup helpers for the AR Manager MCP server.

Split out of ``server.py`` for length. Resolving which pull request a tool
should act on — by explicit number, or by the open PR for a branch — is shared
by several tools (``github_pr_find``, ``github_request_copilot_review``,
``github_update_pr``, ``workstream_context``). Centralising the lookup here
keeps those tools consistent and keeps ``server`` under its line cap.

The functions are re-exported from ``server`` and reached through it
(``server._find_pr_by_branch`` and friends), so the test suite's patches still
apply exactly as before; only their address changed.
"""

from urllib.parse import quote

import server


# GitHub has no `state=merged`: a merged pull request is a closed one that also
# carries `merged_at`. Everything else maps straight onto the wire value.
# Only `merged` needs a full page: it asks GitHub for closed pull requests and
# filters locally, so the merged one may not be the most recent closure. The
# others take the first result, so a single-item page is all they need.
_PR_STATE_QUERIES = {
    "open": "state=open",
    "closed": "state=closed&sort=updated&direction=desc&per_page=1",
    "merged": "state=closed&sort=updated&direction=desc&per_page=100",
    "all": "state=all&sort=updated&direction=desc&per_page=1",
}

PR_STATES = tuple(_PR_STATE_QUERIES)


def _find_pr_by_branch(owner: str, repo: str, branch: str,
                       state: str = "open") -> dict:
    """Look up a pull request for ``branch`` on ``owner/repo``.

    Centralising this avoids drift between the tools that resolve a PR by
    branch (``github_pr_find``, ``github_request_copilot_review``,
    ``github_pr_check_status``, ``workstream_context``), which is why the
    open-only and any-state variants are one function rather than two
    near-identical ones.

    Args:
        owner: GitHub org (owner).
        repo: Repository name.
        branch: Branch to search for.
        state: One of :data:`PR_STATES`. ``closed`` follows GitHub and
            includes merged pull requests; ``merged`` is the subset of those
            that were actually merged rather than abandoned.

    Returns:
        ``ok=True`` with ``found=True`` and ``pr`` (the raw GitHub object) on
        success; ``ok=True`` with ``found=False`` when the branch has no
        matching pull request; ``ok=False`` with an error when the call fails
        or returns something unexpected.
    """
    query = _PR_STATE_QUERIES.get(state)
    if query is None:
        return {
            "ok": False,
            "error": f"Invalid state '{state}'. Must be one of: "
                     + ", ".join(PR_STATES),
        }

    head = f"{owner}:{branch}"
    pr_list = server._github_request(
        "GET",
        f"/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&{query}",
    )
    if isinstance(pr_list, dict) and pr_list.get("ok") is False:
        return pr_list
    if not isinstance(pr_list, list):
        return {
            "ok": False,
            "error": "Unexpected response listing pull requests",
        }

    if state == "merged":
        pr_list = [pr for pr in pr_list if pr.get("merged_at")]

    if not pr_list:
        return {"ok": True, "found": False, "branch": branch}
    return {"ok": True, "found": True, "pr": pr_list[0], "branch": branch}


def _find_open_pr_by_branch(owner: str, repo: str, branch: str) -> dict:
    """Look up the first open pull request for ``branch``."""
    return _find_pr_by_branch(owner, repo, branch, state="open")


def _find_recent_pr_by_branch(owner: str, repo: str, branch: str) -> dict:
    """Look up the most recently updated pull request for ``branch``,
    whatever its state."""
    return _find_pr_by_branch(owner, repo, branch, state="all")


def _resolve_effective_pr(pr_number: int, workstream_id: str, branch: str,
                          org: str, repo: str) -> tuple:
    """Resolve the pull request a tool should act on, by number or by branch.

    When ``pr_number`` is given it is used directly. Otherwise the open PR for
    the resolved branch is looked up — the shared addressing path behind
    ``github_update_pr`` and ``github_request_copilot_review``. Direct
    ``org``/``repo`` addressing carries no branch of its own, so a branch (or a
    ``pr_number``) is required in that case.

    Args:
        pr_number: Explicit PR number, or ``0``/falsy to look one up.
        workstream_id: Workstream to resolve the repo from.
        branch: Branch hint used both for repo resolution and PR lookup.
        org: GitHub org (owner) for direct addressing. Paired with ``repo``.
        repo: Repository name for direct addressing. Paired with ``org``.

    Returns:
        ``(owner, repo, effective_pr, None)`` on success, or
        ``(None, None, None, error_dict)`` when the PR cannot be resolved.
    """
    if org and repo and not pr_number and not branch:
        return None, None, None, {
            "ok": False,
            "error": ("branch is required when using direct org/repo addressing "
                      "without a pr_number"),
            "next_steps": [
                "Pass branch=<feature-branch> so the open PR can be looked up",
                "Or pass pr_number=<number> to address the PR directly",
            ],
        }

    owner, repo, effective_branch, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return None, None, None, err

    if pr_number:
        return owner, repo, pr_number, None

    if not effective_branch:
        return None, None, None, {
            "ok": False,
            "error": "no branch available to locate an open PR",
            "next_steps": [
                "Pass pr_number explicitly, or supply a branch / workstream_id "
                "with a defaultBranch so the open PR can be looked up",
            ],
        }

    lookup = _find_open_pr_by_branch(owner, repo, effective_branch)
    if not lookup.get("ok"):
        return None, None, None, lookup
    if not lookup.get("found"):
        return None, None, None, {
            "ok": False,
            "error": f"No open PR found for branch '{effective_branch}'",
        }
    return owner, repo, lookup["pr"].get("number"), None
