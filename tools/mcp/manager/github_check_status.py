"""Overall-status derivation for ``github_pr_check_status``.

Kept out of ``github_tools.py`` so that module does not grow past its length
cap, and so the status vocabulary has a single home. These are pure functions
over already-fetched GitHub payloads — all HTTP stays in the tool.

The degradation path exists because a fine-grained personal access token can
be granted ``Actions: read`` but not ``Checks: read`` on a given repository,
so the commit check-runs endpoint answers ``403`` while workflow runs succeed.
When that happens the tool records the error as partial and derives the
overall status from the workflow runs and the combined commit status instead
of failing the whole call — see :func:`overall_status`.
"""

# Conclusions that count as a clean pass.
_PASSING = ("success", "skipped", "neutral")


def commit_status_signal(state: str):
    """Map a combined commit-status ``state`` onto a ``(status, conclusion)``
    pair in the check-run vocabulary, so it folds into the same derivation as
    check runs and workflow runs.

    GitHub's combined status ``state`` is one of ``success``, ``failure``,
    ``error`` or ``pending``; ``error`` is treated as a failure.

    Args:
        state: the combined commit-status ``state`` value.

    Returns:
        a ``(status, conclusion)`` tuple.
    """
    if state == "success":
        return ("completed", "success")
    if state in ("failure", "error"):
        return ("completed", "failure")
    return ("in_progress", None)


def _signals(workflow_runs: list, check_runs: list, commit_status_state: str):
    """The ``(status, conclusion)`` pairs the overall status is derived from.

    Check runs are the richest signal and are used alone when present, which
    keeps behaviour identical for repositories whose checks the token can
    read. When they are absent — unreadable on this repository, for example —
    the workflow runs and the combined commit status stand in for them.
    """
    if check_runs:
        return [(r.get("status"), r.get("conclusion")) for r in check_runs]
    signals = [(r.get("status"), r.get("conclusion")) for r in workflow_runs]
    if commit_status_state:
        signals.append(commit_status_signal(commit_status_state))
    return signals


def overall_status(workflow_runs: list, check_runs: list,
                   commit_status_state: str, pipeline_current: bool,
                   had_errors: bool) -> str:
    """Derive the single overall-status word for a PR head commit.

    Returns one of ``no_runs``, ``unknown``, ``stale``, ``pending``,
    ``failure``, ``success`` or ``mixed``. ``unknown`` is distinct from
    ``no_runs``: it means nothing could be read because a sub-request failed,
    not that the commit genuinely has no runs.

    Args:
        workflow_runs: the shaped workflow runs for the head commit.
        check_runs: the shaped check runs for the head commit (empty when
            the check-runs endpoint could not be read).
        commit_status_state: the combined commit-status ``state``, or ``""``
            when it was not consulted or carried no statuses.
        pipeline_current: whether a run targets the PR's head commit SHA.
        had_errors: whether any sub-request failed (so the result is partial).

    Returns:
        the overall-status word.
    """
    if not workflow_runs and not check_runs and not commit_status_state:
        return "unknown" if had_errors else "no_runs"
    if workflow_runs and not pipeline_current and not commit_status_state:
        return "stale"
    signals = _signals(workflow_runs, check_runs, commit_status_state)
    if any(status != "completed" for status, _ in signals):
        return "pending"
    conclusions = [c for _, c in signals if c]
    if not conclusions:
        return "pending"
    if any(c == "failure" for c in conclusions):
        return "failure"
    if all(c in _PASSING for c in conclusions):
        return "success"
    return "mixed"
