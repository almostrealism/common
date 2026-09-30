#!/usr/bin/env python3
"""Questions the MCP servers ask about operating-system processes by PID.

Several servers track processes they did not necessarily start: the test
runner watches a Maven PID and walks the parent chain to find its surefire
fork, the build-tree check asks whether a recorded run's process survives,
and ar-jmx verifies a JVM is still running before pointing a JDK tool at it.
They share one answer to "is this PID alive" and "what is its parent" here,
so a correction to either reaches every server at once.

Only positive integers name a single process. ``kill(0, 0)`` addresses the
caller's own process group and ``kill(-1, 0)`` every process the caller may
signal, so both "succeed" without saying anything about one process; and
``jcmd 0`` addresses every JVM on the host. A non-positive PID is therefore
never reported alive.
"""

import os
import subprocess
from pathlib import Path
from typing import Optional


def pid_alive(pid: Optional[int]) -> bool:
    """Return whether ``pid`` names a single process that currently exists.

    Uses the ``kill(pid, 0)`` probe, which sends no signal. A process owned
    by another user still counts as alive.

    Args:
        pid: The process id to check; ``None`` and non-positive values are
            never alive.

    Returns:
        True when the process exists, False otherwise.
    """
    if pid is None or pid <= 0:
        return False

    try:
        os.kill(pid, 0)
    except PermissionError:
        # The process exists, it just is not ours to signal.
        return True
    except OSError:
        return False
    return True


def get_ppid(pid: int) -> Optional[int]:
    """Return the parent PID of ``pid``. Uses /proc on Linux, ps elsewhere.

    Args:
        pid: Process to look up.

    Returns:
        The parent PID, or None if the process does not exist or the
        lookup fails.
    """
    try:
        text = Path(f"/proc/{pid}/stat").read_text()
        # The command name is parenthesised and may itself contain spaces
        # or parentheses, so fields are counted from the last ")".
        close_paren = text.rfind(")")
        if close_paren == -1:
            return None
        fields = text[close_paren + 2:].split()
        if len(fields) >= 2:
            return int(fields[1])
    except (OSError, ValueError):
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
