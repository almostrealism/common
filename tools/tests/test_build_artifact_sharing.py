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
    select-build-runner job, which asks the FlowTree controller for the first
    idle self-hosted fleet carrying ar-build and falls open to ubuntu-latest, and
    its JDK/Maven/clear/upload steps are correct on either runner. These pin that
    wiring so a future edit cannot quietly hard-code the runner or break one of
    the two environments. See "What the `build` job covers" and "Controller-driven
    build-runner selection" in .github/CLAUDE.md."""

    def setUp(self):
        self.build = _jobs()["build"]
        self.selector = _jobs()["select-build-runner"]

    def test_runs_on_comes_from_the_selector_job(self):
        """build parses the selector's runs_on output with fromJSON, and lists the
        selector in needs so it is available — so an offline self-hosted fleet
        cannot queue build forever (the selector falls open to hosted)."""
        self.assertEqual("${{ fromJSON(needs.select-build-runner.outputs.runs_on) }}",
                         _wrap(self.build["runs-on"]))
        self.assertIn("select-build-runner", _needs(self.build))

    def test_selector_is_hosted_and_checks_out_no_pull_request_code(self):
        """The selector holds the controller credential, so it runs on a hosted
        runner, checks out nothing (no PR code), and exposes runs_on."""
        self.assertEqual("ubuntu-latest", self.selector["runs-on"])
        self.assertEqual("${{ steps.select.outputs.runs_on }}",
                         _wrap(self.selector["outputs"]["runs_on"]))
        self.assertEqual([], _steps_using(self.selector, "actions/checkout"))
        self.assertTrue(all("checkout" not in str(s.get("uses", ""))
                            for s in self.selector["steps"]))

    def test_selector_fails_open_and_has_an_emergency_override(self):
        """Any problem emits ["ubuntu-latest"]; a repository variable forces hosted;
        the one API call is GET /api/runners/select for the ar-build label."""
        run = next(str(s.get("run", "")) for s in self.selector["steps"]
                   if "runs_on=" in str(s.get("run", "")))
        self.assertIn('["ubuntu-latest"]', run)
        self.assertIn("/api/runners/select", run)
        self.assertIn("required=${REQUIRED_LABEL}", run)
        env = next(s.get("env", {}) for s in self.selector["steps"] if s.get("id") == "select")
        self.assertEqual("ar-build", env["REQUIRED_LABEL"])
        self.assertIn("BUILD_FORCE_HOSTED", str(env["FORCE_HOSTED"]))
        # It must not fail build: no `set -e` that would abort on a failed curl.
        self.assertNotIn("set -euo pipefail", run)

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
