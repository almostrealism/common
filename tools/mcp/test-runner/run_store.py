#!/usr/bin/env python3
"""The on-disk record of test runs.

Every run owns a directory holding its ``metadata.json``, its captured
``output.txt``, and its copied surefire ``reports/``. That directory outlives
the process that created it, which is the whole point: an agent can start a run,
lose its context, and still ask what happened. This module is the only thing
that knows the layout — reading and writing metadata, serving output back with
the truncation the MCP responses need, retiring old runs, and marking runs the
parent process abandoned.

Nothing here starts or watches a process; that belongs to the runner.
"""

import json
import re
import shutil
from datetime import datetime
from pathlib import Path
from typing import Optional

# Default number of output lines returned when the caller asks for no limit.
# Enough to see a stack trace in context without flooding a response.
DEFAULT_OUTPUT_LINES = 200


def _elide(lines: list, max_lines: int) -> list:
    """Return at most max_lines, keeping the head and tail around a marker.

    Both ends carry signal — the command and early failures at the top, the
    summary and exit status at the bottom — so a middle elision loses the least.
    """
    if len(lines) <= max_lines:
        return lines

    head = max_lines // 2
    tail = max_lines - head
    return (
        lines[:head] +
        [f"\n... ({len(lines) - max_lines} lines truncated) ...\n\n"] +
        lines[-tail:]
    )


class RunStore:
    """The directory holding every run's record."""

    def __init__(self, directory: Path):
        self.directory = Path(directory)

    def run_dir(self, run_id: str) -> Path:
        """Return the directory holding one run's record."""
        return self.directory / run_id

    def metadata_path(self, run_id: str) -> Path:
        """Return the path of one run's metadata file."""
        return self.run_dir(run_id) / "metadata.json"

    def load(self, run_id: str) -> Optional[dict]:
        """Return a run's stored metadata, or None if it cannot be read."""
        path = self.metadata_path(run_id)
        if not path.exists():
            return None
        try:
            with open(path) as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError):
            return None

    def save(self, run_id: str, metadata: dict) -> None:
        """Write a run's metadata."""
        with open(self.metadata_path(run_id), "w") as f:
            json.dump(metadata, f, indent=2)

    def run_ids(self) -> list:
        """Return the identifiers of every stored run."""
        if not self.directory.exists():
            return []
        try:
            return [d.name for d in self.directory.iterdir() if d.is_dir()]
        except OSError:
            return []

    def cleanup(self, max_runs: int) -> None:
        """Remove the oldest runs while at or above ``max_runs`` are stored."""
        if not self.directory.exists():
            return

        runs = []
        for run_id in self.run_ids():
            metadata = self.load(run_id)
            runs.append((self.run_dir(run_id),
                         metadata.get("started_at", "") if metadata else ""))

        runs.sort(key=lambda entry: entry[1])

        while len(runs) >= max_runs:
            oldest, _ = runs.pop(0)
            try:
                shutil.rmtree(oldest)
            except Exception:
                pass

    def output(self, run_id: str, tail: Optional[int] = None,
               filter_pattern: Optional[str] = None,
               max_lines: Optional[int] = None) -> Optional[dict]:
        """Return a run's captured output.

        Args:
            run_id: The run identifier
            tail: Only return last N lines (overrides max_lines)
            filter_pattern: Regex pattern to filter lines
            max_lines: Max lines to return (default: DEFAULT_OUTPUT_LINES)
                       Set to 0 for unlimited (not recommended)
        """
        output_file = self.run_dir(run_id) / "output.txt"
        if not output_file.exists():
            return None

        with open(output_file) as f:
            lines = f.readlines()

        total_lines = len(lines)

        if filter_pattern:
            try:
                pattern = re.compile(filter_pattern)
                lines = [line for line in lines if pattern.search(line)]
            except re.error:
                pass

        filtered_lines = len(lines)
        limit = DEFAULT_OUTPUT_LINES if max_lines is None else max_lines

        if tail and len(lines) > tail:
            # An explicit tail wins: the caller asked for the end specifically.
            shown = lines[-tail:]
        elif limit > 0:
            shown = _elide(lines, limit)
        else:
            shown = lines

        return {
            "run_id": run_id,
            "output": "".join(shown),
            "truncated": len(shown) != filtered_lines,
            "total_lines": total_lines,
            "filtered_lines": filtered_lines if filter_pattern else None
        }

    def abandon_running(self) -> list:
        """Mark every unfinished run ``abandoned`` and return their IDs.

        Used as an atexit safety net: when the python parent (ar-test-runner)
        is shutting down, any run still in ``status="running"`` is marked
        ``abandoned`` so that the next inspector can distinguish "actually in
        progress" from "stranded by parent death". This is a complement to
        the detached watcher subprocess (which infers terminal status from
        output.txt); the watcher catches the case where maven completes after
        the parent dies, while this handler catches the inverse case where
        maven is still mid-run when the parent dies.
        """
        abandoned = []

        for run_id in self.run_ids():
            metadata = self.load(run_id)
            if not metadata or metadata.get("status") not in ("running", "pending"):
                continue

            metadata["status"] = "abandoned"
            metadata["abandoned_at"] = datetime.now().isoformat()
            metadata["abandoned_reason"] = (
                "ar-test-runner process exited while this run was still in progress")
            try:
                self.save(run_id, metadata)
                abandoned.append(run_id)
            except OSError:
                pass

        return abandoned
