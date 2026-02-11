#!/usr/bin/env python3
"""
AR GitHub MCP Server

Provides tools for Claude Code agents to read and respond to GitHub PR
review comments. This enables agents to address code review feedback
autonomously without going through Slack.

Token resolution (first match wins):
    1. GITHUB_TOKEN environment variable
    2. ~/.config/ar/github-token file (single line, token only)

Other configuration:
    AR_GITHUB_REPO  - Optional. Format: owner/repo. Auto-detected from
                      git remote if unset.
"""

import json
import os
import re
import subprocess
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

TOKEN_FILE = os.path.expanduser("~/.config/ar/github-token")
GITHUB_REPO_OVERRIDE = os.environ.get("AR_GITHUB_REPO", "")
GITHUB_API = "https://api.github.com"
MAX_PAGES = 5

_cached_token: Optional[str] = None

mcp = FastMCP("ar-github")


def _resolve_token() -> str:
    """Resolve the GitHub token from environment or file.

    Resolution order:
        1. GITHUB_TOKEN environment variable
        2. ~/.config/ar/github-token file

    Returns:
        The token string.

    Raises:
        ValueError: If no token is found.
    """
    global _cached_token
    if _cached_token:
        return _cached_token

    # 1. Environment variable
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        _cached_token = token
        return token

    # 2. Token file
    if os.path.isfile(TOKEN_FILE):
        try:
            token = open(TOKEN_FILE).read().strip()
            if token:
                _cached_token = token
                return token
        except OSError:
            pass

    raise ValueError(
        "GitHub token not found. Set GITHUB_TOKEN env var or write your "
        f"token to {TOKEN_FILE}"
    )


def _detect_repo() -> Optional[str]:
    """Detect the owner/repo from git remote origin URL.

    Parses both SSH (git@github.com:owner/repo.git) and HTTPS
    (https://github.com/owner/repo.git) formats. Falls back to
    the AR_GITHUB_REPO environment variable.

    Returns:
        The owner/repo string, or None if detection fails.
    """
    if GITHUB_REPO_OVERRIDE:
        return GITHUB_REPO_OVERRIDE

    try:
        result = subprocess.run(
            ["git", "remote", "get-url", "origin"],
            capture_output=True, text=True, timeout=5
        )
        url = result.stdout.strip()
        if not url:
            return None

        # SSH format: git@github.com:owner/repo.git
        ssh_match = re.match(r"git@github\.com:(.+?)(?:\.git)?$", url)
        if ssh_match:
            return ssh_match.group(1)

        # HTTPS format: https://github.com/owner/repo.git
        parsed = urlparse(url)
        if parsed.hostname and "github" in parsed.hostname:
            path = parsed.path.strip("/")
            if path.endswith(".git"):
                path = path[:-4]
            return path

    except (subprocess.SubprocessError, OSError):
        pass

    return None


def _detect_branch() -> Optional[str]:
    """Detect the current git branch name.

    Returns:
        The branch name, or None if detection fails.
    """
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5
        )
        branch = result.stdout.strip()
        return branch if branch and branch != "HEAD" else None
    except (subprocess.SubprocessError, OSError):
        return None


def _github_get(path: str) -> list | dict:
    """Make a GET request to the GitHub API with pagination support.

    Follows rel="next" Link headers up to MAX_PAGES pages.

    Args:
        path: The API path (e.g., /repos/owner/repo/pulls).

    Returns:
        Parsed JSON response. For paginated list endpoints, all
        pages are concatenated into a single list.

    Raises:
        HTTPError: If the API returns an error status.
        URLError: If the connection fails.
    """
    token = _resolve_token()

    url = GITHUB_API + path
    all_results = []

    for _ in range(MAX_PAGES):
        req = Request(url, headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })

        with urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8"))

            # If the response is not a list, return it directly (single object)
            if not isinstance(body, list):
                return body

            all_results.extend(body)

            # Check for next page via Link header
            link_header = resp.getheader("Link", "")
            next_url = _parse_next_link(link_header)
            if not next_url:
                break
            url = next_url

    return all_results


def _github_post(path: str, payload: dict) -> dict:
    """Make a POST request to the GitHub API.

    Args:
        path: The API path.
        payload: The JSON body to send.

    Returns:
        Parsed JSON response.
    """
    token = _resolve_token()

    url = GITHUB_API + path
    data = json.dumps(payload).encode("utf-8")
    req = Request(url, data=data, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
    })

    with urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _parse_next_link(link_header: str) -> Optional[str]:
    """Extract the next page URL from a GitHub Link header.

    Args:
        link_header: The raw Link header value.

    Returns:
        The URL for the next page, or None if there is no next page.
    """
    if not link_header:
        return None
    for part in link_header.split(","):
        match = re.match(r'\s*<([^>]+)>;\s*rel="next"', part.strip())
        if match:
            return match.group(1)
    return None


def _find_pr(repo: str, branch: str) -> Optional[dict]:
    """Find the open PR for a branch.

    Args:
        repo: The owner/repo string.
        branch: The branch name.

    Returns:
        The PR dict from the GitHub API, or None if no open PR exists.
    """
    owner = repo.split("/")[0]
    prs = _github_get(f"/repos/{repo}/pulls?head={owner}:{branch}&state=open&per_page=1")
    return prs[0] if prs else None


