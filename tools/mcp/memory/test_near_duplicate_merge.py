"""Unit tests for store-time near-duplicate memory merging.

When a branch loops, it writes the same note over and over ("rule X failed
again…"). :meth:`MemoryStore.store` collapses each near-identical arrival into
the newest entry on the same repo+branch, recording how many source notes that
entry now stands for. Distinct memories, and notes on other branches, must
never be merged.
"""

import os
import sys
import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common"))

try:
    import numpy as np
    import store as store_mod
    from store import MemoryStore, NEAR_DUPLICATE_SIMILARITY_THRESHOLD
    from memory_text import encode_dual_source
    _IMPORT_ERROR = None
except Exception as exc:  # numpy/faiss not installed in this environment
    _IMPORT_ERROR = exc

_REPO = "git@github.com:almostrealism/common.git"
_OTHER_REPO = "git@github.com:almostrealism/other.git"
_BRANCH = "feature/loop"
_OTHER_BRANCH = "feature/other"


class _KeywordEmbedder:
    """Deterministic embedder: a text's vector is the count of a few marker
    words. Identical or near-identical text embeds to (almost) the same vector
    — cosine ~1.0 — while text built from different markers embeds orthogonally,
    giving cosine ~0.0. Lets the merge logic be exercised without a real model.
    """

    _MARKERS = ("alpha", "beta", "gamma", "delta", "epsilon", "zeta")
    dimension = len(_MARKERS)

    def embed(self, text):
        lowered = (text or "").lower()
        vec = np.array(
            [float(lowered.count(m)) for m in self._MARKERS], dtype="float32"
        )
        if not vec.any():
            vec[0] = 1.0  # avoid the zero vector (cosine undefined)
        return vec

    def embed_batch(self, texts):
        return [self.embed(t) for t in texts]


