"""Guard: a failing test-integrity-check must never accuse a branch by default.

When this job fails, a message describing why is assembled and handed to an
agent, which then changes the branch to make it stop failing. That is fine when
a detector actually found something. It is not fine when the job failed for its
own reasons — a script missing from an older checkout, a detector that could not
start, a job that was skipped — and it has happened: a branch that had done
nothing wrong was "fixed" in response to a message that said it had weakened its
tests.

The contract that prevents it spans a workflow and a shell script, which cannot
see each other. The failing step names the cause; ``check-quality-gates.sh``
reports only causes it recognises and stays silent otherwise. These tests pin
the two ends together: every cause the workflow can emit is one the script
handles, and the wiring that carries it between them is intact.
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_GATES = os.path.join(_REPO_ROOT, "tools", "ci", "agent-protection", "check-quality-gates.sh")

_JOB = "test-integrity-check"

# `reason=<cause>` as the workflow's steps write it to $GITHUB_OUTPUT.
_EMITTED = re.compile(r"reason=([a-z-]+)")

# The arms of the script's `case` over TEST_INTEGRITY_REASON. The `*` arm is
# the one that reports nothing, so it is deliberately not collected here.
_CASE_ARM = re.compile(r"^\s{8}([a-z-]+)\)$", re.MULTILINE)

# A cause that names a broken pipeline rather than a finding about a branch.
_INFRASTRUCTURE = "infrastructure"

# A real invocation of the gate script, as opposed to a comment naming it or
# the step that runs its regression suite.
_GATES_CALL = re.compile(
    r"^\s*\./tools/ci/agent-protection/check-quality-gates\.sh ", re.MULTILINE)


def _read(path):
    with open(path) as f:
        return f.read()


def _job():
    return yaml.safe_load(_read(_WORKFLOW))["jobs"][_JOB]


def _detector_steps():
    """Returns the run-script of every step that reaches a verdict."""
    return [step["run"] for step in _job()["steps"]
            if "run" in step and "reason=" in step["run"]]


class IntegrityCheckWiringTests(unittest.TestCase):
    """The workflow end of the contract, and its two consumers."""

    def test_the_job_publishes_the_cause_it_found(self):
        self.assertIn("failure_reason", _job()["outputs"])

    def test_every_detector_step_records_a_cause(self):
        """A step that can fail without naming why produces an accusation."""
        self.assertGreaterEqual(len(_detector_steps()), 3)

    def test_every_cause_the_workflow_emits_is_one_the_gate_script_handles(self):
        emitted = set()
        for script in _detector_steps():
            emitted.update(_EMITTED.findall(script))
        handled = set(_CASE_ARM.findall(_read(_GATES)))

        # `infrastructure` is handled by the catch-all arm, which reports
        # nothing at all — that is the point of it, so it is not expected to
        # appear as a named arm.
        self.assertIn(_INFRASTRUCTURE, emitted)
        self.assertEqual(emitted - {_INFRASTRUCTURE}, handled)

    def test_a_detector_that_cannot_run_is_a_separate_cause(self):
        """A step invoking a detector must distinguish it failing to run.

        An exit code from a detector script means one of two things, and the
        step is the only place that knows which: the detector ran and found
        something, or it never got that far. Only the step that invokes one
        is held to this — the checks written inline in the workflow have no
        such script to misread.
        """
        for script in _detector_steps():
            if "./tools/ci/" not in script:
                continue
            with self.subTest(step=script.splitlines()[0]):
                causes = set(_EMITTED.findall(script))
                self.assertIn(_INFRASTRUCTURE, causes,
                              "a finding but no way to report a failure to run")

    def test_no_step_runs_a_script_without_checking_it_is_there(self):
        """The chmod that started this: the file may not be in an old head."""
        for step in _job()["steps"]:
            script = step.get("run", "")
            if "chmod +x" not in script:
                continue
            with self.subTest(step=step.get("name", "<unnamed>")):
                self.assertLess(script.index("if [ ! -f"), script.index("chmod +x"),
                                "chmod before the existence check")

    def test_the_cause_reaches_the_gate_script(self):
        """Whoever runs check-quality-gates.sh must pass the reason along."""
        workflow = yaml.safe_load(_read(_WORKFLOW))
        callers = [step for job in workflow["jobs"].values()
                   for step in job["steps"]
                   if _GATES_CALL.search(step.get("run", ""))]
        self.assertTrue(callers)
        for step in callers:
            with self.subTest(step=step.get("name", "<unnamed>")):
                env = step.get("env", {})
                self.assertIn("TEST_INTEGRITY_REASON", env)
                self.assertIn("failure_reason", env["TEST_INTEGRITY_REASON"])

    def test_the_gate_script_reads_the_cause(self):
        self.assertIn("TEST_INTEGRITY_REASON", _read(_GATES))

    def test_no_job_output_expression_contains_a_newline(self):
        """A folded value can smuggle a newline into a ${{ }} expression.

        In a folded scalar a line indented further than the first keeps its
        newline, so wrapping a long expression for readability can produce
        one that no longer parses — and the output silently becomes the
        empty string, which here means "nothing to report about the branch".
        """
        for name, job in yaml.safe_load(_read(_WORKFLOW))["jobs"].items():
            for output, value in (job.get("outputs") or {}).items():
                with self.subTest(job=name, output=output):
                    if "${{" in value:
                        self.assertNotIn("\n", value)


if __name__ == "__main__":
    unittest.main()