def _resolve_pr(pr_number: Optional[int], branch: Optional[str]) -> tuple[str, int]:
    """Resolve a PR number from the given arguments.

    If pr_number is provided, returns it directly. Otherwise, auto-detects
    the branch and finds the open PR for it.

    Args:
        pr_number: Explicit PR number, or None to auto-detect.
        branch: Explicit branch name, or None to auto-detect.

    Returns:
        Tuple of (repo, pr_number).

    Raises:
        ValueError: If auto-detection fails or no open PR is found.
    """
    repo = _detect_repo()
    if not repo:
        raise ValueError(
            "Could not detect GitHub repo. Set AR_GITHUB_REPO or ensure "
            "a git remote named 'origin' points to GitHub."
        )

    if pr_number:
        return repo, pr_number

    resolved_branch = branch or _detect_branch()
    if not resolved_branch:
        raise ValueError("Could not detect current branch. Provide branch or pr_number explicitly.")

    pr = _find_pr(repo, resolved_branch)
    if not pr:
        raise ValueError(f"No open PR found for branch '{resolved_branch}' in {repo}")

    return repo, pr["number"]


@mcp.tool()
def github_pr_find(branch: Optional[str] = None) -> dict:
    """
    Find the open pull request for a branch.

    Auto-detects the current branch from git if not specified.
    Returns PR metadata including number, title, URL, and body.

    Args:
        branch: Branch name to search for. If omitted, auto-detected
                from the current git checkout.

    Returns:
        Dictionary with PR details or error if no PR found.
    """
    try:
        repo = _detect_repo()
        if not repo:
            return {"ok": False, "error": "Could not detect GitHub repo"}

        resolved_branch = branch or _detect_branch()
        if not resolved_branch:
            return {"ok": False, "error": "Could not detect current branch"}

        pr = _find_pr(repo, resolved_branch)
        if not pr:
            return {"ok": False, "error": f"No open PR found for branch '{resolved_branch}' in {repo}"}

        return {
            "ok": True,
            "pr_number": pr["number"],
            "title": pr["title"],
            "url": pr["html_url"],
            "state": pr["state"],
            "author": pr["user"]["login"],
            "created_at": pr["created_at"],
            "head_sha": pr["head"]["sha"],
            "body": pr.get("body") or "",
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}


@mcp.tool()
def github_pr_review_comments(
    pr_number: Optional[int] = None,
    branch: Optional[str] = None,
) -> dict:
    """
    Get code review comments on a PR, grouped by file path.

    Each comment includes the file path, line number, diff context,
    and author. The file paths map directly to your local checkout
    so you can read the file immediately.

    Args:
        pr_number: The PR number. If omitted, auto-finds PR from branch.
        branch: Branch name for PR lookup. If omitted, auto-detected.

    Returns:
        Dictionary with comments grouped by file path, or error.
    """
    try:
        repo, number = _resolve_pr(pr_number, branch)
        comments = _github_get(f"/repos/{repo}/pulls/{number}/comments?per_page=100")

        files: dict[str, list] = {}
        for c in comments:
            path = c.get("path", "unknown")
            entry = {
                "id": c["id"],
                "line": c.get("line") or c.get("original_line"),
                "body": c["body"],
                "author": c["user"]["login"],
                "created_at": c["created_at"],
                "diff_hunk": c.get("diff_hunk", ""),
                "in_reply_to_id": c.get("in_reply_to_id"),
            }
            files.setdefault(path, []).append(entry)

        return {
            "ok": True,
            "pr_number": number,
            "total_comments": len(comments),
            "files": files,
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}


@mcp.tool()
def github_pr_conversation(
    pr_number: Optional[int] = None,
    branch: Optional[str] = None,
) -> dict:
    """
    Get the general PR conversation (issue-level comments not attached to files).

    These are the top-level comments on the PR, not inline code review comments.

    Args:
        pr_number: The PR number. If omitted, auto-finds PR from branch.
        branch: Branch name for PR lookup. If omitted, auto-detected.

    Returns:
        Dictionary with conversation comments, or error.
    """
    try:
        repo, number = _resolve_pr(pr_number, branch)
        comments = _github_get(f"/repos/{repo}/issues/{number}/comments?per_page=100")

        entries = []
        for c in comments:
            entries.append({
                "id": c["id"],
                "body": c["body"],
                "author": c["user"]["login"],
                "created_at": c["created_at"],
                "author_association": c.get("author_association", ""),
            })

        return {
            "ok": True,
            "pr_number": number,
            "comments": entries,
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}


@mcp.tool()
def github_pr_reply(
    comment_id: int,
    body: str,
    pr_number: Optional[int] = None,
    branch: Optional[str] = None,
) -> dict:
    """
    Reply to a review comment on a PR.

    Use this to acknowledge feedback, ask for clarification, or confirm
    that a fix has been made. The comment_id comes from the id field
    in github_pr_review_comments results.

    Args:
        comment_id: The review comment ID to reply to.
        body: The reply text (supports GitHub markdown).
        pr_number: The PR number. If omitted, auto-finds PR from branch.
        branch: Branch name for PR lookup. If omitted, auto-detected.

    Returns:
        Dictionary with the new comment ID on success, or error.
    """
    try:
        repo, number = _resolve_pr(pr_number, branch)
        result = _github_post(
            f"/repos/{repo}/pulls/{number}/comments/{comment_id}/replies",
            {"body": body},
        )
        return {"ok": True, "id": result["id"]}
    except HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        try:
            detail = json.loads(error_body).get("message", error_body[:200])
        except json.JSONDecodeError:
            detail = f"HTTP {e.code}: {error_body[:200]}"
        return {"ok": False, "error": detail}
    except Exception as e:
        return {"ok": False, "error": str(e)}


if __name__ == "__main__":
    mcp.run()
