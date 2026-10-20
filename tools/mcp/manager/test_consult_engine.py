"""Tests for the consult pipeline (consult_engine) and its tool contract."""

import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from manager_test_support import Synthesis, _grant_all_scopes, server

import consult_engine
import memory_tools
from branch_state import FixedBranchStates
from consult_evidence import (
    TIER_BRANCH_LOCAL, TIER_CURRENT, TIER_HISTORICAL, TIER_STALE, EvidenceList,
)
from consult_tiers import Caller
from snapshot_index import SnapshotIndex

NOW = datetime(2026, 10, 19, tzinfo=timezone.utc)
INDEX = SnapshotIndex({
    "corpus": {"commit": "feedfacecafe0123", "built_at": "2026-10-19T00:00:00Z"},
    "paths": ["tools/ci/agent-protection/validate-agent-commit.sh",
              "flowtree/runtime/X.java"],
    "types": {"CodingAgentJob": ["flowtree/runtime/X.java"]},
    "scripts": {"validate-agent-commit.sh":
                ["tools/ci/agent-protection/validate-agent-commit.sh"]},
    "script_facts": {"tools/ci/agent-protection/validate-agent-commit.sh":
                     {"header": "Exit codes: 3 - BLOCKED", "exits": [[148, 3]]}},
})


def _docs(results):
    docs = MagicMock()
    payload = {"context": "", "markdown_results": results, "html_refs": []}
    docs.get_context_for_query.return_value = payload
    docs.get_context_for_keywords.return_value = payload
    return docs


def _doc(file="tools/ci/README.md", start=97, end=122, text=""):
    return {"file": file, "line": start, "start": start, "end": end,
            "section": "Agent Protection", "context": text}


class _Memory:
    """A memory client with namespaces, as the eval's fixture client."""

    def __init__(self, memories, fail=False):
        self.memories = memories
        self.fail = fail
        self.searched = []

    def namespace_stats(self, repo_url=None, branch=None):
        names = []
        for m in self.memories:
            if m.get("namespace", "default") not in names:
                names.append(m.get("namespace", "default"))
        return [{"namespace": n} for n in names]

    def search(self, query, namespace="default", limit=5, repo_url=None, branch=None):
        if self.fail:
            raise ConnectionError("down")
        self.searched.append((namespace, repo_url))
        return [m for m in self.memories if m.get("namespace", "default") == namespace]


