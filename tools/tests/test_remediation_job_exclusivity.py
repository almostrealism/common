"""Guard: a pipeline attempt submits at most one kind of remediation job.

"Build and Test" has three remediation jobs — ``auto-resolve-python`` (a
python-tests failure, submitted at once), ``auto-review`` (build failure, code
policy, quality gates, docs-only verify or the general review, submitted as
soon as the gates report) and ``auto-resolve`` (long-running test failures,
staged on attempt 3+ and submitted by "Auto-Resolve Submit" once the flaky-test
retries are spent). Two of them submitting for the same attempt would put two
agents on one branch making conflicting edits.

Nothing hands off between the jobs, so the exclusivity rests entirely on their
``if:`` conditions, on the attempt threshold matching the retry limit in
``auto-resolve-submit.yaml``, and on ``rerun-flaky-tests.sh`` never retrying a
run whose python-tests failed. These tests pin each of those, plus the rule
that no job running pull request code holds the controller credentials.
"""

import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOWS = os.path.join(_REPO_ROOT, ".github", "workflows")
_ANALYSIS = os.path.join(_WORKFLOWS, "analysis.yaml")
_SUBMIT_WORKFLOW = os.path.join(_WORKFLOWS, "auto-resolve-submit.yaml")
_RERUN_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "rerun-flaky-tests.sh")
_SUBMIT_STAGED = os.path.join(_REPO_ROOT, "tools", "ci", "submit-staged-request.sh")

_SECRET = "secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET"
_PYTHON_FAILED = "needs.python-tests.result == 'failure'"
_PYTHON_NOT_FAILED = "needs.python-tests.result != 'failure'"

# Each early producer and the job that submits what it staged.
_EARLY_PRODUCERS = {
    "auto-review": ("auto-review-submit", "auto-review-request"),
    "auto-resolve-python": ("auto-resolve-python-submit", "auto-resolve-python-request"),
}


def _load(path):
    with open(path) as f:
        return yaml.safe_load(f)


def _jobs():
    return _load(_ANALYSIS)["jobs"]


def _condition(job):
    return " ".join(str(job.get("if", "")).split())


def _attempt_threshold(condition, operator):
    match = re.search(r"fromJSON\(github\.run_attempt\) %s (\d+)" % re.escape(operator), condition)
    if not match:
        raise AssertionError("no `fromJSON(github.run_attempt) %s N` in %r" % (operator, condition))
    return int(match.group(1))


def _uploaded_artifacts(job):
    return [(step.get("with") or {}).get("name") for step in job.get("steps", [])
            if str(step.get("uses", "")).startswith("actions/upload-artifact")]


