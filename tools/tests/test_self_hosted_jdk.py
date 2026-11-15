"""Guard: self-hosted CI jobs use the runner's installed JDK, never setup-java.

The self-hosted runners are ephemeral, so ``actions/setup-java`` downloaded a
JDK from the internet on every job even though the runner images and hosts
already ship one; those downloads were timing out (mostly on the AMD/OpenCL
lane). The self-hosted jobs now run the image's or host's JDK and verify its
major version instead. These tests keep a new or copied self-hosted job from
re-introducing ``setup-java``, and keep the expected version defined once.

The macOS fleet's ``fleet.sh install`` preflight is held to the same exact
major runner.sh enforces, so install refuses a host its runner would refuse.

Hosted (``ubuntu-latest``) jobs are exempt: the hosted image serves the JDK
from its tool cache, so they keep ``setup-java``.
"""

import os
import re
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
# The self-hosted lanes select and verify the JDK through one shared composite
# action rather than an inline run step duplicated per job, so the robust
# parsing and the JAVA_HOME export cannot drift between copies.
_SELECT_JDK_ACTION = "./.github/actions/select-jdk"
_ACTION = os.path.join(_REPO_ROOT, ".github", "actions", "select-jdk", "action.yml")


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _jobs():
    return _workflow()["jobs"]


def _runs_on(job):
    labels = job.get("runs-on", [])
    return [labels] if isinstance(labels, str) else list(labels)


def _is_self_hosted(job):
    return "self-hosted" in _runs_on(job)


def _steps_using(job, action):
    return [s for s in job.get("steps", []) if str(s.get("uses", "")).startswith(action)]


class SelfHostedJdkTest(unittest.TestCase):
    def test_at_least_one_self_hosted_job_exists(self):
        """A smoke check so a refactor that renamed every lane cannot make the
        rules below pass vacuously."""
        self.assertTrue([n for n, j in _jobs().items() if _is_self_hosted(j)])

    def test_expected_jdk_major_is_defined_once_at_workflow_level(self):
        """The expected major is declared as a workflow-level env value and
        nowhere else, so a JDK upgrade is a single edit. This asserts the value
        is present and a bare major number without hard-coding which one, so an
        intentional upgrade needs no edit here; and that no job redefines it,
        so there is a single source of truth."""
        env = _workflow().get("env") or {}
        major = str(env.get("EXPECTED_JDK_MAJOR", ""))
        self.assertRegex(major, r"^\d+$",
                         "workflow-level env.EXPECTED_JDK_MAJOR must be a bare major number")
        for name, job in _jobs().items():
            with self.subTest(job=name):
                self.assertNotIn("EXPECTED_JDK_MAJOR", (job.get("env") or {}),
                                 f"{name} must not redefine EXPECTED_JDK_MAJOR; "
                                 "it is defined once at the workflow level")

    def test_no_self_hosted_job_uses_setup_java(self):
        for name, job in _jobs().items():
            if not _is_self_hosted(job):
                continue
            with self.subTest(job=name):
                self.assertEqual([], _steps_using(job, "actions/setup-java"))

    def test_every_self_hosted_job_verifies_the_jdk_against_the_expected_major(self):
        """Each self-hosted lane must select and verify its JDK through the one
        shared composite action, passing the workflow-level EXPECTED_JDK_MAJOR.
        Inlining the JDK handling per job is what let the previous shape break —
        it parsed the first line of `java -version`, which on these runners is the
        `Picked up _JAVA_OPTIONS:` notice, and it never exported JAVA_HOME, so the
        macOS Maven launcher ran on a different JDK. Routing every lane through one
        action keeps the robust parsing and the JAVA_HOME export from drifting, and
        the action's own behaviour is pinned by SelectJdkActionTest below. A lane
        that re-inlined its own `run` step would bypass the fix, so this also fails
        on any self-hosted job that carries an inline JDK-probing run step."""
        for name, job in _jobs().items():
            if not _is_self_hosted(job):
                continue
            with self.subTest(job=name):
                uses = [s for s in job.get("steps", [])
                        if str(s.get("uses", "")).rstrip("/") == _SELECT_JDK_ACTION]
                self.assertEqual(1, len(uses),
                                 f"{name} must use the shared {_SELECT_JDK_ACTION} action exactly once")
                major = str(uses[0].get("with", {}).get("expected-major", ""))
                self.assertIn("env.EXPECTED_JDK_MAJOR", major,
                              f"{name} must pass expected-major from env.EXPECTED_JDK_MAJOR")
                inline = [s for s in job.get("steps", [])
                          if "java -version" in str(s.get("run", ""))]
                self.assertEqual([], inline,
                                 f"{name} must not inline a JDK probe; use the shared action")

    def test_setup_java_only_appears_on_hosted_jobs(self):
        for name, job in _jobs().items():
            if _steps_using(job, "actions/setup-java"):
                with self.subTest(job=name):
                    self.assertEqual(["ubuntu-latest"], _runs_on(job))

    def test_hosted_setup_java_consumes_the_workflow_level_major(self):
        """Hosted jobs keep setup-java, but its java-version must come from the
        workflow-level EXPECTED_JDK_MAJOR rather than a second hard-coded value.
        Otherwise an intentional JDK upgrade (which this file documents as a
        single edit to that env value plus a runner image rebuild) would leave
        the hosted build/check lanes pinned to the old major while the
        self-hosted lanes moved, so the single-version contract would be a
        fiction. At least one hosted setup-java step must exist for this guard
        to mean anything."""
        seen = 0
        for name, job in _jobs().items():
            for step in _steps_using(job, "actions/setup-java"):
                seen += 1
                version = str(step.get("with", {}).get("java-version", ""))
                with self.subTest(job=name):
                    self.assertIn("env.EXPECTED_JDK_MAJOR", version,
                                  f"{name} setup-java must take java-version from "
                                  "env.EXPECTED_JDK_MAJOR, not a hard-coded major")
        self.assertGreater(seen, 0,
                           "expected at least one hosted setup-java step to guard")

    def test_no_step_name_hard_codes_a_jdk_major(self):
        """A step name such as 'Set up JDK 17' would keep advertising the old
        major in the run log after EXPECTED_JDK_MAJOR is upgraded, so names
        that mention a JDK major must derive it from the env value too."""
        for name, job in _jobs().items():
            for step in job.get("steps", []):
                with self.subTest(job=name, step=step.get("name")):
                    self.assertNotRegex(str(step.get("name", "")), r"JDK\s+\d",
                                        f"{name} has a step name with a hard-coded "
                                        "JDK major; use ${{ env.EXPECTED_JDK_MAJOR }}")


