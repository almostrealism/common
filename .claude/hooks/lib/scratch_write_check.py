#!/usr/bin/env python3
"""Refuse a file written with the edit tools to a place only this session can read.

The person an agent works for is usually not the account the agent runs as,
so a file under the session's scratchpad or the system's temporary directory
is one they cannot open. A script, a report, a checker or a test written there
has been delivered to nobody. The edit tools are how work products are
written, so a write through them to such a place is refused, with the
instruction to put the file in the working tree instead.

A throwaway intermediate (a diff handed to a checker, a command's captured
output) is written by the shell command that produces it and is not affected.

Usage:

    python3 scratch_write_check.py --stdin

reads a PreToolUse payload for Write/Edit/MultiEdit. Exit 0 allows, exit 2
blocks with the reason on stderr.
"""

import json
import os
import sys
import tempfile

#: Directory names that mark a per-session scratch area wherever it is rooted.
SCRATCH_NAMES = ("scratchpad",)

#: Roots of the system's temporary storage.
TEMPORARY_ROOTS = ("/tmp", "/private/tmp", "/var/tmp", "/private/var/tmp",
                   "/var/folders", "/private/var/folders")


def _resolved(path):
    """Return the path with symbolic links in its existing part resolved."""
    return os.path.realpath(os.path.abspath(path))


def _within(path, root):
    """Return whether ``path`` is ``root`` or lies beneath it."""
    return path == root or path.startswith(root.rstrip(os.sep) + os.sep)


def unreadable_location(path, project_dir):
    """Return why a written file would be out of the owner's reach, or None.

    A path inside the project is always allowed, even when the project itself
    was checked out under a temporary directory.
    """
    if not path:
        return None

    target = _resolved(path)
    if project_dir and _within(target, _resolved(project_dir)):
        return None

    if any(part in SCRATCH_NAMES for part in target.split(os.sep)):
        return "the session scratchpad"

    roots = set(TEMPORARY_ROOTS) | {_resolved(tempfile.gettempdir())}
    for root in sorted(roots):
        if _within(target, root) or _within(target, _resolved(root)):
            return "the system temporary directory"

    return None


def block_reason(path, where):
    """Render the message shown when a write is refused."""
    return (
        "BLOCKED: " + path + " is in " + where + ".\n\n"
        "The person you are working for does not run as your account and cannot read\n"
        "files there, so anything written to it has been delivered to nobody. Write\n"
        "scripts, checkers, tests, reports and every other work product into the\n"
        "repository working tree, on the branch the work belongs to, from the start.\n"
        "Do not describe a file in such a place as something the user can open.\n"
    )


def main(argv=None, stdin=None, stderr=None, project_dir=None):
    """Read a PreToolUse payload and decide on the write."""
    argv = list(sys.argv[1:] if argv is None else argv)
    stdin = sys.stdin if stdin is None else stdin
    stderr = sys.stderr if stderr is None else stderr
    if project_dir is None:
        project_dir = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()

    if argv != ["--stdin"]:
        stderr.write("usage: scratch_write_check.py --stdin\n")
        return 1

    try:
        tool_input = json.loads(stdin.read()).get("tool_input") or {}
        path = tool_input.get("file_path") or tool_input.get("filePath") or ""
    except Exception:
        return 0

    where = unreadable_location(path, project_dir) if isinstance(path, str) else None
    if where is None:
        return 0

    stderr.write(block_reason(path, where))
    return 2


if __name__ == "__main__":
    sys.exit(main())
