"""Guard: a pipeline attempt submits at most one kind of remediation job.

"Build and Test" has three remediation jobs — ``auto-resolve-python`` (a
python-tests failure, submitted at once), ``auto-review`` (build failure, code
policy, quality gates, docs-only review or the general review, submitted as
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
_SUBMIT_AGENT_JOB = os.path.join(_REPO_ROOT, "tools", "ci", "submit-agent-job.sh")

_SECRET = "secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET"
_PYTHON_FAILED = "needs.python-tests.result == 'failure'"
_PYTHON_NOT_FAILED = "needs.python-tests.result != 'failure'"
_EVALS_FAILED = "needs.python-evals.result == 'failure'"
_EVALS_NOT_FAILED = "needs.python-evals.result != 'failure'"

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


def _uploaded_artifacts(job):
    return [(step.get("with") or {}).get("name") for step in job.get("steps", [])
            if str(step.get("uses", "")).startswith("actions/upload-artifact")]


class RemediationJobConditionTests(unittest.TestCase):
    """The static wiring in analysis.yaml and auto-resolve-submit.yaml."""

    def test_auto_review_runs_on_the_first_attempt_only(self):
        """Auto-review hands off on the first attempt only — but no longer by
        pinning its ``if:`` to attempt 1. A validate run reaches attempt 2+ only
        when the infra retry gate re-ran a gate that failed on infrastructure;
        pinning to attempt 1 would skip that later attempt, stranding a validate
        run whose attempt 1 suppressed its dispatch as infrastructure (no review
        agent, no test run) when a later attempt passes. So the job now runs on
        any attempt and the single-handoff guard (below) keeps it from starting
        a second review round, leaving the same net effect: one handoff, on the
        first attempt that produces one. This asserts the pin is gone; the guard
        tests below assert the handoff still happens at most once."""
        self.assertNotIn("fromJSON(github.run_attempt) == 1",
                         _condition(_jobs()["auto-review"]))

    def test_auto_review_detects_an_earlier_attempts_handoff(self):
        """Running on every attempt, auto-review must hand off at most once: a
        retry re-runs it whenever one of its inputs failed, and the agent an
        earlier attempt submitted is likely still working. The guard step runs
        only past attempt 1 and reads the artifacts API for the earlier
        attempt's auto-review-request — the same artifact the retry gate keys
        its auto-resolve-request check on."""
        step = next(s for s in _jobs()["auto-review"]["steps"]
                    if s.get("id") == "earlier")
        self.assertIn("fromJSON(github.run_attempt) != 1", _condition(step))
        self.assertIn("read", _jobs()["auto-review"]["permissions"]["actions"])
        run = step["run"]
        self.assertIn("artifacts?name=auto-review-request", run)
        self.assertIn("earlier=true", run)
        self.assertIn("earlier=false", run)

    def test_every_stage_step_is_guarded_against_an_earlier_handoff(self):
        """No route stages a request once an earlier attempt of the run did, so
        a retried validate run never starts a second review round."""
        staged = [s for s in _jobs()["auto-review"]["steps"]
                  if str(s.get("name", "")).startswith("Stage submit request")]
        self.assertEqual(5, len(staged))
        for step in staged:
            with self.subTest(step=step["name"]):
                self.assertIn("steps.earlier.outputs.earlier != 'true'",
                              _condition(step))

    def test_auto_resolve_never_shares_an_attempt_with_auto_review(self):
        """They never share a run: auto-review is for a validate run (a
        pull_request event) and auto-resolve for a test run, whose lanes never
        run for a pull_request event."""
        jobs = _jobs()
        self.assertIn("github.event_name == 'pull_request'", _condition(jobs["auto-review"]))
        self.assertIn("needs.changes.outputs.run_tests == 'true'", _condition(jobs["auto-resolve"]))
        kind = next(step for step in jobs["changes"]["steps"] if step.get("id") == "kind")
        script = " ".join(kind["run"].split())
        self.assertIn('if [ "$EVENT_NAME" = "pull_request" ]; then echo "run_tests=false"', script)

    def test_the_attempt_threshold_is_the_retry_limit(self):
        """auto-resolve must hand off unconfirmed failures on exactly the
        attempt the retries stop at."""
        threshold = _jobs()["auto-resolve"]["env"]["MAX_ATTEMPTS"]
        retry = _load(_SUBMIT_WORKFLOW)["jobs"]["retry-flaky-tests"]
        env = next(step["env"] for step in retry["steps"] if step.get("id") == "decide")
        self.assertEqual(str(threshold), str(env["MAX_ATTEMPTS"]))

    def test_only_a_confirmed_failure_is_staged_before_the_final_attempt(self):
        """The test-failure request waits for a confirmation or the final
        attempt; the crash and incomplete-run requests for the final attempt."""
        steps = {step.get("name"): _condition(step) for step in _jobs()["auto-resolve"]["steps"]}
        self.assertIn("steps.handoff.outputs.tests == 'true'", steps["Stage submit request (test failures)"])
        for name in ("Stage submit request (test job crash)", "Stage submit request (incomplete tests)"):
            with self.subTest(step=name):
                self.assertIn("steps.handoff.outputs.final == 'true'", steps[name])

    def test_the_retry_gate_learns_when_the_attempt_started(self):
        retry = _load(_SUBMIT_WORKFLOW)["jobs"]["retry-flaky-tests"]
        env = next(step["env"] for step in retry["steps"] if step.get("id") == "decide")
        self.assertEqual("${{ github.event.workflow_run.run_started_at }}", env["ATTEMPT_STARTED_AT"])

    def test_a_python_failure_belongs_to_auto_resolve_python_alone(self):
        jobs = _jobs()
        self.assertIn(_PYTHON_FAILED, _condition(jobs["auto-resolve-python"]))
        for name in ("auto-review", "auto-resolve"):
            with self.subTest(job=name):
                self.assertIn("python-tests", jobs[name]["needs"])
                self.assertIn(_PYTHON_NOT_FAILED, _condition(jobs[name]))

    def test_an_eval_failure_belongs_to_auto_resolve_python_alone(self):
        jobs = _jobs()
        self.assertIn("python-evals", jobs["auto-resolve-python"]["needs"])
        self.assertIn(_EVALS_FAILED, _condition(jobs["auto-resolve-python"]))
        for name in ("auto-review", "auto-resolve"):
            with self.subTest(job=name):
                self.assertIn("python-evals", jobs[name]["needs"])
                self.assertIn(_EVALS_NOT_FAILED, _condition(jobs[name]))

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
        """auto-review owns the build, policy, quality-gate and review prompts."""
        runs = " ".join(step.get("run", "") for step in _jobs()["auto-resolve"]["steps"])
        for builder in ("build-review-prompt.sh", "build-verify-prompt.sh",
                        "build-docs-review-prompt.sh",
                        "build-quality-gate-prompt.sh", "build-policy-violation-prompt.sh"):
            with self.subTest(builder=builder):
                self.assertNotIn(builder, runs)

    def test_auto_review_never_starts_implementing_a_plan(self):
        """A plan branch's first commit is docs-only, so whatever auto-review
        sends a docs-only branch reaches every plan the moment it is proposed.
        That route once sent the verify-completion prompt, which implements
        the plan; it must send the docs review, and the implementation prompt
        must not be reachable from auto-review at all. Implementation is
        started by hand, from the Verify Completion workflow."""
        steps = _jobs()["auto-review"]["steps"]
        runs = " ".join(step.get("run", "") for step in steps)
        self.assertNotIn("build-verify-prompt.sh", runs)
        docs = next(s for s in steps if s.get("name") == "Build prompt (docs-only review)")
        self.assertIn("route == 'docs-review'", docs["if"])
        self.assertIn("build-docs-review-prompt.sh", docs["run"])


class AutoReviewRoutingTests(unittest.TestCase):
    """Runs the `Select prompt` shell to pin which route each state picks.

    The docs-review prompt only edits under `docs/`, so it must reach a branch
    only when the branch is genuinely docs-only. A non-code change that is not
    docs-only — a CI file, a root README, a JSON config — has no code but
    cannot be reviewed under that constraint, and belongs on the general
    review, not docs-review.
    """

    def _select(self, **env):
        script = next(s for s in _jobs()["auto-review"]["steps"]
                      if s.get("name") == "Select prompt")["run"]
        with tempfile.NamedTemporaryFile("r", suffix=".out", delete=False) as out:
            output = out.name
        try:
            full = dict(os.environ, GITHUB_OUTPUT=output,
                        CODE_CHANGED="", BUILD_RESULT="", CODE_POLICY_PASSED="",
                        QUALITY_HAS_FAILURES="", DOCS_ONLY="")
            full.update(env)
            result = subprocess.run(["bash", "-c", script], env=full,
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            with open(output) as f:
                routes = dict(line.strip().split("=", 1) for line in f if "=" in line)
            return routes["route"]
        finally:
            os.unlink(output)

    def test_a_docs_only_branch_is_reviewed_not_implemented(self):
        self.assertEqual("docs-review", self._select(CODE_CHANGED="false", DOCS_ONLY="true"))

    def test_a_non_code_non_docs_branch_gets_the_general_review(self):
        """A CI-only or config-only branch has no code and is not docs-only; the
        docs-review prompt forbids edits outside docs/, so it cannot serve it."""
        self.assertEqual("general-review",
                         self._select(CODE_CHANGED="false", DOCS_ONLY="false"))

    def test_a_docs_only_branch_never_routes_to_build_failure(self):
        """Its build is skipped, so BUILD_RESULT is not 'success'; docs-review
        must still win over the build-failure arm."""
        self.assertEqual("docs-review",
                         self._select(CODE_CHANGED="false", DOCS_ONLY="true",
                                      BUILD_RESULT="skipped"))

    def test_a_build_failure_routes_to_build_failure(self):
        self.assertEqual("build-failure",
                         self._select(CODE_CHANGED="true", BUILD_RESULT="failure"))

    def test_a_code_policy_failure_routes_to_code_policy(self):
        self.assertEqual("code-policy",
                         self._select(CODE_CHANGED="true", BUILD_RESULT="success",
                                      CODE_POLICY_PASSED="false"))

    def test_a_quality_gate_failure_routes_to_quality_gates(self):
        self.assertEqual("quality-gates",
                         self._select(CODE_CHANGED="true", BUILD_RESULT="success",
                                      CODE_POLICY_PASSED="true", QUALITY_HAS_FAILURES="true"))

    def test_a_clean_code_branch_gets_the_general_review(self):
        self.assertEqual("general-review",
                         self._select(CODE_CHANGED="true", BUILD_RESULT="success",
                                      CODE_POLICY_PASSED="true", QUALITY_HAS_FAILURES="false"))


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
                env = _named_step(job, "Submit agent job").get("env", {})
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
                # Every run step invokes a trusted tools/ci/ script (the submit
                # itself and the protected-file grant), never the pull request's
                # own code.
                runs = [s["run"] for s in job["steps"] if "run" in s]
                self.assertTrue(any("tools/ci/submit-staged-request.sh" in r for r in runs))
                for run in runs:
                    self.assertRegex(run, r"tools/ci/[\w.-]+\.sh")

    def test_auto_review_always_selects_a_prompt(self):
        """Every route auto-review can pick has a staging step; there is no route
        that submits nothing. A gate that failed without a recorded cause falls
        through to the general review, whose prompt then says so."""
        steps = _jobs()["auto-review"]["steps"]
        select = next(s for s in steps if s.get("name") == "Select prompt")["run"]
        routes = set(re.findall(r"ROUTE=([a-z-]+)", select))
        self.assertEqual({"docs-review", "build-failure", "code-policy", "quality-gates",
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
                env = _named_step(jobs[submitter], "Submit agent job").get("env", {})
                self.assertEqual(value, env["PROTECT_TEST_FILES"])

    def test_auto_review_waits_for_the_copilot_review(self):
        """auto-review can finish before Copilot's review of the push has posted.

        The submission asks the controller to hold the job, so the agent
        starts with that review available to it.
        """
        job = _jobs()["auto-review-submit"]
        env = _named_step(job, "Submit agent job").get("env", {})
        self.assertGreaterEqual(int(env["DELAY_SECONDS"]), 300)

    def test_auto_review_submit_drops_the_delay_when_copilot_is_dark(self):
        """When Copilot's latest review on the
        PR is an out-of-quota notice there is no review to wait for, so the
        submit step drops its default hold to 0. The 300s default is unchanged;
        the run step overrides it only when COPILOT_AVAILABLE is false."""
        job = _jobs()["auto-review-submit"]
        step = _named_step(job, "Submit agent job")
        env = step.get("env", {})
        self.assertEqual("300", env["DELAY_SECONDS"])
        self.assertIn("copilot_available", env["COPILOT_AVAILABLE"])
        run = step["run"]
        self.assertIn("COPILOT_AVAILABLE", run)
        self.assertIn("DELAY_SECONDS=0", run)

    def test_auto_review_exposes_copilot_availability(self):
        """auto-review detects a dark Copilot and exposes it as an output the
        submit job reads."""
        job = _jobs()["auto-review"]
        self.assertIn("copilot_available", job.get("outputs", {}))
        names = [s.get("name") for s in job["steps"]]
        self.assertIn("Check Copilot review availability", names)

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

    def test_auto_resolve_leaves_the_stale_guard_to_the_submit_workflow(self):
        """The pipeline's start time predates auto-review's own submission, so a
        STARTED_AFTER staged from it would have the controller skip the request."""
        for step in _jobs()["auto-resolve"]["steps"]:
            with self.subTest(step=step.get("name")):
                self.assertNotIn("STARTED_AFTER", step.get("env") or {})


def _named_step(job, name):
    return next(s for s in job["steps"] if s.get("name") == name)


def _submit_step():
    job = _load(_SUBMIT_WORKFLOW)["jobs"]["submit"]
    return _named_step(job, "Submit agent job")


class AutoResolveSubmitStepTests(unittest.TestCase):
    """Runs the submit step of auto-resolve-submit.yaml with a stub submit script."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="auto-resolve-submit-test-")
        self.captured = os.path.join(self.tmp, "captured")
        os.makedirs(os.path.join(self.tmp, "tools", "ci"))
        _write_executable(os.path.join(self.tmp, "tools", "ci", "submit-staged-request.sh"),
                          """#!/usr/bin/env bash
{ echo "request=$1"; env; } > "%s"
""" % self.captured)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, attempt_started_at):
        env = {k: v for k, v in os.environ.items() if k != "STARTED_AFTER"}
        env["ATTEMPT_STARTED_AT"] = attempt_started_at
        return subprocess.run(["bash", "-e", "-c", _submit_step()["run"]], cwd=self.tmp,
                              env=env, capture_output=True, text=True)

    def _captured_env(self):
        with open(self.captured) as f:
            return dict(line.rstrip("\n").split("=", 1) for line in f if "=" in line)

    def test_the_guard_is_scoped_to_the_attempt_that_staged_the_request(self):
        """run_started_at is the start of the latest attempt, not of the run."""
        self.assertEqual("${{ github.event.workflow_run.run_started_at }}",
                         _submit_step()["env"]["ATTEMPT_STARTED_AT"])

    def test_the_attempt_start_is_sent_as_epoch_millis(self):
        result = self._run("2026-09-28T10:41:56Z")
        self.assertEqual(0, result.returncode, result.stderr)
        env = self._captured_env()
        self.assertEqual("1790592116000", env["STARTED_AFTER"])
        self.assertEqual("auto-resolve-request", env["request"])

    def test_a_missing_attempt_start_submits_nothing(self):
        """`date -d ""` is midnight today; it must never stand in for the attempt start."""
        result = self._run("")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(os.path.exists(self.captured))


