#!/usr/bin/env python3
from __future__ import annotations
"""
AR GitHub MCP Server

Provides tools for Claude Code agents to interact with GitHub: reading
and responding to PR review comments, listing open PRs, and creating
new PRs. This enables agents to manage code review feedback and project
branches autonomously.

Authentication (first match wins):
    1. ~/.config/ar/github-token file (direct GitHub API access)
    2. AR_WORKSTREAM_URL controller proxy (no local token needed)

When a local token file is available, the tool calls the GitHub API
directly. When no local token is found but AR_WORKSTREAM_URL is set,
requests are proxied through the FlowTree controller's /api/github/proxy
endpoint. The controller authenticates with per-org tokens from
workstreams.yaml.

Other configuration:
    AR_GITHUB_REPO  - Optional. Format: owner/repo. Auto-detected from
                      git remote if unset.

CLI mode:
    When invoked with command-line arguments, runs a single tool and
    prints JSON output to stdout. This allows CI scripts and workflows
    to use the same GitHub API logic without requiring the `gh` CLI.

    Usage:
        python server.py list-open-prs [--base master]
        python server.py create-pr --title "..." --body "..." [--base master] [--head branch]
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

TOKEN_FILE = os.path.expanduser("~/.config/ar/github-token")
GITHUB_REPO_OVERRIDE = os.environ.get("AR_GITHUB_REPO", "")
GITHUB_ORG = os.environ.get("AR_GITHUB_ORG", "")
GITHUB_API = "https://api.github.com"
WORKSTREAM_URL = os.environ.get("AR_WORKSTREAM_URL", "")
MAX_PAGES = 5

_cached_token: Optional[str] = None

# Log startup configuration to stderr for diagnostics
print(f"ar-github: token_file={'<exists>' if os.path.isfile(TOKEN_FILE) else '<not found>'}",
      file=sys.stderr)
print(f"ar-github: AR_WORKSTREAM_URL={'<not set>' if not WORKSTREAM_URL else WORKSTREAM_URL}",
      file=sys.stderr)
if WORKSTREAM_URL and not os.path.isfile(TOKEN_FILE):
    print("ar-github: Will use controller proxy for GitHub API calls", file=sys.stderr)

_mcp_instance = None


def _get_mcp():
    """Lazily create the FastMCP server instance.

    Defers the ``mcp`` import so that CLI mode (which only needs stdlib)
    can run without the ``mcp`` package installed.
    """
    global _mcp_instance
    if _mcp_instance is None:
        from mcp.server.fastmcp import FastMCP
        _mcp_instance = FastMCP("ar-github")
    return _mcp_instance


def _resolve_token() -> str:
    """Resolve the GitHub token from the token file.

    Reads from ~/.config/ar/github-token. Environment variable
    authentication is not supported — the controller uses per-org
    tokens from workstreams.yaml via the proxy endpoint.

    Returns:
        The token string.

    Raises:
        ValueError: If no token file is found.
    """
    global _cached_token
    if _cached_token:
        return _cached_token

    if os.path.isfile(TOKEN_FILE):
        try:
            token = open(TOKEN_FILE).read().strip()
            if token:
                _cached_token = token
                return token
        except OSError:
            pass

    raise ValueError(
        f"GitHub token not found. Write your token to {TOKEN_FILE} "
        "or use the controller proxy via AR_WORKSTREAM_URL"
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
        if GITHUB_ORG:
            proxy_url += f"&org={quote(GITHUB_ORG, safe='')}"
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
    if GITHUB_ORG:
        proxy_url += f"&org={quote(GITHUB_ORG, safe='')}"
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

    Uses a local token file for direct access when available.
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

    Uses a local token file for direct access when available.
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


def github_list_open_prs(base: Optional[str] = None) -> dict:
    """
    List open pull requests targeting a base branch.

    Returns summary metadata for each open PR including number, title,
    branch name, author, and creation date. Useful for counting active
    work branches and understanding current project activity.

    Args:
        base: Base branch to filter by (e.g., "master"). If omitted,
              returns all open PRs regardless of target branch.

    Returns:
        Dictionary with list of open PRs and count, or error.
    """
    try:
        repo = _detect_repo()
        if not repo:
            return {"ok": False, "error": "Could not detect GitHub repo"}

        query = f"/repos/{repo}/pulls?state=open&per_page=100"
        if base:
            query += f"&base={base}"

        prs = _github_get(query)

        entries = []
        for pr in prs:
            entries.append({
                "number": pr["number"],
                "title": pr["title"],
                "branch": pr["head"]["ref"],
                "author": pr["user"]["login"],
                "created_at": pr["created_at"],
                "url": pr["html_url"],
                "draft": pr.get("draft", False),
            })

        return {
            "ok": True,
            "count": len(entries),
            "pull_requests": entries,
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}


def github_create_pr(
    title: str,
    body: str,
    head: Optional[str] = None,
    base: str = "master",
) -> dict:
    """
    Create a new pull request.

    Opens a PR from the head branch to the base branch. Auto-detects
    the head branch from the current git checkout if not specified.

    Args:
        title: PR title.
        body: PR body/description (supports GitHub markdown).
        head: Source branch name. If omitted, auto-detected from git.
        base: Target branch name (default: "master").

    Returns:
        Dictionary with PR number, URL, and title on success, or error.
    """
    try:
        repo = _detect_repo()
        if not repo:
            return {"ok": False, "error": "Could not detect GitHub repo"}

        resolved_head = head or _detect_branch()
        if not resolved_head:
            return {"ok": False, "error": "Could not detect current branch. Provide head explicitly."}

        result = _github_post(f"/repos/{repo}/pulls", {
            "title": title,
            "body": body,
            "head": resolved_head,
            "base": base,
        })

        return {
            "ok": True,
            "pr_number": result["number"],
            "url": result["html_url"],
            "title": result["title"],
        }
    except HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        try:
            detail = json.loads(error_body).get("message", error_body[:200])
        except json.JSONDecodeError:
            detail = f"HTTP {e.code}: {error_body[:200]}"
        return {"ok": False, "error": detail}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ─── CLI mode ────────────────────────────────────────────────────────
# When invoked with command-line arguments, runs a single tool and
# prints JSON to stdout. This allows CI workflows to use the same
# GitHub API logic without requiring `gh` CLI.

def _cli_list_open_prs(args: list[str]) -> dict:
    """CLI handler for list-open-prs."""
    base = None
    i = 0
    while i < len(args):
        if args[i] == "--base" and i + 1 < len(args):
            base = args[i + 1]
            i += 2
        else:
            i += 1
    return github_list_open_prs(base=base)


def _cli_create_pr(args: list[str]) -> dict:
    """CLI handler for create-pr."""
    title = ""
    body = ""
    head = None
    base = "master"
    i = 0
    while i < len(args):
        if args[i] == "--title" and i + 1 < len(args):
            title = args[i + 1]
            i += 2
        elif args[i] == "--body" and i + 1 < len(args):
            body = args[i + 1]
            i += 2
        elif args[i] == "--head" and i + 1 < len(args):
            head = args[i + 1]
            i += 2
        elif args[i] == "--base" and i + 1 < len(args):
            base = args[i + 1]
            i += 2
        else:
            i += 1
    if not title:
        return {"ok": False, "error": "--title is required"}
    return github_create_pr(title=title, body=body, head=head, base=base)


CLI_COMMANDS = {
    "list-open-prs": _cli_list_open_prs,
    "create-pr": _cli_create_pr,
}


def _register_mcp_tools():
    """Register all tool functions with the MCP server.

    Called only when running in MCP server mode (not CLI mode) so
    that the mcp package import is deferred.
    """
    server = _get_mcp()
    for fn in [
        github_pr_find,
        github_pr_review_comments,
        github_pr_conversation,
        github_pr_reply,
        github_list_open_prs,
        github_create_pr,
    ]:
        server.tool()(fn)


if __name__ == "__main__":
    # CLI mode: python server.py <command> [args...]
    # Runs without the mcp package — only needs stdlib.
    if len(sys.argv) > 1 and sys.argv[1] in CLI_COMMANDS:
        result = CLI_COMMANDS[sys.argv[1]](sys.argv[2:])
        print(json.dumps(result))
        sys.exit(0 if result.get("ok") else 1)

    # MCP server mode
    _register_mcp_tools()
    mcp = _get_mcp()

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
