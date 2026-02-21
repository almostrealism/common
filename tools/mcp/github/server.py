#!/usr/bin/env python3
"""
AR GitHub MCP Server

Provides tools for Claude Code agents to read and respond to GitHub PR
review comments. This enables agents to address code review feedback
autonomously without going through Slack.

Authentication (first match wins):
    1. GITHUB_TOKEN environment variable (direct GitHub API access)
    2. ~/.config/ar/github-token file (direct GitHub API access)
    3. AR_WORKSTREAM_URL controller proxy (no local token needed)

When a local token is available, the tool calls the GitHub API directly.
When no local token is found but AR_WORKSTREAM_URL is set, requests are
proxied through the FlowTree controller's /api/github/proxy endpoint.
The controller authenticates with its own GITHUB_TOKEN, so the token
only needs to be configured in one place.

Other configuration:
    AR_GITHUB_REPO  - Optional. Format: owner/repo. Auto-detected from
                      git remote if unset.
"""

import json
import os
import re
import subprocess
import sys
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

TOKEN_FILE = os.path.expanduser("~/.config/ar/github-token")
GITHUB_REPO_OVERRIDE = os.environ.get("AR_GITHUB_REPO", "")
GITHUB_API = "https://api.github.com"
WORKSTREAM_URL = os.environ.get("AR_WORKSTREAM_URL", "")
MAX_PAGES = 5

_cached_token: Optional[str] = None

# Log startup configuration to stderr for diagnostics
print(f"ar-github: GITHUB_TOKEN={'<set>' if os.environ.get('GITHUB_TOKEN', '').strip() else '<not set>'}",
      file=sys.stderr)
print(f"ar-github: AR_WORKSTREAM_URL={'<not set>' if not WORKSTREAM_URL else WORKSTREAM_URL}",
      file=sys.stderr)
if WORKSTREAM_URL and not os.environ.get("GITHUB_TOKEN", "").strip():
    print("ar-github: Will use controller proxy for GitHub API calls", file=sys.stderr)

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


def _controller_base_url() -> Optional[str]:
    """Derive the controller base URL from AR_WORKSTREAM_URL.

    The workstream URL has the format::

        http://controller:port/api/workstreams/{id}[/jobs/{jobId}]

    Returns:
        The base URL (scheme + host + port), or None if not available.
    """
    if not WORKSTREAM_URL:
        return None
    parts = WORKSTREAM_URL.split("/api/workstreams/")
    if len(parts) < 2:
        return None
    return parts[0]


def _proxy_github_get(path_or_url: str, controller_base: str) -> list | dict:
    """Make a GET request to the GitHub API via the controller proxy.

    Handles pagination by following Link header URLs through
    subsequent proxy requests.

    Args:
        path_or_url: The GitHub API path (e.g., /repos/owner/repo/pulls)
                     or a full ``https://`` URL for pagination.
        controller_base: The controller base URL.

    Returns:
        Parsed JSON response. For paginated list endpoints, all
        pages are concatenated into a single list.

    Raises:
        HTTPError: If the GitHub API returns an error status.
    """
    all_results: list = []
    current = path_or_url

    for _ in range(MAX_PAGES):
        encoded = quote(current, safe="")
        proxy_url = f"{controller_base}/api/github/proxy?url={encoded}"
        req = Request(proxy_url)

        with urlopen(req, timeout=20) as resp:
            wrapper = json.loads(resp.read().decode("utf-8"))

        github_status = wrapper.get("status", 200)
        if github_status >= 400:
            error_body = json.dumps(wrapper.get("body", ""))
            raise HTTPError(
                current, github_status,
                f"GitHub API error: {error_body[:200]}",
                {}, None
            )

        body = wrapper["body"]

        if not isinstance(body, list):
            return body

        all_results.extend(body)

        link_header = wrapper.get("link", "")
        next_url = _parse_next_link(link_header)
        if not next_url:
            break
        current = next_url

    return all_results


def _proxy_github_post(path: str, payload: dict, controller_base: str) -> dict:
    """Make a POST request to the GitHub API via the controller proxy.

    Args:
        path: The GitHub API path.
        payload: The JSON body to send.
        controller_base: The controller base URL.

    Returns:
        Parsed JSON response.

    Raises:
        HTTPError: If the GitHub API returns an error status.
    """
    encoded = quote(path, safe="")
    proxy_url = f"{controller_base}/api/github/proxy?url={encoded}"
    data = json.dumps(payload).encode("utf-8")
    req = Request(proxy_url, data=data, headers={
        "Content-Type": "application/json",
    })

    with urlopen(req, timeout=20) as resp:
        wrapper = json.loads(resp.read().decode("utf-8"))

    github_status = wrapper.get("status", 200)
    if github_status >= 400:
        error_body = json.dumps(wrapper.get("body", ""))
        raise HTTPError(
            path, github_status,
            f"GitHub API error: {error_body[:200]}",
            {}, None
        )

    return wrapper["body"]


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

    Uses the local GITHUB_TOKEN for direct access when available.
    Falls back to proxying through the FlowTree controller when
    AR_WORKSTREAM_URL is set and no local token exists.

    Follows rel="next" Link headers up to MAX_PAGES pages.

    Args:
        path: The API path (e.g., /repos/owner/repo/pulls).

    Returns:
        Parsed JSON response. For paginated list endpoints, all
        pages are concatenated into a single list.

    Raises:
        HTTPError: If the API returns an error status.
        URLError: If the connection fails.
        ValueError: If no token and no controller proxy are available.
    """
    try:
        token = _resolve_token()
    except ValueError:
        base = _controller_base_url()
        if base is not None:
            return _proxy_github_get(path, base)
        raise

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

    Uses the local GITHUB_TOKEN for direct access when available.
    Falls back to proxying through the FlowTree controller when
    AR_WORKSTREAM_URL is set and no local token exists.

    Args:
        path: The API path.
        payload: The JSON body to send.

    Returns:
        Parsed JSON response.

    Raises:
        ValueError: If no token and no controller proxy are available.
    """
    try:
        token = _resolve_token()
    except ValueError:
        base = _controller_base_url()
        if base is not None:
            return _proxy_github_post(path, payload, base)
        raise

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
