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
"""The store for runner-fleet-monitoring data, on sqlite3 or Postgres.

One implementation serves both backends. The schema (:mod:`schema`) is
portable SQL, every write is an ``INSERT ... ON CONFLICT (key) DO UPDATE``
upsert — which sqlite and Postgres spell identically — and the only things
that differ between the two are the connection, the parameter placeholder
(``?`` against sqlite, ``%s`` against Postgres) and the type of the ``ts``
columns. Those three are the whole of :class:`Dialect`, so a query written
once runs against either.

sqlite (:meth:`FleetStore.sqlite`, or the plain constructor) is the
standard library and always available: it is what the tests use and what a
single host can use on its own. Postgres (:meth:`FleetStore.postgres`) is
the central store the design puts on the controller host, reached over the
tailnet by every collector and by Grafana; it needs the ``psycopg`` package,
imported only when a Postgres store is actually opened so nothing else in
this module (or the ``python-tests`` CI job, which installs nothing beyond
``tools/mcp/requirements.txt``) depends on it. :meth:`FleetStore.from_url`
picks the backend from a URL, which is how the collector and poller are
pointed at either.

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
from typing import Any, Iterator, List, Optional, Sequence, Tuple

from tools.fleet import schema


class Dialect:
    """What differs between the two backends: placeholder and ``ts`` type."""

    SQLITE = "sqlite"
    POSTGRES = "postgres"

    def __init__(self, name: str):
        if name not in (self.SQLITE, self.POSTGRES):
            raise ValueError("unknown dialect %r" % name)
        self.name = name

    @property
    def placeholder(self) -> str:
        """The parameter marker the driver expects."""
        return "?" if self.name == self.SQLITE else "%s"

    @property
    def timestamp_type(self) -> str:
        """The column type for the ``ts``-style columns.

        sqlite has no timestamp type, so ISO-8601 text sorts and compares
        correctly there. Postgres gets a real ``TIMESTAMPTZ`` so Grafana's
        time-series queries and any rollup can treat the column as time
        without a cast; the same ISO-8601 strings are what the producers
        write, and Postgres converts them on insert.
        """
        return "TEXT" if self.name == self.SQLITE else "TIMESTAMPTZ"

    def sql(self, text: str) -> str:
        """Rewrite a ``?``-parameterised statement for this backend."""
        if self.name == self.SQLITE:
            return text
        return text.replace("?", "%s")


class FleetStore:
    """The fleet schema on a DB-API connection, sqlite3 or Postgres."""

    def __init__(self, path: str = ":memory:", dialect: Optional[Dialect] = None, connection: Any = None):
        """Open a sqlite store at *path* (the historical, test-friendly form).

        The two-argument form with an explicit *dialect* and *connection* is
        what :meth:`sqlite` and :meth:`postgres` use; callers should go
        through those or :meth:`from_url` rather than passing a connection
        here directly.
        """
        if connection is None:
            dialect = Dialect(Dialect.SQLITE)
            connection = sqlite3.connect(path)
            connection.execute("PRAGMA foreign_keys = ON")
        self._dialect = dialect or Dialect(Dialect.SQLITE)
        self._conn = connection
        self._batch_depth = 0

    # ---- construction ------------------------------------------------------

    @classmethod
    def sqlite(cls, path: str = ":memory:") -> "FleetStore":
        """Open (creating if needed) a sqlite store at *path*."""
        return cls(path)

    @classmethod
    def postgres(cls, dsn: str) -> "FleetStore":
        """Open a Postgres store at *dsn* (a ``postgresql://`` URL or libpq string).

        The connection runs in autocommit mode so that transaction control
        is explicit and identical to the sqlite path — ``BEGIN``,
        ``SAVEPOINT`` and ``COMMIT`` are issued as statements by
        :meth:`transaction`, never implicitly by the driver.
        """
        try:
            import psycopg  # noqa: F401 — optional dependency, needed only here
        except ImportError as exc:
            raise RuntimeError(
                "a Postgres fleet store needs the 'psycopg' package "
                "(pip install 'psycopg[binary]'); sqlite needs nothing"
            ) from exc
        connection = psycopg.connect(dsn, autocommit=True)
        return cls(dialect=Dialect(Dialect.POSTGRES), connection=connection)

    @classmethod
    def from_url(cls, url: str) -> "FleetStore":
        """Open a store from a URL: ``sqlite:///path`` or ``postgresql://...``.

        ``sqlite:///relative/or/absolute.db`` and ``sqlite:///:memory:``
        open sqlite; anything starting with ``postgres://`` or
        ``postgresql://`` opens Postgres. A bare path with no scheme is
        treated as a sqlite file, so existing ``--db fleet.db`` style
        arguments keep working.

        An empty (or whitespace-only) *url* is rejected rather than falling
        through to the bare-path case: ``sqlite3.connect("")`` opens a
        temporary, on-disk-but-unnamed database that silently disappears on
        close, so a blank credential file or environment variable would make
        every write look successful while ingesting nothing into the actual
        store.
        """
        if not url or not url.strip():
            raise ValueError("empty store URL (use sqlite:///path or postgresql://...)")
        if url.startswith("sqlite:///"):
            return cls.sqlite(url[len("sqlite:///"):])
        if url.startswith(("postgresql://", "postgres://")):
            return cls.postgres(url)
        if "://" in url:
            # The scheme alone is reported, never the full *url*: an
            # unrecognised scheme is exactly the shape a mistyped Postgres
            # DSN takes (e.g. "mysql://user:secret@host/db"), and this
            # message reaches stderr via the collector/poller's own
            # exception logging - interpolating the whole URL there would
            # leak its credential into service logs.
            scheme = url.split("://", 1)[0]
            raise ValueError(
                "unsupported store URL scheme %r (use sqlite:///path or postgresql://...)" % scheme
            )
        return cls.sqlite(url)

    @property
    def dialect(self) -> Dialect:
        """The backend this store is talking to."""
        return self._dialect

    def close(self) -> None:
        """Close the underlying connection."""
        self._conn.close()

    def __enter__(self) -> "FleetStore":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()

    # ---- statement execution -------------------------------------------------

    def _execute(self, sql: str, params: Sequence = ()) -> Any:
        """Run one ``?``-parameterised statement, rewritten for the backend."""
        return self._conn.execute(self._dialect.sql(sql), tuple(params))

    def _rows(self, sql: str, params: Sequence = ()) -> List[Tuple]:
        return [tuple(row) for row in self._execute(sql, params)]

    def _commit(self) -> None:
        if self._dialect.name == Dialect.SQLITE:
            self._conn.commit()
        else:
            self._conn.execute("COMMIT")

    def _rollback(self) -> None:
        if self._dialect.name == Dialect.SQLITE:
            self._conn.rollback()
        else:
            self._conn.execute("ROLLBACK")

    @contextlib.contextmanager
    def transaction(self) -> Iterator["FleetStore"]:
        """Batch every ``upsert_*`` call made inside this block into one commit.

        While a batch is open, the individual ``upsert_*`` methods below
        skip their own commit; the whole block commits once on successful
        exit, or rolls back entirely if an exception propagates out of it.
        This turns a poll cycle's per-row commit/fsync into a single one and
        makes the cycle atomic to any concurrent reader of the same store —
        it either sees the previous cycle's rows or the new cycle's rows in
        full, never a mix of the two. Nesting is supported (only the
        outermost block commits) so a helper that already opens its own
        ``transaction()`` composes safely with a caller that wraps it in
        another. A nested block's failure is scoped to a ``SAVEPOINT``, not
        the whole connection: rolling it back undoes only the writes made
        inside that nested block, leaving writes made earlier in an
        enclosing block intact. Without this, a caller that catches the
        nested block's exception and keeps going would silently commit a
        partial cycle at the outermost exit — a plain connection-wide
        rollback would have erased the earlier writes too, with nothing
        left to signal that the eventual commit no longer covers the whole
        cycle.
        """
        depth = self._batch_depth
        if depth == 0:
            self._conn.execute("BEGIN")
        else:
            self._conn.execute("SAVEPOINT fleet_sp_%d" % depth)
        self._batch_depth = depth + 1
        try:
            yield self
        except BaseException:
            if depth == 0:
                self._rollback()
            else:
                self._conn.execute("ROLLBACK TO SAVEPOINT fleet_sp_%d" % depth)
                self._conn.execute("RELEASE SAVEPOINT fleet_sp_%d" % depth)
            self._batch_depth = depth
            raise
        else:
            if depth == 0:
                self._commit()
            else:
                self._conn.execute("RELEASE SAVEPOINT fleet_sp_%d" % depth)
            self._batch_depth = depth

    def _commit_unless_batched(self) -> None:
        """Commit immediately, unless a :meth:`transaction` batch is open.

        On Postgres the connection is in autocommit mode, so outside a batch
        every statement has already committed and there is nothing to do.
        """
        if self._batch_depth == 0 and self._dialect.name == Dialect.SQLITE:
            self._conn.commit()

    def _upsert(self, table: str, key: Sequence[str], columns: Sequence[str], values: Sequence) -> None:
        """``INSERT ... ON CONFLICT (key) DO UPDATE`` on *table*.

        *columns* lists every column being written, key columns included;
        the non-key columns are what the conflict branch updates from the
        proposed row (``excluded``). Both backends accept this form.
        """
        updates = [c for c in columns if c not in key]
        sql = "INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO %s" % (
            table,
            ", ".join(columns),
            ", ".join("?" for _ in columns),
            ", ".join(key),
            "UPDATE SET " + ", ".join("%s = excluded.%s" % (c, c) for c in updates) if updates else "NOTHING",
        )
        self._execute(sql, values)
        self._commit_unless_batched()

    def init_schema(self) -> None:
        """Create every table (and index) declared in :mod:`schema`, if not already present."""
        for statement in schema.statements(self._dialect.timestamp_type):
            self._conn.execute(statement)
        if self._dialect.name == Dialect.SQLITE:
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
        self._upsert(
            "host_sample", ("ts", "host"),
            ("ts", "host", "cpu_pct", "mem_used_mb", "mem_total_mb", "disk_used_gb",
             "disk_total_gb", "load1", "load5", "load15", "thermal_c", "throttled"),
            (
                ts, host, cpu_pct, mem_used_mb, mem_total_mb, disk_used_gb,
                disk_total_gb, load1, load5, load15, thermal_c,
                None if throttled is None else int(bool(throttled)),
            ),
        )

    def upsert_class_sample(self, ts: str, host: str, cls: str, cpu_pct: float, rss_mb: float) -> None:
        """Insert or replace one ``class_sample`` row, keyed on ``(ts, host, class)``."""
        self._upsert(
            "class_sample", ("ts", "host", "class"),
            ("ts", "host", "class", "cpu_pct", "rss_mb"),
            (ts, host, cls, cpu_pct, rss_mb),
        )

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
        self._upsert(
            "runner_state", ("ts", "host", "runner_name"),
            ("ts", "host", "runner_name", "labels", "state", "repo", "workflow", "job_id", "agent_version"),
            (ts, host, runner_name, labels, state, repo, workflow, job_id, agent_version),
        )

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
        self._upsert(
            "job_event", ("job_id",),
            ("job_id", "run_id", "repo", "name", "labels", "created_at", "started_at",
             "completed_at", "status", "conclusion", "runner_name", "runner_group",
             "pre_start_latency_seconds", "is_entry_point", "queue_wait_seconds"),
            (
                job_id, run_id, repo, name, labels, created_at, started_at,
                completed_at, status, conclusion, runner_name, runner_group,
                pre_start_latency_seconds,
                None if is_entry_point is None else int(bool(is_entry_point)),
                queue_wait_seconds,
            ),
        )

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
        self._upsert(
            "job_step", ("job_id", "number"),
            ("job_id", "number", "name", "started_at", "completed_at", "conclusion"),
            (job_id, number, name, started_at, completed_at, conclusion),
        )

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
            return self._rows(query.format(where="WHERE host = ?"), (host,))
        return self._rows(query.format(where=""))

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
            return self._rows(query.format(where="WHERE host = ?"), (host,))
        return self._rows(query.format(where=""))

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
            return self._rows(query.format(label_filter="AND labels = ?"), (labels,))
        return self._rows(query.format(label_filter=""))

    def job_event_count(self) -> int:
        """Return the total number of rows in ``job_event``."""
        return self._rows("SELECT COUNT(*) FROM job_event")[0][0]

    def job_step_count(self) -> int:
        """Return the total number of rows in ``job_step``."""
        return self._rows("SELECT COUNT(*) FROM job_step")[0][0]
