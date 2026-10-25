"""Guard: Plan Ready turns a plan's approval into an implementation, safely.

``.github/workflows/ready-plan.yaml`` runs on ``pull_request_target``, which
grants a writable token in the base-repository context. That is only safe while
the file never checks out or runs the pull request's code, and only dispatches
an implementation when the person who approved the plan is allowed to. None of
that is visible in a passing run, so it is pinned here:

- it must check out nothing (the whole point of a ``pull_request_target`` guard
  workflow);
- it must act only on a same-repo head branch, never a fork;
- it must dispatch only for a sender in the ``PLAN_APPROVERS`` allowlist, and
  only while the PR is still docs-only;
- the attacker-influenced event fields (the head branch name, the sender login)
  must reach shell steps through the environment, never interpolated into a
  ``run:`` body where they would execute.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "ready-plan.yaml")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True


class ReadyPlanWorkflowTests(unittest.TestCase):
    """Structural checks over the Plan Ready workflow."""

    def setUp(self):
        with open(_WORKFLOW) as f:
            self.workflow = yaml.safe_load(f)
        self.job = self.workflow["jobs"]["dispatch"]
        self.steps = self.job["steps"]

    def test_it_triggers_only_on_ready_for_review_against_master(self):
        trigger = self.workflow[_ON]
        self.assertEqual(["pull_request_target"], list(trigger))
        self.assertEqual(["ready_for_review"], trigger["pull_request_target"]["types"])
        self.assertEqual(["master"], trigger["pull_request_target"]["branches"])

    def test_it_checks_out_nothing(self):
        """A pull_request_target workflow that checks out the PR is a token
        exfiltration vector. This one reads everything from event metadata and
        the API, so it must have no checkout at all."""
        for step in self.steps:
            with self.subTest(step=step.get("name")):
                self.assertFalse(str(step.get("uses", "")).startswith("actions/checkout"),
                                 "Plan Ready must not check out any code")

    def test_a_fork_head_is_refused_at_the_job_gate(self):
        """A fork's branch is not in this repository; it can be neither
        dispatched nor trusted to start a dispatch."""
        self.assertEqual(
            "github.event.pull_request.head.repo.full_name == github.repository",
            self.job["if"])

    def test_the_dispatch_needs_the_minimal_write_permission(self):
        self.assertEqual("write", self.job["permissions"]["actions"])

    def test_the_approver_gate_reads_the_repository_variable(self):
        approver = next(s for s in self.steps if s.get("id") == "approver")
        self.assertEqual("${{ vars.PLAN_APPROVERS }}", approver["env"]["APPROVERS"])
        self.assertEqual("${{ github.event.sender.login }}", approver["env"]["SENDER"])

    def test_the_docs_only_gate_runs_only_for_an_approver(self):
        docs = next(s for s in self.steps if s.get("id") == "docs")
        self.assertIn("steps.approver.outputs.approved == 'true'", docs["if"])
        # The rule is read from the PR files API, not a checkout of the branch.
        self.assertIn("pulls/${PR_NUMBER}/files", docs["run"])
        self.assertIn("grep -v '^docs/'", docs["run"])

    def test_it_dispatches_only_when_approved_and_still_docs_only(self):
        dispatch = next(s for s in self.steps if s.get("id") == "dispatch")
        self.assertIn("steps.approver.outputs.approved == 'true'", dispatch["if"])
        self.assertIn("steps.docs.outputs.docs_only == 'true'", dispatch["if"])
        # Dispatches Verify Completion on the PR's head branch through the API.
        self.assertIn("workflows/verify-completion.yaml/dispatches", dispatch["run"])
        self.assertEqual("${{ github.event.pull_request.head.ref }}",
                         dispatch["env"]["HEAD_BRANCH"])

    def test_event_fields_reach_scripts_only_through_the_environment(self):
        """The head branch name and the sender login are attacker-influenced on
        a same-repo PR; interpolated into a `run:` body they would execute. Every
        step must read them from `env:` instead."""
        for step in self.steps:
            run = step.get("run", "")
            with self.subTest(step=step.get("name")):
                self.assertNotIn("github.event.pull_request.head.ref", run)
                self.assertNotIn("github.event.sender.login", run)
                self.assertNotIn("vars.PLAN_APPROVERS", run)


if __name__ == "__main__":
    unittest.main()
