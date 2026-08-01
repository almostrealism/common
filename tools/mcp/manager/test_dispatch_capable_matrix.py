"""Matrix tests for the dispatch-capable controller-side gate and the
MCP payload forwarding it depends on.

This file complements ``test_dispatch_capable.py`` (the first-pass tests)
with the cells of the four-cell permission matrix that the controller is
responsible for:

* **Forwarding** — ``workstream_register`` always forwards
  ``dispatchCapable`` (defaulting false); ``workstream_update_config``
  forwards it with three-state semantics (omitted leaves the field
  untouched, ``True``/``False`` set it explicitly). The controller field
  is only reachable if the MCP layer actually forwards it, so these tests
  assert against the real payload dict handed to ``_controller_post``.
* **Enforcement** — ``_require_dispatch_capable`` is the effective gate
  for the opencode harness (which filters per-SERVER, not per-tool) and a
  backstop for Claude Code. A non-dispatch caller cannot self-elevate by
  passing ``dispatch_capable=True``; the check reads the *caller's*
  existing flag, never the requested child flag.
* **Scope** — ``workstream_submit_task`` is deliberately NOT gated by the
  dispatch flag. It is granted to every agent (self-submission rejected
  server-side), so the dispatch gate must not block it.

These tests live in a new file because ``test_server.py`` and
``test_dispatch_capable.py`` on the base branch are covered by the agent
write-lock; new cases go in a sibling module.
"""

import importlib
import os
import sys
import time
import unittest
from unittest.mock import patch


_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)


def _load_server():
    """Import the server module once and reuse it. The module-level
    dispatch cache is reset per test by ``_reset_dispatch_cache``."""
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
    server._set_token_context(workstream_id=None, job_id=None)
    server._request_workstream_id.set(None)
    server._request_job_id.set(None)
    if hasattr(server._thread_local, "workstream_id"):
        del server._thread_local.workstream_id
    if hasattr(server._thread_local, "job_id"):
        del server._thread_local.job_id


def _reset_dispatch_cache():
    server._dispatch_capable_cache["ids"] = None
    server._dispatch_capable_cache["fetched"] = 0.0


def _warm_dispatch_cache(ids):
    """Force a warm dispatch cache holding exactly ``ids`` so the gate
    decision is deterministic and no controller round-trip is needed."""
    server._dispatch_capable_cache["ids"] = set(ids)
    server._dispatch_capable_cache["fetched"] = time.monotonic()


class DispatchCapableForwardingTest(unittest.TestCase):
    """The MCP layer forwards ``dispatchCapable`` on the controller
    payload. These assert against the real payload dict, not a boolean
    field — a tool that declares the parameter but never forwards it is
    exactly the failure mode the dispatch feature kept hitting."""

    def setUp(self):
        _grant_all_scopes()
        _clear_token_context()
        _reset_dispatch_cache()

    def tearDown(self):
        _clear_token_context()
        _reset_dispatch_cache()

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_register_defaults_dispatch_capable_false_on_payload(
        self, mock_post, mock_get
    ):
        """An operator register that omits ``dispatch_capable`` forwards
        an explicit ``dispatchCapable: false`` so the controller never
        sees the field as absent on the create path."""
        _clear_token_context()
        mock_get.return_value = []
        mock_post.return_value = {"ok": True, "workstreamId": "ws-new"}
        server.workstream_register(default_branch="feature/new")
        payload = mock_post.call_args[0][1]
        self.assertIn("dispatchCapable", payload)
        self.assertIs(payload["dispatchCapable"], False)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_register_dispatch_capable_true_on_payload(
        self, mock_post, mock_get
    ):
        _clear_token_context()
        mock_get.return_value = []
        mock_post.return_value = {"ok": True, "workstreamId": "ws-new"}
        server.workstream_register(
            default_branch="feature/new", dispatch_capable=True
        )
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["dispatchCapable"], True)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_update_omitted_dispatch_capable_absent_from_payload(
        self, mock_post, mock_get
    ):
        """Three-state semantics: an update that omits
        ``dispatch_capable`` must NOT forward the key, so an unrelated
        config change does not silently revoke (or grant) dispatch."""
        _clear_token_context()
        mock_get.return_value = []
        mock_post.return_value = {"ok": True}
        server.workstream_update_config(
            workstream_id="ws-x", planning_document="docs/plans/X.md"
        )
        payload = mock_post.call_args[0][1]
        self.assertNotIn("dispatchCapable", payload)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_update_dispatch_capable_true_on_payload(
        self, mock_post, mock_get
    ):
        _clear_token_context()
        mock_get.return_value = []
        mock_post.return_value = {"ok": True}
        server.workstream_update_config(
            workstream_id="ws-x", dispatch_capable=True
        )
        payload = mock_post.call_args[0][1]
        self.assertIs(payload["dispatchCapable"], True)

    @patch.object(server, "_controller_get")
    @patch.object(server, "_controller_post")
    def test_update_dispatch_capable_false_revokes_on_payload(
        self, mock_post, mock_get
    ):
        """Explicit ``dispatch_capable=False`` forwards
        ``dispatchCapable: false`` so an operator can revoke a previously
        granted flag — the revoke half of the toggle."""
        _clear_token_context()
        mock_get.return_value = []
        mock_post.return_value = {"ok": True}
        server.workstream_update_config(
            workstream_id="ws-x", dispatch_capable=False
        )
        payload = mock_post.call_args[0][1]
        self.assertIn("dispatchCapable", payload)
        self.assertIs(payload["dispatchCapable"], False)


