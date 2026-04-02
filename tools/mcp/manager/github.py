"""GitHub API helpers and PR tools for the AR Manager MCP server.

This module contains:
- Low-level GitHub request plumbing (proxy + direct fallback)
- GitHub GraphQL helper
- GitHub repo resolution from workstream config or local git
- All ``@mcp.tool()`` GitHub PR tools (find, review_comments, conversation,
  reply, list_open_prs, create_pr)

These were extracted from ``server.py`` to keep individual modules under
~1800 lines.
"""

import contextvars
import json
import logging
import re
import sys
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from server import (
    CONTROLLER_URL,
    GITHUB_TOKEN,
    _audit,
    _find_workstream,
    _get_token_workstream_id,
    _require_scope,
    mcp,
)

# Thread-local org context set by Tier 2 tools before calling _github_request
_current_github_org: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_current_github_org", default=None,
)


def _github_request(method: str, path: str, payload: dict = None,
                    timeout: int = 15) -> dict:
    """Make a GitHub API request via the controller's proxy endpoint.

    The controller resolves the GitHub token from its per-organization
    token map (``githubOrgs`` in ``workstreams.yaml``), falling back to
    its instance-level token and then the ``GITHUB_TOKEN`` env var.

    If the controller proxy is unreachable and a local ``GITHUB_TOKEN``
    is available, falls back to a direct API call.

    Args:
        method: HTTP method (GET, POST, PUT).
        path: GitHub API path (e.g., ``/repos/owner/repo/...``).
        payload: Optional dict to JSON-encode as the request body.
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response, or a status dict for empty responses (204).
    """
    org = _current_github_org.get()

    # Try controller proxy first
    result = _github_proxy_request(method, path, payload, org, timeout)
    if result is not None:
        return result

    # Fallback to direct API call if a local token is available
    if not GITHUB_TOKEN:
        return {
            "ok": False,
            "error": (
                "GitHub API unavailable: controller proxy unreachable "
                "and no local GITHUB_TOKEN configured."
            ),
            "next_steps": [
                "Ensure the FlowTree controller is running and reachable",
                "Or set AR_MANAGER_GITHUB_TOKEN / GITHUB_TOKEN as fallback",
            ],
        }

    return _github_direct_request(method, path, payload, timeout)


