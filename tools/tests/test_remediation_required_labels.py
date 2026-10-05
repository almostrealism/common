"""Guard: an auto-resolve for a Metal-lane failure is pinned to a macOS Node.

An agent asked to fix a ``test-mac`` or ``test-media-mac`` failure on a Node
without Metal cannot run the failing tests, so it can only guess. Nothing
reports that: the submission succeeds and the agent works, on the wrong
machine. ``tools/ci/remediation-required-labels.sh`` decides the labels from
the run's job list; these tests run it against a stub ``gh`` that serves a
fixed list, and check the wiring in ``auto-resolve-submit.yaml``.
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "remediation-required-labels.sh")
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "auto-resolve-submit.yaml")

_MACOS = '{"platform":"macos"}'


class RemediationRequiredLabelsTests(unittest.TestCase):
    """End-to-end runs of the script against a stub ``gh``."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _labels(self, failed_jobs, gh_fails=False):
        """Runs the script with ``gh`` reporting ``failed_jobs`` as failed.

        The stub prints the names as the real ``gh api --jq`` call would after
        its filter, so it exercises the script's own name matching.
        """
        gh = os.path.join(self.tmp, "gh")
        with open(gh, "w") as f:
            f.write("#!/usr/bin/env bash\n")
            if gh_fails:
                f.write("exit 1\n")
            else:
                for name in failed_jobs:
                    f.write("echo %s\n" % repr(name))
        os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)

        env = dict(os.environ)
        env.update({
            "PATH": self.tmp + os.pathsep + env["PATH"],
            "REPO": "owner/repo",
            "RUN_ID": "1",
            "RUN_ATTEMPT": "3",
        })
        env.pop("GITHUB_OUTPUT", None)
        result = subprocess.run(["bash", _SCRIPT], env=env,
                                capture_output=True, text=True)
        out = [line for line in result.stdout.splitlines()
               if line.startswith("required_labels=")]
        return result.returncode, out[0].split("=", 1)[1] if out else None

    def test_a_test_mac_failure_requires_macos(self):
        self.assertEqual((0, _MACOS), self._labels(["test-mac"]))

    def test_a_test_media_mac_failure_requires_macos(self):
        self.assertEqual((0, _MACOS), self._labels(["test-media-mac"]))

    def test_a_matrix_entry_of_a_metal_lane_requires_macos(self):
        self.assertEqual((0, _MACOS), self._labels(["test (1)", "test-mac (2)"]))

    def test_other_lanes_leave_the_node_open(self):
        self.assertEqual((0, ""),
                         self._labels(["test (0)", "test-media", "test-cl",
                                       "test-media-cl", "test-flowtree"]))

    def test_no_failures_leave_the_node_open(self):
        self.assertEqual((0, ""), self._labels([]))

    def test_an_unlistable_run_is_an_error_not_an_unpinned_submission(self):
        code, labels = self._labels([], gh_fails=True)
        self.assertNotEqual(0, code)
        self.assertIsNone(labels)


class AutoResolveSubmitWiringTests(unittest.TestCase):
    """The decided labels must reach the submission."""

    def test_the_submission_carries_the_decided_labels(self):
        with open(_WORKFLOW) as f:
            steps = yaml.safe_load(f)["jobs"]["submit"]["steps"]
        names = [s["name"] for s in steps]
        decide = next(i for i, s in enumerate(steps)
                      if "remediation-required-labels.sh" in s.get("run", ""))
        submit = next(i for i, s in enumerate(steps)
                      if "submit-staged-request.sh" in s.get("run", ""))
        self.assertLess(decide, submit, names)
        self.assertEqual(
            "${{ steps.%s.outputs.required_labels }}" % steps[decide]["id"],
            steps[submit]["env"]["REQUIRED_LABELS"])


if __name__ == "__main__":
    unittest.main()
