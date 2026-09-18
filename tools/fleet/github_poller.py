#!/usr/bin/env python3
# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Pull GitHub Actions job data and compute queue-wait/pre-start-latency
metrics from it.

Reuses the ``qa-cadence.sh`` curl/jq pattern (paged, bearer-authenticated
``api.github.com`` calls) but in Python, using only the standard library —
the ``python-tests`` CI job installs no HTTP client beyond what
``tools/mcp/requirements.txt`` already pulls in for the MCP servers, and this
module has no need for it.

**Metric naming:** the raw interval ``started_at - created_at`` is called
``pre_start_latency`` here, not "queue wait". For a job gated behind a
workflow ``needs:``, that interval includes time blocked on upstream jobs,
not only time waiting for a free runner, so it is only a faithful
*queue-wait* measurement for a job with no dependencies (an "entry-point"
job). ``compute_job_metrics`` requires the caller to state which case
applies — it will not guess.

**Rate limits.** GitHub's API returns HTTP 429, or HTTP 403 with a
``Retry-After`` or ``X-RateLimit-Remaining: 0`` header, when a caller is
rate-limited (see :func:`_is_retryable_rate_limit`). ``_get_json`` retries
such a response with the server-directed delay (bounded by
``RATE_LIMIT_MAX_RETRIES``) rather than surfacing it as an immediate failure
— a periodic poller that gives up on the first transient rate limit would
stop collecting data long before it actually clears. A 403 with neither
header is a permission error (bad/expired token, wrong scope), not a rate
limit, and is raised immediately instead of being retried against a token
that will never succeed.

**Persistence.** ``poll_and_store`` is the poll-cycle entry point: it
composes :func:`fetch_runs`/:func:`fetch_run_jobs`/:func:`compute_job_metrics`
and upserts the result into a :class:`tools.fleet.store.FleetStore`. The
lower-level fetch/compute functions above are also useful standalone (e.g.
for a caller that has already parsed the workflow graph and can supply
``needs``), so they remain independently callable.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Dict, List, Optional

from tools.fleet.store import FleetStore

GITHUB_API_BASE = "https://api.github.com"
PER_PAGE = 100
MAX_PAGES = 20
RATE_LIMIT_MAX_RETRIES = 3
RATE_LIMIT_DEFAULT_RETRY_SECONDS = 60.0


def _parse_github_timestamp(value: Optional[str]) -> Optional[datetime]:
    """Parse a GitHub API timestamp (``2026-09-18T04:02:54Z``) to aware UTC."""
    if not value:
        return None
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def compute_job_metrics(
    job: Dict,
    needs: Optional[List[str]] = None,
    dependency_completed_at: Optional[str] = None,
) -> Dict[str, Optional[float]]:
    """Compute ``pre_start_latency_seconds``, ``is_entry_point``, and
    ``queue_wait_seconds`` for one workflow-job API object.

    Args:
        job: one element of the ``jobs`` array from
            ``GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs``. Must
            carry ``created_at``/``started_at`` (both nullable — a job that
            has not started yet yields ``None`` metrics).
        needs: the job's ``needs:`` list from the workflow definition, if
            known. ``None`` means "unknown" — the caller has not parsed the
            workflow graph — and ``is_entry_point``/``queue_wait_seconds``
            are both left ``None`` rather than guessed; presenting an
            unqualified number as queue wait would misrepresent it whenever
            the job actually has dependencies. An empty list means the job
            genuinely has no dependency.
        dependency_completed_at: for a non-entry-point job, the completion
            timestamp of its last dependency, used for a dependency-adjusted
            queue-wait calculation. Ignored when ``needs`` is empty or
            ``None``.

    Returns:
        dict with ``pre_start_latency_seconds`` (float or None),
        ``is_entry_point`` (bool or None), ``queue_wait_seconds`` (float or
        None).
    """
    created_at = _parse_github_timestamp(job.get("created_at"))
    started_at = _parse_github_timestamp(job.get("started_at"))

    pre_start_latency_seconds = None
    if created_at is not None and started_at is not None:
        pre_start_latency_seconds = (started_at - created_at).total_seconds()

    is_entry_point = None if needs is None else (len(needs) == 0)

    queue_wait_seconds = None
    if is_entry_point:
        queue_wait_seconds = pre_start_latency_seconds
    elif is_entry_point is False and started_at is not None:
        dep_completed = _parse_github_timestamp(dependency_completed_at)
        if dep_completed is not None:
            eligible_at = max(dep_completed, created_at) if created_at is not None else dep_completed
            queue_wait_seconds = (started_at - eligible_at).total_seconds()

    return {
        "pre_start_latency_seconds": pre_start_latency_seconds,
        "is_entry_point": is_entry_point,
        "queue_wait_seconds": queue_wait_seconds,
    }


