"""submit-agent-job.sh: the fallback phase config reaches the payload, and is
purely additive.

CI forwards a fallback phase config — a cheaper runner/model for the phases a
workstream leaves unset — from the ``AR_CI_FALLBACK_PHASE_CONFIG`` repository
variable through ``FALLBACK_PHASE_CONFIG``. The job the pipeline submits in a
loop (auto-review, auto-resolve, auto-resolve-python) carries it so an
unconfigured workstream can run on an inexpensive setup without overriding one
an owner configured on purpose.

The key property these tests pin is that the knob is strictly additive: with
``FALLBACK_PHASE_CONFIG``/``FALLBACK_PHASE_CONFIGS`` unset, the submitted payload
is byte-for-byte what it is today; setting one adds only the matching
``fallbackPhaseConfig`` / ``fallbackPhaseConfigs`` key and changes nothing else.
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


class SubmitAgentJobFallbackPhaseConfigTest(unittest.TestCase):
    """Runs the script against the fake curl and inspects the request body."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="fallback-phase-submit-")
        self.addCleanup(self.dir.cleanup)
        bin_curl = os.path.join(self.dir.name, "curl")
        with open(bin_curl, "w") as f:
            f.write(_FAKE_CURL)
        os.chmod(bin_curl, os.stat(bin_curl).st_mode | stat.S_IXUSR)
        self.prompt = os.path.join(self.dir.name, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("resolve the findings")
        self.out = os.path.join(self.dir.name, "payload.json")

    def submit(self, **knobs):
        env = {k: v for k, v in os.environ.items()
               if k not in ("FALLBACK_PHASE_CONFIG", "FALLBACK_PHASE_CONFIGS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   PAYLOAD_OUT=self.out, BRANCH="ci/x", BASE_BRANCH="master",
                   CONTROLLER_URL="http://controller.invalid", **knobs)
        return subprocess.run(["bash", _SCRIPT, self.prompt], env=env,
                              capture_output=True, text=True, timeout=60)

    def body(self):
        with open(self.out) as f:
            return json.load(f)

    def test_unset_sends_no_fallback_and_payload_is_unchanged(self):
        result = self.submit()
        self.assertEqual(0, result.returncode, result.stderr)
        body = self.body()
        self.assertNotIn("fallbackPhaseConfig", body)
        self.assertNotIn("fallbackPhaseConfigs", body)

    def test_fallback_default_reaches_the_payload(self):
        fallback = {"runner": "opencode", "model": "z-ai/glm-4.6",
                    "provider": "openrouter"}
        baseline = self._baseline()
        result = self.submit(FALLBACK_PHASE_CONFIG=json.dumps(fallback))
        self.assertEqual(0, result.returncode, result.stderr)
        body = self.body()
        self.assertEqual(fallback, body["fallbackPhaseConfig"])
        # Purely additive: everything but the new key matches the baseline.
        self.assertEqual(baseline, {k: v for k, v in body.items()
                                    if k != "fallbackPhaseConfig"})

    def test_fallback_per_phase_reaches_the_payload(self):
        fallback = {"review": {"runner": "opencode", "provider": "openrouter"}}
        baseline = self._baseline()
        result = self.submit(FALLBACK_PHASE_CONFIGS=json.dumps(fallback))
        self.assertEqual(0, result.returncode, result.stderr)
        body = self.body()
        self.assertEqual(fallback, body["fallbackPhaseConfigs"])
        self.assertEqual(baseline, {k: v for k, v in body.items()
                                    if k != "fallbackPhaseConfigs"})

    def test_malformed_fallback_fails_loudly(self):
        result = self.submit(FALLBACK_PHASE_CONFIG="{not json}")
        self.assertNotEqual(0, result.returncode)

    def _baseline(self):
        """The payload with no fallback — the current, unchanged submission."""
        self.submit()
        return self.body()


if __name__ == "__main__":
    unittest.main()
