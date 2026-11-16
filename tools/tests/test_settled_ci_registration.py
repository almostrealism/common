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
steps, which are what create the workstream of a docs-only branch. Every other
workflow that registers a workstream or submits a job (the QA rounds, task
planning, plan implementation, goal decomposition, plan review) sets it on
those steps too, so no branch an automated workflow starts waits for a test
run that nobody will start.

The submission half reaches the controller two ways: a step may run
``submit-agent-job.sh`` directly, or through the trusted
``submit-staged-request.sh`` wrapper (the staged auto-resolve/auto-review and
plan-implementation submit paths). The wrapper leaves ``CREATE_WORKSTREAM`` at
``submit-agent-job.sh``'s default of true and forwards ``SETTLED_CI_TRIGGER``
from its own environment, so both invocation forms can be the first to create a
workstream and ``EveryWorkflowWiringTests`` checks both.
"""

import glob
import os
import re
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
_WORKFLOWS = os.path.join(_REPO_ROOT, ".github", "workflows")
_TRIGGER = "deployment:test"
# A step that runs one of the scripts able to create a workstream, as opposed
# to one that only names the script (an integrity check, a chmod, a comment).
# submit-staged-request.sh is a trusted wrapper around submit-agent-job.sh: it
# forwards SETTLED_CI_TRIGGER from its own environment (not from the untrusted
# staged submit.env) and leaves CREATE_WORKSTREAM at submit-agent-job.sh's
# default of true, so a staged submission can be the first to create a branch's
# workstream and must carry the trigger just like a direct invocation.
# The invocation must be the command itself: at the start of a line or after a
# shell operator, so ``chmod +x ./tools/ci/...`` or ``[ -f ./tools/ci/... ]``
# (which only name the script as an argument) are not counted.
# TODO(review): forms like ``if bash ./tools/ci/...``, ``then ./tools/ci/...``, ``bash -x ...`` or ``"$DIR"/tools/ci/...`` are not detected.
_CREATES_WORKSTREAM = re.compile(
    r"(?:^|[;&|(])\s*(?:bash\s+|\./)\S*tools/ci/"
    r"(?:register-workstream|submit-agent-job|submit-staged-request)\.sh",
    re.MULTILINE)


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


class EveryWorkflowWiringTests(unittest.TestCase):
    """Every workflow step that can create a workstream gives it the trigger."""

    def _creating_steps(self):
        """Return (file, job, step name, effective env) for each creating step."""
        found = []
        for path in sorted(glob.glob(os.path.join(_WORKFLOWS, "*.y*ml"))):
            with open(path) as f:
                workflow = yaml.safe_load(f) or {}
            workflow_env = workflow.get("env") or {}
            for job_name, job in (workflow.get("jobs") or {}).items():
                job_env = job.get("env") or {}
                for step in job.get("steps") or []:
                    if _CREATES_WORKSTREAM.search(step.get("run") or ""):
                        env = dict(workflow_env)
                        env.update(job_env)
                        env.update(step.get("env") or {})
                        found.append((os.path.basename(path), job_name,
                                      step.get("name"), env))
        return found

    def test_the_known_creating_workflows_are_found(self):
        files = {step[0] for step in self._creating_steps()}
        for expected in ("analysis.yaml", "master-agent-dispatch.yaml", "qa-round.yaml",
                         "task-planning.yaml", "implement-plan.yaml",
                         "goal-decomposition.yaml", "plan-review.yaml",
                         "auto-resolve-submit.yaml"):
            self.assertIn(expected, files)

    def test_detector_counts_only_executed_invocations(self):
        executed = (
            "bash ./tools/ci/register-workstream.sh",
            "./tools/ci/submit-agent-job.sh prompt.txt",
            'bash "$FRAMEWORK/tools/ci/submit-agent-job.sh" prompt.txt',
            "set -e\n  bash ./tools/ci/submit-staged-request.sh staged-request",
            "./tools/ci/register-workstream.sh 2>&1 | sed 's/x//'",
            "true && ./tools/ci/register-workstream.sh",
            "OUT=$(bash ./tools/ci/submit-agent-job.sh p)",
        )
        named_only = (
            "chmod +x ./tools/ci/register-workstream.sh",
            "if [ ! -f ./tools/ci/submit-staged-request.sh ]; then exit 0; fi",
            "git diff --name-only -- tools/ci/register-workstream.sh",
            'echo "see tools/ci/submit-agent-job.sh"',
            "# bash ./tools/ci/register-workstream.sh",
        )
        for run in executed:
            with self.subTest(run=run):
                self.assertIsNotNone(_CREATES_WORKSTREAM.search(run))
        for run in named_only:
            with self.subTest(run=run):
                self.assertIsNone(_CREATES_WORKSTREAM.search(run))

    def test_every_creating_step_sets_the_trigger(self):
        for name, job, step, env in self._creating_steps():
            with self.subTest(workflow=name, job=job, step=step):
                self.assertIn("'%s'" % _TRIGGER, str(env.get("SETTLED_CI_TRIGGER", "")))


if __name__ == "__main__":
    unittest.main()
