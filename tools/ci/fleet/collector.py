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
"""The host/attribution metrics agent: a generalisation of
``tools/ci/monitor/ar-host-monitor.sh`` that additionally records ``ppid`` and
the owning user per process and computes the runner/agent/other split from
:mod:`attribution`.

Kept local-JSONL-first, matching the existing monitor's fallback-friendly
design: :func:`build_record` is a pure function over already-collected text
(testable without a live host), and :func:`sample_and_write` is the thin I/O
wrapper that actually shells out. Pushing batches to a remote ingest endpoint
is not implemented here — it needs a running store to push to, which this
module does not assume; this module's own JSONL output is already useful on
its own before any such store exists.

Only ``comm`` basenames ever appear in a record — never full argv: this
module calls :mod:`attribution`, which enforces that at parse time.
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timezone
from typing import Dict, List, Optional

from tools.ci.fleet import attribution


def build_record(
    ts: str,
    host: str,
    ps_text: str,
    load1: Optional[float] = None,
    load5: Optional[float] = None,
    load15: Optional[float] = None,
    runner_root_comms=attribution.DEFAULT_RUNNER_ROOT_COMMS,
    agent_root_comms=attribution.DEFAULT_AGENT_ROOT_COMMS,
) -> Dict:
    """Build one sample record from already-captured ``ps`` output.

    Pure with respect to the host: given the same *ps_text* this always
    produces the same record, so it is exercised directly in tests without
    shelling out.
    """
    samples = attribution.parse_ps_output(ps_text)
    classes = attribution.classify_processes(samples, runner_root_comms, agent_root_comms)
    metrics = attribution.class_metrics(samples, classes)
    return {
        "ts": ts,
        "host": host,
        "load": [load1, load5, load15],
        "class_totals": {
            cls: {"cpu_pct": values.cpu_pct, "rss_mb": values.rss_mb, "process_count": values.process_count}
            for cls, values in metrics.items()
        },
        "procs": [
            {
                "pid": s.pid,
                "ppid": s.ppid,
                "user": s.user,
                "cpu": s.cpu_pct,
                "rss_mb": s.rss_mb,
                "comm": s.comm,
                "class": classes.get(s.pid, attribution.OTHER),
            }
            for s in samples
        ],
    }


def write_jsonl(record: Dict, path: str) -> None:
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True))
        handle.write("\n")


def _run_ps() -> str:
    result = subprocess.run(
        ["ps", "-eo", "pid,ppid,user,pcpu,rss,comm"],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout


def _run_uptime_loads() -> List[Optional[float]]:
    try:
        result = subprocess.run(["uptime"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return [None, None, None]
    text = result.stdout
    marker = "load average"
    idx = text.lower().find(marker)
    if idx < 0:
        return [None, None, None]
    tail = text[idx:].split(":", 1)
    if len(tail) < 2:
        return [None, None, None]
    parts = [p.strip().rstrip(",") for p in tail[1].split(",")]
    loads: List[Optional[float]] = []
    for part in parts[:3]:
        try:
            loads.append(float(part))
        except ValueError:
            loads.append(None)
    while len(loads) < 3:
        loads.append(None)
    return loads


def sample_and_write(host: str, jsonl_path: str) -> Dict:
    """Take one live sample and append it to the local JSONL fallback file."""
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    ps_text = _run_ps()
    load1, load5, load15 = _run_uptime_loads()
    record = build_record(ts, host, ps_text, load1, load5, load15)
    write_jsonl(record, jsonl_path)
    return record
