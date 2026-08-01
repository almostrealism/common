"""Self-contained GitHub API helpers for the AR Manager MCP server.

This module provides low-level GitHub request plumbing (proxy-only), a
GraphQL helper, and repo resolution utilities. It is intentionally free of
any imports from ``server.py`` to avoid circular dependencies — all
configuration (URLs, callbacks) is passed in via module-level
``configure()`` or as function arguments.

ar-manager does not hold a GitHub token. Every GitHub API call routes
through the controller's ``/api/github/proxy`` endpoint, which resolves
the per-org PAT from ``workstreams.yaml``.

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

# Callbacks into server.py — set by configure() to avoid import-time coupling
_find_workstream_fn = None
_get_token_workstream_id_fn = None


def configure(controller_url: str,
              find_workstream, get_token_workstream_id) -> None:
    """Initialise module-level configuration.

    Called once by ``server.py`` after its own globals are ready.
    This avoids circular imports — no ``from server import ...`` needed.
    """
    global _controller_url
    global _find_workstream_fn, _get_token_workstream_id_fn
    _controller_url = controller_url
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
    token map (``githubOrgs`` in ``workstreams.yaml``) using the ``org``
    query parameter derived from the caller's context
    (:data:`_current_github_org`).

    There is no direct-to-GitHub fallback. If the controller proxy is
    unreachable this returns an error dict — ar-manager deliberately does
    not hold a GitHub credential of its own, so every request must flow
    through the controller.

    Args:
        method: HTTP method (GET, POST, PUT).
        path: GitHub API path (e.g., ``/repos/owner/repo/...``).
        payload: Optional dict to JSON-encode as the request body.
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response, or a status dict for empty responses (204).
    """
    org = _current_github_org.get()

    result = _github_proxy_request(method, path, payload, org, timeout)
    if result is not None:
        return result

    return {
        "ok": False,
        "error": "GitHub API unavailable: controller proxy unreachable.",
        "next_steps": [
            "Ensure the FlowTree controller is running and reachable",
            "Check controller_health to verify connectivity",
        ],
    }


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


