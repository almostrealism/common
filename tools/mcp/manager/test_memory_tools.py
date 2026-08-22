"""Tests for the memory and documentation tools.

Split from ``test_server.py``, which had grown past the file-length cap. The
tests are unchanged; shared fixtures live in ``manager_test_support``.
"""

import json
import os
import subprocess
import sys
import unittest
from unittest import mock
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from inference import Synthesis  # noqa: E402
from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _grant_scopes, _clear_scopes,
    _set_workspaces, _clear_workspaces, _reset_workspace_cache,
)


class TestMemoryRecall(unittest.TestCase):

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_recall_basic(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "Found something", "score": 0.9,
             "tags": ["test"], "created_at": "2026-01-01", "repo_url": "", "branch": ""},
        ]
        mock_client_fn.return_value = client
        # scope="all" bypasses repo_url resolution — the test just verifies
        # that a bare query plumbs through to client.search.
        result = server.memory_recall(query="test query", scope="all")
        client.search.assert_called_once()
        self.assertTrue(result["ok"])
        self.assertEqual(len(result["memories"]), 1)
        self.assertEqual(result["memories"][0]["content"], "Found something")

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        _grant_all_scopes()
        result = server.memory_recall(query="test")
        self.assertFalse(result["ok"])
        self.assertIn("unavailable", result["error"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_no_results(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = []
        mock_client_fn.return_value = client
        result = server.memory_recall(query="nothing", scope="all")
        self.assertTrue(result["ok"])
        self.assertEqual(len(result["memories"]), 0)

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_include_messages_requires_branch_context(self, mock_client_fn, _):
        # Messages are a non-semantic namespace, merged by branch/recency only
        # when both repo and branch are known. With scope="all" (no branch),
        # no messages lookup happens: semantic search runs once and
        # search_by_branch is not called.
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "memory", "score": 0.5}]
        mock_client_fn.return_value = client
        result = server.memory_recall(
            query="test", include_messages=True, scope="all")
        self.assertEqual(client.search.call_count, 1)
        client.search_by_branch.assert_not_called()
        self.assertTrue(result["ok"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_include_messages_merges_by_branch(self, mock_client_fn, _):
        # With repo+branch known, recent messages are pulled via
        # search_by_branch (NOT semantic search) and merged into the results.
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "memory", "score": 0.5,
             "created_at": "2026-01-01T00:00:00+00:00"}]
        client.search_by_branch.return_value = [
            {"id": "m2", "content": "message",
             "created_at": "2026-06-01T00:00:00+00:00"}]
        mock_client_fn.return_value = client
        result = server.memory_recall(
            query="test", include_messages=True, scope="branch",
            repo_url="git@github.com:almostrealism/common.git", branch="master")
        client.search_by_branch.assert_called_once()
        self.assertEqual(
            client.search_by_branch.call_args[1]["namespace"], "messages")
        self.assertIn("m2", {m["id"] for m in result["memories"]})

    def test_requires_memory_read_scope(self):
        _grant_scopes("read", "write", "memory-write")
        with self.assertRaises(PermissionError):
            server.memory_recall(query="test")

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    @patch.object(server, "_find_workstream")
    def test_resolve_from_workstream_repo_scope(self, mock_find, mock_client_fn, _):
        """Default scope=repo resolves repo_url but does not filter by branch."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        client = MagicMock()
        client.search.return_value = []
        mock_client_fn.return_value = client
        server.memory_recall(query="test", workstream_id="ws-test")
        call_kwargs = client.search.call_args[1]
        self.assertEqual(call_kwargs["repo_url"], "https://github.com/org/repo")
        self.assertIsNone(call_kwargs["branch"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    @patch.object(server, "_find_workstream")
    def test_resolve_from_workstream_branch_scope(self, mock_find, mock_client_fn, _):
        """scope=branch resolves both repo_url and branch from the workstream."""
        _grant_all_scopes()
        mock_find.return_value = {
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        client = MagicMock()
        client.search.return_value = []
        mock_client_fn.return_value = client
        server.memory_recall(query="test", workstream_id="ws-test", scope="branch")
        call_kwargs = client.search.call_args[1]
        self.assertEqual(call_kwargs["repo_url"], "https://github.com/org/repo")
        self.assertEqual(call_kwargs["branch"], "feature/x")

class TestMemoryReformulatedText(unittest.TestCase):
    """Retrieval shows the author's text; the rewrite is an opt-in beta."""

    def _reformulated_memory(self):
        """A memory as the Consultant's remember tool stores it."""
        return {
            "id": "m1",
            "content": "rewritten by the consultant",
            "source": json.dumps({
                "original": "what the agent actually wrote",
                "user_source": None,
            }),
            "score": 0.9,
            "created_at": "2026-01-01",
        }

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_recall_returns_the_original_by_default(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [self._reformulated_memory()]
        mock_client_fn.return_value = client
        result = server.memory_recall(query="test", scope="all")
        memory = result["memories"][0]
        self.assertEqual("what the agent actually wrote", memory["content"])
        self.assertEqual("original", memory["text_source"])
        self.assertIn("reformulated=true", result["notice"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_recall_reformulated_is_opt_in(self, mock_client_fn, _):
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [self._reformulated_memory()]
        mock_client_fn.return_value = client
        result = server.memory_recall(
            query="test", scope="all", reformulated=True)
        memory = result["memories"][0]
        self.assertEqual("rewritten by the consultant", memory["content"])
        self.assertEqual("reformulated", memory["text_source"])
        self.assertEqual("what the agent actually wrote", memory["original"])
        self.assertIn("beta", result["notice"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_verbatim_memories_are_untouched(self, mock_client_fn, _):
        """Memories stored by jobs never went through reformulation."""
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "job note", "source": "job", "score": 0.5},
        ]
        mock_client_fn.return_value = client
        result = server.memory_recall(query="test", scope="all")
        self.assertEqual("job note", result["memories"][0]["content"])
        self.assertNotIn("notice", result)

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_synthesis_summarizes_the_presented_text(self, mock_client_fn, mock_llm):
        """The summary must describe the same text the caller is shown."""
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [self._reformulated_memory()]
        mock_client_fn.return_value = client
        llm = MagicMock()
        llm.available = True
        llm.synthesize.return_value = Synthesis("summary", "fake")
        mock_llm.return_value = llm
        server.memory_recall(query="test", scope="all")
        prompt = llm.synthesize.call_args[0][0]
        self.assertIn("what the agent actually wrote", prompt)
        self.assertNotIn("rewritten by the consultant", prompt)

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_recall_returns_memories_when_synthesis_degrades(
        self, mock_client_fn, mock_llm,
    ):
        """An unreachable model costs the summary, not the memories."""
        _grant_all_scopes()
        client = MagicMock()
        client.search.return_value = [self._reformulated_memory()]
        mock_client_fn.return_value = client
        llm = MagicMock()
        llm.synthesize.return_value = Synthesis(None, "fake", "backend is down")
        mock_llm.return_value = llm

        result = server.memory_recall(query="test", scope="all")

        self.assertTrue(result["ok"])
        self.assertEqual(
            "what the agent actually wrote", result["memories"][0]["content"],
        )
        self.assertNotIn("summary", result)
        self.assertTrue(result["degraded"])
        self.assertIn("backend is down", result["note"])

    @patch.object(server, "_github_request", return_value={"ok": False, "error": "off"})
    @patch.object(server, "_get_memory_client")
    def test_workstream_context_unwraps_the_dual_source(self, mock_client_fn, _gh):
        """The dual-text JSON is an implementation detail, not caller-facing."""
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = [self._reformulated_memory()]
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        memory = result["memories"][0]
        self.assertEqual("what the agent actually wrote", memory["content"])
        self.assertNotIn("source", memory)

class TestMemoryBranchContext(unittest.TestCase):

    @patch.object(server, "_github_request")
    @patch.object(server, "_get_memory_client")
    def test_branch_context(self, mock_client_fn, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = [
            {"id": "m1", "content": "context", "created_at": "2026-01-01"},
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {
            "commits": [
                {"sha": "abc1234567", "commit": {
                    "author": {"name": "Dev", "date": "2026-01-01"},
                    "message": "Fix bug\nDetails"}},
            ]
        }
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_messages=False,
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 1)
        self.assertIn("commits", result)
        self.assertEqual(len(result["commits"]), 1)
        self.assertEqual(result["commits"][0]["message"], "Fix bug")

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        _grant_all_scopes()
        result = server.workstream_context(
            repo_url="https://github.com/org/repo", branch="feature/x")
        self.assertFalse(result["ok"])

    def test_requires_repo_or_workstream(self):
        _grant_all_scopes()
        result = server.workstream_context()
        self.assertFalse(result["ok"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_get_memory_client")
    def test_include_messages_merge_with_explicit_namespace(self, mock_client_fn, mock_gh):
        # Back-compat path: when the caller narrows to a specific namespace,
        # include_messages=True still performs a second fetch against
        # "messages" and merges the two streams.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.side_effect = [
            [{"id": "m1", "content": "mem", "created_at": "2026-01-02"}],
            [{"id": "m2", "content": "msg", "created_at": "2026-01-01"}],
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            namespace="default",
            include_messages=True,
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 2)

    @patch.object(server, "_github_request")
    @patch.object(server, "_get_memory_client")
    def test_default_returns_all_namespaces_single_fetch(self, mock_client_fn, mock_gh):
        # Default behaviour: namespace is empty → server returns a merged
        # newest-first stream across every namespace in one call.
        # include_messages is a no-op in this mode.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = [
            {"id": "m1", "namespace": "feedback", "content": "fb",
             "created_at": "2026-01-03"},
            {"id": "m2", "namespace": "messages", "content": "msg",
             "created_at": "2026-01-02"},
            {"id": "m3", "namespace": "project", "content": "proj",
             "created_at": "2026-01-01"},
        ]
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 3)
        # Exactly one fetch — no per-namespace double-query.
        self.assertEqual(client.search_by_branch.call_count, 1)
        # The call must forward a None namespace (wildcard) to the client.
        call_kwargs = client.search_by_branch.call_args.kwargs
        self.assertIsNone(call_kwargs["namespace"])

    @patch.object(server, "_get_memory_client")
    def test_skip_commits(self, mock_client_fn):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        result = server.workstream_context(
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            include_commits=False,
        )
        self.assertTrue(result["ok"])
        self.assertNotIn("commits", result)

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_jobs_timeline_compact(self, mock_client_fn, mock_controller_get, mock_gh):
        # workstream_context must include a compact jobs timeline when a
        # workstream is provided — just enough fields to situate memories,
        # NOT the operational payload (no costUsd, no targetBranch).
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        # _find_workstream() is invoked twice inside workstream_context
        # (once via _resolve_branch_context, once in the commit block),
        # followed by the jobs fetch. Return the workstream list for the
        # first two calls and the jobs list for the third.
        ws_list = [{"workstreamId": "w-1",
                    "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x",
                    "baseBranch": "master"}]
        jobs_list = [
            {"jobId": "j-1", "timestamp": "2026-04-20T10:00:00Z",
             "status": "SUCCESS", "description": "Fix bug",
             "commitHash": "abc1234567890def",
             "pullRequestUrl": "https://github.com/org/repo/pull/7",
             "targetBranch": "feature/x", "costUsd": 4.50},
            {"jobId": "j-2", "timestamp": "2026-04-20T09:00:00Z",
             "status": "FAILURE", "description": "Attempt",
             "errorMessage": "Git push failed",
             "costUsd": 1.20},
        ]
        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                return jobs_list
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertIn("jobs", result)
        self.assertEqual(2, len(result["jobs"]))
        j1, j2 = result["jobs"]
        # Compact fields present
        self.assertEqual("j-1", j1["jobId"])
        self.assertEqual("SUCCESS", j1["status"])
        self.assertEqual("Fix bug", j1["description"])
        self.assertEqual("abc1234567", j1["commitHash"])  # truncated to 10
        self.assertEqual("https://github.com/org/repo/pull/7", j1["pullRequestUrl"])
        # Operational fields absent
        self.assertNotIn("costUsd", j1)
        self.assertNotIn("targetBranch", j1)
        # Failure job includes errorMessage
        self.assertEqual("Git push failed", j2["errorMessage"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_jobs_field_present_even_when_empty(self, mock_client_fn, mock_controller_get, mock_gh):
        # When a workstream is provided and job_limit > 0, the "jobs" key
        # must appear in the response even if the workstream has no jobs —
        # an empty list is a meaningful signal distinct from omission.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [{"workstreamId": "w-1",
                    "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x"}]

        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                return []
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1")
        self.assertTrue(result["ok"])
        self.assertIn("jobs", result)
        self.assertEqual([], result["jobs"])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_job_limit_coerced_and_query_encoded(self, mock_client_fn, mock_controller_get, mock_gh):
        # Negative/garbage job_limit must not produce a malformed controller URL.
        # A negative int coerces to 0 (jobs fetch is skipped); a stringified
        # number coerces to the int value and is sent via urlencode.
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [{"workstreamId": "w-1",
                    "repoUrl": "https://github.com/org/repo",
                    "defaultBranch": "feature/x"}]
        calls = []

        def controller_side_effect(path, timeout=10):
            calls.append(path)
            if "/jobs" in path:
                return []
            return ws_list

        # Negative → no jobs fetch, no /jobs path seen, jobs field omitted.
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1", job_limit=-5)
        self.assertNotIn("jobs", result)
        self.assertFalse(any("/jobs" in p for p in calls))

        # Stringified int → coerced, urlencoded query string.
        calls.clear()
        mock_controller_get.side_effect = controller_side_effect
        server.workstream_context(workstream_id="w-1", job_limit="3")
        jobs_paths = [p for p in calls if "/jobs" in p]
        self.assertEqual(1, len(jobs_paths))
        self.assertIn("limit=3", jobs_paths[0])

    @patch.object(server, "_github_request")
    @patch.object(server, "_controller_get")
    @patch.object(server, "_get_memory_client")
    def test_jobs_timeline_omitted_when_job_limit_zero(self, mock_client_fn, mock_controller_get, mock_gh):
        _grant_all_scopes()
        client = MagicMock()
        client.search_by_branch.return_value = []
        mock_client_fn.return_value = client
        mock_gh.return_value = {"ok": False, "error": "not found"}
        ws_list = [
            {"workstreamId": "w-1",
             "repoUrl": "https://github.com/org/repo",
             "defaultBranch": "feature/x"},
        ]
        def controller_side_effect(path, timeout=10):
            if "/jobs" in path:
                self.fail("jobs endpoint must not be called when job_limit=0")
            return ws_list
        mock_controller_get.side_effect = controller_side_effect
        result = server.workstream_context(workstream_id="w-1", job_limit=0)
        self.assertTrue(result["ok"])
        self.assertNotIn("jobs", result)

class TestConsult(unittest.TestCase):
    """Documentation-grounded Q&A — the capability that kept ar-consultant
    alive. The contract that matters is that a missing model costs the
    synthesized answer and nothing that was retrieved."""

    def setUp(self):
        _grant_all_scopes()

    def _docs(self, context="DOC CONTEXT"):
        docs = MagicMock()
        docs.get_context_for_query.return_value = {
            "context": context,
            "markdown_results": [{"file": "docs/internals/a.md"}],
            "html_refs": ["docs/modules/graph.html"],
        }
        docs.get_context_for_keywords.return_value = {
            "context": context,
            "markdown_results": [{"file": "docs/internals/kw.md"}],
            "html_refs": [],
        }
        return docs

    @patch.object(server, "_get_memory_client", return_value=None)
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    def test_answers_from_documentation(self, mock_docs, mock_llm, _):
        mock_docs.return_value = self._docs()
        llm = MagicMock()
        llm.consult.return_value = Synthesis("The answer.", "fake")
        mock_llm.return_value = llm

        result = server.consult(question="how does X work")

        self.assertTrue(result["ok"])
        self.assertEqual(result["answer"], "The answer.")
        self.assertEqual(result["sources"], ["docs/internals/a.md"])
        self.assertEqual(result["html_refs"], ["docs/modules/graph.html"])
        self.assertEqual(llm.consult.call_args[1]["doc_context"], "DOC CONTEXT")

    @patch.object(server, "_get_memory_client", return_value=None)
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    def test_keywords_take_precedence_over_the_question(
            self, mock_docs, mock_llm, _):
        docs = self._docs()
        mock_docs.return_value = docs
        llm = MagicMock()
        llm.consult.return_value = Synthesis("A.", "fake")
        mock_llm.return_value = llm

        server.consult(question="q", keywords=["Features mixin"])

        docs.get_context_for_keywords.assert_called_once_with(["Features mixin"])
        docs.get_context_for_query.assert_not_called()

    @patch.object(server, "_get_memory_client", return_value=None)
    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_docs")
    def test_retrieval_survives_a_missing_model(self, mock_docs, *_):
        mock_docs.return_value = self._docs()

        result = server.consult(question="q")

        # The search results are the substance; only the answer is lost.
        self.assertTrue(result["ok"])
        self.assertTrue(result["degraded"])
        self.assertNotIn("answer", result)
        self.assertEqual(result["sources"], ["docs/internals/a.md"])
        self.assertIn("read them directly", result["note"])

    @patch.object(server, "_get_memory_client", return_value=None)
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    def test_degraded_note_advises_on_keywords(self, mock_docs, mock_llm, _):
        mock_docs.return_value = self._docs()
        llm = MagicMock()
        llm.consult.return_value = Synthesis(None, "fake", "model unreachable")
        mock_llm.return_value = llm

        result = server.consult(question="q")

        self.assertIn("keywords", result["note"])

    @patch.object(server, "_get_memory_client", return_value=None)
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    def test_not_documented_is_not_presented_as_an_answer(
            self, mock_docs, mock_llm, _):
        mock_docs.return_value = self._docs()
        llm = MagicMock()
        llm.consult.return_value = Synthesis("Not documented.", "fake")
        mock_llm.return_value = llm

        result = server.consult(question="q")

        self.assertNotIn("answer", result)
        self.assertIn("worth exploring", result["note"])

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    @patch.object(server, "_get_memory_client")
    def test_prior_notes_are_included_and_scoped(
            self, mock_client, mock_docs, mock_llm):
        mock_docs.return_value = self._docs()
        client = MagicMock()
        client.search.return_value = [{"content": "a prior note", "score": 0.2}]
        mock_client.return_value = client
        llm = MagicMock()
        llm.consult.return_value = Synthesis("A.", "fake")
        mock_llm.return_value = llm

        result = server.consult(
            question="q", repo_url="https://github.com/org/repo")

        self.assertEqual(result["related_memories"][0]["content"], "a prior note")
        self.assertEqual(
            client.search.call_args[1]["repo_url"], "https://github.com/org/repo")
        self.assertIn("a prior note", llm.consult.call_args[1]["memory_context"])

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    @patch.object(server, "_get_memory_client")
    def test_unreachable_memory_service_does_not_fail_the_call(
            self, mock_client, mock_docs, mock_llm):
        mock_docs.return_value = self._docs()
        client = MagicMock()
        client.search.side_effect = ConnectionError("down")
        mock_client.return_value = client
        llm = MagicMock()
        llm.consult.return_value = Synthesis("A.", "fake")
        mock_llm.return_value = llm

        result = server.consult(question="q")

        self.assertTrue(result["ok"])
        self.assertEqual(result["answer"], "A.")
        self.assertEqual(result["related_memories"], [])

    @patch.object(server, "_get_docs")
    def test_oversized_context_is_rejected(self, mock_docs):
        # context is concatenated into the prompt, so it carries the same
        # bound as the question. Rejecting it beats letting it displace the
        # retrieved documentation inside the model's window.
        result = server.consult(
            question="q", context="x" * (server.MAX_PROMPT_LEN + 1))
        self.assertFalse(result["ok"])
        self.assertIn("context", result["error"])
        mock_docs.assert_not_called()

    @patch.object(server, "_get_memory_client", return_value=None)
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_docs")
    def test_context_within_the_bound_reaches_the_prompt(
            self, mock_docs, mock_llm, _):
        mock_docs.return_value = self._docs()
        llm = MagicMock()
        llm.consult.return_value = Synthesis("A.", "fake")
        mock_llm.return_value = llm

        server.consult(question="q", context="a code snippet")

        self.assertEqual(
            llm.consult.call_args[1]["extra_context"], "a code snippet")

    @patch.object(server, "_get_docs", return_value=None)
    def test_no_corpus_is_reported_not_guessed_at(self, _):
        result = server.consult(question="q")
        self.assertFalse(result["ok"])
        self.assertIn("documentation corpus", result["error"])

class TestMemoryRecallDocBlending(unittest.TestCase):
    """Documentation grounding on the memory read path.

    This is what let the Consultant's ``recall`` be retired: it is the one
    capability ar-manager's ``memory_recall`` lacked. Both the corpus and the
    model are optional — losing either must cost part of the summary and never
    the memories.
    """

    def setUp(self):
        _grant_all_scopes()
        server.repo_config._cache = {}
        server.repo_config._cache_expires = float("inf")

    def tearDown(self):
        server.repo_config._cache = None
        server.repo_config._cache_expires = 0.0

    def _memories(self):
        client = MagicMock()
        client.search.return_value = [
            {"id": "m1", "content": "a note", "score": 0.1},
        ]
        return client

    @patch.object(server, "_get_docs")
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_doc_context_reaches_the_prompt(
            self, mock_client_fn, mock_llm_fn, mock_docs_fn):
        mock_client_fn.return_value = self._memories()
        docs = MagicMock()
        docs.get_context_for_query.return_value = {
            "context": "DOC CONTEXT HERE",
            "markdown_results": [{"file": "docs/internals/thing.md"}],
            "html_refs": ["docs/modules/graph.html"],
        }
        mock_docs_fn.return_value = docs
        llm = MagicMock()
        llm.synthesize.return_value = Synthesis("a summary", "fake")
        mock_llm_fn.return_value = llm

        result = server.memory_recall(
            query="how does X work", repo_url="https://github.com/org/repo")

        prompt = llm.synthesize.call_args[0][0]
        self.assertIn("DOC CONTEXT HERE", prompt)
        self.assertIn("Relevant Documentation", prompt)
        self.assertEqual(
            result["doc_references"],
            ["docs/internals/thing.md", "docs/modules/graph.html"])

    @patch.object(server, "_get_docs", return_value=None)
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_summary_still_produced_without_a_corpus(
            self, mock_client_fn, mock_llm_fn, _):
        mock_client_fn.return_value = self._memories()
        llm = MagicMock()
        llm.synthesize.return_value = Synthesis("a summary", "fake")
        mock_llm_fn.return_value = llm

        result = server.memory_recall(
            query="q", repo_url="https://github.com/org/repo")

        self.assertTrue(result["ok"])
        self.assertEqual(result["summary"], "a summary")
        self.assertNotIn("doc_references", result)
        self.assertNotIn("Relevant Documentation",
                         llm.synthesize.call_args[0][0])

    @patch.object(server, "_get_docs")
    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_memories_survive_when_only_the_model_is_gone(
            self, mock_client_fn, _, mock_docs_fn):
        mock_client_fn.return_value = self._memories()
        docs = MagicMock()
        docs.get_context_for_query.return_value = {
            "context": "DOC", "markdown_results": [{"file": "docs/a.md"}],
            "html_refs": [],
        }
        mock_docs_fn.return_value = docs

        result = server.memory_recall(
            query="q", repo_url="https://github.com/org/repo")

        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 1)
        self.assertTrue(result["degraded"])
        # The corpus was still consulted, so the references remain useful.
        self.assertEqual(result["doc_references"], ["docs/a.md"])

    @patch.object(server, "_get_docs")
    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_corpus_read_error_does_not_fail_the_call(
            self, mock_client_fn, mock_llm_fn, mock_docs_fn):
        mock_client_fn.return_value = self._memories()
        docs = MagicMock()
        docs.get_context_for_query.side_effect = OSError("corpus unreadable")
        mock_docs_fn.return_value = docs
        llm = MagicMock()
        llm.synthesize.return_value = Synthesis("a summary", "fake")
        mock_llm_fn.return_value = llm

        result = server.memory_recall(
            query="q", repo_url="https://github.com/org/repo")

        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 1)
        self.assertNotIn("doc_references", result)

class TestMemoryNamespaces(unittest.TestCase):
    """Namespace enumeration — the ar-manager counterpart of the Consultant's
    ``recall_namespaces``, without which Phase C would strand that tool."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_get_memory_client")
    def test_lists_namespaces_scoped_to_repo(self, mock_client_fn):
        client = MagicMock()
        client.namespace_stats.return_value = [
            {"namespace": "progress", "count": 3, "latest_created_at": "2026-08-18"},
            {"namespace": "bugs", "count": 1, "latest_created_at": "2026-08-01"},
        ]
        mock_client_fn.return_value = client

        result = server.memory_namespaces(
            repo_url="https://github.com/org/repo", branch="feature/x")

        self.assertTrue(result["ok"])
        self.assertEqual(result["count"], 2)
        self.assertEqual(result["namespaces"][0]["namespace"], "progress")
        kwargs = client.namespace_stats.call_args[1]
        self.assertEqual(kwargs["repo_url"], "https://github.com/org/repo")
        # Repo scope keeps an explicitly supplied branch; it only declines to
        # infer one. This matches memory_recall, which shares the resolver.
        self.assertEqual(kwargs["branch"], "feature/x")

    @patch.object(server, "_get_memory_client")
    def test_repo_scope_does_not_infer_a_branch(self, mock_client_fn):
        client = MagicMock()
        client.namespace_stats.return_value = []
        mock_client_fn.return_value = client

        server.memory_namespaces(repo_url="https://github.com/org/repo")

        self.assertIsNone(client.namespace_stats.call_args[1]["branch"])

    @patch.object(server, "_get_memory_client")
    def test_branch_scope_narrows_to_branch(self, mock_client_fn):
        client = MagicMock()
        client.namespace_stats.return_value = []
        mock_client_fn.return_value = client

        server.memory_namespaces(
            repo_url="https://github.com/org/repo", branch="feature/x",
            scope="branch")

        self.assertEqual(
            client.namespace_stats.call_args[1]["branch"], "feature/x")

    @patch.object(server, "_get_memory_client")
    def test_all_scope_applies_no_filter(self, mock_client_fn):
        client = MagicMock()
        client.namespace_stats.return_value = []
        mock_client_fn.return_value = client

        server.memory_namespaces(scope="all")

        kwargs = client.namespace_stats.call_args[1]
        self.assertIsNone(kwargs["repo_url"])
        self.assertIsNone(kwargs["branch"])

    @patch.object(server, "_get_memory_client")
    def test_rejects_unknown_scope(self, mock_client_fn):
        client = MagicMock()
        mock_client_fn.return_value = client
        result = server.memory_namespaces(
            repo_url="https://github.com/org/repo", scope="sideways")
        self.assertFalse(result["ok"])
        self.assertIn("sideways", result["error"])
        client.namespace_stats.assert_not_called()

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        result = server.memory_namespaces(repo_url="https://github.com/org/repo")
        self.assertFalse(result["ok"])

    @patch.object(server, "_get_memory_client")
    def test_connection_error_is_reported(self, mock_client_fn):
        client = MagicMock()
        client.namespace_stats.side_effect = ConnectionError("boom")
        mock_client_fn.return_value = client
        result = server.memory_namespaces(repo_url="https://github.com/org/repo")
        self.assertFalse(result["ok"])
        self.assertIn("boom", result["error"])

class TestMemoryStoreReformulation(unittest.TestCase):
    """Reformulation on the write path.

    The contract that matters is that enabling reformulation never costs the
    caller the memory: when no model is reachable the author's own text is
    stored anyway. See docs/plans/MANAGER_CONSULTANT_CONSOLIDATION.md.
    """

    def setUp(self):
        _grant_all_scopes()
        server.repo_config._cache = {}
        server.repo_config._cache_expires = float("inf")

    def tearDown(self):
        server.repo_config._cache = None
        server.repo_config._cache_expires = 0.0

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_reformulates_and_stores_both_versions(self, mock_client_fn, mock_llm_fn):
        client = MagicMock()
        client.store_dual.return_value = {"id": "dual-1"}
        mock_client_fn.return_value = client
        llm = MagicMock()
        llm.reformulate.return_value = Synthesis("Rewritten note", "fake")
        mock_llm_fn.return_value = llm

        result = server.memory_store(
            content="raw note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            reformulate=True,
        )

        client.store_dual.assert_called_once()
        kwargs = client.store_dual.call_args[1]
        self.assertEqual(kwargs["original"], "raw note")
        self.assertEqual(kwargs["reformulated"], "Rewritten note")
        client.store.assert_not_called()
        self.assertTrue(result["ok"])
        self.assertTrue(result["reformulated_stored"])

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_stores_original_when_backend_degraded(self, mock_client_fn, mock_llm_fn):
        client = MagicMock()
        client.store.return_value = {"id": "plain-1"}
        mock_client_fn.return_value = client
        llm = MagicMock()
        llm.reformulate.return_value = Synthesis(None, "fake", "model unreachable")
        mock_llm_fn.return_value = llm

        result = server.memory_store(
            content="raw note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            reformulate=True,
        )

        # The memory must survive the missing model.
        client.store.assert_called_once()
        self.assertEqual(client.store.call_args[1]["content"], "raw note")
        client.store_dual.assert_not_called()
        self.assertTrue(result["ok"])
        self.assertFalse(result["reformulated_stored"])
        self.assertTrue(result["degraded"])

    @patch.object(server, "_get_llm", return_value=None)
    @patch.object(server, "_get_memory_client")
    def test_stores_original_when_no_backend_at_all(self, mock_client_fn, _):
        client = MagicMock()
        client.store.return_value = {"id": "plain-2"}
        mock_client_fn.return_value = client

        result = server.memory_store(
            content="raw note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            reformulate=True,
        )

        client.store.assert_called_once()
        self.assertTrue(result["ok"])
        self.assertFalse(result["reformulated_stored"])

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_disabled_by_default(self, mock_client_fn, mock_llm_fn):
        client = MagicMock()
        client.store.return_value = {"id": "plain-3"}
        mock_client_fn.return_value = client

        server.memory_store(
            content="raw note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )

        client.store.assert_called_once()
        client.store_dual.assert_not_called()
        mock_llm_fn.assert_not_called()

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_repo_config_enables_without_an_explicit_argument(
            self, mock_client_fn, mock_llm_fn):
        server.repo_config._cache = {
            "org/repo": {"reformulateOnStore": True},
        }
        client = MagicMock()
        client.store_dual.return_value = {"id": "dual-2"}
        mock_client_fn.return_value = client
        llm = MagicMock()
        llm.reformulate.return_value = Synthesis("Rewritten", "fake")
        mock_llm_fn.return_value = llm

        result = server.memory_store(
            content="raw note",
            repo_url="git@github.com:org/repo.git",
            branch="feature/x",
        )

        client.store_dual.assert_called_once()
        self.assertTrue(result["reformulated_stored"])

    @patch.object(server, "_get_llm")
    @patch.object(server, "_get_memory_client")
    def test_explicit_argument_overrides_repo_config(
            self, mock_client_fn, mock_llm_fn):
        server.repo_config._cache = {
            "org/repo": {"reformulateOnStore": True},
        }
        client = MagicMock()
        client.store.return_value = {"id": "plain-4"}
        mock_client_fn.return_value = client

        server.memory_store(
            content="raw note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            reformulate=False,
        )

        client.store.assert_called_once()
        client.store_dual.assert_not_called()
        mock_llm_fn.assert_not_called()

class TestMemoryStore(unittest.TestCase):

    @patch.object(server, "_get_memory_client")
    def test_store_basic(self, mock_client_fn):
        _grant_all_scopes()
        client = MagicMock()
        client.store.return_value = {"id": "new-1", "content": "stored"}
        mock_client_fn.return_value = client
        result = server.memory_store(
            content="Something important",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        client.store.assert_called_once()
        call_kwargs = client.store.call_args[1]
        self.assertEqual(call_kwargs["content"], "Something important")
        self.assertTrue(result["ok"])
        self.assertIn("next_steps", result)

    @patch.object(server, "_get_memory_client")
    def test_store_with_tags(self, mock_client_fn):
        _grant_all_scopes()
        client = MagicMock()
        client.store.return_value = {"id": "new-2"}
        mock_client_fn.return_value = client
        server.memory_store(
            content="Tagged note",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
            tags=["bug", "fix"],
            source="test",
        )
        call_kwargs = client.store.call_args[1]
        self.assertEqual(call_kwargs["tags"], ["bug", "fix"])
        self.assertEqual(call_kwargs["source"], "test")

    @patch.object(server, "_get_memory_client", return_value=None)
    def test_memory_unavailable(self, _):
        _grant_all_scopes()
        result = server.memory_store(
            content="test",
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        self.assertFalse(result["ok"])

    def test_requires_branch_context(self):
        _grant_all_scopes()
        # Make sure no leftover thread-local / ContextVar / per-request
        # bearer survives from another test class — otherwise the new
        # token fallback in _resolve_branch_context would resolve here.
        server._set_token_context("", "")
        server._request_workstream_id.set(None)
        server._request_job_id.set(None)
        with patch.object(server.mcp, "get_context",
                          side_effect=LookupError("no active request")):
            result = server.memory_store(content="test")
        self.assertFalse(result["ok"])

    @patch.object(server, "_find_workstream")
    @patch.object(server, "_get_memory_client")
    def test_store_resolves_repo_branch_from_token_workstream(
            self, mock_client_fn, mock_find_ws):
        """Regression: memory_store with only ``content`` and a valid token
        context must resolve repo_url and branch from the workstream the
        temp token is bound to. This mirrors the
        :func:`send_message` fix — job-scoped tools should accept a minimal
        payload because the workstream is already implicit in the bearer."""
        _grant_all_scopes()
        # Simulate the auth middleware having decoded a temp token bound to
        # workstream ``ws-token``.
        server._set_token_context("ws-token", "job-token")
        mock_find_ws.return_value = {
            "workstreamId": "ws-token",
            "repoUrl": "https://github.com/org/repo",
            "defaultBranch": "feature/x",
        }
        client = MagicMock()
        client.store.return_value = {"id": "auto-resolved"}
        mock_client_fn.return_value = client

        # No workstream_id, no repo_url, no branch — only content.
        result = server.memory_store(content="Note from a job session")
        self.assertTrue(result["ok"], msg=result.get("error"))
        call_kwargs = client.store.call_args[1]
        self.assertEqual(call_kwargs["repo_url"],
                         "https://github.com/org/repo")
        self.assertEqual(call_kwargs["branch"], "feature/x")
        # Clean up to avoid leaking state into sibling tests.
        server._set_token_context("", "")

    def test_rejects_oversized_content(self):
        _grant_all_scopes()
        result = server.memory_store(
            content="x" * 50_001,
            repo_url="https://github.com/org/repo",
            branch="feature/x",
        )
        self.assertFalse(result["ok"])
