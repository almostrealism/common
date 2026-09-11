"""Shared registration-matching helper for verify-exfiltration-guard.sh.

Both CHECK 2 (is the adapter registered for every required tool on HEAD?)
and CHECK 3 (did the registration entry change relative to the base branch?)
need to tell whether a PreToolUse hook command actually executes the
exfiltration guard adapter, as opposed to merely mentioning its name (for
example inside an ``echo`` or a comment). This module is imported by both of
verify-exfiltration-guard.sh's embedded Python heredocs via ``PYTHONPATH`` so
the two checks share one implementation instead of drifting apart.
"""
import os
import shlex

_WRAPPER_SHELLS = ("bash", "sh", "zsh", "ksh", "dash", "env")


def invokes_adapter(command, adapter):
    """Whether ``command`` actually executes ``adapter``, as opposed to
    merely mentioning its name (e.g. inside an ``echo`` or a comment)."""
    try:
        tokens = shlex.split(command)
    except ValueError:
        return False
    if not tokens:
        return False
    prog = tokens[0]
    if os.path.basename(prog) in _WRAPPER_SHELLS:
        rest = [t for t in tokens[1:] if not t.startswith("-")]
        if not rest:
            return False
        prog = rest[0]
    return os.path.basename(prog) == adapter
