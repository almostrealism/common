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
to a Postgres/TimescaleDB deployment — swapping the backend is a connection
change, not a schema rewrite.
"""

from __future__ import annotations

from typing import List

HOST_SAMPLE = """
CREATE TABLE IF NOT EXISTS host_sample (
    ts TEXT NOT NULL,
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
    ts TEXT NOT NULL,
    host TEXT NOT NULL,
    class TEXT NOT NULL,
    cpu_pct REAL,
    rss_mb REAL,
    PRIMARY KEY (ts, host, class)
)
"""

RUNNER_STATE = """
CREATE TABLE IF NOT EXISTS runner_state (
    ts TEXT NOT NULL,
    host TEXT NOT NULL,
    runner_name TEXT NOT NULL,
    labels TEXT,
    state TEXT,
    repo TEXT,
    workflow TEXT,
    job_id TEXT,
    agent_version TEXT,
    PRIMARY KEY (ts, host, runner_name)
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
    created_at TEXT,
    started_at TEXT,
    completed_at TEXT,
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
    started_at TEXT,
    completed_at TEXT,
    conclusion TEXT,
    UNIQUE (job_id, number)
)
"""

ALL_STATEMENTS: List[str] = [
    HOST_SAMPLE,
    CLASS_SAMPLE,
    RUNNER_STATE,
    JOB_EVENT,
    JOB_STEP,
]