class RemediationJobConditionTests(unittest.TestCase):
    """The static wiring in analysis.yaml and auto-resolve-submit.yaml."""

    def test_auto_review_runs_on_the_first_attempt_only(self):
        """A retry re-runs auto-review whenever one of its inputs failed; its first
        agent is likely still working by then, so it must not submit again."""
        self.assertEqual(1, _attempt_threshold(_condition(_jobs()["auto-review"]), "=="))

    def test_auto_resolve_never_shares_an_attempt_with_auto_review(self):
        self.assertGreater(_attempt_threshold(_condition(_jobs()["auto-resolve"]), ">="), 1)

    def test_the_attempt_threshold_is_the_retry_limit(self):
        """auto-resolve must be staging on exactly the attempt retries stop at."""
        threshold = _attempt_threshold(_condition(_jobs()["auto-resolve"]), ">=")
        retry = _load(_SUBMIT_WORKFLOW)["jobs"]["retry-flaky-tests"]
        env = next(step["env"] for step in retry["steps"] if step.get("id") == "decide")
        self.assertEqual(str(threshold), str(env["MAX_ATTEMPTS"]))

    def test_a_python_failure_belongs_to_auto_resolve_python_alone(self):
        jobs = _jobs()
        self.assertIn(_PYTHON_FAILED, _condition(jobs["auto-resolve-python"]))
        for name in ("auto-review", "auto-resolve"):
            with self.subTest(job=name):
                self.assertIn("python-tests", jobs[name]["needs"])
                self.assertIn(_PYTHON_NOT_FAILED, _condition(jobs[name]))

    def test_test_flowtree_cannot_run_after_a_python_failure(self):
        """test-flowtree is retry-eligible slow-path work: it must not overlap the fast path."""
        job = _jobs()["test-flowtree"]
        self.assertIn("python-tests", job["needs"])
        condition = _condition(job)
        self.assertIn("needs.python-tests.result == 'success'", condition)
        self.assertIn("needs.python-tests.result == 'skipped'", condition)

    def test_no_remediation_job_runs_on_master(self):
        jobs = _jobs()
        for name in ("auto-review", "auto-resolve-python", "auto-resolve"):
            with self.subTest(job=name):
                self.assertIn("github.ref != 'refs/heads/master'", _condition(jobs[name]))

    def test_each_producer_stages_under_its_own_artifact_name(self):
        """Only auto-resolve's artifact is the one the workflow_run submitter reads."""
        jobs = _jobs()
        self.assertEqual(["auto-resolve-request"],
                         [n for n in _uploaded_artifacts(jobs["auto-resolve"]) if n.endswith("-request")])
        for producer, (_, artifact) in _EARLY_PRODUCERS.items():
            with self.subTest(job=producer):
                self.assertIn(artifact, _uploaded_artifacts(jobs[producer]))
                self.assertNotIn("auto-resolve-request", _uploaded_artifacts(jobs[producer]))

    def test_auto_resolve_does_not_stage_the_early_prompts(self):
        """auto-review owns the build, policy, quality-gate, verify and review prompts."""
        runs = " ".join(step.get("run", "") for step in _jobs()["auto-resolve"]["steps"])
        for builder in ("build-review-prompt.sh", "build-verify-prompt.sh",
                        "build-quality-gate-prompt.sh", "build-policy-violation-prompt.sh"):
            with self.subTest(builder=builder):
                self.assertNotIn(builder, runs)


