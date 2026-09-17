"""Guard: a merge-base failure in deception-audit must not read as "no findings".

``deception-audit.sh`` sets ``audit_error=true`` on ``$GITHUB_OUTPUT`` when it
cannot resolve the merge-base (or the merge-base's file listing) and never
runs the audit at all. That is different from running the audit and finding
nothing (``has_findings=false``), and ``check-quality-gates.sh`` already tells
the two apart via the ``DECEPTION_AUDIT_ERROR`` environment variable. None of
that matters if the workflow never surfaces the script's ``audit_error``
output as a job output and threads it into the "Check quality gates" step's
env — the script and the gate script would be correctly wired to each other
but disconnected from the workflow that runs both. These tests pin that
connection.
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_AUDIT_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "agent-protection", "deception-audit.sh")
_GATES_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "agent-protection", "check-quality-gates.sh")

_JOB = "deception-audit"
_QUALITY_GATES_STEP = "Check quality gates"


def _read(path):
    with open(path) as f:
        return f.read()


def _workflow():
    return yaml.safe_load(_read(_WORKFLOW))


def _job():
    return _workflow()["jobs"][_JOB]


def _quality_gates_step():
    for job in _workflow()["jobs"].values():
        for step in job.get("steps", []):
            if step.get("name") == _QUALITY_GATES_STEP:
                return step
    return None


class DeceptionAuditWiringTests(unittest.TestCase):
    """Wires deception-audit.sh's audit_error output through the workflow."""

    def test_the_script_writes_an_audit_error_output(self):
        self.assertIn('audit_error=true', _read(_AUDIT_SCRIPT))

    def test_the_job_declares_audit_error_as_an_output(self):
        outputs = _job()["outputs"]
        self.assertIn("audit_error", outputs)
        self.assertIn("steps.audit.outputs.audit_error", outputs["audit_error"])

    def test_the_job_summary_step_checks_audit_error(self):
        """A merge-base failure must not fall through to "no findings"."""
        summary_steps = [step["run"] for step in _job()["steps"]
                          if step.get("name") == "Summary" and "run" in step]
        self.assertTrue(summary_steps)
        for script in summary_steps:
            with self.subTest(step="Summary"):
                self.assertIn("steps.audit.outputs.audit_error", script)

    def test_the_quality_gates_step_receives_audit_error(self):
        """The auto-resolve job must pass audit_error into check-quality-gates.sh."""
        step = _quality_gates_step()
        self.assertIsNotNone(step, "no step named 'Check quality gates' found")
        env = step.get("env", {})
        self.assertIn("DECEPTION_AUDIT_ERROR", env)
        self.assertIn("needs.deception-audit.outputs.audit_error", env["DECEPTION_AUDIT_ERROR"])

    def test_the_gate_script_reads_audit_error(self):
        self.assertIn("DECEPTION_AUDIT_ERROR", _read(_GATES_SCRIPT))

    def test_no_job_output_expression_contains_a_newline(self):
        """A folded value can smuggle a newline into a ${{ }} expression.

        In a folded scalar a line indented further than the first keeps its
        newline, so wrapping a long expression for readability can produce
        one that no longer parses — and the output silently becomes the
        empty string, which here means "nothing to report about the branch".
        """
        outputs = _job()["outputs"]
        for output, value in outputs.items():
            with self.subTest(output=output):
                if "${{" in value:
                    self.assertNotIn("\n", value)


if __name__ == "__main__":
    unittest.main()
