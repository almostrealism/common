"""Tests for goal decomposition: the reusable decomposition round.

``goal-decomposition.yaml`` is called from other repositories' workflows. It
resolves which release a repository is building and where its release plan
is (``resolve-release-scope.sh``), registers a standing workstream that holds
the tracker "steward" role and has the framework checked out beside it
(``register-workstream.sh``), and submits an agent job that turns the release
plan into tracker tasks. The scripts are run here for real, against
throwaway repository layouts and a local stand-in for the controller; the
workflow's wiring is read from the YAML.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_task_planning_gates import _CI, _ControllerStub, _run  # noqa: E402

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "goal-decomposition.yaml")
_BUILDER = os.path.join(_CI, "prompts", "build-goal-decomposition-prompt.sh")
_TEMPLATE = os.path.join(_CI, "prompts", "goal-decomposition.txt")

_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <version>{version}</version>
  <properties>
    {properties}
  </properties>
</project>
"""


class ResolveReleaseScopeTests(unittest.TestCase):
    """resolve-release-scope.sh reads the release from the repository itself."""

    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="release-scope-")
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)

    def _write(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)

    def _pom(self, version="1.0", framework="0.76"):
        properties = "" if framework is None else (
            "<common.version>%s</common.version>" % framework)
        self._write("pom.xml", _POM.format(version=version, properties=properties))

    def _resolve(self, **env):
        full = dict(os.environ)
        full.pop("GITHUB_OUTPUT", None)
        full.update({"TRACKER_PROJECT": "App"})
        full.update(env)
        result = subprocess.run(
            ["bash", os.path.join(_CI, "resolve-release-scope.sh")],
            cwd=self.repo, env=full, capture_output=True, text=True, timeout=60)
        outputs = dict(line.split("=", 1) for line in result.stdout.splitlines()
                       if "=" in line and not line.startswith("::"))
        return result.returncode, outputs, result.stdout + result.stderr

    def test_a_plain_version_file_names_the_release_and_its_plan(self):
        self._pom()
        self._write("version.txt", "0.41\n")
        self._write("docs/plans/0.41/PLAN.md", "# Plan\n")
        self._write("docs/GOALS.md", "# Goals\n")

        code, out, log = self._resolve(VERSION_FILE="version.txt")

        self.assertEqual(0, code, log)
        self.assertEqual("App 0.41", out["release"])
        self.assertEqual("docs/plans/0.41", out["plan_directory"])
        self.assertEqual("docs/plans/0.41/PLAN.md", out["release_plan"])
        self.assertEqual("docs/GOALS.md", out["goals_document"])
        self.assertEqual("Common 0.76", out["framework_release"])
        self.assertEqual("true", out["in_scope"])

    def test_a_pom_version_file_is_read_as_the_project_version(self):
        self._pom(version="2.3")
        self._write("docs/plans/2.3/PLAN.md", "# Plan\n")

        code, out, log = self._resolve()

        self.assertEqual(0, code, log)
        self.assertEqual(("2.3", "App 2.3"), (out["version"], out["release"]))

    def test_no_release_plan_means_nothing_to_reconcile(self):
        self._pom()
        self._write("version.txt", "0.41")
        self._write("docs/plans/0.40/PLAN.md", "# The previous release\n")

        code, out, log = self._resolve(VERSION_FILE="version.txt")

        self.assertEqual(0, code, log)
        self.assertEqual("false", out["in_scope"])

    def test_a_plan_directory_without_its_plan_is_not_a_scope(self):
        self._pom()
        self._write("version.txt", "0.41")
        self._write("docs/plans/0.41/notes.md", "# Not the plan\n")

        _, out, _ = self._resolve(VERSION_FILE="version.txt")

        self.assertEqual("false", out["in_scope"])

    def test_a_missing_goals_document_is_reported_empty(self):
        self._pom()
        self._write("docs/plans/1.0/PLAN.md", "# Plan\n")

        _, out, _ = self._resolve()

        self.assertEqual("", out["goals_document"])

    def test_the_framework_is_its_own_framework_release(self):
        self._pom(version="0.76", framework=None)

        code, out, log = self._resolve(TRACKER_PROJECT="Common")

        self.assertEqual(0, code, log)
        self.assertEqual("Common 0.76", out["framework_release"])

    def test_the_locations_and_projects_are_configurable(self):
        self._write("pom.xml", _POM.format(
            version="5.0", properties="<base.version>9.1</base.version>"))
        self._write("plans/5.0/PLAN.md", "# Plan\n")

        code, out, log = self._resolve(
            PLANS_DIRECTORY="plans/", FRAMEWORK_PROJECT="Base",
            FRAMEWORK_VERSION_PROPERTY="base.version")

        self.assertEqual(0, code, log)
        self.assertEqual("plans/5.0/PLAN.md", out["release_plan"])
        self.assertEqual("Base 9.1", out["framework_release"])
        self.assertEqual("true", out["in_scope"])

    def test_a_missing_framework_property_is_an_error(self):
        self._pom(framework=None)

        code, _, log = self._resolve()

        self.assertEqual(1, code)
        self.assertIn("common.version", log)

    def test_a_version_that_is_not_one_path_component_is_an_error(self):
        self._pom()
        for version in ("", "../escape", "0.41 beta", "-rc"):
            with self.subTest(version=version):
                self._write("version.txt", version)
                code, _, _ = self._resolve(VERSION_FILE="version.txt")
                self.assertEqual(1, code)

    def test_a_missing_version_file_is_an_error(self):
        self._pom()

        code, _, log = self._resolve(VERSION_FILE="version.txt")

        self.assertEqual(1, code)
        self.assertIn("version.txt", log)

    def test_the_project_is_required(self):
        self._pom()

        code, _, _ = self._resolve(TRACKER_PROJECT="")

        self.assertEqual(1, code)


