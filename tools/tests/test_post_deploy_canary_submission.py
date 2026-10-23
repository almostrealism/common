"""Guard: the post-deploy canary submits a lean job and can be told apart in the queue.

The canary (``tools/ci/agent-e2e/post-deploy-canary.sh``) asks the deployed
fleet for a two-line file write and checks the remote for it. Two things it
depends on live in ``submit-agent-job.sh`` and are pinned here:

- ``REVIEW_ENABLED`` turns the review phase off. It is sent only when set, so
  every other submitter keeps the controller's default (on). The canary sets
  it, and ``OBJECT_ORIENTED_REVIEW_ENABLED``, to ``false``: each extra phase is
  another agent session that can stall for reasons unrelated to whether the
  fleet publishes.
- ``workstream_id=`` is printed beside ``job_id=``. The canary needs it to ask
  the controller's pending view whether a job that has not finished is still
  queued — reported as UNVERIFIED — rather than lost or failed.
"""
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SUBMIT_AGENT_JOB = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")
_CANARY = os.path.join(_REPO_ROOT, "tools", "ci", "agent-e2e", "post-deploy-canary.sh")


class CanarySubmissionSettingsTest(unittest.TestCase):
    """Reads the canary script's own submission settings."""

    def setUp(self):
        with open(_CANARY) as f:
            self.script = f.read()

    def test_review_phases_are_off(self):
        self.assertRegex(self.script, r"(?m)^export REVIEW_ENABLED=false$")
        self.assertRegex(self.script, r"(?m)^export OBJECT_ORIENTED_REVIEW_ENABLED=false$")

    def test_waits_an_hour_by_default(self):
        self.assertIn('CANARY_TIMEOUT_SECONDS="${CANARY_TIMEOUT_SECONDS:-3600}"', self.script)

    def test_still_queued_has_its_own_exit_status(self):
        """Exit 3 and outcome=queued, distinct from the exit 1 of every failure."""
        queued = re.search(r"still-queued\)\n(.*?);;", self.script, re.S)
        self.assertIsNotNone(queued, "the verdict must have a still-queued arm")
        self.assertIn("report_outcome queued", queued.group(1))
        self.assertIn("exit 3", queued.group(1))
        self.assertNotIn("die ", queued.group(1))


class SubmitAgentJobReviewAndWorkstreamTest(unittest.TestCase):
    """Runs submit-agent-job.sh against a stub ``curl`` and reads what it sends and prints."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="canary-submit-test-")
        self.payload = os.path.join(self.tmp, "payload.json")
        self.prompt = os.path.join(self.tmp, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("prompt")
        curl = os.path.join(self.tmp, "curl")
        with open(curl, "w") as f:
            f.write("""#!/usr/bin/env bash
while [ $# -gt 0 ]; do
  if [ "$1" = "-d" ]; then printf '%%s' "$2" > "%s"; shift; fi
  shift
done
printf '{"ok":true,"jobId":"j1","workstreamId":"ws-7"}\\n200'
""" % self.payload)
        os.chmod(curl, os.stat(curl).st_mode | stat.S_IXUSR)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, review):
        env = {k: v for k, v in os.environ.items() if k != "REVIEW_ENABLED"}
        env.update(PATH=self.tmp + os.pathsep + env.get("PATH", ""),
                   BRANCH="feature/x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid")
        if review is not None:
            env["REVIEW_ENABLED"] = review
        return subprocess.run(["bash", _SUBMIT_AGENT_JOB, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def _sent(self):
        with open(self.payload) as f:
            return json.load(f)

    def test_unset_knob_omits_the_field(self):
        result = self._run(None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("reviewEnabled", self._sent())

    def test_false_is_sent_as_a_json_boolean(self):
        result = self._run("false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(False, self._sent()["reviewEnabled"])

    def test_an_invalid_value_submits_nothing(self):
        result = self._run("no")
        self.assertEqual(1, result.returncode)
        self.assertIn("REVIEW_ENABLED must be true or false", result.stderr)
        self.assertFalse(os.path.exists(self.payload))

    def test_prints_the_workstream_beside_the_job(self):
        result = self._run(None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("job_id=j1\n", result.stdout)
        self.assertIn("workstream_id=ws-7\n", result.stdout)


if __name__ == "__main__":
    unittest.main()
