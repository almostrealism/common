"""Guard: the project is built once per pipeline and shared, not rebuilt per job.

Every job in "Build and Test" that needed the project's artifacts used to run
its own ``mvn install -DskipTests`` — eleven full builds on top of ``build``'s.
``build`` now uploads what it installed as ``maven-installed-artifacts`` and
the other jobs download it (see "Sharing the build" in ``.github/CLAUDE.md``).
These tests keep a new or copied job from quietly bringing the rebuild back,
and keep every job that restores the artifacts ordered after ``build``.
"""

import os
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_ARTIFACT = "maven-installed-artifacts"


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
        self.assertEqual("~/.m2/repository/org/almostrealism", uploads[0]["path"])
        self.assertEqual("error", uploads[0]["if-no-files-found"])

    def test_every_job_that_restores_the_artifacts_waits_for_build(self):
        restoring = {name: job for name, job in _jobs().items()
                     if any(s["with"].get("name") == _ARTIFACT
                            for s in _steps_using(job, "actions/download-artifact"))}
        self.assertTrue(restoring)
        for name, job in restoring.items():
            with self.subTest(job=name):
                self.assertIn("build", _needs(job))

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


if __name__ == "__main__":
    unittest.main()
