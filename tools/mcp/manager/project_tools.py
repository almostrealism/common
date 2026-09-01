"""
Project and planning-document tools for the AR Manager MCP server.

Split out of ``server.py`` for length. The tools are unchanged; only their
address is. See ``tracker_tools`` for the conventions this module follows —
the ``_tools`` suffix that makes it visible to tool discovery, and reaching
anything defined in ``server`` through the module rather than by import, so
the suite's patches still apply. Helpers and constants stay in ``server.py``.
"""

import base64
from urllib.parse import quote

import github_api
import server
from server import mcp


@mcp.tool()
def project_create_branch(
    workstream_id: str = "",
    repo_url: str = "",
    plan_title: str = "",
    plan_content: str = "",
) -> dict:
    """Create a planning branch and dispatch the project-manager agent job.

    This triggers the project-manager job of the "Master Agent Dispatch"
    GitHub Actions workflow, which will:
    1. Create a timestamped branch (e.g., project/plan-20260301-143022-title)
    2. Optionally commit a plan document
    3. Register a new workstream for the branch
    4. Submit a planning agent to refine the plan

    The branch name is determined by the workflow (based on the current
    date and plan title), so it cannot be returned immediately. Use
    workstream_list after the workflow completes to see the new workstream.

    The repository is resolved in priority order:
    1. If ``repo_url`` is provided, use it directly.
    2. If ``workstream_id`` is provided, resolve from the workstream config.
    3. If neither is provided, default to ``almostrealism/common`` on master.

    Args:
        workstream_id: Optional source workstream (from workstream_list).
        repo_url: Optional repository URL (HTTPS or SSH). Overrides workstream.
        plan_title: Short title for the plan branch. Slugified into the
            branch name; the timestamp is kept so two runs of the same
            title on one day do not collide.
        plan_content: Optional markdown content for the initial plan
            document. When given, it is committed to the branch as
            ``docs/plans/PLAN-<date>-<slug>.md`` before the agent starts,
            and the agent refines that document rather than surveying the
            project for a task of its own.

    Returns:
        Dictionary confirming the workflow was dispatched.
    """
    server._require_scope("pipeline")
    err = server._check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url, plan_title=plan_title,
    )
    if err:
        return err
    if plan_content:
        err = server._check_length(plan_content, "plan_content", server.MAX_CONTENT_LEN)
        if err:
            return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("project_create_branch", workstream_id=workstream_id,
           repo_url=repo_url, plan_title=plan_title)

    # Resolve repository URL and base branch. The base is left empty here and
    # resolved from GitHub below once owner/repo are known, so a repository
    # whose default branch is not "master" branches from the right place.
    effective_repo = None
    effective_base = ""

    if repo_url:
        effective_repo = repo_url
    elif workstream_id:
        ws = server._find_workstream(workstream_id)
        if ws is None:
            return {
                "ok": False,
                "error": f"Workstream '{workstream_id}' not found",
                "next_steps": ["Use workstream_list to find valid workstream IDs"],
            }
        server._set_github_org(ws)
        effective_repo = ws.get("repoUrl")
        effective_base = ws.get("baseBranch", "")

    if not effective_repo:
        effective_repo = "https://github.com/almostrealism/common"

    owner_repo = server._extract_owner_repo(effective_repo)
    if not owner_repo:
        return {
            "ok": False,
            "error": f"Cannot parse owner/repo from: {effective_repo}",
            "next_steps": ["Provide a valid repo_url (HTTPS or SSH format)"],
        }

    owner, repo = owner_repo
    if not effective_base:
        effective_base = github_api.default_branch(owner, repo)

    inputs = {"agent": "project-manager"}
    if plan_title:
        inputs["plan_title"] = plan_title
    if plan_content:
        inputs["plan_content"] = plan_content

    # The planning job lives alongside the other merge-triggered agent jobs in
    # master-agent-dispatch.yaml; `agent` selects it so a dispatch does not also
    # run the documentation review and the defect hunt.
    result = server._github_request(
        "POST",
        f"/repos/{owner}/{repo}/actions/workflows/master-agent-dispatch.yaml/dispatches",
        {"ref": effective_base, "inputs": inputs},
    )

    if result.get("ok") or result.get("status") == 204:
        return {
            "ok": True,
            "triggered": True,
            "repo": f"{owner}/{repo}",
            "next_steps": [
                "The workflow will create a new branch and register a workstream",
                "Wait 1-2 minutes, then call workstream_list to find the new workstream",
                "The workflow creates a branch named like 'project/plan-YYYYMMDD-HHMMSS-title'",
            ],
        }

    result.setdefault("next_steps", [
        "Check that the master-agent-dispatch.yaml workflow exists in the repository",
        "Verify the GitHub token has 'actions:write' permission",
    ])
    return result