class _Pipeline(unittest.TestCase):
    """Runs server.consult with the clock, index and branch states frozen."""

    def setUp(self):
        _grant_all_scopes()
        patches = [
            patch.object(consult_engine, "clock", return_value=NOW),
            patch.object(consult_engine, "snapshot_index", return_value=INDEX),
            patch.object(consult_engine, "_branch_states", FixedBranchStates({
                "qa/merged": {"state": "merged", "merged_at": "2026-10-16"},
                "feature/open": {"state": "open"},
            })),
            patch.object(consult_engine, "resolve_caller", return_value=Caller()),
            patch.object(server, "_get_token_workstream_id", return_value=""),
            patch.object(server, "_get_token_job_id", return_value=""),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def consult(self, answer, results=None, memories=(), question="q", **kw):
        llm = MagicMock()
        llm.consult.return_value = Synthesis(answer, "fake")
        client = _Memory(list(memories)) if memories is not None else None
        with patch.object(server, "_get_docs", return_value=_docs(results or [])), \
                patch.object(server, "_get_llm", return_value=llm), \
                patch.object(server, "_get_memory_client", return_value=client):
            return server.consult(question=question,
                                  repo_url="git@github.com:org/repo.git", **kw)


class TestAnswerPostProcessing(_Pipeline):

    def test_citations_expand_to_the_range_seen(self):
        result = self.consult(
            "It rejects edits to base-branch tests [D1].\nBASIS: documentation\n"
            "CONFLICTS: none", [_doc(text="rejects edits to base-branch tests")])
        self.assertEqual(result["answer"],
                         "It rejects edits to base-branch tests "
                         "[D1: tools/ci/README.md:97-122].")
        self.assertEqual(result["answer_basis"], "documentation")
        self.assertEqual(result["corpus"]["commit"], "feedfacecafe0123")
        self.assertEqual(result["evidence"][0]["range"], [97, 122])

    def test_the_stated_basis_is_recomputed(self):
        # A fabricated ID cannot raise the basis.
        result = self.consult("Some claim [D999].\nBASIS: documentation\nCONFLICTS: none",
                              [_doc()])
        self.assertEqual(result["answer_basis"], "none")
        self.assertIn("basis mismatch", result["format_warnings"])
        self.assertEqual(result["citation_warnings"][0]["reason"], "unknown citation")

    def test_a_missing_trailer_is_reported(self):
        result = self.consult("Plain answer.", [_doc()])
        self.assertIn("trailer missing", result["format_warnings"])

    def test_an_answer_resting_on_a_current_note_is_labelled(self):
        memories = [{"id": "m1", "content": "The cap is 7.", "branch": "qa/merged",
                     "created_at": "2026-10-15T00:00:00Z", "namespace": "decisions"}]
        result = self.consult("The cap is 7 [N1].\nBASIS: notes\nCONFLICTS: none",
                              [], memories)
        self.assertEqual(result["answer_basis"], "notes")
        self.assertTrue(result["answer"].startswith(consult_engine.NOTES_BASIS_SENTENCE))
        self.assertEqual(result["related_memories"][0]["tier"], TIER_CURRENT)
        self.assertEqual(result["related_memories"][0]["namespace"], "decisions")

    def test_history_moves_out_of_the_answer(self):
        memories = [{"id": "m1", "content": "ClaudeCodeJob was replaced by "
                     "CodingAgentJob to support other runners.",
                     "branch": "feature/gone", "created_at": "2026-05-01T00:00:00Z"}]
        result = self.consult(
            "Not documented. Historically, ClaudeCodeJob was replaced by "
            "CodingAgentJob to support other runners [N1].\nBASIS: none\n"
            "CONFLICTS: none", [], memories)
        self.assertTrue(result["abstained"])
        self.assertNotIn("answer", result)
        self.assertIn("support other runners", result["history"])
        self.assertEqual(result["evidence"][0]["tier"], TIER_STALE)
        self.assertEqual(result["evidence"][0]["named_symbols"]["absent"],
                         ["ClaudeCodeJob"])

    def test_abstention_reports_what_was_searched(self):
        memories = [{"id": "m1", "content": "The widget limit is 3.",
                     "branch": "qa/merged", "created_at": "2026-10-15T00:00:00Z"}]
        result = self.consult("Not documented.\nBASIS: none\nCONFLICTS: none", [],
                              memories, question="What is the widget limit?",
                              keywords=["widget limit"])
        self.assertTrue(result["abstained"])
        self.assertTrue(result["abstained_with_evidence"])
        self.assertEqual(result["searched"]["keywords"], ["widget limit"])
        self.assertEqual(result["searched"]["evidence_by_tier"], {TIER_CURRENT: 1})

    def test_a_question_about_a_missing_file_is_answered_without_the_model(self):
        llm = MagicMock()
        with patch.object(server, "_get_docs", return_value=_docs([])), \
                patch.object(server, "_get_llm", return_value=llm):
            result = server.consult(question="How does check-branch-entropy.sh work?")
        self.assertTrue(result["nonexistent"])
        self.assertIn("No file named `check-branch-entropy.sh` exists in the "
                      "snapshot feedfacecafe", result["note"])
        llm.consult.assert_not_called()

    def test_a_note_contradicted_by_an_index_fact_is_flagged(self):
        memories = [{"id": "m1", "branch": "qa/merged",
                     "created_at": "2026-10-15T00:00:00Z",
                     "content": "validate-agent-commit.sh exits 2 when it blocks."}]
        result = self.consult(
            "validate-agent-commit.sh exits 2 [N1].\nBASIS: notes\nCONFLICTS: none",
            [], memories, keywords=["validate-agent-commit.sh"])
        self.assertIn("[unsupported]", result["answer"])
        self.assertEqual(result["citation_warnings"][0]["reason"],
                         "contradicted by snapshot")
        kinds = [c["kind"] for c in result["conflicts"]]
        self.assertIn("value", kinds)
        self.assertEqual(result["answer_basis"], "none")


class TestNoteRetrieval(_Pipeline):

    def test_every_semantic_namespace_is_searched_with_the_same_filters(self):
        client = _Memory([{"id": "a", "content": "x", "namespace": "decisions"},
                          {"id": "b", "content": "y", "namespace": "messages"}])
        llm = MagicMock()
        llm.consult.return_value = Synthesis("A.", "fake")
        with patch.object(server, "_get_docs", return_value=_docs([])), \
                patch.object(server, "_get_llm", return_value=llm), \
                patch.object(server, "_get_memory_client", return_value=client):
            server.consult(question="q", repo_url="git@github.com:org/repo.git")
        self.assertEqual([ns for ns, _ in client.searched], ["default", "decisions"])
        self.assertTrue(all(repo == "git@github.com:org/repo.git"
                            for _, repo in client.searched))

    def test_namespace_fan_out_is_capped_and_reported(self):
        memories = [{"id": str(i), "content": "c", "namespace": f"ns{i}"}
                    for i in range(10)]
        _, searched, reasons = consult_engine.search_memories(
            _Memory(memories), "q", "r", "")
        self.assertEqual(len(searched), consult_engine.MAX_NAMESPACES)
        self.assertEqual(searched[0], "default")
        self.assertEqual(reasons, ["memory_namespaces_capped"])

    def test_an_unresolvable_scope_stops_note_retrieval(self):
        client = _Memory([{"id": "a", "content": "x"}])
        llm = MagicMock()
        llm.consult.return_value = Synthesis("A.", "fake")
        with patch.object(server, "_get_docs", return_value=_docs([])), \
                patch.object(server, "_get_llm", return_value=llm), \
                patch.object(server, "_get_memory_client", return_value=client), \
                patch.object(server, "_resolve_scope_context",
                             return_value=("", "", {"ok": False, "error": "x"})):
            result = server.consult(question="q")
        self.assertEqual(client.searched, [])
        self.assertIn("memory_scope", result["degraded_reasons"])

    def test_at_most_one_history_only_note_is_presented(self):
        pairs = [({"id": "c1", "score": 0.1}, TIER_HISTORICAL),
                 ({"id": "c2", "score": 0.2}, TIER_STALE),
                 ({"id": "b1", "score": 0.9}, TIER_CURRENT),
                 ({"id": "l1", "score": 0.3}, TIER_BRANCH_LOCAL)]
        chosen = consult_engine.select_notes(pairs, Caller())
        self.assertEqual([m["id"] for m, _ in chosen], ["b1", "l1", "c1"])


class TestPureHelpers(unittest.TestCase):

    def test_parse_trailer(self):
        body, basis, conflicts, present = consult_engine.parse_trailer(
            "Answer [D1].\nBASIS: documentation+notes\nCONFLICTS: N1 vs D2 — x; none")
        self.assertEqual(body, "Answer [D1].")
        self.assertEqual(basis, "documentation+notes")
        self.assertEqual(conflicts, ["N1 vs D2 — x"])
        self.assertTrue(present)

    def test_compute_basis_counts_only_fact_tiers(self):
        ev = EvidenceList()
        ev.add_doc(_doc())
        ev.add_note({"id": "c", "content": "c"}, TIER_HISTORICAL)
        ev.add_note({"id": "b", "content": "b"}, TIER_CURRENT)
        self.assertEqual(consult_engine.compute_basis(["D1", "N1"], ev), "documentation")
        self.assertEqual(consult_engine.compute_basis(["N2"], ev), "notes")
        self.assertEqual(consult_engine.compute_basis(["D1", "N2"], ev),
                         "documentation+notes")
        self.assertEqual(consult_engine.compute_basis(["D7"], ev), "none")

    def test_reported_conflicts_must_name_presented_items(self):
        ev = EvidenceList()
        ev.add_doc(_doc())
        ev.add_note({"id": "b", "content": "b"}, TIER_CURRENT)
        self.assertEqual(len(consult_engine.reported_conflicts(
            ["N1 vs D1 — differ", "N1 vs D9 — invented"], ev)), 1)

    def test_nonexistent_mentions_short_circuits_only_a_pure_how_it_works_ask(self):
        # Every named file missing, and the question is about that file.
        self.assertEqual(
            consult_engine.nonexistent_mentions(
                "How does check-branch-entropy.sh work?", None, INDEX),
            ["check-branch-entropy.sh"])

    def test_nonexistent_mentions_yields_to_an_existing_file(self):
        # A planned file named alongside one that exists is not a dead end.
        self.assertEqual(
            consult_engine.nonexistent_mentions(
                "Does validate-agent-commit.sh supersede old-check.sh?", None,
                INDEX),
            [])

    def test_nonexistent_mentions_yields_to_a_placement_question(self):
        self.assertEqual(
            consult_engine.nonexistent_mentions(
                "Where should I put new-helper.py?", None, INDEX),
            [])

    def test_file_mentions_split_present_from_missing(self):
        present, missing = consult_engine.file_mentions(
            "validate-agent-commit.sh and gone.sh", None, INDEX)
        self.assertEqual(present, ["validate-agent-commit.sh"])
        self.assertEqual(missing, ["gone.sh"])

    def test_remove_span_keeps_indentation_outside_the_cut(self):
        sentence = SimpleNamespace(text="Remove me.")
        out = consult_engine._remove_span(
            "Intro sentence. Remove me. Rest.\n\n    indented code line", sentence)
        self.assertEqual(
            out, "Intro sentence. Rest.\n\n    indented code line")

    def test_a_historically_framed_stale_note_is_not_a_conflict(self):
        ev = EvidenceList()
        ev.add_note({"id": "s", "content": "ClaudeCodeJob was removed."}, TIER_STALE,
                    named_symbols={"present": [], "absent": ["ClaudeCodeJob"]})
        ev.add_note({"id": "t", "content": "ClaudeCodeJob owns the lifecycle."},
                    TIER_STALE, named_symbols={"present": [], "absent": ["ClaudeCodeJob"]})
        conflicts = consult_engine.structural_conflicts(ev)
        self.assertEqual([c["items"] for c in conflicts], [["N2"]])


class TestConversations(_Pipeline):

    def setUp(self):
        super().setUp()
        self.store = memory_tools.ConversationStore()
        p = patch.object(memory_tools, "_conversations", self.store)
        p.start()
        self.addCleanup(p.stop)

    def _job(self):
        return [patch.object(server, "_get_token_workstream_id", return_value="ws"),
                patch.object(server, "_get_token_job_id", return_value="job")]

    def test_a_static_token_is_refused_a_conversation(self):
        result = self.consult("A.", [_doc()], conversation_id="cv-x")
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "conversations require a job token")

    def test_a_static_token_call_is_stateless(self):
        result = self.consult("A.", [_doc()])
        self.assertNotIn("conversation_id", result)
        self.assertNotIn("turn", result)

    def test_a_follow_up_keeps_evidence_ids(self):
        patches = self._job()
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        first = self.consult("A [D1].", [_doc("a.md"), _doc("b.md")])
        self.assertEqual(first["turn"], 1)
        second = self.consult("B [D2].", [_doc("c.md"), _doc("b.md")],
                              conversation_id=first["conversation_id"])
        self.assertEqual(second["turn"], 2)
        ids = {e["file"]: e["id"] for e in second["evidence"]}
        self.assertEqual(ids["b.md"], "D2")
        self.assertEqual(ids["a.md"], "D1")  # carried forward: it was cited
        self.assertEqual(ids["c.md"], "D3")

    def test_an_expired_conversation_is_reported(self):
        patches = self._job()
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        result = self.consult("A.", [_doc()], conversation_id="cv-unknown")
        self.assertEqual(result["error"], "conversation expired")


if __name__ == "__main__":
    unittest.main()
