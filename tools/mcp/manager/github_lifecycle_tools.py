"""Branch-lifecycle GitHub tools for the AR Manager MCP server.

These are the GitHub actions an agent takes to drive its *own* branch to a
decision without a person stepping in: close (or reopen) a pull request the
owner has rejected, and start the CI test run that a pushed branch needs before
it can merge. They are kept apart from ``github_tools`` — which manages a pull
request's content and reviews — both because they are a distinct concern (a
state transition and a deployment, not a content edit) and to keep that module
within its length budget.

Like the other GitHub tools, these route every request through the controller's
``/api/github/proxy`` (ar-manager holds no GitHub credential of its own), reach
shared helpers through ``server`` so the test suite's patches still apply, and
enforce the workspace scope gate before any direct ``org``/``repo`` addressing.
Neither tool writes free text to GitHub: a state change carries only
``open``/``closed`` and a test run carries only a branch name and the fixed
deployment fields, so there is no prose or URL for the private-link guard to
screen — that guard covers the content-bearing GitHub tools (``github_create_pr``,
``github_update_pr``, the comment tools).
"""

from typing import Optional

import server
from server import mcp

#: A pull request's open/closed state is the only thing github_set_pr_state
#: changes. GitHub has no "merged" state to set — a merged PR is a closed one —
#: and a merged PR cannot be reopened, which the tool reports rather than
#: attempts.
PR_STATE_VALUES = ("open", "closed")

#: A job that finished in one of these states is what GitHub's
#: rerun-failed-jobs endpoint reruns. A completed run with none of them among
#: its jobs has nothing for github_rerun_failed_jobs to rerun.
RERUNNABLE_JOB_CONCLUSIONS = ("failure", "cancelled", "timed_out")


