"""Guard: fetch-latest-coverage.sh honours ALLOW_RECOMPUTE=false.

The coverage-qa job runs on a GitHub-hosted runner, where the Java recompute
path (``mvn install`` plus the full ``mvn test`` suite) would take hours and
produce a report that does not match the self-hosted coverage lanes. The job
sets ``ALLOW_RECOMPUTE=false`` so a missing master artifact, or ``FORCE=true``,
fails fast instead. These tests run the real script with a fake ``mvn`` first
on PATH that records every invocation, so they prove whether Maven would have
been started without ever starting it.
"""

import os
import shutil
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "coverage", "fetch-latest-coverage.sh")

# Records its arguments and fails, so a permitted recompute stops right after
# the first Maven call instead of continuing into the rest of the script.
_FAKE_MVN = """#!/usr/bin/env bash
echo "mvn $*" >> "{log}"
exit 1
"""


class FetchLatestCoverageRecomputeGateTests(unittest.TestCase):
    """End-to-end runs of the Java recompute gate."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="fetch-latest-coverage-test-")
        self.bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin_dir)
        self.mvn_log = os.path.join(self.tmp, "mvn.log")
        mvn = os.path.join(self.bin_dir, "mvn")
        with open(mvn, "w") as f:
            f.write(_FAKE_MVN.format(log=self.mvn_log))
        os.chmod(mvn, 0o755)
        self.output_dir = os.path.join(self.tmp, "out")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, **overrides):
        """Runs the script with no GitHub credentials, so reuse always fails."""
        env = {
            "PATH": self.bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "HOME": self.tmp,
            "OUTPUT_DIR": self.output_dir,
        }
        env.update(overrides)
        return subprocess.run(["bash", _SCRIPT], cwd=self.tmp, env=env,
                              capture_output=True, text=True, timeout=60)

    def _mvn_calls(self):
        if not os.path.exists(self.mvn_log):
            return []
        with open(self.mvn_log) as f:
            return f.read().splitlines()

    def test_missing_artifact_fails_fast_when_recompute_is_disallowed(self):
        result = self._run(ALLOW_RECOMPUTE="false")
        self.assertEqual(1, result.returncode)
        self.assertEqual([], self._mvn_calls())
        self.assertIn("ALLOW_RECOMPUTE=false", result.stderr)
        self.assertIn("Build and Test", result.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.output_dir, "coverage.xml")))

    def test_force_fails_fast_when_recompute_is_disallowed(self):
        result = self._run(ALLOW_RECOMPUTE="false", FORCE="true")
        self.assertEqual(1, result.returncode)
        self.assertEqual([], self._mvn_calls())
        self.assertIn("FORCE=true", result.stderr)

    def test_missing_artifact_recomputes_by_default(self):
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        calls = self._mvn_calls()
        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0].startswith("mvn install -DskipTests"), calls[0])
        self.assertNotIn("ALLOW_RECOMPUTE=false", result.stderr)

    def test_only_the_literal_false_disables_recompute(self):
        result = self._run(ALLOW_RECOMPUTE="true", FORCE="true")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self._mvn_calls()))


if __name__ == "__main__":
    unittest.main()
