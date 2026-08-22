"""
GitHub tools for the AR Manager MCP server.

Split out of ``server.py`` for length. The tools are unchanged; only their
address is. See ``tracker_tools`` for the two conventions this module also
follows — the ``_tools`` suffix that makes it visible to tool discovery, and
reaching helpers through ``server`` so the suite's patches still apply.

The private helpers these tools use stay in ``server.py`` deliberately. Several
are patched by name in the tests, and moving them would relocate a seam without
moving the code that depends on it.
"""

import base64
import binascii
from urllib.parse import quote

import github_api
import server
from server import mcp


@mcp.tool()
def github_pr_find(
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
    state: str = "open",
) -> dict:
    """Find a pull request for a branch.

    Defaults to open pull requests. Asking whether a branch's work has
    already landed needs ``state="merged"`` or ``state="all"`` — a merged
    pull request is invisible to the default, which is why a finished
    workstream can look as though it never had one.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch to search for. Defaults to workstream's defaultBranch.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. When set, bypasses workstream resolution — useful
            when no workstream exists for the repo. Scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.
        state: Which pull requests to consider — ``open`` (default),
            ``closed``, ``merged``, or ``all``. ``closed`` follows GitHub and
            includes merged ones; ``merged`` narrows to those actually merged
            rather than abandoned. ``all`` returns the most recently updated
            regardless of state.

    Returns:
        PR details if found, or error. The returned ``state`` is GitHub's own
        value, so a merged pull request reports ``closed``; ``merged`` and
        ``merged_at`` distinguish it.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, effective_branch, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    server._audit("github_pr_find", workstream_id=workstream_id,
           branch=effective_branch, state=state)

    lookup = server._find_pr_by_branch(owner, repo, effective_branch, state=state)
    if not lookup.get("ok"):
        return lookup
    if not lookup.get("found"):
        return {"ok": True, "found": False, "branch": effective_branch,
                "searched_state": state}
    pr = lookup["pr"]
    return {
        "ok": True,
        "found": True,
        "number": pr.get("number"),
        "title": pr.get("title"),
        "url": pr.get("html_url"),
        "state": pr.get("state"),
        # Merged is not a GitHub state, so surface it explicitly rather than
        # making every caller re-derive it from merged_at.
        "merged": bool(pr.get("merged_at")),
        "merged_at": pr.get("merged_at"),
        "branch": effective_branch,
        "searched_state": state,
    }

@mcp.tool()
def github_pr_review_comments(
    pr_number: int,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Get code review comments on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        List of review comments.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    server._audit("github_pr_review_comments", pr_number=pr_number)

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
        result = server._github_graphql_request(REVIEW_THREADS_QUERY, variables)

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
    org: str = "",
    repo: str = "",
) -> dict:
    """Get the conversation (issue comments) on a pull request.

    Args:
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint (used for repo resolution if needed).
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        List of conversation comments.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    server._audit("github_pr_conversation", pr_number=pr_number)

    result = server._github_request("GET", f"/repos/{owner}/{repo}/issues/{pr_number}/comments")
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
    org: str = "",
    repo: str = "",
) -> dict:
    """Reply to a pull request review comment.

    Args:
        comment_id: The ID of the review comment to reply to.
        body: The reply text.
        pr_number: The PR number.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        The created reply.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    server._audit("github_pr_reply", comment_id=comment_id, pr_number=pr_number)

    result = server._github_request(
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
    org: str = "",
    repo: str = "",
) -> dict:
    """List open pull requests.

    Args:
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Filter by base branch (e.g., "master"). If empty, lists all open PRs.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        List of open PRs.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, owner=org, repo=repo)
    if err:
        return err

    server._audit("github_list_open_prs", base=base)

    path = f"/repos/{owner}/{repo}/pulls?state=open"
    if base:
        path += f"&base={quote(base, safe='/')}"

    result = server._github_request("GET", path)
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
    request_copilot_review: bool = False,
    org: str = "",
    repo: str = "",
) -> dict:
    """Create a pull request.

    Args:
        title: PR title.
        body: PR description.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        base: Base branch (default: the workstream's baseBranch, else the
            repository's default branch as GitHub reports it).
        head: Head branch (default: workstream's defaultBranch).
        request_copilot_review: If true, automatically request a Copilot review
            after creating the PR.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        The created PR details, including copilot_review_requested if applicable.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, default_branch, err = server._resolve_github_repo(
        workstream_id=workstream_id, owner=org, repo=repo)
    if err:
        return err

    effective_ws = workstream_id or server._get_token_workstream_id() or ""
    ws = server._find_workstream(effective_ws) if effective_ws else None
    effective_base = (base or (ws or {}).get("baseBranch", "")
                      or github_api.default_branch(owner, repo))
    effective_head = head or default_branch

    if not effective_head:
        return {"ok": False, "error": "head branch is required"}

    server._audit("github_create_pr", title=title, base=effective_base, head=effective_head)

    result = server._github_request("POST", f"/repos/{owner}/{repo}/pulls", {
        "title": title,
        "body": body,
        "base": effective_base,
        "head": effective_head,
    })

    if result.get("number"):
        pr_number = result["number"]
        response = {
            "ok": True,
            "number": pr_number,
            "url": result.get("html_url"),
            "title": result.get("title"),
        }

        if request_copilot_review:
            copilot_result = server._request_copilot_review(owner, repo, pr_number)
            response["copilot_review_requested"] = copilot_result.get("ok", False)
            if not copilot_result.get("ok"):
                response["copilot_review_error"] = copilot_result.get("error")

        return response
    return result

@mcp.tool()
def github_request_copilot_review(
    pr_number: int = 0,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Request a GitHub Copilot automated code review on a pull request.

    Uses the REST ``POST /repos/{owner}/{repo}/pulls/{n}/requested_reviewers``
    endpoint with the bot's login string (``copilot-pull-request-reviewer``).
    The GraphQL ``requestReviews`` mutation cannot be used because its
    ``userIds`` field only accepts User-type node IDs — Copilot is a Bot
    and its node ID (``BOT_kgDOC9w8XQ``) is rejected with "Could not
    resolve to User node with the global id of 'BOT_...'.".

    Args:
        pr_number: Pull request number. If omitted, the open PR for the
            workstream/branch is looked up automatically.
        workstream_id: Workstream to resolve repo from. Defaults to token context.
        branch: Branch hint used to find the PR when pr_number is not given.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``. Bypasses workstream resolution; scoped tokens are
            checked against this org via the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ok=True on success or ok=False with error details.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    # Direct addressing (org+repo) supplies no branch of its own. When the
    # caller also omits pr_number we'd fall through to a PR lookup with an
    # empty head filter, producing a misleading "No open PR found for
    # branch ''" error. Require an explicit branch (or pr_number) in that
    # case before even resolving the repo.
    if (org and repo) and not pr_number and not branch:
        return {
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
        return err

    effective_pr = pr_number
    if not effective_pr:
        if not effective_branch:
            return {
                "ok": False,
                "error": "no branch available to locate an open PR",
                "next_steps": [
                    "Pass pr_number explicitly, or supply a branch / workstream_id "
                    "with a defaultBranch so the open PR can be looked up",
                ],
            }
        # Look up the open PR for the branch.
        lookup = server._find_open_pr_by_branch(owner, repo, effective_branch)
        if not lookup.get("ok"):
            return lookup
        if not lookup.get("found"):
            return {"ok": False, "error": f"No open PR found for branch '{effective_branch}'"}
        effective_pr = lookup["pr"].get("number")

    server._audit("github_request_copilot_review", pr_number=effective_pr)

    return server._request_copilot_review(owner, repo, effective_pr)

@mcp.tool()
def github_read_file(
    path: str,
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    ref: str = "",
) -> dict:
    """Read any file from a GitHub repository.

    Fetches file content via the GitHub Contents API, routed through the
    FlowTree controller proxy for authentication. The repository is
    resolved from the workstream or supplied explicitly via ``repo_url``.

    Returns the file content as text. Binary files that cannot be decoded
    as UTF-8 are rejected with a clear error. Files larger than 1 MB are
    rejected to prevent accidental large pulls — use grep tools or read
    specific line ranges for large files.

    Args:
        path: File path within the repository (e.g. ``docs/README.md``).
        workstream_id: Workstream to resolve the repository from. When
            both ``workstream_id`` and ``repo_url`` are empty, the
            resolver falls back to local-git detection (useful when the
            server runs against a developer checkout).
        repo_url: Explicit GitHub repository URL (e.g.
            ``https://github.com/owner/repo``). Overrides workstream
            resolution when provided. Scoped tokens are checked against
            the parsed owner via the workspace scope gate.
        branch: Branch to read from. Defaults to the workstream's
            ``defaultBranch`` when available, otherwise the repo's
            default branch. Ignored when ``ref`` is provided.
        ref: Git ref to read at (branch, tag, or commit SHA). Takes
            precedence over ``branch`` when both are provided.

    Returns:
        Dictionary with file content, path, ref, sha, and repo.
    """
    server._require_scope("github")
    err = server._check_short_strings(
        path=path, workstream_id=workstream_id, branch=branch, ref=ref,
    )
    if err:
        return err
    if not path:
        return {"ok": False, "error": "path is required"}

    server._audit("github_read_file", path=path, workstream_id=workstream_id,
           branch=branch, ref=ref)

    # Resolve owner/repo
    if repo_url:
        owner_repo = server._extract_owner_repo(repo_url)
        if not owner_repo:
            return {"ok": False, "error": f"Cannot parse owner/repo from: {repo_url}"}
        owner, repo = owner_repo
        server._require_org_in_scope(owner)
        server._current_github_org.set(owner)
        effective_branch = branch
    else:
        owner, repo, effective_branch, err = server._resolve_github_repo(
            workstream_id=workstream_id, branch=branch,
        )
        if err:
            return err

    effective_ref = ref or effective_branch
    ref_suffix = f"?ref={quote(effective_ref, safe='')}" if effective_ref else ""

    result = server._github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}{ref_suffix}",
    )

    if isinstance(result, dict) and result.get("ok") is False:
        result.setdefault("next_steps", [
            f"Verify the file exists at '{path}' on the specified ref/branch",
            "Check the repo_url or workstream_id is correct",
        ])
        return result

    # The GitHub Contents API returns a JSON array (not a dict) when the
    # supplied path refers to a directory. Surface this explicitly rather
    # than reporting a misleading "Unexpected response".
    if isinstance(result, list):
        return {
            "ok": False,
            "error": (
                f"Path '{path}' refers to a directory, not a file"
            ),
            "repo": f"{owner}/{repo}",
            "next_steps": [
                "Pass a specific file path within the directory",
                "Use git/grep tools to enumerate directory contents",
            ],
        }

    if not isinstance(result, dict):
        return {
            "ok": False,
            "error": "Unexpected response from GitHub Contents API",
        }

    # Enforce size limit before decoding
    file_size = result.get("size", 0)
    if file_size > server._GITHUB_READ_FILE_SIZE_LIMIT:
        return {
            "ok": False,
            "error": (
                f"File '{path}' is {file_size:,} bytes, which exceeds the 1 MB "
                "limit. Use grep tools or read specific line ranges instead."
            ),
            "size": file_size,
            "repo": f"{owner}/{repo}",
        }

    # Decode content
    content_b64 = result.get("content", "")
    encoding = result.get("encoding", "")
    if encoding == "base64" and content_b64:
        # GitHub wraps base64 output in newlines; strip them before decoding.
        try:
            raw_bytes = base64.b64decode(content_b64.replace("\n", ""))
        except (binascii.Error, ValueError) as exc:
            return {
                "ok": False,
                "error": (
                    f"Failed to decode base64 content for '{path}': {exc}"
                ),
                "repo": f"{owner}/{repo}",
            }
        try:
            content = raw_bytes.decode("utf-8")
        except UnicodeDecodeError:
            return {
                "ok": False,
                "error": (
                    f"File '{path}' appears to be binary and cannot be returned "
                    "as text. Fetch it directly from the repository instead."
                ),
                "size": file_size,
                "repo": f"{owner}/{repo}",
            }
    else:
        content = content_b64

    return {
        "ok": True,
        "path": result.get("path", path),
        "repo": f"{owner}/{repo}",
        "ref": effective_ref or "(default branch)",
        "sha": result.get("sha", ""),
        "size": file_size,
        "content": content,
    }

@mcp.tool()
def github_pr_check_status(
    pr_number: int = 0,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Check CI pipeline status for a pull request.

    Fetches the PR's current HEAD commit SHA, then retrieves workflow runs
    and check runs for that exact commit. This answers whether the CI
    pipeline has run for the latest commit and whether it passed.

    The ``pipeline_current`` flag in the response indicates whether at
    least one workflow run targets the PR's HEAD commit SHA — if False,
    the run results shown are for an older commit.

    Run-level status can lag per-job state while a pipeline is executing;
    for the authoritative per-job breakdown of any run listed here, use
    ``github_workflow_run_status`` with the run id.

    Args:
        pr_number: Pull request number. If omitted, the open PR for the
            workstream/branch is looked up automatically.
        workstream_id: Workstream to resolve repo from. Defaults to token
            context.
        branch: Branch hint used to find the PR when pr_number is not
            given. Defaults to the workstream's defaultBranch.
        org: GitHub org (owner) to address directly. Must be passed
            together with ``repo``. Bypasses workstream resolution;
            scoped tokens are checked against this org via the workspace
            scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        Dictionary with pr_number, head_sha, pipeline_current flag,
        overall_status, workflow_runs list, and check_runs list. Failed
        check runs include html_url and details_url for log access.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, effective_branch, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo,
    )
    if err:
        return err

    server._audit("github_pr_check_status", pr_number=pr_number,
           workstream_id=workstream_id, branch=effective_branch)

    # Resolve PR number and head SHA
    effective_pr = pr_number
    head_sha = ""
    pr_branch = effective_branch

    if effective_pr:
        pr_data = server._github_request("GET", f"/repos/{owner}/{repo}/pulls/{effective_pr}")
        if isinstance(pr_data, dict) and pr_data.get("ok") is False:
            return pr_data
        if isinstance(pr_data, dict):
            head_sha = pr_data.get("head", {}).get("sha", "")
            pr_branch = pr_data.get("head", {}).get("ref", effective_branch)
    else:
        if not effective_branch:
            return {
                "ok": False,
                "error": "pr_number or branch is required to look up the PR",
                "next_steps": [
                    "Pass pr_number explicitly",
                    "Or supply workstream_id/branch so the open PR can be found",
                ],
            }
        lookup = server._find_open_pr_by_branch(owner, repo, effective_branch)
        if not lookup.get("ok"):
            return lookup
        if not lookup.get("found"):
            return {
                "ok": False,
                "error": f"No open PR found for branch '{effective_branch}'",
                "next_steps": ["Pass pr_number explicitly if the PR is closed"],
            }
        pr = lookup["pr"]
        effective_pr = pr.get("number")
        head_sha = pr.get("head", {}).get("sha", "")
        pr_branch = pr.get("head", {}).get("ref", effective_branch)

    if not head_sha:
        return {"ok": False, "error": "Could not determine PR head commit SHA"}

    # Fetch workflow runs for the head SHA
    runs_result = server._github_request(
        "GET",
        f"/repos/{owner}/{repo}/actions/runs?head_sha={quote(head_sha, safe='')}",
    )

    if isinstance(runs_result, dict) and runs_result.get("ok") is False:
        return runs_result

    workflow_runs = []
    pipeline_current = False

    if isinstance(runs_result, dict):
        for run in runs_result.get("workflow_runs", []):
            if run.get("head_sha") == head_sha:
                pipeline_current = True
            workflow_runs.append({
                "run_id": run.get("id"),
                "name": run.get("name", ""),
                "status": run.get("status", ""),
                "conclusion": run.get("conclusion"),
                "head_sha": run.get("head_sha", ""),
                "created_at": run.get("created_at", ""),
                "updated_at": run.get("updated_at", ""),
                "html_url": run.get("html_url", ""),
            })

    # Fetch check runs for the head SHA
    check_result = server._github_request(
        "GET",
        f"/repos/{owner}/{repo}/commits/{head_sha}/check-runs",
    )

    if isinstance(check_result, dict) and check_result.get("ok") is False:
        return {
            "ok": False,
            "error": "Failed to fetch check runs",
            "check_runs_error": check_result,
        }

    check_runs = []
    if isinstance(check_result, dict):
        for check in check_result.get("check_runs", []):
            # check_runs are scoped to head_sha by the URL, so any returned
            # entry is also evidence that the pipeline targets the current
            # commit. workflow_runs and check_runs come from independent
            # GitHub endpoints — relying on workflow_runs alone misses cases
            # where check_runs is populated but workflow_runs is empty.
            pipeline_current = True
            check_info = {
                "id": check.get("id"),
                "name": check.get("name", ""),
                "status": check.get("status", ""),
                "conclusion": check.get("conclusion"),
                "html_url": check.get("html_url", ""),
                "started_at": check.get("started_at"),
                "completed_at": check.get("completed_at"),
            }
            if check.get("conclusion") == "failure":
                check_info["details_url"] = check.get("details_url", "")
            check_runs.append(check_info)

    # Derive overall status
    if not workflow_runs and not check_runs:
        overall = "no_runs"
    elif workflow_runs and not pipeline_current:
        overall = "stale"
    else:
        # An in-progress check (status != "completed") means CI is still
        # running even if other checks have already concluded — report
        # "pending" rather than letting completed conclusions decide.
        has_incomplete_checks = any(
            r.get("status") != "completed" for r in check_runs
        )
        conclusions = [r["conclusion"] for r in check_runs if r.get("conclusion")]
        if has_incomplete_checks:
            overall = "pending"
        elif not conclusions:
            overall = "pending"
        elif any(c == "failure" for c in conclusions):
            overall = "failure"
        elif all(c in ("success", "skipped", "neutral") for c in conclusions):
            overall = "success"
        else:
            overall = "mixed"

    next_steps: list = []
    if overall == "no_runs":
        next_steps = [
            "No workflow runs found; the pipeline may not be configured or "
            "hasn't triggered yet",
        ]
    elif overall == "stale":
        next_steps = [
            "The latest workflow run targets an older commit; push a new "
            "commit or manually re-run CI to update the status",
        ]
    elif overall == "failure":
        next_steps = [
            "Review failed check runs above for error details",
            "Use the html_url or details_url links to view full logs",
        ]
    elif overall == "success":
        next_steps = ["All checks passed; the PR is ready to review or merge"]
    elif overall == "pending":
        next_steps = ["CI is still running; check back later"]

    return {
        "ok": True,
        "pr_number": effective_pr,
        "repo": f"{owner}/{repo}",
        "head_sha": head_sha,
        "branch": pr_branch,
        "pipeline_current": pipeline_current,
        "overall_status": overall,
        "workflow_runs": workflow_runs,
        "check_runs": check_runs,
        "next_steps": next_steps,
    }

@mcp.tool()
def github_list_workflow_runs(
    workflow: str = "",
    branch: str = "",
    event: str = "",
    status: str = "",
    actor: str = "",
    limit: int = 20,
    workstream_id: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Search GitHub Actions workflow runs for a repository.

    Unlike ``github_pr_check_status`` (which is scoped to one PR's HEAD
    commit), this lists arbitrary workflow runs so a failure that only
    reproduces in CI can be found and inspected — e.g. every ``failure``
    run of ``analysis.yaml`` on ``master``, or the most recent
    ``workflow_dispatch`` runs of a given workflow. Pair it with
    ``github_workflow_run_status`` to drill into a specific run's jobs.

    This is a discovery tool, not a status tool: the run-level ``status``
    and ``updated_at`` in the listing can lag the run's actual per-job
    state — a run listed as ``queued`` may already have jobs executing or
    failed. Never judge a run's outcome or freshness from this listing;
    follow up with ``github_workflow_run_status`` on the run id. When the
    user names a specific run or job, query that run id directly instead
    of selecting a run from this listing.

    Args:
        workflow: Workflow file name (e.g. ``analysis.yaml``) or numeric
            workflow id to restrict to a single workflow. Empty (default)
            lists runs across all workflows.
        branch: Filter by head branch. Empty (default) means any branch.
        event: Filter by triggering event (``push``, ``pull_request``,
            ``workflow_dispatch``, ``schedule``, ...). Empty means any.
        status: Filter by run status or conclusion (``queued``,
            ``in_progress``, ``completed``, ``success``, ``failure``,
            ``cancelled``, ``timed_out``, ...). Empty means any.
        actor: Filter by the GitHub login that triggered the run. Empty
            means any.
        limit: Maximum number of runs to return (1-100). Defaults to 20.
        workstream_id: Workstream to resolve the repo from. Defaults to
            the token context.
        org: GitHub org (owner) to address directly. Must be passed
            together with ``repo``; bypasses workstream resolution and is
            checked against the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ok=True, total_count, returned, and a workflow_runs
        list; or ok=False with error details.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    owner, repo, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo,
    )
    if err:
        return err

    server._audit("github_list_workflow_runs", workflow=workflow, branch=branch,
           event=event, status=status, workstream_id=workstream_id)

    return github_api.list_workflow_runs(
        owner, repo, workflow=workflow, branch=branch, event=event,
        status=status, actor=actor, limit=limit,
    )

@mcp.tool()
def github_workflow_run_status(
    run_id: int,
    workstream_id: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Get the status, jobs, and failed steps of a single workflow run.

    Given a run id (from ``github_list_workflow_runs`` or a run URL),
    fetches the run's outcome and the per-job breakdown, including which
    steps failed and their log URLs — the detail needed to diagnose a CI
    failure without a PR context. This per-job view is authoritative;
    prefer it over the run-level status shown by listing tools, which
    can lag.

    The failed-step names identify the job and step, not the individual
    failing tests: step logs and per-test output are not returned here
    (job log endpoints require broader permissions than this token has).
    To identify the failing tests, reproduce the job locally with its
    exact configuration — for a test-matrix job, mirror the AR_TEST_GROUP,
    AR_TEST_GROUPS, and hardware flags from the workflow definition —
    or consult the uploaded surefire artifacts via the run's html_url.

    Args:
        run_id: The numeric workflow run id.
        workstream_id: Workstream to resolve the repo from. Defaults to
            the token context.
        org: GitHub org (owner) to address directly. Must be passed
            together with ``repo``; bypasses workstream resolution and is
            checked against the workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ok=True, a shaped run, a jobs list (each with its
        failed_steps and html_url), and a summary (total/failed job counts
        and the run's status/conclusion); or ok=False with error details.
    """
    server._require_scope("github")
    if org and repo:
        server._require_org_in_scope(org)
    if not run_id:
        return {"ok": False, "error": "run_id is required"}
    owner, repo, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, owner=org, repo=repo,
    )
    if err:
        return err

    server._audit("github_workflow_run_status", run_id=run_id,
           workstream_id=workstream_id)

    return github_api.get_workflow_run_status(owner, repo, run_id)
