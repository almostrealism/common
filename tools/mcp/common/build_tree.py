#!/usr/bin/env python3
"""Which Maven runs are currently using a project's build tree.

Every MCP server that invokes Maven writes into the same ``target/``
directories, and several of them read that tree back while they work — the
policy checks walk it looking for compiled classes and surefire reports. Two
such runs overlapping is not slow, it is *wrong*: one deletes and rewrites a
directory the other is part-way through walking.

The failure that follows is worse than a crash, because it looks like a
result. A validation overlapping a test run exits non-zero with a
``NoSuchFileException`` somewhere under ``target/`` and no violations parsed,
which reads as "the check failed" when it means "the check never ran".

The servers record what they start, so they can also see what each other has
started. This module is the shared reading of those records: it answers which
runs are still using a project's build tree, and recognises the wreckage when
an overlap happened anyway — a Maven invocation from a shell leaves no record
here, so detection can never be complete.
"""

import json
import os
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

# tools/mcp/common/build_tree.py -> the repository root
REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent

# Where each server records the runs it starts, by the tool name an agent
# knows it as. A server whose directory is absent simply contributes nothing.
RUN_DIRECTORIES = {
    "ar-test-runner": REPO_ROOT / "tools" / "mcp" / "test-runner" / "runs",
    "ar-build-validator": REPO_ROOT / "tools" / "mcp" / "build-validator" / "runs",
}

# Statuses a run carries while it may still be using the tree.
UNFINISHED_STATUSES = ("running", "pending")

# Assumed run length when a record does not carry its own timeout.
DEFAULT_RUN_MINUTES = 30

# Allowed beyond a run's own timeout before its record is treated as stale.
# A run whose parent died leaves "running" behind forever; without this a
# single crash would block every later run.
STALE_GRACE_MINUTES = 5

# How a scan reports the file it expected to still be there.
LOST_FILE_MARKERS = (
    "NoSuchFileException",
    "FileSystemException",
    "DirectoryNotEmptyException",
)


@dataclass(frozen=True)
class MavenRun:
    """A run one of the servers started and has not recorded finishing."""

    tool: str
    run_id: str
    started_at: str
    project_root: str
    summary: str

    def describe(self) -> str:
        """Return a one-line description naming the run and what it is doing."""
        detail = f" ({self.summary})" if self.summary else ""
        return f"{self.tool} run {self.run_id}{detail}, started {self.started_at}"


def _process_alive(pid: Optional[int]) -> bool:
    """Return whether a process id belongs to a live process."""
    if not pid:
        return False

    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        # The process exists, it just is not ours to signal.
        return True
    except OSError:
        return False
    return True


def _deadline(metadata: dict, started: datetime) -> datetime:
    """Return the moment after which a record with no live process is stale."""
    config = metadata.get("config") or {}
    minutes = config.get("timeout_minutes") or DEFAULT_RUN_MINUTES
    try:
        minutes = int(minutes)
    except (TypeError, ValueError):
        minutes = DEFAULT_RUN_MINUTES
    return started + timedelta(minutes=minutes + STALE_GRACE_MINUTES)


def _summarize(metadata: dict) -> str:
    """Return a short description of what a run is doing, from its config."""
    config = metadata.get("config") or {}

    module = config.get("module")
    if module:
        classes = config.get("test_classes") or []
        if classes:
            shown = ", ".join(classes[:3])
            if len(classes) > 3:
                shown += f", +{len(classes) - 3} more"
            return f"{module}: {shown}"
        group = config.get("test_group")
        if group is not None:
            return f"{module} group {group}"
        return str(module)

    checks = config.get("checks") or []
    if checks:
        return ", ".join(checks)

    return ""


def _project_root_of(metadata: dict) -> Path:
    """Return the project a run is building.

    Only the test runner can target another checkout, and it records that as a
    ``project`` in its config; everything else builds the repository the
    servers live in.
    """
    project = (metadata.get("config") or {}).get("project") or ""
    if not project:
        return REPO_ROOT

    path = Path(project)
    if not path.is_absolute():
        path = REPO_ROOT / path

    try:
        return path.resolve()
    except OSError:
        return path


