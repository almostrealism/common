"""Integration tests for ar-test-runner preflight wiring.

These tests verify that ``TestRunner.start_run`` correctly invokes the
preflight seeding step before launching Maven, and that a preflight
failure short-circuits the run cleanly:

* When all upstream artifacts are already in ``~/.m2``, the preflight
  is a no-op and the test launches normally.
* When the seeding subprocess returns success, the test launches and
  the preflight banner is recorded in ``output.txt``.
* When the seeding subprocess fails, the run is marked ``failed``
  immediately, no Maven test process is spawned, and the preflight
  banner explains why.

Maven is never actually invoked: ``server.subprocess.Popen`` is
stubbed in each test to capture invocations and ``preflight._default_runner``
is replaced with an in-process callable.

Run from the repo root::

    python3 -m unittest tools/mcp/test-runner/test_preflight_integration.py -v
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

_COMMON_DIR = _HERE.parent / "common"
if str(_COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(_COMMON_DIR))

import preflight  # noqa: E402
import server  # noqa: E402


def _write_root_pom(project_root: Path, version: str = "0.74") -> None:
    (project_root / "pom.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
        '    <modelVersion>4.0.0</modelVersion>\n'
        '    <groupId>org.almostrealism</groupId>\n'
        '    <artifactId>common</artifactId>\n'
        f'    <version>{version}</version>\n'
        '    <packaging>pom</packaging>\n'
        '</project>\n'
    )


def _write_module_pom(project_root: Path, module: str, ar_deps: list) -> None:
    dep_blocks = []
    for artifact_id, version in ar_deps:
        dep_blocks.append(
            "        <dependency>\n"
            "            <groupId>org.almostrealism</groupId>\n"
            f"            <artifactId>{artifact_id}</artifactId>\n"
            f"            <version>{version}</version>\n"
            "        </dependency>"
        )
    module_dir = project_root / module
    module_dir.mkdir(parents=True, exist_ok=True)
    deps_xml = "\n".join(dep_blocks) if dep_blocks else ""
    (module_dir / "pom.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
        '    <parent>\n'
        '        <groupId>org.almostrealism</groupId>\n'
        '        <artifactId>common</artifactId>\n'
        '        <version>0.74</version>\n'
        '    </parent>\n'
        '    <modelVersion>4.0.0</modelVersion>\n'
        f'    <artifactId>ar-{module.replace("/", "-")}</artifactId>\n'
        '    <dependencies>\n'
        f'{deps_xml}\n'
        '    </dependencies>\n'
        '</project>\n'
    )


def _install_jar(repository: Path, artifact_id: str, version: str) -> None:
    base = repository / "org" / "almostrealism" / artifact_id / version
    base.mkdir(parents=True, exist_ok=True)
    (base / f"{artifact_id}-{version}.jar").write_bytes(b"")


class _FakePopen:
    """Minimal stand-in for ``subprocess.Popen`` used in tests.

    Records every invocation in :data:`launched` so assertions can
    confirm what ``server.start_run`` tried to spawn without actually
    launching Maven.
    """

    launched: list = []

    def __init__(self, cmd, *args, **kwargs):
        type(self).launched.append((list(cmd), kwargs))
        self.pid = 99999

    # Methods invoked by the watcher thread:
    def poll(self):
        return 0

    def wait(self):
        return 0

    @classmethod
    def reset(cls):
        cls.launched = []


class StartRunPreflightIntegrationTests(unittest.TestCase):
    """``TestRunner.start_run`` calls preflight before the test launch."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self._tmp_path = Path(self._tmp.name)
        self._project_root = self._tmp_path / "project"
        self._project_root.mkdir()
        self._runs_dir = self._tmp_path / "runs"
        self._runs_dir.mkdir()
        self._m2 = self._tmp_path / "m2"
        self._m2.mkdir()
        _write_root_pom(self._project_root)
        _write_module_pom(self._project_root, "engine/utils", [
            ("ar-space", "0.74"),
        ])

        self._patches = [
            patch.object(server, "PROJECT_ROOT", self._project_root),
            patch.object(server, "RUNS_DIR", self._runs_dir),
            patch.object(server, "subprocess",
                         _StubSubprocess(_FakePopen)),
            patch.object(preflight, "DEFAULT_M2_REPOSITORY", self._m2),
            patch.object(server.threading, "Timer", _NoOpTimer),
            patch.object(server.threading, "Thread", _NoOpThread),
            patch.object(server.TestRunner, "_spawn_watcher_subprocess",
                         lambda *_args, **_kwargs: None),
        ]
        for p in self._patches:
            p.start()
        _FakePopen.reset()
        self._runner = server.TestRunner()

    def tearDown(self):
        for p in self._patches:
            p.stop()
        self._tmp.cleanup()

    def _read_output(self, run_id: str) -> str:
        return (self._runs_dir / run_id / "output.txt").read_text()

    def _read_metadata(self, run_id: str) -> dict:
        with open(self._runs_dir / run_id / "metadata.json") as f:
            return json.load(f)

    def test_skipped_preflight_when_artifacts_installed(self):
        """All AR jars present in m2: preflight is a no-op, test launches."""
        _install_jar(self._m2, "ar-space", "0.74")

        with patch.object(preflight, "_default_runner",
                          side_effect=AssertionError(
                              "preflight runner must not run when artifacts present")):
            run_id, _ = self._runner.start_run(
                server.RunConfig(module="engine/utils"))

        output = self._read_output(run_id)
        self.assertIn("PREFLIGHT: skipped", output)
        # The test process was launched (single-invocation path).
        self.assertEqual(1, len(_FakePopen.launched))
        cmd, _ = _FakePopen.launched[0]
        self.assertIn("mvn", cmd)
        self.assertIn("test", cmd)
        meta = self._read_metadata(run_id)
        self.assertEqual("running", meta["status"])

    def test_seed_runs_when_artifact_missing_then_test_launches(self):
        """At least one AR jar missing: preflight runs, then test launches."""
        seed_calls = []

        def fake_runner(cmd, cwd, writer):
            seed_calls.append((list(cmd), Path(cwd)))
            if writer is not None:
                writer("[fake mvn] BUILD SUCCESS\n")
            # Simulate a successful install by creating the jar so a
            # subsequent missing-check would pass.
            _install_jar(self._m2, "ar-space", "0.74")
            return 0

        with patch.object(preflight, "_default_runner", fake_runner):
            run_id, _ = self._runner.start_run(
                server.RunConfig(module="engine/utils"))

        # Preflight ran exactly once with the expected command.
        self.assertEqual(1, len(seed_calls),
                         f"expected exactly one preflight install, got {seed_calls}")
        seed_cmd, seed_cwd = seed_calls[0]
        self.assertEqual(
            ["mvn", "-pl", "engine/utils", "-am", "install",
             "-DskipTests", "-B"],
            seed_cmd,
        )
        self.assertEqual(self._project_root.resolve(), seed_cwd.resolve())

        # Preflight banner and seed output are in output.txt; test launched.
        output = self._read_output(run_id)
        self.assertIn("PREFLIGHT: seeding 1 upstream artifact(s)", output)
        self.assertIn("PREFLIGHT: seeded 1 artifact(s)", output)
        self.assertIn("[fake mvn] BUILD SUCCESS", output)
        self.assertEqual(1, len(_FakePopen.launched))
        meta = self._read_metadata(run_id)
        self.assertEqual("running", meta["status"])

    def test_seed_failure_short_circuits_test_launch(self):
        """Failed preflight: run is marked failed, no test process spawned."""
        seed_calls = []

        def fake_runner(cmd, cwd, writer):
            seed_calls.append((list(cmd), Path(cwd)))
            if writer is not None:
                writer("[fake mvn] BUILD FAILURE: cannot resolve dependency\n")
            return 1

        with patch.object(preflight, "_default_runner", fake_runner):
            run_id, _ = self._runner.start_run(
                server.RunConfig(module="engine/utils"))

        # The seed was attempted.
        self.assertEqual(1, len(seed_calls))
        # No Maven test process was launched.
        self.assertEqual(0, len(_FakePopen.launched),
                         "test must not launch when preflight fails")

        meta = self._read_metadata(run_id)
        self.assertEqual("failed", meta["status"])
        self.assertEqual(1, meta["exit_code"])

        output = self._read_output(run_id)
        self.assertIn("PREFLIGHT: FAILED", output)
        self.assertIn("[fake mvn] BUILD FAILURE", output)

    def test_repetitions_path_also_runs_preflight_once(self):
        """Multi-invocation runs preflight before scheduling the loop."""
        seed_calls = []

        def fake_runner(cmd, cwd, writer):
            seed_calls.append(list(cmd))
            _install_jar(self._m2, "ar-space", "0.74")
            return 0

        with patch.object(preflight, "_default_runner", fake_runner):
            run_id, _ = self._runner.start_run(
                server.RunConfig(module="engine/utils", repetitions=5))

        # Preflight runs exactly once (not once per repetition).
        self.assertEqual(1, len(seed_calls))
        # The repetitions watcher thread was scheduled but, because Thread is
        # stubbed, no Maven invocations actually ran. The output file holds
        # the preflight banner.
        meta = self._read_metadata(run_id)
        self.assertEqual("running", meta["status"])
        self.assertEqual(5, meta["repetitions"])

    def test_module_without_ar_deps_skips_preflight(self):
        """Module with no org.almostrealism deps: preflight is skipped."""
        _write_module_pom(self._project_root, "leaf", [])

        with patch.object(preflight, "_default_runner",
                          side_effect=AssertionError(
                              "preflight runner must not run for a leaf module")):
            run_id, _ = self._runner.start_run(
                server.RunConfig(module="leaf"))

        output = self._read_output(run_id)
        self.assertIn("PREFLIGHT: skipped", output)
        self.assertEqual(1, len(_FakePopen.launched))


class _NoOpTimer:
    """Stand-in for ``threading.Timer`` that records but does not run callbacks."""

    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs

    def start(self):
        pass

    def cancel(self):
        pass


class _NoOpThread:
    """Stand-in for ``threading.Thread`` that does not start a real thread."""

    def __init__(self, *args, **kwargs):
        self.target = kwargs.get("target") or (args[0] if args else None)
        self.args = kwargs.get("args", ())

    def start(self):
        pass


class _StubSubprocess:
    """Drop-in replacement for the ``subprocess`` module attribute on server.

    Routes ``Popen`` to ``_FakePopen`` while preserving any other names the
    code under test may touch.
    """

    def __init__(self, popen_cls):
        self.Popen = popen_cls
        # Real subprocess module attributes preserved for any helper that
        # still references them.
        import subprocess as _real_subprocess
        self.PIPE = _real_subprocess.PIPE
        self.STDOUT = _real_subprocess.STDOUT
        self.DEVNULL = _real_subprocess.DEVNULL
        self.TimeoutExpired = _real_subprocess.TimeoutExpired
        self.SubprocessError = _real_subprocess.SubprocessError


if __name__ == "__main__":
    unittest.main()
