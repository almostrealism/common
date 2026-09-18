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
"""The read-only half of the unified fleet CLI.

A CLI for this fleet is one surface with platform-specific *control*
adapters, but read-only visibility is the useful thing to have before any
control verb exists. This module implements the two read verbs that matter
first: ``list`` (runners on a host: name, labels, state, current job) and
``status`` (per-host/per-class utilization). Both read from
:class:`tools.ci.fleet.store.FleetStore`; neither touches any runner process.

Control verbs (``start``/``stop``/``restart``/``register``/``label``) are
deliberately not implemented here — they need platform-specific adapters
wired to a real, operator-supplied fleet inventory, which this module does
not assume.
"""

from __future__ import annotations

import argparse
import sys
from typing import List, Optional, Sequence

from tools.ci.fleet.store import FleetStore


def format_list(rows: Sequence[tuple]) -> str:
    """Format :meth:`FleetStore.latest_runner_states` rows for display."""
    if not rows:
        return "No runner state recorded."
    header = "%-20s %-20s %-24s %-10s %-8s %s" % ("HOST", "RUNNER", "LABELS", "STATE", "JOB", "REPO")
    lines = [header]
    for ts, host, runner_name, labels, state, repo, workflow, job_id, agent_version in rows:
        lines.append(
            "%-20s %-20s %-24s %-10s %-8s %s"
            % (host, runner_name, labels or "", state or "", job_id or "-", repo or "")
        )
    return "\n".join(lines)


def format_status(rows: Sequence[tuple]) -> str:
    """Format :meth:`FleetStore.utilization_by_class` rows for display."""
    if not rows:
        return "No utilization data recorded."
    header = "%-20s %-10s %10s %12s %8s" % ("HOST", "CLASS", "AVG_CPU%", "AVG_RSS_MB", "SAMPLES")
    lines = [header]
    for host, cls, avg_cpu, avg_rss, count in rows:
        lines.append(
            "%-20s %-10s %10.2f %12.1f %8d"
            % (host, cls, avg_cpu or 0.0, avg_rss or 0.0, count)
        )
    return "\n".join(lines)


def run_list(store: FleetStore, host: Optional[str]) -> str:
    return format_list(store.latest_runner_states(host))


def run_status(store: FleetStore, host: Optional[str]) -> str:
    return format_status(store.utilization_by_class(host))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="fleetctl",
        description="Read-only visibility into the GitHub Actions runner fleet.",
    )
    parser.add_argument("--db", default="fleet.db", help="Path to the sqlite store (default: fleet.db).")
    subparsers = parser.add_subparsers(dest="command", required=True)

    list_parser = subparsers.add_parser("list", help="List runners and their current state.")
    list_parser.add_argument("--host", default=None, help="Restrict to one host.")

    status_parser = subparsers.add_parser("status", help="Show per-host/per-class utilization.")
    status_parser.add_argument("--host", default=None, help="Restrict to one host.")

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    store = FleetStore(args.db)
    try:
        store.init_schema()
        if args.command == "list":
            print(run_list(store, args.host))
        elif args.command == "status":
            print(run_status(store, args.host))
        else:  # pragma: no cover - argparse enforces valid choices
            return 2
    finally:
        store.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
