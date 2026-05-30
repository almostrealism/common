"""Unit tests for ar-test-runner reliability fixes.

Covers:
- Fix 1 (durable monitor): the external watcher subprocess updates
  metadata.json with a terminal status when the python parent dies
  before the in-process daemon thread can run.
- Fix 1 (atexit fallback): the atexit handler marks active runs as
  ``abandoned`` when the python process exits cleanly.
- Fix 3 (default timeout): start_test_run defaults to ``timeout_minutes=15``.
"""

import asyncio
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

_SERVER_DIR = Path(__file__).resolve().parent
if str(_SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVER_DIR))

import server  # noqa: E402
import watcher  # noqa: E402


def _write_metadata(metadata_path: Path, **fields) -> None:
    metadata = {
        "run_id": "test0001",
        "config": {},
        "status": "running",
        "started_at": datetime.now().isoformat(),
        "completed_at": None,
        "exit_code": None,
        "pid": 0,
        "command": "",
    }
    metadata.update(fields)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    with open(metadata_path, "w") as f:
        json.dump(metadata, f)


class DefaultTimeoutTest(unittest.TestCase):
    """Fix 3: timeout_minutes default is 15, not 30."""

    def test_default_timeout_is_15(self):
        self.assertEqual(15, server.DEFAULT_TIMEOUT)

    def test_runconfig_default_matches_constant(self):
        config = server.RunConfig()
        self.assertEqual(server.DEFAULT_TIMEOUT, config.timeout_minutes)
        self.assertEqual(15, config.timeout_minutes)

    def test_start_test_run_path_uses_default_timeout(self):
        """The MCP call_tool dispatch reads ``DEFAULT_TIMEOUT`` from server."""
        # Verify the constant the dispatch falls back to is 15.
        # (start_test_run dispatch passes ``arguments.get("timeout_minutes",
        # DEFAULT_TIMEOUT)`` to RunConfig.)
        self.assertEqual(15, server.DEFAULT_TIMEOUT)


class JmxMonitoringJvmArgsTest(unittest.TestCase):
    """jmx_monitoring must not inject startup JVM flags.

    Both -XX:StartFlightRecording and -XX:NativeMemoryTracking break this
    project's surefire-forked JVM (the former corrupts the surefire channel
    by writing to stdout; the latter aborts the JVM when the JNI hardware
    library loads). jmx_monitoring's only job is to enable forked-PID
    discovery so ar-jmx tools can attach at runtime via jcmd.
    """

    def setUp(self):
        self._runner = server.TestRunner()
        self._tmpdir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _arg_line(self, cmd):
        for arg in cmd:
            if arg.startswith("-DargLine="):
                return arg
        return ""

    def test_jmx_monitoring_injects_no_startup_flags(self):
        config = server.RunConfig(module="utils", jmx_monitoring=True)
        cmd = self._runner.build_maven_command(
            config, run_dir=Path(self._tmpdir), run_id="r1")
        arg_line = self._arg_line(cmd)
        self.assertNotIn("StartFlightRecording", arg_line)
        self.assertNotIn("NativeMemoryTracking", arg_line)

    def test_jmx_monitoring_off_injects_no_startup_flags(self):
        config = server.RunConfig(module="utils", jmx_monitoring=False)
        cmd = self._runner.build_maven_command(
            config, run_dir=Path(self._tmpdir), run_id="r2")
        arg_line = self._arg_line(cmd)
        self.assertNotIn("StartFlightRecording", arg_line)
        self.assertNotIn("NativeMemoryTracking", arg_line)

    def test_explicit_jvm_args_still_forwarded(self):
        config = server.RunConfig(
            module="utils", jmx_monitoring=True, jvm_args=["-Xmx4g"])
        cmd = self._runner.build_maven_command(
            config, run_dir=Path(self._tmpdir), run_id="r3")
        self.assertIn("-Xmx4g", self._arg_line(cmd))


