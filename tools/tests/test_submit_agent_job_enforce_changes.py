"""submit-agent-job.sh: the enforceChanges re-run cap reaches the payload.

``MAX_ENFORCE_CHANGES_RETRIES`` caps how many times an ``ENFORCE_CHANGES`` job
is re-run when a run left no change; once the cap is spent with nothing
committed the controller reports the job DEGRADED. 0 is a meaningful value
(never re-run), so the field is sent whenever the knob is set, including to
0, and omitted only when the knob is unset, leaving the controller default.
"""
import json
import os
import stat
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")

# A curl stand-in: records the -d body and answers as a controller would.
_FAKE_CURL = """#!/usr/bin/env bash
body=""
prev=""
for arg in "$@"; do
  [ "$prev" = "-d" ] && body="$arg"
  prev="$arg"
done
printf '%s' "$body" > "$PAYLOAD_OUT"
printf '{"ok":true,"jobId":"j1"}\\n200'
"""


class SubmitAgentJobEnforceChangesTest(unittest.TestCase):
    """Runs the script against the fake curl and inspects the request body."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="enforce-cap-submit-")
        self.addCleanup(self.dir.cleanup)
        bin_curl = os.path.join(self.dir.name, "curl")
        with open(bin_curl, "w") as f:
            f.write(_FAKE_CURL)
        os.chmod(bin_curl, os.stat(bin_curl).st_mode | stat.S_IXUSR)
        self.prompt = os.path.join(self.dir.name, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("write the report")
        self.out = os.path.join(self.dir.name, "payload.json")

    def submit(self, **knobs):
        env = {k: v for k, v in os.environ.items()
               if k not in ("ENFORCE_CHANGES", "MAX_ENFORCE_CHANGES_RETRIES")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   PAYLOAD_OUT=self.out, BRANCH="qa/retro-x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid", **knobs)
        return subprocess.run(["bash", _SCRIPT, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def body(self):
        with open(self.out) as f:
            return json.load(f)

    def test_a_cap_of_zero_is_sent_with_enforce_changes(self):
        result = self.submit(ENFORCE_CHANGES="true", MAX_ENFORCE_CHANGES_RETRIES="0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(True, self.body()["enforceChanges"])
        self.assertEqual(0, self.body()["maxEnforceChangesRetries"])

    def test_a_positive_cap_is_sent_as_a_number(self):
        result = self.submit(ENFORCE_CHANGES="true", MAX_ENFORCE_CHANGES_RETRIES="2")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, self.body()["maxEnforceChangesRetries"])

    def test_an_unset_cap_leaves_the_controller_default(self):
        result = self.submit(ENFORCE_CHANGES="true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("maxEnforceChangesRetries", self.body())

    def test_a_cap_that_is_not_a_non_negative_integer_submits_nothing(self):
        for value in ("-1", "one", "1.5"):
            with self.subTest(value=value):
                result = self.submit(ENFORCE_CHANGES="true", MAX_ENFORCE_CHANGES_RETRIES=value)
                self.assertEqual(1, result.returncode)
                self.assertIn("MAX_ENFORCE_CHANGES_RETRIES must be a non-negative integer",
                              result.stderr)
                self.assertFalse(os.path.exists(self.out))


if __name__ == "__main__":
    unittest.main()
