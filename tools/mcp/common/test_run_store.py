"""Tests for the shared on-disk run record (``common/run_store.py``).

``RunStore`` moved out of ar-test-runner into ``common`` so that
ar-build-validator can share the same run-directory layout instead of
reimplementing metadata load/save and cleanup. These tests pin the contract
both servers now depend on — in particular the robustness that build-validator
previously lacked: a corrupt ``metadata.json`` is reported as absent (``None``)
rather than raising.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

# common/ is a package, so its tests are collected as ``common.test_run_store``.
# The module under test is imported top-level (as the servers import it), which
# needs this directory on the path — matching test_build_tree.py/test_inference.py.
sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_store  # noqa: E402


class RunStoreMetadataTest(unittest.TestCase):
    """Reading and writing a run's metadata."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.store = run_store.RunStore(self.dir)

    def tearDown(self):
        self._tmp.cleanup()

    def _make_run(self, run_id: str) -> Path:
        run_dir = self.dir / run_id
        run_dir.mkdir(parents=True)
        return run_dir

    def test_save_then_load_round_trips(self):
        self._make_run("r1")
        self.store.save("r1", {"run_id": "r1", "status": "completed"})
        self.assertEqual({"run_id": "r1", "status": "completed"},
                         self.store.load("r1"))

    def test_load_missing_run_returns_none(self):
        self.assertIsNone(self.store.load("does-not-exist"))

    def test_load_corrupt_metadata_returns_none(self):
        run_dir = self._make_run("bad")
        (run_dir / "metadata.json").write_text("{ this is : not valid json")
        # The divergence this consolidation fixed: a truncated or otherwise
        # unreadable metadata file must read as absent, never raise.
        self.assertIsNone(self.store.load("bad"))

    def test_load_non_utf8_metadata_returns_none(self):
        run_dir = self._make_run("badenc")
        # A UnicodeDecodeError is a ValueError, not an OSError or
        # json.JSONDecodeError; load() must still treat it as absent rather
        # than letting the exception escape to the caller.
        (run_dir / "metadata.json").write_bytes(b"\xff\xfe\x00\x01garbage")
        self.assertIsNone(self.store.load("badenc"))

    def test_load_non_dict_json_root_returns_none(self):
        run_dir = self._make_run("badroot")
        # Valid JSON whose root is not an object still cannot serve as
        # metadata: every caller assumes a dict and calls .get() on it.
        (run_dir / "metadata.json").write_text(json.dumps(["not", "a", "dict"]))
        self.assertIsNone(self.store.load("badroot"))

    def test_metadata_path_layout(self):
        self.assertEqual(self.dir / "r1" / "metadata.json",
                         self.store.metadata_path("r1"))


class RunStoreListingTest(unittest.TestCase):
    """Enumerating stored runs."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.store = run_store.RunStore(self.dir)

    def tearDown(self):
        self._tmp.cleanup()

    def test_run_ids_lists_only_directories(self):
        (self.dir / "run-a").mkdir()
        (self.dir / "run-b").mkdir()
        (self.dir / "stray.txt").write_text("not a run")
        self.assertEqual({"run-a", "run-b"}, set(self.store.run_ids()))

    def test_run_ids_empty_when_directory_absent(self):
        missing = run_store.RunStore(self.dir / "nope")
        self.assertEqual([], missing.run_ids())


class RunStoreCleanupTest(unittest.TestCase):
    """Retiring the oldest runs."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.store = run_store.RunStore(self.dir)

    def tearDown(self):
        self._tmp.cleanup()

    def _seed(self, count: int):
        for i in range(count):
            run_dir = self.dir / f"r{i:02d}"
            run_dir.mkdir()
            (run_dir / "metadata.json").write_text(
                json.dumps({"started_at": f"2020-01-01T00:00:{i:02d}"}))

    def test_cleanup_keeps_strictly_fewer_than_max(self):
        self._seed(35)
        self.store.cleanup(30)
        self.assertEqual(29, len(self.store.run_ids()))

    def test_cleanup_removes_oldest_first(self):
        self._seed(35)
        self.store.cleanup(30)
        remaining = sorted(self.store.run_ids())
        # r00..r05 are the six oldest; they are the ones removed.
        self.assertEqual("r06", remaining[0])
        self.assertNotIn("r00", remaining)

    def test_cleanup_tolerates_corrupt_metadata(self):
        self._seed(3)
        bad = self.dir / "bad"
        bad.mkdir()
        (bad / "metadata.json").write_text("{ broken")
        # A corrupt run sorts as oldest (empty started_at) and must not
        # abort the sweep.
        self.store.cleanup(2)
        self.assertLess(len(self.store.run_ids()), 4)


if __name__ == "__main__":
    unittest.main()
