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

``thermal_c``/``throttled`` are never populated: reading them on macOS
requires ``powermetrics`` under ``sudo``, and whether the collector may run
with root is an operator decision that has not been made. CPU/memory/disk are
unaffected by that decision and are always sampled.

Only ``comm`` basenames ever appear in a record — never full argv: this
module calls :mod:`attribution`, which enforces that at parse time.
"""

from __future__ import annotations

import json
import platform
import re
import shutil
import subprocess
import time
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

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


def parse_macos_vm_stat(text: str) -> Optional[Tuple[int, int]]:
    """Parse macOS ``vm_stat`` output into ``(page_size_bytes, pages_free)``.

    The page size is embedded in the header (``Mach Virtual Memory
    Statistics: (page size of 4096 bytes)``) rather than assumed, since it is
    not guaranteed to be 4096 on every Mac. Returns ``None`` if either the
    header or the ``Pages free`` line is missing.
    """
    header_match = re.search(r"page size of (\d+) bytes", text)
    page_size = int(header_match.group(1)) if header_match else None
    pages_free = None
    for line in text.splitlines():
        if line.strip().startswith("Pages free"):
            digits = line.split(":", 1)[1].strip().rstrip(".")
            if digits.isdigit():
                pages_free = int(digits)
    if page_size is None or pages_free is None:
        return None
    return page_size, pages_free


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
    page_size, pages_free = parsed
    return (total_bytes - page_size * pages_free) / (1024.0 * 1024.0), total_mb


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


def sample_and_write(
    host: str, jsonl_path: str, agent_domain_target: Optional[str] = None, disk_path: str = "/",
) -> Dict:
    """Take one live sample and append it to the local JSONL fallback file.

    *agent_domain_target* is forwarded to :func:`discover_macos_agent_pid` —
    set it to the agent's launchd domain (e.g. ``"gui/501"``) when the
    collector runs under a separate identity from the agent (see that
    function's docstring); leave it ``None`` when they share an identity.

    *disk_path* is forwarded to :func:`collect_disk_usage`. The design
    defines disk capacity on the runner work volume, not necessarily the root
    filesystem — a host with a separate work disk must pass that mount point
    explicitly, or disk utilization is reported for the wrong filesystem.
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
    write_jsonl(record, jsonl_path)
    return record