def _action():
    with open(_ACTION) as f:
        return yaml.safe_load(f)


def _action_run():
    steps = _action()["runs"]["steps"]
    return "\n".join(str(s.get("run", "")) for s in steps)


class SelectJdkActionTest(unittest.TestCase):
    """The shared composite action every self-hosted lane uses. These pin the
    two properties the inline step got wrong: the major version is read robustly
    (never from the first line of `java -version`, which is the `Picked up
    _JAVA_OPTIONS:` notice on these runners), and JAVA_HOME is exported so the
    Maven launcher — on macOS especially, where it otherwise falls back to the
    newest /usr/libexec/java_home — runs on exactly the verified JDK."""

    def test_is_a_composite_action_taking_the_expected_major(self):
        action = _action()
        self.assertEqual("composite", action["runs"]["using"],
                         "select-jdk must be a composite action")
        self.assertIn("expected-major", action.get("inputs", {}),
                      "select-jdk must take an expected-major input")
        self.assertTrue(action["inputs"]["expected-major"].get("required"),
                        "expected-major must be required")

    def test_does_not_parse_the_first_line_of_java_version(self):
        """head -1 of `java -version` is the bug being fixed: it is the
        `Picked up _JAVA_OPTIONS:` line when that variable is set."""
        run = _action_run()
        self.assertNotIn("-version 2>&1 | head -1", run,
                         "must not read the first line of java -version")

    def test_reads_the_version_robustly(self):
        """The major comes from the JDK's release metadata or the reported
        java.specification.version, with the `version \"` line (selected by
        content, not position) as the only `java -version` fallback."""
        run = _action_run()
        self.assertIn("java.specification.version", run,
                      "should read java.specification.version from -XshowSettings")
        self.assertIn('version "', run,
                      "the java -version fallback must select the line containing 'version \"'")

    def test_resolves_macos_java_home_for_the_expected_major(self):
        run = _action_run()
        self.assertIn('/usr/libexec/java_home -v "${expected}"', run,
                      "macOS must resolve the JDK via java_home for the expected major")

    def test_exports_java_home_and_path_so_the_launcher_uses_the_jdk(self):
        run = _action_run()
        self.assertIn('>> "${GITHUB_ENV}"', run, "must export JAVA_HOME to GITHUB_ENV")
        self.assertIn("JAVA_HOME=", run, "must export JAVA_HOME")
        self.assertIn('>> "${GITHUB_PATH}"', run, "must prepend the JDK bin to GITHUB_PATH")

    def test_prints_the_maven_banner(self):
        self.assertIn("mvn -v", _action_run(),
                      "must print the Maven version banner so the log shows the JDK")

    def test_fails_naming_the_runner_when_no_matching_jdk(self):
        run = _action_run()
        self.assertIn("exit 1", run, "must fail when no JDK of the expected major is found")
        self.assertIn("RUNNER_NAME", run, "the failure message must name the runner")


