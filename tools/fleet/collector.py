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

from tools.fleet import attribution


def build_record(
    ts: str,
    host: str,
    ps_text: str,
    load1: Optional[float] = None,
    load5: Optional[float] = None,
    load15: Optional[float] = None,
    runner_root_comms=attribution.DEFAULT_RUNNER_ROOT_COMMS,
    agent_root_comms=attribution.DEFAULT_AGENT_ROOT_COMMS,
    agent_root_pids=frozenset(),
) -> Dict:
    """Build one sample record from already-captured ``ps`` output.

    Pure with respect to the host: given the same *ps_text* this always
    produces the same record, so it is exercised directly in tests without
    shelling out. *agent_root_pids* is how a caller that discovered the
    agent's real root PID by some other means (see
    :func:`parse_launchctl_list`) identifies it, since neither launcher gives
    it a distinctive ``comm`` (see :mod:`attribution`).
    """
    samples = attribution.parse_ps_output(ps_text)
    classes = attribution.classify_processes(samples, runner_root_comms, agent_root_comms, agent_root_pids)
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


def parse_uptime_loads(text: str) -> List[Optional[float]]:
    """Parse the 1/5/15-minute load averages out of ``uptime`` output.

    The three numbers are comma-separated on Linux (``load average: 0.10,
    0.05, 0.01``) but space-separated on macOS (``load averages: 1.23 1.10
    0.95``, also plural). Splitting only on commas leaves the whole
    space-separated macOS tail as one unparsable token; normalising commas to
    whitespace first handles both formats with a single split.
    """
    marker = "load average"
    idx = text.lower().find(marker)
    if idx < 0:
        return [None, None, None]
    tail = text[idx:].split(":", 1)
    if len(tail) < 2:
        return [None, None, None]
    parts = tail[1].replace(",", " ").split()
    loads: List[Optional[float]] = []
    for part in parts[:3]:
        try:
            loads.append(float(part))
        except ValueError:
            loads.append(None)
    while len(loads) < 3:
        loads.append(None)
    return loads


def _run_uptime_loads() -> List[Optional[float]]:
    try:
        result = subprocess.run(["uptime"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return [None, None, None]
    return parse_uptime_loads(result.stdout)


def parse_launchctl_list(text: str, label: str = "com.almostrealism.flowtree-agent") -> Optional[int]:
    """Find the PID of a launchd service from ``launchctl list`` output.

    ``ps`` cannot identify the native macOS FlowTree agent by ``comm`` (see
    :mod:`attribution`'s module docstring on ``DEFAULT_AGENT_ROOT_COMMS``):
    both the container entrypoint and the native launcher ``exec java``, so
    the process is indistinguishable from any other JVM by name alone.
    launchd itself knows the PID it started for a given service label, and
    ``launchctl list`` (no argument) reports every job as one
    tab-separated ``PID\\tStatus\\tLabel`` line — ``PID`` is ``-`` for a
    label that is registered but not currently running.
    """
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[2] == label and parts[0].isdigit():
            return int(parts[0])
    return None


def discover_macos_agent_pid(label: str = "com.almostrealism.flowtree-agent") -> Optional[int]:
    """Discover the native macOS agent's PID via ``launchctl``, if present.

    Returns ``None`` on any failure (not macOS, ``launchctl`` missing, the
    service not registered or not currently running) rather than raising —
    this is a best-effort enrichment, not a requirement for sampling to work.
    """
    try:
        result = subprocess.run(["launchctl", "list"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return parse_launchctl_list(result.stdout, label)


def sample_and_write(host: str, jsonl_path: str) -> Dict:
    """Take one live sample and append it to the local JSONL fallback file."""
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    ps_text = _run_ps()
    load1, load5, load15 = _run_uptime_loads()
    agent_pid = discover_macos_agent_pid()
    agent_root_pids = frozenset() if agent_pid is None else frozenset({agent_pid})
    record = build_record(
        ts, host, ps_text, load1, load5, load15, agent_root_pids=agent_root_pids
    )
    write_jsonl(record, jsonl_path)
    return record
