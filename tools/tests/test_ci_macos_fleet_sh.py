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


class MacosFleetSecurityTests(unittest.TestCase):
    """The install path needs sudo and launchd, so these guard the security
    properties of ``fleet.sh`` at the source level instead - the same way the
    template tests read ``fleet.sh`` rather than running it. Each one fails if a
    future edit reopens a credential leak or a path-trust hole a reviewer closed.
    """

    def setUp(self):
        with open(_MACOS_FLEET) as f:
            self.src = f.read()

    def test_github_token_never_reaches_a_curl_command_line(self):
        """`ps` shows every process's arguments to every account on the host,
        so the token must travel through a curl config file on stdin, never in
        a `-H` argument where the runner account could read it."""
        self.assertIsNone(
            re.search(r'-H\s+"Authorization: token \$\{ENV_GITHUB_PAT\}"', self.src),
            "GITHUB_PAT is passed in a curl -H argument, exposing it via `ps`")
        self.assertIn("--config -", self.src,
                      "the token should be fed to curl through a config file on stdin")
        self.assertRegex(
            self.src,
            r'printf \'header = "Authorization: token %s".*\$\{ENV_GITHUB_PAT\}',
            "the Authorization header should be built with printf and piped to curl --config -")

    def test_env_file_trust_check_validates_ownership_not_only_mode_bits(self):
        """A mode-0644 env file owned by the runner account passes a
        write-bit-only check yet is still the runner's to edit before it is
        sourced as the administrator; the check must reject foreign ownership,
        symlinks, and group/world write together."""
        self.assertRegex(self.src, r"untrusted_path\(\)\s*\{",
                         "a reusable path-trust helper should exist")
        self.assertIn("-type l", self.src, "the trust check must reject symlinks")
        self.assertIn("! -user", self.src, "the trust check must reject foreign ownership")
        self.assertRegex(
            self.src,
            r'untrusted_path "\$\{admin_user\}" "\$\{ENV_FILE\}"',
            "cmd_install should screen the env file with untrusted_path before sourcing it")

    def test_runner_dir_path_is_walked_for_a_swappable_ancestor(self):
        """RUNNER_DIR may be any absolute path, and the daemon runs
        ${RUNNER_DIR}/run.sh as the runner; a writable or symlinked ancestor
        lets another account swap it, so every component must be checked."""
        self.assertRegex(
            self.src,
            r'untrusted_path "\$\{RUNNER_USER\}" "\$\{prefix\}" sudo',
            "cmd_install should walk RUNNER_DIR's ancestors with untrusted_path")
        self.assertIn('IFS=\'/\' read -r -a parts <<< "${RUNNER_DIR#/}"', self.src,
                      "the walk should split RUNNER_DIR into path components")

    def test_start_bootstraps_the_root_owned_installed_plist(self):
        """start must load the root:wheel plist register-daemon.sh installs under
        /Library/LaunchDaemons, never the runner-writable staged copy."""
        self.assertRegex(
            self.src,
            r'launchctl bootstrap system "\$\{INSTALLED_PLIST\}"',
            "start should bootstrap INSTALLED_PLIST")
        self.assertIn('INSTALLED_PLIST="${DAEMONS_DIR}/${LABEL}.plist"', self.src)
        self.assertIn('DAEMONS_DIR="/Library/LaunchDaemons"', self.src)


class ShellSyntaxTests(unittest.TestCase):
    """`bash -n` catches a syntax error in any of the installer scripts without
    needing macOS; it runs anywhere and guards every future edit to them."""

    def test_installer_scripts_parse(self):
        for script in (_FLEET, _MACOS_FLEET,
                       os.path.join(_REPO_ROOT, "tools", "ci", "macos", "runner.sh"),
                       os.path.join(_REPO_ROOT, "tools", "ci", "macos", "cpu-watcher.sh")):
            with self.subTest(script=os.path.relpath(script, _REPO_ROOT)):
                result = subprocess.run(["bash", "-n", script],
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)


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