@mcp.tool()
def project_verify_branch(
    workstream_id: str,
    branch: str = "",
    plan_file: str = "",
) -> dict:
    """Dispatch the verify-completion workflow to validate branch work.

    This triggers a GitHub Actions workflow that checks whether the work
    on a branch meets the criteria defined in the planning document.

    Args:
        workstream_id: Workstream to verify (from workstream_list).
        branch: Branch to verify (default: workstream's defaultBranch).
        plan_file: Path to plan file for verification criteria (optional).

    Returns:
        Dictionary confirming the workflow was dispatched.
    """
    server._require_scope("pipeline")
    err = server._check_short_strings(
        workstream_id=workstream_id, branch=branch, plan_file=plan_file,
    )
    if err:
        return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("project_verify_branch", workstream_id=workstream_id, branch=branch)

    ws = server._find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    server._set_github_org(ws)

    repo_url = ws.get("repoUrl")
    if not repo_url:
        return server._pipeline_error(workstream_id, "repo_url is not configured")

    owner_repo = server._extract_owner_repo(repo_url)
    if not owner_repo:
        return {
            "ok": False,
            "error": f"Cannot parse owner/repo from: {repo_url}",
            "next_steps": ["Use workstream_update_config to fix repo_url"],
        }

    owner, repo = owner_repo
    effective_branch = branch or ws.get("defaultBranch", "")
    if not effective_branch:
        return {
            "ok": False,
            "error": "No branch specified and workstream has no defaultBranch",
            "next_steps": ["Provide the branch parameter explicitly"],
        }

    inputs = {}
    if plan_file:
        inputs["plan_file"] = plan_file

    # The workflow uses github.ref_name as the branch, so we dispatch
    # on the target branch (not baseBranch). Inputs only has plan_file.
    result = server._github_request(
        "POST",
        f"/repos/{owner}/{repo}/actions/workflows/verify-completion.yaml/dispatches",
        {"ref": effective_branch, "inputs": inputs},
    )

    if result.get("ok") or result.get("status") == 204:
        return {
            "ok": True,
            "triggered": True,
            "repo": f"{owner}/{repo}",
            "branch": effective_branch,
            "next_steps": [
                f"The verification workflow is running on branch '{effective_branch}'",
                "Check GitHub Actions in the repository for workflow results",
                "Use workstream_get_status to see if the agent completes follow-up tasks",
            ],
        }

    result.setdefault("next_steps", [
        "Check that the verify-completion.yaml workflow exists in the repository",
        "Verify the GitHub token has 'actions:write' permission",
    ])
    return result

