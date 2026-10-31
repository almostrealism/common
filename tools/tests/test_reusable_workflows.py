"""Guard: the reusable workflows other repositories call run only trusted code.

``goal-decomposition.yaml``, ``task-planning.yaml``, ``implement-plan.yaml``
and ``plan-review.yaml`` are called from other repositories' workflows, and
from this repository's own. Inside a called workflow a plain checkout fetches
the *caller's* repository, so a script run from the working tree would be the
caller's copy, and on a dispatched branch that copy is whatever the branch
says. Each workflow therefore checks this repository out explicitly
(``framework-repository`` at ``framework-ref``) and runs every script and
prompt template from that checkout. None of that is visible in a passing run,
so it is checked here, together with the steps whose behaviour a consumer
depends on: resolving the release from a plain version file, and telling a
documentation-only branch from any other.
"""

import os
import shutil
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOWS = os.path.join(_REPO_ROOT, ".github", "workflows")
_REUSABLE = ("goal-decomposition.yaml", "task-planning.yaml",
             "implement-plan.yaml", "plan-review.yaml", "qa-round.yaml")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True

_FRAMEWORK_REPOSITORY = "${{ inputs.framework-repository }}"
_FRAMEWORK_REF = "${{ inputs.framework-ref }}"


def _load(name):
    with open(os.path.join(_WORKFLOWS, name)) as f:
        return yaml.safe_load(f)


def _framework_checkouts(job):
    """Returns the ``with`` of every checkout of the framework in ``job``."""
    return [step.get("with", {}) for step in job["steps"]
            if str(step.get("uses", "")).startswith("actions/checkout")
            and step.get("with", {}).get("repository") == _FRAMEWORK_REPOSITORY]


def _step_script(workflow, job, name):
    return next(s for s in _load(workflow)["jobs"][job]["steps"] if s["name"] == name)


class ReusableWorkflowTests(unittest.TestCase):
    """Structural checks shared by every reusable workflow."""

    def setUp(self):
        self.workflows = {name: _load(name) for name in _REUSABLE}

    def test_each_is_only_called(self):
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                self.assertEqual(["workflow_call"], list(workflow[_ON]))

    def test_the_service_token_is_an_optional_explicit_secret(self):
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                secrets = workflow[_ON]["workflow_call"]["secrets"]
                self.assertEqual(["FLOWTREE_CF_ACCESS_CLIENT_SECRET"], list(secrets))
                self.assertIs(False, secrets["FLOWTREE_CF_ACCESS_CLIENT_SECRET"]["required"])

    def test_scripts_run_from_the_framework_checkout(self):
        """A script is run either through ``$FRAMEWORK``, the path the job
        checked the framework out to, or as ``./tools/ci`` in a job whose
        working tree is the framework checkout."""
        checked = 0
        for name, workflow in self.workflows.items():
            for job_name, job in workflow["jobs"].items():
                framework = _framework_checkouts(job)
                for step in job["steps"]:
                    run = step.get("run", "")
                    if "tools/ci/" not in run:
                        continue
                    checked += 1
                    with self.subTest(workflow=name, job=job_name, step=step["name"]):
                        self.assertTrue(framework, "no framework checkout in this job")
                        self.assertNotIn("${{ github.workspace }}/tools/ci", run)
                        if "$FRAMEWORK/tools/ci/" in run:
                            path = job["env"]["FRAMEWORK"]
                            self.assertIn(path.split("}}/", 1)[1],
                                          [c.get("path") for c in framework])
                        else:
                            self.assertIn("./tools/ci/", run)
                            self.assertIn(None, [c.get("path") for c in framework])
        self.assertGreaterEqual(checked, 15)

    def test_the_framework_checkout_is_pinned_and_holds_no_credentials(self):
        for name, workflow in self.workflows.items():
            for job_name, job in workflow["jobs"].items():
                for checkout in _framework_checkouts(job):
                    with self.subTest(workflow=name, job=job_name):
                        self.assertEqual(_FRAMEWORK_REF, checkout["ref"])
                        self.assertIs(False, checkout["persist-credentials"])

    def test_the_token_reaches_scripts_only_through_the_environment(self):
        """A secret interpolated into ``run`` is written into the script
        itself; and a step holding it must reach the controller by the
        caller's URL, or the default when the caller passes an unset
        variable as an empty string."""
        for name, workflow in self.workflows.items():
            env_of = {}
            for job_name, job in workflow["jobs"].items():
                env_of = dict(job.get("env", {}))
                for step in job["steps"]:
                    with self.subTest(workflow=name, job=job_name, step=step.get("name")):
                        self.assertNotIn("secrets.", step.get("run", ""))
                        env = dict(env_of, **step.get("env", {}))
                        if "CF_ACCESS_CLIENT_SECRET" not in env:
                            continue
                        self.assertEqual("${{ secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET }}",
                                         env["CF_ACCESS_CLIENT_SECRET"])
                        self.assertEqual("${{ inputs.cf-access-client-id }}",
                                         env["CF_ACCESS_CLIENT_ID"])
                        self.assertEqual(
                            "${{ inputs.controller-url || 'https://flowtree.almostrealism.ai' }}",
                            env["CONTROLLER_URL"])

    def test_the_plan_review_token_job_checks_out_only_the_framework(self):
        """The review's branch is read in a token-free job; the job that
        submits the review never has the branch's files in its tree."""
        jobs = self.workflows["plan-review.yaml"]["jobs"]
        self.assertNotIn("FLOWTREE_CF_ACCESS_CLIENT_SECRET", str(jobs["detect"]))
        review = jobs["review"]
        checkouts = [s for s in review["steps"]
                     if str(s.get("uses", "")).startswith("actions/checkout")]
        self.assertEqual(1, len(checkouts))
        self.assertEqual(_FRAMEWORK_REPOSITORY, checkouts[0]["with"]["repository"])
        self.assertEqual("needs.detect.outputs.docs_only == 'true'", review["if"])

    def test_the_task_planning_release_is_the_callers_project(self):
        """The round plans the caller's own release, so the release step names
        the caller's project as its own framework: a consumer's framework pin
        has nothing to do with which of its tasks it plans."""
        step = _step_script("task-planning.yaml", "plan", "Resolve the release")
        self.assertEqual("release", step["id"])
        self.assertEqual("${{ inputs.project }}", step["env"]["FRAMEWORK_PROJECT"])
        self.assertEqual("${{ inputs.version-file }}", step["env"]["VERSION_FILE"])
        job = self.workflows["task-planning.yaml"]["jobs"]["plan"]
        self.assertEqual("${{ inputs.project }}", job["env"]["TRACKER_PROJECT"])


