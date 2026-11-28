"""Guard: a conflicted PR still gets its workstream registered, safely.

GitHub does not fire the ``pull_request`` event for a PR that conflicts with
its base (there is no trial-merge commit to build), so analysis.yaml's own
``register-workstream`` job never runs for one, and a PR opened in a conflicted
state — a harness rescue branch, for one — sat without a workstream until
someone registered it by hand.

``.github/workflows/register-workstream.yaml`` closes that gap on
``pull_request_target``, which runs the base branch's copy of the workflow and
needs no mergeable commit. That trigger grants a writable token and the
repository's secrets in the base-repository context, so it is safe only while
the workflow never checks out or runs the PR's code. None of that is visible in
a passing run, so it is pinned here:

- it triggers on ``pull_request_target`` against master, the event that (unlike
  ``pull_request``) fires regardless of merge state;
- its checkout takes the base branch (no ``ref``), so the
  register-workstream.sh it runs is origin/master's trusted copy, never the
  PR's — which is why it needs no script-integrity step of its own;
- it acts only on a same-repo head branch, never a fork;
- the attacker-influenced event fields (the head branch name) reach the script
  through the environment, never interpolated into a ``run:`` body;
- it declares only ``contents: read`` (for the checkout);
- it registers no model configuration onto the auto-created workstream, the
  same invariant ``test_register_workstream_wiring.py`` pins for the normal
  path.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "register-workstream.yaml")
_SCRIPT = "tools/ci/register-workstream.sh"

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True


def _load(path):
    with open(path) as f:
        return yaml.safe_load(f)


class RegisterWorkstreamConflictWorkflowTests(unittest.TestCase):
    """Structural checks over the conflicted-PR registration workflow."""

    def setUp(self):
        self.workflow = _load(_WORKFLOW)
        jobs = self.workflow["jobs"]
        self.assertEqual(1, len(jobs), "the workflow declares a single job")
        self.job = next(iter(jobs.values()))
        self.steps = self.job["steps"]
        self.register_step = next(
            s for s in self.steps if s.get("name") == "Register workstream")

    def test_it_triggers_on_pull_request_target_against_master(self):
        """pull_request_target is the trigger that fires on a conflicted PR;
        pull_request (analysis.yaml's own job) is exactly the one that does
        not."""
        trigger = self.workflow[_ON]
        self.assertEqual(["pull_request_target"], list(trigger))
        self.assertEqual(["master"], trigger["pull_request_target"]["branches"])
        # Covers a PR opened conflicted, reopened, or pushed to while conflicted.
        self.assertIn("opened", trigger["pull_request_target"]["types"])

    def test_it_checks_out_the_base_branch_not_the_pr(self):
        """The checkout must carry no `ref`: that takes the base branch, so the
        register-workstream.sh that runs is origin/master's trusted copy and the
        workflow never executes PR-head code under pull_request_target."""
        checkouts = [s for s in self.steps
                     if str(s.get("uses", "")).startswith("actions/checkout")]
        self.assertTrue(checkouts, "the workflow must check out the base branch")
        for step in checkouts:
            with self.subTest(step=step.get("name")):
                self.assertNotIn("ref", step.get("with", {}) or {},
                                 "a `ref` would risk checking out PR-head code")

    def test_a_fork_head_is_refused_at_the_job_gate(self):
        """A fork's branch is not in this repository: the controller could not
        clone it, and a fork PR gets none of the secrets this job needs."""
        self.assertEqual(
            "github.event.pull_request.head.repo.full_name == github.repository",
            self.job["if"])

    def test_it_declares_only_minimal_read_permission(self):
        self.assertEqual({"contents": "read"}, self.job["permissions"])

    def test_it_runs_the_registration_script(self):
        self.assertIn(_SCRIPT, self.register_step["run"])

    def test_the_branch_name_reaches_the_script_through_the_environment(self):
        """The head branch name is attacker-influenced and git refs permit shell
        metacharacters; it must reach the script via env, never interpolated
        into the run body."""
        self.assertEqual("${{ github.event.pull_request.head.ref }}",
                         self.register_step["env"]["BRANCH"])
        self.assertEqual("${{ github.event.pull_request.base.ref }}",
                         self.register_step["env"]["BASE_BRANCH"])
        for step in self.steps:
            run = step.get("run", "")
            with self.subTest(step=step.get("name")):
                self.assertNotIn("github.event.pull_request.head.ref", run)
                self.assertNotIn("github.event.pull_request.base.ref", run)

    def test_it_carries_the_controller_credential(self):
        """It holds the secret but runs no PR code — the same trust position as
        cancel-merged-pr-runs.yaml's cancel-branch-jobs."""
        self.assertIn("CF_ACCESS_CLIENT_SECRET", self.register_step["env"])

    def test_it_sets_the_settled_ci_trigger_so_the_merge_gate_can_start(self):
        """Without the settled-CI trigger the branch's test run never starts and
        the PR stays unmergeable — the whole point of registering it early."""
        self.assertIn("SETTLED_CI_TRIGGER", self.register_step["env"])

    def test_it_registers_no_model_configuration(self):
        """An auto-created workstream must declare nothing that shadows the
        workspace's model/runner/effort defaults — the invariant
        test_register_workstream_wiring.py pins for the normal path."""
        for name in self.register_step["env"]:
            for forbidden in ("MODEL", "EFFORT", "RUNNER", "PROVIDER", "PHASE"):
                self.assertNotIn(forbidden, name.upper(),
                                 "env var %r would pin model configuration onto "
                                 "the auto-created workstream" % name)

    def test_it_does_not_fail_the_run_on_a_registration_error(self):
        """Registration is best-effort: a controller error downgrades the
        script's ::error:: marker and the step still exits 0, so a transient
        failure does not paint a red workflow run."""
        run = self.register_step["run"]
        self.assertIn("sed 's/^::error:://'", run)
        self.assertIn("PIPESTATUS", run)


if __name__ == "__main__":
    unittest.main()
