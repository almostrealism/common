"""Unit tests for ``MemoryStore.namespace_stats``.

These cover the per-namespace summary used by the ``memory_namespaces`` /
``recall_namespaces`` tools: ordering by most-recent write, entry counts, the
id of the newest entry per namespace, and the optional repo/branch filters.

The store's embedding/FAISS path is irrelevant here, so a trivial fake
embedder is used and rows are inserted directly with controlled timestamps.
"""

import os
import sys
import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import numpy as np
    from store import MemoryStore
    _IMPORT_ERROR = None
except Exception as exc:  # numpy/faiss not installed in this environment
    _IMPORT_ERROR = exc


class _FakeEmbedder:
    """Minimal embedder stand-in; only needed to construct the store."""

    dimension = 4

    def embed(self, text):
        return np.zeros(self.dimension, dtype="float32")


@unittest.skipIf(_IMPORT_ERROR is not None, f"store deps unavailable: {_IMPORT_ERROR}")
class NamespaceStatsTests(unittest.TestCase):
    """Cover :meth:`MemoryStore.namespace_stats`."""

    def _store_with(self, rows):
        """Create a store in a temp dir and insert ``rows`` directly.

        Each row is ``(id, namespace, created_at, repo_url, branch)``. Insertion
        bypasses :meth:`MemoryStore.store` so the timestamps are deterministic.
        """
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = MemoryStore(embedder=_FakeEmbedder(), data_dir=tmp.name)
        for entry_id, namespace, created_at, repo_url, branch in rows:
            store._conn.execute(
                "INSERT INTO entries (id, namespace, content, tags, source, "
                "created_at, repo_url, branch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (entry_id, namespace, "content", None, None, created_at,
                 repo_url, branch),
            )
        store._conn.commit()
        return store

    def test_empty_store_returns_empty_list(self):
        store = self._store_with([])
        self.assertEqual([], store.namespace_stats())

    def test_orders_by_latest_and_counts_and_latest_id(self):
        store = self._store_with([
            ("a1", "bugs", "2026-06-06T10:00:00Z", "git@x:r.git", "b1"),
            ("a2", "bugs", "2026-06-06T12:00:00Z", "git@x:r.git", "b1"),
            ("a3", "handoff", "2026-06-06T17:30:00Z", "git@x:r.git", "b1"),
            ("a4", "context", "2026-06-05T09:00:00Z", "git@x:r.git", "b1"),
        ])
        stats = store.namespace_stats()

        # Newest-written namespace first.
        self.assertEqual(["handoff", "bugs", "context"],
                         [s["namespace"] for s in stats])
        bugs = next(s for s in stats if s["namespace"] == "bugs")
        self.assertEqual(2, bugs["count"])
        # latest_id/latest_created_at point at the most recent entry, not the first.
        self.assertEqual("a2", bugs["latest_id"])
        self.assertEqual("2026-06-06T12:00:00Z", bugs["latest_created_at"])
        handoff = stats[0]
        self.assertEqual("a3", handoff["latest_id"])
        self.assertEqual(1, handoff["count"])

    def test_repo_filter_scopes_counts(self):
        store = self._store_with([
            ("a1", "bugs", "2026-06-06T10:00:00Z", "git@x:r.git", "b1"),
            ("a2", "bugs", "2026-06-06T11:00:00Z", "git@x:other.git", "b1"),
            ("a3", "context", "2026-06-06T12:00:00Z", "git@x:other.git", "b2"),
        ])
        stats = store.namespace_stats(repo_url="git@x:r.git")
        self.assertEqual(1, len(stats))
        self.assertEqual("bugs", stats[0]["namespace"])
        self.assertEqual(1, stats[0]["count"])
        self.assertEqual("a1", stats[0]["latest_id"])

    def test_branch_filter_scopes_counts(self):
        store = self._store_with([
            ("a1", "bugs", "2026-06-06T10:00:00Z", "git@x:r.git", "b1"),
            ("a2", "bugs", "2026-06-06T11:00:00Z", "git@x:r.git", "b2"),
            ("a3", "bugs", "2026-06-06T12:00:00Z", "git@x:r.git", "b1"),
        ])
        stats = store.namespace_stats(branch="b1")
        self.assertEqual(1, len(stats))
        self.assertEqual(2, stats[0]["count"])
        self.assertEqual("a3", stats[0]["latest_id"])


if __name__ == "__main__":
    unittest.main()
