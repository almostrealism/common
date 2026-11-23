"""Tests for ``.claude/hooks/mvn-artifact-staleness.py``.

The hook warns before a Maven or test-runner call when project artifacts in
the local repository predate the last branch switch. It once read only
``~/.m2/repository/<group>/...`` while a FlowTree job's Maven installs into a
split local repository (``MAVEN_ARGS`` sets the local prefix), so inside every
job it reported all modules stale right after a fresh install. These tests run
the hook as a process against a temporary project, a temporary ``HOME`` and a
controlled Maven environment, and check that it judges the directory Maven
will actually use, names that directory, separates a fresh empty install space
from stale artifacts, still warns about real staleness, and describes the
scope of a ``-pl ... -am`` build correctly.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_HOOK = os.path.join(_REPO_ROOT, ".claude", "hooks", "mvn-artifact-staleness.py")

GROUP = "org.example"
VERSION = "1.0"
MODULES = ("mod-a", "mod-b")
SPLIT_ARGS = ("-Daether.enhancedLocalRepository.split=true "
              "-Daether.enhancedLocalRepository.localPrefix=flowtree-jobs/job-a")

_POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  {parent}
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>{packaging}</packaging>
  {modules}
</project>
"""


def _git(project, *args):
    subprocess.run(["git", *args], cwd=project, check=True, capture_output=True,
                   timeout=30)