def read_run(tool: str, run_dir: Path, now: Optional[datetime] = None) -> Optional[MavenRun]:
    """Return the run recorded in a directory if it is still using the tree.

    Returns None for a finished run, an unreadable record, or a record left
    behind by a process that is gone and whose time budget has passed.
    """
    now = now or datetime.now()
    metadata_path = run_dir / "metadata.json"

    try:
        with open(metadata_path) as f:
            metadata = json.load(f)
    except (OSError, ValueError):
        return None

    if metadata.get("status") not in UNFINISHED_STATUSES:
        return None
    if metadata.get("completed_at"):
        return None

    try:
        started = datetime.fromisoformat(metadata["started_at"])
    except (KeyError, TypeError, ValueError):
        return None

    # A live Maven process is proof on its own. Between one check and the next
    # a server holds no process, so a record still inside its time budget also
    # counts — the next check is about to start.
    if not _process_alive(metadata.get("pid")) and now > _deadline(metadata, started):
        return None

    return MavenRun(
        tool=tool,
        run_id=metadata.get("run_id", run_dir.name),
        started_at=metadata.get("started_at", ""),
        project_root=str(_project_root_of(metadata)),
        summary=_summarize(metadata),
    )


def in_flight(project_root=None,
              run_directories: Optional[dict] = None,
              now: Optional[datetime] = None) -> list:
    """Return the runs still using a project's build tree, newest first.

    :param project_root: restrict to runs building this project; None means
        the repository the servers live in.
    :param run_directories: where to look, defaulting to every known server.
    """
    directories = RUN_DIRECTORIES if run_directories is None else run_directories
    wanted = str(Path(project_root).resolve() if project_root else REPO_ROOT)

    found = []
    for tool, directory in directories.items():
        directory = Path(directory)
        if not directory.is_dir():
            continue

        for run_dir in directory.iterdir():
            if not run_dir.is_dir():
                continue
            run = read_run(tool, run_dir, now=now)
            if run is not None and run.project_root == wanted:
                found.append(run)

    found.sort(key=lambda r: r.started_at, reverse=True)
    return found


def conflict_message(runs: list, starting: str, project_root=None) -> str:
    """Return the explanation for refusing to start alongside these runs."""
    listed = "\n".join(f"  - {run.describe()}" for run in runs)
    where = str(Path(project_root).resolve() if project_root else REPO_ROOT)
    waits = sorted({run.tool for run in runs})
    how = " or ".join(
        "get_run_status" if tool == "ar-test-runner" else "get_validation_status"
        for tool in waits)

    return (
        f"{starting} did not start: another Maven run is still using the same "
        f"build tree.\n{listed}\n\n"
        f"Both build {where}, so they write and read the same target/ directories. "
        "An overlapping run does not merely slow things down — one deletes and "
        "rewrites a directory the other is walking, and the result that comes back "
        "describes neither. A validation caught this way reports a failed check "
        "with zero violations, which reads like a real finding.\n\n"
        f"Wait for it to finish ({how} with block=true), or cancel it, then start again."
    )


def race_diagnosis(output: str) -> Optional[str]:
    """Return an explanation when output shows a check that lost its build tree.

    Recognises the wreckage of an overlap this module could not prevent — a
    Maven invocation from a shell, or one already running before the servers
    began checking. Returns None when nothing in the output points that way.

    The signal is one line reporting a file that vanished from a build
    directory, which is why the path has to appear alongside the failure rather
    than merely somewhere in the same output: every Maven run mentions
    ``target`` constantly. "No source directories found" is deliberately not a
    signal — the policy detector logs it on runs that go on to pass.
    """
    if not output:
        return None

    lost_file = any(
        any(marker in line for marker in LOST_FILE_MARKERS) and "target" in line
        for line in output.splitlines()
    )

    if not lost_file:
        return None

    return (
        "This check did not reach a verdict: it lost files under target/ while it was "
        "scanning, which happens when another Maven run rewrites the tree underneath it. "
        "Any counts above describe an interrupted scan, not the state of the code. "
        "Re-run this check with nothing else building the same project."
    )
