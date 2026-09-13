"""Tests for ar-build-validator's metadata handling.

ar-build-validator used to reimplement metadata load/save and old-run cleanup
inline. It now delegates to the shared ``RunStore`` in ``common``. These tests
pin that delegation at build-validator's own call sites, and in particular that
a corrupt ``metadata.json`` now reads as absent (``None``) — the robustness the
inline copy lacked, where ``json.load`` raised straight out of ``_load_metadata``
and out of ``get_status``.

The server module is loaded from its explicit path under a directory-specific
name: nine ``tools/mcp`` directories define a top-level ``server.py``, so a bare
``import server`` would resolve to whichever one ``sys.path`` happened to reach
first (see ``common/test_layout.py``).
"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

_SERVER_DIR = Path(__file__).resolve().parent
_COMMON_DIR = _SERVER_DIR.parent / "common"
for _d in (str(_COMMON_DIR), str(_SERVER_DIR)):
    if _d not in sys.path:
        sys.path.insert(0, _d)

_spec = importlib.util.spec_from_file_location(
    "bv_server_under_test", str(_SERVER_DIR / "server.py"))
server = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(server)


class BuildValidatorMetadataTest(unittest.TestCase):
    """Metadata load/save/cleanup at build-validator's call sites."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.validator = server.BuildValidator()
        # Bind both the module global (read directly for output files) and the
        # store (bound at construction) to the temporary directory.
        self._orig_runs_dir = server.RUNS_DIR
        server.RUNS_DIR = self.dir
        self.validator.store = server.run_store.RunStore(self.dir)

    def tearDown(self):
        server.RUNS_DIR = self._orig_runs_dir
        self._tmp.cleanup()

    def _make_run(self, run_id: str) -> Path:
        run_dir = self.dir / run_id
        run_dir.mkdir(parents=True)
        return run_dir

    def test_save_then_load_round_trips(self):
        self._make_run("r1")
        self.validator._save_metadata(
            "r1", {"run_id": "r1", "status": "completed",
                   "started_at": "2020-01-01T00:00:00"})
        self.assertEqual("completed",
                         self.validator._load_metadata("r1")["status"])

    def test_load_missing_returns_none(self):
        self.assertIsNone(self.validator._load_metadata("absent"))

    def test_load_corrupt_metadata_returns_none(self):
        run_dir = self._make_run("bad")
        (run_dir / "metadata.json").write_text("{ not valid json")
        self.assertIsNone(self.validator._load_metadata("bad"))

    def test_get_status_on_corrupt_metadata_returns_none(self):
        run_dir = self._make_run("bad")
        (run_dir / "metadata.json").write_text("{ not valid json")
        # Previously this raised JSONDecodeError; a corrupt run must now be
        # reported as not found rather than crashing the status call.
        self.assertIsNone(self.validator.get_status("bad"))

    def test_cleanup_retires_oldest_runs(self):
        for i in range(server.MAX_RUNS + 5):
            run_dir = self.dir / f"r{i:03d}"
            run_dir.mkdir()
            (run_dir / "metadata.json").write_text(
                json.dumps({"started_at": f"2020-01-01T00:{i // 60:02d}:{i % 60:02d}"}))
        self.validator._cleanup_old_runs()
        remaining = sorted(p.name for p in self.dir.iterdir() if p.is_dir())
        self.assertEqual(server.MAX_RUNS - 1, len(remaining))
        self.assertNotIn("r000", remaining)


if __name__ == "__main__":
    unittest.main()
