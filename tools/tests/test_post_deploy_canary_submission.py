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

    def test_marker_path_is_outside_the_ci_file_lock(self):
        """The marker must not live under tools/ci/ (or any guarded path): the
        canary runs on the non-ci/ agent-canary branch, where the harness drops
        a tools/ci/** write under the CI file lock. The old path made every run
        end DEGRADED, unable to commit the marker."""
        match = re.search(r'(?m)^CANARY_FILE="\$\{CANARY_FILE:-([^}]+)\}"', self.script)
        self.assertIsNotNone(match, "CANARY_FILE must have an overridable default")
        path = match.group(1)
        self.assertFalse(path.startswith("tools/ci/"),
                         "marker must not be under the CI file lock: " + path)
        self.assertNotRegex(path, r"(^|/)(src/test|src/it)/")
        self.assertFalse(path.startswith(".github/"))
        self.assertFalse(path.startswith(".claude/"))
        self.assertFalse(path.startswith(".flowtree/"))

    def _run_with_marker(self, marker):
        """Runs the canary with a bogus subcommand so the marker guard — which
        runs at load time, before the subcommand is dispatched — decides the
        outcome without any network, agent, or git activity."""
        env = dict(os.environ, CANARY_FILE=marker)
        return subprocess.run(["bash", _CANARY, "__noop__"], env=env,
                              capture_output=True, text=True, timeout=60)

    def test_override_under_a_guarded_root_is_rejected(self):
        """An override that points the marker under a guarded root must fail
        loudly and name the cause, not masquerade as a fleet failure. Covers
        each guarded root the harness drops at staging on the non-ci/ branch."""
        for marker in ("tools/ci/agent-e2e/canary/last-run.txt",
                       ".github/canary.txt",
                       ".claude/canary.txt",
                       ".flowtree/canary.txt",
                       "engine/ml/.flowtree/canary.txt",
                       "src/test/canary.txt",
                       "flowtree/runtime/src/test/canary.txt",
                       "src/it/canary.txt"):
            with self.subTest(marker=marker):
                result = self._run_with_marker(marker)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("guarded path", result.stderr)

    def test_non_canonical_override_is_rejected(self):
        """A spelling git would canonicalize into a guarded path (or any other
        non-canonical form) must be rejected before the guarded-root check,
        which compares spellings and would otherwise let it through."""
        for marker in ("./tools/ci/agent-e2e/canary/last-run.txt",
                       "tools/../tools/ci/canary.txt",
                       "tools/./ci/canary.txt",
                       "tools//ci/canary.txt",
                       "/tmp/canary.txt",
                       "../canary.txt",
                       "tools/canary/",
                       "tools/canary/.."):
            with self.subTest(marker=marker):
                result = self._run_with_marker(marker)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("not a canonical repository-relative path", result.stderr)

    def test_dotted_file_name_is_not_mistaken_for_a_dot_segment(self):
        """Only whole '.'/'..' segments are non-canonical; a name that merely
        contains or starts with dots is an ordinary path and clears the guard."""
        for marker in ("tools/canary/..last-run.txt", "tools/canary/.last-run"):
            with self.subTest(marker=marker):
                result = self._run_with_marker(marker)
                self.assertEqual(1, result.returncode)
                self.assertIn("Usage", result.stderr)
                self.assertNotIn("canonical", result.stderr)
                self.assertNotIn("guarded path", result.stderr)

    def test_override_outside_every_guarded_root_passes_the_guard(self):
        """A marker outside every guarded root clears the guard and reaches the
        subcommand dispatch (which rejects the bogus subcommand with usage),
        proving the guard does not reject ordinary paths."""
        result = self._run_with_marker("tools/canary/last-run.txt")
        self.assertEqual(1, result.returncode)
        self.assertIn("Usage", result.stderr)
        self.assertNotIn("guarded path", result.stderr)

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