class DispatchCapableEnforcementMatrixTest(unittest.TestCase):
    """The controller-side gate. These pin the cells the harness CSV
    cannot cover — notably opencode (per-server filtering) and
    self-elevation attempts."""

    def setUp(self):
        _grant_all_scopes()
        _clear_token_context()
        _reset_dispatch_cache()

    def tearDown(self):
        _clear_token_context()
        _reset_dispatch_cache()

    @patch.object(server, "_controller_post")
    def test_non_dispatch_caller_cannot_self_elevate_via_register(
        self, mock_post
    ):
        """A non-dispatch caller that passes ``dispatch_capable=True`` is
        still denied. The gate reads the CALLER's existing flag, never
        the requested child flag, so an agent cannot grant itself
        dispatch power by asking for a dispatch-capable child."""
        server._set_token_context(
            workstream_id="ws-inert", job_id="job-inert"
        )
        _warm_dispatch_cache(set())  # caller not dispatch-capable
        with self.assertRaises(PermissionError) as ctx:
            server.workstream_register(
                default_branch="feature/child", dispatch_capable=True
            )
        self.assertIn("ws-inert", str(ctx.exception))
        mock_post.assert_not_called()

    def test_submit_task_is_not_gated_by_dispatch_capable(self):
        """``workstream_submit_task`` must NOT consult the dispatch gate.
        It is granted to every agent (self-submission rejected
        server-side), so a non-dispatch caller can still delegate work.
        We assert the gate function is never invoked on the submit path."""
        server._set_token_context(
            workstream_id="ws-inert", job_id="job-inert"
        )
        _warm_dispatch_cache(set())  # caller is NOT dispatch-capable
        with patch.object(server, "_require_dispatch_capable") as gate, \
                patch.object(server, "_controller_post") as mock_post:
            mock_post.return_value = {"ok": True, "jobId": "j-1"}
            try:
                server.workstream_submit_task(
                    prompt="do work", workstream_id="ws-target"
                )
            except Exception:
                # Other validation may reject (e.g. target resolution);
                # the point is only that the dispatch gate is never the
                # reason — assert that below.
                pass
            gate.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_opencode_style_non_dispatch_caller_denied_by_controller(
        self, mock_post
    ):
        """The opencode cell: the harness grants the whole ar-manager
        server (it cannot filter per-tool), so the controller is the only
        gate. A job-scoped non-dispatch caller is rejected here and the
        controller mutation never runs."""
        server._set_token_context(
            workstream_id="ws-opencode", job_id="job-oc"
        )
        _warm_dispatch_cache(set())
        with self.assertRaises(PermissionError):
            server.workstream_update_config(
                workstream_id="ws-opencode",
                planning_document="docs/plans/OC.md",
            )
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_register_and_update_both_honor_the_gate(self, mock_post):
        """Both dispatch tools share the gate. A single non-dispatch
        caller is denied on register AND on update, and neither reaches
        the controller."""
        server._set_token_context(
            workstream_id="ws-inert", job_id="job-inert"
        )
        _warm_dispatch_cache(set())
        with self.assertRaises(PermissionError):
            server.workstream_register(default_branch="feature/child")
        with self.assertRaises(PermissionError):
            server.workstream_update_config(
                workstream_id="ws-inert",
                planning_document="docs/plans/Y.md",
            )
        mock_post.assert_not_called()

    @patch.object(server, "_controller_post")
    def test_revoke_takes_effect_after_cache_refresh(self, mock_post):
        """A workstream that WAS dispatch-capable but is revoked (no
        longer in the refreshed set) is denied on its next call. The gate
        is not sticky-once-granted."""
        server._set_token_context(
            workstream_id="ws-was-orch", job_id="job-1"
        )
        # First: dispatch-capable -> permitted.
        _warm_dispatch_cache({"ws-was-orch"})
        mock_post.return_value = {"ok": True, "workstreamId": "ws-child"}
        first = server.workstream_register(default_branch="feature/a")
        self.assertTrue(first["ok"], msg=first)
        # Operator revokes; cache refresh now omits the workstream.
        _warm_dispatch_cache(set())
        with self.assertRaises(PermissionError):
            server.workstream_register(default_branch="feature/b")

    @patch.object(server, "_controller_post")
    def test_warm_cache_missing_caller_denies(self, mock_post):
        """A warm (non-stale) cache that simply does not contain the
        caller denies without any controller round-trip — the cache is
        authoritative within its TTL."""
        server._set_token_context(
            workstream_id="ws-absent", job_id="job-1"
        )
        _warm_dispatch_cache({"ws-other"})
        with self.assertRaises(PermissionError):
            server.workstream_register(default_branch="feature/x")
        mock_post.assert_not_called()