def _fake_jdk_home(parent, major):
    """A stand-in JDK home whose `release` metadata reports the given major.
    The action reads `release` before invoking any `java`, so a bare directory
    with this one file is a complete candidate for the selection logic."""
    home = os.path.join(parent, "jdk-%s" % major)
    os.makedirs(os.path.join(home, "bin"), exist_ok=True)
    with open(os.path.join(home, "release"), "w") as f:
        f.write('JAVA_VERSION="%s.0.2"\n' % major)
    return home


class SelectJdkActionBehaviorTest(unittest.TestCase):
    """Execute the composite action's shell program, not just grep its text.
    The structural tests above pin the shape of the script; these run it against
    stand-in JDK homes and a real JDK and assert what it actually does: which
    home it selects, the JAVA_HOME and PATH it writes to GITHUB_ENV/GITHUB_PATH,
    and that a missing expected major exits non-zero without exporting anything.
    A real JDK of the expected major is required to exercise the success paths
    (the final `mvn -v` banner runs on the selected JDK), so those cases skip
    when the test host does not carry one."""

    @classmethod
    def setUpClass(cls):
        # The action's success path ends in `mvn -v` on the selected JDK, so the
        # behavioural success cases need a real JDK home whose major we know and
        # a Maven on PATH. Resolve them from the host; skip the success cases
        # (not the mismatch case) when either is absent.
        cls._real_home = os.environ.get("JAVA_HOME", "")
        cls._real_major = ""
        release = os.path.join(cls._real_home, "release") if cls._real_home else ""
        if release and os.path.isfile(release):
            with open(release) as f:
                match = re.search(r'JAVA_VERSION="?(\d+)', f.read())
                if match:
                    cls._real_major = match.group(1)
        cls._has_mvn = any(
            os.access(os.path.join(d, "mvn"), os.X_OK)
            for d in os.environ.get("PATH", "").split(os.pathsep) if d
        )

    def _run(self, expected, java_home=None, runner_os="Linux", path_dirs=None):
        """Run the extracted action script in a clean environment and return
        the process result together with the GITHUB_ENV and GITHUB_PATH files
        the action writes. PATH carries /usr/bin:/bin for the script's coreutils
        plus any explicitly requested directories (e.g. a real JDK's bin), so no
        unexpected `java` leaks in from the host."""
        with tempfile.TemporaryDirectory() as workdir:
            github_env = os.path.join(workdir, "github_env")
            github_path = os.path.join(workdir, "github_path")
            open(github_env, "w").close()
            open(github_path, "w").close()
            env = {
                "EXPECTED_JDK_MAJOR": expected,
                "RUNNER_NAME": "test-runner",
                "RUNNER_OS": runner_os,
                "GITHUB_ENV": github_env,
                "GITHUB_PATH": github_path,
                "PATH": os.pathsep.join((path_dirs or []) + ["/usr/bin", "/bin"]),
            }
            if java_home is not None:
                env["JAVA_HOME"] = java_home
            result = subprocess.run(["/bin/bash", "-c", _action_run()],
                                    env=env, capture_output=True, text=True, timeout=120)
            with open(github_env) as f:
                env_out = f.read()
            with open(github_path) as f:
                path_out = f.read()
            return result, env_out, path_out

    def test_selects_the_matching_java_home_and_exports_it(self):
        """When JAVA_HOME already points at a JDK of the expected major, the
        action selects it and exports that home and its bin."""
        if not (self._real_major and self._has_mvn):
            self.skipTest("needs a real JDK home (JAVA_HOME with release) and mvn on PATH")
        result, env_out, path_out = self._run(self._real_major, java_home=self._real_home)
        self.assertEqual(0, result.returncode,
                         "a matching JAVA_HOME must be accepted: %s" % result.stderr)
        self.assertIn("JAVA_HOME=%s" % self._real_home, env_out,
                      "the selected home must be exported to GITHUB_ENV")
        self.assertIn("%s/bin" % self._real_home, path_out,
                      "the selected home's bin must be prepended to GITHUB_PATH")
        self.assertIn("selected JDK major %s" % self._real_major, result.stdout)

    def test_selects_the_java_on_path_when_java_home_is_the_wrong_major(self):
        """A JAVA_HOME of the wrong major must not be accepted; the action falls
        back to the java.home of the `java` on PATH and selects that instead.
        This is the candidate-parsing path the structural tests cannot reach."""
        if not (self._real_major and self._has_mvn):
            self.skipTest("needs a real JDK home (JAVA_HOME with release) and mvn on PATH")
        other = "17" if self._real_major != "17" else "21"
        with tempfile.TemporaryDirectory() as parent:
            wrong_home = _fake_jdk_home(parent, other)
            result, env_out, path_out = self._run(
                self._real_major, java_home=wrong_home,
                path_dirs=[os.path.join(self._real_home, "bin")])
        self.assertEqual(0, result.returncode,
                         "the PATH java of the expected major must be selected: %s" % result.stderr)
        self.assertIn("JAVA_HOME=%s" % self._real_home, env_out,
                      "the PATH java's home, not the mismatched JAVA_HOME, must be exported")
        self.assertNotIn("JAVA_HOME=%s" % wrong_home, env_out,
                         "the wrong-major JAVA_HOME must never be selected")

    def test_fails_nonzero_without_exporting_when_no_jdk_of_the_expected_major(self):
        """With no JDK of the expected major anywhere — not in JAVA_HOME, nor the
        java on PATH — the action exits non-zero, names the runner, and writes
        nothing to GITHUB_ENV/GITHUB_PATH so no later step runs on an unverified
        JDK. The expected major is an impossible one (999) so that the host's own
        `java` on /usr/bin:/bin (which `_run` always leaves on PATH for coreutils)
        can never accidentally satisfy the match and hide the no-match branch."""
        with tempfile.TemporaryDirectory() as parent:
            wrong_home = _fake_jdk_home(parent, "21")
            result, env_out, path_out = self._run("999", java_home=wrong_home)
        self.assertNotEqual(0, result.returncode,
                            "a run with no JDK of the expected major must fail")
        self.assertIn("test-runner", result.stdout + result.stderr,
                      "the failure must name the runner")
        self.assertEqual("", env_out.strip(),
                         "nothing must be exported to GITHUB_ENV on failure")
        self.assertEqual("", path_out.strip(),
                         "nothing must be exported to GITHUB_PATH on failure")


