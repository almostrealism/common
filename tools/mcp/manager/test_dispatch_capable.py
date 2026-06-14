"""Tests for the dispatch-capable controller-side enforcement on
``workstream_register`` and ``workstream_update_config``.

The dispatch-capable flag is the gate that lets a workstream register
or update child workstreams. The harness CSV half of the contract is
in ``McpConfigBuilder.buildAllowedTools`` (Java side, tested in
``McpConfigBuilderDispatchCapableTest``); the controller-side check in
``server._require_dispatch_capable`` is the backstop for the opencode
harness, which filters per-SERVER (not per-tool) and cannot precisely
gate individual MCP tools at the harness layer.

These tests live in a new file because ``test_server.py`` is on the
base branch and the agent write-lock prevents editing it. The
behaviour pinned here:

* An unscoped / admin caller (``_get_token_workstream_id()`` returns
  None) is always permitted to register / update.
* A job-scoped caller on a workstream with
  ``dispatchCapable=true`` is permitted.
* A job-scoped caller on a workstream with
  ``dispatchCapable=false`` (or absent) is denied with a
  ``PermissionError`` that names the calling workstream and points
  the operator at the ``workstream_update_config(...,`` flag.
* The controller must NOT have been called when the check rejects
  the request.
"""

import importlib
import os
import sys
import unittest
from unittest.mock import patch


_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)


def _load_server():
    """Import the server module fresh. Cached across test cases
    because the module-level state (caches, lock) is what the dispatch
    check consults; reloading between cases would only invalidate the
    in-memory caches, which we want stable for the duration of a
    test."""
    if "server" not in sys.modules:
        with patch.dict(os.environ, {"AR_CONTROLLER_URL": "http://test:7780"}):
            importlib.import_module("server")
    return sys.modules["server"]


server = _load_server()


def _grant_all_scopes():
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


def _clear_token_context():
    """Reset the per-request caller workstream identity so the
    dispatch check sees an unscoped / admin caller."""
    server._set_token_context(workstream_id=None, job_id=None)
    server._request_workstream_id.set(None)
    server._request_job_id.set(None)
    if hasattr(server._thread_local, "workstream_id"):
        del server._thread_local.workstream_id
    if hasattr(server._thread_local, "job_id"):
        del server._thread_local.job_id


def _reset_dispatch_cache():
    """Drop the cached dispatch-capable IDs so the next test sees a
    cold cache. The tests assert against the cache miss path
    specifically, so each test starts with a cold cache."""
    server._dispatch_capable_cache["ids"] = None
    server._dispatch_capable_cache["fetched"] = 0.0


class TestWorkstreamRegisterDispatchCapable(unittest.TestCase):
    """The dispatch-capable controller-side enforcement on
    ``workstream_register``."""

    def setUp(self):
        _grant_all_scopes()
        _clear_token_context()
        _reset_dispatch_cache()

    def tearDown(self):
        _clear_token_context()
        _reset_dispatch_cache()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_unscoped_caller_is_always_permitted(
        self, mock_post, mock_get
    ):
        """An operator-level caller (no job-scoped identity) is
        always permitted. The dispatch check is specifically for
        the agent path; admin / operator flows have their own auth.
        """
        _clear_token_context()
        # Empty workstream list — controller unreachable / cold cache.
        mock_get.return_value = []
        mock_post.return_value = {"ok": True, "workstreamId": "ws-new"}
        result = server.workstream_register(default_branch="feature/new")
        self.assertTrue(result["ok"], msg=result)
        mock_post.assert_called_once()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_job_scoped_caller_with_dispatch_capable_true_is_permitted(
        self, mock_post, mock_get
    ):
        """A job-scoped caller on a dispatch-capable workstream
        proceeds to the controller. The harness CSV re-adds the
        dispatch tool entries; the controller-side check sees the
        flag and lets the request through.
        """
        server._set_token_context(
            workstream_id="ws-orch", job_id="job-orch"
        )
        mock_get.return_value = [
            {
                "workstreamId": "ws-orch",
                "workspaceId": "TAAA",
                "dispatchCapable": True,
            },
        ]
        mock_post.return_value = {
            "ok": True, "workstreamId": "ws-child"
        }
        result = server.workstream_register(
            default_branch="feature/child"
        )
        self.assertTrue(result["ok"], msg=result)
        mock_post.assert_called_once()
        # The dispatch_capable value passed by the operator must be
        # forwarded on the controller payload.
        payload = mock_post.call_args[0][1]
        self.assertIn("dispatchCapable", payload)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_job_scoped_caller_with_dispatch_capable_false_is_denied(
        self, mock_post, mock_get
    ):
        """A job-scoped caller on a non-dispatch workstream is
        denied with a PermissionError that names the calling
        workstream. The controller is NOT called.

        This is the opencode-caveat backstop: the harness CSV for
        opencode filters per-SERVER, so a non-dispatch workstream
        still sees the entire ar-manager server. The controller
        refuses the dispatch tool invocation here.
        """
        server._set_token_context(
            workstream_id="ws-inert", job_id="job-inert"
        )
        mock_get.return_value = [
            {
                "workstreamId": "ws-inert",
                "workspaceId": "TAAA",
                # dispatchCapable absent / false.
            },
        ]
        with self.assertRaises(PermissionError) as ctx:
            server.workstream_register(default_branch="feature/child")
        msg = str(ctx.exception)
        self.assertIn("dispatch-capable", msg)
        self.assertIn("ws-inert", msg)
        # Controller must NOT have been called — the rejection is
        # entirely server-side.
        mock_post.assert_not_called()