class CredentialIsolationTests(unittest.TestCase):
    """No job that runs pull request code holds the controller secret."""

    def test_producers_hold_no_controller_secret(self):
        jobs = _jobs()
        for name in list(_EARLY_PRODUCERS) + ["auto-resolve"]:
            with self.subTest(job=name):
                self.assertNotIn(_SECRET, yaml.safe_dump(jobs[name]))

    def test_submit_jobs_run_only_trusted_scripts(self):
        jobs = _jobs()
        for producer, (submitter, artifact) in _EARLY_PRODUCERS.items():
            with self.subTest(job=submitter):
                job = jobs[submitter]
                self.assertEqual(["changes", producer], job["needs"])
                env = [step.get("env", {}) for step in job["steps"] if "run" in step][0]
                self.assertEqual("${{ needs.changes.outputs.branch }}", env["BRANCH"])
                self.assertEqual("${{ needs.changes.outputs.base_branch }}", env["BASE_BRANCH"])
                self.assertIn("needs.%s.outputs.staged == 'true'" % producer, _condition(job))
                checkouts = [s for s in job["steps"]
                             if str(s.get("uses", "")).startswith("actions/checkout")]
                self.assertEqual(1, len(checkouts))
                self.assertEqual("${{ github.event.repository.default_branch }}",
                                 checkouts[0]["with"]["ref"])
                downloads = [s for s in job["steps"]
                             if str(s.get("uses", "")).startswith("actions/download-artifact")]
                self.assertEqual([artifact], [s["with"]["name"] for s in downloads])
                runs = [s["run"] for s in job["steps"] if "run" in s]
                self.assertEqual(1, len(runs))
                self.assertIn("tools/ci/submit-staged-request.sh", runs[0])

    def test_auto_review_always_selects_a_prompt(self):
        """Every route auto-review can pick has a staging step; there is no route
        that submits nothing. A gate that failed without a recorded cause falls
        through to the general review, whose prompt then says so."""
        steps = _jobs()["auto-review"]["steps"]
        select = next(s for s in steps if s.get("name") == "Select prompt")["run"]
        routes = set(re.findall(r"ROUTE=([a-z-]+)", select))
        self.assertEqual({"docs-verify", "build-failure", "code-policy", "quality-gates",
                          "general-review"}, routes)
        staged = {m for s in steps if s.get("name", "").startswith("Stage submit request")
                  for m in re.findall(r"route == '([a-z-]+)'", str(s.get("if", "")))}
        self.assertEqual(routes, staged)
        review = next(s for s in steps if s.get("name") == "Build prompt (general review)")
        self.assertIn("steps.quality.outputs.unattributed", review["env"]["QUALITY_UNATTRIBUTED"])
        self.assertIn("QUALITY_UNATTRIBUTED", review["run"])

    def test_the_submit_jobs_decide_the_test_lock_themselves(self):
        """auto-resolve-python fixes failing tests, so the harness keeps master's
        tests as they are; no auto-review prompt does, so the lock stays off."""
        jobs = _jobs()
        expected = {"auto-resolve-python-submit": "true", "auto-review-submit": "false"}
        for submitter, value in expected.items():
            with self.subTest(job=submitter):
                env = [s.get("env", {}) for s in jobs[submitter]["steps"] if "run" in s][0]
                self.assertEqual(value, env["PROTECT_TEST_FILES"])

    def test_auto_review_waits_for_the_copilot_review(self):
        """auto-review can finish before Copilot's review of the push has posted.

        The submission asks the controller to hold the job, so the agent
        starts with that review available to it.
        """
        job = _jobs()["auto-review-submit"]
        env = [step.get("env", {}) for step in job["steps"] if "run" in step][0]
        self.assertGreaterEqual(int(env["DELAY_SECONDS"]), 300)

    def test_submit_jobs_survive_a_skipped_or_failed_upstream_job(self):
        """Without a status function the implicit success() covers every job
        upstream, not only `needs`. The producers run exactly when python-tests
        was skipped (auto-review) or failed (auto-resolve-python), so a bare
        `if:` skipped the submission every time (run 35998737943)."""
        jobs = _jobs()
        for producer, (submitter, _) in _EARLY_PRODUCERS.items():
            with self.subTest(job=submitter):
                condition = _condition(jobs[submitter])
                self.assertTrue(condition.startswith("!cancelled()"), condition)
                self.assertIn("needs.%s.result == 'success'" % producer, condition)

    def test_no_analysis_job_declares_an_environment(self):
        for name, job in _jobs().items():
            with self.subTest(job=name):
                self.assertNotIn("environment", job)


def _write_executable(path, text):
    with open(path, "w") as f:
        f.write(text)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)


class RerunFlakyTestsTests(unittest.TestCase):
    """Runs rerun-flaky-tests.sh against a stub `gh`."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="rerun-flaky-test-")
        self.posts = os.path.join(self.tmp, "posts")
        self.jobs_file = os.path.join(self.tmp, "jobs.tsv")
        _write_executable(os.path.join(self.tmp, "gh"), """#!/usr/bin/env bash
case "$*" in
  *"-X POST"*) echo "$*" >> "%s" ;;
  *"/commits/"*) echo "$STUB_TIP_SHA" ;;
  *"/jobs"*) cat "%s" ;;
