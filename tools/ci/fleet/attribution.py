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
"""Classify host processes into runner / agent / other, by process tree.

On a shared host (a Mac running both GitHub Actions runners and FlowTree
coding agents is the case that matters most), CPU/memory utilization only
means something if it is split by *who* is using it — a GitHub Actions
runner, a FlowTree coding agent, or everything else.

Each class is measured **directly**, not as a residual. Computing
``other = total - runner - agent`` is ill-defined: host CPU% and summed
per-process ``ps %cpu`` are not guaranteed to share a denominator or sampling
window, and summing per-process RSS double-counts shared library pages. The
fix implemented here walks the process list once, tags every PID with exactly
one class by process-tree ancestry, and sums each class's own processes
independently — ``other`` is simply "everything not tagged runner or agent",
using the same accounting basis (and the same known RSS-double-counting
caveat) as the other two classes.

Only ``comm`` basenames are ever handled here, never full argv — argv can
carry a runner registration token.
"""

from __future__ import annotations

from typing import Dict, Iterable, List, NamedTuple, Optional, Set

# Process names (comm basenames) that mark the root of a runner's subtree.
# `Runner.Worker` is the one that actually runs a job; `Runner.Listener` is
# its ephemeral-registration parent (see fleet.sh:104-109 in the ROCm fleet,
# already using this signal for busy detection).
DEFAULT_RUNNER_ROOT_COMMS = frozenset({"Runner.Listener", "Runner.Worker"})

# Process names that mark the root of a FlowTree coding-agent subtree. On
# macOS this is the launchd-managed native agent process; in the Docker pool
# it is the container's own PID 1. Neither name is authoritative on every
# platform, so callers on a host with different naming should pass their own
# set. Note that a container's process tree is not always visible to a native
# host `ps` at all (e.g. containers running inside a Docker Desktop VM on
# macOS) — that class must be attributed through the container runtime's own
# API instead of this function; passing a root name here does not help.
DEFAULT_AGENT_ROOT_COMMS = frozenset({"flowtree-agent"})

RUNNER = "runner"
AGENT = "agent"
OTHER = "other"


class ProcessSample(NamedTuple):
    """One process observed in a single ``ps`` snapshot.

    ``comm`` is always a basename (never full argv — see module docstring).
    """

    pid: int
    ppid: int
    user: str
    cpu_pct: float
    rss_mb: float
    comm: str


def parse_ps_line(line: str) -> Optional[ProcessSample]:
    """Parse one line of ``ps -eo pid,ppid,user,pcpu,rss,comm`` output.

    Returns ``None`` for blank lines or the header line. ``rss`` from ``ps``
    is in KB; converted to MB here so every sample in the pipeline uses the
    same unit. ``comm`` may itself contain a full path (BSD/GNU `ps` differ);
    only the basename is kept, matching the existing `tools/ci/monitor`
    convention.
    """
    stripped = line.strip()
    if not stripped or stripped.upper().startswith("PID"):
        return None
    parts = stripped.split(None, 5)
    if len(parts) < 6:
        return None
    pid_s, ppid_s, user, cpu_s, rss_s, comm = parts
    try:
        pid = int(pid_s)
        ppid = int(ppid_s)
        cpu_pct = float(cpu_s)
        rss_mb = float(rss_s) / 1024.0
    except ValueError:
        return None
    basename = comm.rsplit("/", 1)[-1]
    return ProcessSample(pid=pid, ppid=ppid, user=user, cpu_pct=cpu_pct, rss_mb=rss_mb, comm=basename)


def parse_ps_output(text: str) -> List[ProcessSample]:
    """Parse the full output of ``ps -eo pid,ppid,user,pcpu,rss,comm``."""
    samples = []
    for line in text.splitlines():
        parsed = parse_ps_line(line)
        if parsed is not None:
            samples.append(parsed)
    return samples


def _children_by_ppid(samples: Iterable[ProcessSample]) -> Dict[int, List[ProcessSample]]:
    children: Dict[int, List[ProcessSample]] = {}
    for sample in samples:
        children.setdefault(sample.ppid, []).append(sample)
    return children


def classify_processes(
    samples: Iterable[ProcessSample],
    runner_root_comms: Set[str] = DEFAULT_RUNNER_ROOT_COMMS,
    agent_root_comms: Set[str] = DEFAULT_AGENT_ROOT_COMMS,
) -> Dict[int, str]:
    """Classify every process into exactly one of runner / agent / other.

    Each process is tagged by walking down from every recognised class root
    (a process whose ``comm`` is in *runner_root_comms* or *agent_root_comms*)
    through its full descendant subtree via ``ppid``. A process reachable from
    more than one root keeps the class of whichever root's walk reaches it
    first (roots are walked runner-then-agent, so a runner subtree that
    happens to spawn something matching an agent root name is classified as
    runner — the outer boundary wins, since it is the more specific match).
    Every process not reached by either walk is ``other``.

    Returns ``{pid: class}`` covering every pid in *samples*.
    """
    sample_list = list(samples)
    children = _children_by_ppid(sample_list)
    classes: Dict[int, str] = {}

    def _tag_subtree(root_pid: int, cls: str) -> None:
        stack = [root_pid]
        while stack:
            pid = stack.pop()
            if pid in classes:
                continue
            classes[pid] = cls
            for child in children.get(pid, ()):
                stack.append(child.pid)

    for sample in sample_list:
        if sample.comm in runner_root_comms and sample.pid not in classes:
            _tag_subtree(sample.pid, RUNNER)
    for sample in sample_list:
        if sample.comm in agent_root_comms and sample.pid not in classes:
            _tag_subtree(sample.pid, AGENT)
    for sample in sample_list:
        classes.setdefault(sample.pid, OTHER)
    return classes


class ClassMetrics(NamedTuple):
    """Aggregated CPU/RSS for one attribution class."""

    cpu_pct: float
    rss_mb: float
    process_count: int


def class_metrics(
    samples: Iterable[ProcessSample],
    classes: Dict[int, str],
) -> Dict[str, ClassMetrics]:
    """Sum CPU%/RSS per class, directly — never as a subtraction/residual.

    Every one of ``runner``/``agent``/``other`` is always present in the
    result (zero-valued if no process fell into it), so a caller can rely on
    all three keys existing.
    """
    totals: Dict[str, List[float]] = {RUNNER: [0.0, 0.0, 0], AGENT: [0.0, 0.0, 0], OTHER: [0.0, 0.0, 0]}
    for sample in samples:
        cls = classes.get(sample.pid, OTHER)
        bucket = totals.setdefault(cls, [0.0, 0.0, 0])
        bucket[0] += sample.cpu_pct
        bucket[1] += sample.rss_mb
        bucket[2] += 1
    return {
        cls: ClassMetrics(cpu_pct=values[0], rss_mb=values[1], process_count=int(values[2]))
        for cls, values in totals.items()
    }


def classified_cpu_total(metrics: Dict[str, ClassMetrics]) -> float:
    """Sum of every class's CPU%, for comparison against a host-level counter.

    A caller with an independently measured host CPU% (from ``uptime`` /
    ``vm_stat`` style counters, not from summing ``ps`` rows) should report
    ``host_cpu_pct - classified_cpu_total(metrics)`` as its own diagnostic
    field rather than folding it into any one class — silently absorbing it
    into ``other`` would reintroduce the same ill-defined-residual problem
    this module exists to avoid.
    """
    return sum(m.cpu_pct for m in metrics.values())
