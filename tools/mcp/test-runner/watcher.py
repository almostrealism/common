#!/usr/bin/env python3
"""External monitor process for ar-test-runner.

Spawned by ar-test-runner's `start_run` as a session-detached subprocess
(`start_new_session=True`) so it survives the parent ar-test-runner process
being reaped when claude exits cleanly.

The in-process `_watch_completion` daemon thread in `server.py` updates
metadata.json when maven exits, but that thread dies with the python parent.
This script is a backup: it polls the maven PID via `os.kill(pid, 0)`, waits
for maven to exit, and only writes terminal metadata if the in-process thread
did not already do so.

Status is inferred from `output.txt` (BUILD SUCCESS / BUILD FAILURE) since
this watcher cannot `waitpid` on a non-child PID.
"""
import json
import os
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path


# Polling interval (seconds) between liveness checks for the watched maven PID.
POLL_INTERVAL = 1.0
# Grace window in seconds during which the in-process daemon thread may write
# terminal metadata before the watcher steps in. The thread normally writes
# within milliseconds of maven exit; a 5-second grace covers the case where
# the python parent is alive but slow.
THREAD_GRACE_SECONDS = 5.0


def is_alive(pid: int) -> bool:
    """Check whether a process with the given PID is still alive.

    Uses the standard `kill 0` probe. Returns True if the process exists
    (whether or not we have permission to signal it), False if it does not.
    """
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def load_metadata(metadata_path: Path):
    """Load metadata.json, returning None if it cannot be read."""
    if not metadata_path.exists():
        return None
    try:
        with open(metadata_path) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return None


def save_metadata(metadata_path: Path, metadata: dict) -> None:
    """Atomically replace metadata.json with the provided dict."""
    tmp_path = metadata_path.with_suffix(".json.tmp")
    with open(tmp_path, "w") as f:
        json.dump(metadata, f, indent=2)
    os.replace(tmp_path, metadata_path)


def infer_exit_status(output_path: Path) -> str:
    """Infer the maven exit status by reading the tail of output.txt.

    Maven prints either `BUILD SUCCESS` or `BUILD FAILURE` near the end of
    its output. A run with neither marker is reported as `failed` so it is
    not silently treated as a pass.
    """
    if not output_path.exists():
        return "failed"
    try:
        size = output_path.stat().st_size
        read_bytes = min(size, 16384)
        with open(output_path, "rb") as f:
            if size > read_bytes:
                f.seek(-read_bytes, os.SEEK_END)
            tail = f.read().decode("utf-8", errors="replace")
        if "BUILD SUCCESS" in tail:
            return "completed"
        if "BUILD FAILURE" in tail:
            return "failed"
    except OSError:
        pass
    return "failed"


def copy_surefire_reports(project_root: Path, module: str, reports_dst: Path,
                          run_started_at: str) -> None:
    """Copy surefire TEST-*.xml files modified since the run started."""
    reports_src = project_root / module / "target" / "surefire-reports"
    if not reports_src.exists():
        return
    reports_dst.mkdir(parents=True, exist_ok=True)
    try:
        run_start = datetime.fromisoformat(run_started_at)
    except (TypeError, ValueError):
        run_start = None
    for xml_file in reports_src.glob("TEST-*.xml"):
        try:
            if run_start is not None:
                file_mtime = datetime.fromtimestamp(xml_file.stat().st_mtime)
                if file_mtime < run_start:
                    continue
            shutil.copy2(xml_file, reports_dst / xml_file.name)
        except OSError:
            pass


def _terminal(status):
    """Whether `status` is a terminal status that needs no further updates."""
    return status in ("completed", "failed", "timeout", "cancelled")


def watch(maven_pid: int, metadata_path: Path, output_path: Path,
          reports_dst: Path, project_root: Path, module: str) -> None:
    """Block until maven exits, then update metadata if no other writer did.

    The watcher bails out (writing nothing) if ``metadata.pid`` does not match
    the ``maven_pid`` it was spawned for. That mismatch indicates the run has
    been restarted with a new maven PID (e.g., the JMX-fork-failure retry path
    or a future repetitions path) and a stale watcher must not overwrite the
    new run's metadata.
    """
    while is_alive(maven_pid):
        time.sleep(POLL_INTERVAL)

    grace_deadline = time.monotonic() + THREAD_GRACE_SECONDS
    while time.monotonic() < grace_deadline:
        metadata = load_metadata(metadata_path)
        if metadata is not None and _terminal(metadata.get("status")):
            return
        time.sleep(0.5)

    metadata = load_metadata(metadata_path)
    if metadata is None or _terminal(metadata.get("status")):
        return
    if metadata.get("pid") != maven_pid:
        return

    inferred = infer_exit_status(output_path)
    metadata["completed_at"] = datetime.now().isoformat()
    metadata["status"] = inferred
    metadata["completion_source"] = "watcher_process"
    save_metadata(metadata_path, metadata)

    run_started_at = metadata.get("started_at")
    if run_started_at:
        copy_surefire_reports(project_root, module, reports_dst, run_started_at)


def main() -> int:
    """Entry point: parse args, detach session, watch the maven PID."""
    if len(sys.argv) != 7:
        sys.stderr.write(
            "usage: watcher.py <maven_pid> <metadata_path> <output_path> "
            "<reports_dst> <project_root> <module>\n")
        return 2

    maven_pid = int(sys.argv[1])
    metadata_path = Path(sys.argv[2])
    output_path = Path(sys.argv[3])
    reports_dst = Path(sys.argv[4])
    project_root = Path(sys.argv[5])
    module = sys.argv[6]

    try:
        os.setsid()
    except OSError:
        pass

    watch(maven_pid, metadata_path, output_path, reports_dst, project_root, module)
    return 0


if __name__ == "__main__":
    sys.exit(main())