@mcp.tool()
def project_commit_plan(
    workstream_id: str,
    content: str,
    path: str = "",
    branch: str = "",
    commit_message: str = "",
) -> dict:
    """Commit a plan document to a branch via the GitHub Contents API.

    This creates or updates a file directly on GitHub without needing a
    local clone. Useful for creating planning documents that agents will
    reference during their work.

    If no path is provided, one is auto-generated as:
    ``docs/plans/PLAN-YYYYMMDD-<slug>.md``

    Args:
        workstream_id: Workstream with repo_url (from workstream_list).
        content: The markdown content of the plan document.
        path: File path in the repository (auto-generated if omitted).
        branch: Branch to commit to (default: workstream's defaultBranch).
        commit_message: Git commit message (auto-generated if omitted).

    Returns:
        Dictionary with commit SHA and file path on success.
    """
    server._require_scope("pipeline")
    err = server._check_length(content, "content", server.MAX_CONTENT_LEN)
    if err:
        return err
    err = server._check_short_strings(
        workstream_id=workstream_id, path=path, branch=branch,
        commit_message=commit_message,
    )
    if err:
        return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("project_commit_plan", workstream_id=workstream_id, path=path, branch=branch)

    ws = server._find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    server._set_github_org(ws)

    repo_url = ws.get("repoUrl")
    if not repo_url:
        return server._pipeline_error(workstream_id, "repo_url is not configured")

    owner_repo = server._extract_owner_repo(repo_url)
    if not owner_repo:
        return {
            "ok": False,
            "error": f"Cannot parse owner/repo from: {repo_url}",
            "next_steps": ["Use workstream_update_config to fix repo_url"],
        }

    owner, repo = owner_repo
    effective_branch = branch or ws.get("defaultBranch", "")
    if not effective_branch:
        return {
            "ok": False,
            "error": "No branch specified and workstream has no defaultBranch",
            "next_steps": ["Provide the branch parameter explicitly"],
        }

    # Auto-generate path if not provided
    if not path:
        date_str = server.datetime.now(server.timezone.utc).strftime("%Y%m%d")
        slug = (
            effective_branch
            .replace("/", "-")
            .replace("_", "-")
            .lower()[:40]
        )
        path = f"docs/plans/PLAN-{date_str}-{slug}.md"

    # Path traversal protection
    normalized = server.os.path.normpath(path)
    if normalized.startswith("..") or "/../" in path or path.startswith("/"):
        return {
            "ok": False,
            "error": "Invalid path: must be a relative path without '..' segments",
        }
    for prefix in server._SENSITIVE_PATH_PREFIXES:
        if normalized.startswith(prefix) or path.startswith(prefix):
            return {
                "ok": False,
                "error": f"Invalid path: cannot target '{prefix}' directory",
            }

    if not commit_message:
        commit_message = f"Add plan document: {path}"

    # Check if file already exists (need current SHA for updates)
    existing = server._github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}?ref={quote(effective_branch, safe='/')}",
    )
    existing_sha = existing.get("sha")

    # Commit the file via Contents API (PUT)
    encoded_content = base64.b64encode(content.encode("utf-8")).decode("ascii")
    payload = {
        "message": commit_message,
        "content": encoded_content,
        "branch": effective_branch,
    }
    if existing_sha:
        payload["sha"] = existing_sha

    result = server._github_request(
        "PUT",
        f"/repos/{owner}/{repo}/contents/{quote(path, safe='/')}",
        payload,
    )

    if result.get("content"):
        commit_sha = result.get("commit", {}).get("sha", "")
        return {
            "ok": True,
            "path": path,
            "branch": effective_branch,
            "commit_sha": commit_sha,
            "repo": f"{owner}/{repo}",
            "next_steps": [
                f"Plan committed to {path} on branch '{effective_branch}'",
                "Use workstream_update_config to set planning_document if not already set",
                "Use workstream_submit_task to send an agent to work on the plan",
            ],
        }

    if not result.get("ok", True):
        result.setdefault("next_steps", [
            "Verify the branch exists on GitHub",
            "Check the GitHub token has 'contents:write' permission",
        ])
    return result

@mcp.tool()
def project_read_plan(
    workstream_id: str,
    path: str = "",
    branch: str = "",
) -> dict:
    """Read the planning document for a workstream (delegates to server.github_read_file).

    Looks up the workstream's configured ``planningDocument`` path and
    delegates to :func:`server.github_read_file` to fetch its content. The
    planning document path must be set via ``workstream_update_config``.

    Args:
        workstream_id: Workstream to read from (from workstream_list).
        path: Override for the planning document path. When omitted, the
            workstream's configured ``planningDocument`` path is used.
        branch: Branch to read from. Defaults to the workstream's
            ``defaultBranch``.

    Returns:
        Dictionary with file content, path, branch, sha, and repo.
    """
    server._require_scope("github")
    err = server._check_short_strings(
        workstream_id=workstream_id, path=path, branch=branch,
    )
    if err:
        return err
    server._require_workstream_in_scope(workstream_id)
    server._audit("project_read_plan", workstream_id=workstream_id, path=path, branch=branch)

    ws = server._find_workstream(workstream_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{workstream_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    effective_path = path or ws.get("planningDocument", "")
    if not effective_path:
        return {
            "ok": False,
            "error": "No planning document path configured for this workstream",
            "next_steps": [
                "Provide the path parameter explicitly",
                "Use workstream_update_config to set planning_document",
            ],
        }

    repo_url = ws.get("repoUrl", "")
    if not repo_url:
        return {
            "ok": False,
            "error": "No repository URL configured for this workstream",
            "next_steps": [
                "Use workstream_update_config to set repo_url",
            ],
        }

    effective_branch = branch or ws.get("defaultBranch", "")
    if not effective_branch:
        return {
            "ok": False,
            "error": "No branch configured for this workstream",
            "next_steps": [
                "Pass branch explicitly when calling project_read_plan",
                "Use workstream_update_config to set default_branch",
            ],
        }

    result = server.github_read_file(
        path=effective_path,
        repo_url=repo_url,
        branch=effective_branch,
        workstream_id=workstream_id,
    )

    if result.get("ok"):
        # Expose branch alongside ref for backward compatibility
        result.setdefault("branch", result.get("ref", effective_branch))
        result["next_steps"] = [
            "Use project_commit_plan to update this document",
            "Use workstream_submit_task to send an agent to work on the plan",
        ]
    else:
        result.setdefault("next_steps", [
            f"Verify the file exists at '{effective_path}'",
            "Use project_commit_plan to create the planning document first",
        ])
    return result
