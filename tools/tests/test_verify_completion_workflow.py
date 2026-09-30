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
- The scripts it runs with the Cloudflare Access service token attached must
  come from the trusted default branch, not the dispatched branch's own tree,
  and no job that holds the token may run any of the branch's own code — not
  even a prompt builder, whose writes to ``$GITHUB_ENV``/``$GITHUB_PATH`` would
  survive a re-checkout and steer the trusted submit step. Manual dispatch is
  not a code-integrity boundary: a branch author who edited a script the
  token-bearing job runs, or the prompt builder that runs beside it, could
  otherwise exfiltrate the token when the workflow is dispatched on that branch.
  So the prompt is built in a separate, token-free job and handed to the submit
  job as an artifact.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "verify-completion.yaml")
_DOC = os.path.join(_REPO_ROOT, "flowtree", "runtime", "docs", "ci-integration.md")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True

# The scripts that run with the controller service token attached. The prompt
# is submitted through submit-staged-request.sh (which reads the staged request
# through a key allowlist), never submit-agent-job.sh directly, so that the
# token-bearing job runs only trusted default-branch code.
_CONTROLLER_SCRIPTS = ("register-workstream.sh", "submit-staged-request.sh")

# The trusted checkout the secret-bearing steps must run against: the repository
# default branch, which only carries reviewed code.
_TRUSTED_REF = "github.event.repository.default_branch"


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
        build_runs = " ".join(step.get("run", "")
                              for step in self.jobs["build-prompt"]["steps"])
        self.assertIn("build-verify-prompt.sh", build_runs)
        self.assertIn("stage-submit-request.sh", build_runs)
        submit_runs = " ".join(step.get("run", "")
                              for step in self.jobs["verify"]["steps"])
        self.assertIn("submit-staged-request.sh", submit_runs)

    def test_the_prompt_is_built_in_a_token_free_job(self):
        """The branch's prompt builder must not run in a job that holds the
        controller token. A prompt builder that appended ``BASH_ENV``/``PATH``/
        ``LD_PRELOAD`` to ``$GITHUB_ENV`` would otherwise steer the trusted
        submit step, since a re-checkout replaces the workspace but not the
        environment. Building the prompt in its own job, whose environment has
        never seen the branch's code, closes that.
        """
        def _holds_the_token(job):
            return any("CF_ACCESS_CLIENT_SECRET" in str(step.get("env", {}))
                       for step in job["steps"])

        for name, job in self.jobs.items():
            runs = " ".join(step.get("run", "") for step in job["steps"])
            if "build-verify-prompt.sh" in runs:
                with self.subTest(job=name):
                    self.assertFalse(
                        _holds_the_token(job),
                        "the prompt builder runs in a job that holds the token")
        # And the token-bearing submit job runs no branch prompt builder.
        self.assertTrue(_holds_the_token(self.jobs["verify"]))
        verify_runs = " ".join(step.get("run", "")
                               for step in self.jobs["verify"]["steps"])
        self.assertNotIn("build-verify-prompt.sh", verify_runs)

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

    def test_secret_bearing_scripts_run_from_the_trusted_checkout(self):
        """A step that runs a controller script with the service token must be
        preceded, in its own job, by a checkout of the default branch — never
        left running the dispatched branch's copy of that script."""
        checked = 0
        for name, job in self.jobs.items():
            last_checkout_ref = None
            for step in job["steps"]:
                if step.get("uses", "").startswith("actions/checkout"):
                    last_checkout_ref = str(step.get("with", {}).get("ref", ""))
                if not any(s in step.get("run", "") for s in _CONTROLLER_SCRIPTS):
                    continue
                checked += 1
                with self.subTest(job=name, step=step["name"]):
                    self.assertIsNotNone(last_checkout_ref,
                                         "secret-bearing step has no preceding checkout")
                    self.assertIn(_TRUSTED_REF, last_checkout_ref)
        self.assertEqual(len(_CONTROLLER_SCRIPTS), checked)

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

    def test_a_cancelled_run_does_not_submit_the_implementation(self):
        """The jobs downstream of registration gate on ``!cancelled()``, never
        ``always()``. ``always()`` runs a job even when the run was cancelled,
        so a run cancelled after registration (or after the prompt was staged)
        would still build and submit the implementation. ``!cancelled()`` stops
        the chain when the run is cancelled while keeping it running through a
        skipped register-workstream (the is_new_plan-false path)."""
        for name in ("build-prompt", "verify"):
            with self.subTest(job=name):
                condition = self.jobs[name]["if"]
                self.assertIn("!cancelled()", condition)
                self.assertNotIn("always()", condition)

    def test_build_prompt_does_not_run_after_cancelled_registration(self):
        """A cancelled register-workstream has result ``cancelled``, which a
        ``!= 'failure'`` test would let through. build-prompt must instead allow
        only registration that succeeded or was skipped, so a cancelled (or
        failed) registration blocks it."""
        condition = self.jobs["build-prompt"]["if"]
        self.assertIn("needs.register-workstream.result == 'success'", condition)
        self.assertIn("needs.register-workstream.result == 'skipped'", condition)
        self.assertNotIn("!= 'failure'", condition)

    def test_registration_runs_on_every_dispatch(self):
        """The plan's workstream settings are applied during registration, so
        registration cannot be limited to a newly added plan document: a
        dispatch naming an existing plan file would otherwise skip it and
        implement on whatever machine the workstream last targeted."""
        self.assertNotIn("if", self.jobs["register-workstream"])

    def test_the_plan_settings_are_read_as_data_by_trusted_code(self):
        """The settings file comes from the dispatched branch, and the job that
        applies it holds the controller token. So the branch's files are
        fetched with `git archive`, never checked out, and are read by the
        default branch's resolver; the only checkout in the job is the trusted
        one."""
        steps = self.jobs["register-workstream"]["steps"]
        checkouts = [s for s in steps if str(s.get("uses", "")).startswith("actions/checkout")]
        self.assertEqual(1, len(checkouts))
        self.assertIn(_TRUSTED_REF, str(checkouts[0].get("with", {}).get("ref", "")))

        resolve = next(s for s in steps if "plan_workstream_config.py" in s.get("run", ""))
        self.assertIn("git archive FETCH_HEAD docs/plans", resolve["run"])
        self.assertIn("./tools/ci/plan_workstream_config.py", resolve["run"])
        self.assertNotIn("CF_ACCESS_CLIENT_SECRET", resolve.get("env", {}))

        register = next(s for s in steps if "register-workstream.sh" in s.get("run", ""))
        self.assertIn("steps.plan_settings.outputs.required_labels",
                      register["env"]["REQUIRED_LABELS_JSON"])
        self.assertLess(steps.index(resolve), steps.index(register))

    def test_the_plan_settings_come_from_the_dispatched_commit(self):
        """The approved plan is the dispatched commit, and every other job in
        the run reads `github.sha`. Fetching the branch head instead would let a
        push made after approval replace the settings, routing the
        implementation with labels nobody approved. A fetch failure must stop
        the run rather than submit the implementation without the labels."""
        steps = self.jobs["register-workstream"]["steps"]
        resolve = next(s for s in steps if "plan_workstream_config.py" in s.get("run", ""))
        self.assertEqual("${{ github.sha }}", resolve["env"]["DISPATCHED_SHA"])
        self.assertIn('origin "$DISPATCHED_SHA"', resolve["run"])
        self.assertNotIn("refs/heads/", resolve["run"])
        self.assertIn("exit 1", resolve["run"])

    def test_the_operator_documentation_describes_every_job(self):
        """ci-integration.md is where an operator learns what this workflow
        does. It once kept describing a three-job pipeline that submitted
        through submit-agent-job.sh after the workflow had moved to a
        token-free build-prompt job and submit-staged-request.sh, which sent
        readers to a submission path the workflow no longer takes."""
        with open(_DOC) as f:
            doc = f.read()
        start = doc.index("## Verify-Completion Workflow")
        section = doc[start:doc.index("\n## ", start + 1)]
        for name in self.jobs:
            with self.subTest(job=name):
                self.assertIn("`%s`" % name, section)
        for script in _CONTROLLER_SCRIPTS:
            with self.subTest(script=script):
                self.assertIn(script, section)
        self.assertNotIn("submit-agent-job.sh", section)


if __name__ == "__main__":
    unittest.main()
