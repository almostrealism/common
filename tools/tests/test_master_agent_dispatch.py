"""Guard: every recurring QA job in Master Agent Dispatch is wired completely.

The QA-style jobs in ``.github/workflows/master-agent-dispatch.yaml`` all have
the same shape — a cadence gate, an archive of the previous rounds, a branch, a
workstream, a prompt, a submission — and every one of them was written by
copying the one before it. That is exactly the situation where a step gets
dropped or a name gets left behind unchanged, and the result does not fail: a
job missing its cadence gate submits a round on every merge to master, and a
job that inherits another's ``BRANCH_PREFIX`` reads that job's branches as its
own history and silently follows its schedule instead.

None of that is visible in a passing workflow run, so it is checked here.
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "master-agent-dispatch.yaml")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True

# The scripts a QA round runs, in the order it must run them.
_QA_SCRIPTS = [
    "qa-cadence.sh",
    "archive-stale-workstreams.sh",
    "register-workstream.sh",
    "submit-agent-job.sh",
]

_SELECTOR = re.compile(r"github\.event\.inputs\.agent == '([a-z-]+)'")


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _run_steps(job):
    """Returns the shell of every step that runs one."""
    return [step["run"] for step in job["steps"] if "run" in step]


def _qa_jobs(workflow):
    """Returns the jobs gated by the shared cadence script, by name."""
    return {name: job for name, job in workflow["jobs"].items()
            if "BRANCH_PREFIX" in job.get("env", {})}


class MasterAgentDispatchTests(unittest.TestCase):
    """Structural checks over the dispatch workflow."""

    def setUp(self):
        self.workflow = _workflow()
        self.jobs = self.workflow["jobs"]
        self.qa_jobs = _qa_jobs(self.workflow)

    def test_the_qa_jobs_are_found(self):
        """A discovery bug would make the per-job checks vacuously pass."""
        self.assertGreaterEqual(len(self.qa_jobs), 4)

    def test_every_qa_job_runs_the_shared_round_scripts(self):
        for name, job in self.qa_jobs.items():
            shell = "\n".join(_run_steps(job))
            for script in _QA_SCRIPTS:
                with self.subTest(job=name, script=script):
                    self.assertIn(script, shell)

    def test_every_qa_job_builds_a_prompt_that_exists(self):
        for name, job in self.qa_jobs.items():
            with self.subTest(job=name):
                builders = re.findall(r"(build-[a-z-]+-prompt\.sh)",
                                      "\n".join(_run_steps(job)))
                self.assertEqual(1, len(set(builders)), builders)
                self.assertTrue(os.path.isfile(os.path.join(
                    _REPO_ROOT, "tools", "ci", "prompts", builders[0])))

    def test_every_qa_job_has_its_own_branch_prefix(self):
        """A shared prefix would make one job read another's cadence."""
        prefixes = [job["env"]["BRANCH_PREFIX"] for job in self.qa_jobs.values()]
        self.assertEqual(len(prefixes), len(set(prefixes)))
        for prefix in prefixes:
            with self.subTest(prefix=prefix):
                # qa-cadence.sh appends "YYYYMMDD-HHMMSS" and parses it back out.
                self.assertTrue(prefix.startswith("qa/"))
                self.assertTrue(prefix.endswith("-"))

    def test_every_qa_job_bounds_its_cadence(self):
        for name, job in self.qa_jobs.items():
            with self.subTest(job=name):
                self.assertGreaterEqual(int(job["env"]["MIN_INTERVAL_DAYS"]), 1)

    def test_every_step_after_the_gate_is_gated(self):
        """An ungated step would run on merges the cadence gate declined."""
        for name, job in self.qa_jobs.items():
            steps = job["steps"]
            gate = next(i for i, s in enumerate(steps)
                        if "qa-cadence.sh" in s.get("run", ""))
            for step in steps[gate + 1:]:
                with self.subTest(job=name, step=step["name"]):
                    condition = step.get("if", "")
                    self.assertTrue(
                        "steps.decide.outputs.run == 'true'" in condition
                        or condition == "always()",
                        "ungated: " + step["name"])

    def test_every_job_serializes_under_its_own_concurrency_group(self):
        """Two rounds of one job racing is what the cadence gate cannot see."""
        groups = []
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                self.assertIn("concurrency", job)
                self.assertFalse(job["concurrency"]["cancel-in-progress"])
                groups.append(job["concurrency"]["group"])
        self.assertEqual(len(groups), len(set(groups)))

    def test_every_job_can_be_dispatched_on_its_own(self):
        options = self.workflow[_ON]["workflow_dispatch"]["inputs"]["agent"]["options"]
        selectors = set()
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                found = _SELECTOR.findall(job["if"])
                self.assertTrue(found, "no agent selector")
                self.assertIn("github.event_name != 'workflow_dispatch'", job["if"],
                              "a push to master must run this job")
                self.assertIn("all", found)
                selectors.update(found)
        self.assertEqual(selectors, set(options))

    def test_the_planning_dispatch_the_mcp_tool_uses_still_exists(self):
        """project_tools.py dispatches this file by name with this selector."""
        self.assertTrue(os.path.basename(_WORKFLOW).endswith("master-agent-dispatch.yaml"))
        self.assertIn("project-manager",
                      self.workflow[_ON]["workflow_dispatch"]["inputs"]["agent"]["options"])


if __name__ == "__main__":
    unittest.main()