def _is_retryable_rate_limit(exc: "urllib.error.HTTPError") -> bool:
    """Whether *exc* is a rate limit that should be retried, not a hard failure.

    A 429 is always a rate limit. A 403 is ambiguous on the GitHub API: it is
    returned both for a secondary rate limit and for permission-denied (a
    bad/expired token or a token missing the required scope) — the two share
    a status code, so the status alone cannot distinguish them. A 403 is only
    treated as a rate limit when its headers say so: a ``Retry-After`` header
    (used for secondary rate limits) or ``X-RateLimit-Remaining: 0`` (the
    primary hourly quota exhausted). A permission-denied 403 carries neither
    header and is therefore raised immediately instead of being retried
    ``RATE_LIMIT_MAX_RETRIES`` times against a token that will never succeed.
    """
    if exc.code == 429:
        return True
    if exc.code != 403:
        return False
    headers = exc.headers
    if headers is None:
        return False
    if headers.get("Retry-After") is not None:
        return True
    return headers.get("X-RateLimit-Remaining") == "0"


def _rate_limit_delay_seconds(exc: "urllib.error.HTTPError") -> float:
    """Derive a retry delay from a rate-limited response's headers.

    Prefers ``Retry-After`` (seconds, used for secondary rate limits) and
    falls back to ``X-RateLimit-Reset`` (an epoch-seconds timestamp, used
    when the primary hourly quota is exhausted). Neither header is
    guaranteed to be present or well-formed, so an unparsable/missing value
    falls back to :data:`RATE_LIMIT_DEFAULT_RETRY_SECONDS` rather than
    raising.
    """
    headers = exc.headers
    if headers is not None:
        retry_after = headers.get("Retry-After")
        if retry_after is not None:
            try:
                return max(0.0, float(retry_after))
            except ValueError:
                pass
        reset_at = headers.get("X-RateLimit-Reset")
        if reset_at is not None:
            try:
                return max(0.0, float(reset_at) - time.time())
            except ValueError:
                pass
    return RATE_LIMIT_DEFAULT_RETRY_SECONDS


def _get_json(url: str, token: str, max_retries: int = RATE_LIMIT_MAX_RETRIES) -> Dict:
    """Fetch and parse one JSON response from the GitHub API.

    Retries a rate-limited response (see :func:`_is_retryable_rate_limit`) up
    to *max_retries* times, sleeping for the delay the response itself
    directs (see :func:`_rate_limit_delay_seconds`) between attempts, before
    giving up and letting the error propagate to the caller as any other
    failure would. A permission-denied 403 and any other HTTP status raise
    immediately, without consuming a retry.
    """
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": "Bearer %s" % token,
            "Accept": "application/vnd.github+json",
        },
    )
    attempt = 0
    while True:
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if _is_retryable_rate_limit(exc) and attempt < max_retries:
                time.sleep(_rate_limit_delay_seconds(exc))
                attempt += 1
                continue
            raise


def fetch_runs(
    repo: str,
    token: str,
    per_page: int = PER_PAGE,
    max_pages: int = MAX_PAGES,
    allow_partial: bool = False,
) -> List[Dict]:
    """Fetch recent workflow runs for *repo* (``owner/name``), paged.

    Raises :class:`RuntimeError` if *max_pages* is exhausted while a full
    page is still coming back — silently returning at that point would hand
    the caller a truncated list that looks complete. A caller that hits this
    should raise *max_pages*, since there is more data than promised.

    *allow_partial* opts out of that guarantee for a caller that deliberately
    wants only a bounded, most-recent window rather than the full run
    history (see :func:`poll_and_store`) — such a caller sets a small
    *max_pages* on purpose and a "more data exists beyond the cap" condition
    is exactly what it expects, not an error.

    Least-privilege note: *token* should be a read-only Actions-scoped
    credential, never logged or embedded in a returned error message.
    """
    runs: List[Dict] = []
    page = 1
    while page <= max_pages:
        url = "%s/repos/%s/actions/runs?per_page=%d&page=%d" % (GITHUB_API_BASE, repo, per_page, page)
        try:
            payload = _get_json(url, token)
        except urllib.error.URLError as exc:
            raise RuntimeError("failed to fetch workflow runs (page %d): %s" % (page, exc)) from exc
        batch = payload.get("workflow_runs", [])
        runs.extend(batch)
        if len(batch) < per_page:
            return runs
        page += 1
    if allow_partial:
        return runs
    raise RuntimeError(
        "workflow runs for %s exceeded max_pages=%d (%d per page); "
        "raise max_pages to fetch the full list" % (repo, max_pages, per_page)
    )


def fetch_run_jobs(
    repo: str,
    run_id: str,
    token: str,
    per_page: int = PER_PAGE,
    max_pages: int = MAX_PAGES,
    allow_partial: bool = False,
) -> List[Dict]:
    """Fetch every job for one workflow run, paged.

    Raises :class:`RuntimeError` if *max_pages* is exhausted while a full
    page is still coming back, for the same reason as :func:`fetch_runs`:
    silently stopping there would drop steps and metrics with no signal.
    *allow_partial* opts out of that guarantee; see :func:`fetch_runs`.
    """
    jobs: List[Dict] = []
    page = 1
    while page <= max_pages:
        url = "%s/repos/%s/actions/runs/%s/jobs?per_page=%d&page=%d" % (
            GITHUB_API_BASE, repo, run_id, per_page, page,
        )
        try:
            payload = _get_json(url, token)
        except urllib.error.URLError as exc:
            raise RuntimeError("failed to fetch jobs for run %s (page %d): %s" % (run_id, page, exc)) from exc
        batch = payload.get("jobs", [])
        jobs.extend(batch)
        if len(batch) < per_page:
            return jobs
        page += 1
    if allow_partial:
        return jobs
    raise RuntimeError(
        "jobs for run %s exceeded max_pages=%d (%d per page); "
        "raise max_pages to fetch the full list" % (run_id, max_pages, per_page)
    )


