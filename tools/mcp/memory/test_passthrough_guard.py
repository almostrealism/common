"""Unit tests for the passthrough/backend-down write-path guard.

``MemoryStore.store`` must refuse content that is a PassthroughBackend dump
(a backend-down "[Consultant model not available. Returning raw context.]"
notice followed by raw context). A census found 36 such dumps already stored
as memories, where they lead recall for large queries. The guard stops new
ones at the single chokepoint every caller passes through.

The store's embedding/FAISS path is irrelevant to the guard (rejection
happens before embedding), so a trivial fake embedder is used.
"""

import os
import sys
import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import numpy as np
    from store import MemoryStore, is_passthrough_dump
    _IMPORT_ERROR = None
except Exception as exc:  # numpy/faiss not installed in this environment
    _IMPORT_ERROR = exc

_BANNER = "[Consultant model not available. Returning raw context.]"


class PassthroughDetectorTests(unittest.TestCase):
    """Cover the pure :func:`is_passthrough_dump` detector."""

    @unittest.skipIf(_IMPORT_ERROR is not None, f"deps unavailable: {_IMPORT_ERROR}")
    def test_leading_banner_is_dump(self):
        self.assertTrue(is_passthrough_dump(_BANNER + "\n\n## Docs ..."))

    @unittest.skipIf(_IMPORT_ERROR is not None, f"deps unavailable: {_IMPORT_ERROR}")
    def test_leading_whitespace_tolerated(self):
        self.assertTrue(is_passthrough_dump("   \n" + _BANNER))

    @unittest.skipIf(_IMPORT_ERROR is not None, f"deps unavailable: {_IMPORT_ERROR}")
    def test_quoting_banner_is_not_dump(self):
        # A meta-memory (e.g. a QC audit note) that merely quotes the banner
        # partway through must NOT be flagged.
        self.assertFalse(
            is_passthrough_dump("Audit: 36 memories begin with " + _BANNER)
        )

    @unittest.skipIf(_IMPORT_ERROR is not None, f"deps unavailable: {_IMPORT_ERROR}")
    def test_normal_and_empty(self):
        self.assertFalse(is_passthrough_dump("normal knowledge"))
        self.assertFalse(is_passthrough_dump(""))
        self.assertFalse(is_passthrough_dump(None))


class _FakeEmbedder:
    """Minimal embedder stand-in; only needed to construct the store."""

    dimension = 4

    def embed(self, text):
        return np.zeros(self.dimension, dtype="float32")

    def embed_batch(self, texts):
        return [self.embed(t) for t in texts]


@unittest.skipIf(_IMPORT_ERROR is not None, f"store deps unavailable: {_IMPORT_ERROR}")
class StoreGuardTests(unittest.TestCase):
    """Cover the guard inside :meth:`MemoryStore.store`."""

    def _store(self):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        return MemoryStore(embedder=_FakeEmbedder(), data_dir=tmp.name)

    def test_store_rejects_passthrough_dump(self):
        store = self._store()
        with self.assertRaises(ValueError):
            store.store(
                content=_BANNER + "\n\n## Relevant Documentation ...",
                namespace="default",
                repo_url="git@github.com:almostrealism/common.git",
                branch="master",
            )
        # Nothing should have been persisted.
        self.assertEqual(store.list_entries(namespace="default"), [])

    def test_store_accepts_normal_content(self):
        store = self._store()
        entry = store.store(
            content="StateDictionary loads model weights by key.",
            namespace="default",
            repo_url="git@github.com:almostrealism/common.git",
            branch="master",
        )
        self.assertIn("id", entry)
        self.assertEqual(len(store.list_entries(namespace="default")), 1)


if __name__ == "__main__":
    unittest.main()
