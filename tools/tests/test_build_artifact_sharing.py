"""Guard: the project is built once per pipeline and shared, not rebuilt per job.

Every job in "Build and Test" that needed the project's artifacts used to run
its own ``mvn install -DskipTests`` — eleven full builds on top of ``build``'s.
``build`` now uploads what it installed as ``maven-installed-artifacts`` and
the other jobs download it (see "Sharing the build" in ``.github/CLAUDE.md``).
These tests keep a new or copied job from quietly bringing the rebuild back,
and keep every job that restores the artifacts ordered after ``build``.
"""

import json
import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_ARTIFACT = "maven-installed-artifacts"
_NO_BUILD_CHECKS = (
    "test-integrity-check",
    "checkstyle-integrity-check",
    "deception-audit",
    "agent-commit-validation",
    "assertion-density-report",
)


def _jobs():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)["jobs"]


def _needs(job):
    needs = job.get("needs", [])
    return [needs] if isinstance(needs, str) else needs


def _steps_using(job, action):
    return [s for s in job.get("steps", []) if str(s.get("uses", "")).startswith(action)]


class BuildArtifactSharingTest(unittest.TestCase):
    def test_only_build_runs_mvn_install(self):
        installing = sorted(name for name, job in _jobs().items()
                            if any("mvn install" in str(s.get("run", "")) for s in job.get("steps", [])))
        self.assertEqual(["build"], installing)

    def test_build_uploads_what_it_installed(self):
        uploads = [s["with"] for s in _steps_using(_jobs()["build"], "actions/upload-artifact")
                   if s["with"].get("name") == _ARTIFACT]
        self.assertEqual(1, len(uploads))
        # build can run on a hosted or a self-hosted runner (see BuildRunnerSelectionTest).
        # It uploads from whatever repository it installed into, as resolved by the
        # shell in the clear step (MAVEN_REPO when the self-hosted "Select Maven
        # repository" step set it, ~/.m2/repository otherwise) and published as that
        # step's output; an action input never reads a GITHUB_ENV value directly.
        self.assertEqual("${{ steps.project-artifacts.outputs.path }}",
                         uploads[0]["path"])
        self.assertEqual("error", uploads[0]["if-no-files-found"])

    def test_every_job_that_restores_the_artifacts_waits_for_build(self):
        restoring = {name: job for name, job in _jobs().items()
                     if any(s["with"].get("name") == _ARTIFACT
                            for s in _steps_using(job, "actions/download-artifact"))}
        self.assertTrue(restoring)
        for name, job in restoring.items():
            with self.subTest(job=name):
                self.assertIn("build", _needs(job))

    def test_branch_checks_that_need_no_build_run_beside_it(self):
        """The git-only branch checks wait for `changes`, not `build`, and keep the
        code_changed gate they used to inherit from it (see "Branch checks that need
        no build" in .github/CLAUDE.md). None of them may use Java or the artifacts."""
        jobs = _jobs()
        for name in _NO_BUILD_CHECKS:
            job = jobs[name]
            with self.subTest(job=name):
                self.assertEqual(["changes"], _needs(job))
                self.assertIn("needs.changes.outputs.code_changed == 'true'", job["if"])
                self.assertEqual([], _steps_using(job, "actions/setup-java"))
                self.assertEqual([], _steps_using(job, "actions/download-artifact"))
                self.assertFalse(any("mvn " in str(s.get("run", "")) for s in job["steps"]))

    def test_jobs_that_run_maven_tests_restore_the_artifacts(self):
        for name, job in _jobs().items():
            runs_tests = any("mvn test" in str(s.get("run", "")) for s in job.get("steps", []))
            if not runs_tests:
                continue
            with self.subTest(job=name):
                names = [s["with"].get("name") for s in _steps_using(job, "actions/download-artifact")]
                self.assertIn(_ARTIFACT, names)

    def test_every_restore_copies_into_the_repository_maven_reads(self):
        """The copy resolves MAVEN_REPO in the shell, falling back to the default repository."""
        for name, job in _jobs().items():
            if name in ("build", "analysis"):
                continue
            downloads = [s for s in _steps_using(job, "actions/download-artifact")
                         if s["with"].get("name") == _ARTIFACT]
            if not downloads:
                continue
            with self.subTest(job=name):
                self.assertEqual("${{ runner.temp }}/maven-installed-artifacts",
                                 downloads[0]["with"]["path"])
                steps = job["steps"]
                restore = steps[steps.index(downloads[0]) + 1]
                self.assertEqual("Restore build artifacts", restore.get("name"))
                self.assertIn('${MAVEN_REPO:-$HOME/.m2/repository}/org/almostrealism', restore["run"])
                self.assertIn("${RUNNER_TEMP}/maven-installed-artifacts/.", restore["run"])

    def test_downloads_that_can_cross_attempts_use_the_rest_route(self):
        """On a retry `build` does not re-run, and auto-resolve runs only on attempt
        3 or later: their artifacts come from an earlier attempt, which the default
        lookup cannot find. The REST route (run-id + token) can, given actions: read."""
        jobs = _jobs()
        for name, job in jobs.items():
            for step in _steps_using(job, "actions/download-artifact"):
                spec = step["with"]
                crosses = (spec.get("name") == _ARTIFACT or name == "auto-resolve")
                if not crosses:
                    continue
                with self.subTest(job=name, artifact=spec.get("name") or spec.get("pattern")):
                    self.assertEqual("${{ github.run_id }}", spec.get("run-id"))
                    self.assertEqual("${{ secrets.GITHUB_TOKEN }}", spec.get("github-token"))
                    self.assertEqual("read", (job.get("permissions") or {}).get("actions"))

    def test_every_upload_replaces_an_earlier_attempts_artifact(self):
        """upload-artifact@v4 refuses a name already used in the run unless told to
        overwrite. A re-run job re-uploads the same names, so without it the
        upload fails (a new failed job for the retry gate to see) and the report
        from the earlier attempt is the one auto-resolve reads on attempt 3."""
        for name, job in _jobs().items():
            for step in _steps_using(job, "actions/upload-artifact"):
                with self.subTest(job=name, artifact=step["with"].get("name")):
                    self.assertIs(True, step["with"].get("overwrite"))

    def test_isolated_repositories_export_their_path(self):
        """The restore targets MAVEN_REPO wherever a job moves its Maven repository."""
        for name, job in _jobs().items():
            for step in job.get("steps", []):
                run = str(step.get("run", ""))
                if "maven.repo.local" in run:
                    with self.subTest(job=name):
                        self.assertIn("MAVEN_REPO=", run)


