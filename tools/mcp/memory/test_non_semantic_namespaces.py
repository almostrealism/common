"""Unit tests for non-semantic namespaces (e.g. ``messages``).

Entries in a non-semantic namespace must be persisted to SQLite (so branch /
recency retrieval via ``search_by_branch`` keeps working) but must NOT be
embedded or added to the FAISS index, and must never be returned by semantic
``search``. Stale index files from before a namespace was designated
non-semantic must be purged on startup.
"""

import os
import sys
import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import numpy as np
    import store as store_mod
    from store import MemoryStore
    _IMPORT_ERROR = None
except Exception as exc:  # numpy/faiss not installed in this environment
    _IMPORT_ERROR = exc

_REPO = "git@github.com:almostrealism/common.git"
_BRANCH = "master"


class _CountingEmbedder:
    """Embedder that records how many times it embeds, to prove skips."""

    dimension = 4

    def __init__(self):
        self.embed_calls = 0

    def embed(self, text):
        self.embed_calls += 1
        return np.zeros(self.dimension, dtype="float32")

    def embed_batch(self, texts):
        return [self.embed(t) for t in texts]


@unittest.skipIf(_IMPORT_ERROR is not None, f"store deps unavailable: {_IMPORT_ERROR}")
class NonSemanticNamespaceTests(unittest.TestCase):
    """Cover storing, searching, and retrieval of non-semantic namespaces."""

    def setUp(self):
        # `messages` is the default non-semantic namespace.
        self.assertIn("messages", store_mod.NON_SEMANTIC_NAMESPACES)
        self._tmp = TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.embedder = _CountingEmbedder()
        self.store = MemoryStore(embedder=self.embedder, data_dir=self._tmp.name)

    def test_messages_not_embedded_or_indexed(self):
        self.store.store(
            content="agent A -> agent B: build finished",
            namespace="messages", repo_url=_REPO, branch=_BRANCH,
        )
        self.assertEqual(self.embedder.embed_calls, 0)
        self.assertNotIn("messages", self.store._indices)

    def test_messages_retrievable_by_list_and_branch(self):
        self.store.store(
            content="agent A -> agent B: build finished",
            namespace="messages", repo_url=_REPO, branch=_BRANCH,
        )
        listed = self.store.list_entries(namespace="messages")
        self.assertEqual(len(listed), 1)
        by_branch = self.store.search_by_branch(
            repo_url=_REPO, branch=_BRANCH, namespace="messages",
        )
        self.assertEqual(len(by_branch), 1)

    def test_semantic_search_on_messages_returns_empty(self):
        self.store.store(
            content="agent A -> agent B: build finished",
            namespace="messages", repo_url=_REPO, branch=_BRANCH,
        )
        self.assertEqual(self.store.search("build", namespace="messages"), [])

    def test_normal_namespace_still_indexed_and_searchable(self):
        self.store.store(
            content="StateDictionary loads weights by key",
            namespace="default", repo_url=_REPO, branch=_BRANCH,
        )
        self.assertGreater(self.embedder.embed_calls, 0)
        self.assertIn("default", self.store._indices)
        self.assertEqual(len(self.store.search("weights", namespace="default")), 1)

    def test_purge_removes_stale_index_files(self):
        # Simulate messages having been embedded before the designation:
        # write index files by hand, then re-open the store.
        idx = self.store._index_path("messages")
        ids = self.store._ids_path("messages")
        idx.write_bytes(b"stale")
        ids.write_text("[]")
        self.assertTrue(idx.exists() and ids.exists())

        reopened = MemoryStore(embedder=_CountingEmbedder(), data_dir=self._tmp.name)
        self.assertFalse(idx.exists())
        self.assertFalse(ids.exists())
        self.assertNotIn("messages", reopened._indices)


if __name__ == "__main__":
    unittest.main()