class TaskPlanningReleaseStepTests(unittest.TestCase):
    """The release step of task-planning.yaml, run for real."""

    def _run(self, files, version_file):
        tmp = tempfile.mkdtemp(prefix="task-planning-release-")
        self.addCleanup(shutil.rmtree, tmp)
        for path, text in files.items():
            with open(os.path.join(tmp, path), "w") as f:
                f.write(text)
        output = os.path.join(tmp, "github-output")
        step = _step_script("task-planning.yaml", "plan", "Resolve the release")
        env = dict(os.environ)
        env.update({"FRAMEWORK": _REPO_ROOT, "TRACKER_PROJECT": "App",
                    "VERSION_FILE": version_file, "FRAMEWORK_PROJECT": "App",
                    "GITHUB_OUTPUT": output})
        result = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=tmp, env=env,
                                capture_output=True, text=True, timeout=60)
        outputs = {}
        if os.path.exists(output):
            with open(output) as f:
                outputs = dict(line.split("=", 1) for line in f.read().splitlines())
        return result, outputs

    def test_a_plain_version_file_names_the_release(self):
        """A consumer with no framework pin in a pom.xml still resolves."""
        result, outputs = self._run({"version.txt": "0.41\n"}, "version.txt")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("App 0.41", outputs["release"])
        self.assertIn("::notice::Planning tasks of release 'App 0.41'", result.stdout)

    def test_an_unreadable_version_fails_the_step(self):
        result, _ = self._run({"version.txt": "not a version\n"}, "version.txt")
        self.assertNotEqual(0, result.returncode)