DEFAULT_POLL_MAX_RUNS = 100
DEFAULT_POLL_RUN_MAX_PAGES = 2


def poll_and_store(
    repo: str,
    token: str,
    store: FleetStore,
    max_runs: Optional[int] = DEFAULT_POLL_MAX_RUNS,
    run_per_page: int = PER_PAGE,
    run_max_pages: int = DEFAULT_POLL_RUN_MAX_PAGES,
    job_per_page: int = PER_PAGE,
    job_max_pages: int = MAX_PAGES,
) -> int:
    """One poll cycle: fetch recent runs and their jobs for *repo*, compute
    job metrics, and upsert every job/step into *store*.

    This is the entry point that actually populates ``job_event``/
    ``job_step`` — :func:`fetch_runs`, :func:`fetch_run_jobs`, and
    :func:`compute_job_metrics` are the lower-level pieces it composes, and
    remain independently callable for a caller that wants to interpose (e.g.
    supply a parsed ``needs`` workflow graph for a precise ``queue_wait``).

    The workflow's dependency graph (``needs:``) is not available from the
    job API this function calls, so every job here is persisted with
    ``needs=None`` — ``is_entry_point``/``queue_wait_seconds`` stay unset and
    only the always-honest ``pre_start_latency_seconds`` is populated (see
    the module docstring and :func:`compute_job_metrics`). Deriving
    ``needs`` from the workflow YAML to unlock ``queue_wait_seconds`` for
    dependent jobs is the dependency-adjusted follow-up the design document
    describes and is not implemented here.

    Every call re-fetches and re-upserts recent runs/jobs (idempotent, since
    every write here is a natural-key upsert), rather than tracking what
    changed since the last poll — a periodic poller re-reading a bounded
    recent window on every cycle does not need that finer-grained
    incremental fetch. The window is bounded two ways so a repository with a
    long run history cannot force one jobs request per run every cycle, or
    exceed ``fetch_runs``'s own hard cap (2,000 runs at the defaults):
    *run_max_pages* keeps the *fetch* itself to a small number of pages
    (fetched with ``allow_partial=True``, since a periodic poller wants a
    bounded recent window, not a guarantee that no run exists beyond it —
    unlike a caller of :func:`fetch_runs` directly), and *max_runs* then
    truncates the result further before the (per-run) jobs fetch, since the
    GitHub API returns runs newest-first. *run_per_page*/*job_per_page* and
    *job_max_pages* are passed straight through to :func:`fetch_runs`/
    :func:`fetch_run_jobs`.

    Returns the number of job_event rows upserted.
    """
    runs = fetch_runs(repo, token, per_page=run_per_page, max_pages=run_max_pages, allow_partial=True)
    if max_runs is not None:
        runs = runs[:max_runs]
    jobs_stored = 0
    for run in runs:
        run_id = str(run.get("id"))
        for job in fetch_run_jobs(repo, run_id, token, per_page=job_per_page, max_pages=job_max_pages):
            job_id = str(job.get("id"))
            metrics = compute_job_metrics(job)
            # Sorted so the same label set always serializes identically
            # regardless of the order the API happens to return it in —
            # `job_event` is grouped by this string (see
            # `FleetStore.pre_start_latency_by_label`), and an unsorted join
            # would split one label set into separate buckets across polls.
            labels = sorted(job.get("labels") or [])
            store.upsert_job_event(
                job_id=job_id,
                run_id=run_id,
                repo=repo,
                name=job.get("name") or "",
                labels=",".join(labels),
                created_at=job.get("created_at"),
                started_at=job.get("started_at"),
                completed_at=job.get("completed_at"),
                status=job.get("status") or "",
                conclusion=job.get("conclusion") or "",
                runner_name=job.get("runner_name") or "",
                runner_group=job.get("runner_group_name") or "",
                pre_start_latency_seconds=metrics["pre_start_latency_seconds"],
                is_entry_point=metrics["is_entry_point"],
                queue_wait_seconds=metrics["queue_wait_seconds"],
            )
            for step in job.get("steps") or []:
                store.upsert_job_step(
                    job_id=job_id,
                    number=step.get("number"),
                    name=step.get("name") or "",
                    started_at=step.get("started_at"),
                    completed_at=step.get("completed_at"),
                    conclusion=step.get("conclusion") or "",
                )
            jobs_stored += 1
    return jobs_stored
