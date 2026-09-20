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
and upserts the result into a :class:`tools.fleet.store.FleetStore`, wrapping
every upsert for the cycle in a single
:meth:`~tools.fleet.store.FleetStore.transaction` so the cycle commits once
rather than once per job/step. The lower-level fetch/compute functions above
are also useful standalone (e.g. for a caller that has already parsed the
workflow graph and can supply ``needs``), so they remain independently
callable. ``poll_and_store`` itself accepts an optional ``resolve_needs``
callback for exactly that caller — see its docstring — so
``is_entry_point``/``queue_wait_seconds`` are not permanently NULL in the
production poll path, just NULL whenever no
resolver is supplied or it does not know a given job's dependency graph.

**Labels.** The ``labels`` persisted per job are the *executing runner's*
actual label set (the workflow-jobs API's own ``labels`` field), not the
workflow's requested ``runs-on:`` set — a runner can carry extra/custom
labels beyond what a job asked for. Resolving the requested set would need
the run's workflow YAML parsed, which this module does not do.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional, Tuple

from tools.fleet.credentials import read_secret_file, reject_postgres_url_on_command_line
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


def _fetch_paginated(
    base_url: str,
    item_key: str,
    description: str,
    token: str,
    per_page: int,
    max_pages: int,
    allow_partial: bool,
) -> List[Dict]:
    """Page through one GitHub list endpoint, collecting *item_key* from each response.

    Shared pagination loop for :func:`fetch_runs`/:func:`fetch_run_jobs`: both
    walk ``per_page``-sized pages of *base_url* up to *max_pages*, stopping
    early on a short page (the API's signal that no more data follows) and
    otherwise raising :class:`RuntimeError` once the cap is hit while a full
    page is still coming back — silently returning at that point would hand
    the caller a truncated list that looks complete.

    *allow_partial* opts out of that guarantee for a caller that deliberately
    wants only a bounded, most-recent window rather than the full history
    (see :func:`poll_and_store`) — such a caller sets a small *max_pages* on
    purpose and a "more data exists beyond the cap" condition is exactly what
    it expects, not an error. *description* names the resource being fetched
    for both the URLError-wrapping message and the exhausted-cap message.
    """
    items: List[Dict] = []
    page = 1
    while page <= max_pages:
        url = "%s?per_page=%d&page=%d" % (base_url, per_page, page)
        try:
            payload = _get_json(url, token)
        except urllib.error.URLError as exc:
            raise RuntimeError("failed to fetch %s (page %d): %s" % (description, page, exc)) from exc
        batch = payload.get(item_key, [])
        items.extend(batch)
        if len(batch) < per_page:
            return items
        page += 1
    if allow_partial:
        return items
    raise RuntimeError(
        "%s exceeded max_pages=%d (%d per page); "
        "raise max_pages to fetch the full list" % (description, max_pages, per_page)
    )


def fetch_runs(
    repo: str,
    token: str,
    per_page: int = PER_PAGE,
    max_pages: int = MAX_PAGES,
    allow_partial: bool = False,
) -> List[Dict]:
    """Fetch recent workflow runs for *repo* (``owner/name``), paged.

    See :func:`_fetch_paginated` for the raise/*allow_partial* contract.

    Least-privilege note: *token* should be a read-only Actions-scoped
    credential, never logged or embedded in a returned error message.
    """
    base_url = "%s/repos/%s/actions/runs" % (GITHUB_API_BASE, repo)
    return _fetch_paginated(
        base_url, "workflow_runs", "workflow runs for %s" % repo, token, per_page, max_pages, allow_partial,
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

    See :func:`_fetch_paginated` for the raise/*allow_partial* contract.
    """
    base_url = "%s/repos/%s/actions/runs/%s/jobs" % (GITHUB_API_BASE, repo, run_id)
    return _fetch_paginated(
        base_url, "jobs", "jobs for run %s" % run_id, token, per_page, max_pages, allow_partial,
    )


DEFAULT_POLL_MAX_RUNS = 100
DEFAULT_POLL_RUN_MAX_PAGES = 2

NeedsResolver = Callable[[Dict, Dict], Optional[List[str]]]


def poll_and_store(
    repo: str,
    token: str,
    store: FleetStore,
    max_runs: Optional[int] = DEFAULT_POLL_MAX_RUNS,
    run_per_page: int = PER_PAGE,
    run_max_pages: int = DEFAULT_POLL_RUN_MAX_PAGES,
    job_per_page: int = PER_PAGE,
    job_max_pages: int = MAX_PAGES,
    resolve_needs: Optional[NeedsResolver] = None,
) -> int:
    """One poll cycle: fetch recent runs and their jobs for *repo*, compute
    job metrics, and upsert every job/step into *store*.

    This is the entry point that actually populates ``job_event``/
    ``job_step`` — :func:`fetch_runs`, :func:`fetch_run_jobs`, and
    :func:`compute_job_metrics` are the lower-level pieces it composes, and
    remain independently callable for a caller that wants to interpose (e.g.
    supply a parsed ``needs`` workflow graph for a precise ``queue_wait``).

    The workflow's dependency graph (``needs:``) is not available from the
    job API this function calls. *resolve_needs*, when supplied, is called
    as ``resolve_needs(run, job)`` for every job and must return that job's
    ``needs:`` list (an empty list for a verified entry point) or ``None``
    when the graph is not known for that particular job — the same
    "unknown means ``None``, never guessed" contract
    :func:`compute_job_metrics` already documents. This is how a caller that
    *does* have reliable dependency-graph knowledge (for example, one that
    has parsed the run's workflow file and can map jobs to their YAML keys
    for a non-matrix workflow) populates ``is_entry_point``/
    ``queue_wait_seconds`` for the production poll path. Without a resolver
    (the default), every job here is persisted with ``needs=None`` exactly
    as before — ``is_entry_point``/``queue_wait_seconds`` stay unset and
    only the always-honest ``pre_start_latency_seconds`` is populated.

    This module deliberately does not attempt that mapping itself: the
    workflow-jobs API's ``name`` field is the job's ``name:`` override or
    matrix-expanded label, not its YAML key, so guessing the mapping for a
    matrix-heavy workflow risks attributing the wrong dependency list to a
    job — silently wrong data, which is worse than an honest ``NULL``.

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

    Every ``job_event``/``job_step`` upsert for the whole cycle is wrapped in
    a single :meth:`tools.fleet.store.FleetStore.transaction`, so a run
    covering hundreds or thousands of jobs/steps commits (and fsyncs) once
    instead of once per row, and a concurrent reader of *store* never
    observes a poll cycle that is only partially written — it sees either
    the previous cycle's rows or this cycle's rows in full. That transaction
    is opened only around the upserts, after every run's jobs have already
    been fetched: opening it earlier would hold sqlite's write lock for the
    duration of the GitHub requests (including any rate-limit sleep in
    :func:`_get_json`), blocking a concurrent collector or poller writer on
    network latency instead of on the brief span the local upserts take.

    Returns the number of job_event rows upserted.
    """
    runs = fetch_runs(repo, token, per_page=run_per_page, max_pages=run_max_pages, allow_partial=True)
    if max_runs is not None:
        runs = runs[:max_runs]

    fetched: List[Tuple[Dict, str, Dict]] = []
    for run in runs:
        run_id = str(run.get("id"))
        for job in fetch_run_jobs(repo, run_id, token, per_page=job_per_page, max_pages=job_max_pages):
            fetched.append((run, run_id, job))

    jobs_stored = 0
    with store.transaction():
        for run, run_id, job in fetched:
            job_id = str(job.get("id"))
            needs = resolve_needs(run, job) if resolve_needs is not None else None
            metrics = compute_job_metrics(job, needs=needs)
            # Sorted so the same label set always serializes identically
            # regardless of the order the API happens to return it in —
            # `job_event` is grouped by this string (see
            # `FleetStore.pre_start_latency_by_label`), and an unsorted
            # encoding would split one label set into separate buckets
            # across polls. JSON-encoded rather than comma-joined: a raw
            # comma join is not a lossless representation of a label set —
            # `["a,b", "c"]` and `["a", "b,c"]` would both serialize to the
            # same string and then be merged by `pre_start_latency_by_label`
            # even though they are distinct sets. Any caller filtering
            # `pre_start_latency_by_label(labels=...)` on an exact set must
            # encode it the same way (`json.dumps(sorted(label_list))`).
            #
            # These are the *executing runner's* labels (the workflow-jobs
            # API's own `labels` field), not the job's requested `runs-on:`
            # set — a runner can carry extra/custom labels beyond what a job
            # asked for. Resolving the requested set would require parsing
            # the run's workflow YAML, which this module does not do (see
            # `FleetStore.upsert_job_event`/`pre_start_latency_by_label`).
            labels = sorted(job.get("labels") or [])
            store.upsert_job_event(
                job_id=job_id,
                run_id=run_id,
                repo=repo,
                name=job.get("name") or "",
                labels=json.dumps(labels),
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


# ---- scheduled entry point ------------------------------------------------

DEFAULT_POLL_INTERVAL_SECONDS = 600


def run_poll_loop(
    repo: str,
    token: str,
    store_url: str,
    interval_seconds: int = DEFAULT_POLL_INTERVAL_SECONDS,
    iterations: Optional[int] = None,
    max_runs: Optional[int] = DEFAULT_POLL_MAX_RUNS,
    open_store=FleetStore.from_url,
    poll=None,
) -> int:
    """Run :func:`poll_and_store` every *interval_seconds*, *iterations* times or forever.

    The scheduled counterpart of :func:`tools.fleet.collector.run_sampling_loop`,
    with the same stance on the store: opened lazily, closed and reopened
    after any failure, because the central database restarts whenever the
    controller stack is redeployed and a poller that died with it would
    leave a gap. A GitHub-side failure (an HTTP error that outlasted the
    rate-limit retries, a network error) is reported on stderr and the
    cycle is skipped; every poll re-reads the same recent window, so a
    skipped cycle costs latency, not data.

    *poll* and *open_store* exist so a test can drive the loop without a
    network or a database. Returns the number of cycles that stored jobs.
    """
    poll = poll or poll_and_store
    store: Optional[FleetStore] = None
    count = 0
    stored_cycles = 0
    while iterations is None or count < iterations:
        if store is None:
            try:
                store = open_store(store_url)
                store.init_schema()
            except Exception as exc:  # noqa: BLE001 — any failure means "not this cycle"
                print("fleet poller: store unavailable (%s); skipping this cycle" % exc, file=sys.stderr)
                if store is not None:
                    try:
                        store.close()
                    except Exception:  # noqa: BLE001 — the connection is already gone
                        pass
                store = None
        if store is not None:
            try:
                jobs = poll(repo, token, store, max_runs=max_runs)
                print("fleet poller: stored %d jobs for %s" % (jobs, repo), file=sys.stderr)
                stored_cycles += 1
            except Exception as exc:  # noqa: BLE001 — the loop must outlive one bad cycle
                print("fleet poller: cycle failed (%s); reconnecting next cycle" % exc, file=sys.stderr)
                try:
                    store.close()
                except Exception:  # noqa: BLE001 — the connection is already gone
                    pass
                store = None
        count += 1
        if iterations is None or count < iterations:
            time.sleep(interval_seconds)
    if store is not None:
        store.close()
    return stored_cycles


def build_parser() -> argparse.ArgumentParser:
    """Build the argument parser for running this module as a service."""
    parser = argparse.ArgumentParser(
        prog="tools.fleet.github_poller",
        description="Poll GitHub Actions runs/jobs for a repository on an interval and upsert them into the fleet store.",
    )
    parser.add_argument("--repo", required=True, help="Repository to poll, as owner/name.")
    parser.add_argument(
        "--token-file", required=True,
        help="File (mode 600) holding a read-only GitHub token; the token never appears on a command line.",
    )
    parser.add_argument(
        "--store-url", default=None,
        help="Store to write to: sqlite:///path, or a bare sqlite file path. A postgresql://... URL "
             "is rejected here — use --store-url-file instead, so the credential it carries never "
             "appears on this process's command line.",
    )
    parser.add_argument(
        "--store-url-file", default=None,
        help="Read --store-url from this file (mode 600); the only way to point the poller at the "
             "central Postgres store, so the database credential never appears on a command line.",
    )
    parser.add_argument(
        "--interval-seconds", type=int, default=DEFAULT_POLL_INTERVAL_SECONDS,
        help="Seconds between polls (default: %d)." % DEFAULT_POLL_INTERVAL_SECONDS,
    )
    parser.add_argument(
        "--max-runs", type=int, default=DEFAULT_POLL_MAX_RUNS,
        help="Most recent runs to re-read each cycle (default: %d)." % DEFAULT_POLL_MAX_RUNS,
    )
    parser.add_argument("--once", action="store_true", help="Poll once and exit, instead of looping.")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    """Parse *argv* and run :func:`run_poll_loop` (or a single poll with ``--once``)."""
    args = build_parser().parse_args(argv)
    if not args.store_url and not args.store_url_file:
        build_parser().error("one of --store-url or --store-url-file is required")
    token = read_secret_file(args.token_file)
    store_url = args.store_url
    if args.store_url_file:
        store_url = read_secret_file(args.store_url_file)
    else:
        reject_postgres_url_on_command_line(store_url, "--store-url")
    stored = run_poll_loop(
        args.repo, token, store_url,
        interval_seconds=args.interval_seconds,
        iterations=1 if args.once else None,
        max_runs=args.max_runs,
    )
    if args.once and stored == 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