class PlanReviewDocsCheckTests(unittest.TestCase):
    """The docs-only check of plan-review.yaml, run for real."""

    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="plan-review-")
        self.addCleanup(shutil.rmtree, self.repo)
        self._git("init", "-q", "-b", "master")
        self._commit("README.md", "base\n")
        self._git("update-ref", "refs/remotes/origin/master", "HEAD")
        self._git("checkout", "-q", "-b", "project/task-1")

    def _git(self, *args):
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
                       cwd=self.repo, check=True, capture_output=True, timeout=60)

    def _commit(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)
        self._git("add", path)
        self._git("commit", "-q", "-m", "change " + path)

    def _docs_only(self):
        step = _step_script("plan-review.yaml", "detect", "Check for docs-only changes")
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, check=True,
                              capture_output=True, text=True, timeout=60).stdout.strip()
        output = os.path.join(self.repo, ".git", "github-output")
        env = dict(os.environ)
        env.update({"BASE_BRANCH": "master", "HEAD_SHA": head, "GITHUB_OUTPUT": output})
        result = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=self.repo, env=env,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        with open(output) as f:
            return f.read().strip()

    def test_a_plan_branch_is_documentation_only(self):
        self._commit("docs/plans/PLAN-20261020-thing.md", "# Plan\n")
        self.assertEqual("docs_only=true", self._docs_only())

    def test_any_change_outside_docs_is_not(self):
        self._commit("docs/plans/PLAN-20261020-thing.md", "# Plan\n")
        self._commit("src/Thing.java", "class Thing {}\n")
        self.assertEqual("docs_only=false", self._docs_only())

    def test_a_branch_with_no_changes_is_not(self):
        self.assertEqual("docs_only=false", self._docs_only())


class ImplementPlanDetectStepTests(unittest.TestCase):
    """The plan detection of implement-plan.yaml, run for real against a
    consumer whose default branch and plans directory are not this
    repository's."""

    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="implement-plan-")
        self.addCleanup(shutil.rmtree, self.repo)
        self._git("init", "-q", "-b", "main")
        self._commit("README.md", "base\n")
        self._commit("planning/EXISTING.md", "# Existing plan\n")
        self._git("update-ref", "refs/remotes/origin/main", "HEAD")
        self._git("checkout", "-q", "-b", "project/task-1")

    def _git(self, *args):
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
                       cwd=self.repo, check=True, capture_output=True, timeout=60)

    def _commit(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)
        self._git("add", path)
        self._git("commit", "-q", "-m", "change " + path)

    def _detect(self, plan_file=""):
        step = _step_script("implement-plan.yaml", "detect-plan", "Detect plan file")
        output = os.path.join(self.repo, ".git", "github-output")
        env = dict(os.environ)
        env.update({"PLAN_FILE_INPUT": plan_file, "PLANS_DIRECTORY": "planning/",
                    "DEFAULT_BRANCH": "main", "GITHUB_OUTPUT": output})
        result = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=self.repo, env=env,
                                capture_output=True, text=True, timeout=60)
        outputs = {}
        if os.path.exists(output):
            with open(output) as f:
                outputs = dict(line.split("=", 1) for line in f.read().splitlines())
        return result, outputs

    def test_a_new_plan_in_the_plans_directory_is_detected(self):
        self._commit("planning/PLAN-20261021-thing.md", "# Plan\n")
        result, outputs = self._detect()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("planning/PLAN-20261021-thing.md", outputs["plan_file"])
        self.assertEqual("true", outputs["is_new_plan"])

    def test_the_manager_log_and_documents_elsewhere_are_not_plans(self):
        self._commit("planning/MANAGER_LOG.md", "# Log\n")
        self._commit("docs/plans/PLAN-20261021-thing.md", "# Elsewhere\n")
        result, outputs = self._detect()
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("plan_file", outputs)
        self.assertIn("No new plan document found in planning/", result.stdout)

    def test_a_branch_with_no_new_plan_fails(self):
        self._commit("planning/EXISTING.md", "# Existing plan, edited\n")
        result, outputs = self._detect()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, outputs)

    def test_an_explicit_plan_that_predates_the_branch_is_not_new(self):
        result, outputs = self._detect("planning/EXISTING.md")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("planning/EXISTING.md", outputs["plan_file"])
        self.assertEqual("false", outputs["is_new_plan"])

    def test_an_explicit_plan_added_on_the_branch_is_new(self):
        self._commit("planning/PLAN-20261021-thing.md", "# Plan\n")
        result, outputs = self._detect("planning/PLAN-20261021-thing.md")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", outputs["is_new_plan"])

    def test_a_missing_explicit_plan_fails(self):
        result, outputs = self._detect("planning/MISSING.md")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Plan file not found: planning/MISSING.md", result.stdout)
        self.assertEqual({}, outputs)


if __name__ == "__main__":
    unittest.main()
