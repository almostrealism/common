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
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Dict, List, Optional

GITHUB_API_BASE = "https://api.github.com"
PER_PAGE = 100
MAX_PAGES = 20


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


def _get_json(url: str, token: str) -> Dict:
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": "Bearer %s" % token,
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_runs(repo: str, token: str, per_page: int = PER_PAGE, max_pages: int = MAX_PAGES) -> List[Dict]:
    """Fetch recent workflow runs for *repo* (``owner/name``), paged.

    Raises :class:`RuntimeError` if *max_pages* is exhausted while a full
    page is still coming back — silently returning at that point would hand
    the caller a truncated list that looks complete. A caller that hits this
    should raise *max_pages*, since there is more data than promised.

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
    raise RuntimeError(
        "workflow runs for %s exceeded max_pages=%d (%d per page); "
        "raise max_pages to fetch the full list" % (repo, max_pages, per_page)
    )


def fetch_run_jobs(repo: str, run_id: str, token: str, per_page: int = PER_PAGE, max_pages: int = MAX_PAGES) -> List[Dict]:
    """Fetch every job for one workflow run, paged.

    Raises :class:`RuntimeError` if *max_pages* is exhausted while a full
    page is still coming back, for the same reason as :func:`fetch_runs`:
    silently stopping there would drop steps and metrics with no signal.
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
    raise RuntimeError(
        "jobs for run %s exceeded max_pages=%d (%d per page); "
        "raise max_pages to fetch the full list" % (run_id, max_pages, per_page)
    )
