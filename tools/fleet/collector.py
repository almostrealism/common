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
the owning user per process, computes the runner/agent/other split from
:mod:`attribution`, and — unlike that script — also samples the host-level
counters the ``host_sample`` table declares (CPU%, memory, disk).

Kept local-JSONL-first, matching the existing monitor's fallback-friendly
design: :func:`build_record` is a pure function over already-collected text
(testable without a live host), and :func:`sample_and_write` is the thin I/O
wrapper that actually shells out. Pushing batches to a remote ingest endpoint
is not implemented here — it needs a running store to push to, which this
module does not assume; this module's own JSONL output is already useful on
its own before any such store exists.

Runnable directly as ``python -m tools.fleet.collector --log-dir <dir>``
(see :func:`main`/:func:`build_parser`): :func:`run_sampling_loop` is the
scheduled entry point, meant to be the body of a launchd/systemd service, the
same role ``ar-host-monitor.sh``'s own sampling loop plays for the existing
shell-based monitor. It also owns this module's log rotation
(:func:`cleanup_old_jsonl`), mirroring that script's ``MONITOR_RETENTION_DAYS``
cleanup so the JSONL fallback directory stays bounded on a host that is
disconnected from the ingest endpoint for a long time.

``thermal_c``/``throttled`` are never populated: reading them on macOS
requires ``powermetrics`` under ``sudo``, and whether the collector may run
with root is an operator decision that has not been made. CPU/memory/disk are
unaffected by that decision and are always sampled.

Only ``comm`` basenames ever appear in a record — never full argv: this
module calls :mod:`attribution`, which enforces that at parse time.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

from tools.fleet import attribution
from tools.fleet.credentials import read_secret_file, reject_postgres_url_on_command_line
from tools.fleet.store import FleetStore


class StoreWriteError(RuntimeError):
    """A sample was collected and written to the local JSONL fallback, but writing it to the
    central store failed. Raised only for a failure inside the store write itself, never for a
    failure collecting the sample or writing the JSONL line, so :func:`run_sampling_loop` can
    tell "the store connection needs to be reopened" apart from "something is actually broken"
    and let the latter propagate instead of masking it as a transient store outage.
    """


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
    cpu_pct: Optional[float] = None,
    mem_used_mb: Optional[float] = None,
    mem_total_mb: Optional[float] = None,
    disk_used_gb: Optional[float] = None,
    disk_total_gb: Optional[float] = None,
) -> Dict:
    """Build one sample record from already-captured ``ps`` output.

    Pure with respect to the host: given the same arguments this always
    produces the same record, so it is exercised directly in tests without
    shelling out. *agent_root_pids* is how a caller that discovered the
    agent's real root PID by some other means (see
    :func:`parse_launchctl_list`) identifies it, since neither launcher gives
    it a distinctive ``comm`` (see :mod:`attribution`). *cpu_pct* through
    *disk_total_gb* are the ``host_sample`` counters (see :mod:`schema`),
    collected independently of the per-process ``ps`` walk — a caller
    without a live host (a test) simply omits them and gets ``None`` back for
    each, rather than this function trying to derive them from *ps_text*.
    ``thermal_c``/``throttled`` are intentionally absent; see the module
    docstring.
    """
    samples = attribution.parse_ps_output(ps_text)
    classes = attribution.classify_processes(samples, runner_root_comms, agent_root_comms, agent_root_pids)
    metrics = attribution.class_metrics(samples, classes)
    return {
        "ts": ts,
        "host": host,
        "load": [load1, load5, load15],
        "host_metrics": {
            "cpu_pct": cpu_pct,
            "mem_used_mb": mem_used_mb,
            "mem_total_mb": mem_total_mb,
            "disk_used_gb": disk_used_gb,
            "disk_total_gb": disk_total_gb,
            "thermal_c": None,
            "throttled": None,
        },
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
    """Append *record* as one JSON line to *path*, matching the existing monitor's JSONL format."""
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True))
        handle.write("\n")


def daily_jsonl_path(log_dir: str, ts: Optional[datetime] = None) -> str:
    """Return the date-stamped JSONL path for *ts* (default: now, UTC) under *log_dir*.

    Matches ``ar-host-monitor.sh``'s ``${MONITOR_LOG_DIR}/${CURRENT_DATE}.jsonl``
    naming (``tools/ci/monitor/ar-host-monitor.sh``): one file per UTC day, so
    that :func:`cleanup_old_jsonl`'s file-age check can delete a whole day's
    samples at once instead of needing to rewrite a single ever-growing file.
    """
    date = (ts or datetime.now(timezone.utc)).strftime("%Y-%m-%d")
    return os.path.join(log_dir, "%s.jsonl" % date)


