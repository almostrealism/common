"""Guard: Verify Completion is the manual, working "implement the plan" trigger.

A plan branch is only reviewed until a person approves its plan; implementation
starts when someone dispatches ``.github/workflows/verify-completion.yaml`` on
the branch. That makes two properties load-bearing, and neither shows up in a
passing pipeline:

- It must never start on its own. A push or pull-request trigger would put an
  implementing agent on every plan the moment it was proposed, which is the
  behaviour this workflow's manual trigger exists to prevent.
- It must be able to reach the controller from wherever it runs. It once ran on
  the self-hosted fleet and addressed the controller by a LAN hostname; a
  button that fails when pressed is no better than no button.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "verify-completion.yaml")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True

_CONTROLLER_SCRIPTS = ("register-workstream.sh", "submit-agent-job.sh")


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


class VerifyCompletionWorkflowTests(unittest.TestCase):
    """Structural checks over the Verify Completion workflow."""

    def setUp(self):
        self.workflow = _workflow()
        self.jobs = self.workflow["jobs"]

    def test_it_only_runs_when_dispatched(self):
        self.assertEqual(["workflow_dispatch"], list(self.workflow[_ON]))

    def test_it_submits_the_implementation_prompt(self):
        runs = " ".join(step.get("run", "") for step in self.jobs["verify"]["steps"])
        self.assertIn("build-verify-prompt.sh", runs)
        self.assertIn("submit-agent-job.sh", runs)

    def test_every_job_reaches_the_controller_through_the_tunnel(self):
        found = 0
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                self.assertNotIn("self-hosted", str(job["runs-on"]))
            for step in job["steps"]:
                if not any(s in step.get("run", "") for s in _CONTROLLER_SCRIPTS):
                    continue
                found += 1
                with self.subTest(job=name, step=step["name"]):
                    env = step.get("env", {})
                    self.assertNotIn("CONTROLLER_HOST", env)
                    self.assertIn("FLOWTREE_CONTROLLER_URL", env.get("CONTROLLER_URL", ""))
                    self.assertIn("CF_ACCESS_CLIENT_ID", env)
                    self.assertIn("secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET",
                                  env.get("CF_ACCESS_CLIENT_SECRET", ""))
        self.assertEqual(len(_CONTROLLER_SCRIPTS), found)

    def test_the_plan_file_input_is_not_interpolated_into_a_script(self):
        """A dispatch input is free text; interpolated into `run:` it executes."""
        for name, job in self.jobs.items():
            for step in job["steps"]:
                with self.subTest(job=name, step=step.get("name")):
                    self.assertNotIn("inputs.plan_file", step.get("run", ""))

    def test_the_detected_plan_file_output_is_not_interpolated_into_a_script(self):
        """detect-plan's plan_file output is the dispatch input echoed to
        `$GITHUB_OUTPUT`, so interpolating it into `run:` executes the input
        just as directly. The summaries must read it through `env:` instead."""
        for name, job in self.jobs.items():
            for step in job["steps"]:
                with self.subTest(job=name, step=step.get("name")):
                    self.assertNotIn("outputs.plan_file", step.get("run", ""))


if __name__ == "__main__":
    unittest.main()