class DependentReposRegistrationTests(unittest.TestCase):
    """register-workstream.sh attaches dependent repositories to the workstream."""

    _ENV = {"BRANCH": "orchestration/goal-decomposition", "BASE_BRANCH": "master",
            "DEPENDENT_REPOS": " git@github.com:o/framework.git , ,git@github.com:o/lib.git"}
    _EXPECTED = ["git@github.com:o/framework.git", "git@github.com:o/lib.git"]

    def _register(self, stub):
        self.addCleanup(stub.close)
        env = dict(self._ENV, CONTROLLER_URL=stub.url)
        return _run("register-workstream.sh", env)

    def test_a_new_workstream_is_registered_with_them(self):
        stub = _ControllerStub()
        code, _, log = self._register(stub)
        self.assertEqual(0, code, log)
        self.assertEqual(self._EXPECTED, stub.seen[0][4]["dependentRepos"])

    def test_an_existing_workstream_is_updated_with_them(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub)
        self.assertEqual(0, code, log)
        method, path, _, _, body = stub.seen[1]
        self.assertEqual(("POST", "/api/workstreams/ws-1/update"), (method, path))
        self.assertEqual(self._EXPECTED, body["dependentRepos"])

    def test_a_failed_update_fails_the_registration(self):
        """Without the framework beside it, the agent cannot place its tasks."""
        stub = _ControllerStub(existing=True, update=(500, {"ok": False}))
        code, _, log = self._register(stub)
        self.assertEqual(1, code, log)

    def test_nothing_is_sent_when_none_are_given(self):
        stub = _ControllerStub()
        self.addCleanup(stub.close)
        code, _, log = _run("register-workstream.sh", {
            "BRANCH": "b", "BASE_BRANCH": "master", "CONTROLLER_URL": stub.url})
        self.assertEqual(0, code, log)
        self.assertNotIn("dependentRepos", stub.seen[0][4])


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _triggers(workflow):
    # PyYAML reads the bare key `on` as the boolean True.
    return workflow.get("on", workflow.get(True))


def _job():
    return _workflow()["jobs"]["decompose"]


def _steps():
    return _job()["steps"]


def _step(name):
    for step in _steps():
        if step.get("name") == name:
            return step
    raise AssertionError("no step named %r" % name)


def _index(name):
    return [s.get("name") for s in _steps()].index(name)


_SCOPE = "Resolve the release and its plan"
_INTAKE = "Check that the controller accepts automated jobs"
_MOVE = "Move the workstream branch to this commit"
_REGISTER = "Register the goal-decomposition workstream"
_BUILD = "Build the goal-decomposition prompt"
_SUBMIT = "Submit the goal-decomposition job"
_SIDE_EFFECTS = (_MOVE, _REGISTER, _SUBMIT)


