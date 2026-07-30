"""
Forked surefire JVM discovery.

Maven surefire runs tests in a forked JVM whose PID is needed for jcmd/jstat
attachment (the ar-jmx tools). The fork only exists after maven finishes
dependency resolution and compilation, which on large modules takes minutes —
the previous in-server implementation polled for a fixed 30 seconds and
therefore failed on every run whose compile phase outlasted the window.
Discovery here polls for as long as the caller reports the run is still
active, so a fork that appears late is still found.

A candidate from `jps -l` is accepted only if it is a process descendant of
the maven process that owns the run, so concurrent maven invocations on the
same host never cross-match.

Extracted from server.py so the polling logic is unit-testable on its own and
the server module stays focused on run orchestration.
"""

import os
import subprocess
import time
from pathlib import Path
from typing import Callable, Optional


# Substrings of a `jps -l` line that identify a surefire test JVM: the fork
# runs either the ForkedBooter main class or a surefirebooter-*.jar.
FORK_MARKERS = ("ForkedBooter", "surefirebooter")

# Ancestry walk depth. The fork is normally a direct child of the maven
# launcher (mvn execs java, preserving the PID); the headroom covers shells
# or wrappers between them.
MAX_ANCESTRY_DEPTH = 10


def get_ppid(pid: int) -> Optional[int]:
    """Get parent PID. Uses /proc on Linux, ps on macOS.

    Args:
        pid: Process to look up.

    Returns:
        The parent PID, or None if the process does not exist or the
        lookup fails.
    """
    try:
        stat_path = Path(f"/proc/{pid}/stat")
        text = stat_path.read_text()
        close_paren = text.rfind(")")
        if close_paren == -1:
            return None
        fields = text[close_paren + 2:].split()
        if len(fields) >= 2:
            return int(fields[1])
    except (OSError, PermissionError, ValueError):
        pass

    # Fallback: ps (macOS / general Unix)
    try:
        result = subprocess.run(
            ["ps", "-o", "ppid=", "-p", str(pid)],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0 and result.stdout.strip():
            return int(result.stdout.strip())
    except (subprocess.TimeoutExpired, FileNotFoundError, ValueError):
        pass

    return None


def is_descendant_of(pid: int, ancestor_pid: int) -> bool:
    """Check if pid is a descendant of ancestor_pid by walking the parent chain.

    Args:
        pid: The candidate descendant.
        ancestor_pid: The proposed ancestor.

    Returns:
        True when ancestor_pid appears in pid's parent chain within
        MAX_ANCESTRY_DEPTH steps.
    """
    current = pid
    for _ in range(MAX_ANCESTRY_DEPTH):
        ppid = get_ppid(current)
        if ppid is None or ppid <= 1:
            return False
        if ppid == ancestor_pid:
            return True
        current = ppid
    return False


def pid_alive(pid: int) -> bool:
    """Check whether a process exists (without signalling it).

    Args:
        pid: Process to check.

    Returns:
        True when the process exists (including when owned by another user).
    """
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except OSError:
        return False


def find_forked_jvm(maven_pid: int) -> Optional[int]:
    """Scan `jps -l` once for a surefire fork descended from the maven process.

    Args:
        maven_pid: The maven process whose test fork is wanted.

    Returns:
        The fork's PID, or None if no matching descendant is currently
        visible.

    Raises:
        FileNotFoundError: If jps is not available on the PATH.
    """
    result = subprocess.run(
        ["jps", "-l"],
        capture_output=True,
        text=True,
        timeout=5
    )
    if result.returncode != 0:
        return None

    for line in result.stdout.strip().split("\n"):
        if not any(marker in line for marker in FORK_MARKERS):
            continue
        parts = line.split(None, 1)
        if not parts:
            continue
        try:
            candidate_pid = int(parts[0])
        except ValueError:
            continue
        if is_descendant_of(candidate_pid, maven_pid):
            return candidate_pid

    return None


def discover_forked_pid(maven_pid: int,
                        should_continue: Callable[[], bool],
                        poll_interval: float = 1.0) -> Optional[int]:
    """Poll for the surefire fork until it appears or the run ends.

    There is deliberately no fixed iteration cap: the fork appears only
    after maven's resolve and compile phases, whose duration varies from
    seconds to minutes by module. The caller's should_continue callback —
    typically "the run is still active and the maven process is alive" —
    bounds the loop instead.

    Args:
        maven_pid: The maven process whose test fork is wanted.
        should_continue: Returns False once polling should stop (run
            finished, cancelled, or the maven process died).
        poll_interval: Seconds between jps scans.

    Returns:
        The fork's PID, or None if the run ended first or jps is
        unavailable on this host.
    """
    while should_continue():
        try:
            pid = find_forked_jvm(maven_pid)
        except FileNotFoundError:
            # No jps on this host; the fork can never be discovered.
            return None
        except subprocess.TimeoutExpired:
            pid = None

        if pid is not None:
            return pid

        time.sleep(poll_interval)

    return None
