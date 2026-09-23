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
"""The runner-fleet-monitoring store schema.

Every table declares a natural key because both ingest paths are
at-least-once: a push transport retries on timeout, and a poller that
re-reads the same upstream records every cycle sees the same rows again.
Without a declared key, a retried push or a repeated poll duplicates rows and
inflates every rollup computed on top of this data.

This module writes plain, portable SQL (standard column types, no
Postgres-specific syntax) so the exact same statements apply to the
sqlite3-backed :mod:`store` used for local development and tests, as well as
to a Postgres deployment — swapping the backend is a connection change, not
a schema rewrite. The one type that differs is the timestamp columns: sqlite
stores ISO-8601 text (which sorts and compares correctly), Postgres a real
``TIMESTAMPTZ`` so Grafana and any rollup can treat them as time without a
cast. Each statement therefore carries a ``{ts}`` token that
:func:`statements` fills for the backend in use; :data:`ALL_STATEMENTS` is
the sqlite rendering, kept for callers that only ever meant sqlite.
"""

from __future__ import annotations

from typing import List

HOST_SAMPLE = """
CREATE TABLE IF NOT EXISTS host_sample (
    ts {ts} NOT NULL,
    host TEXT NOT NULL,
    cpu_pct REAL,
    mem_used_mb REAL,
    mem_total_mb REAL,
    disk_used_gb REAL,
    disk_total_gb REAL,
    load1 REAL,
    load5 REAL,
    load15 REAL,
    thermal_c REAL,
    throttled INTEGER,
    PRIMARY KEY (ts, host)
)
"""

CLASS_SAMPLE = """
CREATE TABLE IF NOT EXISTS class_sample (
    ts {ts} NOT NULL,
    host TEXT NOT NULL,
    class TEXT NOT NULL,
    cpu_pct REAL,
    rss_mb REAL,
    PRIMARY KEY (ts, host, class)
)
"""

RUNNER_STATE = """
CREATE TABLE IF NOT EXISTS runner_state (
    ts {ts} NOT NULL,
    -- The host the runner runs on, when known. The GitHub runners API does
    -- not report it, so the poller writes '' here; a collector that can see
    -- the runner's process tree may write the real hostname.
    host TEXT NOT NULL,
    runner_name TEXT NOT NULL,
    labels TEXT,
    -- Derived from `labels` (see `lane`/`platform` on job_event).
    lane TEXT,
    platform TEXT,
    -- busy / idle / offline, as the runners API reports it.
    state TEXT,
    -- For a GitHub-API-sourced row (see `github_poller.store_runner_states`),
    -- the registration scope the runner came from ('owner/repo' or
    -- 'org:name') -- part of the key alongside `runner_name` because a
    -- runner's name is unique only within one registration scope, not
    -- across scopes, and every such row shares `host=''`.
    repo TEXT NOT NULL DEFAULT '',
    workflow TEXT,
    job_id TEXT,
    agent_version TEXT,
    PRIMARY KEY (ts, host, runner_name, repo)
)
"""

JOB_EVENT = """
CREATE TABLE IF NOT EXISTS job_event (
    job_id TEXT PRIMARY KEY,
    run_id TEXT,
    repo TEXT,
    name TEXT,
    -- The labels of the runner that actually executed this job (the
    -- workflow-jobs API's own `labels` field), not the workflow's requested
    -- `runs-on:` set. A runner can carry extra/custom labels beyond what a
    -- job asked for, so grouping by this column reports actual-runner-label
    -- demand, not per-`runs-on` demand.
    labels TEXT,
    -- Two projections of `labels`, so a dashboard groups by a short, stable
    -- key instead of parsing the JSON label set in every panel. `lane` is
    -- the fleet's own `ar-*` label(s) — the kind of work a runner is for
    -- (ar-ci, ar-ci-cl, ar-deploy, ...) — and is '' for a GitHub-hosted
    -- runner, which carries none; `platform` is macos / linux / windows.
    lane TEXT,
    platform TEXT,
    created_at {ts},
    started_at {ts},
    completed_at {ts},
    status TEXT,
    conclusion TEXT,
    runner_name TEXT,
    runner_group TEXT,
    pre_start_latency_seconds REAL,
    is_entry_point INTEGER,
    queue_wait_seconds REAL
)
"""

JOB_STEP = """
CREATE TABLE IF NOT EXISTS job_step (
    job_id TEXT NOT NULL,
    number INTEGER NOT NULL,
    name TEXT,
    started_at {ts},
    completed_at {ts},
    conclusion TEXT,
    UNIQUE (job_id, number)
)
"""

# The queries the CLI and a dashboard run are "this host, this time range"
# and "these runs' jobs"; the primary keys lead with ``ts``/``job_id``, so
# these secondary indexes serve the other access path on each table.
INDEXES = [
    "CREATE INDEX IF NOT EXISTS host_sample_host_ts ON host_sample (host, ts)",
    # ts leads class: the dashboard and status queries filter by host and a
    # ts range without constraining class, so class after ts would strand the
    # range scan behind an unconstrained middle column and force a full scan
    # of every class for the matched hosts.
    "CREATE INDEX IF NOT EXISTS class_sample_host_ts ON class_sample (host, ts, class)",
    "CREATE INDEX IF NOT EXISTS runner_state_host_ts ON runner_state (host, runner_name, ts)",
    "CREATE INDEX IF NOT EXISTS job_event_run ON job_event (run_id)",
    "CREATE INDEX IF NOT EXISTS job_event_created ON job_event (created_at)",
    # The runners-per-lane panels read the newest sample per runner and
    # then filter by lane; ts leads so "latest sample" is an index walk.
    "CREATE INDEX IF NOT EXISTS runner_state_ts ON runner_state (ts)",
]

# Columns added after a table first shipped. ``CREATE TABLE IF NOT EXISTS``
# leaves an existing table untouched, so a store created before a column
# existed never acquires it from the definitions above; :meth:`FleetStore.init_schema`
# adds each of these to a table that lacks it. sqlite has no
# ``ADD COLUMN IF NOT EXISTS``, so the check is the store's, not the DDL's.
# Every entry is also present in the ``CREATE TABLE`` above, so a fresh store
# and a migrated one end up identical.
ADDED_COLUMNS: List[tuple] = [
    ("job_event", "lane", "TEXT"),
    ("job_event", "platform", "TEXT"),
    ("runner_state", "lane", "TEXT"),
    ("runner_state", "platform", "TEXT"),
]

TABLES: List[str] = [
    HOST_SAMPLE,
    CLASS_SAMPLE,
    RUNNER_STATE,
    JOB_EVENT,
    JOB_STEP,
]


def statements(timestamp_type: str = "TEXT") -> List[str]:
    """Every DDL statement, with the timestamp columns typed as *timestamp_type*.

    ``"TEXT"`` is the sqlite rendering, ``"TIMESTAMPTZ"`` the Postgres one;
    :class:`tools.fleet.store.Dialect` supplies the right value.
    """
    return [table.format(ts=timestamp_type) for table in TABLES] + list(INDEXES)


ALL_STATEMENTS: List[str] = statements("TEXT")
