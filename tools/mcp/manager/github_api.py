"""Self-contained GitHub API helpers for the AR Manager MCP server.

This module provides low-level GitHub request plumbing (proxy + direct
fallback), a GraphQL helper, and repo resolution utilities. It is
intentionally free of any imports from ``server.py`` to avoid circular
dependencies — all configuration (URLs, tokens) is passed in via
module-level ``configure()`` or as function arguments.

Extracted from ``server.py`` to keep individual modules manageable.
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

# ---------------------------------------------------------------------------
# Module-level configuration — set by server.py at import time via configure()
# ---------------------------------------------------------------------------

_controller_url: str = ""
_github_token: str = ""

# Callbacks into server.py — set by configure() to avoid import-time coupling
_find_workstream_fn = None
_get_token_workstream_id_fn = None


def configure(controller_url: str, github_token: str,
              find_workstream, get_token_workstream_id) -> None:
    """Initialise module-level configuration.

    Called once by ``server.py`` after its own globals are ready.
    This avoids circular imports — no ``from server import ...`` needed.
    """
    global _controller_url, _github_token
    global _find_workstream_fn, _get_token_workstream_id_fn
    _controller_url = controller_url
    _github_token = github_token
    _find_workstream_fn = find_workstream
    _get_token_workstream_id_fn = get_token_workstream_id


# Thread-local org context set by Tier 2 tools before calling _github_request
_current_github_org: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_current_github_org", default=None,
)


# ---------------------------------------------------------------------------
# Request helpers
# ---------------------------------------------------------------------------


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
    if not _github_token:
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
    proxy_url = _controller_url.rstrip("/") + f"/api/github/proxy?{params}"

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
            if isinstance(gh_body, (dict, list)):
                return gh_body
        if isinstance(gh_body, dict):
            msg = gh_body.get("message", "")
            return {"ok": False, "error": f"GitHub returned HTTP {gh_status}: {msg}"}
        return {"ok": False, "error": f"GitHub returned HTTP {gh_status}"}
    except HTTPError as e:
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
    req.add_header("Authorization", f"Bearer {_github_token}")
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

    Args:
        query: The GraphQL query string.
        variables: Variables to pass with the query.

    Returns:
        Parsed JSON response dict, or an error dict with ``ok=False``.
    """
    return _github_request("POST", "/graphql", {"query": query, "variables": variables})


# ---------------------------------------------------------------------------
# GitHub org / repo resolution
# ---------------------------------------------------------------------------


def _set_github_org(ws: dict) -> None:
    """Set the GitHub org context from a workstream for token routing.

    Uses the explicit ``githubOrg`` field if configured, otherwise
    extracts the org from the repository URL.
    """
    org = ws.get("githubOrg")
    if not org:
        owner_repo = _extract_owner_repo(ws.get("repoUrl", ""))
        if owner_repo:
            org = owner_repo[0]
    _current_github_org.set(org)


def _extract_owner_repo(repo_url: str) -> Optional[tuple]:
    """Extract (owner, repo) from a GitHub URL.

    Handles HTTPS URLs (``https://github.com/owner/repo.git``)
    and SSH URLs (``git@github.com:owner/repo.git``).

    Returns:
        Tuple of (owner, repo) or None if parsing fails.
    """
    if not repo_url:
        return None
    # HTTPS
    if "github.com/" in repo_url:
        parts = repo_url.split("github.com/")[-1]
        parts = parts.rstrip("/").removesuffix(".git")
        segments = parts.split("/")
        if len(segments) >= 2:
            return (segments[0], segments[1])
    # SSH
    if "github.com:" in repo_url:
        parts = repo_url.split("github.com:")[-1]
        parts = parts.rstrip("/").removesuffix(".git")
        segments = parts.split("/")
        if len(segments) >= 2:
            return (segments[0], segments[1])
    return None


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
    effective_ws = workstream_id or (_get_token_workstream_id_fn() if _get_token_workstream_id_fn else "") or ""

    ws = _find_workstream_fn(effective_ws) if (_find_workstream_fn and effective_ws) else None

    if ws is not None:
        repo_url = ws.get("repoUrl", "")
        parsed = _parse_github_remote(repo_url) if repo_url else None

        if parsed:
            owner, repo = parsed
            effective_branch = branch or ws.get("defaultBranch", "")

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