_FLEET = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "fleet.sh")


def _fleet_jdk_preflight():
    """The bash program `fleet.sh install` runs as the runner account to check
    the daemon PATH's tools and JDK, cut from the script so it can be run
    against a stand-in java."""
    with open(_FLEET) as f:
        text = f.read()
    match = re.search(r"missing=\"\$\(sudo .*?/bin/bash -c '(.*?)' _ \$\{REQUIRED_TOOLS\}",
                      text, re.S)
    if match is None:
        raise AssertionError("fleet.sh install no longer has the daemon-PATH tool preflight")
    return match.group(1)


class FleetJdkPreflightTest(unittest.TestCase):
    """`fleet.sh install` must refuse exactly the JDKs runner.sh refuses. A
    looser preflight (it once accepted any major >= 17) passes a host whose
    runner then exits at start-up, so install waits for a registration that
    never arrives instead of reporting the wrong JDK."""

    def _missing(self, version_line, expected):
        with tempfile.TemporaryDirectory() as bindir:
            java = os.path.join(bindir, "java")
            with open(java, "w") as f:
                f.write("#!/bin/sh\necho '%s' >&2\n" % version_line)
            os.chmod(java, 0o755)
            env = {"PATH": bindir + ":/usr/bin:/bin", "EXPECTED_JDK_MAJOR": expected}
            result = subprocess.run(["/bin/bash", "-c", _fleet_jdk_preflight(), "_", "java"],
                                    env=env, capture_output=True, text=True, timeout=30)
            return result.stdout

    def test_matching_major_passes(self):
        self.assertEqual("", self._missing('openjdk version "17.0.12" 2024-07-16', "17"))

    def test_newer_major_is_refused(self):
        missing = self._missing('openjdk version "21.0.2" 2024-01-16', "17")
        self.assertIn("jdk17 exactly", missing)
        self.assertIn('"21.0.2"', missing)

    def test_older_major_is_refused(self):
        self.assertIn("jdk17 exactly", self._missing('openjdk version "11.0.20" 2023-07-18', "17"))

    def test_major_follows_the_configured_expectation(self):
        self.assertEqual("", self._missing('openjdk version "21.0.2" 2024-01-16', "21"))
        self.assertIn("jdk21 exactly", self._missing('openjdk version "17.0.12" 2024-07-16', "21"))

    def test_unparseable_version_is_refused(self):
        self.assertIn("jdk17 exactly", self._missing("no java here", "17"))

    def test_preflight_receives_the_configured_major(self):
        """The preflight runs under `env -i`, so the expectation must be passed
        into that clean environment explicitly, and derived from the env file
        with the same default runner.sh applies."""
        with open(_FLEET) as f:
            text = f.read()
        self.assertIn('EXPECTED_JDK_MAJOR="${ENV_EXPECTED_JDK_MAJOR:-17}"', text)
        self.assertRegex(text, r'env -i [^\n]*\\\n\s+EXPECTED_JDK_MAJOR="\$\{EXPECTED_JDK_MAJOR\}" /bin/bash -c')
        self.assertNotIn("-ge 17", text)


if __name__ == "__main__":
    unittest.main()
