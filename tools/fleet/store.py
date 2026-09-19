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
"""A small, dependency-free store for runner-fleet-monitoring data.

Backed by :mod:`sqlite3` (standard library, always available — the
``python-tests`` CI job installs nothing beyond
``tools/mcp/requirements.txt`` and ``pyyaml``, so this module deliberately
avoids a Postgres client dependency). A Postgres/TimescaleDB deployment is
expected to use the exact same schema (:mod:`schema`) so query logic written
against this store transfers directly — only the connection and the upsert
syntax (``INSERT OR REPLACE`` here, ``INSERT ... ON CONFLICT ... DO UPDATE``
on Postgres) would need to change.

Every write here is an upsert on the table's declared natural key, because
both producers are at-least-once (see :mod:`schema`'s module docstring): a
plain insert would raise on the second write of the same key rather than
silently duplicating, but it would still be the wrong behaviour for a retried
push or a re-polled job, so every ``upsert_*`` method here replaces the prior
row for that key instead of erroring or accumulating duplicates.

Each ``upsert_*`` method commits on its own by default, which is the right
behaviour for a single, standalone write. A caller that performs many
upserts as one logical unit of work — the GitHub poller upserts one
``job_event`` per job plus one ``job_step`` per step, hundreds or thousands
of statements across a single poll cycle — should instead wrap them in
:meth:`FleetStore.transaction`, so the whole cycle commits (and fsyncs) once
instead of once per row, and a concurrent reader never observes a poll cycle
that is only partially written.
"""

from __future__ import annotations

import contextlib
import sqlite3
from typing import Iterator, List, Optional, Tuple

from tools.fleet import schema