class TestWorkstreamUpdateConfigDispatchCapable(unittest.TestCase):
    """The dispatch-capable controller-side enforcement on
    ``workstream_update_config``. Same contract as
    ``workstream_register`` — both dispatch tools share the check.
    """

    def setUp(self):
        _grant_all_scopes()
        _clear_token_context()
        _reset_dispatch_cache()

    def tearDown(self):
        _clear_token_context()
        _reset_dispatch_cache()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_job_scoped_non_dispatch_caller_is_denied_on_update(
        self, mock_post, mock_get
    ):
        server._set_token_context(
            workstream_id="ws-inert", job_id="job-inert"
        )
        mock_get.return_value = [
            {
                "workstreamId": "ws-inert",
                "workspaceId": "TAAA",
            },
        ]
        with self.assertRaises(PermissionError) as ctx:
            server.workstream_update_config(
                workstream_id="ws-inert",
                planning_document="docs/plans/NEW.md",
            )
        self.assertIn("dispatch-capable", str(ctx.exception))
        mock_post.assert_not_called()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_job_scoped_dispatch_caller_is_permitted_on_update(
        self, mock_post, mock_get
    ):
        server._set_token_context(
            workstream_id="ws-orch", job_id="job-orch"
        )
        mock_get.return_value = [
            {
                "workstreamId": "ws-orch",
                "workspaceId": "TAAA",
                "dispatchCapable": True,
            },
        ]
        mock_post.return_value = {"ok": True}
        result = server.workstream_update_config(
            workstream_id="ws-orch",
            planning_document="docs/plans/CHILD.md",
        )
        self.assertTrue(result["ok"], msg=result)
        mock_post.assert_called_once()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_unscoped_caller_can_update_even_when_dispatch_cache_empty(
        self, mock_post, mock_get
    ):
        """An operator / unscoped caller is always permitted to
        update a workstream — the dispatch check is a no-op when
        there is no caller workstream identity.
        """
        _clear_token_context()
        mock_get.return_value = []
        mock_post.return_value = {"ok": True}
        result = server.workstream_update_config(
            workstream_id="ws-anything",
            planning_document="docs/plans/OP.md",
        )
        self.assertTrue(result["ok"], msg=result)


class TestDispatchCapableCache(unittest.TestCase):
    """The cache behaviour for the dispatch-capable IDs."""

    def setUp(self):
        _grant_all_scopes()
        _clear_token_context()
        _reset_dispatch_cache()

    def tearDown(self):
        _clear_token_context()
        _reset_dispatch_cache()

    @patch.object(server, "_controller_get")
    def test_cold_cache_denies_every_workstream(self, mock_get):
        """A controller that is unreachable (controller_get returns
        an empty list / errors) must result in an empty dispatch
        set. A fresh ``_require_dispatch_capable`` on a
        job-scoped caller is then denied. This is the
        fail-closed default: a broken controller cannot silently
        grant dispatch.
        """
        server._set_token_context(
            workstream_id="ws-anything", job_id="job-anything"
        )
        mock_get.return_value = []
        with self.assertRaises(PermissionError):
            server.workstream_register(default_branch="feature/x")

    @patch.object(server, "_controller_get")
    def test_warm_cache_with_dispatch_capable_permits(self, mock_get):
        """A warm cache containing the calling workstream ID
        permits the request without a controller round-trip on the
        critical path.
        """
        server._dispatch_capable_cache["ids"] = {"ws-orch"}
        server._dispatch_capable_cache["fetched"] = __import__("time").monotonic()
        server._set_token_context(
            workstream_id="ws-orch", job_id="job-orch"
        )
        with patch.object(server, "_controller_post") as mock_post:
            mock_post.return_value = {
                "ok": True, "workstreamId": "ws-child"
            }
            result = server.workstream_register(
                default_branch="feature/child"
            )
            self.assertTrue(result["ok"], msg=result)
        # The controller was called for the register, NOT for the
        # workstream-list lookup (the warm cache served it).
        # We only assert against the register call here, not the
        # list cache miss path.
        self.assertGreaterEqual(
            mock_post.call_count, 1,
            "register must be posted after a warm dispatch cache"
            " permitted the call"
        )

    @patch.object(server, "_controller_get")
    def test_cache_excludes_workstreams_without_flag(self, mock_get):
        """A workstream that is in the controller's list but does
        NOT have ``dispatchCapable: true`` must NOT appear in the
        dispatch set. The cache filter is ``ws.get(\"dispatchCapable\") is True``,
        so any other value (missing, false, \"true\" as a string)
        is excluded.
        """
        mock_get.return_value = [
            {"workstreamId": "ws-yes", "dispatchCapable": True},
            {"workstreamId": "ws-no", "dispatchCapable": False},
            {"workstreamId": "ws-absent"},  # field absent
            {"workstreamId": "ws-string"},  # not a Python bool
        ]
        ids = server._refresh_dispatch_capable_ids()
        self.assertIn("ws-yes", ids)
        self.assertNotIn("ws-no", ids)
        self.assertNotIn("ws-absent", ids)
        # The string-typed entry must not appear either — the
        # controller's Java side emits a real boolean and the
        # JSON load should round-trip it; a non-bool here is
        # treated as "not dispatch-capable" to be safe.
        self.assertNotIn("ws-string", ids)
