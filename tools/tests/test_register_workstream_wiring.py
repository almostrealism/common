"""Guard: register-workstream must not run an untrusted copy of its script.

register-workstream no longer waits on test-integrity-check or any of the
other validation gates (it needs only `changes` and `build`), so nothing else
in the pipeline verifies that ``tools/ci/register-workstream.sh`` is unmodified
before this job executes it with ``FLOWTREE_CF_ACCESS_CLIENT_SECRET`` attached.
A PR that modified the script itself would otherwise have that modified copy
run with the secret in its environment. The "Verify registration script
integrity" step exists to close that gap, and the step that carries the
secret must actually be gated on its verdict — these tests pin that wiring so
a future edit cannot silently drop the gate while leaving the check in place
(or vice versa).
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")

_JOB = "register-workstream"
_INTEGRITY_STEP = "Verify registration script integrity"
_SECRET_STEP = "Register workstream"
_SCRIPT = "tools/ci/register-workstream.sh"


def _read(path):
    with open(path) as f:
        return f.read()


def _job():
    return yaml.safe_load(_read(_WORKFLOW))["jobs"][_JOB]


def _step(name):
    for step in _job()["steps"]:
        if step.get("name") == name:
            return step
    raise AssertionError("no step named %r in job %r" % (name, _JOB))


class RegisterWorkstreamWiringTests(unittest.TestCase):
    """Pins the script-integrity gate in front of the secret-bearing step."""

    def test_the_secret_bearing_step_carries_the_secret(self):
        """Sanity check that this test is looking at the right step."""
        env = _step(_SECRET_STEP).get("env", {})
        self.assertIn("CF_ACCESS_CLIENT_SECRET", env)

    def test_the_secret_bearing_step_is_gated_on_script_integrity(self):
        condition = _step(_SECRET_STEP).get("if", "")
        self.assertIn("script_integrity", condition)
        self.assertIn("skip", condition)

    def test_the_integrity_step_runs_before_the_secret_bearing_step(self):
        names = [s.get("name") for s in _job()["steps"]]
        self.assertIn(_INTEGRITY_STEP, names)
        self.assertLess(names.index(_INTEGRITY_STEP), names.index(_SECRET_STEP))

    def test_the_integrity_step_has_the_id_the_gate_condition_references(self):
        self.assertEqual(_step(_INTEGRITY_STEP).get("id"), "script_integrity")

    def test_the_integrity_step_diffs_the_registration_script_against_master(self):
        script = _step(_INTEGRITY_STEP)["run"]
        self.assertIn(_SCRIPT, script)
        self.assertIn("origin/master", script)
        self.assertIn("skip=true", script)

    def test_the_integrity_step_exempts_ci_branches(self):
        """Matches the carve-out used by test-integrity-check's own tampering check."""
        script = _step(_INTEGRITY_STEP)["run"]
        self.assertTrue(re.search(r"\^ci/", script), "no ci/... branch carve-out found")

    def test_the_integrity_step_does_not_interpolate_the_branch_name_into_the_script(self):
        """Regression guard for script injection via a crafted branch name.

        `steps.ctx.outputs.branch` derives from `github.head_ref`, which is
        attacker-controlled on a pull_request run and may contain shell
        metacharacters (git refs permit backticks, `$()`, `;`, quotes).
        Template-substituting it directly into the `run:` body would let a
        crafted branch name inject commands into this step, which runs
        immediately before the step that executes
        tools/ci/register-workstream.sh with the registration secret
        attached in the same job/workspace. The branch name must be passed
        through `env:` and referenced as a shell variable instead.
        """
        step = _step(_INTEGRITY_STEP)
        self.assertNotIn("${{", step["run"],
                          "the script body must not contain a GitHub Actions "
                          "expression — untrusted values belong in env:, not "
                          "interpolated into the shell script")
        self.assertEqual(step.get("env", {}).get("BRANCH"),
                          "${{ steps.ctx.outputs.branch }}")
        self.assertIn("BRANCH", step["run"])

    def test_the_checkout_fetches_enough_history_to_diff_against_master(self):
        checkout = _step("Checkout Code")
        self.assertEqual(checkout.get("with", {}).get("fetch-depth"), 0)

    def test_master_is_fetched_before_the_diff_is_computed(self):
        names = [s.get("name") for s in _job()["steps"]]
        self.assertIn("Fetch base branch", names)
        self.assertLess(names.index("Fetch base branch"), names.index(_INTEGRITY_STEP))

    def test_the_job_still_does_not_gate_on_the_validation_jobs(self):
        """This job is deliberately independent of code-policy/checkstyle/etc.

        The script-integrity check exists precisely because those gates no
        longer run first — if this job's `needs` ever grew them back, the
        rationale for the integrity step (and this whole test module) would
        need to be revisited, not silently left in place.
        """
        self.assertEqual(_job()["needs"], ["changes", "build"])


if __name__ == "__main__":
    unittest.main()
