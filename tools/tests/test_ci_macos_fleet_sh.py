#!/usr/bin/env python3
# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Tests for ``tools/bin/fleet`` and ``tools/ci/macos/fleet.sh``.

Installing needs sudo and launchd, so the install itself cannot run here.
What can: the dispatcher, the argument guards (which run before anything
touches the host, and before the macOS check, so they hold on Linux too),
and the LaunchDaemon template, which must render into a plist that
``register-daemon.sh`` would accept - every key on its allow-list, and
every placeholder filled.
"""

import os
import platform
import plistlib
import re
import subprocess
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_FLEET = os.path.join(_REPO_ROOT, "tools", "bin", "fleet")
_MACOS_FLEET = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "fleet.sh")
_TEMPLATE = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "com.almostrealism.ci-runner.plist")
_REGISTER = os.path.join(_REPO_ROOT, "flowtree", "runtime", "agent", "macos", "register-daemon.sh")


def setUpModule():
    """Refuse to run as root, where a guard that failed to fire could install."""
    if os.geteuid() == 0:
        raise unittest.SkipTest("fleet tests must not run as root")


def _run(script, *args):
    return subprocess.run(["bash", script, *args], capture_output=True, text=True, timeout=30)


class FleetDispatcherTests(unittest.TestCase):

    def test_help_lists_both_platforms(self):
        result = _run(_FLEET)
        self.assertEqual(0, result.returncode)
        self.assertIn("macos", result.stdout)
        self.assertIn("rocm", result.stdout)

    def test_rejects_an_unknown_platform(self):
        result = _run(_FLEET, "windows", "start")
        self.assertEqual(2, result.returncode)
        self.assertIn("Unknown platform: windows", result.stderr)

    def test_dispatches_to_the_macos_script(self):
        result = _run(_FLEET, "macos", "--help")
        self.assertEqual(0, result.returncode)
        self.assertIn("install", result.stdout)
        self.assertIn("--instance", result.stdout)


class MacosFleetArgumentTests(unittest.TestCase):

    def test_rejects_user_root(self):
        result = _run(_MACOS_FLEET, "install", "--user", "root")
        self.assertEqual(1, result.returncode)
        self.assertIn("must not be root", result.stderr)

    def test_rejects_a_path_in_the_user(self):
        """The user name becomes part of a path under /Users; a separator
        must never reach it."""
        result = _run(_MACOS_FLEET, "install", "--user", "../etc")
        self.assertEqual(1, result.returncode)
        self.assertIn("valid account name", result.stderr)

    def test_rejects_an_instance_that_is_not_a_label_fragment(self):
        """The instance becomes part of a launchd label and of file names."""
        for instance in ("a/b", "Deploy", "-x", "a b"):
            with self.subTest(instance=instance):
                result = _run(_MACOS_FLEET, "start", "--instance", instance)
                self.assertEqual(1, result.returncode)
                self.assertIn("--instance", result.stderr)

    def test_rejects_an_unknown_option(self):
        result = _run(_MACOS_FLEET, "status", "--bogus")
        self.assertEqual(2, result.returncode)
        self.assertIn("unknown argument", result.stderr)

    def test_an_option_without_its_value_is_refused(self):
        result = _run(_MACOS_FLEET, "install", "--user")
        self.assertEqual(2, result.returncode)
        self.assertIn("needs a value", result.stderr)

    @unittest.skipUnless(platform.system() == "Darwin", "needs launchd")
    def test_status_of_an_uninstalled_instance_says_how_to_install(self):
        result = _run(_MACOS_FLEET, "status", "--instance", "fleet-test-never-installed")
        self.assertEqual(1, result.returncode)
        self.assertIn("is not installed", result.stderr)
        self.assertIn("install --instance fleet-test-never-installed", result.stderr)


class RunnerPlistTemplateTests(unittest.TestCase):

    def _render(self):
        with open(_TEMPLATE) as f:
            text = f.read()
        values = {
            "LABEL": "com.almostrealism.ci-runner-test",
            "RUNNER_USER": "worker",
            "RUNNER_HOME": "/Users/worker",
            "RUNNER_DIR": "/Users/worker/actions-runner-test",
            "RUNNER_PATH": "/opt/homebrew/bin:/usr/bin:/bin",
            "STAGE_DIR": "/Users/worker/ci-runner-test",
        }
        for key, value in values.items():
            text = text.replace("@%s@" % key, value)
        return text

    def test_every_placeholder_is_one_fleet_sh_fills(self):
        with open(_TEMPLATE) as f:
            placeholders = set(re.findall(r"@([A-Z_]+)@", f.read()))
        with open(_MACOS_FLEET) as f:
            filled = set(re.findall(r"s\|@([A-Z_]+)@\|", f.read()))
        self.assertEqual(placeholders, filled)

    def test_renders_to_a_plist_register_daemon_accepts(self):
        plist = plistlib.loads(self._render().encode())
        with open(_REGISTER) as f:
            allowed = re.search(r'^ALLOWED_KEYS="([^"]+)"', f.read(), re.M).group(1).split()
        self.assertLessEqual(set(plist), set(allowed))
        self.assertEqual("worker", plist["UserName"])
        self.assertNotIn("GroupName", plist)
        self.assertTrue(plist["Label"].startswith("com.almostrealism."))

    def test_runs_the_staged_runner_with_its_env_file_and_directory(self):
        """fleet.sh reads the runner directory back from argument 3, so the
        argument order is part of its contract with the template."""
        plist = plistlib.loads(self._render().encode())
        self.assertEqual(
            ["/bin/bash", "/Users/worker/ci-runner-test/bin/runner.sh",
             "/Users/worker/ci-runner-test/runner.env", "/Users/worker/actions-runner-test"],
            plist["ProgramArguments"])
        self.assertEqual("/Users/worker/ci-runner-test", plist["WorkingDirectory"])
        self.assertEqual("/Users/worker", plist["EnvironmentVariables"]["HOME"])


if __name__ == "__main__":
    unittest.main()