def _github_graphql_request(query: str, variables: dict) -> dict:
    """Execute a GitHub GraphQL query via the controller proxy.

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


def _resolve_github_repo(workstream_id: str = "", branch: str = "",
                         owner: str = "", repo: str = "") -> tuple[str, str, str, Optional[dict]]:
    """Resolve GitHub owner, repo, and branch with the following precedence:

    1. Explicit ``owner`` and ``repo`` (both must be set). The caller
       provided them out-of-band; ar-manager must enforce the workspace
       scope gate via :func:`_require_org_in_scope` *before* calling this
       resolver. The org is recorded in :data:`_current_github_org` so
       subsequent :func:`_github_request` calls route the proxy correctly.
    2. Workstream context — either the explicit ``workstream_id``
       argument or the one embedded in a temporary job token. When set,
       the workstream's ``repoUrl`` supplies (owner, repo) and the
       default branch feeds ``branch``. A caller-supplied ``branch`` that
       points to a *different* local checkout takes over via local-git
       detection (preserves the dev-mode workflow).
    3. Local-git detection. Intended for interactive sessions where the
       MCP server runs directly against the developer's checkout. Not
       relevant inside the ar-manager HTTP deployment (which has no
       meaningful working tree), but useful in a local REPL.
    4. Error.

    Returns (owner, repo, branch, error_dict_or_None).
    """
    # 1. Explicit owner/repo wins — scope has already been checked upstream.
    if owner and repo:
        _current_github_org.set(owner)
        return owner, repo, branch, None
    if owner or repo:
        # Partial input is ambiguous — neither the workstream path nor the
        # local-git path can compensate for a missing half.
        return "", "", branch, {
            "ok": False,
            "error": "org and repo must be supplied together",
        }

    effective_ws = workstream_id or (_get_token_workstream_id_fn() if _get_token_workstream_id_fn else "") or ""

    ws = _find_workstream_fn(effective_ws) if (_find_workstream_fn and effective_ws) else None

    if ws is not None:
        repo_url = ws.get("repoUrl", "")
        parsed = _parse_github_remote(repo_url) if repo_url else None

        if parsed:
            ws_owner, ws_repo = parsed
            effective_branch = branch or ws.get("defaultBranch", "")

            if branch:
                local = _detect_local_github_repo()
                if local and (local[0], local[1]) != (ws_owner, ws_repo):
                    _current_github_org.set(local[0])
                    return local[0], local[1], branch, None

            _set_github_org(ws)
            return ws_owner, ws_repo, effective_branch, None

    # Workstream lookup failed or has no repoUrl — try local git
    local = _detect_local_github_repo()
    if local:
        effective_branch = branch or local[2]
        _current_github_org.set(local[0])
        return local[0], local[1], effective_branch, None

    if not effective_ws:
        return "", "", branch, {"ok": False,
                                "error": ("workstream_id is required, or pass an explicit"
                                          " owner and repo; no local git repo detected")}

    return "", "", branch, {"ok": False,
                            "error": f"Workstream '{effective_ws}' not found and no local git repo detected"}


# ---------------------------------------------------------------------------
# GitHub Actions — workflow run search and status
# ---------------------------------------------------------------------------


def _shape_workflow_run(run: dict) -> dict:
    """Reduce a raw GitHub workflow-run object to the fields callers need.

    Args:
        run: A raw workflow-run object from the GitHub Actions API.

    Returns:
        A flat dict with the run's identity, trigger, and outcome fields.
    """
    return {
        "run_id": run.get("id"),
        "name": run.get("name", ""),
        "display_title": run.get("display_title", ""),
        "workflow_id": run.get("workflow_id"),
        "event": run.get("event", ""),
        "status": run.get("status", ""),
        "conclusion": run.get("conclusion"),
        "head_branch": run.get("head_branch", ""),
        "head_sha": run.get("head_sha", ""),
        "run_number": run.get("run_number"),
        "run_attempt": run.get("run_attempt"),
        "created_at": run.get("created_at", ""),
        "updated_at": run.get("updated_at", ""),
        "html_url": run.get("html_url", ""),
    }


def _shape_job(job: dict) -> dict:
    """Reduce a raw GitHub Actions job object to a diagnostic summary.

    Only the steps that failed are retained, since those are what a caller
    diagnosing a CI failure needs to see.

    Args:
        job: A raw job object from the GitHub Actions API.

    Returns:
        A flat dict describing the job and its failed steps.
    """
    failed_steps = [
        {
            "name": step.get("name", ""),
            "number": step.get("number"),
            "conclusion": step.get("conclusion"),
        }
        for step in (job.get("steps") or [])
        if step.get("conclusion") == "failure"
    ]
    return {
        "job_id": job.get("id"),
        "name": job.get("name", ""),
        "status": job.get("status", ""),
        "conclusion": job.get("conclusion"),
        "started_at": job.get("started_at"),
        "completed_at": job.get("completed_at"),
        "html_url": job.get("html_url", ""),
        "failed_steps": failed_steps,
    }


def list_workflow_runs(owner: str, repo: str, workflow: str = "",
                       branch: str = "", event: str = "", status: str = "",
                       actor: str = "", limit: int = 20) -> dict:
    """List GitHub Actions workflow runs for a repository.

    When ``workflow`` is given the workflow-scoped endpoint is used
    (runs of that single workflow); otherwise runs across all workflows
    are returned. All filters map directly onto the GitHub Actions API's
    query parameters.

    Args:
        owner: Repository owner (org or user).
        repo: Repository name.
        workflow: Workflow file name (e.g. ``analysis.yaml``) or numeric
            workflow id. Empty means all workflows.
        branch: Filter by head branch. Empty means any branch.
        event: Filter by triggering event (``push``, ``pull_request``,
            ``workflow_dispatch``, ``schedule``, ...). Empty means any.
        status: Filter by status or conclusion (``queued``,
            ``in_progress``, ``completed``, ``success``, ``failure``,
            ``cancelled``, ``timed_out``, ...). Empty means any.
        actor: Filter by the login that triggered the run. Empty means any.
        limit: Maximum number of runs to return (1-100).

    Returns:
        dict with ok=True, total_count (GitHub's count of all matches),
        returned (number in this response), and a workflow_runs list; or
        an ok=False error dict.
    """
    per_page = max(1, min(int(limit or 20), 100))
    params = [f"per_page={per_page}"]
    if branch:
        params.append(f"branch={quote(branch, safe='')}")
    if event:
        params.append(f"event={quote(event, safe='')}")
    if status:
        params.append(f"status={quote(status, safe='')}")
    if actor:
        params.append(f"actor={quote(actor, safe='')}")
    query = "&".join(params)

    if workflow:
        path = (f"/repos/{owner}/{repo}/actions/workflows/"
                f"{quote(str(workflow), safe='')}/runs?{query}")
    else:
        path = f"/repos/{owner}/{repo}/actions/runs?{query}"

    result = _github_request("GET", path)
    if isinstance(result, dict) and result.get("ok") is False:
        return result
    if not isinstance(result, dict):
        return {"ok": False, "error": "Unexpected response listing workflow runs"}

    runs = [_shape_workflow_run(run) for run in result.get("workflow_runs", [])]
    return {
        "ok": True,
        "total_count": result.get("total_count", len(runs)),
        "returned": len(runs),
        "workflow_runs": runs,
    }


def get_workflow_run_status(owner: str, repo: str, run_id: int) -> dict:
    """Fetch a single workflow run together with its jobs and failed steps.

    Args:
        owner: Repository owner (org or user).
        repo: Repository name.
        run_id: The numeric workflow run id.

    Returns:
        dict with ok=True, a shaped ``run``, a ``jobs`` list (each with its
        failed_steps), and a ``summary`` (total/failed job counts and the
        run's status/conclusion); or an ok=False error dict.
    """
    run_result = _github_request(
        "GET", f"/repos/{owner}/{repo}/actions/runs/{run_id}")
    if isinstance(run_result, dict) and run_result.get("ok") is False:
        return run_result
    if not isinstance(run_result, dict):
        return {"ok": False, "error": "Unexpected response fetching workflow run"}

    jobs_result = _github_request(
        "GET", f"/repos/{owner}/{repo}/actions/runs/{run_id}/jobs?per_page=100")

    jobs = []
    failed_jobs = 0
    if isinstance(jobs_result, dict) and jobs_result.get("ok") is not False:
        for job in jobs_result.get("jobs", []):
            shaped = _shape_job(job)
            if shaped["conclusion"] == "failure":
                failed_jobs += 1
            jobs.append(shaped)

    run = _shape_workflow_run(run_result)
    run["run_started_at"] = run_result.get("run_started_at", "")

    return {
        "ok": True,
        "run": run,
        "jobs": jobs,
        "summary": {
            "total_jobs": len(jobs),
            "failed_jobs": failed_jobs,
            "status": run_result.get("status", ""),
            "conclusion": run_result.get("conclusion"),
        },
    }
