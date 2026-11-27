#!/usr/bin/env python3
# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Pins the deploy rehearsal: CI performs the deploy, short of the swap, on
every change, and the deploy refuses a commit that has not passed it.

Before this, CI's only view of the controller stack was the ``docker-build``
job: path-gated, build-only, and blind to the controller and agent images.
Images that built but crashed on start (ar-memory missing an imported module)
or did not build at all (the agent image) passed every check and failed the
deploy. The rehearsal closes that by running the deploy's own script, so these
tests hold four things in place: the job runs that script with ``--rehearse``
and no path gate; the merge gate requires it; the deploy checks for it before
it changes anything; and ``rebuild.sh``'s rehearsal stops before the swap.
"""

import importlib.util
import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_ANALYSIS = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_DEPLOY = os.path.join(_REPO_ROOT, ".github", "workflows", "deploy.yaml")
_REBUILD = os.path.join(_REPO_ROOT, "flowtree", "runtime", "rebuild.sh")
_COMPOSE = os.path.join(_REPO_ROOT, "flowtree", "runtime", "controller", "docker-compose.yml")
_GATE = os.path.join(_REPO_ROOT, "tools", "ci", "require_rehearsed_commit.py")


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _load_gate():
    spec = importlib.util.spec_from_file_location("require_rehearsed_commit", _GATE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RehearsalJobTest(unittest.TestCase):
    """The ``deploy-rehearsal`` job runs the deploy's script on every change."""

    def setUp(self):
        self.workflow = yaml.safe_load(_read(_ANALYSIS))
        self.job = self.workflow["jobs"]["deploy-rehearsal"]

    def test_it_runs_the_deploy_script_in_rehearsal_mode_with_the_agent_pool(self):
        runs = [step.get("run", "") for step in self.job["steps"]]
        self.assertIn("./flowtree/runtime/rebuild.sh --rehearse --agents", runs)

    def test_it_has_no_path_gate(self):
        condition = self.job["if"]
        self.assertNotIn("outputs.", condition,
                         "a path flag is a guess at what the images depend on")
        self.assertIn("github.event_name != 'deployment'", condition)

    def test_its_name_is_the_one_the_deploy_gate_looks_for(self):
        self.assertEqual(_load_gate().REHEARSAL_JOB_NAME, self.job["name"])

    def test_it_builds_for_the_deploy_hosts_architecture(self):
        self.assertTrue(self.job["runs-on"].endswith("-arm"))

    def test_the_merge_gate_requires_it(self):
        gate = self.workflow["jobs"]["all-checks"]
        self.assertIn("deploy-rehearsal", gate["needs"])
        script = gate["steps"][0]["run"]
        self.assertIn('check_job "deploy-rehearsal"', script)
        self.assertIn("needs.deploy-rehearsal.result",
                      gate["steps"][0]["env"]["DEPLOY_REHEARSAL_RESULT"])


class DeployGateTest(unittest.TestCase):
    """The deploy refuses an unrehearsed commit before it changes anything."""

    def setUp(self):
        self.steps = yaml.safe_load(_read(_DEPLOY))["jobs"]["deploy"]["steps"]
        self.names = [step.get("name", "") for step in self.steps]

    def test_the_gate_runs_after_checkout_and_before_intake_closes(self):
        gate = self.names.index("Require a passing deploy rehearsal of this commit")
        self.assertLess(self.names.index("Checkout master"), gate)
        self.assertLess(gate, self.names.index("Stop accepting new agent jobs"))
        self.assertIsNone(self.steps[gate].get("if"), "the gate has no override")
        self.assertIsNone(self.steps[gate].get("continue-on-error"))

    def test_the_gate_checks_the_checked_out_commit(self):
        gate = self.steps[self.names.index("Require a passing deploy rehearsal of this commit")]
        self.assertIn('require_rehearsed_commit.py "$(git rev-parse HEAD)"', gate["run"])


class RehearsalVerdictTest(unittest.TestCase):
    """``rehearsal_verdict`` passes a commit only on a successful rehearsal."""

    def setUp(self):
        self.verdict = _load_gate().rehearsal_verdict

    def _jobs(self, table):
        return lambda run_id: table[run_id]

    def test_a_successful_rehearsal_passes(self):
        passed, _ = self.verdict(
            [{"id": 1, "run_number": 5}],
            self._jobs({1: [{"name": "Deploy rehearsal", "conclusion": "success"}]}))
        self.assertTrue(passed)

    def test_no_run_does_not_pass(self):
        passed, reason = self.verdict([], self._jobs({}))
        self.assertFalse(passed)
        self.assertIn("no Build and Test push run", reason)

    def test_a_failed_or_running_rehearsal_does_not_pass(self):
        for job in ({"name": "Deploy rehearsal", "conclusion": "failure"},
                    {"name": "Deploy rehearsal", "conclusion": None, "status": "in_progress"},
                    {"name": "Deploy rehearsal", "conclusion": "skipped"},
                    {"name": "Deploy rehearsal", "conclusion": "cancelled"}):
            passed, _ = self.verdict([{"id": 1, "run_number": 1}], self._jobs({1: [job]}))
            self.assertFalse(passed, job)

    def test_a_run_without_the_job_does_not_pass(self):
        passed, reason = self.verdict(
            [{"id": 1, "run_number": 1}],
            self._jobs({1: [{"name": "Build", "conclusion": "success"}]}))
        self.assertFalse(passed)
        self.assertIn("no 'Deploy rehearsal' job", reason)

    def test_any_run_of_the_commit_with_a_passing_rehearsal_passes(self):
        passed, _ = self.verdict(
            [{"id": 1, "run_number": 1}, {"id": 2, "run_number": 2}],
            self._jobs({1: [{"name": "Deploy rehearsal", "conclusion": "success"}],
                        2: [{"name": "Deploy rehearsal", "conclusion": "cancelled"}]}))
        self.assertTrue(passed)


class RehearsalModeTest(unittest.TestCase):
    """``rebuild.sh --rehearse`` stops after the preflight, before any swap."""

    def setUp(self):
        self.script = _read(_REBUILD)

    def test_the_rehearsal_exits_between_the_preflight_and_the_swap(self):
        exit_at = self.script.index('echo "Rehearsal passed')
        self.assertLess(self.script.index("\n  preflight_controller_stack\n"), exit_at)
        self.assertLess(exit_at, self.script.index("# ── Swap"))
        self.assertLess(exit_at, self.script.index("docker image prune"))

    def test_the_rehearsal_builds_under_its_own_project_name(self):
        self.assertIn('export COMPOSE_PROJECT_NAME="${REHEARSAL_PROJECT:-flowtree-rehearsal}"',
                      self.script)

    def test_the_preflight_covers_every_service_but_the_fleet_ones(self):
        services = set(yaml.safe_load(_read(_COMPOSE))["services"])
        match = re.search(r"^PREFLIGHT_SERVICES=\(([^)]*)\)", self.script, re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertEqual(services - {"fleet-db", "fleet-grafana"}, set(match.group(1).split()),
                         "a service the preflight does not start is a service CI never starts")


if __name__ == "__main__":
    unittest.main()
