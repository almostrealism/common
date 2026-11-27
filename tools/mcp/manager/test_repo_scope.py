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
    # A repo-less workstream in TOWN sharing a branch name with no repository.
    # It must not authorize an unrelated repository on that branch.
    {"workstreamId": "w-own-bare", "workspaceId": "TOWN", "repoUrl": "",
     "defaultBranch": "feature/bare", "baseBranch": "master"},
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

    def test_branch_override_via_own_workstream_is_authorized_on_resolved_repo(
            self, mock_client_fn, *_):
        """The override path: a scoped caller supplies its own ``workstream_id``
        and an explicit branch owned by another workspace on the shared
        repository, with no ``repo_url``. The up-front check sees an empty
        repository and passes; the authorization must run again on the repo the
        workstream resolves to, with the branch override kept, and refuse."""
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(
            workstream_id="w-own", branch="feature/theirs")
        self.assertFalse(result["ok"], result)
        self.assertIn("not scoped", result["error"])
        client.search_by_branch.assert_not_called()

    def test_store_branch_override_via_own_workstream_is_refused(
            self, mock_client_fn, *_):
        """The write side of the override path: a ``memory_store`` keyed to
        another workspace's branch on the shared repository, reached by naming
        the caller's own ``workstream_id`` with a branch override and no
        ``repo_url``, must be refused before the memory is written."""
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.memory_store(
            content="note", workstream_id="w-own", branch="feature/theirs")
        self.assertFalse(result["ok"], result)
        self.assertIn("not scoped", result["error"])
        client.store.assert_not_called()

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

    def test_repo_less_branch_owner_does_not_authorize_an_unrelated_repo(
            self, mock_client_fn, *_):
        """A scoped workspace owning a repo-less workstream on ``feature/bare``
        must not thereby read an unrelated repository addressed on that branch.
        The branch match is drawn from a repository-filtered listing, so the
        repo-less fallback cannot stand in for OTHER_REPO."""
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.workstream_context(
            repo_url=OTHER_REPO, branch="feature/bare")
        self.assertFalse(result["ok"], result)
        self.assertIn("not scoped", result["error"])
        client.search_by_branch.assert_not_called()

    def test_branch_listing_failure_fails_closed_before_repo_wide_fallback(
            self, mock_client_fn, *_):
        """A controller error on the branch-owner listing must not let the
        repository-wide fallback authorize a branch owned by another workspace.
        The branch query for (OWN_REPO, feature/theirs) fails (``None``), while
        the repository-wide query would succeed because TOWN owns OWN_REPO on
        another branch — so the old fall-through would have authorized TOTHER's
        branch. The caller must be refused instead."""
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        calls = {"n": 0}

        def flaky_controller(path, timeout=10):
            if path.startswith("/api/workstreams"):
                calls["n"] += 1
                if calls["n"] == 1:
                    # The branch-owner listing is unreadable this time.
                    return None
            return _controller_get(path, timeout)

        with patch.object(server, "_controller_get", side_effect=flaky_controller):
            result = server.workstream_context(
                repo_url=OWN_REPO, branch="feature/theirs")
        self.assertFalse(result["ok"], result)
        client.search_by_branch.assert_not_called()

    def test_scope_all_without_a_repo_is_refused_for_scoped_callers(
            self, mock_client_fn, *_):
        """``scope='all'`` with no repository or workstream would read across
        every workspace; a scoped token is refused since memories carry no
        workspace to filter on."""
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.memory_recall(query="anything", scope="all")
        self.assertFalse(result["ok"], result)
        self.assertIn("scope='all'", result["error"])
        client.search.assert_not_called()

    def test_scope_all_without_a_repo_is_allowed_for_unscoped_callers(
            self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _clear_workspaces()
        result = server.memory_recall(query="anything", scope="all")
        self.assertTrue(result["ok"], result)
        client.search.assert_called()

    def test_memory_namespaces_of_another_workspaces_repo_is_refused(
            self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.memory_namespaces(repo_url=OTHER_REPO)
        self.assertFalse(result["ok"], result)
        self.assertIn("not scoped", result["error"])
        client.namespace_stats.assert_not_called()

    def test_memory_namespaces_scope_all_is_refused_for_scoped_callers(
            self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.memory_namespaces(scope="all")
        self.assertFalse(result["ok"], result)
        self.assertIn("scope='all'", result["error"])
        client.namespace_stats.assert_not_called()

    def test_memory_namespaces_of_own_repo_is_allowed(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        client.namespace_stats.return_value = []
        _set_workspaces("TOWN")
        result = server.memory_namespaces(repo_url=OWN_REPO)
        self.assertTrue(result["ok"], result)
        client.namespace_stats.assert_called()

    def test_memory_namespaces_unscoped_reads_any_repo(self, mock_client_fn, *_):
        client = self._client(mock_client_fn)
        client.namespace_stats.return_value = []
        _clear_workspaces()
        result = server.memory_namespaces(repo_url=OTHER_REPO)
        self.assertTrue(result["ok"], result)
        client.namespace_stats.assert_called()

    def _docs(self):
        """A documentation corpus stub sufficient for consult to run."""
        docs = MagicMock()
        ctx = {"context": "DOC", "html_refs": [],
               "markdown_results": [{"file": "docs/a.md", "context": "DOC"}]}
        docs.get_context_for_query.return_value = ctx
        docs.get_context_for_keywords.return_value = ctx
        return docs

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_docs")
    def test_consult_does_not_read_another_workspaces_notes(
            self, mock_docs, _mock_llm, mock_client_fn, *_):
        """consult degrades rather than hard-refuses, but the security property
        holds: a scoped caller naming another workspace's repository does not
        reach that repository's notes."""
        mock_docs.return_value = self._docs()
        client = self._client(mock_client_fn)
        _set_workspaces("TOWN")
        result = server.consult(question="how does the scope check behave",
                                repo_url=OTHER_REPO)
        self.assertTrue(result["ok"], result)
        client.search.assert_not_called()
        self.assertIn("memory_scope", result.get("degraded_reasons", []))

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_docs")
    def test_consult_reads_notes_for_an_unscoped_caller(
            self, mock_docs, _mock_llm, mock_client_fn, *_):
        mock_docs.return_value = self._docs()
        client = self._client(mock_client_fn)
        _clear_workspaces()
        result = server.consult(question="how does the scope check behave",
                                repo_url=OTHER_REPO)
        self.assertTrue(result["ok"], result)
        client.search.assert_called()
        self.assertNotIn("memory_scope", result.get("degraded_reasons", []))


if __name__ == "__main__":
    unittest.main()
