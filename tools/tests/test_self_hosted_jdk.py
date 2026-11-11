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
_VERIFY_STEP = "Verify JDK version"


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


def _step_named(job, name):
    return [s for s in job.get("steps", []) if s.get("name") == name]


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
        """Each self-hosted lane must actually inspect the installed java and
        fail on a mismatch, not merely mention the variable. A step that only
        echoed EXPECTED_JDK_MAJOR would satisfy a token check while running an
        arbitrary JDK, so this asserts the step probes `java -version`, compares
        the detected major against EXPECTED_JDK_MAJOR, and exits non-zero on a
        mismatch."""
        for name, job in _jobs().items():
            if not _is_self_hosted(job):
                continue
            with self.subTest(job=name):
                verify = _step_named(job, _VERIFY_STEP)
                self.assertEqual(1, len(verify),
                                 f"{name} must have exactly one '{_VERIFY_STEP}' step")
                run = str(verify[0].get("run", ""))
                self.assertIn("java -version", run,
                              f"{name} '{_VERIFY_STEP}' must probe the installed java")
                self.assertIn("EXPECTED_JDK_MAJOR", run,
                              f"{name} '{_VERIFY_STEP}' must compare against EXPECTED_JDK_MAJOR")
                self.assertIn("exit 1", run,
                              f"{name} '{_VERIFY_STEP}' must fail the job on a mismatch")

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
