"""Tests that a workspace-scoped token (a job's HMAC token) cannot read a
repository outside its workspace by naming it directly.

The read tools that take ``repo_url``/``branch`` — ``workstream_context``,
``memory_recall``, ``memory_namespaces``, ``consult`` and ``memory_store`` —
resolve them through ``_resolve_branch_context`` / ``_resolve_scope_context``,
which used to accept an explicit repository without asking whose it was.
Unscoped (static/interactive) tokens must behave exactly as before.
"""

import os
import sys
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs, urlparse

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _set_workspaces, _clear_workspaces, _reset_workspace_cache,
)


OWN_REPO = "https://github.com/own/repo"
OTHER_REPO = "https://github.com/other/repo"

_WORKSTREAMS = [
    {"workstreamId": "w-own", "workspaceId": "TOWN", "repoUrl": OWN_REPO,
     "defaultBranch": "feature/own", "baseBranch": "master"},
    {"workstreamId": "w-other", "workspaceId": "TOTHER", "repoUrl": OTHER_REPO,
     "defaultBranch": "feature/other", "baseBranch": "master"},
    {"workstreamId": "w-shared", "workspaceId": "TOTHER", "repoUrl": OWN_REPO,
     "defaultBranch": "feature/theirs", "baseBranch": "master"},
]


def _controller_get(path, timeout=10):
    """A controller whose workstream listing honours the ``repoUrl`` filter."""
    if "/jobs" in path:
        return []
    query = parse_qs(urlparse(path).query)
    repo = (query.get("repoUrl") or [""])[0]
    return [ws for ws in _WORKSTREAMS if not repo or ws["repoUrl"] == repo]


@patch.object(server, "_github_request", return_value={"ok": False, "error": "not found"})
@patch.object(server, "_controller_get", side_effect=_controller_get)
@patch.object(server, "_get_memory_client")
class RepoScopeTests(unittest.TestCase):

    def setUp(self):
        _grant_all_scopes()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    def _client(self, mock_client_fn):
        client = MagicMock()
        client.search_by_branch.return_value = []
        client.search.return_value = []
        client.list_entries.return_value = []
        mock_client_fn.return_value = client
        return client

    def test_context_for_another_workspaces_repo_is_refused(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(repo_url=OTHER_REPO, branch="feature/other")
        self.assertFalse(result["ok"], result)
        self.assertIn("not scoped", result["error"])
        client.search_by_branch.assert_not_called()

    def test_explicit_repo_does_not_bypass_the_workstream_check(self, mock_client_fn, *_):
        """The reported case: repo_url, branch and workstream_id all supplied."""
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(
            workstream_id="w-own", repo_url=OTHER_REPO, branch="feature/other")
        self.assertFalse(result["ok"], result)
        client.search_by_branch.assert_not_called()

    def test_another_workspaces_branch_on_a_shared_repo_is_refused(self, mock_client_fn, *_):
        self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(repo_url=OWN_REPO, branch="feature/theirs")
        self.assertFalse(result["ok"], result)

    def test_own_workspaces_repo_and_branch_are_readable(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(repo_url=OWN_REPO, branch="feature/own")
        self.assertTrue(result["ok"], result)
        client.search_by_branch.assert_called()

    def test_an_unregistered_branch_of_an_own_repo_is_readable(self, mock_client_fn, *_):
        self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(repo_url=OWN_REPO, branch="feature/unregistered")
        self.assertTrue(result["ok"], result)

    def test_memory_recall_of_another_workspaces_repo_is_refused(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.memory_recall(query="anything", repo_url=OTHER_REPO)
        self.assertFalse(result["ok"], result)
        client.search.assert_not_called()

    def test_memory_store_on_another_workspaces_branch_is_refused(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.memory_store(
            content="note", repo_url=OTHER_REPO, branch="feature/other")
        self.assertFalse(result["ok"], result)
        client.store.assert_not_called()

    def test_unscoped_tokens_read_any_repo_as_before(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _clear_workspaces()
        result = server.workstream_context(repo_url=OTHER_REPO, branch="feature/other")
        self.assertTrue(result["ok"], result)
        client.search_by_branch.assert_called()

    def test_block_report_for_another_workspaces_job_is_refused(self, mock_client_fn, *_):
        self._client(mock_client_fn)
        _set_workspaces("TOWN")
        with patch.object(server, "_controller_post") as post:
            with self.assertRaises(PermissionError):
                server.job_report_blocked(
                    category="missing_resource", detail="x",
                    workstream_id="w-other", job_id="j-1")
            post.assert_not_called()


if __name__ == "__main__":
    unittest.main()
