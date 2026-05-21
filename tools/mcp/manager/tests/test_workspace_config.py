"""Focused workspace-config test entry point.

The substantive test classes live in `test_server.py` at the top of the
manager directory alongside the other ar-manager MCP-tool tests. This
module re-exports them under the `tests/` subdirectory so that focused
runs of the form `pytest tests/ -k workspace` discover them.

The re-exported classes are the source of truth: edit them in
`test_server.py`, not here. Adding a new workspace-related TestCase to
`test_server.py` does not require an edit here as long as its name is
covered by the wildcard collection below.
"""

import os
import sys
import unittest

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

import test_server  # noqa: E402  -- sys.path adjusted above

_WORKSPACE_CLASSES = [
    name
    for name, obj in vars(test_server).items()
    if isinstance(obj, type)
    and issubclass(obj, unittest.TestCase)
    and "Workspace" in name
]

if not _WORKSPACE_CLASSES:
    raise RuntimeError(
        "No workspace TestCase classes found in test_server.py — "
        "the re-export in tests/test_workspace_config.py is broken."
    )

for _name in _WORKSPACE_CLASSES:
    globals()[_name] = getattr(test_server, _name)

__all__ = list(_WORKSPACE_CLASSES)