def _github_proxy_request(method: str, path: str, payload: dict = None,
                          org: str = None, timeout: int = 15) -> Optional[dict]:
    """Route a GitHub API request through the controller's proxy.

    Returns:
        Parsed response dict, or None if the proxy is unreachable.
    """
    params = f"url={quote(path, safe='')}"
    if org:
        params += f"&org={quote(org, safe='')}"
    proxy_url = CONTROLLER_URL.rstrip("/") + f"/api/github/proxy?{params}"

    data = json.dumps(payload).encode("utf-8") if payload else None
    req = Request(proxy_url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")

    print(f"ar-manager: {method} {path} (via controller proxy, org={org})",
          file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            if not body:
                return {"ok": True, "status": 204}
            wrapper = json.loads(body)

        # The proxy wraps responses as {"status": N, "link": "...", "body": <json>}
        gh_status = wrapper.get("status", 0)
        gh_body = wrapper.get("body")

        if gh_status == 204 or gh_body is None:
            return {"ok": True, "status": gh_status}
        if 200 <= gh_status < 300:
            # GitHub returns dicts for single resources and lists for
            # collection endpoints (e.g., /pulls, /comments).  Both are
            # valid success responses.
            if isinstance(gh_body, (dict, list)):
                return gh_body
        if isinstance(gh_body, dict):
            msg = gh_body.get("message", "")
            return {"ok": False, "error": f"GitHub returned HTTP {gh_status}: {msg}"}
        return {"ok": False, "error": f"GitHub returned HTTP {gh_status}"}
    except HTTPError as e:
        # The controller returned an error response (e.g., 400 Bad Request
        # for missing org token) — read and report the error body
        try:
            err_body = e.read().decode("utf-8")
            err_json = json.loads(err_body)
            msg = err_json.get("error", f"Controller returned HTTP {e.code}")
        except Exception:
            msg = f"Controller returned HTTP {e.code}"
        print(f"ar-manager: controller proxy error: {msg}", file=sys.stderr)
        return {"ok": False, "error": msg}
    except (URLError, OSError, TimeoutError) as e:
        print(f"ar-manager: controller proxy unreachable: {e}", file=sys.stderr)
        return None
    except Exception as e:
        logging.getLogger("ar-manager").error("GitHub proxy %s %s: %s", method, path, e)
        return None


def _github_direct_request(method: str, path: str, payload: dict = None,
                           timeout: int = 15) -> dict:
    """Direct GitHub API call using local GITHUB_TOKEN (fallback only).

    Args:
        method: HTTP method (GET, POST, PUT).
        path: GitHub API path.
        payload: Optional request body.
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response.
    """
    url = f"https://api.github.com{path}"
    data = json.dumps(payload).encode("utf-8") if payload else None
    req = Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {GITHUB_TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")

    print(f"ar-manager: {method} {url} (direct fallback)", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            if not body:
                return {"ok": True, "status": resp.status}
            return json.loads(body)
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        logging.getLogger("ar-manager").error(
            "GitHub %s %s: HTTP %d: %s", method, path, e.code, body[:500])
        try:
            err = json.loads(body)
            return {"ok": False, "error": f"GitHub returned HTTP {e.code}: {err.get('message', '')}"}
        except json.JSONDecodeError:
            return {"ok": False, "error": f"GitHub returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"GitHub API unreachable: {e.reason}"}
    except Exception as e:
        logging.getLogger("ar-manager").error("GitHub %s %s: %s", method, path, e)
        return {"ok": False, "error": "Internal error contacting GitHub API"}


def _github_graphql_request(query: str, variables: dict) -> dict:
    """Execute a GitHub GraphQL query.

    Tries the controller proxy first, falls back to a direct API call.
    Returns the parsed response dict (which will contain ``data`` on success
    or ``errors`` on failure).

    Args:
        query: The GraphQL query string.
        variables: Variables to pass with the query.

    Returns:
        Parsed JSON response dict, or an error dict with ``ok=False``.
    """
    return _github_request("POST", "/graphql", {"query": query, "variables": variables})


def _set_github_org(ws: dict) -> None:
    """Set the GitHub org context from a workstream for token routing.

    Uses the explicit ``githubOrg`` field if configured, otherwise
    extracts the org from the repository URL.
    """
    org = ws.get("githubOrg")
    if not org:
        repo_url = ws.get("repoUrl", "")
        parsed = _parse_github_remote(repo_url) if repo_url else None
        if parsed:
            org = parsed[0]
    if org:
        _current_github_org.set(org)


def _extract_owner_repo(repo_url: str) -> Optional[tuple]:
    """Extract (owner, repo) from a GitHub URL.

    This is a convenience wrapper around :func:`_parse_github_remote` that
    validates the URL begins with a recognized GitHub prefix.

    Returns:
        ``(owner, repo)`` tuple, or ``None`` if not parseable.
    """
    if not repo_url or "github.com" not in repo_url:
        return None
    return _parse_github_remote(repo_url)


# ---------------------------------------------------------------------------
# GitHub PR tools
# ---------------------------------------------------------------------------


def _parse_github_remote(url: str) -> Optional[tuple[str, str]]:
    """Parse owner and repo from a GitHub remote URL.

    Supports ``git@github.com:owner/repo.git`` and
    ``https://github.com/owner/repo.git`` formats.

    Returns (owner, repo) or None if the URL cannot be parsed.
    """
    m = re.search(r"[:/]([^/:]+)/([^/]+?)(?:\.git)?$", url)
    return (m.group(1), m.group(2)) if m else None


def _detect_local_github_repo() -> Optional[tuple[str, str, str]]:
    """Detect the GitHub owner, repo, and current branch from the local git
    working directory.

    Returns (owner, repo, branch) or None if detection fails.
    """
    try:
        import subprocess
        remote = subprocess.check_output(
            ["git", "remote", "get-url", "origin"],
            stderr=subprocess.DEVNULL, timeout=5,
        ).decode().strip()
        parsed = _parse_github_remote(remote)
        if not parsed:
            return None
        branch = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            stderr=subprocess.DEVNULL, timeout=5,
        ).decode().strip()
        return parsed[0], parsed[1], branch
    except Exception:
        return None


def _resolve_github_repo(workstream_id: str = "", branch: str = "") -> tuple[str, str, str, Optional[dict]]:
    """Resolve GitHub owner, repo, and branch from workstream context.

    Falls back to the local git working directory when no workstream is
    configured or when the caller supplies a *branch* hint that belongs
    to a different repository than the workstream's ``repoUrl``.

    Returns (owner, repo, branch, error_dict_or_None).
    """
    effective_ws = workstream_id or _get_token_workstream_id() or ""

    ws = _find_workstream(effective_ws) if effective_ws else None

    if ws is not None:
        repo_url = ws.get("repoUrl", "")
        parsed = _parse_github_remote(repo_url) if repo_url else None

        if parsed:
            owner, repo = parsed
            effective_branch = branch or ws.get("defaultBranch", "")

            # If a branch hint was given, verify it matches this repo by
            # checking the local git state.  When the local repo differs
            # from the workstream repo, prefer the local repo so that
            # callers operating on a sibling checkout get the right result.
            if branch:
                local = _detect_local_github_repo()
                if local and (local[0], local[1]) != (owner, repo):
                    _current_github_org.set(local[0])
                    return local[0], local[1], branch, None

            _set_github_org(ws)
            return owner, repo, effective_branch, None

    # Workstream lookup failed or has no repoUrl — try local git
    local = _detect_local_github_repo()
    if local:
        effective_branch = branch or local[2]
        _current_github_org.set(local[0])
        return local[0], local[1], effective_branch, None

    if not effective_ws:
        return "", "", branch, {"ok": False, "error": "workstream_id is required and no local git repo detected"}

    return "", "", branch, {"ok": False, "error": f"Workstream '{effective_ws}' not found and no local git repo detected"}


@mcp.tool()
def github_pr_find(
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Find an open pull request for a branch.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch to search for. Defaults to workstream's defaultBranch.

    Returns:
        PR details if found, or error/empty result.
    """
    _require_scope("read")
    owner, repo, effective_branch, err = _resolve_github_repo(workstream_id, branch)
    if err:
        return err

    if not effective_branch:
        return {"ok": False, "error": "branch is required (pass explicitly or configure defaultBranch on the workstream)"}

    _audit("github_pr_find", branch=effective_branch)

    head = f"{owner}:{effective_branch}"
    result = _github_request("GET", f"/repos/{owner}/{repo}/pulls?head={quote(head, safe=':/')}&state=open")
    if isinstance(result, list):
        if not result:
            return {"ok": True, "found": False, "branch": effective_branch}
        pr = result[0]
        return {
            "ok": True,
            "found": True,
            "number": pr.get("number"),
            "title": pr.get("title"),
            "url": pr.get("html_url"),
            "head": pr.get("head", {}).get("ref"),
            "base": pr.get("base", {}).get("ref"),
        }
    return result  # error dict from _github_request


@mcp.tool()
def github_pr_review_comments(
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Get code review comments on a pull request.

    Returns only comments from unresolved review threads, fetched via
    the GitHub GraphQL API with full pagination.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).

    Returns:
        List of review comments from unresolved threads.
    """
    _require_scope("read")
    owner, repo, _, err = _resolve_github_repo(workstream_id, branch)
    if err:
        return err

    _audit("github_pr_review_comments", pr_number=pr_number)

    # Fetch unresolved review threads via GraphQL (paginated).
    # The REST /pulls/{pr}/comments endpoint caps at 30 per page and does not
    # expose thread-level resolution state, so we use the GraphQL API instead.
    REVIEW_THREADS_QUERY = """
    query($owner: String!, $repo: String!, $pr: Int!, $cursor: String) {
      repository(owner: $owner, name: $repo) {
        pullRequest(number: $pr) {
          reviewThreads(first: 100, after: $cursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              isResolved
              comments(first: 50) {
                nodes {
                  databaseId
                  path
                  line
                  originalLine
                  body
                  author { login }
                  createdAt
                }
              }
            }
          }
        }
      }
    }
    """

    all_comments = []
    cursor = None
    while True:
        variables = {"owner": owner, "repo": repo, "pr": pr_number, "cursor": cursor}
        result = _github_graphql_request(REVIEW_THREADS_QUERY, variables)

        if isinstance(result, dict) and not result.get("ok", True) is False:
            if "errors" in result:
                return {"ok": False, "error": result["errors"][0].get("message", "GraphQL error")}
        if not isinstance(result, dict) or "data" not in result:
            return result if isinstance(result, dict) else {"ok": False, "error": "Unexpected response from GitHub GraphQL"}

        pr_data = (result.get("data") or {}).get("repository", {}).get("pullRequest") or {}
        threads_connection = pr_data.get("reviewThreads", {})
        threads = threads_connection.get("nodes", [])

        for thread in threads:
            if thread.get("isResolved"):
                continue
            for c in thread.get("comments", {}).get("nodes", []):
                all_comments.append({
                    "id": c.get("databaseId"),
                    "path": c.get("path"),
                    "line": c.get("line") or c.get("originalLine"),
                    "body": c.get("body"),
                    "user": (c.get("author") or {}).get("login"),
                    "created_at": c.get("createdAt"),
                    "in_reply_to_id": None,
                })

        page_info = threads_connection.get("pageInfo", {})
        if not page_info.get("hasNextPage"):
            break
        cursor = page_info.get("endCursor")

    all_comments.sort(key=lambda c: c.get("created_at") or "", reverse=True)
    top_comments = all_comments[:50]
    return {"ok": True, "comments": top_comments, "count": len(top_comments)}


@mcp.tool()
def github_pr_conversation(
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Get the conversation (issue comments) on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).

    Returns:
        List of conversation comments.
    """
    _require_scope("read")
    owner, repo, _, err = _resolve_github_repo(workstream_id, branch)
    if err:
        return err

    _audit("github_pr_conversation", pr_number=pr_number)

    result = _github_request("GET", f"/repos/{owner}/{repo}/issues/{pr_number}/comments")
    if isinstance(result, list):
        comments = []
        for c in result:
            comments.append({
                "id": c.get("id"),
                "body": c.get("body"),
                "user": c.get("user", {}).get("login"),
                "created_at": c.get("created_at"),
            })
        return {"ok": True, "comments": comments, "count": len(comments)}
    return result


@mcp.tool()
def github_pr_reply(
    comment_id: int,
    body: str,
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
) -> dict:
    """Reply to a pull request review comment.

    Args:
        comment_id: The ID of the review comment to reply to.
        body: The reply text.
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint.

    Returns:
        The created reply.
    """
    _require_scope("write")
    owner, repo, _, err = _resolve_github_repo(workstream_id, branch)
    if err:
        return err

    _audit("github_pr_reply", comment_id=comment_id, pr_number=pr_number)

    result = _github_request(
        "POST",
        f"/repos/{owner}/{repo}/pulls/{pr_number}/comments/{comment_id}/replies",
        {"body": body},
    )
    if result.get("id"):
        return {"ok": True, "id": result["id"]}
    return result


@mcp.tool()
def github_list_open_prs(
    workstream_id: str = "",
    base: str = "",
) -> dict:
    """List open pull requests.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Filter by base branch (e.g., "master"). If empty, lists all open PRs.

    Returns:
        List of open PRs.
    """
    _require_scope("read")
    owner, repo, _, err = _resolve_github_repo(workstream_id)
    if err:
        return err

    _audit("github_list_open_prs", base=base)

    path = f"/repos/{owner}/{repo}/pulls?state=open"
    if base:
        path += f"&base={quote(base, safe='/')}"

    result = _github_request("GET", path)
    if isinstance(result, list):
        prs = []
        for pr in result:
            prs.append({
                "number": pr.get("number"),
                "title": pr.get("title"),
                "url": pr.get("html_url"),
                "head": pr.get("head", {}).get("ref"),
                "base": pr.get("base", {}).get("ref"),
                "user": pr.get("user", {}).get("login"),
                "created_at": pr.get("created_at"),
            })
        return {"ok": True, "prs": prs, "count": len(prs)}
    return result


@mcp.tool()
def github_create_pr(
    title: str,
    body: str,
    workstream_id: str = "",
    base: str = "",
    head: str = "",
) -> dict:
    """Create a pull request.

    Args:
        title: PR title.
        body: PR description.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Base branch (default: workstream's baseBranch or "master").
        head: Head branch (default: workstream's defaultBranch).

    Returns:
        The created PR details.
    """
    _require_scope("write")
    owner, repo, default_branch, err = _resolve_github_repo(workstream_id)
    if err:
        return err

    effective_ws = workstream_id or _get_token_workstream_id() or ""
    ws = _find_workstream(effective_ws) if effective_ws else None
    effective_base = base or (ws.get("baseBranch", "master") if ws else "master")
    effective_head = head or default_branch

    if not effective_head:
        return {"ok": False, "error": "head branch is required"}

    _audit("github_create_pr", title=title, base=effective_base, head=effective_head)

    result = _github_request("POST", f"/repos/{owner}/{repo}/pulls", {
        "title": title,
        "body": body,
        "base": effective_base,
        "head": effective_head,
    })

    if result.get("number"):
        return {
            "ok": True,
            "number": result["number"],
            "url": result.get("html_url"),
            "title": result.get("title"),
        }
    return result