@unittest.skipIf(_IMPORT_ERROR is not None, f"store deps unavailable: {_IMPORT_ERROR}")
class NearDuplicateMergeTests(unittest.TestCase):

    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.store = MemoryStore(embedder=_KeywordEmbedder(), data_dir=self._tmp.name)

    def _store(self, content, namespace="bugs", repo=_REPO, branch=_BRANCH):
        return self.store.store(
            content=content, namespace=namespace, repo_url=repo, branch=branch)

    def test_threshold_is_a_named_constant_in_range(self):
        self.assertGreater(NEAR_DUPLICATE_SIMILARITY_THRESHOLD, 0.5)
        self.assertLessEqual(NEAR_DUPLICATE_SIMILARITY_THRESHOLD, 1.0)

    def _parse_threshold_with(self, raw):
        original = os.environ.get("AR_MEMORY_NEAR_DUPLICATE_THRESHOLD")
        if raw is None:
            os.environ.pop("AR_MEMORY_NEAR_DUPLICATE_THRESHOLD", None)
        else:
            os.environ["AR_MEMORY_NEAR_DUPLICATE_THRESHOLD"] = raw
        try:
            return store_mod._parse_near_duplicate_threshold()
        finally:
            if original is None:
                os.environ.pop("AR_MEMORY_NEAR_DUPLICATE_THRESHOLD", None)
            else:
                os.environ["AR_MEMORY_NEAR_DUPLICATE_THRESHOLD"] = original

    def test_threshold_override_in_range_is_honoured(self):
        self.assertAlmostEqual(self._parse_threshold_with("0.85"), 0.85)
        self.assertAlmostEqual(self._parse_threshold_with("1.0"), 1.0)

    def test_threshold_override_out_of_range_falls_back_to_default(self):
        # A non-positive threshold would merge orthogonal (or anti-correlated)
        # candidates, and a value above 1 can never match; both are rejected in
        # favour of the safe default rather than silently defeating the merge
        # contract.
        for raw in ("0", "0.0", "-0.5", "1.5", "nan", "inf", "-inf", "banana"):
            with self.subTest(raw=raw):
                self.assertEqual(self._parse_threshold_with(raw), 0.97)

    def test_near_identical_consecutive_memories_are_merged(self):
        first = self._store("OOP review done on alpha beta: CLEAN")
        self.assertEqual(first["merged_count"], 1)
        self.assertEqual(first["merged_ids"], [])

        second = self._store("OOP review done on alpha beta: CLEAN")
        # The newest entry is kept and records both source notes.
        self.assertEqual(second["merged_count"], 2)
        self.assertEqual(second["merged_ids"], [first["id"]])

        third = self._store("OOP review done on alpha beta: CLEAN")
        self.assertEqual(third["merged_count"], 3)
        self.assertEqual(third["merged_ids"], [second["id"]])

        # Only one entry survives on the branch, and it is the newest.
        remaining = self.store.search_by_branch(
            repo_url=_REPO, branch=_BRANCH, namespace="bugs")
        self.assertEqual(len(remaining), 1)
        self.assertEqual(remaining[0]["id"], third["id"])
        self.assertEqual(remaining[0]["merged_count"], 3)

    def test_distinct_memories_are_kept(self):
        a = self._store("alpha alpha alpha finding about memory copy")
        b = self._store("gamma gamma gamma finding about kernel compile")
        self.assertEqual(a["merged_count"], 1)
        self.assertEqual(b["merged_count"], 1)
        self.assertEqual(b["merged_ids"], [])

        remaining = self.store.search_by_branch(
            repo_url=_REPO, branch=_BRANCH, namespace="bugs")
        self.assertEqual(len(remaining), 2)

    def test_same_text_on_different_branch_is_not_merged(self):
        self._store("OOP review done on alpha beta: CLEAN", branch=_BRANCH)
        other = self._store(
            "OOP review done on alpha beta: CLEAN", branch=_OTHER_BRANCH)
        self.assertEqual(other["merged_count"], 1)
        self.assertEqual(other["merged_ids"], [])
        self.assertEqual(
            len(self.store.search_by_branch(
                repo_url=_REPO, branch=_BRANCH, namespace="bugs")), 1)
        self.assertEqual(
            len(self.store.search_by_branch(
                repo_url=_REPO, branch=_OTHER_BRANCH, namespace="bugs")), 1)

    def test_same_text_on_different_repo_is_not_merged(self):
        self._store("OOP review done on alpha beta: CLEAN", repo=_REPO)
        other = self._store("OOP review done on alpha beta: CLEAN", repo=_OTHER_REPO)
        self.assertEqual(other["merged_count"], 1)
        self.assertEqual(other["merged_ids"], [])

    def test_merge_preserves_searchability(self):
        self._store("alpha beta review CLEAN")
        self._store("alpha beta review CLEAN")
        results = self.store.search("alpha beta review", namespace="bugs")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["merged_count"], 2)

    def test_reindex_preserves_unrelated_entries(self):
        # A distinct note must survive the index rebuild that a later merge
        # triggers, and stay independently searchable.
        distinct = self._store("gamma gamma unrelated finding")
        self._store("alpha beta review CLEAN")
        merged = self._store("alpha beta review CLEAN")
        self.assertEqual(merged["merged_count"], 2)

        remaining = self.store.search_by_branch(
            repo_url=_REPO, branch=_BRANCH, namespace="bugs")
        ids = {m["id"] for m in remaining}
        self.assertIn(distinct["id"], ids)
        self.assertIn(merged["id"], ids)
        self.assertEqual(len(remaining), 2)
        # Both are still indexed: each ranks first for its own query.
        gamma_top = self.store.search("gamma unrelated", namespace="bugs")[0]
        self.assertEqual(gamma_top["id"], distinct["id"])
        alpha_top = self.store.search("alpha beta review", namespace="bugs")[0]
        self.assertEqual(alpha_top["id"], merged["id"])

    def test_merge_can_be_disabled(self):
        original = store_mod.MERGE_NEAR_DUPLICATES
        store_mod.MERGE_NEAR_DUPLICATES = False
        try:
            self._store("alpha beta review CLEAN")
            second = self._store("alpha beta review CLEAN")
            self.assertEqual(second["merged_count"], 1)
            self.assertEqual(
                len(self.store.search_by_branch(
                    repo_url=_REPO, branch=_BRANCH, namespace="bugs")), 2)
        finally:
            store_mod.MERGE_NEAR_DUPLICATES = original

    def test_per_call_opt_out_preserves_duplicates(self):
        # A caller (e.g. a bulk import/restore) that passes
        # merge_near_duplicates=False must keep every entry even while the
        # global merge default is on, so historical notes are never silently
        # dropped by the loop-collapse heuristic.
        original = store_mod.MERGE_NEAR_DUPLICATES
        store_mod.MERGE_NEAR_DUPLICATES = True
        try:
            first = self.store.store(
                content="alpha beta review CLEAN", namespace="bugs",
                repo_url=_REPO, branch=_BRANCH, merge_near_duplicates=False)
            second = self.store.store(
                content="alpha beta review CLEAN", namespace="bugs",
                repo_url=_REPO, branch=_BRANCH, merge_near_duplicates=False)
            self.assertEqual(first["merged_count"], 1)
            self.assertEqual(second["merged_count"], 1)
            self.assertEqual(second["merged_ids"], [])
            remaining = self.store.search_by_branch(
                repo_url=_REPO, branch=_BRANCH, namespace="bugs")
            self.assertEqual(len(remaining), 2)
        finally:
            store_mod.MERGE_NEAR_DUPLICATES = original

    def test_per_call_merge_opt_in_overrides_disabled_global(self):
        # The parameter also forces merging on when the global default is off,
        # confirming None-vs-explicit resolution rather than an OR of the two.
        original = store_mod.MERGE_NEAR_DUPLICATES
        store_mod.MERGE_NEAR_DUPLICATES = False
        try:
            self.store.store(
                content="alpha beta review CLEAN", namespace="bugs",
                repo_url=_REPO, branch=_BRANCH, merge_near_duplicates=True)
            second = self.store.store(
                content="alpha beta review CLEAN", namespace="bugs",
                repo_url=_REPO, branch=_BRANCH, merge_near_duplicates=True)
            self.assertEqual(second["merged_count"], 2)
            self.assertEqual(
                len(self.store.search_by_branch(
                    repo_url=_REPO, branch=_BRANCH, namespace="bugs")), 1)
        finally:
            store_mod.MERGE_NEAR_DUPLICATES = original


    def test_incoming_reformulated_entry_is_not_merged(self):
        # A dual-text entry indexes its reformulated rewrite, so two distinct
        # originals whose rewrites embed alike must not merge and delete an
        # original. The incoming reformulated arrival is never auto-merged.
        first = self.store.store(
            content="alpha beta review CLEAN", namespace="bugs",
            repo_url=_REPO, branch=_BRANCH,
            source=encode_dual_source("the first author's distinct note"))
        second = self.store.store(
            content="alpha beta review CLEAN", namespace="bugs",
            repo_url=_REPO, branch=_BRANCH,
            source=encode_dual_source("an entirely different author's note"))
        self.assertEqual(first["merged_count"], 1)
        self.assertEqual(second["merged_count"], 1)
        self.assertEqual(second["merged_ids"], [])
        remaining = self.store.search_by_branch(
            repo_url=_REPO, branch=_BRANCH, namespace="bugs")
        self.assertEqual(len(remaining), 2)

    def test_reformulated_candidate_is_not_merged_away(self):
        # A verbatim arrival must not delete an existing reformulated entry
        # whose preserved original it cannot be shown to duplicate.
        dual = self.store.store(
            content="alpha beta review CLEAN", namespace="bugs",
            repo_url=_REPO, branch=_BRANCH,
            source=encode_dual_source("a reformulated note's original text"))
        verbatim = self._store("alpha beta review CLEAN")
        self.assertEqual(verbatim["merged_count"], 1)
        self.assertEqual(verbatim["merged_ids"], [])
        remaining = self.store.search_by_branch(
            repo_url=_REPO, branch=_BRANCH, namespace="bugs")
        ids = {m["id"] for m in remaining}
        self.assertIn(dual["id"], ids)
        self.assertIn(verbatim["id"], ids)
        self.assertEqual(len(remaining), 2)


if __name__ == "__main__":
    unittest.main()