def _step_named(job, name):
    for s in job.get("steps", []):
        if s.get("name") == name:
            return s
    return None


class BuildRunnerSelectionTest(unittest.TestCase):
    """`build` is the only dual-mode job: its runner is chosen by the
    BUILD_RUNS_ON repository variable (a self-hosted move the owner can make and
    undo without a commit), defaulting to GitHub-hosted ubuntu-latest, and its
    JDK/Maven/clear/upload steps are correct on either runner. These pin that
    wiring so a future edit cannot quietly hard-code the runner or break one of
    the two environments. See "What the `build` job covers" and "The
    BUILD_RUNS_ON repository variable" in .github/CLAUDE.md."""

    def setUp(self):
        self.build = _jobs()["build"]

    def test_runs_on_is_the_variable_with_a_hosted_fallback(self):
        """A JSON label list from the variable, parsed with fromJSON, falling back
        to ubuntu-latest when unset — so an offline self-hosted runner cannot queue
        build forever (the variable is cleared to revert), and today's hosted
        behaviour is the default."""
        runs_on = self.build["runs-on"]
        self.assertIn("vars.BUILD_RUNS_ON", runs_on)
        self.assertIn("fromJSON(vars.BUILD_RUNS_ON)", runs_on)
        self.assertIn("'ubuntu-latest'", runs_on)

    def test_fork_pull_requests_never_select_the_self_hosted_runner(self):
        """build runs the pull request's head; a self-hosted runner holds the
        runner-registration token and a persistent Maven repository, so the
        variable may only take effect for a non-pull_request event or a head in
        this repository. The guard must sit between the variable check and
        fromJSON, so a fork PR falls through to 'ubuntu-latest'."""
        runs_on = " ".join(str(self.build["runs-on"]).split())
        guard = ("(github.event_name != 'pull_request' || "
                 "github.event.pull_request.head.repo.full_name == github.repository)")
        self.assertIn(guard, runs_on)
        self.assertLess(runs_on.index("vars.BUILD_RUNS_ON &&"), runs_on.index(guard))
        self.assertLess(runs_on.index(guard), runs_on.index("fromJSON(vars.BUILD_RUNS_ON)"))
        self.assertLess(runs_on.index("fromJSON(vars.BUILD_RUNS_ON)"),
                        runs_on.index("|| 'ubuntu-latest'"))

    def test_runs_on_selects_by_event_and_head_repository(self):
        """Evaluate the runs-on expression for each case: unset variable, fork PR,
        same-repository PR, and a non-PR event (deployment, push, dispatch)."""
        labels = '["self-hosted","linux","ar-build"]'
        cases = [
            ("", "pull_request", "almostrealism/common", "ubuntu-latest"),
            ("", "deployment", None, "ubuntu-latest"),
            (labels, "pull_request", "someone/common", "ubuntu-latest"),
            (labels, "pull_request", "almostrealism/common", ["self-hosted", "linux", "ar-build"]),
            (labels, "deployment", None, ["self-hosted", "linux", "ar-build"]),
            (labels, "push", None, ["self-hosted", "linux", "ar-build"]),
        ]
        for variable, event, head, expected in cases:
            with self.subTest(variable=variable, event=event, head=head):
                self.assertEqual(expected, _evaluate_runs_on(
                    self.build["runs-on"], variable, event, head, "almostrealism/common"))

    def test_jdk_is_selected_by_runner_environment(self):
        """Hosted downloads the JDK with setup-java; self-hosted selects the
        shipped JDK with the shared select-jdk action (as the test lanes do)."""
        setup = _step_named(self.build, "Set up JDK ${{ env.EXPECTED_JDK_MAJOR }}")
        self.assertIsNotNone(setup, "build must keep setup-java for the hosted runner")
        self.assertTrue(str(setup.get("uses", "")).startswith("actions/setup-java"))
        self.assertEqual("${{ runner.environment == 'github-hosted' }}",
                         _wrap(setup["if"]))

        select = _step_named(self.build, "Select and verify JDK")
        self.assertIsNotNone(select, "build must use select-jdk on the self-hosted runner")
        self.assertEqual("./.github/actions/select-jdk", str(select.get("uses", "")).rstrip("/"))
        self.assertEqual("${{ env.EXPECTED_JDK_MAJOR }}", select["with"]["expected-major"])
        self.assertEqual("${{ runner.environment == 'self-hosted' }}",
                         _wrap(select["if"]))

    def test_maven_repo_is_selected_by_runner_environment(self):
        """Hosted restores the deliberately-built GitHub-backed cache; self-hosted
        prefers its host's shared cache via select-maven-repo (exports MAVEN_REPO)."""
        restore = _step_named(self.build, "Restore Maven dependencies")
        self.assertIsNotNone(restore, "build must restore the hosted Maven cache")
        self.assertEqual("./.github/actions/restore-maven-cache",
                         str(restore.get("uses", "")).rstrip("/"))
        self.assertEqual("${{ runner.environment == 'github-hosted' }}",
                         _wrap(restore["if"]))

        select = _step_named(self.build, "Select Maven repository")
        self.assertIsNotNone(select, "build must select the Maven repo on a self-hosted runner")
        self.assertEqual("./.github/actions/select-maven-repo",
                         str(select.get("uses", "")).rstrip("/"))
        self.assertEqual("${{ runner.environment == 'self-hosted' }}",
                         _wrap(select["if"]))

    def test_clear_step_targets_the_repository_maven_reads(self):
        """Clearing org/almostrealism must hit the per-runner MAVEN_REPO where the
        self-hosted step set it (never the shared cache root another runner uses),
        else ~/.m2/repository — the same resolution the restore steps use."""
        clear = _step_named(self.build, "Clear previously installed project artifacts")
        self.assertIsNotNone(clear)
        self.assertIn('${MAVEN_REPO:-$HOME/.m2/repository}/org/almostrealism', clear["run"])

    def test_clear_step_publishes_the_path_the_upload_reads(self):
        """The clear step resolves the repository once and publishes it as its
        `path` output, which is what the upload names — so the cleared, installed
        and uploaded trees are one and the same on either runner."""
        clear = _step_named(self.build, "Clear previously installed project artifacts")
        self.assertEqual("project-artifacts", clear.get("id"))
        self.assertIn('rm -rf "${path}"', clear["run"])
        self.assertIn('echo "path=${path}" >> "${GITHUB_OUTPUT}"', clear["run"])
        upload = _step_named(self.build, "Upload installed artifacts")
        self.assertEqual("${{ steps.project-artifacts.outputs.path }}", upload["with"]["path"])

    def test_build_still_only_installs_and_uploads(self):
        """build stays the critical-path minimum: mvn install -DskipTests and the
        artifact upload, no tests and no extra Maven phases."""
        mvn = [str(s.get("run", "")) for s in self.build["steps"] if "mvn " in str(s.get("run", ""))]
        self.assertEqual(1, len(mvn), "build must run exactly one mvn invocation")
        self.assertIn("mvn install -DskipTests", mvn[0])
        self.assertNotIn("mvn test", mvn[0])