def _classify_step_script(runner_temp, failure_count):
    """The auto-resolve "Classify test failures" run script, with its
    expressions substituted the way the runner would."""
    step = [s for s in _jobs()["auto-resolve"]["steps"]
            if s.get("name") == "Classify test failures"][0]
    return (step["run"]
            .replace("${{ runner.temp }}", runner_temp)
            .replace("${{ steps.failures.outputs.failure_count }}", str(failure_count)))


class ClassifyFailuresStepTests(unittest.TestCase):
    """A classifier that crashes must not silently drop a real
    failure; the step falls back to treating every failure as a regression."""

    def _run(self, classifier):
        work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, work)
        temp = os.path.join(work, "temp")
        os.makedirs(temp)
        with open(os.path.join(temp, "test-failures.txt"), "w") as f:
            f.write("- FooTest.bar\n- FooTest.baz\n")
        os.makedirs(os.path.join(work, "tools", "ci"))
        if classifier is not None:
            with open(os.path.join(work, "tools", "ci", "classify-failures.py"), "w") as f:
                f.write(classifier)
        output = os.path.join(work, "github_output")
        env = dict(os.environ, GITHUB_OUTPUT=output)
        proc = subprocess.run(
            ["bash", "-e", "-c", _classify_step_script(temp, 2)],
            cwd=work, env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(0, proc.returncode, proc.stderr)
        with open(output) as f:
            outputs = dict(line.strip().split("=", 1) for line in f if "=" in line)
        with open(os.path.join(temp, "regressions.txt")) as f:
            regressions = f.read()
        return outputs, regressions, proc.stdout

    def test_a_crashing_classifier_falls_back_to_all_regressions(self):
        outputs, regressions, stdout = self._run("import sys\nsys.exit(3)\n")
        self.assertEqual("2", outputs["regression_count"])
        self.assertEqual("0", outputs["preexisting_count"])
        self.assertEqual("0", outputs["environmental_count"])
        self.assertIn("FooTest.bar", regressions)
        self.assertIn("treating every failure as a regression", stdout)

    def test_an_absent_classifier_falls_back_to_all_regressions(self):
        outputs, regressions, _ = self._run(None)
        self.assertEqual("2", outputs["regression_count"])
        self.assertIn("FooTest.baz", regressions)

    def test_a_working_classifier_is_used(self):
        classifier = (
            "import os, sys\n"
            "out = sys.argv[2]\n"
            "open(os.path.join(out, 'regressions.txt'), 'w').write('- FooTest.bar\\n')\n"
            "print('regression_count=1')\n"
            "print('preexisting_count=1')\n"
            "print('environmental_count=0')\n")
        outputs, regressions, _ = self._run(classifier)
        self.assertEqual("1", outputs["regression_count"])
        self.assertEqual("1", outputs["preexisting_count"])
        self.assertNotIn("FooTest.baz", regressions)

    def test_counts_that_do_not_sum_to_the_failure_count_fall_back(self):
        # The classifier partitions every failure into exactly one bucket, so a
        # classification whose three counts do not sum to the raw failure count
        # (here it under-reports regressions as 0 while the run had 2 failures)
        # is rejected and every failure is treated as a regression.
        classifier = (
            "print('regression_count=0')\n"
            "print('preexisting_count=1')\n"
            "print('environmental_count=0')\n")
        outputs, regressions, stdout = self._run(classifier)
        self.assertEqual("2", outputs["regression_count"])
        self.assertEqual("0", outputs["preexisting_count"])
        self.assertEqual("0", outputs["environmental_count"])
        self.assertIn("FooTest.bar", regressions)
        self.assertIn("do not match", stdout)

    def test_missing_counts_fall_back_to_all_regressions(self):
        # A classifier that prints only regression_count=0 (the other counts
        # absent) must not be accepted: without all three it cannot be checked
        # against the failure count, so the conservative path is taken.
        outputs, regressions, stdout = self._run("print('regression_count=0')\n")
        self.assertEqual("2", outputs["regression_count"])
        self.assertIn("FooTest.baz", regressions)
        self.assertIn("did not report all three counts", stdout)

    def test_a_confirmed_count_is_passed_on(self):
        classifier = (
            "import os, sys\n"
            "open(os.path.join(sys.argv[2], 'regressions.txt'), 'w').write('- FooTest.bar\\n- FooTest.baz\\n')\n"
            "print('regression_count=2')\n"
            "print('preexisting_count=0')\n"
            "print('environmental_count=0')\n"
            "print('confirmed_count=1')\n")
        outputs, _, _ = self._run(classifier)
        self.assertEqual("1", outputs["confirmed_count"])

    def test_a_confirmed_count_above_the_regressions_confirms_nothing(self):
        classifier = (
            "import os, sys\n"
            "open(os.path.join(sys.argv[2], 'regressions.txt'), 'w').write('- FooTest.bar\\n')\n"
            "print('regression_count=1')\n"
            "print('preexisting_count=1')\n"
            "print('environmental_count=0')\n"
            "print('confirmed_count=5')\n")
        outputs, _, _ = self._run(classifier)
        self.assertEqual("0", outputs["confirmed_count"])

    def test_a_fallback_confirms_nothing(self):
        outputs, _, _ = self._run("import sys\nsys.exit(3)\n")
        self.assertEqual("0", outputs["confirmed_count"])


class HandoffStepTests(unittest.TestCase):
    """The auto-resolve "Decide whether this attempt hands off" step."""

    def _run(self, attempt, confirmed):
        step = next(s for s in _jobs()["auto-resolve"]["steps"] if s.get("id") == "handoff")
        work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, work)
        output = os.path.join(work, "github_output")
        env = dict(os.environ, GITHUB_OUTPUT=output, ATTEMPT=attempt, CONFIRMED=confirmed,
                   MAX_ATTEMPTS=str(_jobs()["auto-resolve"]["env"]["MAX_ATTEMPTS"]))
        proc = subprocess.run(["bash", "-e", "-c", step["run"]], env=env,
                              capture_output=True, text=True, timeout=60)
        self.assertEqual(0, proc.returncode, proc.stderr)
        with open(output) as f:
            return dict(line.strip().split("=", 1) for line in f if "=" in line)

    def test_an_unconfirmed_failure_on_the_first_attempt_waits(self):
        self.assertEqual({"final": "false", "tests": "false"}, self._run("1", "0"))

    def test_a_confirmed_failure_on_the_first_attempt_is_handed_off(self):
        self.assertEqual({"final": "false", "tests": "true"}, self._run("1", "2"))

    def test_the_final_attempt_hands_everything_off(self):
        self.assertEqual({"final": "true", "tests": "true"}, self._run("3", "0"))

    def test_a_run_without_failures_has_no_confirmed_count(self):
        self.assertEqual({"final": "false", "tests": "false"}, self._run("1", ""))


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
        self.logs_dir = os.path.join(self.tmp, "logs")
        os.makedirs(self.logs_dir)
        _write_executable(os.path.join(self.tmp, "gh"), """#!/usr/bin/env bash
case "$*" in
  *"-X POST"*) echo "$*" >> "%s" ;;
  *"/commits/"*) echo "$STUB_TIP_SHA" ;;
  *"/artifacts"*) [ -n "${STUB_STAGED_AT:-}" ] && echo "$STUB_STAGED_AT" ;;
  *"--help"*) [ -n "${STUB_ESCAPE_GUARD:-}" ] && echo "      --allow-escape-sequences   Allow printing terminal escape sequences" ;;
  *"/logs"*) path="${!#}"; id="${path#*/jobs/}"; file="%s/${id%%%%/logs*}"
    [ -f "$file" ] || exit 1
    if [ -n "${STUB_ESCAPE_GUARD:-}" ] && [[ "$*" != *--allow-escape-sequences* ]] && grep -q $'\\033' "$file"; then
      echo "the response contains terminal escape sequences; pass --allow-escape-sequences to output it anyway" >&2
      exit 1
    fi
    cat "$file" ;;
  *"/jobs"*) cat "%s" ;;
esac
exit 0
""" % (self.posts, self.logs_dir, self.jobs_file))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, jobs, attempt="1", tip="abc", staged_at="", attempt_started_at="",
             logs=None, escape_guard=False):
        """Runs the script; ``logs`` maps a job's position in ``jobs`` to its log.
        ``escape_guard`` makes the stub behave like a current ``gh``, which
        refuses to print a log containing escape sequences unless passed
        ``--allow-escape-sequences``."""
        with open(self.jobs_file, "w") as f:
            f.write("".join("%s\t%s\t%d\n" % (job + (100 + i,))
                            for i, job in enumerate(jobs)))
        for i, text in (logs or {}).items():
            with open(os.path.join(self.logs_dir, str(100 + i)), "w") as f:
                f.write(text)
        output = os.path.join(self.tmp, "output")
        open(output, "w").close()
        env = dict(os.environ, PATH=self.tmp + os.pathsep + os.environ["PATH"],
                   GH_TOKEN="t", REPO="o/r", RUN_ID="1", RUN_ATTEMPT=attempt,
                   HEAD_SHA="abc", HEAD_BRANCH="feature/x", HEAD_REPO="o/r",
                   MAX_ATTEMPTS="3", STUB_TIP_SHA=tip, GITHUB_OUTPUT=output,
                   STUB_STAGED_AT=staged_at, ATTEMPT_STARTED_AT=attempt_started_at,
                   # Keep the log-fetch retry (infra-failure.sh) fast and
                   # deterministic for the stub: two tries, no delay.
                   CI_INFRA_LOG_ATTEMPTS="2", CI_INFRA_LOG_RETRY_DELAY="0",
                   STUB_ESCAPE_GUARD="1" if escape_guard else "")
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

    def test_a_run_whose_python_evals_failed_is_never_retried(self):
        """auto-resolve-python submits for an eval failure too."""
        outputs, posted = self._run([("failure", "python-evals"), ("success", "python-tests"),
                                     ("failure", "test (0)")])
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_a_run_whose_python_tests_timed_out_is_never_retried(self):
        """A timed-out job is a failed job (``needs.*.result`` is ``failure``), so
        auto-resolve-python submitted for it as well."""
        outputs, posted = self._run([("timed_out", "python-tests"), ("failure", "test (0)")])
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_a_gate_only_failure_is_not_retried(self):
        """Readable gate logs that show a code failure, not infrastructure, are
        deterministic and are not retried."""
        outputs, posted = self._run(
            [("failure", "checkstyle"), ("failure", "test-timeout-check"),
             ("skipped", "test (0)")],
            logs={0: "[ERROR] Checkstyle violation: do not use the var keyword\n",
                  1: "FooTest.bar has @Test without a timeout\n"})
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_a_check_that_failed_to_download_a_plugin_is_retried(self):
        """An infrastructure failure in a validator skips every test lane: retry it."""
        outputs, posted = self._run(
            [("failure", "test-timeout-check"), ("skipped", "test (0)")],
            logs={0: "[ERROR] Plugin org.jacoco:jacoco-maven-plugin could not be resolved: "
                     "Could not find artifact org.jacoco:jacoco-maven-plugin:jar in central\n"})
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_build_that_lost_its_connection_is_retried(self):
        outputs, posted = self._run(
            [("failure", "build")],
            logs={0: "Caused by: java.net.SocketException: Connection reset\n"})
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_timed_out_job_with_an_infrastructure_failure_is_retried(self):
        """``timed_out`` is a failed conclusion: its log is read like a failure's."""
        outputs, posted = self._run(
            [("timed_out", "build")],
            logs={0: "##[error]The runner has received a shutdown signal.\n"})
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_timed_out_test_job_is_retried(self):
        outputs, posted = self._run([("timed_out", "test-media")])
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_an_early_signature_in_a_large_log_is_found(self):
        """grep stops at the first match; that must not turn the match into a miss."""
        outputs, posted = self._run(
            [("failure", "test-integrity-check")],
            logs={0: "Connection reset by peer\n"
                     + ("filler line of an ordinary build log\n" * 200000)})
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_check_failing_on_the_code_is_not_retried(self):
        outputs, posted = self._run(
            [("failure", "test-timeout-check")],
            logs={0: "FooTest.bar has @Test without a timeout\n"})
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_an_unreadable_log_leaves_the_job_deterministic(self):
        """An unreadable log on a job that is neither a validator nor the build
        (here register-workstream) stays deterministic: the retry-on-unreadable
        safety net is scoped to the jobs whose infrastructure failure skips the
        test lanes (the *-check validators and build)."""
        outputs, posted = self._run([("failure", "register-workstream")])
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_an_unreadable_check_log_is_retried(self):
        """A *-check validator whose log cannot be read must not be silently
        called deterministic: that is the case that left pipelines stuck. Retry
        while attempts remain."""
        outputs, posted = self._run([("failure", "test-timeout-check"), ("skipped", "test (0)")])
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_an_unreadable_build_log_is_retried(self):
        """The build job's unreadable log is retried for the same reason."""
        outputs, posted = self._run([("failure", "build")])
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_rate_limited_coverage_download_in_analysis_is_retried(self):
        """The analysis job's Merge Coverage Data step failing on a Maven
        Central 429, with GitHub's colour-wrapped step echo in the log and a
        ``gh`` that guards escape sequences, is an infrastructure failure. The
        Test Checks gate it then fails does not stop the retry."""
        esc = "\x1b"
        outputs, posted = self._run(
            [("failure", "analysis"), ("failure", "Test Checks")],
            logs={0: esc + "[36;1m  # retries transient ones with backoff" + esc + "[0m\n"
                     "jacoco-cli 0.8.11 not in the Maven cache; downloading from Maven Central\n"
                     "curl: (22) The requested URL returned error: 429\n"
                     "##[error]Process completed with exit code 22.\n",
                  1: esc + "[36;1mcheck_job analysis" + esc + "[0m\n"
                     "##[error]Process completed with exit code 1.\n"},
            escape_guard=True)
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_a_coloured_log_is_read_through_the_escape_guard(self):
        """A coloured log must be readable: without --allow-escape-sequences a
        current ``gh`` refuses it and every failure looks unreadable."""
        esc = "\x1b"
        outputs, posted = self._run(
            [("failure", "register-workstream")],
            logs={0: esc + "[36;1mcurl ... /api/workstreams" + esc + "[0m\n"
                     "Connection " + esc + "[1mrefused" + esc + "[0m\n"},
            escape_guard=True)
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_an_analysis_failing_on_the_code_is_not_retried(self):
        outputs, posted = self._run(
            [("failure", "analysis"), ("failure", "Test Checks")],
            logs={0: "End-of-central-directory signature not found.\n"
                     "##[error]Process completed with exit code 9.\n",
                  1: "##[error]Process completed with exit code 1.\n"})
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_an_unreadable_analysis_log_is_retried(self):
        """An analysis failure reaches no agent, so an unreadable log is retried
        while attempts remain, as for build and the validators."""
        outputs, posted = self._run([("failure", "analysis"), ("failure", "Test Checks")])
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_an_unreadable_check_log_on_the_last_attempt_is_not_retried(self):
        """The retry-on-unreadable net is bounded by MAX_ATTEMPTS, so the final
        attempt proceeds exactly as a deterministic failure would."""
        outputs, posted = self._run([("failure", "test-timeout-check")], attempt="3")
        self.assertEqual("false", outputs["retried"])
        self.assertFalse(posted)

    def test_an_infrastructure_failure_on_the_last_attempt_is_not_retried(self):
        outputs, posted = self._run(
            [("failure", "test-timeout-check")], attempt="3",
            logs={0: "Could not transfer artifact x:y:pom from/to central: 503 Service Unavailable\n"})
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

    def test_an_attempt_that_staged_a_request_is_submitted_not_retried(self):
        """auto-resolve staged at once for a confirmed regression: a retry cannot clear it."""
        outputs, posted = self._run([("failure", "test-mac (1)"), ("failure", "test-cl (1)")],
                                    staged_at="2026-11-15T03:10:00Z",
                                    attempt_started_at="2026-11-15T01:00:00Z")
        self.assertEqual("false", outputs["retried"])
        self.assertEqual("false", outputs["superseded"])
        self.assertFalse(posted)

    def test_a_request_from_an_earlier_attempt_does_not_stop_a_retry(self):
        outputs, posted = self._run([("failure", "test (0)")], attempt="2",
                                    staged_at="2026-11-15T00:30:00Z",
                                    attempt_started_at="2026-11-15T01:00:00Z")
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)

    def test_without_the_attempt_start_a_staged_request_is_not_looked_for(self):
        outputs, posted = self._run([("failure", "test (0)")],
                                    staged_at="2026-11-15T03:10:00Z")
        self.assertEqual("true", outputs["retried"])
        self.assertTrue(posted)


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


