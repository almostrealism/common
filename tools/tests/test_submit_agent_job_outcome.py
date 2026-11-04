"""submit-agent-job.sh: the `submitted` output distinguishes a job from a non-job.

A skip and a rejection both exit 0, so a GitHub Actions caller cannot tell them
from a real submission by the step's success alone. The script writes
``submitted=true`` to ``$GITHUB_OUTPUT`` only when the controller created a job,
and ``submitted=false`` on the two exit-0 paths that create none (the controller
skipping the submission, or rejecting it because automated jobs are off), so the
round's retire step can archive a workstream that never got a job.
"""
import os
import stat
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")

# A curl stand-in that answers with the body in $FAKE_BODY and HTTP 200.
_FAKE_CURL = """#!/usr/bin/env bash
printf '%s' "$FAKE_BODY"
printf '\\n200'
"""


class SubmitAgentJobOutcomeTest(unittest.TestCase):
    """Runs the script against a scripted controller response and reads the output."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="submit-outcome-")
        self.addCleanup(self.dir.cleanup)
        bin_curl = os.path.join(self.dir.name, "curl")
        with open(bin_curl, "w") as f:
            f.write(_FAKE_CURL)
        os.chmod(bin_curl, os.stat(bin_curl).st_mode | stat.S_IXUSR)
        self.prompt = os.path.join(self.dir.name, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("do the work")
        self.output = os.path.join(self.dir.name, "github_output")
        self.summary = os.path.join(self.dir.name, "step_summary")

    def submit(self, body):
        env = {k: v for k, v in os.environ.items()}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   FAKE_BODY=body, BRANCH="qa/defect-x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid",
                   GITHUB_OUTPUT=self.output, GITHUB_STEP_SUMMARY=self.summary)
        return subprocess.run(["bash", _SCRIPT, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def outputs(self):
        if not os.path.exists(self.output):
            return []
        with open(self.output) as f:
            return [line.strip() for line in f if line.strip()]

    def test_a_created_job_reports_submitted_true(self):
        result = self.submit('{"ok":true,"jobId":"j1","workstreamId":"w1"}')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("submitted=true", self.outputs())

    def test_a_skipped_submission_reports_submitted_false(self):
        result = self.submit('{"skipped":true,"reason":"a newer job exists"}')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("submitted=false", self.outputs())
        self.assertNotIn("submitted=true", self.outputs())

    def test_a_rejected_automated_submission_reports_submitted_false(self):
        result = self.submit('{"automated":true,"ok":false}')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("submitted=false", self.outputs())
        self.assertNotIn("submitted=true", self.outputs())

    def test_a_no_agents_response_reports_submitted_false(self):
        # The controller answers HTTP 200 {"ok":false,"error":"No agents
        # connected"} when no worker is connected. That is neither a skip nor an
        # automated rejection, so the script must not mistake it for a created
        # job: it reports submitted=false and fails the step so the caller
        # retires the workstream it just registered.
        result = self.submit('{"ok":false,"error":"No agents connected"}')
        self.assertNotEqual(0, result.returncode, result.stderr)
        self.assertIn("submitted=false", self.outputs())
        self.assertNotIn("submitted=true", self.outputs())

    def test_a_200_without_a_job_id_reports_submitted_false(self):
        # A 200 that carries ok:true but no jobId still created nothing; the
        # script must not emit submitted=true on the strength of the status code
        # alone.
        result = self.submit('{"ok":true}')
        self.assertNotEqual(0, result.returncode, result.stderr)
        self.assertIn("submitted=false", self.outputs())
        self.assertNotIn("submitted=true", self.outputs())


if __name__ == "__main__":
    unittest.main()
