"""Guard: "Build and Test" splits into validate runs and deployment-started test runs.

``analysis.yaml`` runs in one of two modes, decided by the triggering event
(see "Validate and Test Runs" in ``.github/CLAUDE.md``):

* a VALIDATE run (``pull_request``) runs the static and integrity checks and
  reports its gate as ``All Checks``;
* a TEST run (a ``deployment`` to the ``test`` environment, a push to master,
  ``workflow_dispatch``) also runs every long-running test lane and reports
  its gate as ``Test Checks``.

Both names are required checks, so the merge gate rests on a handful of
expressions: the gate's dynamic name, the ``if:`` of ``changes``, ``analysis``
and ``all-checks`` (which keep a deployment to any other environment from
running anything or reporting ``Test Checks``), the ``run_tests`` gating of
every long-running lane, and the per-mode concurrency group. These tests
evaluate those expressions for each kind of event rather than only matching
their text, so a rewrite that changes their meaning fails here.
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOWS = os.path.join(_REPO_ROOT, ".github", "workflows")
_ANALYSIS = os.path.join(_WORKFLOWS, "analysis.yaml")
_START_TESTS = os.path.join(_WORKFLOWS, "start-tests.yaml")
_CI_GUIDE = os.path.join(_REPO_ROOT, ".github", "CLAUDE.md")

# Every job that a validate run must not start: the long-running lanes on the
# self-hosted fleet, and the remediation job that reads their results.
_TEST_RUN_ONLY = [
    "test", "test-media", "test-mac", "test-media-mac", "test-cl", "test-media-cl",
    "test-cuda", "test-media-cuda", "test-flowtree", "auto-resolve",
]

_REPO = "almostrealism/common"


def _load(path):
    with open(path) as f:
        return yaml.safe_load(f)


def _jobs():
    return _load(_ANALYSIS)["jobs"]


def _normalize(text):
    return " ".join(str(text).split())


def _strip_braces(expression):
    expression = _normalize(expression)
    if expression.startswith("${{") and expression.endswith("}}"):
        expression = expression[3:-2].strip()
    return expression


def _context(event_name, environment=None, ref="refs/heads/feature", head_repo=_REPO,
             run_tests=None):
    """Return the expression context of a run started by ``event_name``."""
    values = {
        "github.event_name": event_name,
        "github.repository": _REPO,
        "github.ref": ref,
        "github.ref_name": ref.rsplit("/", 1)[-1],
        "github.head_ref": "feature" if event_name == "pull_request" else "",
        "github.run_attempt": "1",
        "github.run_id": "42",
    }
    if event_name == "pull_request":
        values["github.event.pull_request.head.repo.full_name"] = head_repo
    if event_name == "deployment":
        values["github.event.deployment.environment"] = environment
    if run_tests is not None:
        values["needs.changes.outputs.run_tests"] = run_tests
    return values


def _evaluate(expression, context, cancelled=False):
    """Evaluate a GitHub Actions expression made of the operators used here.

    ``&&`` and ``||`` return an operand, as Python's ``and``/``or`` do, and a
    missing context property is null, which compares unequal to any string.
    ``format`` is not supported; callers evaluate only expressions without it.
    """
    python = _strip_braces(expression)
    python = re.sub(r"\b((?:github|needs)(?:\.[A-Za-z_][\w-]*)+)", r'ctx("\1")', python)
    python = python.replace("'", '"')
    python = python.replace("&&", " and ").replace("||", " or ")
    python = re.sub(r"!(?!=)", " not ", python)
    namespace = {
        "ctx": lambda name: context.get(name),
        "cancelled": lambda: cancelled,
        "always": lambda: True,
        "fromJSON": int,
    }
    return eval(python, namespace)  # noqa: S307 - the input is this repository's workflow


class GateNameTest(unittest.TestCase):
    """The gate job reports as All Checks, Test Checks or neither."""

    def setUp(self):
        self.name = _jobs()["all-checks"]["name"]

    def test_pull_request_reports_all_checks(self):
        self.assertEqual("All Checks", _evaluate(self.name, _context("pull_request")))

    def test_test_deployment_reports_test_checks(self):
        self.assertEqual("Test Checks", _evaluate(self.name, _context("deployment", "test")))

    def test_push_and_dispatch_report_test_checks(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assertEqual("Test Checks", _evaluate(self.name, _context(event)))

    def test_other_deployments_never_report_test_checks(self):
        for environment in ("production", "worker", "start-tests", "Test", ""):
            with self.subTest(environment=environment):
                self.assertEqual("Not a test deployment",
                                 _evaluate(self.name, _context("deployment", environment)))

    def test_no_other_job_can_be_named_like_a_gate(self):
        for job_name, job in _jobs().items():
            if job_name == "all-checks":
                continue
            with self.subTest(job=job_name):
                self.assertNotIn("Test Checks", str(job.get("name", "")))
                self.assertNotIn("All Checks", str(job.get("name", "")))

    def test_ci_guide_documents_the_same_expression(self):
        with open(_CI_GUIDE) as f:
            guide = _normalize(f.read())
        self.assertIn(_normalize(self.name), guide)


class EnvironmentFilterTest(unittest.TestCase):
    """Only a deployment to "test" runs anything; the gate runs even when cancelled."""

    def setUp(self):
        self.jobs = _jobs()

    def _runs(self, job, context, cancelled=False):
        return bool(_evaluate(self.jobs[job]["if"], context, cancelled))

    def test_changes_skips_other_deployments(self):
        self.assertFalse(self._runs("changes", _context("deployment", "production")))
        self.assertTrue(self._runs("changes", _context("deployment", "test")))
        self.assertTrue(self._runs("changes", _context("pull_request")))
        self.assertTrue(self._runs("changes", _context("push", ref="refs/heads/master")))

    def test_analysis_runs_only_in_test_runs(self):
        self.assertTrue(self._runs("analysis", _context("deployment", "test")))
        self.assertTrue(self._runs("analysis", _context("push", ref="refs/heads/master")))
        self.assertFalse(self._runs("analysis", _context("pull_request")))
        for environment in ("production", "worker", "start-tests"):
            with self.subTest(environment=environment):
                self.assertFalse(self._runs("analysis", _context("deployment", environment)))
        self.assertFalse(self._runs("analysis", _context("deployment", "test"), cancelled=True))

    def test_gate_reports_a_cancelled_run(self):
        self.assertIn("always()", _normalize(self.jobs["all-checks"]["if"]))
        self.assertTrue(self._runs("all-checks", _context("deployment", "test"), cancelled=True))
        self.assertTrue(self._runs("all-checks", _context("pull_request"), cancelled=True))

    def test_gate_skips_other_deployments(self):
        self.assertFalse(self._runs("all-checks", _context("deployment", "production")))

    def test_analysis_waits_for_the_same_test_lanes_as_the_gate(self):
        gate_needs = set(self.jobs["all-checks"]["needs"])
        for job in self.jobs["analysis"]["needs"]:
            with self.subTest(job=job):
                self.assertIn(job, gate_needs)


class RunKindTest(unittest.TestCase):
    """``changes`` publishes run_tests, and every long-running lane honours it."""

    def setUp(self):
        self.jobs = _jobs()

    def test_deployment_is_a_trigger(self):
        triggers = _load(_ANALYSIS)[True]
        self.assertIn("deployment", triggers)
        self.assertIn("pull_request", triggers)

    def _kind_step(self):
        for step in self.jobs["changes"]["steps"]:
            if step.get("id") == "kind":
                return step
        self.fail("changes has no step with id 'kind'")

    def _run_kind(self, event_name):
        bash = shutil.which("bash")
        self.assertIsNotNone(bash, "bash is required to run the kind step")
        with tempfile.TemporaryDirectory() as tmp:
            output = os.path.join(tmp, "output")
            open(output, "w").close()
            env = dict(os.environ, EVENT_NAME=event_name, GITHUB_OUTPUT=output)
            subprocess.run([bash, "-e", "-c", self._kind_step()["run"]], env=env, check=True,
                           capture_output=True)
            with open(output) as f:
                return f.read().strip()

    def test_pull_request_is_a_validate_run(self):
        self.assertEqual("run_tests=false", self._run_kind("pull_request"))

    def test_every_other_event_is_a_test_run(self):
        for event in ("deployment", "push", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assertEqual("run_tests=true", self._run_kind(event))

    def test_run_tests_is_a_job_output(self):
        outputs = self.jobs["changes"]["outputs"]
        self.assertEqual("${{ steps.kind.outputs.run_tests }}", outputs["run_tests"])

    def test_long_running_lanes_are_test_run_only(self):
        for job in _TEST_RUN_ONLY:
            with self.subTest(job=job):
                condition = _normalize(self.jobs[job]["if"])
                self.assertIn("needs.changes.outputs.run_tests == 'true' &&", condition)
                self.assertIn("changes", self.jobs[job]["needs"])
                self.assertFalse(_evaluate(condition, _context("pull_request", run_tests="false")))

    def test_auto_review_is_validate_run_only(self):
        condition = _normalize(self.jobs["auto-review"]["if"])
        self.assertIn("github.event_name == 'pull_request'", condition)


class ConcurrencyTest(unittest.TestCase):
    """Validate and test runs of one branch never share a concurrency group."""

    def _group(self, context):
        group = _normalize(_load(_ANALYSIS)["concurrency"]["group"])
        # The master-rerun suffix uses format(); it is empty off master.
        suffix = re.search(r"\$\{\{ github\.ref == 'refs/heads/master'.*\}\}$", group)
        self.assertIsNotNone(suffix, "the rerun suffix moved; update this test")
        group = group[:suffix.start()]
        return re.sub(r"\$\{\{(.*?)\}\}", lambda m: str(_evaluate(m.group(1), context)), group)

    def test_modes_have_separate_groups(self):
        validate = self._group(_context("pull_request"))
        test = self._group(_context("deployment", "test"))
        self.assertEqual("build-and-test-almostrealism/common-feature-validate", validate)
        self.assertEqual("build-and-test-almostrealism/common-feature-test", test)

    def test_forks_have_separate_groups(self):
        fork = self._group(_context("pull_request", head_repo="someone/common"))
        self.assertEqual("build-and-test-someone/common-feature-validate", fork)


class CancelSupersededTestRunsTest(unittest.TestCase):
    """The validate run cancels older test runs of its own branch only."""

    def setUp(self):
        self.jobs = _jobs()
        self.job = self.jobs["cancel-superseded-test-runs"]

    def test_runs_for_same_repository_pull_requests_only(self):
        condition = self.job["if"]
        self.assertTrue(_evaluate(condition, _context("pull_request")))
        self.assertFalse(_evaluate(condition, _context("pull_request", head_repo="someone/common")))
        self.assertFalse(_evaluate(condition, _context("deployment", "test")))

    def test_filters_by_head_repository_and_sha(self):
        script = self.job["steps"][0]["run"]
        self.assertIn("-f event=deployment", script)
        self.assertIn('.head_repository.full_name == \\"${REPO}\\"', script)
        self.assertIn('.head_sha != \\"${HEAD_SHA}\\"', script)

    def test_is_the_only_job_with_actions_write_and_checks_out_nothing(self):
        holders = [name for name, job in self.jobs.items()
                   if (job.get("permissions") or {}).get("actions") == "write"]
        self.assertEqual(["cancel-superseded-test-runs"], holders)
        for step in self.job["steps"]:
            self.assertNotIn("uses", step)


class StartTestsWorkflowTest(unittest.TestCase):
    """The manual button creates a test deployment, from master only."""

    def setUp(self):
        self.workflow = _load(_START_TESTS)
        self.job = self.workflow["jobs"]["start"]

    def test_runs_from_master_in_the_protected_environment(self):
        self.assertEqual("github.ref == 'refs/heads/master'", self.job["if"])
        self.assertEqual("start-tests", self.job["environment"])
        # Only contents:read, for resolving the branch input; the deployment is
        # created with the START_TESTS_TOKEN secret, not github.token.
        self.assertEqual({"contents": "read"}, self.job["permissions"])
        self.assertIn("workflow_dispatch", self.workflow[True])

    def _gh_stub(self, directory, git_ref_exit):
        """Write a fake ``gh`` that fails the branch lookup with ``git_ref_exit``."""
        gh = os.path.join(directory, "gh")
        with open(gh, "w") as f:
            f.write(
                "#!/usr/bin/env bash\n"
                "for a in \"$@\"; do\n"
                "  case \"$a\" in\n"
                "    */git/ref/heads/*) exit %d;;\n"
                "  esac\n"
                "done\n"
                "echo 'Created deployment 1'\n" % git_ref_exit
            )
        os.chmod(gh, 0o755)
        return gh

    def test_rejects_a_ref_that_is_not_a_branch(self):
        """A pull ref or raw SHA (fork-controlled code) is refused, not deployed."""
        bash = shutil.which("bash")
        self.assertIsNotNone(bash)
        with tempfile.TemporaryDirectory() as tmp:
            self._gh_stub(tmp, git_ref_exit=1)
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"],
                       GH_TOKEN="deploy-token", VALIDATE_TOKEN="validate-token",
                       REPO=_REPO, BRANCH="refs/pull/5/head")
            result = subprocess.run([bash, "-e", "-c", self.job["steps"][0]["run"]], env=env,
                                    cwd=tmp, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn("is not a branch", result.stdout)
            self.assertNotIn("Created deployment", result.stdout)

    def test_deploys_a_branch_that_resolves(self):
        """A ref that resolves under refs/heads is deployed."""
        bash = shutil.which("bash")
        self.assertIsNotNone(bash)
        if shutil.which("jq") is None:
            self.skipTest("jq is required to build the deployment request")
        with tempfile.TemporaryDirectory() as tmp:
            self._gh_stub(tmp, git_ref_exit=0)
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"],
                       GH_TOKEN="deploy-token", VALIDATE_TOKEN="validate-token",
                       REPO=_REPO, BRANCH="feature")
            result = subprocess.run([bash, "-e", "-c", self.job["steps"][0]["run"]], env=env,
                                    cwd=tmp, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Created deployment", result.stdout)

    def test_deployment_request_body(self):
        jq = shutil.which("jq")
        self.assertIsNotNone(jq, "jq is required to build the deployment request")
        script = self.job["steps"][0]["run"]
        program = re.search(r"jq -n --arg ref \"\$BRANCH\" '(.*?)' > deployment.json",
                            script, re.DOTALL)
        self.assertIsNotNone(program, "the deployment body is no longer built with jq")
        result = subprocess.run([jq, "-n", "-c", "--arg", "ref", "my-branch", program.group(1)],
                                check=True, capture_output=True, text=True)
        body = yaml.safe_load(result.stdout)
        self.assertEqual("my-branch", body["ref"])
        self.assertEqual("test", body["environment"])
        self.assertIs(False, body["auto_merge"])
        self.assertEqual([], body["required_contexts"])
        self.assertIs(False, body["production_environment"])

    def test_refuses_to_run_without_the_token(self):
        bash = shutil.which("bash")
        self.assertIsNotNone(bash)
        env = dict(os.environ, GH_TOKEN="", REPO=_REPO, BRANCH="feature")
        result = subprocess.run([bash, "-e", "-c", self.job["steps"][0]["run"]], env=env,
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("START_TESTS_TOKEN is not set", result.stdout)


if __name__ == "__main__":
    unittest.main()
