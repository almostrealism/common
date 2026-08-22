"""Shared fixtures for the ar-manager tool tests.

Imports the server under test and grants request scopes. Deliberately not
named test_*.py: it holds no tests and must not be collected as a module of
them, only imported by the modules that do.

Each tool is tested by mocking the underlying HTTP calls (_controller_get,
_controller_post, _github_request, _get_memory_client) so no running
controller, GitHub, or ar-memory server is required.
"""

import importlib
import json
import os
import subprocess
import sys
import unittest
from unittest import mock
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

# Ensure the manager package is importable
_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

_COMMON_DIR = os.path.join(os.path.dirname(_MANAGER_DIR), "common")
if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)

from inference import Synthesis  # noqa: E402

# Suppress startup prints during import
with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
    import server


def _grant_all_scopes():
    """Grant all scopes for the current request context."""
    server._set_scopes(
        [
            "read",
            "write",
            "submit",
            "pipeline",
            "github",
            "memory-read",
            "memory-write",
        ],
        label="test",
    )


def _grant_scopes(*scopes):
    """Grant specific scopes for the current request context."""
    server._set_scopes(list(scopes), label="test")


def _clear_scopes():
    """Clear the request scope context (an unauthenticated request)."""
    server._request_scopes.set(None)
    server._request_token_label.set(None)
    if hasattr(server._thread_local, "scopes"):
        del server._thread_local.scopes
    if hasattr(server._thread_local, "token_label"):
        del server._thread_local.token_label


# -----------------------------------------------------------------------
# Tier 1: Universal tools
# -----------------------------------------------------------------------


# -----------------------------------------------------------------------
# Workspace scope enforcement
# -----------------------------------------------------------------------


def _set_workspaces(*workspace_ids):
    """Mark the current request as scoped to the given workspace IDs."""
    server._set_workspace_scopes(list(workspace_ids) if workspace_ids else None)


def _clear_workspaces():
    server._request_workspace_scopes.set(None)
    if hasattr(server._thread_local, "workspace_scopes"):
        del server._thread_local.workspace_scopes


def _reset_workspace_cache():
    server._workspace_map_cache["map"] = None
    server._workspace_map_cache["fetched"] = 0.0
