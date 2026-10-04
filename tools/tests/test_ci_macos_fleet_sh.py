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
import pwd
import re
import shutil
import subprocess
import tempfile
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
            r'untrusted_ancestor "\$\{admin_user\}" "\$\{ENV_FILE\}"',
            "cmd_install should screen the env file and every directory above it before sourcing it")

    def test_runner_dir_path_is_walked_for_a_swappable_ancestor(self):
        """RUNNER_DIR may be any absolute path, and the daemon runs
        ${RUNNER_DIR}/run.sh as the runner; a writable or symlinked ancestor
        lets another account swap it, so every component must be checked."""
        self.assertRegex(
            self.src,
            r'untrusted_ancestor "\$\{RUNNER_USER\}" "\$\{RUNNER_DIR\}" sudo',
            "cmd_install should walk RUNNER_DIR's ancestors with untrusted_ancestor")
        self.assertIn('IFS=\'/\' read -r -a parts <<< "${path#/}"', self.src,
                      "the walk should split the path into components")

    def test_status_checks_the_env_file_path_before_sourcing_it(self):
        """status sources the env file as the invoker, exactly as install
        does, so it must hold the file to the same path-trust standard."""
        status = re.search(r"^cmd_status\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = status.find('untrusted_ancestor "$(id -un)" "${ENV_FILE}"')
        source = status.find('read_env "${ENV_FILE}"')
        self.assertNotEqual(-1, check, "status should walk the env file's path")
        self.assertNotEqual(-1, source)
        self.assertLess(check, source, "the check must come before the env file is sourced")

    def test_a_relative_env_file_is_made_absolute_before_the_walk(self):
        """The walk starts at /, so a relative --env would leave every
        directory above the working directory unchecked."""
        self.assertIn('*) ENV_FILE="${PWD}/${ENV_FILE}" ;;', self.src)

    def test_nothing_is_staged_into_the_runner_home_through_sudo(self):
        """The stage directory belongs to the runner, which can plant a
        symlink in it; a root write there (sudo install -o ...) would follow
        that link and could chown or overwrite any file on the host."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIsNone(re.search(r"sudo install\b", install),
                          "staging must not write through sudo install")
        self.assertRegex(self.src, r'sudo -u "\$\{RUNNER_USER\}" /bin/sh -c \'umask 077 && cat > "\$1"')
        for dest in (r'"\$\{STAGE_DIR\}/bin/\$\{script\}" 755',
                     r'"\$\{STAGE_DIR\}/runner.env" 600',
                     r'"\$\{STAGE_DIR\}/\$\{LABEL\}.plist" 644'):
            self.assertRegex(install, r"stage_file " + dest)

    def test_the_runner_log_is_read_as_the_runner(self):
        """The log lives in the runner's stage directory; reading it as root
        would follow a symlink the runner put in its place."""
        self.assertIn('sudo -u "${RUNNER_USER}" tail -n 30 "${LOG_FILE}"', self.src)
        self.assertNotIn('sudo tail', self.src)

    def test_start_bootstraps_the_root_owned_installed_plist(self):
        """start must load the root:wheel plist register-daemon.sh installs under
        /Library/LaunchDaemons, never the runner-writable staged copy."""
        self.assertRegex(
            self.src,
            r'launchctl bootstrap system "\$\{INSTALLED_PLIST\}"',
            "start should bootstrap INSTALLED_PLIST")
        self.assertIn('INSTALLED_PLIST="${DAEMONS_DIR}/${LABEL}.plist"', self.src)
        self.assertIn('DAEMONS_DIR="/Library/LaunchDaemons"', self.src)


def _trust_functions():
    """The source of fleet.sh's path-trust helpers, to run outside the script
    (which refuses to run anywhere but macOS)."""
    with open(_MACOS_FLEET) as f:
        src = f.read()
    return "\n".join(
        re.search(r"^%s\(\) \{.*?^\}" % name, src, re.M | re.S).group(0)
        for name in ("untrusted_path", "untrusted_ancestor"))


class UntrustedAncestorTests(unittest.TestCase):
    """Runs ``untrusted_ancestor`` itself against real directories. Accounts
    other than the test's own cannot be created here, so foreign ownership is
    covered only at the source level above; symlinks, write bits and the walk
    order are exercised for real.

    The walk starts at /, and the directories above a temporary directory are
    the host's, not the test's: /tmp is 1777, and macOS's /var is a symlink.
    So ``untrusted_path`` is wrapped to treat everything above the fixture root
    as trusted, and to apply the real predicate to everything inside it.
    """

    @classmethod
    def setUpClass(cls):
        functions = _trust_functions().replace("untrusted_path() {", "real_untrusted_path() {", 1)
        cls.functions = functions + '''
untrusted_path() {
    case "$2" in
        "${FIXTURE_ROOT}"|"${FIXTURE_ROOT}"/*) real_untrusted_path "$@" ;;
    esac
}'''
        cls.user = pwd.getpwuid(os.geteuid()).pw_name

    def _walk(self, path):
        result = subprocess.run(
            ["bash", "-c", self.functions + '\nuntrusted_ancestor "$1" "$2"', "_", self.user, path],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, FIXTURE_ROOT=self.root))
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="fleet-trust-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.dir = os.path.join(self.root, "ci")
        os.mkdir(self.dir, 0o755)
        os.chmod(self.dir, 0o755)
        self.file = os.path.join(self.dir, ".env")
        with open(self.file, "w") as f:
            f.write("GITHUB_OWNER=x\n")
        os.chmod(self.file, 0o600)

    def test_a_private_file_in_private_directories_is_trusted(self):
        self.assertEqual("", self._walk(self.file))

    def test_a_group_writable_file_is_reported(self):
        os.chmod(self.file, 0o620)
        self.assertEqual(self.file, self._walk(self.file))

    def test_a_world_writable_parent_is_reported_even_when_the_file_is_private(self):
        """The case the file-only check missed: whoever can write the
        directory can rename the file away and put their own in its place."""
        os.chmod(self.dir, 0o777)
        self.addCleanup(os.chmod, self.dir, 0o755)
        self.assertEqual(self.dir, self._walk(self.file))

    def test_the_first_untrusted_component_is_the_one_reported(self):
        os.chmod(self.dir, 0o775)
        self.addCleanup(os.chmod, self.dir, 0o755)
        os.chmod(self.file, 0o666)
        self.assertEqual(self.dir, self._walk(self.file))

    def test_a_symlinked_directory_on_the_path_is_reported(self):
        link = os.path.join(self.root, "link")
        os.symlink(self.dir, link)
        self.assertEqual(link, self._walk(os.path.join(link, ".env")))

    def test_a_symlinked_file_is_reported(self):
        link = os.path.join(self.dir, "linked.env")
        os.symlink(self.file, link)
        self.assertEqual(link, self._walk(link))

    def test_a_missing_tail_ends_the_walk_at_the_last_trusted_directory(self):
        """A RUNNER_DIR that install has not created yet is fine, so long as
        the directory it will be created in is trusted."""
        self.assertEqual("", self._walk(os.path.join(self.dir, "missing", "deeper")))

    def test_a_missing_tail_below_an_untrusted_directory_is_still_reported(self):
        os.chmod(self.dir, 0o757)
        self.addCleanup(os.chmod, self.dir, 0o755)
        self.assertEqual(self.dir, self._walk(os.path.join(self.dir, "missing")))

    def test_repeated_separators_are_ignored(self):
        self.assertEqual("", self._walk(self.dir + "//.env"))


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