@mcp.tool()
def github_set_pr_state(
    state: str,
    pr_number: int = 0,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Close or reopen a pull request.

    Only the open/closed state is changed; the title, description and every
    other field are left untouched. This is the counterpart to
    ``github_update_pr``, which deliberately never carries state — use that for
    the title or description. Use this to close a pull request the owner has
    rejected, or to reopen one that was closed without merging.

    A merged pull request cannot be reopened (GitHub refuses it); this tool does
    not try to, and reports the pull request's state unchanged if asked.

    The edit is refused when the workstream (or the round owning an explicit
    ``branch``) is archived, as for ``github_update_pr``: an archived round is
    read-only. Direct ``org``/``repo`` addressing is not guarded that way and is
    scoped only by the workspace org gate.

    Args:
        state: ``"closed"`` to close the pull request, or ``"open"`` to reopen
            one that was closed without merging.
        pr_number: The PR number. When omitted (``0``): for ``"closed"`` the
            branch's open PR is looked up; for ``"open"`` the branch's most
            recent PR is looked up (the open-PR lookup would find nothing, since
            the target is closed). A negative number is rejected.
        workstream_id: Workstream to resolve the repo from. Defaults to token
            context.
        branch: Branch hint used to resolve the repo and, when ``pr_number`` is
            omitted, to look up the pull request.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``; scoped tokens are checked against this org via the
            workspace scope gate. With direct addressing a ``branch`` is required
            when ``pr_number`` is omitted.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ``ok=True``, ``number``, ``url`` and ``state`` on success;
        ``ok=False`` with ``error`` (and ``returned`` when GitHub answered but
        the state did not change) on failure.
    """
    server._require_scope("github")
    direct_addressing = bool(org and repo)
    if direct_addressing:
        server._require_org_in_scope(org)

    normalized_state = state.strip().lower()
    if normalized_state not in PR_STATE_VALUES:
        return {
            "ok": False,
            "error": "state must be 'open' or 'closed'",
        }
    if isinstance(pr_number, bool) or not isinstance(pr_number, int) \
            or pr_number < 0:
        return {"ok": False, "error": "pr_number must be a non-negative integer"}

    # An archived round is read-only, so the state of its pull request is part of
    # that frozen record — the same guards github_update_pr applies. Direct
    # org/repo addressing carries no workstream and is scoped only by the org
    # gate above.
    if not direct_addressing:
        effective_ws = workstream_id or server._get_token_workstream_id() or ""
        ws = server._find_workstream(effective_ws) if effective_ws else None
        archived_err = server._archived_workstream_error(
            "change the state of a pull request", effective_ws, ws=ws)
        if archived_err:
            return archived_err
        if branch and branch != (ws or {}).get("defaultBranch"):
            archived_err = server._archived_branch_error(
                "change the state of a pull request",
                (ws or {}).get("repoUrl", ""), branch)
            if archived_err:
                return archived_err

    owner, repo_name, effective_pr, err = _resolve_pr_for_state(
        normalized_state, pr_number, workstream_id, branch, org, repo)
    if err:
        return err

    server._audit("github_set_pr_state", pr_number=effective_pr,
                  state=normalized_state)

    result = server._github_request(
        "PATCH", f"/repos/{owner}/{repo_name}/pulls/{effective_pr}",
        {"state": normalized_state})

    if isinstance(result, dict) and result.get("ok") is False:
        return result
    if not isinstance(result, dict) or "state" not in result:
        return {
            "ok": False,
            "error": ("GitHub did not return the pull request state, so the "
                      "change could not be confirmed"),
            "returned": result,
        }
    if result.get("state") != normalized_state:
        detail = ""
        if normalized_state == "open" and result.get("merged"):
            detail = "; a merged pull request cannot be reopened"
        return {
            "ok": False,
            "error": (f"the pull request is still {result.get('state')}, not "
                      f"{normalized_state}{detail}"),
            "number": result.get("number"),
            "returned": {"state": result.get("state"),
                         "merged": result.get("merged")},
        }
    return {
        "ok": True,
        "number": result.get("number"),
        "url": result.get("html_url"),
        "state": result.get("state"),
    }


def _resolve_pr_for_state(state: str, pr_number: int, workstream_id: str,
                          branch: str, org: str, repo: str) -> tuple:
    """Resolve the pull request ``github_set_pr_state`` should act on.

    Closing acts on the branch's open PR, so it reuses the shared open-PR
    resolver. Reopening cannot: the target is closed, so the open-PR lookup
    would find nothing; the branch's most recently updated PR is used instead.

    Args:
        state: The normalized target state (``"open"`` or ``"closed"``).
        pr_number: Explicit PR number, or ``0`` to look one up.
        workstream_id: Workstream to resolve the repo from.
        branch: Branch hint for repo resolution and PR lookup.
        org: GitHub org for direct addressing (paired with ``repo``).
        repo: Repository name for direct addressing (paired with ``org``).

    Returns:
        ``(owner, repo, effective_pr, None)`` on success, or
        ``(None, None, None, error_dict)`` when it cannot be resolved.
    """
    if pr_number or state == "closed":
        return server._resolve_effective_pr(
            pr_number=pr_number, workstream_id=workstream_id, branch=branch,
            org=org, repo=repo)

    owner, repo_name, effective_branch, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return None, None, None, err
    if not effective_branch:
        return None, None, None, {
            "ok": False,
            "error": ("a branch or pr_number is required to reopen a pull "
                      "request"),
        }
    lookup = server._find_recent_pr_by_branch(owner, repo_name, effective_branch)
    if not lookup.get("ok"):
        return None, None, None, lookup
    if not lookup.get("found"):
        return None, None, None, {
            "ok": False,
            "error": f"No pull request found for branch '{effective_branch}'",
        }
    return owner, repo_name, lookup["pr"].get("number"), None


def _test_run_branch_error(branch: str) -> Optional[str]:
    """Reject anything that cannot be a plain branch name of the workspace repo.

    A test-run deployment of a fork-controlled commit would run
    fork-controlled workflow code with the repository's secrets, so
    .github/CLAUDE.md requires that a test run name a branch and resolve it
    against ``refs/heads`` — never a raw SHA, a pull ref, or a fork head.

    Only the forms that can never be a plain branch name are rejected here: a
    fork head (``owner:branch``), a pull-request ref (``refs/pull/...`` or the
    bare ``pull/...``), and a fully-qualified ref (``refs/...``). A name whose
    shape merely resembles a commit SHA (an all-hex string such as a date-like
    ``"20261121"``), or an ordinary branch that contains ``pull`` as a path
    segment (``feature/pull/fix``), is a legitimate ``refs/heads`` name and is
    left to the exact ``refs/heads`` lookup in :func:`github_start_test_run`.
    That lookup is the real gate — it confirms a real branch and 404s a raw SHA
    that is not also a branch — so a name-shape guess here would only refuse
    legitimate branch names.

    Args:
        branch: The branch name as resolved for the request.

    Returns:
        An error message when ``branch`` cannot be a bare branch name, else
        ``None``.
    """
    candidate = (branch or "").strip()
    if not candidate:
        return "a branch name is required"
    if ":" in candidate:
        return ("a fork head ('owner:branch') is not allowed; name a branch of "
                "the workspace's own repository")
    if candidate.startswith("refs/pull/") or candidate.startswith("pull/"):
        return "a pull request ref is not allowed; name a branch (refs/heads/...)"
    if candidate.startswith("refs/"):
        return "pass a plain branch name, not a fully-qualified ref"
    return None


@mcp.tool()
def github_start_test_run(
    branch: str = "",
    workstream_id: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Start a CI test run for a branch of the workspace's repository.

    A branch's pull request is unmergeable until a test run has passed on its
    head commit (see .github/CLAUDE.md, "Validate and Test Runs"). A test run is
    started by creating a ``deployment`` of the branch to the ``test``
    environment. This tool asks the controller to create that deployment through
    its GitHub proxy, which uses the controller's own per-org credential — the
    non-``GITHUB_TOKEN`` token GitHub requires for an event to start workflows.

    It accepts only a branch name of the workspace's repository and resolves it
    against ``refs/heads`` before deploying; a raw commit SHA, a pull-request ref
    (``refs/pull/N/head``) and a fork head (``owner:branch``) are refused,
    because a test run executes the branch's own workflow file with the
    repository's secrets and must never be pointed at fork-controlled code. The
    deployment is created with ``auto_merge: false`` (the default would merge
    master into the branch first) and empty ``required_contexts`` (the default
    refuses to deploy until every commit status has already passed).

    Args:
        branch: The branch to test. Defaults to the workstream's branch when a
            workstream supplies one.
        workstream_id: Workstream to resolve the repo (and default branch) from.
            Defaults to token context.
        org: GitHub org (owner) to address directly. Must be passed together
            with ``repo``; scoped tokens are checked against this org via the
            workspace scope gate. With direct addressing a ``branch`` is
            required.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ``ok=True``, ``deployment_id``, ``sha``, ``environment``,
        ``branch`` and ``next_steps`` for following the run on success;
        ``ok=False`` with ``error`` when the branch is not a plain branch of the
        repository, does not exist, or the deployment could not be created.
    """
    server._require_scope("github")
    direct_addressing = bool(org and repo)
    if direct_addressing:
        server._require_org_in_scope(org)

    owner, repo_name, effective_branch, err = server._resolve_github_repo(
        workstream_id=workstream_id, branch=branch, owner=org, repo=repo)
    if err:
        return err

    target = branch or effective_branch
    branch_error = _test_run_branch_error(target)
    if branch_error:
        return {
            "ok": False,
            "error": branch_error,
            "next_steps": [
                "Pass branch=<feature-branch> naming a branch of this repository",
            ],
        }

    server._audit("github_start_test_run", branch=target)

    # Confirm the branch exists on refs/heads of this repository. The singular
    # git/ref endpoint matches exactly (or 404s), so this both rejects a SHA /
    # pull ref / fork that slipped the name check and avoids deploying a branch
    # that is not there. Path parts are literal; the proxy URL-encodes them.
    ref = server._github_request(
        "GET", f"/repos/{owner}/{repo_name}/git/ref/heads/{target}")
    if isinstance(ref, dict) and ref.get("ok") is False:
        return {
            "ok": False,
            "error": (f"branch '{target}' was not found on refs/heads of "
                      f"{owner}/{repo_name}"),
            "returned": ref,
        }
    if not isinstance(ref, dict) or ref.get("ref") != f"refs/heads/{target}":
        return {
            "ok": False,
            "error": (f"branch '{target}' did not resolve to a single "
                      f"refs/heads ref on {owner}/{repo_name}"),
            "returned": ref,
        }

    deployment = server._github_request(
        "POST", f"/repos/{owner}/{repo_name}/deployments",
        {
            "ref": target,
            "environment": "test",
            "auto_merge": False,
            "required_contexts": [],
            "transient_environment": True,
        })

    if isinstance(deployment, dict) and deployment.get("ok") is False:
        return deployment
    if not isinstance(deployment, dict) or "id" not in deployment:
        return {
            "ok": False,
            "error": ("GitHub did not return a deployment, so the test run could "
                      "not be confirmed"),
            "returned": deployment,
        }

    return {
        "ok": True,
        "deployment_id": deployment.get("id"),
        "sha": deployment.get("sha"),
        "environment": deployment.get("environment"),
        "branch": target,
        "next_steps": [
            "Follow the run with github_list_workflow_runs("
            f"branch='{target}', event='deployment')",
            "Then github_workflow_run_status(run_id=...) for the run's jobs",
        ],
    }


def _run_has_rerunnable_job(jobs: list) -> bool:
    """True when at least one of ``jobs`` failed, was cancelled, or timed out.

    These are the conclusions GitHub's rerun-failed-jobs endpoint acts on; a
    completed run with none of them among its jobs has nothing to rerun.

    Args:
        jobs: The run's jobs, as returned by the run's ``/jobs`` endpoint.

    Returns:
        True when any job carries a rerunnable conclusion.
    """
    return any(
        (job.get("conclusion") or "") in RERUNNABLE_JOB_CONCLUSIONS
        for job in jobs
    )


def _list_run_jobs(owner: str, repo: str, run_id: int):
    """List the latest attempt's jobs of a workflow run, following pagination.

    GitHub's run-jobs endpoint returns at most 100 jobs per page, so a run with
    more than 100 jobs carries its later jobs — and any failure among them — on
    the pages after the first. Reading every page is what lets a caller reason
    about the run's full set of job conclusions; stopping at the first page can
    miss a failure that sits beyond it.

    The request pins ``filter=latest`` so only the most recent attempt's jobs
    are returned. That is already the endpoint's default, but a rerun decision
    must never see a failure left behind by an earlier attempt that the latest
    attempt has since passed, so the contract is made explicit rather than left
    to the default.

    Args:
        owner: The repository owner.
        repo: The repository name.
        run_id: The numeric workflow run id.

    Returns:
        The list of raw job objects from every page, or the error dict from the
        first request that failed.
    """
    all_jobs = []
    page = 1
    while True:
        result = server._github_request(
            "GET",
            f"/repos/{owner}/{repo}/actions/runs/{run_id}/jobs"
            f"?per_page=100&page={page}&filter=latest")
        if isinstance(result, dict) and result.get("ok") is False:
            return result
        jobs = result.get("jobs", []) if isinstance(result, dict) else []
        all_jobs.extend(jobs)
        if len(jobs) < 100:
            return all_jobs
        page += 1


@mcp.tool()
def github_rerun_failed_jobs(
    run_id: int = 0,
    workstream_id: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
    """Rerun only the failed jobs of an existing workflow run, in place.

    GitHub reruns the failed, cancelled and timed-out jobs of ``run_id`` as a
    new attempt of that same run: the jobs that passed are kept, and the run's
    identity and URL do not change. Use this to recover a run that went red on a
    transient failure — a flaky test, a runner that dropped out — without
    starting a fresh run from scratch.

    This is the counterpart to ``github_start_test_run`` and the two are not
    interchangeable. ``github_start_test_run`` creates a *new* ``deployment`` run
    of a branch; it cannot touch an existing run, so the original red run stays
    red, and on a repository whose workflow has no deployment trigger it starts
    nothing at all. This reruns the failed jobs of a run that already exists,
    whatever event triggered it. Reach for ``github_start_test_run`` when there
    is no run yet (a pushed branch that needs its first test run); reach for this
    when a run exists and some of its jobs failed.

    Before rerunning, the run is read and the request is refused when it is still
    queued or in progress (nothing has finished to rerun yet) or when none of its
    jobs failed, were cancelled or timed out (nothing to rerun). There is
    deliberately no "rerun all jobs" mode: rerunning jobs that already passed is
    wasteful and is not what recovering a red run needs.

    Like the other GitHub tools this routes through the controller's GitHub
    proxy, so the rerun is posted with the controller's own per-org credential —
    the same credential ``github_start_test_run`` uses.

    Args:
        run_id: The numeric workflow run id, from ``github_list_workflow_runs``
            or ``github_workflow_run_status``. Required.
        workstream_id: Workstream to resolve the repo from. Defaults to the token
            context. Used when ``org``/``repo`` are not given.
        org: GitHub org (owner) to address directly. Must be passed together with
            ``repo``; scoped tokens are checked against this org via the
            workspace scope gate.
        repo: GitHub repository name. Must be passed together with ``org``.

    Returns:
        dict with ``ok=True``, ``run_id``, ``repo``, ``run_attempt`` (the run's
        attempt number after the rerun, when GitHub reports it), ``html_url`` and
        ``next_steps`` pointing to ``github_workflow_run_status`` on success;
        ``ok=False`` with ``error`` when the run is unfinished, has nothing to
        rerun, or GitHub rejects the request (a 403 notes that the controller's
        credential may lack Actions write on the repository).
    """
    server._require_scope("github")
    direct_addressing = bool(org and repo)
    if direct_addressing:
        server._require_org_in_scope(org)

    if isinstance(run_id, bool) or not isinstance(run_id, int) or run_id <= 0:
        return {"ok": False, "error": "run_id must be a positive integer"}

    owner, repo_name, _, err = server._resolve_github_repo(
        workstream_id=workstream_id, owner=org, repo=repo)
    if err:
        return err

    server._audit("github_rerun_failed_jobs", run_id=run_id,
                  workstream_id=workstream_id)

    run = server._github_request(
        "GET", f"/repos/{owner}/{repo_name}/actions/runs/{run_id}")
    if isinstance(run, dict) and run.get("ok") is False:
        return run
    if not isinstance(run, dict) or "status" not in run:
        return {
            "ok": False,
            "error": (f"could not read workflow run {run_id} on "
                      f"{owner}/{repo_name}"),
            "returned": run,
        }

    # GitHub reruns the jobs of a completed run only, so an unfinished run is
    # refused before its jobs are even listed.
    status = (run.get("status") or "").strip().lower()
    if status != "completed":
        return {
            "ok": False,
            "error": (f"workflow run {run_id} is {status or 'not completed'}; "
                      "wait for it to finish before rerunning its failed jobs"),
        }

    jobs_result = _list_run_jobs(owner, repo_name, run_id)
    if isinstance(jobs_result, dict) and jobs_result.get("ok") is False:
        return jobs_result
    jobs = jobs_result if isinstance(jobs_result, list) else []
    if not _run_has_rerunnable_job(jobs):
        return {
            "ok": False,
            "error": (f"workflow run {run_id} has no failed, cancelled or "
                      "timed-out jobs to rerun"),
        }

    rerun = server._github_request(
        "POST",
        f"/repos/{owner}/{repo_name}/actions/runs/{run_id}/rerun-failed-jobs")
    if isinstance(rerun, dict) and rerun.get("ok") is False:
        error = rerun.get("error", "")
        if "HTTP 403" in error:
            error = (f"{error}; the controller's credential may lack Actions "
                     f"write permission on {owner}/{repo_name}")
        return {"ok": False, "error": error}

    # The rerun endpoint answers 201 with no body, so the attempt number the
    # rerun produced comes from re-reading the run. A rerun advances the
    # attempt, so only a refreshed attempt greater than the pre-rerun one
    # confirms the new attempt; the pre-rerun value — and a GET that is briefly
    # stale and still reports it — is not usable. When the re-read cannot
    # confirm an advanced attempt, run_attempt stays None rather than reporting
    # a stale number the caller might act on, and next_steps sends the caller to
    # status polling. The run's identity and URL do not change, so the pre-rerun
    # read still supplies html_url.
    pre_attempt = run.get("run_attempt")
    refreshed = server._github_request(
        "GET", f"/repos/{owner}/{repo_name}/actions/runs/{run_id}")
    attempt = None
    html_url = run.get("html_url")
    if isinstance(refreshed, dict) and refreshed.get("ok") is not False:
        refreshed_attempt = refreshed.get("run_attempt")
        html_url = refreshed.get("html_url", html_url)
        if isinstance(refreshed_attempt, int) \
                and not isinstance(refreshed_attempt, bool) \
                and (not isinstance(pre_attempt, int)
                     or isinstance(pre_attempt, bool)
                     or refreshed_attempt > pre_attempt):
            attempt = refreshed_attempt

    # A direct-addressed call may carry no workstream or token context, so its
    # follow-up names org/repo; an explicit workstream_id must likewise be
    # carried, or the status lookup falls back to token context and can resolve
    # a different repo (or none). A purely contextual call keeps the short form.
    if direct_addressing:
        follow_up = (f"github_workflow_run_status(run_id={run_id}, "
                     f"org='{owner}', repo='{repo_name}')")
    elif workstream_id:
        follow_up = (f"github_workflow_run_status(run_id={run_id}, "
                     f"workstream_id='{workstream_id}')")
    else:
        follow_up = f"github_workflow_run_status(run_id={run_id})"

    return {
        "ok": True,
        "run_id": run_id,
        "repo": f"{owner}/{repo_name}",
        "run_attempt": attempt,
        "html_url": html_url,
        "next_steps": [
            f"Follow the new attempt with {follow_up}",
        ],
    }
