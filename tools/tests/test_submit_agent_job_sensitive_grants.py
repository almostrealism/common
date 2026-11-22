"""submit-agent-job.sh: the sensitive-file operator grants reach the payload.

A branch whose own work is in a protected file needs its remediation job
launched with the operator grants that let the agent edit that file:
``sensitiveFileProtectionEnabled=false`` (lifts the harness CI/test staging
locks and signs the bypass trailer) and ``bypassAgentPermissionPrompts=true``
(lets the agent write under ``.claude/``). The script forwards each only in the
direction that lifts a protection, so the controller's fully-protected default
holds whenever the knob is unset or left at its protecting value.
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


class SubmitAgentJobSensitiveGrantsTest(unittest.TestCase):
    """Runs the script against the fake curl and inspects the request body."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="sensitive-grant-submit-")
        self.addCleanup(self.dir.cleanup)
        bin_curl = os.path.join(self.dir.name, "curl")
        with open(bin_curl, "w") as f:
            f.write(_FAKE_CURL)
        os.chmod(bin_curl, os.stat(bin_curl).st_mode | stat.S_IXUSR)
        self.prompt = os.path.join(self.dir.name, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("fix the findings")
        self.out = os.path.join(self.dir.name, "payload.json")

    def submit(self, **knobs):
        env = {k: v for k, v in os.environ.items()
               if k not in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                            "BYPASS_AGENT_PERMISSION_PROMPTS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   PAYLOAD_OUT=self.out, BRANCH="qa/retro-x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid", **knobs)
        return subprocess.run(["bash", _SCRIPT, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def body(self):
        with open(self.out) as f:
            return json.load(f)

    def test_the_grants_reach_the_payload_when_set(self):
        result = self.submit(SENSITIVE_FILE_PROTECTION_ENABLED="false",
                             BYPASS_AGENT_PERMISSION_PROMPTS="true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIs(False, self.body()["sensitiveFileProtectionEnabled"])
        self.assertIs(True, self.body()["bypassAgentPermissionPrompts"])

    def test_unset_grants_leave_the_controller_default(self):
        result = self.submit()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("sensitiveFileProtectionEnabled", self.body())
        self.assertNotIn("bypassAgentPermissionPrompts", self.body())

    def test_a_grant_is_sent_only_in_the_lifting_direction(self):
        # The protecting value of each knob is the controller default, so the
        # script never sends it — it would be a no-op field at best and, for
        # bypassAgentPermissionPrompts=false, could read as an explicit refusal.
        result = self.submit(SENSITIVE_FILE_PROTECTION_ENABLED="true",
                             BYPASS_AGENT_PERMISSION_PROMPTS="false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("sensitiveFileProtectionEnabled", self.body())
        self.assertNotIn("bypassAgentPermissionPrompts", self.body())

    def test_a_non_boolean_grant_submits_nothing(self):
        for var in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                    "BYPASS_AGENT_PERMISSION_PROMPTS"):
            with self.subTest(var=var):
                result = self.submit(**{var: "maybe"})
                self.assertEqual(1, result.returncode)
                self.assertIn("must be true or false", result.stderr)
                self.assertFalse(os.path.exists(self.out))


if __name__ == "__main__":
    unittest.main()