class StalenessHookTest(unittest.TestCase):
    """Runs the hook in a temporary project with a known branch switch."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.home = os.path.join(self.tmp, "home")
        self.project = os.path.join(self.tmp, "project")
        os.makedirs(self.home)
        os.makedirs(self.project)
        self._write_pom(self.project, "parent", "pom",
                        "<modules>" + "".join(f"<module>{m}</module>" for m in MODULES)
                        + "</modules>")
        for module in MODULES:
            directory = os.path.join(self.project, module)
            os.makedirs(directory)
            self._write_pom(directory, module, "jar", "")
        _git(self.project, "init", "-q", "-b", "main")
        _git(self.project, "-c", "user.name=t", "-c", "user.email=t@example.com",
             "commit", "-q", "--allow-empty", "-m", "init")
        _git(self.project, "checkout", "-q", "-b", "feature")
        self.switch_time = time.time()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_pom(self, directory, artifact, packaging, modules):
        with open(os.path.join(directory, "pom.xml"), "w") as f:
            f.write(_POM.format(parent="", group=GROUP, artifact=artifact,
                                version=VERSION, packaging=packaging, modules=modules))

    def default_repository(self):
        return os.path.join(self.home, ".m2", "repository")

    def install(self, root, stale, modules=MODULES + ("parent",)):
        """Writes installed artifacts under root, before or after the branch switch."""
        when = self.switch_time - 3600 if stale else self.switch_time + 60
        for artifact in modules:
            base = os.path.join(root, *GROUP.split("."), artifact, VERSION)
            os.makedirs(base, exist_ok=True)
            extension = "pom" if artifact == "parent" else "jar"
            for name in (f"{artifact}-{VERSION}.{extension}", "_remote.repositories"):
                path = os.path.join(base, name)
                with open(path, "w") as f:
                    f.write("x")
                os.utime(path, (when, when))

    def run_hook(self, command=None, tool="Bash", **maven_env):
        """Runs the hook and returns (additionalContext, systemMessage), or None if silent."""
        env = {k: v for k, v in os.environ.items()
               if k not in ("MAVEN_ARGS", "MAVEN_OPTS", "FLOWTREE_MAVEN_LOCAL_REPOSITORY")}
        env.update(maven_env)
        env["HOME"] = self.home
        env["CLAUDE_PROJECT_DIR"] = self.project
        payload = {"tool_name": tool, "tool_input": {"command": command} if command else {}}
        result = subprocess.run([sys.executable, _HOOK], input=json.dumps(payload),
                                capture_output=True, text=True, env=env, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        if not result.stdout.strip():
            return None
        out = json.loads(result.stdout)
        return out["hookSpecificOutput"]["additionalContext"], out["systemMessage"]

    def assertCurrent(self, report, root):
        context, summary = report
        self.assertIn(f"Maven artifact check in {root}", context)
        self.assertIn("all 3 installed module artifacts are newer", context)
        self.assertNotIn("STALE", context + summary)
        self.assertIn(root, summary)

    def assertStale(self, report, root):
        context, summary = report
        self.assertIn("*** STALE INSTALLED ARTIFACTS (3 of 3", context)
        self.assertIn(f"Looked in {root}", context)
        self.assertIn(f"STALE INSTALLED ARTIFACTS in {root}", summary)

    def test_default_layout_current(self):
        root = self.default_repository()
        self.install(root, stale=False)
        report = self.run_hook("mvn install -DskipTests")
        self.assertCurrent(report, root)
        self.assertIn("the default local repository", report[0])
        self.assertNotIn("~/.m2", report[0])

    def test_default_layout_stale_still_warns(self):
        root = self.default_repository()
        self.install(root, stale=True)
        self.assertStale(self.run_hook("mvn compile"), root)

    def test_split_layout_from_maven_args_judges_the_job_space(self):
        """A fresh job space is current even when the shared unsplit copies are old."""
        self.install(self.default_repository(), stale=True)
        job_root = os.path.join(self.default_repository(), "flowtree-jobs", "job-a")
        self.install(job_root, stale=False)
        report = self.run_hook("mvn test -pl mod-b", MAVEN_ARGS=SPLIT_ARGS)
        self.assertCurrent(report, job_root)
        self.assertIn("split local repository from MAVEN_ARGS", report[0])

    def test_split_layout_stale_job_space_still_warns(self):
        job_root = os.path.join(self.default_repository(), "flowtree-jobs", "job-a")
        self.install(job_root, stale=True)
        self.assertStale(self.run_hook("mvn compile", MAVEN_ARGS=SPLIT_ARGS), job_root)

    def test_split_layout_for_the_test_runner_tool(self):
        """The test runner's Maven inherits MAVEN_ARGS, so the hook judges the job space."""
        job_root = os.path.join(self.default_repository(), "flowtree-jobs", "job-a")
        self.install(job_root, stale=True)
        context, _ = self.run_hook(tool="mcp__ar-test-runner__start_test_run",
                                   MAVEN_ARGS=SPLIT_ARGS)
        self.assertIn(f"Looked in {job_root}", context)
        self.assertIn("The ar-test-runner runs Maven in its own process", context)

    def test_repo_local_from_maven_opts(self):
        relocated = os.path.join(self.tmp, "ci-repo")
        self.install(self.default_repository(), stale=True)
        self.install(relocated, stale=False)
        report = self.run_hook("mvn package", MAVEN_OPTS=f"-Xmx1g -Dmaven.repo.local={relocated}")
        self.assertCurrent(report, relocated)
        self.assertIn("maven.repo.local from MAVEN_OPTS", report[0])

    def test_repo_local_from_command_line_overrides_environment(self):
        from_env = os.path.join(self.tmp, "env-repo")
        on_line = os.path.join(self.tmp, "line-repo")
        self.install(from_env, stale=False)
        self.install(on_line, stale=True)
        context, _ = self.run_hook(f"mvn compile -Dmaven.repo.local={on_line}",
                                   MAVEN_OPTS=f"-Dmaven.repo.local={from_env}")
        self.assertIn(f"Looked in {on_line} (maven.repo.local from the command line)", context)
        self.assertIn(f"mvn clean install -DskipTests -Dmaven.repo.local={on_line}", context)

    def test_repo_local_with_split_combines_both(self):
        relocated = os.path.join(self.tmp, "ci-repo")
        job_root = os.path.join(relocated, "flowtree-jobs", "job-a")
        self.install(job_root, stale=False)
        report = self.run_hook(f"mvn verify -Dmaven.repo.local={relocated}",
                               MAVEN_ARGS=SPLIT_ARGS)
        self.assertCurrent(report, job_root)

    def test_nothing_written_yet_is_not_reported_as_stale(self):
        self.install(self.default_repository(), stale=True)
        job_root = os.path.join(self.default_repository(), "flowtree-jobs", "job-a")
        context, summary = self.run_hook("mvn test -pl mod-a", MAVEN_ARGS=SPLIT_ARGS)
        self.assertIn(f"nothing is installed yet in {job_root}", context)
        self.assertIn("fresh install space", context)
        self.assertNotIn("STALE", context + summary)
        self.assertNotIn("NOT INSTALLED", context)
        self.assertIn("mvn clean install -DskipTests", context)
        self.assertLess(len(context.splitlines()), 3)

    def test_partially_installed_space_names_what_is_missing(self):
        root = self.default_repository()
        self.install(root, stale=False, modules=("parent", "mod-a"))
        context, _ = self.run_hook("mvn compile")
        self.assertIn("all 2 installed module artifacts are newer", context)
        self.assertIn("Not installed there: mod-b.", context)

    def test_also_make_describes_a_partial_reactor(self):
        root = self.default_repository()
        self.install(root, stale=True)
        context, _ = self.run_hook("mvn install -DskipTests -pl mod-b -am")
        self.assertNotIn("full reactor", context)
        self.assertNotIn("refresh all of them", context)
        self.assertIn("This command rebuilds and installs only `mod-b` and the modules "
                      "they depend on (`-am`).", context)
        self.assertIn(f"Every other module still resolves its artifact from {root}.", context)
        self.assertIn("mvn clean install -DskipTests", context)

    def test_projects_without_also_make_warns_about_dependencies(self):
        self.install(self.default_repository(), stale=True)
        context, _ = self.run_hook("mvn test -pl mod-b")
        self.assertIn("This command rebuilds only `mod-b`.", context)
        self.assertIn("Their dependencies are NOT rebuilt (no `-am`)", context)

    def test_full_reactor_install_says_it_refreshes(self):
        self.install(self.default_repository(), stale=True)
        context, _ = self.run_hook("mvn clean install -DskipTests")
        self.assertIn("builds and installs every module in the reactor", context)

    def test_inline_env_assignment_preserved_in_refresh_command(self):
        """A `MAVEN_OPTS=... mvn ...` prefix selects the repository for that run only.

        The refresh command the warning prints must re-emit that inline
        assignment, or pasting it into a plain shell would reinstall into the
        ambient default repository instead of the stale directory judged here.
        """
        relocated = os.path.join(self.tmp, "inline-repo")
        self.install(relocated, stale=True)
        context, _ = self.run_hook(f"MAVEN_OPTS=-Dmaven.repo.local={relocated} mvn compile")
        self.assertIn(f"Looked in {relocated}", context)
        self.assertIn(f"MAVEN_OPTS=-Dmaven.repo.local={relocated} mvn clean install -DskipTests",
                      context)

    def test_non_maven_command_is_silent(self):
        self.assertIsNone(self.run_hook("ls -la"))

    def test_missing_maven_repository_helper_degrades_quietly(self):
        """A checkout without tools/mcp/common must not crash the Bash command.

        The hook resolves its helper relative to its own file. Running a copy
        placed where ``../../tools/mcp/common/maven_repository`` does not exist
        reproduces the ImportError, and the hook must exit 0 with no output
        rather than printing a traceback on every Bash call.
        """
        isolated_dir = os.path.join(self.tmp, "isolated_hooks")
        os.makedirs(isolated_dir)
        isolated_hook = os.path.join(isolated_dir, "mvn-artifact-staleness.py")
        shutil.copyfile(_HOOK, isolated_hook)
        env = {k: v for k, v in os.environ.items()
               if k not in ("MAVEN_ARGS", "MAVEN_OPTS", "FLOWTREE_MAVEN_LOCAL_REPOSITORY")}
        env["HOME"] = self.home
        env["CLAUDE_PROJECT_DIR"] = self.project
        payload = {"tool_name": "Bash", "tool_input": {"command": "mvn install"}}
        result = subprocess.run([sys.executable, isolated_hook], input=json.dumps(payload),
                                capture_output=True, text=True, env=env, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout.strip())
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