class DispatchCapableRefreshParsingTest(unittest.TestCase):
    """``_refresh_dispatch_capable_ids`` parses the controller's
    ``/api/workstreams`` projection. The projection is
    ``Workstream.toSummaryJson`` (Java), which emits
    ``"dispatchCapable": true`` only when set."""

    def setUp(self):
        _grant_all_scopes()
        _clear_token_context()
        _reset_dispatch_cache()

    def tearDown(self):
        _clear_token_context()
        _reset_dispatch_cache()

    @patch.object(server, "_controller_get")
    def test_refresh_reads_list_form(self, mock_get):
        mock_get.return_value = [
            {"workstreamId": "ws-a", "dispatchCapable": True},
            {"workstreamId": "ws-b"},
        ]
        ids = server._refresh_dispatch_capable_ids()
        self.assertEqual({"ws-a"}, ids)

    @patch.object(server, "_controller_get")
    def test_refresh_reads_wrapper_form(self, mock_get):
        """The controller may return ``{"workstreams": [...]}`` instead of
        a bare list; the refresh handles both shapes."""
        mock_get.return_value = {
            "workstreams": [
                {"workstreamId": "ws-a", "dispatchCapable": True},
                {"workstreamId": "ws-b", "dispatchCapable": False},
            ]
        }
        ids = server._refresh_dispatch_capable_ids()
        self.assertIn("ws-a", ids)
        self.assertNotIn("ws-b", ids)

    @patch.object(server, "_controller_get")
    def test_refresh_unreachable_controller_is_fail_closed(self, mock_get):
        """A controller error yields an empty set (fail-closed): a broken
        controller cannot silently grant dispatch."""
        mock_get.side_effect = RuntimeError("controller down")
        ids = server._refresh_dispatch_capable_ids()
        self.assertEqual(set(), ids)


if __name__ == "__main__":
    unittest.main()