class FleetStore:
    """Thin wrapper around a sqlite3 connection implementing the fleet schema."""

    def __init__(self, path: str = ":memory:"):
        self._conn = sqlite3.connect(path)
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._batch_depth = 0

    def close(self) -> None:
        """Close the underlying sqlite3 connection."""
        self._conn.close()

    def __enter__(self) -> "FleetStore":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()

    @contextlib.contextmanager
    def transaction(self) -> Iterator["FleetStore"]:
        """Batch every ``upsert_*`` call made inside this block into one commit.

        While a batch is open, the individual ``upsert_*`` methods below
        skip their own commit; the whole block commits once on successful
        exit, or rolls back entirely if an exception propagates out of it.
        This turns a poll cycle's per-row commit/fsync into a single one and
        makes the cycle atomic to any concurrent reader of the same sqlite
        file — it either sees the previous cycle's rows or the new cycle's
        rows in full, never a mix of the two. Nesting is supported (only the
        outermost block commits) so a helper that already opens its own
        ``transaction()`` composes safely with a caller that wraps it in
        another.
        """
        self._batch_depth += 1
        try:
            yield self
        except BaseException:
            self._batch_depth -= 1
            self._conn.rollback()
            raise
        else:
            self._batch_depth -= 1
            if self._batch_depth == 0:
                self._conn.commit()

    def _commit_unless_batched(self) -> None:
        """Commit immediately, unless a :meth:`transaction` batch is open."""
        if self._batch_depth == 0:
            self._conn.commit()

    def init_schema(self) -> None:
        """Create every table declared in :mod:`schema`, if not already present."""
        for statement in schema.ALL_STATEMENTS:
            self._conn.execute(statement)
        self._conn.commit()

    # ---- host_sample / class_sample -----------------------------------

    def upsert_host_sample(
        self,
        ts: str,
        host: str,
        cpu_pct: Optional[float] = None,
        mem_used_mb: Optional[float] = None,
        mem_total_mb: Optional[float] = None,
        disk_used_gb: Optional[float] = None,
        disk_total_gb: Optional[float] = None,
        load1: Optional[float] = None,
        load5: Optional[float] = None,
        load15: Optional[float] = None,
        thermal_c: Optional[float] = None,
        throttled: Optional[bool] = None,
    ) -> None:
        """Insert or replace one ``host_sample`` row, keyed on ``(ts, host)``."""
        self._conn.execute(
            """
            INSERT OR REPLACE INTO host_sample
                (ts, host, cpu_pct, mem_used_mb, mem_total_mb, disk_used_gb,
                 disk_total_gb, load1, load5, load15, thermal_c, throttled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                ts, host, cpu_pct, mem_used_mb, mem_total_mb, disk_used_gb,
                disk_total_gb, load1, load5, load15, thermal_c,
                None if throttled is None else int(bool(throttled)),
            ),
        )
        self._commit_unless_batched()

    def upsert_class_sample(self, ts: str, host: str, cls: str, cpu_pct: float, rss_mb: float) -> None:
        """Insert or replace one ``class_sample`` row, keyed on ``(ts, host, class)``."""
        self._conn.execute(
            """
            INSERT OR REPLACE INTO class_sample (ts, host, class, cpu_pct, rss_mb)
            VALUES (?, ?, ?, ?, ?)
            """,
            (ts, host, cls, cpu_pct, rss_mb),
        )
        self._commit_unless_batched()

    def upsert_class_samples(self, ts: str, host: str, metrics: dict) -> None:
        """Bulk form of :meth:`upsert_class_sample` for one sampling round.

        *metrics* maps class name to an object exposing ``cpu_pct``/``rss_mb``
        (e.g. the ``ClassMetrics`` namedtuples from :mod:`attribution`).
        """
        for cls, values in metrics.items():
            self.upsert_class_sample(ts, host, cls, values.cpu_pct, values.rss_mb)

    # ---- runner_state ----------------------------------------------------

    def upsert_runner_state(
        self,
        ts: str,
        host: str,
        runner_name: str,
        labels: str = "",
        state: str = "",
        repo: str = "",
        workflow: str = "",
        job_id: str = "",
        agent_version: str = "",
    ) -> None:
        """Insert or replace one ``runner_state`` row, keyed on ``(ts, host, runner_name)``."""
        self._conn.execute(
            """
            INSERT OR REPLACE INTO runner_state
                (ts, host, runner_name, labels, state, repo, workflow, job_id, agent_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (ts, host, runner_name, labels, state, repo, workflow, job_id, agent_version),
        )
        self._commit_unless_batched()

    # ---- job_event / job_step ---------------------------------------------

    def upsert_job_event(
        self,
        job_id: str,
        run_id: str = "",
        repo: str = "",
        name: str = "",
        labels: str = "",
        created_at: Optional[str] = None,
        started_at: Optional[str] = None,
        completed_at: Optional[str] = None,
        status: str = "",
        conclusion: str = "",
        runner_name: str = "",
        runner_group: str = "",
        pre_start_latency_seconds: Optional[float] = None,
        is_entry_point: Optional[bool] = None,
        queue_wait_seconds: Optional[float] = None,
    ) -> None:
        """Insert or replace one ``job_event`` row, keyed on ``job_id``.

        *labels* is the executing runner's actual label set (the
        workflow-jobs API's own ``labels`` field), not the workflow's
        requested ``runs-on:`` set — see :data:`tools.fleet.schema.JOB_EVENT`.
        A runner can carry extra/custom labels beyond what a job asked for,
        so a caller grouping on this column is measuring actual-runner-label
        demand, not per-``runs-on`` demand.
        """
        self._conn.execute(
            """
            INSERT OR REPLACE INTO job_event
                (job_id, run_id, repo, name, labels, created_at, started_at,
                 completed_at, status, conclusion, runner_name, runner_group,
                 pre_start_latency_seconds, is_entry_point, queue_wait_seconds)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                job_id, run_id, repo, name, labels, created_at, started_at,
                completed_at, status, conclusion, runner_name, runner_group,
                pre_start_latency_seconds,
                None if is_entry_point is None else int(bool(is_entry_point)),
                queue_wait_seconds,
            ),
        )
        self._commit_unless_batched()

    def upsert_job_step(
        self,
        job_id: str,
        number: int,
        name: str = "",
        started_at: Optional[str] = None,
        completed_at: Optional[str] = None,
        conclusion: str = "",
    ) -> None:
        """Insert or replace one ``job_step`` row, keyed on ``(job_id, number)``."""
        self._conn.execute(
            """
            INSERT OR REPLACE INTO job_step
                (job_id, number, name, started_at, completed_at, conclusion)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (job_id, number, name, started_at, completed_at, conclusion),
        )
        self._commit_unless_batched()

    # ---- queries -----------------------------------------------------------

    def latest_runner_states(self, host: Optional[str] = None) -> List[Tuple]:
        """One row per ``runner_name`` (the most recent sample), for ``list``."""
        query = """
            SELECT rs.ts, rs.host, rs.runner_name, rs.labels, rs.state,
                   rs.repo, rs.workflow, rs.job_id, rs.agent_version
            FROM runner_state rs
            JOIN (
                SELECT host, runner_name, MAX(ts) AS max_ts
                FROM runner_state
                {where}
                GROUP BY host, runner_name
            ) latest
            ON rs.host = latest.host AND rs.runner_name = latest.runner_name AND rs.ts = latest.max_ts
            ORDER BY rs.host, rs.runner_name
        """
        if host is not None:
            query = query.format(where="WHERE host = ?")
            return list(self._conn.execute(query, (host,)))
        return list(self._conn.execute(query.format(where="")))

    def utilization_by_class(self, host: Optional[str] = None) -> List[Tuple]:
        """Average CPU% per host/class across every stored sample, for ``status``."""
        query = """
            SELECT host, class, AVG(cpu_pct), AVG(rss_mb), COUNT(*)
            FROM class_sample
            {where}
            GROUP BY host, class
            ORDER BY host, class
        """
        if host is not None:
            return list(self._conn.execute(query.format(where="WHERE host = ?"), (host,)))
        return list(self._conn.execute(query.format(where="")))

    def pre_start_latency_by_label(self, labels: Optional[str] = None) -> List[Tuple]:
        """Average ``pre_start_latency_seconds`` for entry-point jobs, by label set.

        Restricted to ``is_entry_point = 1``: only for those rows does
        ``pre_start_latency`` coincide with runner-availability queue wait —
        for a job with dependencies, that raw interval also includes time
        blocked on upstream jobs. A caller wanting the dependency-adjusted
        metric for non-entry-point jobs should read ``queue_wait_seconds``
        directly instead of this aggregate.

        The count column counts non-``NULL`` ``pre_start_latency_seconds``
        rows (matching what the average is actually computed over), not
        every row in the group — a queued entry-point job has no latency yet.

        ``labels`` groups by the *executing runner's* actual label set, not
        the workflow's requested ``runs-on:`` set (see
        :meth:`upsert_job_event`) — a caller cannot yet use this to answer
        "how long did jobs requesting `runs-on: [self-hosted, gpu]` wait",
        only "how long did jobs that happened to land on a runner carrying
        this exact label set wait". This method treats ``labels`` as an
        opaque string and does no encoding/decoding itself — it matches
        whatever encoding the producer used. ``tools.fleet.github_poller``
        writes it JSON-encoded (``json.dumps(sorted(label_list))``), so a
        caller passing the *labels* filter to match those rows must encode
        it the same way rather than joining the label list with a plain
        separator.
        """
        query = """
            SELECT labels, AVG(pre_start_latency_seconds), COUNT(pre_start_latency_seconds)
            FROM job_event
            WHERE is_entry_point = 1
            {label_filter}
            GROUP BY labels
            ORDER BY labels
        """
        if labels is not None:
            return list(self._conn.execute(query.format(label_filter="AND labels = ?"), (labels,)))
        return list(self._conn.execute(query.format(label_filter="")))

    def job_event_count(self) -> int:
        """Return the total number of rows in ``job_event``."""
        return self._conn.execute("SELECT COUNT(*) FROM job_event").fetchone()[0]

    def job_step_count(self) -> int:
        """Return the total number of rows in ``job_step``."""
        return self._conn.execute("SELECT COUNT(*) FROM job_step").fetchone()[0]