class GoalDecompositionWorkflowTests(unittest.TestCase):
    """Pins the shape of goal-decomposition.yaml."""

    def test_it_is_only_ever_called_by_another_workflow(self):
        """The caller owns the trigger; this file must not start runs itself."""
        self.assertEqual(["workflow_call"], list(_triggers(_workflow())))

    def test_it_runs_only_on_the_default_branch(self):
        """Its tasks are ready to plan at once, so only approved documents count."""
        condition = _job().get("if", "")
        self.assertIn("github.ref_name", condition)
        self.assertIn("github.event.repository.default_branch", condition)
        # A tag sharing the default branch's name must not satisfy the guard.
        self.assertIn("github.ref_type == 'branch'", condition)

    def test_runs_in_one_repository_are_serialized_and_not_cancelled(self):
        concurrency = _job()["concurrency"]
        self.assertIn("github.repository", concurrency["group"])
        self.assertIs(False, concurrency["cancel-in-progress"])

    def test_every_script_comes_from_the_framework_checkout(self):
        """A plain checkout in a called workflow is the caller's repository."""
        scripts = 0
        for step in _steps():
            run = step.get("run", "")
            for match in re.finditer(r"(\S*)tools/ci/", run):
                scripts += 1
                self.assertEqual('"$FRAMEWORK/', match.group(1),
                                 "step %r runs a script outside the framework "
                                 "checkout" % step.get("name"))
        self.assertGreaterEqual(scripts, 5)

    def test_the_framework_checkout_is_explicit_and_keeps_no_credentials(self):
        checkouts = [s for s in _steps() if str(s.get("uses", "")).startswith("actions/checkout")]
        framework = [s for s in checkouts if "repository" in s.get("with", {})]
        self.assertEqual(1, len(framework))
        options = framework[0]["with"]
        self.assertEqual("${{ inputs.framework-repository }}", options["repository"])
        self.assertEqual("${{ inputs.framework-ref }}", options["ref"])
        self.assertIs(False, options["persist-credentials"])
        self.assertTrue(_job()["env"]["FRAMEWORK"].endswith("/" + options["path"]))

    def test_no_input_is_interpolated_into_a_script(self):
        """Inputs reach scripts through the environment, never as shell text."""
        for step in _steps():
            self.assertNotIn("${{ inputs.", step.get("run", ""), step.get("name"))

    def test_the_scope_is_resolved_before_the_controller_is_asked(self):
        self.assertLess(_index(_SCOPE), _index(_INTAKE))
        self.assertIn("steps.scope.outputs.in_scope == 'true'", _step(_INTAKE)["if"])

    def test_nothing_is_created_unless_intake_is_open(self):
        for name in _SIDE_EFFECTS + (_BUILD,):
            with self.subTest(step=name):
                self.assertLess(_index(_INTAKE), _index(name))
                self.assertEqual("steps.intake.outputs.open == 'true'", _step(name)["if"])

    def test_the_workstream_holds_the_steward_role_and_the_framework(self):
        env = _step(_REGISTER)["env"]
        self.assertEqual("steward", env["TRACKER_CAPABILITIES"])
        self.assertIn("inputs.framework-repository", env["DEPENDENT_REPOS"])
        self.assertIn("inputs.workstream-branch", env["BRANCH"])

    def test_the_branch_is_moved_before_the_workstream_is_registered(self):
        self.assertLess(_index(_MOVE), _index(_REGISTER))
        self.assertLess(_index(_REGISTER), _index(_SUBMIT))

    def test_the_workstream_branch_is_a_standing_workstream_by_default(self):
        """The orchestration/ prefix is what keeps the workstream from being archived."""
        default = _triggers(_workflow())["workflow_call"]["inputs"]["workstream-branch"]["default"]
        self.assertTrue(default.startswith("orchestration/"))

    def test_no_step_pins_model_configuration(self):
        for name in (_REGISTER, _SUBMIT):
            for var in _step(name).get("env", {}):
                for forbidden in ("MODEL", "EFFORT", "RUNNER", "PROVIDER", "PHASE"):
                    with self.subTest(step=name, var=var):
                        self.assertNotIn(forbidden, var.upper())

    def test_the_job_opens_no_pull_request_and_enforces_no_changes(self):
        env = _step(_SUBMIT)["env"]
        self.assertEqual("false", env["AUTO_CREATE_PR"])
        self.assertEqual("false", env["ENFORCE_CHANGES"])

    def test_the_prompt_step_supplies_every_variable_the_builder_requires(self):
        with open(_BUILDER) as f:
            required = re.search(r"^for var in ([A-Z_ ]+); do$", f.read(), re.MULTILINE)
        provided = set(_step(_BUILD)["env"])
        self.assertLessEqual(set(required.group(1).split()), provided)

    def test_tasks_carry_this_repository_in_their_source(self):
        """A repository's decomposition run may only update its own tasks."""
        self.assertEqual("goals:${{ github.repository }}/",
                         _step(_BUILD)["env"]["SOURCE_PREFIX"])

    def test_the_framework_is_checked_out_by_fetch_head_not_a_tracking_ref(self):
        """``framework-ref`` may be a tag; ``origin/<tag>`` would not resolve."""
        with open(_TEMPLATE) as f:
            template = f.read()
        self.assertIn("git fetch origin ${FRAMEWORK_BRANCH}", template)
        self.assertIn("git checkout --detach FETCH_HEAD", template)
        self.assertNotIn("checkout --detach origin/${FRAMEWORK_BRANCH}", template)

    def test_the_secret_is_declared_and_the_client_id_is_an_input(self):
        call = _triggers(_workflow())["workflow_call"]
        self.assertIn("FLOWTREE_CF_ACCESS_CLIENT_SECRET", call["secrets"])
        self.assertIn("cf-access-client-id", call["inputs"])
        self.assertEqual("number", call["inputs"]["max-new-tasks"]["type"])


if __name__ == "__main__":
    unittest.main()