class WatcherInferStatusTest(unittest.TestCase):
    """Fix 1: watcher.infer_exit_status reads BUILD SUCCESS / BUILD FAILURE."""

    def setUp(self):
        self._tmpdir = tempfile.mkdtemp()
        self._output = Path(self._tmpdir) / "output.txt"

    def tearDown(self):
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def test_build_success_returns_completed(self):
        self._output.write_text("...maven output...\nBUILD SUCCESS\nTotal time: 3 s\n")
        self.assertEqual("completed", watcher.infer_exit_status(self._output))

    def test_build_failure_returns_failed(self):
        self._output.write_text("...maven output...\nBUILD FAILURE\nTotal time: 3 s\n")
        self.assertEqual("failed", watcher.infer_exit_status(self._output))

    def test_missing_file_returns_failed(self):
        self.assertEqual("failed", watcher.infer_exit_status(self._output))

    def test_neither_marker_returns_failed(self):
        self._output.write_text("ambiguous output, no markers\n")
        self.assertEqual("failed", watcher.infer_exit_status(self._output))


class WatcherProcessTest(unittest.TestCase):
    """Fix 1: the watcher subprocess writes terminal metadata after the
    fake-maven PID dies, simulating ar-test-runner being killed mid-run.
    """

    def setUp(self):
        self._tmpdir = Path(tempfile.mkdtemp())
        self._run_dir = self._tmpdir / "runs" / "fakerun1"
        self._run_dir.mkdir(parents=True)
        self._metadata = self._run_dir / "metadata.json"
        self._output = self._run_dir / "output.txt"
        self._reports = self._run_dir / "reports"

    def tearDown(self):
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def test_watcher_finalizes_metadata_after_maven_exits(self):
        """Spawn a short-lived process; wait for the watcher to finalize."""
        # Start a fake maven that exits in 1 second.
        fake_maven = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(1)"]
        )
        try:
            self._output.write_text("[fake maven output]\nBUILD SUCCESS\n")
            _write_metadata(self._metadata, pid=fake_maven.pid)

            # Spawn the watcher as the test does in production: detached.
            watcher_proc = subprocess.Popen(
                [sys.executable, str(_SERVER_DIR / "watcher.py"),
                 str(fake_maven.pid), str(self._metadata), str(self._output),
                 str(self._reports), str(self._tmpdir), "module-x"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                start_new_session=True,
            )

            # Wait for the fake maven to finish, then for the watcher to
            # update metadata. The grace window is 5s; allow a generous
            # margin for slow CI machines.
            fake_maven.wait()
            deadline = time.monotonic() + 30.0
            final_status = None
            while time.monotonic() < deadline:
                with open(self._metadata) as f:
                    metadata = json.load(f)
                if metadata.get("status") in ("completed", "failed", "timeout", "cancelled"):
                    final_status = metadata.get("status")
                    break
                time.sleep(0.5)

            watcher_proc.wait(timeout=15)
            self.assertEqual("completed", final_status,
                             f"watcher did not finalize metadata: {metadata}")
            self.assertEqual("watcher_process", metadata.get("completion_source"))
        finally:
            if fake_maven.poll() is None:
                fake_maven.terminate()

    def test_watcher_bails_when_pid_changes(self):
        """If metadata.pid no longer matches maven_pid, the watcher must not write."""
        fake_maven = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(1)"]
        )
        try:
            self._output.write_text("BUILD SUCCESS\n")
            # Metadata records a DIFFERENT pid than what the watcher is given.
            _write_metadata(self._metadata, pid=fake_maven.pid + 999_999)

            watcher_proc = subprocess.Popen(
                [sys.executable, str(_SERVER_DIR / "watcher.py"),
                 str(fake_maven.pid), str(self._metadata), str(self._output),
                 str(self._reports), str(self._tmpdir), "module-x"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            fake_maven.wait()
            watcher_proc.wait(timeout=30)

            # Status must remain "running" because the watcher should have bailed.
            with open(self._metadata) as f:
                metadata = json.load(f)
            self.assertEqual("running", metadata.get("status"))
            self.assertNotIn("completion_source", metadata)
        finally:
            if fake_maven.poll() is None:
                fake_maven.terminate()

    def test_watcher_does_nothing_if_thread_already_finalized(self):
        """If the in-process daemon thread already wrote terminal status, the
        watcher must observe and exit without overwriting."""
        fake_maven = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(1)"]
        )
        try:
            self._output.write_text("BUILD SUCCESS\n")
            _write_metadata(self._metadata, pid=fake_maven.pid,
                            status="completed",
                            completed_at=datetime.now().isoformat(),
                            exit_code=0)

            watcher_proc = subprocess.Popen(
                [sys.executable, str(_SERVER_DIR / "watcher.py"),
                 str(fake_maven.pid), str(self._metadata), str(self._output),
                 str(self._reports), str(self._tmpdir), "module-x"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            fake_maven.wait()
            watcher_proc.wait(timeout=30)

            with open(self._metadata) as f:
                metadata = json.load(f)
            # No mutation: completion_source must NOT be set.
            self.assertEqual("completed", metadata.get("status"))
            self.assertEqual(0, metadata.get("exit_code"))
            self.assertNotIn("completion_source", metadata)
        finally:
            if fake_maven.poll() is None:
                fake_maven.terminate()


class AbandonRunningRunsTest(unittest.TestCase):
    """Fix 1 (Option B): the atexit-style abandon_running_runs sweep marks
    running runs as ``abandoned`` and leaves terminal runs untouched.
    """

    def setUp(self):
        self._tmpdir = Path(tempfile.mkdtemp())
        self._patcher = patch.object(server, "RUNS_DIR", self._tmpdir)
        self._patcher.start()
        self._runner = server.TestRunner()

    def tearDown(self):
        self._patcher.stop()
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _make_run(self, run_id: str, status: str) -> Path:
        run_dir = self._tmpdir / run_id
        run_dir.mkdir(parents=True)
        _write_metadata(run_dir / "metadata.json", run_id=run_id, status=status)
        return run_dir

    def test_running_run_becomes_abandoned(self):
        self._make_run("ab000001", "running")
        result = self._runner.abandon_running_runs()
        self.assertEqual(["ab000001"], result)
        with open(self._tmpdir / "ab000001" / "metadata.json") as f:
            metadata = json.load(f)
        self.assertEqual("abandoned", metadata["status"])
        self.assertIn("abandoned_at", metadata)
        self.assertIn("abandoned_reason", metadata)

    def test_pending_run_becomes_abandoned(self):
        self._make_run("ab000002", "pending")
        result = self._runner.abandon_running_runs()
        self.assertEqual(["ab000002"], result)

    def test_terminal_run_left_alone(self):
        for i, status in enumerate(("completed", "failed", "timeout", "cancelled")):
            self._make_run(f"ab1000{i:02d}", status)
        result = self._runner.abandon_running_runs()
        self.assertEqual([], result)
        # All four run metadata files remain at their original status.
        for i, status in enumerate(("completed", "failed", "timeout", "cancelled")):
            with open(self._tmpdir / f"ab1000{i:02d}" / "metadata.json") as f:
                metadata = json.load(f)
            self.assertEqual(status, metadata["status"])

    def test_already_abandoned_left_alone(self):
        self._make_run("ab000003", "abandoned")
        result = self._runner.abandon_running_runs()
        self.assertEqual([], result)


class GetRunStatusBlockingTest(unittest.TestCase):
    """get_run_status block=true waits for a terminal state before responding."""

    def _dispatch(self, arguments):
        """Invoke the MCP get_run_status dispatch and return the parsed JSON."""
        result = asyncio.run(server.call_tool("get_run_status", arguments))
        return json.loads(result[0].text)

    def test_non_blocking_returns_immediately(self):
        """Without block, the dispatch returns the current (running) status once."""
        calls = []

        def fake_status(run_id):
            calls.append(run_id)
            return {"status": "running"}

        with patch.object(server.runner, "get_run_status", side_effect=fake_status):
            status = self._dispatch({"run_id": "abc"})

        self.assertEqual("running", status["status"])
        self.assertEqual(["abc"], calls)

    def test_blocking_waits_for_terminal_status(self):
        """With block=true, the dispatch polls until a terminal state appears."""
        seq = ["running", "running", "completed"]
        calls = []

        def fake_status(run_id):
            calls.append(run_id)
            return {"status": seq[min(len(calls) - 1, len(seq) - 1)]}

        # Patch sleep so the test does not actually wait between polls.
        async def no_sleep(_seconds):
            return None

        with patch.object(server.runner, "get_run_status", side_effect=fake_status), \
                patch.object(server.asyncio, "sleep", side_effect=no_sleep):
            status = self._dispatch({"run_id": "abc", "block": True, "timeout_seconds": 30})

        self.assertEqual("completed", status["status"])
        self.assertEqual(3, len(calls))

    def test_blocking_unknown_run_reports_error(self):
        """A blocking call on an unknown run still returns the not-found error."""
        with patch.object(server.runner, "get_run_status", return_value=None):
            status = self._dispatch({"run_id": "missing", "block": True})
        self.assertIn("error", status)


if __name__ == "__main__":
    unittest.main()
