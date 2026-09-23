"""Guard: the coverage-qa venv must not silently keep using a stale interpreter.

``tools/ci/coverage/fetch-latest-coverage.sh`` used to check only that
``${VENV_DIR}/bin/python3`` existed before reusing a cached venv, never
whether the interpreter that created it (or ``tools/mcp/requirements.txt``)
had changed since. A venv provisioned once by an interpreter older than
Python 3.10 kept being reused forever even after a newer interpreter became
available on PATH, and pip's own ``Requires-Python`` filtering then made
every release of ``mcp`` look unavailable ("from versions: none").

``tools/ci/coverage/select-python-env.sh`` replaces the existence check with
a marker file recording the selected interpreter's path and version, a hash
of the requirements file, and a hash of the caller's extra pip package
arguments — any mismatch forces a full reprovision.

These tests run the real script against fake ``python3.NN`` interpreters (tiny
bash scripts) on an isolated PATH containing only symlinked coreutils, so no
real Python installation on the host — regardless of its version — can hide
the "no interpreter satisfies the minimum" or "stale interpreter" cases the
script exists to handle.
"""

import os
import shutil
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "coverage", "select-python-env.sh")

# Only what select-python-env.sh (and the fake interpreters it invokes)
# actually shell out to. Deliberately excludes any real python* binary, so
# the "no interpreter found" and "too old" cases are reproducible regardless
# of what the host running these tests has installed.
_COREUTILS = ["mkdir", "rm", "cp", "chmod", "cat", "dirname", "cut", "sha256sum", "env"]

_FAKE_PYTHON_TEMPLATE = """#!/usr/bin/env bash
set -euo pipefail
VERSION="{version}"
LOG="{log}"
if [ "$1" = "-c" ]; then
    echo "$VERSION"
    exit 0
fi
if [ "$1" = "-m" ] && [ "$2" = "venv" ]; then
    dir="$3"
    mkdir -p "$dir/bin"
    cp "$0" "$dir/bin/python3"
    chmod +x "$dir/bin/python3"
    exit 0
fi
if [ "$1" = "-m" ] && [ "$2" = "pip" ]; then
    shift 2
    echo "pip $*" >> "$LOG"
    exit 0
fi
echo "unsupported invocation: $*" >&2
exit 1
"""


