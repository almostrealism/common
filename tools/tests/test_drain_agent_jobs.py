"""Tests for tools/ci/drain-agent-jobs.sh.

The deploy drain once polled a ``GET /api/jobs`` route the controller never
had, so every probe failed, counted zero, and the deploy restarted the stack
into running jobs. These tests run the script with a stub ``curl`` on ``PATH``
that serves a fixed ``/api/jobs/in-flight`` body (and fails for any other
URL), so the script blocks only when it reads the route the controller
actually serves.
"""

import os
import shutil
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "drain-agent-jobs.sh")

_STUB_CURL = """#!/usr/bin/env bash
for arg in "$@"; do url="$arg"; done
case "$url" in
  */api/jobs/in-flight) cat "$STUB_BODY_FILE" ;;
  *) exit 22 ;;
esac
"""


class DrainAgentJobsTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        curl = os.path.join(self.tmp, "curl")
        with open(curl, "w") as f:
            f.write(_STUB_CURL)
        os.chmod(curl, 0o755)
        self.body = os.path.join(self.tmp, "body.json")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def _drain(self, body):
        with open(self.body, "w") as f:
            f.write(body)
        env = dict(os.environ,
                   PATH=self.tmp + os.pathsep + os.environ["PATH"],
                   STUB_BODY_FILE=self.body,
                   CONTROLLER_URL="http://controller.invalid",
                   DRAIN_TIMEOUT_SECONDS="0",
                   DRAIN_POLL_SECONDS="0")
        return subprocess.run(["bash", _SCRIPT], env=env,
                              capture_output=True, text=True, timeout=30)

    def test_in_flight_jobs_block_the_deploy(self):
        r = self._drain('{"active":1,"pending":2,"total":3}')
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("3 agent job(s) still running", r.stdout)

    def test_pending_jobs_alone_block_the_deploy(self):
        r = self._drain('{"active":0,"pending":1,"total":1}')
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)

    def test_no_in_flight_jobs_drains(self):
        r = self._drain('{"active":0,"pending":0,"total":0}')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("No in-flight agent jobs", r.stdout)

    def test_unparseable_body_counts_as_zero(self):
        r = self._drain("<html>not json</html>")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
