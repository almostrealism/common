"""Every branch's workstream must carry the settled-CI trigger.

A pull request cannot merge until a test run has passed on its head, and a
test run starts only when something deploys the branch to the ``test``
environment. The controller does that when a job on the branch's workstream
ends with nothing to commit, but only for a workstream whose
``settledCiTrigger`` is set. A workstream without it leaves its pull request
waiting for tests that never start.

These tests pin the three places that give a workstream the trigger:
``register-workstream.sh`` sends it on registration and re-applies it to a
workstream that already exists (one created some other way);
``submit-agent-job.sh`` sends it for a workstream its submission creates; and
``analysis.yaml`` sets it on the registration step and on both early submit
steps, which are what create the workstream of a docs-only branch.
"""

import os
import subprocess
import sys
import tempfile
import unittest

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_task_planning_gates import _ControllerStub, _run  # noqa: E402

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_SUBMIT = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")
_TRIGGER = "deployment:test"


class RegisterWorkstreamTriggerTests(unittest.TestCase):
    """register-workstream.sh carries the trigger to new and existing workstreams."""

    def _register(self, stub, trigger):
        self.addCleanup(stub.close)
        return _run("register-workstream.sh", {
            "BRANCH": "feature/x", "BASE_BRANCH": "master",
            "CONTROLLER_URL": stub.url, "SETTLED_CI_TRIGGER": trigger,
            "REQUIRED_LABELS_JSON": "", "TRACKER_CAPABILITIES": "",
            "DEPENDENT_REPOS": "", "PLAN_FILE": ""})

    def test_the_trigger_is_sent_with_the_registration(self):
        stub = _ControllerStub()
        code, _, log = self._register(stub, _TRIGGER)
        self.assertEqual(0, code, log)
        self.assertEqual(_TRIGGER, stub.seen[0][4]["settledCiTrigger"])
        self.assertEqual(1, len(stub.seen), "a new workstream needs no update")

    def test_an_existing_workstream_is_updated_with_the_trigger(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub, _TRIGGER)
        self.assertEqual(0, code, log)
        self.assertEqual(2, len(stub.seen), log)
        self.assertTrue(stub.seen[1][1].endswith("/ws-1/update"))
        self.assertEqual({"settledCiTrigger": _TRIGGER}, stub.seen[1][4])

    def test_a_failed_trigger_update_fails_the_registration(self):
        stub = _ControllerStub(existing=True, update=(503, {"ok": False}))
        code, _, log = self._register(stub, _TRIGGER)
        self.assertEqual(1, code, log)

    def test_no_trigger_sends_no_trigger(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub, "")
        self.assertEqual(0, code, log)
        self.assertNotIn("settledCiTrigger", stub.seen[0][4])
        self.assertEqual(1, len(stub.seen), "nothing to update")


class SubmitAgentJobTriggerTests(unittest.TestCase):
    """submit-agent-job.sh sends the trigger for a workstream it creates."""

    def _submit(self, trigger):
        stub = _ControllerStub()
        self.addCleanup(stub.close)
        with tempfile.TemporaryDirectory() as tmp:
            prompt = os.path.join(tmp, "prompt.txt")
            with open(prompt, "w") as f:
                f.write("Do something")
            env = dict(os.environ)
            env.pop("GITHUB_OUTPUT", None)
            env.update({"BRANCH": "feature/x", "BASE_BRANCH": "master",
                        "CONTROLLER_URL": stub.url, "SETTLED_CI_TRIGGER": trigger,
                        "REPO_URL": "git@github.com:example/repo.git"})
            subprocess.run(["bash", _SUBMIT, prompt], env=env,
                           capture_output=True, text=True, timeout=60)
        submits = [s for s in stub.seen if s[1] == "/api/submit"]
        self.assertEqual(1, len(submits), stub.seen)
        return submits[0][4]

    def test_the_trigger_is_sent_with_the_submission(self):
        self.assertEqual(_TRIGGER, self._submit(_TRIGGER)["settledCiTrigger"])

    def test_no_trigger_sends_no_trigger(self):
        self.assertNotIn("settledCiTrigger", self._submit(""))


class AnalysisWiringTests(unittest.TestCase):
    """analysis.yaml gives the trigger to every step that can create a workstream."""

    @classmethod
    def setUpClass(cls):
        with open(_WORKFLOW) as f:
            cls.jobs = yaml.safe_load(f)["jobs"]

    def _env(self, job, step_name):
        for step in self.jobs[job]["steps"]:
            if step.get("name") == step_name:
                return step.get("env", {})
        self.fail("no step %r in job %r" % (step_name, job))

    def assertSetsTrigger(self, job, step_name):
        value = self._env(job, step_name).get("SETTLED_CI_TRIGGER", "")
        self.assertIn("'%s'" % _TRIGGER, value, (job, step_name))

    def test_early_registration_sets_the_trigger(self):
        self.assertSetsTrigger("register-workstream", "Register workstream")

    def test_the_early_submissions_set_the_trigger(self):
        self.assertSetsTrigger("auto-review-submit", "Submit agent job")
        self.assertSetsTrigger("auto-resolve-python-submit", "Submit agent job")


if __name__ == "__main__":
    unittest.main()
