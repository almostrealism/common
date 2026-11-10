"""Guard: self-hosted CI jobs use the runner's installed JDK, never setup-java.

The self-hosted runners are ephemeral, so ``actions/setup-java`` downloaded a
JDK from the internet on every job even though the runner images and hosts
already ship one; those downloads were timing out (mostly on the AMD/OpenCL
lane). The self-hosted jobs now run the image's or host's JDK and verify its
major version instead. These tests keep a new or copied self-hosted job from
re-introducing ``setup-java``, and keep the expected version defined once.

Hosted (``ubuntu-latest``) jobs are exempt: the hosted image serves the JDK
from its tool cache, so they keep ``setup-java``.
"""

import os
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


if __name__ == "__main__":
    unittest.main()