class SelectPythonEnvTests(unittest.TestCase):
    """End-to-end runs of the interpreter-selection/venv-provisioning gate."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="select-python-env-test-")
        self.bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin_dir)
        for tool in _COREUTILS:
            real = shutil.which(tool)
            self.assertIsNotNone(real, "required coreutil not found: %s" % tool)
            os.symlink(real, os.path.join(self.bin_dir, tool))
        # bash itself must come from the real PATH so the subprocess and any
        # fake interpreter scripts it invokes can actually run.
        os.symlink(shutil.which("bash"), os.path.join(self.bin_dir, "bash"))
        os.symlink(shutil.which("sh"), os.path.join(self.bin_dir, "sh"))

        self.requirements = os.path.join(self.tmp, "requirements.txt")
        self._write_requirements("mcp>=1.0.0,<2\n")

        self.venv_dir = os.path.join(self.tmp, "venv")
        self.pip_log = os.path.join(self.tmp, "pip.log")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_requirements(self, content):
        with open(self.requirements, "w") as f:
            f.write(content)

    def _make_fake_python(self, name, version, log=None):
        """Installs a fake interpreter named ``name`` reporting ``version``."""
        path = os.path.join(self.bin_dir, name)
        with open(path, "w") as f:
            f.write(_FAKE_PYTHON_TEMPLATE.format(version=version, log=log or self.pip_log))
        os.chmod(path, 0o755)

    def _run(self, extra_args=(), min_version=None, venv_dir=None):
        env = {
            "PATH": self.bin_dir,
            "HOME": self.tmp,
            "REQUIREMENTS_FILE": self.requirements,
            "VENV_DIR": venv_dir or self.venv_dir,
        }
        if min_version is not None:
            env["MIN_PYTHON_VERSION"] = min_version
        return subprocess.run(
            ["bash", _SCRIPT] + list(extra_args),
            env=env, capture_output=True, text=True,
        )

    def _pip_log_lines(self):
        if not os.path.exists(self.pip_log):
            return []
        with open(self.pip_log) as f:
            return f.read().splitlines()

    def test_selects_newest_available_interpreter(self):
        self._make_fake_python("python3.10", "3.10")
        self._make_fake_python("python3.11", "3.11")
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(os.path.join(self.venv_dir, "bin", "python3"), result.stdout.strip())
        pip_lines = self._pip_log_lines()
        self.assertTrue(any("-r " + self.requirements in line for line in pip_lines), pip_lines)

    def test_rejects_an_interpreter_below_the_minimum(self):
        # Only a bare `python3` is on PATH, and it resolves to something
        # below the minimum — exactly the "OS-bundled Python 3.9" scenario.
        self._make_fake_python("python3", "3.9")
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("3.10", result.stderr)

    def test_no_interpreter_found(self):
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("::error::", result.stderr)

    def test_stdout_contains_only_the_venv_path(self):
        self._make_fake_python("python3.10", "3.10")
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(1, len(lines), result.stdout)
        self.assertEqual(os.path.join(self.venv_dir, "bin", "python3"), lines[0])
        self.assertIn("::notice::", result.stderr)

    def test_venv_is_reused_when_nothing_changed(self):
        self._make_fake_python("python3.10", "3.10")
        first = self._run()
        self.assertEqual(0, first.returncode, first.stderr)
        first_install_count = len(self._pip_log_lines())

        second = self._run()
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertIn("Reusing", second.stderr)
        self.assertEqual(first_install_count, len(self._pip_log_lines()))

    def test_venv_is_recreated_when_the_interpreter_changes(self):
        self._make_fake_python("python3.10", "3.10")
        first = self._run()
        self.assertEqual(0, first.returncode, first.stderr)
        first_install_count = len(self._pip_log_lines())

        os.remove(os.path.join(self.bin_dir, "python3.10"))
        self._make_fake_python("python3.11", "3.11")
        second = self._run()
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertIn("Recreating", second.stderr)
        self.assertGreater(len(self._pip_log_lines()), first_install_count)

    def test_venv_is_recreated_when_requirements_change(self):
        self._make_fake_python("python3.10", "3.10")
        first = self._run()
        self.assertEqual(0, first.returncode, first.stderr)
        first_install_count = len(self._pip_log_lines())

        self._write_requirements("mcp>=1.0.0,<2\nfastembed>=0.2.0\n")
        second = self._run()
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertIn("Recreating", second.stderr)
        self.assertGreater(len(self._pip_log_lines()), first_install_count)

    def test_venv_is_recreated_when_extra_packages_change(self):
        # Reviewer follow-up: the marker must also cover the caller's extra
        # pip-package list, not just the requirements file and interpreter,
        # or a caller adding a package would see it silently never installed
        # into an already-cached venv.
        self._make_fake_python("python3.10", "3.10")
        first = self._run(extra_args=["pyyaml"])
        self.assertEqual(0, first.returncode, first.stderr)
        first_install_count = len(self._pip_log_lines())

        second = self._run(extra_args=["pyyaml", "coverage"])
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertIn("Recreating", second.stderr)
        self.assertGreater(len(self._pip_log_lines()), first_install_count)

    def test_missing_requirements_file_is_an_error(self):
        os.remove(self.requirements)
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("::error::", result.stderr)

    def test_custom_minimum_version_is_honored(self):
        self._make_fake_python("python3.11", "3.11")
        result = self._run(min_version="3.12")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("3.12", result.stderr)


if __name__ == "__main__":
    unittest.main()
