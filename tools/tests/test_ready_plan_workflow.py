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

``ready-plan.yaml`` is now a thin caller of the reusable ``plan-ready.yaml``:
its ``dispatch`` job is a ``uses:`` of the reusable workflow and carries no
steps of its own. The behaviours above live in the called workflow's job, so
each check below follows the caller's local ``uses:`` into ``plan-ready.yaml``
and reasserts them there — the same "resolve a ``uses:`` caller into the called
workflow's job" pattern ``test_master_agent_dispatch.py`` and
``test_verify_completion_workflow.py`` use.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_CALLER = os.path.join(_REPO_ROOT, ".github", "workflows", "ready-plan.yaml")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True


def _load(path):
    with open(path) as f:
        return yaml.safe_load(f)


def _called_job(caller_job):
    """Returns the single job of the local reusable workflow ``caller_job``
    runs. The caller's ``dispatch`` job is a ``uses:`` of ``plan-ready.yaml``;
    its one job is where every behaviour is pinned."""
    uses = caller_job["uses"]
    assert uses.startswith("./.github/workflows/"), uses
    jobs = _load(os.path.join(_REPO_ROOT, uses[2:]))["jobs"]
    assert len(jobs) == 1, uses
    return next(iter(jobs.values()))


class ReadyPlanWorkflowTests(unittest.TestCase):
    """Structural checks over Plan Ready: the caller's trigger, and the called
    workflow's job it dispatches."""

    def setUp(self):
        self.caller = _load(_CALLER)
        self.caller_job = self.caller["jobs"]["dispatch"]
        self.job = _called_job(self.caller_job)
        self.steps = self.job["steps"]

    def test_it_triggers_only_on_ready_for_review_against_master(self):
        trigger = self.caller[_ON]
        self.assertEqual(["pull_request_target"], list(trigger))
        self.assertEqual(["ready_for_review"], trigger["pull_request_target"]["types"])
        self.assertEqual(["master"], trigger["pull_request_target"]["branches"])

    def test_the_caller_passes_its_own_approvers_variable(self):
        """The caller hands the reusable workflow this repository's allowlist as
        the ``approvers`` input; the reusable workflow is not coupled to the
        variable name."""
        self.assertEqual("${{ vars.PLAN_APPROVERS }}", self.caller_job["with"]["approvers"])

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
        dispatched nor trusted to start a dispatch. The github context is the
        caller's inside a called workflow, so the guard on the called job still
        compares the caller's event against the caller's repository."""
        self.assertEqual(
            "github.event.pull_request.head.repo.full_name == github.repository",
            self.job["if"])

    def test_the_dispatch_needs_the_minimal_write_permission(self):
        """Both the caller and the called job declare it: the caller grants it
        (a called job cannot widen beyond the caller's grant) and the called job
        declares the same for hygiene."""
        self.assertEqual("write", self.caller_job["permissions"]["actions"])
        self.assertEqual("write", self.job["permissions"]["actions"])

    def test_the_approver_gate_reads_the_repository_variable(self):
        approver = next(s for s in self.steps if s.get("id") == "approver")
        self.assertEqual("${{ inputs.approvers }}", approver["env"]["APPROVERS"])
        self.assertEqual("${{ github.event.sender.login }}", approver["env"]["SENDER"])

    def test_the_docs_only_gate_runs_only_for_an_approver(self):
        docs = next(s for s in self.steps if s.get("id") == "docs")
        self.assertIn("steps.approver.outputs.approved == 'true'", docs["if"])
        # The rule is read from the PR files API, not a checkout of the branch.
        self.assertIn("pulls/${PR_NUMBER}/files", docs["run"])

    def test_the_docs_only_gate_requires_every_file_under_the_docs_directory(self):
        """Plan Ready's own check: every changed file under the docs-directory
        input. This repository leaves that input at the reusable workflow's
        ``docs/`` default, so here the rule is "every changed file under
        ``docs/``". It is read through the environment (the directory is data,
        not shell), and the filter keeps only files under it."""
        docs = next(s for s in self.steps if s.get("id") == "docs")
        self.assertEqual("${{ inputs.docs-directory }}", docs["env"]["DOCS_DIRECTORY"])
        self.assertNotIn("docs-directory", self.caller_job.get("with", {}))
        spec = _load(os.path.join(
            _REPO_ROOT, self.caller_job["uses"][2:]))[_ON]["workflow_call"]["inputs"]
        self.assertEqual("docs/", spec["docs-directory"]["default"])
        # The filter drops every file not under the (slash-normalised) directory.
        self.assertIn('PREFIX="${DOCS_DIRECTORY%/}/"', docs["run"])
        self.assertIn('grep -v "^${PREFIX}"', docs["run"])

    def test_it_dispatches_only_when_approved_and_still_docs_only(self):
        dispatch = next(s for s in self.steps if s.get("id") == "dispatch")
        self.assertIn("steps.approver.outputs.approved == 'true'", dispatch["if"])
        self.assertIn("steps.docs.outputs.docs_only == 'true'", dispatch["if"])
        # Dispatches the implementation workflow on the PR's head branch through
        # the API. The workflow file is the dispatch-workflow input, which this
        # repository leaves at its verify-completion.yaml default.
        self.assertEqual("${{ inputs.dispatch-workflow }}",
                         dispatch["env"]["DISPATCH_WORKFLOW"])
        self.assertIn("workflows/${DISPATCH_WORKFLOW}/dispatches", dispatch["run"])
        self.assertNotIn("dispatch-workflow", self.caller_job.get("with", {}))
        spec = _load(os.path.join(
            _REPO_ROOT, self.caller_job["uses"][2:]))[_ON]["workflow_call"]["inputs"]
        self.assertEqual("verify-completion.yaml", spec["dispatch-workflow"]["default"])
        self.assertEqual("${{ github.event.pull_request.head.ref }}",
                         dispatch["env"]["HEAD_BRANCH"])

    def test_event_fields_reach_scripts_only_through_the_environment(self):
        """The head branch name and the sender login are attacker-influenced on
        a same-repo PR, and the inputs are free text; interpolated into a `run:`
        body they would execute. Every step must read them from `env:` instead."""
        for step in self.steps:
            run = step.get("run", "")
            with self.subTest(step=step.get("name")):
                self.assertNotIn("github.event.pull_request.head.ref", run)
                self.assertNotIn("github.event.sender.login", run)
                self.assertNotIn("inputs.approvers", run)
                self.assertNotIn("inputs.docs-directory", run)
                self.assertNotIn("inputs.dispatch-workflow", run)


if __name__ == "__main__":
    unittest.main()
