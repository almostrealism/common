"""Tests for the automated-intake gate of the master agent dispatch.

Every round in ``master-agent-dispatch.yaml`` creates a branch and registers a
workstream before it submits its agent job. When the controller's intake of
automated jobs is closed, that submission is refused, and the round used to be
left half-built: a pushed branch and a registered workstream with no agent,
which then held off the next round for the cadence gate's grace window.
``automated-intake.sh`` asks the controller first, and every job's cadence
gate runs only when the answer is yes. The script is run here for real
against a local stand-in for the controller, and the wiring is read from the
workflow.
"""

import os
import sys
import unittest

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_task_planning_gates import _ControllerStub, _run  # noqa: E402

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "master-agent-dispatch.yaml")
_SCRIPT = "automated-intake.sh"


class AutomatedIntakeScriptTests(unittest.TestCase):
    """automated-intake.sh opens the gate only on an explicit yes."""

    def _ask(self, answer):
        stub = _ControllerStub(claimable=answer)
        self.addCleanup(stub.close)
        code, out, log = _run(_SCRIPT, {"CONTROLLER_URL": stub.url + "/"})
        return stub, code, out, log

    def test_an_accepting_controller_opens_the_gate(self):
        stub, code, out, _ = self._ask((200, {"acceptAutomatedJobs": True}))
        self.assertEqual((0, "true"), (code, out["open"]))
        self.assertEqual("/api/config/accept-automated-jobs", stub.seen[0][1])
        self.assertEqual("GET", stub.seen[0][0])

    def test_a_closed_intake_keeps_the_gate_shut(self):
        _, code, out, log = self._ask((200, {"acceptAutomatedJobs": False}))
        self.assertEqual((0, "false"), (code, out["open"]))
        self.assertIn("not accepting automated jobs", log)

    def test_an_error_from_the_controller_keeps_the_gate_shut(self):
        _, code, out, _ = self._ask((503, {"ok": False}))
        self.assertEqual((0, "false"), (code, out["open"]))

    def test_an_unexpected_answer_keeps_the_gate_shut(self):
        for answer in ({}, {"acceptAutomatedJobs": "true"}, {"acceptAutomatedJobs": None},
                       b"not json"):
            with self.subTest(answer=answer):
                _, code, out, _ = self._ask((200, answer))
                self.assertEqual((0, "false"), (code, out["open"]))

    def test_an_unreachable_controller_keeps_the_gate_shut(self):
        stub = _ControllerStub()
        url = stub.url
        stub.close()
        code, out, _ = _run(_SCRIPT, {"CONTROLLER_URL": url})
        self.assertEqual((0, "false"), (code, out["open"]))

    def test_the_service_token_is_sent(self):
        stub = _ControllerStub(claimable=(200, {"acceptAutomatedJobs": True}))
        self.addCleanup(stub.close)
        _run(_SCRIPT, {"CONTROLLER_URL": stub.url, "CF_ACCESS_CLIENT_ID": "id",
                       "CF_ACCESS_CLIENT_SECRET": "secret"})
        headers = {k.lower(): v for k, v in stub.seen[0][3].items()}
        self.assertEqual(("id", "secret"), (headers["cf-access-client-id"],
                                            headers["cf-access-client-secret"]))

    def test_a_missing_controller_url_is_an_error(self):
        code, _, _ = _run(_SCRIPT, {"CONTROLLER_URL": ""})
        self.assertEqual(1, code)


class AutomatedIntakeWiringTests(unittest.TestCase):
    """Every dispatch job asks about intake before its round can start."""

    def setUp(self):
        with open(_WORKFLOW) as f:
            self.jobs = yaml.safe_load(f)["jobs"]

    def test_every_job_checks_intake_before_its_cadence_gate(self):
        for name, job in self.jobs.items():
            steps = job["steps"]
            with self.subTest(job=name):
                intake = next(i for i, s in enumerate(steps) if s.get("id") == "intake")
                gate = next(i for i, s in enumerate(steps) if s.get("id") == "decide")
                self.assertLess(intake, gate)
                self.assertIn(_SCRIPT, steps[intake]["run"])
                self.assertEqual("steps.intake.outputs.open == 'true'", steps[gate].get("if"))

    def test_nothing_is_created_before_the_cadence_gate(self):
        """Steps before the gate run regardless of intake, so they must not
        push a branch, register a workstream or submit a job."""
        creating = ("git push", "register-workstream.sh", "submit-agent-job.sh",
                    "archive-stale-workstreams.sh")
        for name, job in self.jobs.items():
            steps = job["steps"]
            gate = next(i for i, s in enumerate(steps) if s.get("id") == "decide")
            for step in steps[:gate]:
                with self.subTest(job=name, step=step["name"]):
                    for command in creating:
                        self.assertNotIn(command, step.get("run", ""))

    def test_every_summary_reports_the_intake_decision(self):
        """A closed intake must not read as a round in progress: every job's
        summary reports the intake answer, and the QA summaries name the
        empty reason a gate that never ran leaves behind."""
        for name, job in self.jobs.items():
            summary = next(s for s in job["steps"] if s["name"] == "Summary")
            text = summary["run"] + str(summary.get("env", {}))
            with self.subTest(job=name):
                self.assertEqual("always()", summary.get("if"))
                self.assertIn("steps.intake.outputs.open", text)
                if "case \"${{ steps.decide.outputs.reason }}\"" in summary["run"]:
                    self.assertIn('"") echo "Skipped: the cadence gate did not run',
                                  summary["run"])

    def test_the_intake_check_reaches_the_controller_through_the_tunnel(self):
        for name, job in self.jobs.items():
            step = next(s for s in job["steps"] if s.get("id") == "intake")
            env = step.get("env", {})
            with self.subTest(job=name):
                self.assertIn("FLOWTREE_CONTROLLER_URL", env.get("CONTROLLER_URL", ""))
                self.assertIn("CF_ACCESS_CLIENT_ID", env)
                self.assertIn("secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET",
                              env.get("CF_ACCESS_CLIENT_SECRET", ""))


if __name__ == "__main__":
    unittest.main()