class SubmitAgentJobSkipTests(unittest.TestCase):
    """Runs submit-agent-job.sh against stub curl and jq to pin the skip path.

    A controller skip is a 200 response carrying ``skipped: true``. The step
    must stay green (a skip is a successful response) while making the dropped
    request visible: a workflow ``::warning::`` and, when running under Actions,
    a line in the job step summary. The stubs let the test drive the response
    without a real controller or a system ``jq``.
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="submit-agent-job-test-")
        self.script = os.path.join(self.tmp, "submit-agent-job.sh")
        shutil.copy(_SUBMIT_AGENT_JOB, self.script)
        self.prompt = os.path.join(self.tmp, "prompt.txt")
        with open(self.prompt, "w") as f:
            f.write("do the thing\n")
        self.summary = os.path.join(self.tmp, "summary.md")
        # curl echoes the staged body followed by the HTTP status on its own
        # line, matching the `-w "\n%{http_code}"` the script parses.
        _write_executable(os.path.join(self.tmp, "curl"), """#!/usr/bin/env bash
printf '%s\\n200\\n' "$STUB_BODY"
""")
        # jq answers only the field reads the skip path makes; every payload
        # build or field it does not recognise yields an empty object, which is
        # all the script needs before it reaches the skip branch.
        _write_executable(os.path.join(self.tmp, "jq"), """#!/usr/bin/env bash