def _evaluate_runs_on(expression, variable, event_name, head_repo, repository):
    """Evaluate a GitHub Actions runs-on expression built from &&, ||, ==, !=,
    string literals, fromJSON and the contexts build's runs-on reads. GitHub's
    && and || return an operand, exactly as Python's `and`/`or` do, and an empty
    string and null are falsy in both, so a token-for-token translation gives
    the value the runner would receive."""
    text = " ".join(str(expression).split())
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2]
    names = {
        "vars.BUILD_RUNS_ON": "build_runs_on",
        "github.event.pull_request.head.repo.full_name": "head_repo",
        "github.event_name": "event_name",
        "github.repository": "repository",
        "fromJSON": "from_json",
    }
    for source, target in names.items():
        text = text.replace(source, target)
    text = text.replace("&&", " and ").replace("||", " or ")
    namespace = {
        "build_runs_on": variable,
        "head_repo": head_repo,
        "event_name": event_name,
        "repository": repository,
        "from_json": json.loads,
    }
    return eval(text, {"__builtins__": {}}, namespace)  # noqa: S307 - fixed workflow text


def _wrap(value):
    """GitHub normalises a bare job/step `if` expression by wrapping it in
    ${{ }}; yaml.safe_load gives it back as the raw string, so compare against
    the wrapped form regardless of how it was written in the file."""
    text = str(value).strip()
    if text.startswith("${{") and text.endswith("}}"):
        return text
    return "${{ %s }}" % text


if __name__ == "__main__":
    unittest.main()