def cleanup_old_jsonl(log_dir: str, retention_days: int) -> None:
    """Delete ``*.jsonl`` files under *log_dir* older than *retention_days*.

    Mirrors ``ar-host-monitor.sh``'s ``cleanup_old_logs`` (``find
    "${MONITOR_LOG_DIR}" -name "*.jsonl" -mtime "+${MONITOR_RETENTION_DAYS}"
    -delete``): the JSONL fallback file this module writes is meant to be a
    bounded local cache, not an unbounded append log, so a host left
    disconnected from the ingest endpoint cannot grow this directory without
    bound. Age is judged by file mtime, not the date encoded in the filename,
    so a manually renamed or copied-in file is still subject to cleanup.
    """
    cutoff = time.time() - retention_days * 86400.0
    try:
        entries = os.listdir(log_dir)
    except OSError:
        return
    for name in entries:
        if not name.endswith(".jsonl"):
            continue
        path = os.path.join(log_dir, name)
        try:
            if os.path.getmtime(path) < cutoff:
                os.remove(path)
        except OSError:
            continue


def _run_ps() -> str:
    """Run the process-table snapshot ``ps`` call, tolerating its failure.

    Mirrors :func:`_run_uptime_loads`: the existing shell-based monitor
    treats a failed ``ps`` snapshot as empty output rather than a fatal
    error, and :func:`sample_and_write`/:func:`run_sampling_loop` are not
    prepared to catch an exception from here — letting one transient
    ``ps`` failure escape would kill the long-running sampling loop instead
    of writing a degraded (zero-process) sample and continuing.
    """
    try:
        result = subprocess.run(
            ["ps", "-eo", "pid,ppid,user,pcpu,rss,comm"],
            capture_output=True,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return ""
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


def parse_proc_stat_cpu_line(text: str) -> Optional[Tuple[int, int]]:
    """Parse the aggregate ``cpu`` line of Linux ``/proc/stat``.

    Returns ``(busy_jiffies, total_jiffies)`` for that single snapshot.
    ``/proc/stat`` reports cumulative jiffies since boot, not a percentage —
    a caller subtracts two snapshots taken apart in time (see
    :func:`cpu_pct_from_proc_stat_samples`) to get a rate. ``idle`` is
    ``idle + iowait`` (the ``iowait`` field is absent on very old kernels,
    hence the length check), matching how ``top``/``mpstat`` define "busy".

    The trailing ``guest``/``guest_nice`` fields (indices 8 and 9, present on
    kernels new enough to track virtualised guest time) are excluded from
    *total*: the kernel already folds guest time into ``user``/``nice``
    respectively, so summing all ten fields double-counts it. On a host
    running VMs that would inflate *total* without inflating *busy* by the
    same amount, understating ``cpu_pct``.
    """
    for line in text.splitlines():
        if line.startswith("cpu "):
            fields = [int(v) for v in line.split()[1:]]
            if len(fields) < 4:
                return None
            idle = fields[3] + (fields[4] if len(fields) > 4 else 0)
            total = sum(fields[:8]) if len(fields) >= 8 else sum(fields)
            return total - idle, total
    return None


def cpu_pct_from_proc_stat_samples(
    first: Optional[Tuple[int, int]], second: Optional[Tuple[int, int]]
) -> Optional[float]:
    """Compute a CPU busy percentage from two ``/proc/stat`` snapshots."""
    if first is None or second is None:
        return None
    busy1, total1 = first
    busy2, total2 = second
    delta_total = total2 - total1
    if delta_total <= 0:
        return None
    return 100.0 * (busy2 - busy1) / delta_total


def _read_proc_stat_cpu() -> Optional[Tuple[int, int]]:
    try:
        with open("/proc/stat", "r", encoding="utf-8") as handle:
            return parse_proc_stat_cpu_line(handle.read())
    except OSError:
        return None


def _linux_cpu_pct(sample_interval: float = 0.1) -> Optional[float]:
    first = _read_proc_stat_cpu()
    if first is None:
        return None
    time.sleep(sample_interval)
    second = _read_proc_stat_cpu()
    return cpu_pct_from_proc_stat_samples(first, second)


def parse_macos_top_cpu_line(text: str) -> Optional[float]:
    """Parse the ``CPU usage: X% user, Y% sys, Z% idle`` line from
    ``top -l 1 -n 0`` on macOS.

    Unlike ``/proc/stat``'s cumulative counters, ``top`` on macOS computes
    this over its own internal sampling window and reports a percentage
    directly from a single invocation — no second sample is needed.
    """
    for line in text.splitlines():
        if "CPU usage" in line:
            match = re.search(r"([\d.]+)%\s*user.*?([\d.]+)%\s*sys", line)
            if match:
                return float(match.group(1)) + float(match.group(2))
    return None


def _macos_cpu_pct() -> Optional[float]:
    try:
        result = subprocess.run(
            ["top", "-l", "1", "-n", "0"], capture_output=True, text=True, check=True, timeout=10,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return None
    return parse_macos_top_cpu_line(result.stdout)


def collect_host_cpu_pct(sample_interval: float = 0.1) -> Optional[float]:
    """Sample host CPU busy percentage, dispatching by platform.

    Returns ``None`` on a platform this module does not know how to sample
    (rather than guessing) so a caller can distinguish "not available here"
    from a genuine ``0.0``.
    """
    system = platform.system()
    if system == "Linux":
        return _linux_cpu_pct(sample_interval)
    if system == "Darwin":
        return _macos_cpu_pct()
    return None


def parse_proc_meminfo(text: str) -> Tuple[Optional[float], Optional[float]]:
    """Parse Linux ``/proc/meminfo`` into ``(mem_used_mb, mem_total_mb)``.

    Uses ``MemAvailable`` (an estimate of memory available for a new
    application without swapping, per the kernel's own documentation) rather
    than ``MemFree`` — ``MemFree`` alone ignores reclaimable buffers/cache
    and would overstate "used".
    """
    total_kb = None
    avail_kb = None
    for line in text.splitlines():
        if line.startswith("MemTotal:"):
            total_kb = float(line.split()[1])
        elif line.startswith("MemAvailable:"):
            avail_kb = float(line.split()[1])
    if total_kb is None or avail_kb is None:
        return None, None
    return (total_kb - avail_kb) / 1024.0, total_kb / 1024.0


def _linux_memory_mb() -> Tuple[Optional[float], Optional[float]]:
    try:
        with open("/proc/meminfo", "r", encoding="utf-8") as handle:
            return parse_proc_meminfo(handle.read())
    except OSError:
        return None, None


# `vm_stat` line labels that count toward "reclaimable" memory: pages the
# kernel can hand back to a new allocation without swapping. Mirrors why
# `_linux_memory_mb` uses `MemAvailable` rather than `MemFree` above — on
# macOS, "Pages free" alone is a poor proxy for headroom because the kernel
# deliberately keeps recently-used file pages "inactive" (and read-ahead
# pages "speculative") rather than freeing them immediately. Counting only
# "Pages free" would report a healthy host as almost entirely out of memory.
#
# "Pages purgeable" is deliberately excluded here: unlike free/inactive/
# speculative, it is not a disjoint LRU queue but an attribute the kernel
# tracks on pages that already live in one of those queues (memory an app
# marked volatile via `vm_purgable_control`). Adding it to those queues'
# counts would double-count the same physical pages and could make
# `mem_used_mb` artificially low, or even negative.
_MACOS_VM_STAT_RECLAIMABLE_LABELS = ("Pages free", "Pages inactive", "Pages speculative")


def parse_macos_vm_stat(text: str) -> Optional[Tuple[int, int]]:
    """Parse macOS ``vm_stat`` output into ``(page_size_bytes, reclaimable_pages)``.

    The page size is embedded in the header (``Mach Virtual Memory
    Statistics: (page size of 4096 bytes)``) rather than assumed, since it is
    not guaranteed to be 4096 on every Mac. ``reclaimable_pages`` sums every
    label in :data:`_MACOS_VM_STAT_RECLAIMABLE_LABELS` that is present
    (missing labels contribute 0, so a minimal ``vm_stat`` snapshot carrying
    only ``Pages free`` still parses). ``Pages purgeable`` is intentionally
    not one of those labels — it overlaps the active/inactive/speculative
    queues rather than adding to them. Returns ``None`` if either the header
    or the ``Pages free`` line itself is missing.
    """
    header_match = re.search(r"page size of (\d+) bytes", text)
    page_size = int(header_match.group(1)) if header_match else None
    counts: Dict[str, int] = {}
    for line in text.splitlines():
        stripped = line.strip()
        for label in _MACOS_VM_STAT_RECLAIMABLE_LABELS:
            if stripped.startswith(label + ":"):
                digits = stripped.split(":", 1)[1].strip().rstrip(".")
                if digits.isdigit():
                    counts[label] = int(digits)
                break
    if page_size is None or "Pages free" not in counts:
        return None
    reclaimable_pages = sum(counts.get(label, 0) for label in _MACOS_VM_STAT_RECLAIMABLE_LABELS)
    return page_size, reclaimable_pages


def _macos_memory_mb() -> Tuple[Optional[float], Optional[float]]:
    try:
        mem_result = subprocess.run(
            ["sysctl", "-n", "hw.memsize"], capture_output=True, text=True, check=True, timeout=10,
        )
        vm_result = subprocess.run(["vm_stat"], capture_output=True, text=True, check=True, timeout=10)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return None, None
    try:
        total_bytes = int(mem_result.stdout.strip())
    except ValueError:
        return None, None
    total_mb = total_bytes / (1024.0 * 1024.0)
    parsed = parse_macos_vm_stat(vm_result.stdout)
    if parsed is None:
        return None, total_mb
    page_size, reclaimable_pages = parsed
    return (total_bytes - page_size * reclaimable_pages) / (1024.0 * 1024.0), total_mb


def collect_host_memory_mb() -> Tuple[Optional[float], Optional[float]]:
    """Sample ``(mem_used_mb, mem_total_mb)``, dispatching by platform."""
    system = platform.system()
    if system == "Linux":
        return _linux_memory_mb()
    if system == "Darwin":
        return _macos_memory_mb()
    return None, None


def collect_disk_usage(path: str = "/") -> Tuple[Optional[float], Optional[float]]:
    """Sample ``(disk_used_gb, disk_total_gb)`` for the filesystem holding *path*.

    Backed by :func:`shutil.disk_usage`, which is cross-platform in the
    standard library — no subprocess or platform dispatch needed.
    """
    try:
        usage = shutil.disk_usage(path)
    except OSError:
        return None, None
    gb = 1024.0 ** 3
    return usage.used / gb, usage.total / gb


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


_LAUNCHCTL_PRINT_PID_RE = re.compile(r"^pid\s*=\s*(\d+)$")


def parse_launchctl_print(text: str) -> Optional[int]:
    """Find the PID reported by ``launchctl print <target>`` output.

    A running service's output contains a line of the form ``\\tpid = 1234``;
    a registered-but-stopped or unrecognised target has no such line. Matched
    with an anchored regex, not a ``startswith("pid")`` prefix check, so an
    unrelated field that happens to start with ``pid`` (e.g. a hypothetical
    ``pid count = ...``) is never mistaken for the PID itself.
    """
    for line in text.splitlines():
        match = _LAUNCHCTL_PRINT_PID_RE.match(line.strip())
        if match:
            return int(match.group(1))
    return None


def discover_macos_agent_pid(
    label: str = "com.almostrealism.flowtree-agent",
    domain_target: Optional[str] = None,
) -> Optional[int]:
    """Discover the native macOS agent's PID via ``launchctl``, if present.

    Returns ``None`` on any failure (not macOS, ``launchctl`` missing, the
    service not registered or not currently running) rather than raising —
    this is a best-effort enrichment, not a requirement for sampling to work.

    Plain ``launchctl list`` only ever reports jobs in the *caller's own*
    launchd domain. The design requires the collector to run under a
    separate OS identity from the agent it is enriching data for wherever
    that isolation is available (so a fork-PR job on the same host cannot
    read the collector's push token); under that separation, ``launchctl
    list`` run as the collector's identity would never see the agent's job
    at all, and its subtree would be silently misclassified as ``other``.
    *domain_target* (e.g. ``"gui/501"`` or ``"user/501"``, the agent's
    launchd domain) addresses that job explicitly via ``launchctl print
    <domain_target>/<label>`` instead, which any identity permitted to query
    launchd may do. Omit it when the collector and the agent share an
    identity, in which case plain ``launchctl list`` already sees the job.
    """
    try:
        if domain_target:
            result = subprocess.run(
                ["launchctl", "print", "%s/%s" % (domain_target, label)],
                capture_output=True, text=True, check=True,
            )
            return parse_launchctl_print(result.stdout)
        result = subprocess.run(["launchctl", "list"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return parse_launchctl_list(result.stdout, label)


DEFAULT_PROC_CPU_THRESHOLD = 5.0
DEFAULT_PROC_RSS_THRESHOLD_MB = 100.0


def filter_procs(
    record: Dict,
    cpu_threshold: float = DEFAULT_PROC_CPU_THRESHOLD,
    rss_threshold_mb: float = DEFAULT_PROC_RSS_THRESHOLD_MB,
) -> Dict:
    """Drop the uninteresting ``other`` processes from a record's ``procs`` list.

    :func:`build_record` classifies and totals every process on the host —
    it has to, or ``class_totals`` would be wrong — but writing every one
    of them to the JSONL fallback is what turns a sample into ~100 KB on a
    busy host (hundreds of idle system processes), or hundreds of MB a day.
    The shell monitor this collector generalises keeps only processes above
    a CPU or memory threshold; this does the same, keeping every process
    already attributed to ``runner`` or ``agent`` (the ones the attribution
    exists to show) and any ``other`` process above either threshold.
    ``class_totals`` and ``host_metrics`` are left exactly as computed, so
    the filter changes what the fallback file lists, never what it counts.
    """
    kept = [
        p for p in record.get("procs", [])
        if p.get("class") != attribution.OTHER
        or (p.get("cpu") or 0.0) >= cpu_threshold
        or (p.get("rss_mb") or 0.0) >= rss_threshold_mb
    ]
    filtered = dict(record)
    filtered["procs"] = kept
    return filtered


def store_record(store: FleetStore, record: Dict) -> None:
    """Upsert one record's ``host_sample`` and ``class_sample`` rows into *store*.

    One transaction per sample, so a reader never sees a host row without
    its class rows. The per-process list is not stored — the store holds
    the per-host and per-class series the capacity question needs; the
    process detail stays in the local JSONL.
    """
    metrics = record["host_metrics"]
    load1, load5, load15 = record.get("load") or (None, None, None)
    with store.transaction():
        store.upsert_host_sample(
            record["ts"], record["host"],
            cpu_pct=metrics.get("cpu_pct"),
            mem_used_mb=metrics.get("mem_used_mb"),
            mem_total_mb=metrics.get("mem_total_mb"),
            disk_used_gb=metrics.get("disk_used_gb"),
            disk_total_gb=metrics.get("disk_total_gb"),
            load1=load1, load5=load5, load15=load15,
            thermal_c=metrics.get("thermal_c"),
            throttled=metrics.get("throttled"),
        )
        for cls, totals in record["class_totals"].items():
            store.upsert_class_sample(record["ts"], record["host"], cls, totals["cpu_pct"], totals["rss_mb"])


def sample_and_write(
    host: str,
    jsonl_path: str,
    agent_domain_target: Optional[str] = None,
    disk_path: str = "/",
    store: Optional[FleetStore] = None,
    cpu_threshold: float = DEFAULT_PROC_CPU_THRESHOLD,
    rss_threshold_mb: float = DEFAULT_PROC_RSS_THRESHOLD_MB,
) -> Dict:
    """Take one live sample, append it to the local JSONL file, and write it to *store* if given.

    *agent_domain_target* is forwarded to :func:`discover_macos_agent_pid` —
    set it to the agent's launchd domain (``"system"`` for the LaunchDaemon
    install.sh registers, or ``"gui/501"`` for a per-user LaunchAgent) when
    the collector runs under a separate identity from the agent (see that
    function's docstring); leave it ``None`` when they share an identity.

    *disk_path* is forwarded to :func:`collect_disk_usage`. The design
    defines disk capacity on the runner work volume, not necessarily the root
    filesystem — a host with a separate work disk must pass that mount point
    explicitly, or disk utilization is reported for the wrong filesystem.

    The JSONL line is written first and unconditionally: it is the fallback
    the design requires, and a store that is unreachable must not cost the
    sample. Only a failure inside the store write itself is raised as
    :class:`StoreWriteError`, which is what :func:`run_sampling_loop` catches
    to decide whether to reconnect — a failure collecting the sample (before
    the JSONL write) propagates as whatever it actually was, so a genuine bug
    there is not misreported as "store unavailable" and does not trigger a
    pointless close/reopen of an otherwise healthy store connection.
    """
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    ps_text = _run_ps()
    load1, load5, load15 = _run_uptime_loads()
    agent_pid = discover_macos_agent_pid(domain_target=agent_domain_target)
    agent_root_pids = frozenset() if agent_pid is None else frozenset({agent_pid})
    cpu_pct = collect_host_cpu_pct()
    mem_used_mb, mem_total_mb = collect_host_memory_mb()
    disk_used_gb, disk_total_gb = collect_disk_usage(disk_path)
    record = build_record(
        ts, host, ps_text, load1, load5, load15,
        agent_root_pids=agent_root_pids,
        cpu_pct=cpu_pct,
        mem_used_mb=mem_used_mb,
        mem_total_mb=mem_total_mb,
        disk_used_gb=disk_used_gb,
        disk_total_gb=disk_total_gb,
    )
    write_jsonl(filter_procs(record, cpu_threshold, rss_threshold_mb), jsonl_path)
    if store is not None:
        try:
            store_record(store, record)
        except Exception as exc:
            raise StoreWriteError(str(exc)) from exc
    return record


DEFAULT_INTERVAL_SECONDS = 15
DEFAULT_RETENTION_DAYS = 14


def run_sampling_loop(
    host: str,
    log_dir: str,
    interval_seconds: int = DEFAULT_INTERVAL_SECONDS,
    retention_days: int = DEFAULT_RETENTION_DAYS,
    agent_domain_target: Optional[str] = None,
    disk_path: str = "/",
    iterations: Optional[int] = None,
    store_url: Optional[str] = None,
    cpu_threshold: float = DEFAULT_PROC_CPU_THRESHOLD,
    rss_threshold_mb: float = DEFAULT_PROC_RSS_THRESHOLD_MB,
    open_store=FleetStore.from_url,
) -> None:
    """Sample forever (or *iterations* times), one call to :func:`sample_and_write` per cycle.

    This is the scheduled entry point ``sample_and_write`` itself was missing:
    without it, nothing in this repository ever calls ``sample_and_write`` in
    production and the collector cannot generate samples once deployed. Meant
    to run as the body of a launchd/systemd service (see the design's
    ``tools/ci/monitor``-alike deployment convention) — this function owns the
    sleep loop so the service unit only needs to keep one process alive, the
    same role ``ar-host-monitor.sh``'s own ``while ... do ... sleep`` loop
    plays for the existing shell-based monitor.

    Each cycle writes to :func:`daily_jsonl_path`'s file for the current UTC
    date, and runs :func:`cleanup_old_jsonl` whenever that date changes, so
    the on-disk fallback log is naturally bounded to *retention_days* worth of
    files exactly as ``ar-host-monitor.sh``'s ``cleanup_old_logs`` bounds the
    shell monitor's own log directory.

    *iterations* bounds the loop to a fixed number of samples instead of
    running forever - used by tests, and by a caller that wants to run this
    under an external scheduler (e.g. a systemd timer or cron entry) that
    itself invokes one short-lived process per sample rather than keeping a
    long-running service alive.

    *store_url*, when given, is where each sample is also written
    (:func:`store_record`; see :meth:`tools.fleet.store.FleetStore.from_url`
    for the forms). The store is opened lazily and reopened after any
    failure: the central database restarts whenever the controller stack is
    redeployed, and a collector that died — or stopped sampling — every time
    that happened would leave holes in exactly the data the redeploy is
    meant to be observed through. A failed write is reported on stderr, the
    connection is dropped, the JSONL line (already written) is the record of
    that sample, and the next cycle tries to connect again. Samples that
    could not be stored are not replayed from the JSONL; the fallback file is
    for an operator to consult, not a queue. Only :class:`StoreWriteError` —
    a failure inside the store write itself — triggers that reconnect; any
    other exception from :func:`sample_and_write` (a collection or JSONL
    bug) propagates and ends the loop, since it is not something reopening
    the store connection would fix.
    """
    last_log_date: Optional[str] = None
    count = 0
    store: Optional[FleetStore] = None
    while iterations is None or count < iterations:
        now = datetime.now(timezone.utc)
        current_date = now.strftime("%Y-%m-%d")
        if current_date != last_log_date:
            cleanup_old_jsonl(log_dir, retention_days)
            last_log_date = current_date
        if store_url is not None and store is None:
            try:
                store = open_store(store_url)
                store.init_schema()
            except Exception as exc:  # noqa: BLE001 — any failure means "not this cycle"
                print("fleet collector: store unavailable (%s); sampling to JSONL only" % exc, file=sys.stderr)
                if store is not None:
                    try:
                        store.close()
                    except Exception:  # noqa: BLE001 — the connection is already gone
                        pass
                store = None
        try:
            sample_and_write(
                host, daily_jsonl_path(log_dir, now),
                agent_domain_target=agent_domain_target, disk_path=disk_path, store=store,
                cpu_threshold=cpu_threshold, rss_threshold_mb=rss_threshold_mb,
            )
        except StoreWriteError as exc:
            print("fleet collector: store write failed (%s); reconnecting next cycle" % exc, file=sys.stderr)
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


def build_parser() -> argparse.ArgumentParser:
    """Build the argument parser for running this module as a service."""
    parser = argparse.ArgumentParser(
        prog="tools.fleet.collector",
        description="Sample host/attribution metrics on an interval and append them to a local JSONL log.",
    )
    parser.add_argument("--host", default=platform.node(), help="Host label to record (default: platform.node()).")
    parser.add_argument("--log-dir", required=True, help="Directory for the date-stamped JSONL fallback files.")
    parser.add_argument(
        "--interval-seconds", type=int, default=DEFAULT_INTERVAL_SECONDS,
        help="Seconds between samples (default: %d)." % DEFAULT_INTERVAL_SECONDS,
    )
    parser.add_argument(
        "--retention-days", type=int, default=DEFAULT_RETENTION_DAYS,
        help="Delete JSONL files older than this many days (default: %d)." % DEFAULT_RETENTION_DAYS,
    )
    parser.add_argument(
        "--agent-domain-target", default=None,
        help="launchd domain (e.g. 'gui/501') to query for the agent's PID; omit if collector and agent share an identity.",
    )
    parser.add_argument("--disk-path", default="/", help="Filesystem path to sample disk usage for (default: /).")
    parser.add_argument("--once", action="store_true", help="Take a single sample and exit, instead of looping.")
    parser.add_argument(
        "--store-url", default=None,
        help="Also write each sample to this store: sqlite:///path, or a bare sqlite file path. "
             "A postgresql://... URL is rejected here — use --store-url-file instead, so the "
             "credential it carries never appears on this process's command line.",
    )
    parser.add_argument(
        "--store-url-file", default=None,
        help="Read --store-url from this file (mode 600); the only way to point the collector at "
             "the central Postgres store, so the database credential never appears on a command line.",
    )
    parser.add_argument(
        "--proc-cpu-threshold", type=float, default=DEFAULT_PROC_CPU_THRESHOLD,
        help="Keep an 'other' process in the JSONL only above this CPU%% (default: %.1f)." % DEFAULT_PROC_CPU_THRESHOLD,
    )
    parser.add_argument(
        "--proc-rss-threshold-mb", type=float, default=DEFAULT_PROC_RSS_THRESHOLD_MB,
        help="Keep an 'other' process in the JSONL only above this RSS in MB (default: %.0f)." % DEFAULT_PROC_RSS_THRESHOLD_MB,
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    """Parse *argv* and run :func:`run_sampling_loop` (or a single sample with ``--once``)."""
    args = build_parser().parse_args(argv)
    os.makedirs(args.log_dir, exist_ok=True)
    store_url = args.store_url
    if args.store_url_file:
        store_url = read_secret_file(args.store_url_file)
    else:
        reject_postgres_url_on_command_line(store_url, "--store-url")
    # The store and the thresholds are passed only when the operator set
    # them, so a plain JSONL invocation reaches run_sampling_loop exactly as
    # it always did and takes that function's own defaults.
    optional: Dict = {}
    if store_url is not None:
        optional["store_url"] = store_url
    if args.proc_cpu_threshold != DEFAULT_PROC_CPU_THRESHOLD:
        optional["cpu_threshold"] = args.proc_cpu_threshold
    if args.proc_rss_threshold_mb != DEFAULT_PROC_RSS_THRESHOLD_MB:
        optional["rss_threshold_mb"] = args.proc_rss_threshold_mb
    run_sampling_loop(
        args.host,
        args.log_dir,
        interval_seconds=args.interval_seconds,
        retention_days=args.retention_days,
        agent_domain_target=args.agent_domain_target,
        disk_path=args.disk_path,
        iterations=1 if args.once else None,
        **optional,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