esac
""" % (self.posts, self.jobs_file))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, jobs, attempt="1", tip="abc"):
        with open(self.jobs_file, "w") as f:
            f.write("".join("%s\t%s\n" % job for job in jobs))
        output = os.path.join(self.tmp, "output")
        open(output, "w").close()
        env = dict(os.environ, PATH=self.tmp + os.pathsep + os.environ["PATH"],
                   GH_TOKEN="t", REPO="o/r", RUN_ID="1", RUN_ATTEMPT=attempt,
                   HEAD_SHA="abc", HEAD_BRANCH="feature/x", HEAD_REPO="o/r",
                   MAX_ATTEMPTS="3", STUB_TIP_SHA=tip, GITHUB_OUTPUT=output)
        result = subprocess.run(["bash", _RERUN_SCRIPT], env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        with open(output) as f:
            outputs = dict(line.strip().split("=", 1) for line in f if "=" in line)
        return outputs, os.path.exists(self.posts)

    def test_a_failed_long_running_test_job_is_retried(self):
        outputs, posted = self._run([("failure", "test (3)"), ("skipped", "auto-resolve"),
                                     ("success", "python-tests")])
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_failed_test_flowtree_is_retried(self):
        outputs, posted = self._run([("failure", "test-flowtree")])
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_run_whose_python_tests_failed_is_never_retried(self):
        """auto-resolve-python already submitted: another attempt could only add a second job."""
        outputs, posted = self._run([("failure", "python-tests"), ("failure", "test (0)"),
                                     ("failure", "test-media (1)")])
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_a_gate_only_failure_is_not_retried(self):
        outputs, posted = self._run([("failure", "checkstyle"), ("failure", "test-timeout-check"),
                                     ("skipped", "test (0)")])
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_the_last_attempt_is_not_retried(self):
        outputs, posted = self._run([("failure", "test (0)")], attempt="3")
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_a_superseded_run_is_neither_retried_nor_submitted(self):
        outputs, posted = self._run([("failure", "test (0)")], tip="newer")
        self.assertEqual("false", outputs["retried"])
        self.assertEqual("true", outputs["superseded"])
        self.assertFalse(posted)


class SubmitStagedRequestTests(unittest.TestCase):
    """Runs submit-staged-request.sh with a stub submit-agent-job.sh beside it."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="submit-staged-test-")
        self.script = os.path.join(self.tmp, "submit-staged-request.sh")
        shutil.copy(_SUBMIT_STAGED, self.script)
        self.captured = os.path.join(self.tmp, "captured")
        _write_executable(os.path.join(self.tmp, "submit-agent-job.sh"), """#!/usr/bin/env bash
{ echo "prompt=$1"; env; } > "%s"
""" % self.captured)
        self.request = os.path.join(self.tmp, "request")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _stage(self, submit_env):
        os.makedirs(self.request)
        with open(os.path.join(self.request, "agent-prompt.txt"), "w") as f:
            f.write("prompt\n")
        with open(os.path.join(self.request, "submit.env"), "w") as f:
            f.write(submit_env)

    def _run(self, **overrides):
        """Runs the script as a trusted caller would: target and credentials set."""
        env = {k: v for k, v in os.environ.items()
               if k not in ("BRANCH", "BASE_BRANCH", "DESCRIPTION", "CF_ACCESS_CLIENT_SECRET")}
        env.update(BRANCH="feature/x", BASE_BRANCH="master", CF_ACCESS_CLIENT_SECRET="secret")
        for key, value in overrides.items():
            if value is None:
                env.pop(key, None)
            else:
                env[key] = value
        return subprocess.run(["bash", self.script, self.request], env=env,
                              capture_output=True, text=True)

    def _captured_env(self):
        with open(self.captured) as f:
            return dict(line.rstrip("\n").split("=", 1) for line in f if "=" in line)

    def test_documented_keys_are_exported_verbatim(self):
        self._stage("BRANCH=feature/a b\nBASE_BRANCH=master\nDESCRIPTION=Resolve 3 test failure(s)\n"
                    "PROTECT_TEST_FILES=true\n")
        result = self._run(BRANCH="feature/a b")
        self.assertEqual(0, result.returncode, result.stderr)
        env = self._captured_env()
        self.assertEqual("feature/a b", env["BRANCH"])
        self.assertEqual("Resolve 3 test failure(s)", env["DESCRIPTION"])
        self.assertEqual("true", env["PROTECT_TEST_FILES"])
        self.assertEqual(os.path.join(self.request, "agent-prompt.txt"), env["prompt"])

    def test_undocumented_keys_are_ignored(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nBASH_ENV=/tmp/evil\n"
                    "CF_ACCESS_CLIENT_SECRET=stolen\n")
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        env = self._captured_env()
        self.assertNotEqual("/tmp/evil", env.get("BASH_ENV"))
        self.assertNotEqual("stolen", env.get("CF_ACCESS_CLIENT_SECRET"))
        self.assertIn("Ignoring unexpected key in the staged submit.env: BASH_ENV", result.stdout)

    def test_the_target_comes_from_the_caller_not_the_request(self):
        """Pull request code wrote the request; it cannot redirect the submission."""
        self._stage("BRANCH=master\nBASE_BRANCH=other\nREPO_URL=git@github.com:evil/repo.git\n"
                    "CREATE_WORKSTREAM=true\n")
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        env = self._captured_env()
        self.assertEqual("feature/x", env["BRANCH"])
        self.assertEqual("master", env["BASE_BRANCH"])
        self.assertNotEqual("git@github.com:evil/repo.git", env.get("REPO_URL"))
        self.assertIn("submitting for feature/x", result.stdout)

    def test_a_request_cannot_switch_test_protection_off(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nPROTECT_TEST_FILES=false\n")
        result = self._run(PROTECT_TEST_FILES="true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", self._captured_env().get("PROTECT_TEST_FILES"))

    def test_a_request_may_turn_test_protection_on(self):
        """auto-resolve-submit cannot tell which route was staged; the request can."""
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nPROTECT_TEST_FILES=true\n")
        self.assertEqual(0, self._run(PROTECT_TEST_FILES=None).returncode)
        self.assertEqual("true", self._captured_env().get("PROTECT_TEST_FILES"))

    def test_the_callers_test_protection_wins_either_way(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nPROTECT_TEST_FILES=true\n")
        self.assertEqual(0, self._run(PROTECT_TEST_FILES="false").returncode)
        self.assertEqual("false", self._captured_env().get("PROTECT_TEST_FILES"))

    def test_a_request_cannot_switch_enforce_changes_off_the_caller_set(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nENFORCE_CHANGES=false\n")
        result = self._run(ENFORCE_CHANGES="true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", self._captured_env()["ENFORCE_CHANGES"])

    def test_a_request_may_only_turn_enforce_changes_on(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nENFORCE_CHANGES=true\n")
        self.assertEqual(0, self._run(ENFORCE_CHANGES=None).returncode)
        self.assertEqual("true", self._captured_env()["ENFORCE_CHANGES"])

    def test_malformed_request_values_are_ignored(self):
        """A value that would break the submission's JSON never reaches it."""
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nENFORCE_CHANGES={\"x\"\n"
                    "STARTED_AFTER=1; rm -rf /\n")
        result = self._run(ENFORCE_CHANGES=None, STARTED_AFTER=None)
        self.assertEqual(0, result.returncode, result.stderr)
        env = self._captured_env()
        self.assertNotIn("ENFORCE_CHANGES", env)
        self.assertNotIn("STARTED_AFTER", env)

    def test_the_callers_started_after_wins(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\nSTARTED_AFTER=99999999999999\n")
        self.assertEqual(0, self._run(STARTED_AFTER="1700000000000").returncode)
        self.assertEqual("1700000000000", self._captured_env()["STARTED_AFTER"])

    def test_the_caller_must_name_the_target(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\n")
        result = self._run(BRANCH=None)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(os.path.exists(self.captured))

    def test_nothing_is_submitted_without_credentials(self):
        self._stage("BRANCH=feature/x\nBASE_BRANCH=master\n")
        result = self._run(CF_ACCESS_CLIENT_SECRET=None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(os.path.exists(self.captured))

    def test_nothing_is_submitted_without_a_staged_request(self):
        result = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(os.path.exists(self.captured))


if __name__ == "__main__":
    unittest.main()