for arg in "$@"; do
  case "$arg" in
    ".skipped // empty") echo "$STUB_SKIPPED"; exit 0 ;;
    '.reason // "unknown"') echo "$STUB_REASON"; exit 0 ;;
    "if .automated"*) echo "false"; exit 0 ;;
    ".jobId // empty") echo "job-123"; exit 0 ;;
    ".workstreamCreated // empty") echo ""; exit 0 ;;
  esac
done
echo "{}"
""")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, skipped, reason="", step_summary=True):
        env = {k: v for k, v in os.environ.items()
               if k not in ("STARTED_AFTER", "REPO_URL", "GITHUB_REPOSITORY",
                            "DESCRIPTION", "GITHUB_STEP_SUMMARY")}
        env.update(PATH=self.tmp + os.pathsep + os.environ["PATH"],
                   BRANCH="feature/x", BASE_BRANCH="master",
                   STUB_BODY='{"skipped":true}', STUB_SKIPPED=skipped,
                   STUB_REASON=reason)
        if step_summary:
            env["GITHUB_STEP_SUMMARY"] = self.summary
        return subprocess.run(["bash", self.script, self.prompt], env=env,
                              capture_output=True, text=True)

    def test_a_controller_skip_is_a_warning_and_leaves_the_step_green(self):
        result = self._run("true", reason="a newer job exists")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("::warning::Agent job skipped by the controller: a newer job exists",
                      result.stdout)

    def test_a_controller_skip_records_the_branch_and_reason_in_the_step_summary(self):
        result = self._run("true", reason="a newer job exists")
        self.assertEqual(0, result.returncode, result.stderr)
        with open(self.summary) as f:
            summary = f.read()
        self.assertIn("feature/x", summary)
        self.assertIn("a newer job exists", summary)

    def test_a_skip_without_a_step_summary_still_succeeds(self):
        """Outside Actions GITHUB_STEP_SUMMARY is unset; the skip must not crash."""
        result = self._run("true", reason="a newer job exists", step_summary=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("::warning::Agent job skipped by the controller", result.stdout)

    def test_a_normal_submission_is_not_reported_as_a_skip(self):
        """The warning must fire only on a skip, never on an accepted job."""
        result = self._run("", step_summary=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("skipped by the controller", result.stdout)
        self.assertIn("job_id=job-123", result.stdout)


class InfrastructureFailureSharingTests(unittest.TestCase):
    """The infrastructure-vs-deterministic classification lives in one place,
    tools/ci/infra-failure.sh, used by both the retry gate and auto-review, and
    auto-review suppresses a dispatch when every failing gate is infrastructure.
    """

    _SHARED = os.path.join(_REPO_ROOT, "tools", "ci", "infra-failure.sh")

    def test_the_shared_script_exists(self):
        self.assertTrue(os.path.isfile(self._SHARED))

    def test_the_retry_gate_sources_the_shared_script_and_keeps_no_copy(self):
        with open(_RERUN_SCRIPT) as f:
            body = f.read()
        self.assertIn("infra-failure.sh", body)
        # No second copy of the signatures or the matcher to drift from it.
        self.assertNotIn("INFRA_SIGNATURES=", body)
        self.assertNotIn("is_infra_failure()", body)

    def test_auto_review_may_read_the_runs_jobs_and_logs(self):
        self.assertEqual("read", _jobs()["auto-review"]["permissions"]["actions"])

    def test_auto_review_classifies_gate_failures_with_the_shared_script(self):
        step = next(s for s in _jobs()["auto-review"]["steps"] if s.get("id") == "infra")
        run = step["run"]
        self.assertIn("infra-failure.sh", run)
        self.assertIn("ci_infra_job_matches", run)
        self.assertIn("infra_only", run)
        # Only the failure routes are classified; docs/general review always submit.
        condition = _condition(step)
        for route in ("build-failure", "code-policy", "quality-gates"):
            with self.subTest(route=route):
                self.assertIn("route == '%s'" % route, condition)

    def test_every_finding_producing_gate_is_classified(self):
        """A gate whose failure check-quality-gates.sh can turn into a finding
        must appear in GATE_JOBS, or an infrastructure failure in another gate
        could set infra_only=true and suppress the agent that should handle the
        genuine finding. The integrity checks (test-integrity-check,
        agent-commit-validation) drive the quality-gates route just as the Maven
        gates do, so they must be classified too."""
        step = next(s for s in _jobs()["auto-review"]["steps"] if s.get("id") == "infra")
        gate_jobs = step["env"]["GATE_JOBS"].split()
        for gate in ("build", "code-policy-check", "test-timeout-check",
                     "duplicate-code-check", "checkstyle",
                     "test-integrity-check", "agent-commit-validation"):
            with self.subTest(gate=gate):
                self.assertIn(gate, gate_jobs)

    def test_the_failure_routes_suppress_dispatch_on_an_infra_only_failure(self):
        steps = _jobs()["auto-review"]["steps"]
        for route in ("build failure", "code policy violations", "quality gates"):
            with self.subTest(route=route):
                step = _named_step({"steps": steps}, "Stage submit request (%s)" % route)
                self.assertIn("steps.infra.outputs.infra_only != 'true'", _condition(step))

    def test_docs_and_general_review_are_never_suppressed_as_infrastructure(self):
        """A review that reports no gate finding must always run."""
        steps = _jobs()["auto-review"]["steps"]
        for route in ("docs-only review", "general review"):
            with self.subTest(route=route):
                step = _named_step({"steps": steps}, "Stage submit request (%s)" % route)
                self.assertNotIn("infra_only", _condition(step))

    def test_the_classifier_shell_suite_runs_in_ci(self):
        """INFRA_SIGNATURES lives in a shell script no unittest discovery
        reaches, and the Python wiring tests here stub the log fetch, so the
        standalone shell suite is the only CI coverage of the regex itself. It
        must be invoked by a python-tests step or it never runs in CI."""
        step = _named_step(_jobs()["python-tests"],
                           "Run infrastructure classifier tests")
        self.assertIn("tools/tests/test-infra-classification.sh", step["run"])

    def test_python_tests_runs_when_only_the_classifier_scripts_change(self):
        """A change to infra-failure.sh or the retry gate touches no .py file,
        so python-tests must also gate on infra_classifier_changed or the
        classifier suite above would not run on exactly that change."""
        self.assertIn("needs.changes.outputs.infra_classifier_changed == 'true'",
                      _condition(_jobs()["python-tests"]))

    def test_the_classifier_scripts_have_a_change_detection_flag(self):
        """The changes job must set infra_classifier_changed from the classifier
        scripts, and set_all_flags_true must include it, following the contract
        every non-layer path flag follows (detection + force-all + consumer)."""
        filter_step = next(s for s in _jobs()["changes"]["steps"]
                           if s.get("id") == "filter")
        run = filter_step["run"]
        self.assertIn("infra_classifier_changed", run)
        for path in ("tools/ci/infra-failure.sh",
                     "tools/ci/rerun-flaky-tests.sh",
                     "tools/tests/test-infra-classification.sh"):
            with self.subTest(path=path):
                self.assertIn(path, run)

    def test_the_flag_also_detects_this_workflow_file(self):
        """The auto-review GATE_JOBS and suppression conditions this very test
        class reads live in analysis.yaml, which is not a .py, so a change to
        that wiring alone would set neither python_changed nor (without this)
        infra_classifier_changed — skipping python-tests and leaving the wiring
        unexercised. analysis.yaml must be in the detection set so a change to
        the classifier's other half runs its wiring tests."""
        filter_step = next(s for s in _jobs()["changes"]["steps"]
                           if s.get("id") == "filter")
        run = filter_step["run"]
        infra_block = run.split("INFRA_FILES=", 1)[1].split("ISO_FILES=", 1)[0]
        self.assertIn(".github/workflows/analysis.yaml", infra_block)


if __name__ == "__main__":
    unittest.main()
